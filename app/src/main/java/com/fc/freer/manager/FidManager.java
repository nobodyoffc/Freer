package com.fc.freer.manager;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.network.ApipClient;
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.List;

/**
 * FidManager - Manages mainFid and liveFid runtime state
 * 
 * Responsibilities:
 * - Track mainFid (the authenticated user with settings and ApiCenter)
 * - Track liveFid (the currently active FID being operated on)
 * - Handle switching between different FIDs
 * - Reload managers when liveFid changes
 */
public class FidManager {
    private static final String TAG = "FidManager";
    
    private static FidManager instance;
    
    // Runtime state
    private String mainFid;           // The authenticated user's FID
    private String liveFid;           // The currently active FID being operated on
    private KeyInfo mainKeyInfo;      // KeyInfo for mainFid
    private KeyInfo liveKeyInfo;      // KeyInfo for liveFid
    
    // Manager instances for current liveFid
    private CashManager cashManager;
    private SecretManager secretManager;

    private FidManager() {
        // Private constructor for singleton
    }
    
    public static synchronized FidManager getInstance() {
        if (instance == null) {
            instance = new FidManager();
        }
        return instance;
    }
    
    /**
     * Initialize with mainFid after authentication
     */
    public void initialize(String mainFid, KeyInfo mainKeyInfo) {
        this.mainFid = mainFid;
        this.mainKeyInfo = mainKeyInfo;
        
        // Initially, liveFid is the same as mainFid
        this.liveFid = mainFid;
        this.liveKeyInfo = mainKeyInfo;
        
        TimberLogger.d(TAG, "Initialized FidManager with mainFid: %s, liveFid: %s", mainFid, liveFid);
    }
    
    /**
     * Switch to a different liveFid and reload managers (synchronous version)
     */
    public boolean switchLiveFid(Context context, String newLiveFid, KeyInfo newLiveKeyInfo) {
        if (newLiveFid == null || newLiveFid.equals(liveFid)) {
            TimberLogger.d(TAG, "No FID switch needed, current liveFid: %s", liveFid);
            return false;
        }
        
        TimberLogger.d(TAG, "Switching liveFid from %s to %s", liveFid, newLiveFid);
        
        String previousLiveFid = liveFid;
        KeyInfo previousLiveKeyInfo = liveKeyInfo;
        
        // Update liveFid state
        this.liveFid = newLiveFid;
        this.liveKeyInfo = newLiveKeyInfo;
        
        try {
            // Reload managers for the new liveFid
            reloadManagers(context);
            
            TimberLogger.d(TAG, "Successfully switched liveFid from %s to %s", previousLiveFid, liveFid);
            return true;
            
        } catch (Exception e) {
            // Rollback on error
            this.liveFid = previousLiveFid;
            this.liveKeyInfo = previousLiveKeyInfo;
            
            TimberLogger.e(TAG, "Failed to switch liveFid, rolled back: %s", e.getMessage());
            return false;
        }
    }
    
    /**
     * Switch to a different liveFid and reload managers (asynchronous version with callbacks)
     */
    public void switchLiveFidAsync(Context context, String newLiveFid, KeyInfo newLiveKeyInfo, FidSwitchCallback callback) {
        if (newLiveFid == null || newLiveFid.equals(liveFid)) {
            TimberLogger.d(TAG, "No FID switch needed, current liveFid: %s", liveFid);
            if (callback != null) {
                callback.onSwitchComplete(false, "No FID switch needed");
            }
            return;
        }
        
        // Execute switching on background thread
        new Thread(() -> {
            try {
                TimberLogger.d(TAG, "Async switching liveFid from %s to %s", liveFid, newLiveFid);
                
                String previousLiveFid = liveFid;
                KeyInfo previousLiveKeyInfo = liveKeyInfo;
                
                // Update liveFid state
                this.liveFid = newLiveFid;
                this.liveKeyInfo = newLiveKeyInfo;
                
                // Reload managers for the new liveFid
                reloadManagers(context);
                
                TimberLogger.d(TAG, "Successfully switched liveFid from %s to %s", previousLiveFid, liveFid);
                
                // Notify success on main thread
                if (callback != null) {
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(() -> {
                            callback.onSwitchComplete(true, "FID switched successfully");
                        });
                    } else {
                        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                        mainHandler.post(() -> {
                            callback.onSwitchComplete(true, "FID switched successfully");
                        });
                    }
                }
                
            } catch (Exception e) {
                // Rollback on error
                this.liveFid = liveFid; // Keep current state
                this.liveKeyInfo = liveKeyInfo;
                
                TimberLogger.e(TAG, "Failed to switch liveFid async: %s", e.getMessage());
                
                // Notify error on main thread
                if (callback != null) {
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(() -> {
                            callback.onSwitchComplete(false, "Failed to switch FID: " + e.getMessage());
                        });
                    } else {
                        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                        mainHandler.post(() -> {
                            callback.onSwitchComplete(false, "Failed to switch FID: " + e.getMessage());
                        });
                    }
                }
            }
        }).start();
    }
    
    /**
     * Callback interface for async FID switching
     */
    public interface FidSwitchCallback {
        void onSwitchComplete(boolean success, String message);
    }
    
    /**
     * Switch back to mainFid
     */
    public boolean switchToMainFid(Context context) {
        return switchLiveFid(context, mainFid, mainKeyInfo);
    }
    
    /**
     * Reload all managers for the current liveFid
     * Note: AvatarManager is global and doesn't depend on FID
     */
    private void reloadManagers(Context context) {
        TimberLogger.d(TAG, "Reloading managers for liveFid: %s", liveFid);
        
        // Get current setting to check which managers are enabled
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null || currentSetting.getManagers() == null) {
            TimberLogger.w(TAG, "No current setting or managers list available");
            return;
        }
        
        // Close existing managers if they exist
        closeExistingManagers();
        
        // Reload managers for the new liveFid
        for (FcManager.ManagerType managerType : currentSetting.getManagers()) {
            switch (managerType) {
                case CASH:
                    cashManager = CashManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded CashManager for liveFid: %s", liveFid);
                    break;
                    
                case SECRET:
                    secretManager = SecretManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded SecretManager for liveFid: %s", liveFid);
                    break;
                    
                default:
                    TimberLogger.w(TAG, "Unknown manager type: %s", managerType);
                    break;
            }
        }
    }
    
    /**
     * Close existing managers to free resources
     */
    private void closeExistingManagers() {
        // CashManager and SecretManager instances are managed by their singleton patterns
        // We don't need to explicitly close them as they handle their own lifecycle
        cashManager = null;
        secretManager = null;
        
        TimberLogger.d(TAG, "Closed existing managers");
    }
    
    /**
     * Get all available FIDs that can be switched to
     */
    public List<FidOption> getAvailableFids() {
        List<FidOption> options = new ArrayList<>();
        
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null) {
            return options;
        }
        
        // 1. Main FID (always available)
        options.add(new FidOption(mainFid, mainKeyInfo, FidType.MAIN, "Main Identity"));
        
        // 2. Master FID (if available)
        KeyInfo masterKeyInfo = currentSetting.getKeyInfoMap().get(mainKeyInfo.getMaster());
        if (masterKeyInfo != null) {
            options.add(new FidOption(masterKeyInfo.getId(), masterKeyInfo, FidType.MASTER, "My Master"));
        }
        
        // 3. Watched FIDs
        List<KeyInfo> watchedList = currentSetting.getWatchedKeyInfoList();
        if (watchedList != null) {
            for (KeyInfo keyInfo : watchedList) {
                options.add(new FidOption(keyInfo.getId(), keyInfo, FidType.WATCHED, "Watched"));
            }
        }
        
        // 4. Multisign FIDs
        List<KeyInfo> multisignList = currentSetting.getMultisignKeyInfoList();
        if (multisignList != null) {
            for (KeyInfo keyInfo : multisignList) {
                options.add(new FidOption(keyInfo.getId(), keyInfo, FidType.MULTISIGN, "Multisign"));
            }
        }
        
        // 5. Servant FIDs
        List<KeyInfo> servantList = currentSetting.getServantKeyInfoList();
        if (servantList != null) {
            for (KeyInfo keyInfo : servantList) {
                options.add(new FidOption(keyInfo.getId(), keyInfo, FidType.SERVANT, "Servant"));
            }
        }
        
        return options;
    }
    
    // Getters
    public String getMainFid() { return mainFid; }
    public String getLiveFid() { return liveFid; }
    public KeyInfo getMainKeyInfo() { return mainKeyInfo; }
    public KeyInfo getLiveKeyInfo() { return liveKeyInfo; }
    
    public CashManager getCashManager() { return cashManager; }
    public SecretManager getSecretManager() { return secretManager; }

    /**
     * Check if current liveFid is the mainFid
     */
    public boolean isLiveFidMain() {
        return mainFid != null && mainFid.equals(liveFid);
    }
    
    /**
     * Get KeyInfo for any FID from the Setting's keyInfoMap
     */
    public KeyInfo getKeyInfoByFid(String fid) {
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        return currentSetting != null ? currentSetting.getKeyInfo(fid) : null;
    }
    
    /**
     * Update KeyInfo in the Setting's keyInfoMap and save settings
     */
    public boolean updateKeyInfo(Context context, String fid, KeyInfo keyInfo) {
        try {
            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                TimberLogger.w(TAG, "Current setting not available for KeyInfo update");
                return false;
            }
            
            // Update KeyInfo in the keyInfoMap
            currentSetting.updateKeyInfo(fid, keyInfo);
            
            // If this is the live FID, also update our local reference
            if (fid.equals(liveFid)) {
                this.liveKeyInfo = keyInfo;
            }
            
            // If this is the main FID, also update our local reference
            if (fid.equals(mainFid)) {
                this.mainKeyInfo = keyInfo;
            }
            
            // Save settings to persist the change
            SettingManager.getInstance().saveSettings(context, currentSetting);
            
            // Also update the Configure's mainCidInfoMap if this is the main FID
            if (fid.equals(mainFid)) {
                com.fc.freer.initiate.ConfigureManager configureManager = com.fc.freer.initiate.ConfigureManager.getInstance();
                com.fc.freer.model.Configure configure = configureManager.getConfigure();
                if (configure != null) {
                    if (configure.getMainCidInfoMap() == null) {
                        configure.setMainCidInfoMap(new java.util.HashMap<>());
                    }
                    configure.getMainCidInfoMap().put(fid, keyInfo);
                    configureManager.storeConfigure(context, configure);
                    TimberLogger.d(TAG, "Updated mainKeyInfo in Configure's mainCidInfoMap for FID: %s", fid);
                }
            }
            
            TimberLogger.d(TAG, "Successfully updated and saved KeyInfo for FID: %s", fid);
            return true;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to update KeyInfo for FID %s: %s", fid, e.getMessage());
            return false;
        }
    }
    
    /**
     * Reset the FidManager (for logout/cleanup)
     */
    public void reset() {
        closeExistingManagers();
        mainFid = null;
        liveFid = null;
        mainKeyInfo = null;
        liveKeyInfo = null;
        
        TimberLogger.d(TAG, "FidManager reset completed");
    }
    
    /**
     * Enum for different types of FIDs
     */
    public enum FidType {
        MAIN,       // mainFid itself
        MASTER,     // Master of mainFid
        WATCHED,    // Watched FID
        MULTISIGN,  // Multisign FID
        SERVANT     // Servant FID
    }
    
    /**
     * Class representing a FID option for switching
     */
    public static class FidOption {
        public final String fid;
        public final KeyInfo keyInfo;
        public final FidType type;
        public final String displayName;
        
        public FidOption(String fid, KeyInfo keyInfo, FidType type, String displayName) {
            this.fid = fid;
            this.keyInfo = keyInfo;
            this.type = type;
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName + " (" + fid + ")";
        }
    }

    public void setCashManager(CashManager cashManager) {
        this.cashManager = cashManager;
    }

    public void setSecretManager(SecretManager secretManager) {
        this.secretManager = secretManager;
    }

    public void setLiveKeyInfo(KeyInfo liveKeyInfo) {
        this.liveKeyInfo = liveKeyInfo;
    }
    
    /**
     * Refresh cidInfo from API for the live FID and update KeyInfo
     */
    public void refreshLiveFidCidInfoAsync(Context context, Runnable onComplete) {
        if (liveFid == null) {
            TimberLogger.w(TAG, "No live FID available for cidInfo refresh");
            return;
        }
        
        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) {
                    TimberLogger.w(TAG, "ApiCenter not available for cidInfo refresh");
                    return;
                }
                
                ApipClient apipClient = (ApipClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.APIP);
                if (apipClient == null || !apipClient.getConnected()) {
                    TimberLogger.w(TAG, "APIP client not available for cidInfo refresh");
                    return;
                }
                
                // Fetch fresh cidInfo from API
                com.fc.fc_ajdk.data.fchData.Cid cidInfo = apipClient.cidInfoById(liveFid, 
                    com.fc.fc_ajdk.utils.http.RequestMethod.POST, 
                    com.fc.fc_ajdk.utils.http.AuthType.FC_SIGN_BODY, 
                    context);
                    
                if (cidInfo != null) {
                    TimberLogger.d(TAG, "API returned cidInfo for FID %s - Cash: %s, Balance: %s, CD: %s", 
                        liveFid, cidInfo.getCash(), cidInfo.getBalance(), cidInfo.getCd());
                    
                    // Update KeyInfo with fresh data while preserving user-specific data
                    KeyInfo currentKeyInfo = getLiveKeyInfo();
                    if (currentKeyInfo != null) {
                        TimberLogger.d(TAG, "Current KeyInfo before update - Cash: %s, Balance: %s, CD: %s", 
                            currentKeyInfo.getCash(), currentKeyInfo.getBalance(), currentKeyInfo.getCd());
                            
                        KeyInfo updatedKeyInfo = KeyInfo.updateFromCid(cidInfo,currentKeyInfo);
                        
                        TimberLogger.d(TAG, "Updated KeyInfo after fromCid - Cash: %s, Balance: %s, CD: %s", 
                            updatedKeyInfo.getCash(), updatedKeyInfo.getBalance(), updatedKeyInfo.getCd());
                        
                        // Update in FidManager and save
                        if (updateKeyInfo(context, liveFid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Successfully updated KeyInfo for FID: %s", liveFid);
                            
                            // Verify the updated KeyInfo is correct
                            KeyInfo verifyKeyInfo = getLiveKeyInfo();
                            TimberLogger.d(TAG, "Verification - KeyInfo after save - Cash: %s, Balance: %s, CD: %s", 
                                verifyKeyInfo != null ? verifyKeyInfo.getCash() : "null KeyInfo", 
                                verifyKeyInfo != null ? verifyKeyInfo.getBalance() : "null KeyInfo", 
                                verifyKeyInfo != null ? verifyKeyInfo.getCd() : "null KeyInfo");
                            
                            // Call completion callback on main thread if provided
                            if (onComplete != null) {
                                if (context instanceof android.app.Activity) {
                                    ((android.app.Activity) context).runOnUiThread(onComplete);
                                } else {
                                    // For non-Activity contexts, use Handler to post to main thread
                                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                    mainHandler.post(onComplete);
                                }
                            }
                        } else {
                            TimberLogger.w(TAG, "Failed to update KeyInfo for FID: %s", liveFid);
                        }
                    } else {
                        TimberLogger.w(TAG, "Current KeyInfo is null, cannot update");
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch cidInfo for live FID: %s", liveFid);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for live FID %s: %s", liveFid, e.getMessage());
            }
        }).start();
    }
}