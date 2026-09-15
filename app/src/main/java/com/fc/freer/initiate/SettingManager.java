package com.fc.freer.initiate;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.fc.freer.model.Configure;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * A singleton class to manage and share Setting objects for different FIDs.
 * This provides a centralized way to access Setting objects for each FID.
 */
public class SettingManager {
    private static SettingManager instance;
    private static final String SETTINGS_PREFS_NAME = "fc_settings_prefs";
    private static final String SETTINGS_KEY = "settings_map";
//    private static final String CURRENT_SETTING_KEY = "current_setting_fid";

    // Current active setting
    private Setting currentSetting;

    private SettingManager() {
        // Private constructor to prevent direct instantiation
    }

    public static synchronized SettingManager getInstance() {
        if (instance == null) {
            instance = new SettingManager();
        }
        return instance;
    }

    /**
     * Sets the current active setting.
     * 
     * @param setting The Setting object to set as current
     */
    public void setCurrentSetting(Setting setting) {
        this.currentSetting = setting;
    }

    /**
     * Gets the current active setting.
     * 
     * @return The current Setting object, or null if not set
     */
    public Setting getCurrentSetting() {
        return currentSetting;
    }

    /**
     * Clears the current active setting.
     * This is typically called during logout.
     */
    public void clearCurrentSetting() {
        this.currentSetting = null;
        TimberLogger.d("SettingManager", "Cleared current setting");
    }

    /**
     * Gets the current KeyInfo from the current active setting.
     *
     * @return The current KeyInfo object, or null if not set
     */
    public KeyInfo getCurrentKeyInfo() {
        return currentSetting != null ? currentSetting.getMainKeyInfo() : null;
    }

    /**
     * Gets or creates a Setting object for the given KeyInfo.
     * First tries to load from local storage, if not found creates a new one.
     * 
     * @param context The application context
     * @param keyInfo The KeyInfo object containing FID information
     * @return The Setting object for the FID
     */
    public Setting getOrCreateSetting(Context context, KeyInfo keyInfo) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        
        if (keyInfo == null || keyInfo.getId() == null) {
            throw new IllegalArgumentException("KeyInfo or KeyInfo ID is null");
        }

        String fid = keyInfo.getId();
        
        // First try to load existing setting
        Setting setting = loadSetting(context, fid);
        if (setting != null) {
            TimberLogger.d("SettingManager", "Loaded existing setting for FID: " + fid);
            return setting;
        }

        // If not found, create new setting
        TimberLogger.d("SettingManager", "Creating new setting for FID: " + fid);
        setting = createNewSetting(context, keyInfo, new HashMap<>());
        
        // Add KeyInfo to configure's mainCidInfoMap
        addKeyInfoToConfigure(keyInfo);
        return setting;
    }

    /**
     * Creates a new Setting object for the given KeyInfo.
     *
     * @param context    The application context
     * @param keyInfo    The KeyInfo object containing FID information
     * @param settingMap The initial setting map to use
     * @return The newly created Setting object
     */
    private Setting createNewSetting(Context context, KeyInfo keyInfo, Map<String, Object> settingMap) {
        Configure configure = ConfigureManager.getInstance().getConfigure();
        if (configure == null) {
            throw new IllegalStateException("Configure is not available from ConfigureManager");
        }

        String fid = keyInfo.getId();
        byte[] symkey = configure.getSymkey();
        
        if (symkey == null) {
            throw new IllegalStateException("Symkey is not available from ConfigureManager");
        }

        // Create new setting with the keyInfo
        Setting setting = new Setting(new HashMap<>(), keyInfo, symkey);
        setting.setSettingMap(settingMap);
        
        // Save to local storage
        saveSettings(context, setting);
        
        TimberLogger.d("SettingManager", "Created and saved new setting for FID: " + fid);
        return setting;
    }

    /**
     * Loads a Setting object from local storage for the given FID.
     * 
     * @param context The application context
     * @param fid The FID to load settings for
     * @return The Setting object if found, null otherwise
     */
    public Setting loadSetting(Context context, String fid) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        
        if (fid == null) {
            throw new IllegalArgumentException("FID is null");
        }
        Configure configure = ConfigureManager.getInstance().getConfigure();

        Map<String, Setting> settingsMap = loadSettingMap(context, getSettingMapKey(configure));

        return settingsMap.get(fid);
    }

    @NonNull
    public static Map<String, Setting> loadSettingMap(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        String settingsJson = prefs.getString(key, "{}");
        return settingMapFromJson(settingsJson);
    }

    /**
     * Saves a Setting object to local storage for the given FID.
     *
     * @param context The application context
     * @param setting The Setting object to save
     */
    public void saveSettings(Context context, Setting setting) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }

        
        if (setting == null) {
            throw new IllegalArgumentException("Setting is null");
        }

        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Configure configure = ConfigureManager.getInstance().getConfigure();

        // Get existing setting map or create new one
        String settingsJson = prefs.getString(getSettingMapKey(configure), "{}");
        Map<String, Setting> settingsMap = settingMapFromJson(settingsJson);
        
        // Add or update the setting object
        settingsMap.put(setting.getFid(), setting);
        
        // Save back to SharedPreferences
        String newSettingMapJson = JsonUtils.toJson(settingsMap);
        editor.putString(getSettingMapKey(configure), newSettingMapJson);
        editor.apply();
        
        TimberLogger.d("SettingManager", "Saved setting for FID: " + setting.getFid());
    }

    @NonNull
    public static String getSettingMapKey(Configure configure) {
        return settingMapKeyFor(configure.getPasswordName());
    }

    /** The preferences key of the settings map of the vault named {@code vaultName}. */
    @NonNull
    public static String settingMapKeyFor(String vaultName) {
        return vaultName + "_" + SETTINGS_KEY;
    }

    /** @return the settings map JSON of the vault named {@code vaultName}, or null if it has none. */
    public static String readSettingMapJson(Context context, String vaultName) {
        return settingsPrefs(context).getString(settingMapKeyFor(vaultName), null);
    }

    /** Writes the settings map JSON of a vault durably. @return false if the write failed. */
    public static boolean commitSettingMapJson(Context context, String vaultName, String json) {
        return settingsPrefs(context).edit().putString(settingMapKeyFor(vaultName), json).commit();
    }

    /** Removes the settings map of a vault durably. @return false if the write failed. */
    public static boolean removeSettingMapJson(Context context, String vaultName) {
        return settingsPrefs(context).edit().remove(settingMapKeyFor(vaultName)).commit();
    }

    private static SharedPreferences settingsPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void saveSettingMap(Context context, String key, Map<String, Setting> settingMap) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(key, JsonUtils.toJson(settingMap));
        editor.apply();
    }

    /**
     * Erases a setting map from SharedPreferences by its key.
     *
     * @param context The application context
     * @param key The key of the setting map to erase
     */
    public static void eraseSettingMap(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(key);
        editor.apply();
        TimberLogger.d("SettingManager", "Erased setting map for key: " + key);
    }

    /**
     * Removes a Setting object from local storage and removes the corresponding KeyInfo from configure.
     * 
     * @param context The application context
     * @param fid The FID to remove settings for
     */
    public void removeSettings(Context context, String fid) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        
        if (fid == null) {
            throw new IllegalArgumentException("FID is null");
        }

        // Remove from local storage
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        String settingsJson = prefs.getString(SETTINGS_KEY, "{}");
        Map<String, Setting> settingsMap = JsonUtils.jsonToMap(settingsJson, String.class, Setting.class);
        
        if (settingsMap != null && settingsMap.containsKey(fid)) {
            settingsMap.remove(fid);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(SETTINGS_KEY, JsonUtils.toJson(settingsMap));
            editor.apply();
            
            TimberLogger.d("SettingManager", "Removed settings for FID: " + fid);
        }

        // Remove KeyInfo from configure's mainCidInfoMap
        removeKeyInfoFromConfigure(fid);
    }

    /**
     * Adds a KeyInfo to the current configure's mainCidInfoMap.
     * 
     * @param keyInfo The KeyInfo to add
     */
    private void addKeyInfoToConfigure(KeyInfo keyInfo) {
        ConfigureManager configureManager = ConfigureManager.getInstance();
        Configure configure = configureManager.getConfigure();
        
        if (configure != null) {
            if (configure.getMainCidInfoMap() == null) {
                configure.setMainCidInfoMap(new HashMap<>());
            }
            
            configure.getMainCidInfoMap().put(keyInfo.getId(), keyInfo);
            
            // Save the updated configure using the static context
            configureManager.storeConfigure(com.fc.freer.model.Configure.getContext(), configure);
            
            TimberLogger.d("SettingManager", "Added KeyInfo to configure for FID: " + keyInfo.getId());
        } else {
            TimberLogger.w("SettingManager", "Configure is null, cannot add KeyInfo");
        }
    }

    /**
     * Removes a KeyInfo from the current configure's mainCidInfoMap.
     * 
     * @param fid The FID to remove
     */
    private void removeKeyInfoFromConfigure(String fid) {
        ConfigureManager configureManager = ConfigureManager.getInstance();
        com.fc.freer.model.Configure configure = configureManager.getConfigure();
        
        if (configure != null && configure.getMainCidInfoMap() != null) {
            configure.getMainCidInfoMap().remove(fid);
            
            // Save the updated configure using the static context
            configureManager.storeConfigure(com.fc.freer.model.Configure.getContext(), configure);
            
            TimberLogger.d("SettingManager", "Removed KeyInfo from configure for FID: " + fid);
        } else {
            TimberLogger.w("SettingManager", "Configure or mainCidInfoMap is null, cannot remove KeyInfo");
        }
    }

    /**
     * Checks if settings exist for the given FID.
     * 
     * @param context The application context
     * @param fid The FID to check
     * @return true if settings exist, false otherwise
     */
    public boolean hasSettings(Context context, String fid) {
        return loadSetting(context, fid) != null;
    }

    /**
     * Gets all FIDs that have settings stored.
     * 
     * @param context The application context
     * @return A set of FIDs that have settings
     */
    public java.util.Set<String> getAllFids(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        Configure configure = ConfigureManager.getInstance().getConfigure();
        Map<String, Setting> settingsMap = loadSettingMap(context, getSettingMapKey(configure));

        return settingsMap.keySet();
    }

    /**
     * Special JSON parser to fully preserve nested KeyInfo (including multisig) when reading settings.
     * The generic Map<String, Setting> parsing can sometimes coerce nested maps in a way
     * that drops complex nested fields. This method reparses each Setting and its keyInfoMap explicitly.
     */
    public static Map<String, Setting> settingMapFromJson(String json) {
        Map<String, Setting> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Gson gson = new GsonBuilder().enableComplexMapKeySerialization().create();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String fid = entry.getKey();
                JsonElement settingElement = entry.getValue();

                Setting setting = gson.fromJson(settingElement, Setting.class);
                if (setting == null) continue;

                if (settingElement.isJsonObject()) {
                    JsonObject settingObj = settingElement.getAsJsonObject();
                    if (settingObj.has("keyInfoMap") && settingObj.get("keyInfoMap").isJsonObject()) {
                        JsonObject keyInfoMapObj = settingObj.getAsJsonObject("keyInfoMap");
                        Map<String, KeyInfo> keyInfoMap = new HashMap<>();
                        for (Map.Entry<String, JsonElement> kiEntry : keyInfoMapObj.entrySet()) {
                            KeyInfo keyInfo = gson.fromJson(kiEntry.getValue(), KeyInfo.class);
                            if (keyInfo != null) {
                                keyInfoMap.put(kiEntry.getKey(), keyInfo);
                            }
                        }
                        setting.setKeyInfoMap(keyInfoMap);
                    }
                }

                result.put(fid, setting);
            }
        } catch (Exception e) {
            TimberLogger.w("SettingManager", "Failed to parse settings with settingMapFromJson: " + e.getMessage());
            // Fallback to generic parsing to avoid hard failures
            Map<String, Setting> fallback = JsonUtils.jsonToMap(json, String.class, Setting.class);
            if (fallback != null) return fallback;
        }
        return result;
    }

    /**
     * Clears all settings from local storage.
     * 
     * @param context The application context
     */
    public void clearAllSettings(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }

        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        
        TimberLogger.d("SettingManager", "Cleared all settings");
    }
} 