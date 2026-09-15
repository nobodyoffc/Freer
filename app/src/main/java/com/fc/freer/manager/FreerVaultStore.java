package com.fc.freer.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.fc.fc_ajdk.core.crypto.VaultKey;
import com.fc.fc_ajdk.core.crypto.VaultMigration;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Configure;
import com.tencent.mmkv.MMKV;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The storage a {@link VaultMigration} moves in Freer. Three places are named after a vault and hold
 * vault-key ciphers:
 * <ul>
 *   <li>its Configure entry: identity prikeys in {@code mainCidInfoMap} and API session keys;</li>
 *   <li>its settings map: each identity's {@code keyInfoMap};</li>
 *   <li>its MMKV databases, one per identity and entity type, named {@code <vault name>_<fid>_...}:
 *   older Symkey secrets.</li>
 * </ul>
 * The re-encrypted Configure waits in its own preferences until the flip, so no unlock can open the new
 * vault before every record is in it.
 */
public class FreerVaultStore implements VaultMigration.Store {
    private static final String TAG = "FreerVaultStore";
    private static final String PENDING_PREFS_NAME = "fc_vault_migration_prefs";
    private static final String PENDING_CONFIGURE = "configure";

    private final Context context;
    private final String legacyName;
    private Configure configure;
    private String configKey;

    public FreerVaultStore(Context context, Configure configure) {
        this.context = context.getApplicationContext();
        this.configure = configure;
        this.configKey = configure.getPasswordName();
        this.legacyName = configure.getLegacyName() != null ? configure.getLegacyName() : configure.getPasswordName();
    }

    /** The vault's Configure: the legacy one until the flip, the re-encrypted copy after it. */
    public Configure getConfigure() {
        return configure;
    }

    /**
     * @return true if {@code legacySymkey} opens at least one cipher in the legacy vault, or the vault has none.
     * The legacy name matches about one wrong password in 16 million, so it cannot confirm a password alone.
     */
    public boolean legacyKeyOpensVault(byte[] legacySymkey) {
        boolean sawCipher = false;
        List<String> values = new ArrayList<>();
        values.add(JsonUtils.toJson(configure));
        values.add(SettingManager.readSettingMapJson(context, legacyName));
        for (String value : values) {
            int opens = VaultRecords.opens(value, legacySymkey);
            if (opens == VaultRecords.OPENS) return true;
            if (opens == VaultRecords.CLOSED) sawCipher = true;
        }
        for (String id : storageIds(legacyName)) {
            MMKV mmkv = open(id);
            String[] keys = mmkv.allKeys();
            if (keys == null) continue;
            for (String key : keys) {
                int opens = VaultRecords.opens(mmkv.decodeString(key), legacySymkey);
                if (opens == VaultRecords.OPENS) return true;
                if (opens == VaultRecords.CLOSED) sawCipher = true;
            }
        }
        return !sawCipher;
    }

    /**
     * Call when the legacy vault is opened without moving it. The app may then change its records,
     * so a copy made earlier is stale and has to be made again.
     */
    public void reopenedAsLegacy() {
        if (state() != VaultMigration.State.COPIED) return;
        configure.setVaultState(VaultMigration.State.PREPARED.name());
        try {
            save();
        } catch (IOException e) {
            TimberLogger.e(TAG, "Failed to mark the vault copy stale: " + e.getMessage());
        }
    }

    @Override
    public VaultMigration.State state() {
        if (configure.getVaultState() != null) return VaultMigration.State.valueOf(configure.getVaultState());
        return VaultKey.isLegacyName(configure.getPasswordName()) ? VaultMigration.State.LEGACY : VaultMigration.State.DONE;
    }

    @Override
    public String pendingVaultId() {
        return configure.getPendingVaultId();
    }

    @Override
    public String pendingDekCipher() {
        return configure.getDekCipher();
    }

    @Override
    public void savePrepared(String vaultId, String dekCipher) throws IOException {
        configure.setPendingVaultId(vaultId);
        configure.setDekCipher(dekCipher);
        configure.setVaultState(VaultMigration.State.PREPARED.name());
        save();
    }

    @Override
    public void copyInto(String vaultId, VaultMigration.Reencrypt reencrypt) throws Exception {
        for (String id : storageIds(legacyName)) {
            MMKV from = open(id);
            MMKV to = open(vaultId + id.substring(legacyName.length()));
            to.clearAll();
            String[] keys = from.allKeys();
            if (keys == null) continue;
            String dbName = dbName(id, legacyName);
            long copied = 0;
            for (String key : keys) {
                String value = from.decodeString(key);
                if (value == null) {
                    TimberLogger.w(TAG, "Skipped a non-string entry in %s", dbName);
                    continue;
                }
                // MMKVDB keys do not carry the vault name, so they copy as they are.
                if (!to.encode(key, VaultRecords.reencrypt(dbName + "/" + key, value, reencrypt))) {
                    throw new IOException("Failed to write " + dbName);
                }
                copied++;
            }
            to.sync();
            if (to.count() != copied) throw new IOException("Copied " + to.count() + " of " + copied + " entries of " + dbName);
        }

        String settings = SettingManager.readSettingMapJson(context, legacyName);
        if (settings != null && !SettingManager.commitSettingMapJson(context, vaultId, VaultRecords.reencrypt("settings", settings, reencrypt))) {
            throw new IOException("Failed to save the settings");
        }

        // Serialized as a one-entry map, the way ConfigureManager stores every Configure.
        String copy = VaultRecords.reencrypt("config", JsonUtils.toJson(Collections.singletonMap(PENDING_CONFIGURE, configure)), reencrypt);
        if (!pendingPrefs().edit().putString(vaultId, copy).commit()) throw new IOException("Failed to save the configuration copy");
    }

    @Override
    public void forEachCipherIn(String vaultId, VaultMigration.Check check) throws Exception {
        for (String id : storageIds(vaultId)) {
            MMKV mmkv = open(id);
            String[] keys = mmkv.allKeys();
            if (keys == null) continue;
            String dbName = dbName(id, vaultId);
            for (String key : keys) {
                VaultRecords.check(dbName + "/" + key, mmkv.decodeString(key), check);
            }
        }
        VaultRecords.check("settings", SettingManager.readSettingMapJson(context, vaultId), check);
        String copy = pendingPrefs().getString(vaultId, null);
        if (copy == null) throw new IOException("The configuration copy is missing");
        VaultRecords.check("config", copy, check);
    }

    @Override
    public void discard(String vaultId) {
        removeStorages(vaultId);
        if (!SettingManager.removeSettingMapJson(context, vaultId)) TimberLogger.w(TAG, "Failed to remove the settings copy");
        if (!pendingPrefs().edit().remove(vaultId).commit()) TimberLogger.w(TAG, "Failed to remove the configuration copy");
    }

    @Override
    public void saveCopied() throws IOException {
        configure.setVaultState(VaultMigration.State.COPIED.name());
        save();
    }

    @Override
    public void flip(String vaultId) throws IOException {
        String copy = pendingPrefs().getString(vaultId, null);
        Map<String, Configure> copied = copy == null ? null : JsonUtils.jsonToMap(copy, String.class, Configure.class);
        Configure flipped = copied == null ? null : copied.get(PENDING_CONFIGURE);
        if (flipped == null) throw new IOException("The configuration copy is missing");
        flipped.setDekCipher(configure.getDekCipher());
        flipped.setPendingVaultId(vaultId);
        flipped.setLegacyName(legacyName);
        flipped.setPasswordName(vaultId);
        flipped.setVaultState(VaultMigration.State.FLIPPED.name());

        Map<String, Configure> configMap = ConfigureManager.loadConfigMap(context);
        configMap.remove(legacyName);
        configMap.put(vaultId, flipped);
        if (!ConfigureManager.commitConfigMap(context, configMap)) throw new IOException("Failed to save the configuration");
        configure = flipped;
        configKey = vaultId;
        pendingPrefs().edit().remove(vaultId).commit();
    }

    @Override
    public void deleteLegacy() throws IOException {
        removeStorages(legacyName);
        if (!SettingManager.removeSettingMapJson(context, legacyName)) throw new IOException("Failed to remove the legacy settings");
        Map<String, Configure> configMap = ConfigureManager.loadConfigMap(context);
        if (configMap.remove(legacyName) != null && !ConfigureManager.commitConfigMap(context, configMap)) {
            throw new IOException("Failed to save the configuration");
        }
        if (configure.getPendingVaultId() != null) pendingPrefs().edit().remove(configure.getPendingVaultId()).commit();
    }

    @Override
    public void saveDone() throws IOException {
        configure.setVaultState(null);
        configure.setLegacyName(null);
        configure.setPendingVaultId(null);
        save();
    }

    private void save() throws IOException {
        Map<String, Configure> configMap = ConfigureManager.loadConfigMap(context);
        configMap.put(configKey, configure);
        if (!ConfigureManager.commitConfigMap(context, configMap)) throw new IOException("Failed to save the configuration");
    }

    private SharedPreferences pendingPrefs() {
        return context.getSharedPreferences(PENDING_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void removeStorages(String vaultName) {
        for (String id : storageIds(vaultName)) {
            if (!MMKV.removeStorage(id)) TimberLogger.w(TAG, "Failed to remove a storage of %s", dbName(id, vaultName));
        }
    }

    /** @return the ids of the MMKV storages named after {@code vaultName}. */
    private static List<String> storageIds(String vaultName) {
        List<String> ids = new ArrayList<>();
        String rootDir = MMKV.getRootDir();
        if (rootDir == null) return ids;
        File root = new File(rootDir);
        String[] names = root.list();
        if (names == null) return ids;
        String prefix = vaultName + "_";
        for (String name : names) {
            // MMKV keeps each storage in a file named by its id, beside a .crc meta file.
            if (name.startsWith(prefix) && !name.endsWith(".crc") && new File(root, name).isFile()) ids.add(name);
        }
        return ids;
    }

    private static MMKV open(String id) {
        // The same mode MMKVDB opens its storages with.
        MMKV mmkv = MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE);
        if (mmkv == null) throw new IllegalStateException("Failed to open MMKV storage");
        return mmkv;
    }

    private static String dbName(String id, String vaultName) {
        String name = id.substring(vaultName.length() + 1);
        return name.endsWith("_") ? name.substring(0, name.length() - 1) : name;
    }
}
