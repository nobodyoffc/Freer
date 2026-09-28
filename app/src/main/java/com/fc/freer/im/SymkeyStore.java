package com.fc.freer.im;

import android.content.Context;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Hash;
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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secure storage for the symmetric keys a team or room is sealed under.
 * Keys are stored encrypted to this identity's pubkey, so only its prikey opens them.
 *
 * <p>See {@code docs/SYMKEY_IDENTITY_SPEC.md} §1–2. Two rules carry the whole design:
 *
 * <ol>
 *   <li><b>A symkey is named by when it was minted.</b> {@code symkeyVersion} carries seconds
 *       since the epoch, floored above every version already known, so two devices of one
 *       identity can mint independently without agreeing on a counter. A per-entity counter
 *       could not survive that: a device holding nothing minted "1" for a key that was not
 *       everyone else's v1, and two distinct keys then answered to one name.</li>
 *   <li><b>Nothing is ever overwritten.</b> A key is identified by its own bytes, so two keys
 *       may share a version; both are kept and opening tries each. The rule that used to
 *       protect a version from being poisoned — "only the owner may replace it" — was also the
 *       only thing able to destroy history, because the owner's fresh v1 landed on a member's
 *       v1 and every message under it became unreadable forever.</li>
 * </ol>
 */
public class SymkeyStore {
    private static final String TAG = "SymkeyStore";

    /**
     * Below this, a version is a pre-spec counter rather than a mint time.
     * A timestamp is about 1.79e9 and a counter is single digits, so the two coexist with no
     * migration: {@code max()} still selects the newest, because a timestamp always exceeds a
     * counter.
     */
    public static final long LEGACY_VERSION_THRESHOLD = 1_000_000_000L;

    /**
     * The most keys kept for one entity. Reaching it means something is wrong — an honest
     * entity is rotated a handful of times — and §4's acceptance rule is what stops a peer
     * being able to reach it now that nothing is overwritten.
     */
    public static final int MAX_KEYS_PER_ENTITY = 256;

    /**
     * The most versions one request is answered with.
     *
     * <p><b>A bound is a security property, not tidiness.</b> Every version answered costs the
     * responder one asymmetric seal and one message on somebody's DOCK, paid for by the
     * responder. The cap counts versions <b>answered</b>, not named, so a request padded with
     * versions we do not hold cannot buy a larger answer.
     */
    public static final int MAX_VERSIONS_PER_REQUEST = 64;

    /** Beyond this many names in one request the rest is not even parsed. */
    private static final int MAX_VERSIONS_PARSED = 1024;

    /** Row key: {@code <entityId>_<19-digit version>_<16 hex of SHA256(symkey)>}. */
    private static final int VERSION_DIGITS = 19;
    private static final int HASH_PREFIX_CHARS = 16;
    private static final int KEY_SUFFIX_LEN = 1 + VERSION_DIGITS + 1 + HASH_PREFIX_CHARS;

    private final LocalDB<SymkeyEntry> db;
    private final String liveFid;
    private byte[] userPrikey;
    private byte[] userPubkey;

    /** Row key -> decrypted key bytes. */
    private final Map<String, byte[]> keyCache = new ConcurrentHashMap<>();

    /**
     * Entity id -> its row keys, newest version first.
     *
     * <p>A row key carries its own version, so "which versions are held" and "which is current"
     * are answered from this index without touching the database or decrypting anything. Built
     * once from the row names and maintained on every write; the alternative, scanning every
     * row on each lookup, made reopening a backlog quadratic in the size of the store.
     */
    private final Map<String, List<String>> rowIndex = new ConcurrentHashMap<>();
    private volatile boolean indexed = false;

    public SymkeyStore(Context context, String liveFid) {
        this.liveFid = liveFid;
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, SymkeyEntry.class, SymkeyEntry.class);
    }

    /**
     * Set this identity's private key. The public key is derived from it, and the one-pass
     * migration to content-addressed row keys runs here because it has to decrypt every row.
     */
    public void setUserPrikey(byte[] prikey) {
        this.userPrikey = prikey;
        this.userPubkey = KeyTools.prikeyToPubkey(prikey);
        migrateRowKeys();
    }

    // ================= naming =================

    /**
     * The version a key minted now would carry -- §1.
     *
     * <p>{@code max(nowSeconds, highestKnown + 1)}. The floor is not belt-and-braces: it makes
     * a double-tapped "Reset the key" impossible to collide rather than unlikely, and it stops
     * a clock that has stepped backwards from minting a key that sorts <i>below</i> an existing
     * one. That case is worse than a tie — "current" would select the retired key, so an owner
     * who rotates after removing a member keeps sealing under the key that member still holds.
     */
    public long mintVersion(String entityId) {
        long now = System.currentTimeMillis() / 1000L;
        long highest = getCurrentVersion(entityId);
        return Math.max(now, highest + 1);
    }

    /** Whether a version is a pre-spec counter rather than a mint time. */
    public static boolean isLegacyVersion(long version) {
        return version < LEGACY_VERSION_THRESHOLD;
    }

    // ================= minting =================

    /** A freshly minted key and the version naming it. */
    public static final class Minted {
        public final long version;
        public final byte[] symkey;

        Minted(long version, byte[] symkey) {
            this.version = version;
            this.symkey = symkey;
        }
    }

    /**
     * Mint a new symkey for an entity and store it, naming it by the clock -- §1.
     *
     * @return the new key and its version, or null if it could not be minted
     */
    public Minted mintSymkey(String entityId) {
        if (entityId == null || userPrikey == null || userPubkey == null) {
            TimberLogger.e(TAG, "Cannot mint symkey: user prikey not set");
            return null;
        }
        byte[] symkey = new byte[32];
        new SecureRandom().nextBytes(symkey);

        long version = mintVersion(entityId);
        StoreResult result = put(entityId, version, symkey);
        if (result != StoreResult.STORED) {
            TimberLogger.e(TAG, "Failed to store minted symkey for %s v%d: %s", entityId, version, result);
            return null;
        }
        return new Minted(version, symkey);
    }

    // ================= storing =================

    /** What happened to a key handed to {@link #put}. Mirrors the ledger's outcomes. */
    public enum StoreResult {
        /** A key we did not hold, now held. */
        STORED,
        /** Exactly this key, at this version, was already here. Two members answering one ask. */
        DUPLICATE,
        /** The entity is at {@link #MAX_KEYS_PER_ENTITY}. */
        FULL,
        /** A cipher that would not open, or no prikey to open it with. */
        UNREADABLE,
        /** Anything else. */
        FAILED
    }

    /**
     * Store a key under a version, <b>never overwriting</b> -- §2.
     *
     * <p>A different key at a version already held is kept beside it rather than instead of it:
     * opening a message tries every candidate and the body's AES-GCM tag decides, so a bogus key
     * is an extra attempt that fails, not a key that has replaced the real one.
     */
    public StoreResult put(String entityId, long version, byte[] symkey) {
        if (entityId == null || symkey == null || symkey.length == 0) return StoreResult.FAILED;
        if (userPrikey == null || userPubkey == null) return StoreResult.UNREADABLE;

        String contentHash = Hex.toHex(Hash.sha256(symkey));
        String id = rowKey(entityId, version, contentHash);

        ensureIndexed();
        if (rowsForEntity(entityId).contains(id)) return StoreResult.DUPLICATE;

        int held = rowsForEntity(entityId).size();
        if (held >= MAX_KEYS_PER_ENTITY) {
            TimberLogger.w(TAG, "Refusing symkey for %s: %d keys already held", entityId, held);
            return StoreResult.FULL;
        }

        try {
            String cipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7)
                    .encryptByAsyOneWay(symkey, userPubkey).toJson();

            SymkeyEntry entry = new SymkeyEntry();
            entry.setId(id);
            entry.setEntityId(entityId);
            entry.setVersion(version);
            entry.setContentHash(contentHash);
            entry.setCipher(cipher);
            entry.setCreatedAt(System.currentTimeMillis());
            entry.setActive(true);

            db.put(id, entry);
            // A copy: a caller that erases its own key afterwards, as key handling should,
            // must not erase the one this store hands out.
            keyCache.put(id, symkey.clone());
            index(entityId, id);
            return StoreResult.STORED;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to store symkey: %s", e.getMessage());
            return StoreResult.FAILED;
        }
    }

    /** @return true when the key is held afterwards, whether it was new or already there. */
    public boolean storeSymkey(String entityId, long version, byte[] symkey) {
        StoreResult result = put(entityId, version, symkey);
        return result == StoreResult.STORED || result == StoreResult.DUPLICATE;
    }

    /**
     * Open a key sealed to our pubkey and store it -- the receiving half of a share.
     * {@code allowOverwrite} and {@code isOwner} are gone from this path by design: see §2.
     */
    public StoreResult receiveSharedSymkey(String entityId, long version, String cipher) {
        if (cipher == null) return StoreResult.FAILED;
        if (userPrikey == null) return StoreResult.UNREADABLE;

        try {
            CryptoDataByte result = new Decryptor().decrypt(cipher, userPrikey);
            if (result == null || result.getCode() != 0 || result.getData() == null) {
                TimberLogger.e(TAG, "Failed to open shared symkey for %s v%d: code=%s",
                        entityId, version, result != null ? result.getCode() : "null");
                return StoreResult.UNREADABLE;
            }
            return put(entityId, version, result.getData());
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to open shared symkey for %s v%d: %s",
                    entityId, version, e.getMessage());
            return StoreResult.UNREADABLE;
        }
    }

    // ================= reading =================

    /**
     * Every key held for this version, newest first -- §2's <i>candidates</i>.
     *
     * <p>Ordinarily one. Two only when two different keys were minted in the same second by two
     * devices of one owner, which costs the opener one extra AES-GCM attempt.
     */
    public List<byte[]> getSymkeys(String entityId, long version) {
        List<byte[]> keys = new ArrayList<>();
        for (String id : rowsForEntity(entityId)) {
            RowKey parsed = parseRowKey(id);
            if (parsed == null || parsed.version != version) continue;

            // The cache is keyed on the row, so a message opened under a key that is already
            // cached costs nothing beyond the AES-GCM attempt itself.
            byte[] cached = keyCache.get(id);
            if (cached != null) {
                keys.add(cached);
                continue;
            }
            SymkeyEntry entry = db.get(id);
            byte[] key = decrypt(entry);
            if (key != null) keys.add(key);
        }
        return keys;
    }

    /** One key for this version -- for sealing and for sharing, where any candidate will do. */
    public byte[] getSymkey(String entityId, long version) {
        List<byte[]> keys = getSymkeys(entityId, version);
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** The current (highest-versioned) key for an entity. */
    public byte[] getSymkey(String entityId) {
        long version = getCurrentVersion(entityId);
        if (version == 0) return null;
        return getSymkey(entityId, version);
    }

    /**
     * The highest version held, or 0 when none is.
     *
     * <p>Answered from the row names: the zero-padded version dominates the key text, so the
     * newest row is the first one and nothing has to be decrypted to find it.
     */
    public long getCurrentVersion(String entityId) {
        List<String> rows = rowsForEntity(entityId);
        if (rows.isEmpty()) return 0;
        RowKey parsed = parseRowKey(rows.get(0));
        return parsed != null ? parsed.version : 0;
    }

    /** Every version held for an entity, ascending and without repeats. */
    public List<Long> getVersionHistory(String entityId) {
        TreeSet<Long> versions = new TreeSet<>();
        for (String id : rowsForEntity(entityId)) {
            RowKey parsed = parseRowKey(id);
            if (parsed != null) versions.add(parsed.version);
        }
        return new ArrayList<>(versions);
    }

    public boolean hasSymkey(String entityId) {
        return !rowsForEntity(entityId).isEmpty();
    }

    public boolean hasSymkey(String entityId, long version) {
        return candidateCount(entityId, version) > 0;
    }

    /** How many distinct keys are held at this version. Two means a same-second collision. */
    public int candidateCount(String entityId, long version) {
        int count = 0;
        for (String id : rowsForEntity(entityId)) {
            RowKey parsed = parseRowKey(id);
            if (parsed != null && parsed.version == version) count++;
        }
        return count;
    }

    /** Seal one version to a recipient's pubkey, for handing it over. */
    public String createShareCipher(String entityId, long version, String recipientPubkey) {
        byte[] symkey = getSymkey(entityId, version);
        if (symkey == null) return null;

        try {
            byte[] pubkeyBytes = Hex.fromHex(recipientPubkey);
            return new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7)
                    .encryptByAsyOneWay(symkey, pubkeyBytes).toJson();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create share cipher: %s", e.getMessage());
            return null;
        }
    }

    /** Clear the decrypted-key cache (on logout, or under memory pressure). */
    public void clearCache() {
        keyCache.clear();
    }

    /** Drop every key for an entity. Only for a conversation being erased. */
    public void forget(String entityId) {
        List<String> ids = new ArrayList<>(rowsForEntity(entityId));
        if (ids.isEmpty()) return;
        db.removeList(ids);
        for (String id : ids) keyCache.remove(id);
        rowIndex.remove(entityId);
    }

    // ================= requests (§3) =================

    /**
     * What a {@code SYMKEY} request is asking for.
     *
     * <p>One shape covers all three cases FIMP4V3 §5.1/§5.2 and FIMP2V3 §5.2/§5.3 define:
     * {@code "<id>"} is whatever the responder holds now, {@code "<id>:<v>"} is one version, and
     * {@code "<id>:<v1>,<v2>,…"} is several. {@code SYMKEY_HISTORY} is an accepted alias for the
     * third and is never sent.
     */
    public static final class Asked {
        public final String entityId;
        /** The versions named, ascending. Empty means "whatever you hold now". */
        public final List<Long> versions;

        Asked(String entityId, List<Long> versions) {
            this.entityId = entityId;
            this.versions = versions;
        }

        public boolean wantsCurrent() {
            return versions.isEmpty();
        }
    }

    /**
     * Parse a symkey request's content -- one parser for zero, one or many versions.
     *
     * <p>An unreadable entry is <b>skipped rather than failing the request</b>: a list of eight
     * versions with one piece of nonsense in it is still seven keys somebody needs. A tail whose
     * every entry is unreadable is treated as <b>absent</b> rather than as a bad request —
     * answering with the current key is what this protocol did before versions could be named,
     * so it stays the compatible reading of anything unrecognised after the colon.
     *
     * @return the parsed request, or null when there is no entity id
     */
    public static Asked parseRequest(String content) {
        if (content == null || content.isEmpty()) return null;
        int colonIdx = content.indexOf(':');
        if (colonIdx < 0) return new Asked(content, List.of());
        String entityId = content.substring(0, colonIdx);
        if (entityId.isEmpty()) return null;

        TreeSet<Long> wanted = new TreeSet<>();
        for (String part : content.substring(colonIdx + 1).split(",")) {
            if (wanted.size() >= MAX_VERSIONS_PARSED) break;
            try {
                long version = Long.parseLong(part.trim());
                if (version >= 1) wanted.add(version);
            } catch (NumberFormatException ignored) {
                // Skipped, not fatal -- see the note above.
            }
        }
        return new Asked(entityId, new ArrayList<>(wanted));
    }

    /** Build a request naming one version, or none when {@code version} is null. */
    public static String requestContent(String entityId, Long version) {
        if (version == null || version < 1) return entityId;
        return entityId + ":" + version;
    }

    /**
     * Build a request naming several versions. De-duplicated and sorted, so the same set always
     * produces the same content. Falls back to the whole-entity form when none is worth naming.
     */
    public static String requestContent(String entityId, List<Long> versions) {
        if (entityId == null || entityId.isEmpty()) return null;
        if (versions == null || versions.isEmpty()) return entityId;

        TreeSet<Long> wanted = new TreeSet<>();
        for (Long version : versions) {
            if (version != null && version >= 1) wanted.add(version);
        }
        if (wanted.isEmpty()) return entityId;

        StringBuilder sb = new StringBuilder(entityId).append(':');
        int count = 0;
        for (Long version : wanted) {
            if (count >= MAX_VERSIONS_PER_REQUEST) break;
            if (count > 0) sb.append(',');
            sb.append(version);
            count++;
        }
        return sb.toString();
    }

    // ================= row keys =================

    /** A parsed row key. Fixed widths, so an entity id containing '_' still parses. */
    static final class RowKey {
        final String entityId;
        final long version;
        final String hashPrefix;

        RowKey(String entityId, long version, String hashPrefix) {
            this.entityId = entityId;
            this.version = version;
            this.hashPrefix = hashPrefix;
        }
    }

    static String rowKey(String entityId, long version, String contentHash) {
        String prefix = contentHash.length() > HASH_PREFIX_CHARS
                ? contentHash.substring(0, HASH_PREFIX_CHARS) : contentHash;
        return entityId + "_" + String.format("%0" + VERSION_DIGITS + "d", version) + "_" + prefix;
    }

    /**
     * Slice a row key from the <b>end</b>: 16 hex, '_', 19 digits, '_', and whatever is left is
     * the entity id. Reading it forwards would mis-split every entity id containing '_'.
     */
    static RowKey parseRowKey(String key) {
        if (key == null || key.length() <= KEY_SUFFIX_LEN) return null;
        int hashAt = key.length() - HASH_PREFIX_CHARS;
        int versionAt = hashAt - 1 - VERSION_DIGITS;
        if (key.charAt(hashAt - 1) != '_' || key.charAt(versionAt - 1) != '_') return null;

        String hashPrefix = key.substring(hashAt);
        String digits = key.substring(versionAt, hashAt - 1);
        for (int i = 0; i < hashPrefix.length(); i++) {
            if (Character.digit(hashPrefix.charAt(i), 16) < 0) return null;
        }
        long version;
        try {
            version = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        return new RowKey(key.substring(0, versionAt - 1), version, hashPrefix);
    }

    /**
     * Rewrite pre-spec rows ({@code <entityId>_<version>}) under content-addressed keys -- §2.
     *
     * <p>A local one-pass rewrite: no network, nothing lost, and messages whose key was never
     * held stay exactly as unreadable as they were.
     */
    private void migrateRowKeys() {
        Map<String, SymkeyEntry> all = db.getAll();
        if (all == null || all.isEmpty()) return;

        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, SymkeyEntry> row : all.entrySet()) {
            String id = row.getKey();
            SymkeyEntry entry = row.getValue();
            if (entry == null) continue;
            RowKey current = parseRowKey(id);
            if (current != null) {
                index(current.entityId, id);
                continue;
            }

            byte[] symkey = decryptCipher(entry.getCipher());
            if (symkey == null || entry.getEntityId() == null || entry.getVersion() == null) {
                TimberLogger.w(TAG, "Leaving unreadable legacy symkey row %s alone", id);
                continue;
            }
            String contentHash = Hex.toHex(Hash.sha256(symkey));
            String newId = rowKey(entry.getEntityId(), entry.getVersion(), contentHash);

            entry.setId(newId);
            entry.setContentHash(contentHash);
            db.put(newId, entry);
            keyCache.put(newId, symkey);
            index(entry.getEntityId(), newId);
            stale.add(id);
        }
        if (!stale.isEmpty()) {
            db.removeList(stale);
            TimberLogger.i(TAG, "Migrated %d symkey rows to content-addressed keys", stale.size());
        }
        // This pass has just walked every row, so the index it built is complete.
        indexed = true;
    }

    // ================= internals =================

    /**
     * The row keys held for one entity, newest version first.
     *
     * <p>Matched by <b>parsing</b> each row key rather than by its prefix: entity ids "abc" and
     * "abc_d" share a prefix, and a prefix match would hand one entity's keys to the other.
     */
    private List<String> rowsForEntity(String entityId) {
        if (entityId == null) return List.of();
        ensureIndexed();
        List<String> rows = rowIndex.get(entityId);
        return rows != null ? rows : List.of();
    }

    /** Build the row index once per session, from the row names alone. */
    private void ensureIndexed() {
        if (indexed) return;
        synchronized (this) {
            if (indexed) return;
            try {
                Map<String, SymkeyEntry> all = db.getAll();
                if (all != null) {
                    for (String id : all.keySet()) {
                        RowKey parsed = parseRowKey(id);
                        if (parsed != null) index(parsed.entityId, id);
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to index symkeys: %s", e.getMessage());
            }
            indexed = true;
        }
    }

    /** Add a row to the index, keeping the entity's rows newest first. */
    private void index(String entityId, String rowKey) {
        List<String> rows = rowIndex.computeIfAbsent(entityId, k -> new ArrayList<>());
        synchronized (rows) {
            if (rows.contains(rowKey)) return;
            rows.add(rowKey);
            // The zero-padded version dominates the key text, so this is version order.
            rows.sort((a, b) -> b.compareTo(a));
        }
    }

    /** Every stored entry for one entity, newest version first. */
    private List<SymkeyEntry> entriesForEntity(String entityId) {
        List<SymkeyEntry> result = new ArrayList<>();
        for (String id : rowsForEntity(entityId)) {
            SymkeyEntry entry = db.get(id);
            if (entry != null) result.add(entry);
        }
        return result;
    }

    private byte[] decrypt(SymkeyEntry entry) {
        if (entry == null || entry.getId() == null) return null;
        byte[] cached = keyCache.get(entry.getId());
        if (cached != null) return cached;

        byte[] symkey = decryptCipher(entry.getCipher());
        if (symkey != null) keyCache.put(entry.getId(), symkey);
        return symkey;
    }

    private byte[] decryptCipher(String cipher) {
        if (cipher == null) return null;
        if (userPrikey == null) {
            TimberLogger.e(TAG, "Cannot decrypt symkey: user prikey not set");
            return null;
        }
        try {
            CryptoDataByte result = new Decryptor().decrypt(cipher, userPrikey);
            if (result == null || result.getCode() != 0 || result.getData() == null) {
                TimberLogger.e(TAG, "Failed to decrypt stored symkey: code=%s",
                        result != null ? result.getCode() : "null");
                return null;
            }
            return result.getData();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to decrypt stored symkey: %s", e.getMessage());
            return null;
        }
    }

    /**
     * One stored key.
     *
     * <p>{@code contentHash} is {@code SHA256(symkey)} — the key's own identity, computed
     * locally and never transmitted. It is what lets two keys share a version without either
     * displacing the other.
     */
    public static class SymkeyEntry extends com.fc.fc_ajdk.data.fcData.FcEntity {
        private String entityId;     // Team or room id
        private Long version;        // Seconds since the epoch at mint, or a pre-spec counter
        private String contentHash;  // SHA256(symkey), hex
        private String cipher;       // The key, sealed to this identity's pubkey
        private Long createdAt;      // When this device stored it, ms
        private Boolean active;

        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }

        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }

        public String getContentHash() { return contentHash; }
        public void setContentHash(String contentHash) { this.contentHash = contentHash; }

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
