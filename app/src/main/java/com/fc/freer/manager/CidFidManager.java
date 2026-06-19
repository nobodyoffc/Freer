package com.fc.freer.manager;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.MMKVDB;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CidFidManager - Manages bidirectional CID-FID mappings with LRU caching and persistent storage
 * Maintains global mappings shared across all identities in the application
 * 
 * Responsibilities:
 * - Cache recently used CID-FID mappings to avoid database lookups
 * - Maintain bidirectional mappings: FID->CID and CID->FID
 * - Provide persistent storage that survives app restarts
 * - Support batch operations for efficiency
 */
public class CidFidManager {
    private static final String TAG = "CidFidManager";
    
    // Maximum number of cached CID-FID mappings
    public static final int MAX_CACHED_CIDS = 1000;
    
    // Map names for storing mappings in HawkDB
    private static final String FID_TO_CID_MAP = "fid_to_cid_map";
    private static final String CID_TO_FID_MAP = "cid_to_fid_map";
    
    private static CidFidManager instance;
    private final LinkedHashMap<String, String> fidToCidCache;
    private final LinkedHashMap<String, String> cidToFidCache;
    private final LocalDB<FcEntity> mappingDB;
    
    /**
     * Private constructor for singleton pattern
     */
    private CidFidManager(Context context) {

        // Initialize persistent storage using MMKVDB for better performance (~100x faster than HawkDB)
        this.mappingDB = new MMKVDB<>(FcEntity.class);
        this.mappingDB.initialize(null, null, null,
            context.getFilesDir().getAbsolutePath(), "cid_fid_mapping");
        this.mappingDB.createMap(FID_TO_CID_MAP, String.class);
        this.mappingDB.createMap(CID_TO_FID_MAP, String.class);
        
        // Create LRU caches with access-order (true parameter) 
        this.fidToCidCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                boolean shouldRemove = size() > MAX_CACHED_CIDS;
                if (shouldRemove) {
                    TimberLogger.d(TAG, "Removing oldest FID->CID mapping from memory cache: %s -> %s", 
                        eldest.getKey(), eldest.getValue());
                }
                return shouldRemove;
            }
        };
        
        this.cidToFidCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                boolean shouldRemove = size() > MAX_CACHED_CIDS;
                if (shouldRemove) {
                    TimberLogger.d(TAG, "Removing oldest CID->FID mapping from memory cache: %s -> %s", 
                        eldest.getKey(), eldest.getValue());
                }
                return shouldRemove;
            }
        };
        
        // Load persisted mappings into memory cache
        loadPersistedMappings();
        
        TimberLogger.d(TAG, "CidFidManager initialized with max cache size: %d, loaded %d FID->CID and %d CID->FID mappings", 
            MAX_CACHED_CIDS, fidToCidCache.size(), cidToFidCache.size());

    }
    
    /**
     * Load persisted mappings from HawkDB into memory cache
     */
    private void loadPersistedMappings() {
        try {
            // Load FID->CID mappings
            Map<String, String> persistedFidToCid = mappingDB.getAllFromMap(FID_TO_CID_MAP);
            if (persistedFidToCid != null && !persistedFidToCid.isEmpty()) {
                for (Map.Entry<String, String> entry : persistedFidToCid.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        fidToCidCache.put(entry.getKey(), entry.getValue());
                    }
                }
                TimberLogger.d(TAG, "Loaded %d FID->CID mappings from persistent storage", persistedFidToCid.size());
            }
            
            // Load CID->FID mappings
            Map<String, String> persistedCidToFid = mappingDB.getAllFromMap(CID_TO_FID_MAP);
            if (persistedCidToFid != null && !persistedCidToFid.isEmpty()) {
                for (Map.Entry<String, String> entry : persistedCidToFid.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        cidToFidCache.put(entry.getKey(), entry.getValue());
                    }
                }
                TimberLogger.d(TAG, "Loaded %d CID->FID mappings from persistent storage", persistedCidToFid.size());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load persisted mappings: %s", e.getMessage());
        }
    }
    
    /**
     * Get singleton instance of CidFidManager
     */
    public static synchronized CidFidManager getInstance(Context context) {
        if (instance == null) {
            instance = new CidFidManager(context);
        }
        return instance;
    }

    /**
     * Get singleton instance of CidFidManager
     */
    public static synchronized CidFidManager getInstance() {
        return instance;
    }
    
    /**
     * Add a single CID-FID mapping
     * 
     * @param fid The FID
     * @param cid The CID
     */
    public synchronized void add(String fid, String cid) {
        if (fid == null || fid.isEmpty() || cid == null || cid.isEmpty()) {
            TimberLogger.w(TAG, "FID or CID is null/empty, cannot add mapping: FID=%s, CID=%s", fid, cid);
            return;
        }
        
        // Add to memory cache
        fidToCidCache.put(fid, cid);
        cidToFidCache.put(cid, fid);
        
        // Persist the mappings asynchronously
        persistMappingAsync(fid, cid);
        
        TimberLogger.d(TAG, "Added CID-FID mapping: %s <-> %s (cache sizes: FID->CID=%d, CID->FID=%d)", 
            fid, cid, fidToCidCache.size(), cidToFidCache.size());
    }
    
    /**
     * Add multiple CID-FID mappings
     * 
     * @param mappings Map of FID->CID mappings to add
     */
    public synchronized void addAll(Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        
        // Add all mappings to memory cache
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String fid = entry.getKey();
            String cid = entry.getValue();
            
            if (fid != null && !fid.isEmpty() && cid != null && !cid.isEmpty()) {
                fidToCidCache.put(fid, cid);
                cidToFidCache.put(cid, fid);
            }
        }
        
        // Persist all mappings asynchronously
        persistMappingsAsync(mappings);
        
        TimberLogger.d(TAG, "Added %d CID-FID mappings (cache sizes: FID->CID=%d, CID->FID=%d)", 
            mappings.size(), fidToCidCache.size(), cidToFidCache.size());
    }
    
    /**
     * Get CID by FID
     * 
     * @param fid The FID to look up
     * @return The corresponding CID, or null if not found
     */
    public synchronized String getCidByFid(String fid) {
        if (fid == null || fid.isEmpty()) {
            return null;
        }
        
        // Check memory cache first
        String cid = fidToCidCache.get(fid);
        if (cid != null) {
            TimberLogger.d(TAG, "Found CID in cache for FID: %s -> %s", fid, cid);
            return cid;
        }
        
        // Check persistent storage
        try {
            cid = mappingDB.getFromMap(FID_TO_CID_MAP, fid);
            if (cid != null) {
                // Add to cache for future lookups
                fidToCidCache.put(fid, cid);
                // Also update the reverse mapping if it exists
                String existingFid = mappingDB.getFromMap(CID_TO_FID_MAP, cid);
                if (existingFid != null) {
                    cidToFidCache.put(cid, existingFid);
                }
                TimberLogger.d(TAG, "Found CID in persistent storage for FID: %s -> %s", fid, cid);
                return cid;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error retrieving CID for FID %s: %s", fid, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Get FID by CID
     * 
     * @param cid The CID to look up
     * @return The corresponding FID, or null if not found
     */
    public synchronized String getFidByCid(String cid) {
        if (cid == null || cid.isEmpty()) {
            return null;
        }
        
        // Check memory cache first
        String fid = cidToFidCache.get(cid);
        if (fid != null) {
            TimberLogger.d(TAG, "Found FID in cache for CID: %s -> %s", cid, fid);
            return fid;
        }
        
        // Check persistent storage
        try {
            fid = mappingDB.getFromMap(CID_TO_FID_MAP, cid);
            if (fid != null) {
                // Add to cache for future lookups
                cidToFidCache.put(cid, fid);
                // Also update the reverse mapping if it exists
                String existingCid = mappingDB.getFromMap(FID_TO_CID_MAP, fid);
                if (existingCid != null) {
                    fidToCidCache.put(fid, existingCid);
                }
                TimberLogger.d(TAG, "Found FID in persistent storage for CID: %s -> %s", cid, fid);
                return fid;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error retrieving FID for CID %s: %s", cid, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Get multiple CIDs by FIDs
     * 
     * @param fids List of FIDs to look up
     * @return Map of FID->CID mappings (only includes found mappings)
     */
    public synchronized Map<String, String> getCidsByFids(List<String> fids) {
        Map<String, String> result = new ConcurrentHashMap<>();
        
        if (fids == null || fids.isEmpty()) {
            return result;
        }
        
        for (String fid : fids) {
            String cid = getCidByFid(fid);
            if (cid != null) {
                result.put(fid, cid);
            }
        }
        
        return result;
    }
    
    /**
     * Get multiple FIDs by CIDs
     * 
     * @param cids List of CIDs to look up
     * @return Map of CID->FID mappings (only includes found mappings)
     */
    public synchronized Map<String, String> getFidsByCids(List<String> cids) {
        Map<String, String> result = new ConcurrentHashMap<>();
        
        if (cids == null || cids.isEmpty()) {
            return result;
        }
        
        for (String cid : cids) {
            String fid = getFidByCid(cid);
            if (fid != null) {
                result.put(cid, fid);
            }
        }
        
        return result;
    }
    
    /**
     * Remove a single CID-FID mapping by FID
     * 
     * @param fid The FID to remove
     * @return true if mapping was removed, false if it wasn't found
     */
    public synchronized boolean removeByFid(String fid) {
        if (fid == null || fid.isEmpty()) {
            return false;
        }
        
        // Get the corresponding CID before removing
        String cid = fidToCidCache.get(fid);
        if (cid == null) {
            // Try to get from persistent storage
            try {
                cid = mappingDB.getFromMap(FID_TO_CID_MAP, fid);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error retrieving CID for FID removal %s: %s", fid, e.getMessage());
            }
        }
        
        boolean removedFromCache = false;
        
        // Remove from memory cache
        if (fidToCidCache.remove(fid) != null) {
            removedFromCache = true;
        }
        if (cid != null && cidToFidCache.remove(cid) != null) {
            removedFromCache = true;
        }
        
        // Remove from persistent storage asynchronously
        if (cid != null) {
            removeMappingAsync(fid, cid);
            TimberLogger.d(TAG, "Removed CID-FID mapping by FID: %s <-> %s", fid, cid);
            return true;
        }
        
        return removedFromCache;
    }
    
    /**
     * Remove a single CID-FID mapping by CID
     * 
     * @param cid The CID to remove
     * @return true if mapping was removed, false if it wasn't found
     */
    public synchronized boolean removeByCid(String cid) {
        if (cid == null || cid.isEmpty()) {
            return false;
        }
        
        // Get the corresponding FID before removing
        String fid = cidToFidCache.get(cid);
        if (fid == null) {
            // Try to get from persistent storage
            try {
                fid = mappingDB.getFromMap(CID_TO_FID_MAP, cid);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error retrieving FID for CID removal %s: %s", cid, e.getMessage());
            }
        }
        
        boolean removedFromCache = false;
        
        // Remove from memory cache
        if (cidToFidCache.remove(cid) != null) {
            removedFromCache = true;
        }
        if (fid != null && fidToCidCache.remove(fid) != null) {
            removedFromCache = true;
        }
        
        // Remove from persistent storage asynchronously
        if (fid != null) {
            removeMappingAsync(fid, cid);
            TimberLogger.d(TAG, "Removed CID-FID mapping by CID: %s <-> %s", cid, fid);
            return true;
        }
        
        return removedFromCache;
    }
    
    /**
     * Remove multiple CID-FID mappings by FIDs
     * 
     * @param fids List of FIDs to remove
     * @return Number of mappings successfully removed
     */
    public synchronized int removeByFids(List<String> fids) {
        if (fids == null || fids.isEmpty()) {
            return 0;
        }
        
        int removedCount = 0;
        for (String fid : fids) {
            if (removeByFid(fid)) {
                removedCount++;
            }
        }
        
        TimberLogger.d(TAG, "Removed %d CID-FID mappings by FIDs", removedCount);
        return removedCount;
    }
    
    /**
     * Remove multiple CID-FID mappings by CIDs
     * 
     * @param cids List of CIDs to remove
     * @return Number of mappings successfully removed
     */
    public synchronized int removeByCids(List<String> cids) {
        if (cids == null || cids.isEmpty()) {
            return 0;
        }
        
        int removedCount = 0;
        for (String cid : cids) {
            if (removeByCid(cid)) {
                removedCount++;
            }
        }
        
        TimberLogger.d(TAG, "Removed %d CID-FID mappings by CIDs", removedCount);
        return removedCount;
    }
    
    /**
     * Persists a single mapping to storage asynchronously
     * 
     * @param fid The FID
     * @param cid The CID
     */
    private void persistMappingAsync(String fid, String cid) {
        new Thread(() -> {
            try {
                mappingDB.putInMap(FID_TO_CID_MAP, fid, cid);
                mappingDB.putInMap(CID_TO_FID_MAP, cid, fid);
                TimberLogger.d(TAG, "Persisted mapping: %s <-> %s", fid, cid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to persist mapping %s <-> %s: %s", fid, cid, e.getMessage());
            }
        }).start();
    }
    
    /**
     * Persists multiple mappings to storage asynchronously
     * 
     * @param mappings Map of FID->CID mappings to persist
     */
    private void persistMappingsAsync(Map<String, String> mappings) {
        new Thread(() -> {
            try {
                // Create reverse mappings
                Map<String, String> reverseMappings = new ConcurrentHashMap<>();
                for (Map.Entry<String, String> entry : mappings.entrySet()) {
                    String fid = entry.getKey();
                    String cid = entry.getValue();
                    if (fid != null && cid != null) {
                        reverseMappings.put(cid, fid);
                    }
                }
                
                // Persist both directions
                mappingDB.putAllInMap(FID_TO_CID_MAP, mappings);
                mappingDB.putAllInMap(CID_TO_FID_MAP, reverseMappings);
                TimberLogger.d(TAG, "Persisted %d mappings", mappings.size());
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to persist %d mappings: %s", mappings.size(), e.getMessage());
            }
        }).start();
    }
    
    /**
     * Removes a mapping from persistent storage asynchronously
     * 
     * @param fid The FID to remove
     * @param cid The CID to remove
     */
    private void removeMappingAsync(String fid, String cid) {
        new Thread(() -> {
            try {
                mappingDB.removeFromMap(FID_TO_CID_MAP, fid);
                mappingDB.removeFromMap(CID_TO_FID_MAP, cid);
                TimberLogger.d(TAG, "Removed mapping from persistent storage: %s <-> %s", fid, cid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to remove mapping from persistent storage %s <-> %s: %s", fid, cid, e.getMessage());
            }
        }).start();
    }
    
    /**
     * Clear all cached mappings from both memory and persistent storage
     */
    public synchronized void clearCache() {
        int previousFidToCidSize = fidToCidCache.size();
        int previousCidToFidSize = cidToFidCache.size();
        
        fidToCidCache.clear();
        cidToFidCache.clear();
        
        // Schedule async clearing of persistent storage
        new Thread(() -> {
            try {
                mappingDB.clearMap(FID_TO_CID_MAP);
                mappingDB.clearMap(CID_TO_FID_MAP);
                TimberLogger.d(TAG, "Cleared persistent mapping cache");
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to clear persistent mapping cache: %s", e.getMessage());
            }
        }).start();
        
        TimberLogger.d(TAG, "Cleared memory mapping cache (removed %d FID->CID and %d CID->FID mappings)", 
            previousFidToCidSize, previousCidToFidSize);
    }
    
    /**
     * Get current memory cache size for FID->CID mappings
     * 
     * @return Number of FID->CID mappings currently cached in memory
     */
    public synchronized int getFidToCidCacheSize() {
        return fidToCidCache.size();
    }
    
    /**
     * Get current memory cache size for CID->FID mappings
     * 
     * @return Number of CID->FID mappings currently cached in memory
     */
    public synchronized int getCidToFidCacheSize() {
        return cidToFidCache.size();
    }
    
    /**
     * Get persistent cache size for FID->CID mappings
     * 
     * @return Number of FID->CID mappings currently cached in persistent storage
     */
    public synchronized int getPersistentFidToCidCacheSize() {
        try {
            return mappingDB.getMapSize(FID_TO_CID_MAP);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to get persistent FID->CID cache size: %s", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get persistent cache size for CID->FID mappings
     * 
     * @return Number of CID->FID mappings currently cached in persistent storage
     */
    public synchronized int getPersistentCidToFidCacheSize() {
        try {
            return mappingDB.getMapSize(CID_TO_FID_MAP);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to get persistent CID->FID cache size: %s", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get cache statistics
     * 
     * @return String containing cache statistics
     */
    public synchronized String getCacheStats() {
        int persistentFidToCidSize = getPersistentFidToCidCacheSize();
        int persistentCidToFidSize = getPersistentCidToFidCacheSize();
        return String.format("CidFidCache: %d/%d FID->CID in memory (%d persisted), %d/%d CID->FID in memory (%d persisted)", 
            fidToCidCache.size(), MAX_CACHED_CIDS, persistentFidToCidSize,
            cidToFidCache.size(), MAX_CACHED_CIDS, persistentCidToFidSize);
    }
}