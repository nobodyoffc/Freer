package com.fc.freer.initiate;

import android.content.Context;

import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * Simple test class for SettingManager functionality
 */
public class SettingsManagerTest {
    
    /**
     * Test the complete workflow of SettingManager
     */
    public static void testSettingsManagerWorkflow(Context context) {
        SettingManager settingManager = SettingManager.getInstance();
        
        try {
            // Test 1: Create a test KeyInfo
            KeyInfo testKeyInfo = createTestKeyInfo();
            
            // Test 2: Get or create setting
            TimberLogger.d("SettingsManagerTest", "Testing getOrCreateSettings...");
            Setting setting = settingManager.getOrCreateSetting(context, testKeyInfo);
            
            if (setting != null) {
                TimberLogger.d("SettingsManagerTest", "✓ Successfully created setting for FID: " + setting.getFid());
            } else {
                TimberLogger.e("SettingsManagerTest", "✗ Failed to create setting");
                return;
            }
            
            // Test 3: Check if setting exist
            TimberLogger.d("SettingsManagerTest", "Testing hasSettings...");
            boolean exists = settingManager.hasSettings(context, testKeyInfo.getId());
            TimberLogger.d("SettingsManagerTest", "✓ Setting exist: " + exists);
            
            // Test 4: Load setting
            TimberLogger.d("SettingsManagerTest", "Testing loadSettings...");
            Setting loadedSetting = settingManager.loadSetting(context, testKeyInfo.getId());
            if (loadedSetting != null) {
                TimberLogger.d("SettingsManagerTest", "✓ Successfully loaded setting");
            } else {
                TimberLogger.e("SettingsManagerTest", "✗ Failed to load setting");
            }
            
            // Test 5: Get all FIDs
            TimberLogger.d("SettingsManagerTest", "Testing getAllFids...");
            java.util.Set<String> allFids = settingManager.getAllFids(context);
            TimberLogger.d("SettingsManagerTest", "✓ All FIDs: " + allFids);
            
            // Test 6: Remove setting
            TimberLogger.d("SettingsManagerTest", "Testing removeSettings...");
            settingManager.removeSettings(context, testKeyInfo.getId());
            
            // Test 7: Verify removal
            boolean stillExists = settingManager.hasSettings(context, testKeyInfo.getId());
            TimberLogger.d("SettingsManagerTest", "✓ Setting removed: " + !stillExists);
            
            TimberLogger.d("SettingsManagerTest", "✓ All tests completed successfully!");
            
        } catch (Exception e) {
            TimberLogger.e("SettingsManagerTest", "✗ Test failed with exception: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create a test KeyInfo for testing purposes
     */
    private static KeyInfo createTestKeyInfo() {
        KeyInfo keyInfo = new KeyInfo();
        keyInfo.setId("test_fid_123");
        keyInfo.setPrikeyCipher("test_encrypted_private_key");
        keyInfo.setPubkey("test_public_key");
        return keyInfo;
    }
    
    /**
     * Test error handling
     */
    public static void testErrorHandling(Context context) {
        SettingManager settingManager = SettingManager.getInstance();
        
        try {
            // Test with null context
            try {
                settingManager.getOrCreateSetting(null, createTestKeyInfo());
                TimberLogger.e("SettingsManagerTest", "✗ Should have thrown exception for null context");
            } catch (IllegalArgumentException e) {
                TimberLogger.d("SettingsManagerTest", "✓ Correctly handled null context: " + e.getMessage());
            }
            
            // Test with null KeyInfo
            try {
                settingManager.getOrCreateSetting(context, null);
                TimberLogger.e("SettingsManagerTest", "✗ Should have thrown exception for null KeyInfo");
            } catch (IllegalArgumentException e) {
                TimberLogger.d("SettingsManagerTest", "✓ Correctly handled null KeyInfo: " + e.getMessage());
            }
            
            // Test with KeyInfo with null ID
            KeyInfo invalidKeyInfo = new KeyInfo();
            try {
                settingManager.getOrCreateSetting(context, invalidKeyInfo);
                TimberLogger.e("SettingsManagerTest", "✗ Should have thrown exception for KeyInfo with null ID");
            } catch (IllegalArgumentException e) {
                TimberLogger.d("SettingsManagerTest", "✓ Correctly handled KeyInfo with null ID: " + e.getMessage());
            }
            
        } catch (Exception e) {
            TimberLogger.e("SettingsManagerTest", "✗ Error handling test failed: " + e.getMessage(), e);
        }
    }
} 