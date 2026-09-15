package com.fc.freer.initiate;

import android.content.Context;
import android.content.SharedPreferences;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.core.crypto.VaultKey;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * A singleton class to manage and share the Configure object across activities.
 * This provides a freer way to access the Configure object and its symkey from any activity.
 */
public class ConfigureManager {
    private static ConfigureManager instance;
    private Configure configure;
    private static final String CONFIG_PREFS_NAME = "fc_config_prefs";
    private static final String CONFIG_KEY = "config_map";

    private ConfigureManager() {
        // Private constructor to prevent direct instantiation
    }

    public static synchronized ConfigureManager getInstance() {
        if (instance == null) {
            instance = new ConfigureManager();
        }
        return instance;
    }

    /**
     * Creates a new vault: a random data key wrapped under the password, stored under a random vault id.
     * Runs Argon2id, so call it off the UI thread. The caller stores the result.
     * @param passwordBytes The password bytes
     * @return The newly created Configure object, with its data key as the symkey
     */
    public static Configure createConfigure(byte[] passwordBytes) {
        Configure configure = new Configure();
        byte[] dek = VaultKey.newDek();
        configure.setDekCipher(VaultKey.wrap(dek, toChars(passwordBytes)));
        configure.setSymkey(dek);
        configure.setPasswordName(VaultKey.newVaultId());

        TimberLogger.d("ConfigMethods", "Created new vault: " + configure.getPasswordName());

        return configure;
    }

    /** The password as the characters VaultKey takes; the bytes come from {@code String.getBytes()}, which is UTF-8 on Android. */
    public static char[] toChars(byte[] passwordBytes) {
        return new String(passwordBytes, StandardCharsets.UTF_8).toCharArray();
    }

    /** Writes the whole Configure map durably. @return false if the write failed. */
    public static boolean commitConfigMap(Context context, Map<String, Configure> configMap) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.edit().putString(CONFIG_KEY, JsonUtils.toJson(configMap)).commit();
    }

    /**
     * Sets the Configure object in memory for sharing across activities.
     * @param configure The Configure object to store
     */
    public void setConfigure(Configure configure) {
        this.configure = configure;
    }

    /**
     * Gets the Configure object from memory.
     * @return The Configure object
     */
    public Configure getConfigure() {
        return configure;
    }

    /**
     * Gets the symmetric key from the Configure object.
     * @return The symmetric key
     */
    public byte[] getSymkey() {
        return configure != null ? configure.getSymkey() : null;
    }

    /**
     * Stores a Configure object in SharedPreferences using its password name as the key.
     * @param context The application context
     * @param configure The Configure object to store
     * @return false if the write failed
     */
    public boolean storeConfigure(Context context, Configure configure) {
        if (configure == null || configure.getPasswordName() == null) {
            throw new IllegalArgumentException("Configure object or password name is null");
        }
        
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }

        context = context.getApplicationContext();
        
        SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        // Get existing config map or create new one
        String configJson = prefs.getString(CONFIG_KEY, "{}");
        Map<String, Configure> configMap = JsonUtils.jsonToMap(configJson, String.class, Configure.class);
        if (configMap == null) {
            configMap = new HashMap<>();
        }

        // Add or update the configure object
        configMap.put(configure.getPasswordName(), configure);
        
        // The wrapped data key lives only here, so the write must be durable before anyone relies on it.
        editor.putString(CONFIG_KEY, JsonUtils.toJson(configMap));
        boolean saved = editor.commit();
        if (!saved) {
            TimberLogger.e("ConfigureManager", "Failed to save the configuration");
        }
        return saved;
    }

    /**
     * Retrieves a Configure object from SharedPreferences using its password name.
     * @param context The application context
     * @param passwordName The password name to look up
     * @return The Configure object if found, null otherwise
     */
    public Configure getConfigure(Context context, String passwordName) {
        if (passwordName == null) {
            throw new IllegalArgumentException("Password name is null");
        }
        
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        
        SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        String configJson = prefs.getString(CONFIG_KEY, "{}");
        Map<String, Configure> configMap = JsonUtils.jsonToMap(configJson, String.class, Configure.class);
        
        return configMap != null ? configMap.get(passwordName) : null;
    }

    /**
     * Checks if the configuration is empty.
     * @param context The application context
     * @return true if the config is empty, false otherwise
     */
    public boolean isConfigEmpty(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        
        SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        String configJson = prefs.getString(CONFIG_KEY, "{}");
        Map<String, Configure> configMap = JsonUtils.jsonToMap(configJson, String.class, Configure.class);
        return configMap == null || configMap.isEmpty();
    }

    /**
     * Clears all configuration data from SharedPreferences.
     * @param context The application context
     */
    public void clearConfig(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        
        SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    /**
     * Verifies that the password opens the vault that is open now. For a vault with a data key this
     * runs Argon2id, so call it off the UI thread.
     * @param passwordBytes The password bytes to verify
     * @return true if the password opens the open vault
     */
    public boolean verifyPassword(byte[] passwordBytes) {
        if (passwordBytes == null || configure == null || configure.getSymkey() == null) {
            return false;
        }
        byte[] key;
        if (configure.getDekCipher() != null && !VaultKey.isLegacyName(configure.getPasswordName())) {
            key = VaultKey.unwrap(configure.getDekCipher(), toChars(passwordBytes));
        } else {
            key = Configure.getSymkeyFromPassword(passwordBytes);
        }
        return key != null && MessageDigest.isEqual(key, configure.getSymkey());
    }

    /**
     * Checks if a password already opens a stored vault. Runs Argon2id once per vault with a data key,
     * so call it off the UI thread.
     * @param context The application context
     * @param passwordBytes The password bytes to check
     * @return true if the password already exists, false otherwise
     */
    public boolean passwordExists(Context context, byte[] passwordBytes) {
        if (passwordBytes == null || context == null) {
            return false;
        }

        Map<String, Configure> configMap = loadConfigMap(context);
        if (configMap.containsKey(IdNameUtils.makePasswordHashName(passwordBytes))) {
            return true;
        }
        char[] password = toChars(passwordBytes);
        for (Map.Entry<String, Configure> entry : configMap.entrySet()) {
            Configure stored = entry.getValue();
            if (stored == null || VaultKey.isLegacyName(entry.getKey()) || stored.getDekCipher() == null) continue;
            if (VaultKey.unwrap(stored.getDekCipher(), password) != null) return true;
        }
        return false;
    }

    /**
     * Removes a Configure object from SharedPreferences using its password name as the key.
     * @param context The application context
     * @param passwordName The password name to remove
     */
    public void removeConfigure(Context context, String passwordName) {
        if (passwordName == null) {
            throw new IllegalArgumentException("Password name is null");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        context = context.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        String configJson = prefs.getString(CONFIG_KEY, "{}");
        Map<String, Configure> configMap = JsonUtils.jsonToMap(configJson, String.class, Configure.class);
        if (configMap != null && configMap.containsKey(passwordName)) {
            configMap.remove(passwordName);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(CONFIG_KEY, JsonUtils.toJson(configMap));
            editor.commit();
        }
    }

    /**
     * 静态方法：将ApiProvider和ApiAccount保存到当前configure中并永久化
     * @param context 应用上下文
     * @param apiProvider 要保存的ApiProvider
     * @param apiAccount 要保存的ApiAccount
     * @return true如果保存成功，false如果失败
     */
    public static boolean saveApiProviderAndAccount(Context context, ApiProvider apiProvider, ApiAccount apiAccount) {
        if (context == null) {
            TimberLogger.e("ConfigureManager", "Context is null, cannot save apiProvider and apiAccount");
            return false;
        }
        
        if (apiProvider == null || apiAccount == null) {
            TimberLogger.e("ConfigureManager", "ApiProvider or ApiAccount is null, cannot save");
            return false;
        }
        
        ConfigureManager instance = getInstance();
        Configure configure = instance.getConfigure();
        
        if (configure == null) {
            TimberLogger.w("ConfigureManager", "Configure is null, cannot save apiProvider and apiAccount");
            return false;
        }
        
        try {
            // 确保map不为null
            if (configure.getApiProviderMap() == null) {
                configure.setApiProviderMap(new HashMap<>());
            }
            if (configure.getApiAccountMap() == null) {
                configure.setApiAccountMap(new HashMap<>());
            }
            
            // 更新apiProvider和apiAccount
            configure.getApiProviderMap().put(apiProvider.getId(), apiProvider);
            configure.getApiAccountMap().put(apiAccount.getId(), apiAccount);
            
            // 永久化保存
            instance.storeConfigure(context, configure);
            TimberLogger.d("ConfigureManager", "Successfully saved apiProvider (%s) and apiAccount (%s) to configure", 
                          apiProvider.getId(), apiAccount.getId());
            return true;
            
        } catch (Exception e) {
            TimberLogger.e("ConfigureManager", "Failed to save apiProvider and apiAccount: %s", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 静态方法：将ApiProvider保存到当前configure中并永久化
     * @param context 应用上下文
     * @param apiProvider 要保存的ApiProvider
     * @return true如果保存成功，false如果失败
     */
    public static boolean saveApiProvider(Context context, ApiProvider apiProvider) {
        if (context == null) {
            TimberLogger.e("ConfigureManager", "Context is null, cannot save apiProvider");
            return false;
        }
        
        if (apiProvider == null) {
            TimberLogger.e("ConfigureManager", "ApiProvider is null, cannot save");
            return false;
        }
        
        ConfigureManager instance = getInstance();
        Configure configure = instance.getConfigure();
        
        if (configure == null) {
            TimberLogger.w("ConfigureManager", "Configure is null, cannot save apiProvider");
            return false;
        }
        
        try {
            // 确保map不为null
            if (configure.getApiProviderMap() == null) {
                configure.setApiProviderMap(new HashMap<>());
            }
            
            // 更新apiProvider
            configure.getApiProviderMap().put(apiProvider.getId(), apiProvider);
            
            // 永久化保存
            instance.storeConfigure(context, configure);
            TimberLogger.d("ConfigureManager", "Successfully saved apiProvider (%s) to configure", apiProvider.getId());
            return true;
            
        } catch (Exception e) {
            TimberLogger.e("ConfigureManager", "Failed to save apiProvider: %s", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 静态方法：将ApiAccount保存到当前configure中并永久化
     * @param context 应用上下文
     * @param apiAccount 要保存的ApiAccount
     * @return true如果保存成功，false如果失败
     */
    public static boolean saveApiAccount(Context context, ApiAccount apiAccount) {
        if (context == null) {
            TimberLogger.e("ConfigureManager", "Context is null, cannot save apiAccount");
            return false;
        }
        
        if (apiAccount == null) {
            TimberLogger.e("ConfigureManager", "ApiAccount is null, cannot save");
            return false;
        }
        
        ConfigureManager instance = getInstance();
        Configure configure = instance.getConfigure();
        
        if (configure == null) {
            TimberLogger.w("ConfigureManager", "Configure is null, cannot save apiAccount");
            return false;
        }
        
        try {
            // 确保map不为null
            if (configure.getApiAccountMap() == null) {
                configure.setApiAccountMap(new HashMap<>());
            }
            
            // 更新apiAccount
            configure.getApiAccountMap().put(apiAccount.getId(), apiAccount);
            
            // 永久化保存
            instance.storeConfigure(context, configure);
            TimberLogger.d("ConfigureManager", "Successfully saved apiAccount (%s) to configure", apiAccount.getId());
            return true;
            
        } catch (Exception e) {
            TimberLogger.e("ConfigureManager", "Failed to save apiAccount: %s", e.getMessage(), e);
            return false;
        }
    }

    /** @return every stored Configure by its vault name; never null. */
    public static Map<String,Configure> loadConfigMap(Context context){
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        // Get existing config map or create new one
        String configJson = prefs.getString(CONFIG_KEY, "{}");
        Map<String, Configure> configMap = JsonUtils.jsonToMap(configJson, String.class, Configure.class);
        if (configMap == null) {
            configMap = new HashMap<>();
        }
        return configMap;
    }
} 