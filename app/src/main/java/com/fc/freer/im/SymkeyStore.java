package com.fc.freer.im;

import android.content.Context;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.DatabaseManager;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secure storage for symmetric keys used in team/room encryption.
 * Keys are stored encrypted with the user's public key (can be decrypted with prikey).
 */
public class SymkeyStore {
    private static final String TAG = "SymkeyStore";
    private static final String DB_NAME = "symkeys";
    
    private LocalDB<SymkeyEntry> db;
    private final String liveFid;
    private byte[] userPrikey;
    private byte[] userPubkey;
    
    // In-memory cache of decrypted keys (thread-safe for cross-thread access)
    private final Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
    
    public SymkeyStore(Context context, String liveFid) {
        this.liveFid = liveFid;
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, SymkeyEntry.class, SymkeyEntry.class);
    }
    
    /**
     * Set the user's private key for encrypting/decrypting symkeys.
     * The public key is derived from the private key for encryption.
     */
    public void setUserPrikey(byte[] prikey) {
        this.userPrikey = prikey;
        this.userPubkey = KeyTools.prikeyToPubkey(prikey);
    }
    
    /**
     * Generate a new symmetric key for a team or room.
     * @param entityId Team or room ID
     * @param version Key version (should be incremented on rotation)
     * @return The new key bytes
     */
    public byte[] generateSymkey(String entityId, long version) {
        if (userPrikey == null || userPubkey == null) {
            TimberLogger.e(TAG, "Cannot generate symkey: user prikey not set");
            return null;
        }
        
        try {
            // Generate 32-byte AES key
            byte[] symkey = new byte[32];
            new SecureRandom().nextBytes(symkey);
            
            // Encrypt with user's pubkey for storage (can be decrypted with user's prikey)
            String cipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptByAsyOneWay(symkey, userPubkey).toJson();
            
            // Store
            SymkeyEntry entry = new SymkeyEntry();
            entry.setId(entityId + "_" + version);
            entry.setEntityId(entityId);
            entry.setVersion(version);
            entry.setCipher(cipher);
            entry.setCreatedAt(System.currentTimeMillis());
            entry.setActive(true);
            
            db.put(entry.getId(),entry);
            
            // Cache
            keyCache.put(entry.getId(), symkey);
            
            return symkey;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to generate symkey: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Store a received symmetric key.
     * @param entityId Team or room ID
     * @param version Key version
     * @param symkey The key bytes
     * @param allowOverwrite If true, overwrite existing key for the same version (for owner-pushed keys)
     * @return true if stored successfully
     */
    public boolean storeSymkey(String entityId, long version, byte[] symkey, boolean allowOverwrite) {
        if (userPrikey == null || userPubkey == null || symkey == null) {
            return false;
        }

        String id = entityId + "_" + version;
        if (!allowOverwrite && db.get(id) != null) {
            TimberLogger.d(TAG, "Symkey already exists for %s v%d, skipping overwrite", entityId, version);
            return true;
        }
        
        try {
            String cipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptByAsyOneWay(symkey, userPubkey).toJson();
            
            SymkeyEntry entry = new SymkeyEntry();
            entry.setId(id);
            entry.setEntityId(entityId);
            entry.setVersion(version);
            entry.setCipher(cipher);
            entry.setCreatedAt(System.currentTimeMillis());
            entry.setActive(true);
            
            db.put(entry.getId(), entry);
            keyCache.put(entry.getId(), symkey);
            
            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to store symkey: %s", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the current (latest version) symmetric key for an entity.
     */
    public byte[] getSymkey(String entityId) {
        SymkeyEntry latest = getLatestEntry(entityId);
        if (latest == null) return null;
        return getSymkey(entityId, latest.getVersion());
    }
    
    /**
     * Get a specific version of a symmetric key.
     */
    public byte[] getSymkey(String entityId, long version) {
        String key = entityId + "_" + version;
        
        // Check cache
        if (keyCache.containsKey(key)) {
            return keyCache.get(key);
        }
        
        // Load from DB
        SymkeyEntry entry = db.get(key);
        if (entry == null || entry.getCipher() == null) {
            return null;
        }

        if (userPrikey == null) {
            TimberLogger.e(TAG, "Cannot decrypt symkey: user prikey not set");
            return null;
        }
        
        try {
            CryptoDataByte parsed = CryptoDataByte.fromJson(entry.getCipher());
            if (parsed != null) {
                TimberLogger.d(TAG, "Stored cipher for %s v%d: alg=%s, type=%s",
                        entityId, version, parsed.getAlg(), parsed.getType());
            }
            CryptoDataByte result = new Decryptor().decrypt(entry.getCipher(), userPrikey);
            if (result == null || result.getCode() != 0 || result.getData() == null) {
                TimberLogger.e(TAG, "Failed to decrypt symkey for %s v%d: code=%s, msg=%s",
                        entityId, version,
                        result != null ? result.getCode() : "null",
                        result != null ? result.getMessage() : "null");
                return null;
            }
            keyCache.put(key, result.getData());
            return result.getData();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to decrypt symkey for %s v%d: %s", entityId, version, e.getMessage());
            return null;
        }
    }
    
    /**
     * Get current key version for an entity.
     */
    public long getCurrentVersion(String entityId) {
        SymkeyEntry latest = getLatestEntry(entityId);
        return latest != null ? latest.getVersion() : 0;
    }
    
    /**
     * Get all versions for an entity (for decrypting old messages).
     */
    public List<Long> getVersionHistory(String entityId) {
        List<SymkeyEntry> entries = getEntriesForEntity(entityId);
        List<Long> versions = new ArrayList<>();
        for (SymkeyEntry entry : entries) {
            versions.add(entry.getVersion());
        }
        return versions;
    }
    
    /**
     * Get all entries for an entity by filtering all entries.
     */
    private List<SymkeyEntry> getEntriesForEntity(String entityId) {
        List<SymkeyEntry> result = new ArrayList<>();
        Map<String, SymkeyEntry> all = db.getAll();
        String prefix = entityId + "_";
        for (Map.Entry<String, SymkeyEntry> entry : all.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }
    
    /**
     * Check if we have the symkey for an entity.
     */
    public boolean hasSymkey(String entityId) {
        return getLatestEntry(entityId) != null;
    }
    
    /**
     * Check if we have a specific version.
     */
    public boolean hasSymkey(String entityId, long version) {
        return db.get(entityId + "_" + version) != null;
    }

    /**
     * What a {@code RequestType.SYMKEY} request is asking for.
     *
     * <p>FIMP4V3 §5.1 and FIMP2V3 §5.2 define the request content as
     * {@code "<entityId>"} for the current version, or
     * {@code "<entityId>:<version>"} for a specific one.
     */
    public static final class Asked {
        public final String entityId;
        /** The version asked for, or null for "whatever you have now". */
        public final Long version;

        Asked(String entityId, Long version) {
            this.entityId = entityId;
            this.version = version;
        }
    }

    /**
     * Parse a symkey request's content into the entity and the version it names.
     *
     * <p><b>The version used to be parsed off and thrown away.</b> This method's
     * caller read the entity id out of {@code "<id>:<version>"} and then answered
     * with {@link #getCurrentVersion(String)} regardless, so asking for an older
     * key — the only reason to name a version, since messages sealed before a
     * rotation cannot be read without it — was impossible to express. A member
     * holding every version but the one they needed was handed the one they
     * already had, every time they asked.
     *
     * <p>A version that is not a positive integer is treated as <b>absent</b>
     * rather than as a bad request: answering with the current key is what this
     * protocol did before versions could be named, so it stays the compatible
     * reading of anything unrecognised after the colon.
     *
     * @return the parsed request, or null when there is no entity id
     */
    public static Asked parseRequest(String content) {
        if (content == null || content.isEmpty()) return null;
        int colonIdx = content.indexOf(':');
        if (colonIdx < 0) return new Asked(content, null);
        String entityId = content.substring(0, colonIdx);
        if (entityId.isEmpty()) return null;
        String tail = content.substring(colonIdx + 1);
        try {
            long version = Long.parseLong(tail);
            return new Asked(entityId, version >= 1 ? version : null);
        } catch (NumberFormatException e) {
            return new Asked(entityId, null);
        }
    }

    /**
     * Build a symkey request's content, naming a version when one is wanted.
     * See {@link #parseRequest(String)}.
     */
    public static String requestContent(String entityId, Long version) {
        if (version == null || version < 1) return entityId;
        return entityId + ":" + version;
    }

    /**
     * The most versions one {@code SYMKEY_HISTORY} request may ask for.
     *
     * <p><b>A bound is a security property, not tidiness.</b> Every version
     * named costs the responder one asymmetric seal and one message on
     * somebody's DOCK, paid for by the responder. An unbounded list is an
     * amplifier: one small request naming ten thousand versions would have a
     * member's device seal and pay to send ten thousand replies. No real entity
     * has been rotated this many times, so the cap costs nothing legitimate.
     */
    public static final int MAX_HISTORY_VERSIONS = 64;

    /**
     * Build the content of a batch request -- FIMP4V3 §5.2, FIMP2V3 §5.3:
     * {@code "<entityId>:<v1>,<v2>,…"}.
     *
     * <p>Versions are de-duplicated and sorted, so the same set always produces
     * the same request, and capped at {@link #MAX_HISTORY_VERSIONS}. Returns
     * null when no version is worth asking for -- a batch naming none has no
     * meaning, and the caller wants {@link #requestContent(String, Long)}.
     */
    public static String historyRequestContent(String entityId, List<Long> versions) {
        if (entityId == null || entityId.isEmpty() || versions == null) return null;
        java.util.TreeSet<Long> wanted = new java.util.TreeSet<>();
        for (Long version : versions) {
            if (version != null && version >= 1) wanted.add(version);
        }
        if (wanted.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(entityId).append(':');
        int count = 0;
        for (Long version : wanted) {
            if (count >= MAX_HISTORY_VERSIONS) break;
            if (count > 0) sb.append(',');
            sb.append(version);
            count++;
        }
        return sb.toString();
    }

    /** What a batch request is asking for. */
    public static final class AskedHistory {
        public final String entityId;
        public final List<Long> versions;

        AskedHistory(String entityId, List<Long> versions) {
            this.entityId = entityId;
            this.versions = versions;
        }
    }

    /**
     * Parse a batch request's content.
     *
     * <p>Unreadable entries are <b>skipped rather than failing the request</b>:
     * a list of eight versions with one piece of nonsense in it is still seven
     * keys somebody needs, and refusing the whole thing helps nobody. A request
     * whose every entry is unreadable yields null, because there is then nothing
     * to answer.
     */
    public static AskedHistory parseHistoryRequest(String content) {
        if (content == null || content.isEmpty()) return null;
        int colonIdx = content.indexOf(':');
        if (colonIdx < 0) return null;
        String entityId = content.substring(0, colonIdx);
        if (entityId.isEmpty()) return null;

        java.util.TreeSet<Long> wanted = new java.util.TreeSet<>();
        for (String part : content.substring(colonIdx + 1).split(",")) {
            try {
                long version = Long.parseLong(part.trim());
                if (version >= 1) wanted.add(version);
            } catch (NumberFormatException ignored) {
                // Skipped, not fatal -- see the note above.
            }
        }
        if (wanted.isEmpty()) return null;

        List<Long> versions = new ArrayList<>();
        for (Long version : wanted) {
            if (versions.size() >= MAX_HISTORY_VERSIONS) break;
            versions.add(version);
        }
        return new AskedHistory(entityId, versions);
    }
    
    /**
     * Create cipher for sharing symkey with another FID.
     * Encrypts the symkey with the recipient's public key.
     */
    public String createShareCipher(String entityId, long version, String recipientPubkey) {
        byte[] symkey = getSymkey(entityId, version);
        if (symkey == null) return null;
        
        try {
            byte[] pubkeyBytes = Hex.fromHex(recipientPubkey);
            return new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptByAsyOneWay(symkey, pubkeyBytes).toJson();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create share cipher: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Receive a shared symkey (encrypted with our pubkey).
     * @param allowOverwrite If true, overwrite existing key for the same version (for owner-pushed keys)
     */
    public boolean receiveSharedSymkey(String entityId, long version, String cipher, boolean allowOverwrite) {
        if (userPrikey == null || cipher == null) return false;
        
        try {
            CryptoDataByte result = new Decryptor().decrypt(cipher, userPrikey);
            if (result == null || result.getCode() != 0 || result.getData() == null) {
                TimberLogger.e(TAG, "Failed to receive shared symkey for %s v%d: code=%s",
                        entityId, version, result != null ? result.getCode() : "null");
                return false;
            }
            return storeSymkey(entityId, version, result.getData(), allowOverwrite);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to receive shared symkey for %s v%d: %s", entityId, version, e.getMessage());
            return false;
        }
    }
    
    /**
     * Clear cache (call on logout or when memory pressure).
     */
    public void clearCache() {
        keyCache.clear();
    }
    
    private SymkeyEntry getLatestEntry(String entityId) {
        List<SymkeyEntry> entries = getEntriesForEntity(entityId);
        if (entries.isEmpty()) return null;
        
        SymkeyEntry latest = null;
        for (SymkeyEntry entry : entries) {
            if (latest == null || entry.getVersion() > latest.getVersion()) {
                latest = entry;
            }
        }
        return latest;
    }
    
    /**
     * Symkey entry for database storage.
     */
    public static class SymkeyEntry extends com.fc.fc_ajdk.data.fcData.FcEntity {
        private String entityId;     // Team or room ID
        private Long version;        // Key version
        private String cipher;       // Encrypted key
        private Long createdAt;
        private Boolean active;
        
        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }
        
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        
        public String getCipher() { return cipher; }
        public void setCipher(String cipher) { this.cipher = cipher; }
        
        public Long getCreatedAt() { return createdAt; }
        public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
        
        public Boolean getActive() { return active; }
        public void setActive(Boolean active) { this.active = active; }
        
        public String toJson() { return JsonUtils.toJson(this); }
        public static SymkeyEntry fromJson(String json) { return JsonUtils.fromJson(json, SymkeyEntry.class); }
    }
}
