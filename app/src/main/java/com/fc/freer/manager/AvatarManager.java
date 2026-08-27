package com.fc.freer.manager;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.fc.freer.utils.ToastUtils;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.MMKVDB;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.feature.avatar.AvatarMaker;
import com.fc.fc_ajdk.feature.avatar.GroupAvatarMaker;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AvatarManager - Manages avatar caching with LRU eviction policy and persistent storage
 * Caches recently used avatars to avoid regenerating them repeatedly
 * Uses MMKVDB for persistent storage that survives app restarts (~100x faster than HawkDB)
 */
public class AvatarManager {
    private static final String TAG = "AvatarManager";

    // Maximum number of cached avatars
    public static final int MAX_CACHED_AVATARS = 200;

    // Map name for storing avatars in MMKVDB
    private static final String AVATAR_CACHE_MAP = "avatar_cache";

    private static AvatarManager instance;
    private final Context context;
    private final LinkedHashMap<String, byte[]> avatarCache;
    private final LocalDB<FcEntity> avatarDB;

    /**
     * Private constructor for singleton pattern
     */
    private AvatarManager(Context context) {
        this.context = context.getApplicationContext();

        // Initialize persistent storage using MMKVDB for better performance
        this.avatarDB = new MMKVDB<>(FcEntity.class);
        this.avatarDB.initialize(null, null, null,
            context.getFilesDir().getAbsolutePath(), "avatar_cache");
        this.avatarDB.createMap(AVATAR_CACHE_MAP, byte[].class);
        
        // Create LRU cache with access-order (true parameter) 
        this.avatarCache = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                boolean shouldRemove = size() > MAX_CACHED_AVATARS;
                if (shouldRemove) {
                    TimberLogger.d(TAG, "Removing oldest avatar from memory cache: %s", eldest.getKey());
                    // Also remove from persistent storage when evicted from memory (async)
                    removeFromPersistentStorageAsync(eldest.getKey());
                }
                return shouldRemove;
            }
        };
        
        // Load recently used avatars from persistent storage into memory cache
        loadPersistedAvatars();
        
        TimberLogger.d(TAG, "AvatarManager initialized with max cache size: %d, loaded %d persisted avatars", 
            MAX_CACHED_AVATARS, avatarCache.size());
    }

    private static void setAvatarImage(ImageView avatarView, byte[] avatarBytes) {
        if (avatarBytes != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
            if (bitmap != null) {
                avatarView.setImageBitmap(bitmap);
                return;
            }
        }
        avatarView.setBackgroundColor(Color.LTGRAY);
    }

    public static void showAvatarDialog(Context context, String id) {
        TimberLogger.d(TAG, "showAvatarDialog called with id: %s", id);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_avatar_utils, null);

        TextView idText = dialogView.findViewById(R.id.id_text);
        idText.setText(id);
        idText.setTextColor(ContextCompat.getColor(context, R.color.field_name));

        ImageView avatarView = dialogView.findViewById(R.id.avatar_view);
        byte[] avatarBytes = null;

        try {
            AvatarManager avatarManager = getInstance(context);
            avatarBytes = avatarManager.getAvatar(id);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create avatar: %s", e.getMessage());
            ToastUtils.makeText(context, R.string.failed_to_create_avatar);
        }
        setAvatarImage(avatarView, avatarBytes);

        AlertDialog dialog = builder.setView(dialogView).create();

        byte[] finalAvatarBytes = avatarBytes;
        dialogView.findViewById(R.id.save_button).setOnClickListener(v -> saveAvatarToGallery(context, id, finalAvatarBytes));

        dialogView.findViewById(R.id.ok_button).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private static void saveAvatarToGallery(Context context, String id, byte[] avatarBytes) {
        if (avatarBytes == null) {
            ToastUtils.makeText(context, context.getString(R.string.no_avatar_to_save));
            return;
        }

        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
            String fileName = "avatar_" + id + ".png";
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if(!picturesDir.mkdirs())TimberLogger.e(TAG, "Failed to create directory.");

            File file = new File(picturesDir, fileName);

            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            // Notify the media scanner
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(file);
            mediaScanIntent.setData(contentUri);
            context.sendBroadcast(mediaScanIntent);

            ToastUtils.makeText(context, context.getString(R.string.avatar_saved_to_gallery));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to save avatar: %s", e.getMessage());
            ToastUtils.makeText(context, context.getString(R.string.failed_to_save_avatar));
        }
    }

    /**
     * Loads persisted avatars from HawkDB into memory cache
     * Only loads up to MAX_CACHED_AVATARS most recently used avatars
     */
    private void loadPersistedAvatars() {
        try {
            Map<String, byte[]> persistedAvatars = avatarDB.getAllFromMap(AVATAR_CACHE_MAP);
            if (persistedAvatars != null && !persistedAvatars.isEmpty()) {
                // Add all persisted avatars to memory cache (LRU will handle overflow)
                for (Map.Entry<String, byte[]> entry : persistedAvatars.entrySet()) {
                    if (entry.getValue() != null) {
                        avatarCache.put(entry.getKey(), entry.getValue());
                    }
                }
                TimberLogger.d(TAG, "Loaded %d avatars from persistent storage", persistedAvatars.size());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load persisted avatars: %s", e.getMessage());
        }
    }
    
    /**
     * Get singleton instance of AvatarManager
     */
    public static synchronized AvatarManager getInstance(Context context) {
        if (instance == null) {
            instance = new AvatarManager(context);
        }
        return instance;
    }
    
    /**
     * Get avatar for the given FID/address
     * Checks memory cache first, generates new avatar if not found
     * 
     * @param fid The FID/address to generate avatar for
     * @return Byte array containing the avatar image, or null if generation failed
     */
    public synchronized byte[] getAvatar(String fid) {
        if (fid == null || fid.isEmpty()) {
            TimberLogger.w(TAG, "FID is null or empty, cannot generate avatar");
            return null;
        }
        
        // Check if avatar is already in memory cache
        byte[] cachedAvatar = avatarCache.get(fid);
        if (cachedAvatar != null) {
            TimberLogger.d(TAG, "Avatar found in memory cache for FID: %s", fid);
            return cachedAvatar;
        }
        
        // Avatar not in memory cache, generate it
        TimberLogger.d(TAG, "Avatar not cached, generating for FID: %s", fid);
        try {
            // Initialize AvatarMaker if needed
            AvatarMaker.init(context);
            
            // Generate avatar using AvatarMaker
            byte[] avatarBytes = AvatarMaker.makeAvatar(fid);
            
            if (avatarBytes != null) {
                // Cache the generated avatar in memory
                avatarCache.put(fid, avatarBytes);
                
                // Persist the new avatar for app restart recovery
                persistAvatarAsync(fid, avatarBytes);
                
                TimberLogger.d(TAG, "Avatar generated and cached for FID: %s (memory cache size: %d)", 
                    fid, avatarCache.size());
                return avatarBytes;
            } else {
                TimberLogger.w(TAG, "Failed to generate avatar for FID: %s", fid);
                return null;
            }
            
        } catch (IOException e) {
            TimberLogger.e(TAG, "Error generating avatar for FID %s: %s", fid, e.getMessage());
            return null;
        }
    }
    
    /**
     * Persists a new avatar to storage asynchronously to avoid blocking the UI thread
     * 
     * @param fid The FID of the avatar
     * @param avatarBytes The avatar data to persist
     */
    private void persistAvatarAsync(String fid, byte[] avatarBytes) {
        // Use a background thread to persist the avatar without blocking UI
        new Thread(() -> {
            try {
                avatarDB.putInMap(AVATAR_CACHE_MAP, fid, avatarBytes);
                TimberLogger.d(TAG, "Avatar persisted for FID: %s", fid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to persist avatar for FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }
    
    /**
     * Removes an avatar from persistent storage asynchronously
     * 
     * @param fid The FID of the avatar to remove
     */
    private void removeFromPersistentStorageAsync(String fid) {
        // Use a background thread to remove from persistent storage without blocking UI
        new Thread(() -> {
            try {
                avatarDB.removeFromMap(AVATAR_CACHE_MAP, fid);
                TimberLogger.d(TAG, "Avatar removed from persistent storage for FID: %s", fid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to remove avatar from persistent storage for FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }
    
    /**
     * Get avatar as Bitmap for the given FID/address
     * 
     * @param fid The FID/address to get avatar for
     * @return Bitmap of the avatar, or null if not available
     */
    public Bitmap getAvatarBitmap(String fid) {
        byte[] avatarBytes = getAvatar(fid);
        if (avatarBytes != null) {
            try {
                return BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error converting avatar bytes to bitmap for FID %s: %s", 
                    fid, e.getMessage());
                return null;
            }
        }
        return null;
    }
    
    /**
     * Get the avatar for a <b>group</b> — a room, team or square.
     *
     * <p>Drawn from the group's id, which is the one thing about a group
     * that never changes, and badged with the owner (or a square's last
     * namer). Do not route a group id through {@link #getAvatarBitmap}
     * instead: {@code AvatarMaker} reads a group id as if it were a FID
     * and composites a human face out of it.
     *
     * @param groupId  room id, or the team/square txid
     * @param ownerFid the FID to badge; null or empty draws no badge,
     *                 which is what an unknown owner should look like
     * @param sizePx   side length in pixels
     */
    public Bitmap getGroupAvatarBitmap(String groupId, String ownerFid, int sizePx) {
        Bitmap ownerAvatar = (ownerFid == null || ownerFid.isEmpty())
                ? null : getAvatarBitmap(ownerFid);
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        try {
            return GroupAvatarMaker.makeAvatar(groupId, ownerFid, ownerAvatar, sizePx, night);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to build group avatar for %s: %s", groupId, e.getMessage());
            return null;
        }
    }

    /**
     * Preload avatars for multiple FIDs
     * Useful for bulk loading avatars in the background
     *
     * @param fids Array of FIDs to preload avatars for
     */
    public void preloadAvatars(String[] fids) {
        if (fids == null || fids.length == 0) {
            return;
        }
        
        TimberLogger.d(TAG, "Preloading avatars for %d FIDs", fids.length);
        
        // Use a background thread for preloading to avoid blocking UI
        new Thread(() -> {
            for (String fid : fids) {
                if (fid != null && !fid.isEmpty()) {
                    getAvatar(fid); // This will cache the avatar if not already cached
                }
            }
            TimberLogger.d(TAG, "Finished preloading avatars (cache size: %d)", avatarCache.size());
        }).start();
    }
    
    /**
     * Check if avatar is cached for the given FID (in memory cache only)
     * Since all persisted avatars are loaded into memory at startup, 
     * we only need to check the memory cache
     * 
     * @param fid The FID to check
     * @return true if avatar is cached in memory, false otherwise
     */
    public synchronized boolean isAvatarCached(String fid) {
        return fid != null && avatarCache.containsKey(fid);
    }
    
    /**
     * Remove avatar from memory cache and schedule removal from persistent storage
     * 
     * @param fid The FID to remove avatar for
     * @return true if avatar was removed from memory cache, false if it wasn't cached
     */
    public synchronized boolean removeAvatar(String fid) {
        if (fid != null) {
            boolean removedFromMemory = avatarCache.remove(fid) != null;
            
            if (removedFromMemory) {
                // Schedule async removal from persistent storage
                removeFromPersistentStorageAsync(fid);
                TimberLogger.d(TAG, "Removed avatar from memory cache for FID: %s", fid);
                return true;
            }
        }
        return false;
    }
    
    /**
     * Clear all cached avatars from both memory and persistent storage
     */
    public synchronized void clearCache() {
        int previousMemorySize = avatarCache.size();
        avatarCache.clear();
        
        // Schedule async clearing of persistent storage
        new Thread(() -> {
            try {
                avatarDB.clearMap(AVATAR_CACHE_MAP);
                TimberLogger.d(TAG, "Cleared persistent avatar cache");
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to clear persistent avatar cache: %s", e.getMessage());
            }
        }).start();
        
        TimberLogger.d(TAG, "Cleared memory avatar cache (removed %d avatars)", previousMemorySize);
    }
    
    /**
     * Get current memory cache size
     * 
     * @return Number of avatars currently cached in memory
     */
    public synchronized int getCacheSize() {
        return avatarCache.size();
    }
    
    /**
     * Get persistent cache size
     * 
     * @return Number of avatars currently cached in persistent storage
     */
    public synchronized int getPersistentCacheSize() {
        try {
            return avatarDB.getMapSize(AVATAR_CACHE_MAP);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to get persistent cache size: %s", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get cache statistics
     * 
     * @return String containing cache statistics
     */
    public synchronized String getCacheStats() {
        int persistentSize = getPersistentCacheSize();
        return String.format("AvatarCache: %d/%d in memory, %d persisted", 
            avatarCache.size(), MAX_CACHED_AVATARS, persistentSize);
    }
}