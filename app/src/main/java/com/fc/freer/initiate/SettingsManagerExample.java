package com.fc.freer.initiate;

import android.content.Context;

import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * Example usage of SettingManager
 */
public class SettingsManagerExample {
    
    /**
     * Example of how to use SettingManager to get or create settings for a KeyInfo
     */
    public static Setting getOrCreateSettingsExample(Context context, KeyInfo keyInfo) {
        SettingManager settingManager = SettingManager.getInstance();
        
        try {
            // This will either load existing setting or create new ones
            Setting setting = settingManager.getOrCreateSetting(context, keyInfo);
            
            TimberLogger.d("SettingsManagerExample", "Successfully got setting for FID: " + keyInfo.getId());
            return setting;
            
        } catch (Exception e) {
            TimberLogger.e("SettingsManagerExample", "Error getting settings: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Example of how to remove settings for a FID
     */
    public static void removeSettingsExample(Context context, String fid) {
        SettingManager settingManager = SettingManager.getInstance();
        
        try {
            settingManager.removeSettings(context, fid);
            TimberLogger.d("SettingsManagerExample", "Successfully removed settings for FID: " + fid);
            
        } catch (Exception e) {
            TimberLogger.e("SettingsManagerExample", "Error removing settings: " + e.getMessage(), e);
        }
    }
    
    /**
     * Example of how to check if settings exist for a FID
     */
    public static boolean checkSettingsExistExample(Context context, String fid) {
        SettingManager settingManager = SettingManager.getInstance();
        
        try {
            boolean exists = settingManager.hasSettings(context, fid);
            TimberLogger.d("SettingsManagerExample", "Setting exist for FID " + fid + ": " + exists);
            return exists;
            
        } catch (Exception e) {
            TimberLogger.e("SettingsManagerExample", "Error checking settings: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Example of how to get all FIDs that have settings
     */
    public static void getAllFidsExample(Context context) {
        SettingManager settingManager = SettingManager.getInstance();
        
        try {
            java.util.Set<String> allFids = settingManager.getAllFids(context);
            TimberLogger.d("SettingsManagerExample", "All FIDs with settings: " + allFids);
            
        } catch (Exception e) {
            TimberLogger.e("SettingsManagerExample", "Error getting all FIDs: " + e.getMessage(), e);
        }
    }
} 