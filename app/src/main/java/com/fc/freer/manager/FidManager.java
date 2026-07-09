package com.fc.freer.manager;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.im.PendingIssueManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private ToastManager toastManager;
    private AppManager appManager;
    private CashManager cashManager;
    private SecretManager secretManager;
    private ContactManager contactManager;
    private MailManager mailManager;
    private FcObjectManager fcObjectManager;
    private ProofManager proofManager;
    private ImManager imManager;
    private PendingIssueManager pendingIssueManager;

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
                callback.onSwitchComplete(false, context.getString(R.string.no_fid_switch_needed));
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
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            callback.onSwitchComplete(true, context.getString(R.string.fid_switched));
                        });
                    } else {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> {
                            callback.onSwitchComplete(true, context.getString(R.string.fid_switched));
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
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> {
                            callback.onSwitchComplete(false, context.getString(R.string.failed_to_switch_fid,e.getMessage()));
                        });
                    } else {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> {
                            callback.onSwitchComplete(false, context.getString(R.string.failed_to_switch_fid,e.getMessage()));
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

        toastManager = ToastManager.getInstance(context,liveFid );
        
        // Reload managers for the new liveFid
        for (FcManager.ManagerType managerType : currentSetting.getManagers()) {
            switch (managerType) {
                case APP:
                    appManager = AppManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded AppManager for liveFid: %s", liveFid);
                    break;

                case CASH:
                    cashManager = CashManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded CashManager for liveFid: %s", liveFid);
                    break;
                    
                case SECRET:
                    secretManager = SecretManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded SecretManager for liveFid: %s", liveFid);
                    break;

                case CONTACT:
                    contactManager = ContactManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded ContactManager for liveFid: %s", liveFid);
                    break;

                case MAIL:
                    mailManager = MailManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded MailManager for liveFid: %s", liveFid);
                    break;

                case FC_OBJECT:
                    fcObjectManager = FcObjectManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded FcObjectManager for liveFid: %s", liveFid);
                    break;

                case PROOF:
                    proofManager = ProofManager.getInstance(context, liveFid);
                    TimberLogger.d(TAG, "Reloaded ProofManager for liveFid: %s", liveFid);
                    break;

                default:
                    TimberLogger.w(TAG, "Unknown manager type: %s", managerType);
                    break;
            }
        }

        // Re-create ImManager for the current mainFid
        FapiClient fapiClient = null;
        ApiCenter apiCenter = ApiCenter.getInstance();
        if (apiCenter != null) {
            fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
        }
        imManager = currentSetting.getOrCreateImManager(context, fapiClient);
        if (imManager != null) {
            pendingIssueManager = new PendingIssueManager(context, mainFid, null);
            imManager.setPendingIssueManager(pendingIssueManager);
            TimberLogger.d(TAG, "Reloaded ImManager and PendingIssueManager for mainFid: %s", mainFid);
        }
    }
    
    /**
     * Close existing managers to free resources
     */
    private void closeExistingManagers() {
        // CashManager, SecretManager, ContactManager, MailManager, and ProofManager instances are managed by their singleton patterns
        // We don't need to explicitly close them as they handle their own lifecycle
        appManager = null;
        cashManager = null;
        secretManager = null;
        contactManager = null;
        mailManager = null;
        toastManager = null;
        fcObjectManager = null;
        proofManager = null;

        if (imManager != null) {
            imManager.stop();
            imManager = null;
            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting != null) {
                currentSetting.setImManager(null);
            }
        }
        pendingIssueManager = null;

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
        
        // 4. Multisig FIDs
        List<KeyInfo> multisignList = currentSetting.getMultisigKeyInfoList();
        if (multisignList != null) {
            for (KeyInfo keyInfo : multisignList) {
                options.add(new FidOption(keyInfo.getId(), keyInfo, FidType.MULTISIG, "Multisig"));
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
    public ContactManager getContactManager() { return contactManager; }
    public MailManager getMailManager() { return mailManager; }
    public FcObjectManager getFcObjectManager() { return fcObjectManager; }
    public ProofManager getProofManager() { return proofManager; }
    public ImManager getImManager() { return imManager; }

    public ToastManager getToastManager() {
        return toastManager;
    }

    public void setToastManager(ToastManager toastManager) {
        this.toastManager = toastManager;
    }

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
     * Full cleanup for password change - closes all managers and resets all state
     * This is more thorough than reset() as it's specifically for password change scenarios
     */
    public void fullCleanup() {
        TimberLogger.d(TAG, "Starting full cleanup for password change");

        // Close all managers
        closeExistingManagers();

        // Reset all FID state
        mainFid = null;
        liveFid = null;
        mainKeyInfo = null;
        liveKeyInfo = null;

        TimberLogger.d(TAG, "FidManager full cleanup completed");
    }
    
    /**
     * Enum for different types of FIDs
     */
    public enum FidType {
        MAIN,       // mainFid itself
        MASTER,     // Master of mainFid
        WATCHED,    // Watched FID
        MULTISIG,  // Multisig FID
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

    public void setAppManager(AppManager appManager) {
        this.appManager = appManager;
    }

    public void setCashManager(CashManager cashManager) {
        this.cashManager = cashManager;
    }

    public void setSecretManager(SecretManager secretManager) {
        this.secretManager = secretManager;
    }

    public void setContactManager(ContactManager contactManager) {
        this.contactManager = contactManager;
    }

    public void setMailManager(MailManager mailManager) {
        this.mailManager = mailManager;
    }

    public void setFcObjectManager(FcObjectManager fcObjectManager) {
        this.fcObjectManager = fcObjectManager;
    }

    public void setProofManager(ProofManager proofManager) {
        this.proofManager = proofManager;
    }

    public void setImManager(ImManager imManager) {
        this.imManager = imManager;
    }

    public PendingIssueManager getPendingIssueManager() { return pendingIssueManager; }

    public void setPendingIssueManager(PendingIssueManager pendingIssueManager) {
        this.pendingIssueManager = pendingIssueManager;
    }

    public void setLiveKeyInfo(KeyInfo liveKeyInfo) {
        this.liveKeyInfo = liveKeyInfo;
    }
    
    /**
     * Refresh cidInfo from API for the live FID and update KeyInfo.
     *
     * @param onComplete invoked on the main thread with {@code true} when the refresh
     *                   succeeded and KeyInfo was updated, or {@code false} on any failure
     *                   (no live FID, API unavailable, fetch/update failed). May be null.
     */
    public void refreshLiveFidCidInfoAsync(Context context, Consumer<Boolean> onComplete) {
        if (liveFid == null) {
            TimberLogger.w(TAG, "No live FID available for cidInfo refresh");
            postRefreshResult(context, onComplete, false);
            return;
        }

        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) {
                    TimberLogger.w(TAG, "ApiCenter not available for freerInfo refresh");
                    // Do not show topUp dialog: we don't know if liveFid has zero balance without API
                    postRefreshResult(context, onComplete, false);
                    return;
                }

                FapiClient fapiClient = (FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null || !fapiClient.isConnected()) {
                    TimberLogger.w(TAG, "FAPI client not available for freerInfo refresh (client=%s, connected=%s)",
                            fapiClient != null ? "exists" : "null",
                            fapiClient != null ? fapiClient.isConnected() : "N/A");
                    // Do not show topUp dialog: network/default fapiClient not ready, we don't know balance
                    postRefreshResult(context, onComplete, false);
                    return;
                }

                // Fetch fresh freerInfo from API
                Freer freerInfo = fapiClient.getFreer(liveFid);

                if (freerInfo != null) {
                    TimberLogger.d(TAG, "API returned freerInfo for FID %s - Cash: %s, Balance: %s, CD: %s",
                        liveFid, freerInfo.getCash(), freerInfo.getBalance(), freerInfo.getCd());

                    // Check if balance is zero or null - show topup prompt if needed
                    Long balance = freerInfo.getBalance();
                    if (balance == null || balance == 0) {
                        checkTopUpIfNeeded(context);
                    } else {
                        // Funded (e.g. the first FCH just arrived on a request-board
                        // ask): if no DOCK is registered yet the user is still
                        // unreachable — prompt the server setup now.
                        checkDockSetupAfterFunding(context, freerInfo);
                    }

                    // Update KeyInfo with fresh data while preserving user-specific data
                    KeyInfo currentKeyInfo = getLiveKeyInfo();
                    if (currentKeyInfo != null) {
                        TimberLogger.d(TAG, "Current KeyInfo before update - Cash: %s, Balance: %s, CD: %s",
                            currentKeyInfo.getCash(), currentKeyInfo.getBalance(), currentKeyInfo.getCd());

                        KeyInfo updatedKeyInfo = KeyInfo.updateFromCid(freerInfo,currentKeyInfo);

                        if(updatedKeyInfo.getPrikey()!=null)
                            updatedKeyInfo.setNobody(Boolean.TRUE);

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

                            // Check if CID needs to be set (after we have fresh API data)
                            checkSetCidIfNeeded(context, freerInfo);

                            // Refresh succeeded and KeyInfo was updated
                            postRefreshResult(context, onComplete, true);
                        } else {
                            TimberLogger.w(TAG, "Failed to update KeyInfo for FID: %s", liveFid);
                            postRefreshResult(context, onComplete, false);
                        }
                    } else {
                        TimberLogger.w(TAG, "Current KeyInfo is null, cannot update");
                        postRefreshResult(context, onComplete, false);
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch freerInfo for live FID: %s", liveFid);
                    checkTopUpIfNeeded(context);
                    postRefreshResult(context, onComplete, false);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for live FID %s: %s", liveFid, e.getMessage());
                postRefreshResult(context, onComplete, false);
            }
        }).start();
    }

    /**
     * Deliver the refresh result to {@code onComplete} on the main thread, if provided.
     */
    private void postRefreshResult(Context context, Consumer<Boolean> onComplete, boolean success) {
        if (onComplete == null) return;
        Runnable deliver = () -> onComplete.accept(success);
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(deliver);
        } else {
            new Handler(Looper.getMainLooper()).post(deliver);
        }
    }

    /** FIDs already prompted for DOCK setup after funding, once per app run. */
    private static final java.util.Set<String> dockPromptedFids =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * A freshly funded FID without home.DOCK can send but not receive messages
     * (send-only mode). Now that it can afford the registration TX, prompt the
     * server setup once per app run. Typical path: a newcomer asked for their
     * first FCH on the request board and the coins just arrived.
     */
    private void checkDockSetupAfterFunding(Context context, Freer freerInfo) {
        try {
            String mainFid = getMainFid();
            if (mainFid == null || dockPromptedFids.contains(mainFid)) return;

            Map<String, String> home = freerInfo.getHome();
            if (home != null) {
                for (Map.Entry<String, String> entry : home.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key != null && key.startsWith("DOCK")
                            && value != null && !value.trim().isEmpty()) {
                        return; // DOCK already registered
                    }
                }
            }

            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            com.fc.freer.im.ImManager imManager =
                    currentSetting != null ? currentSetting.getImManager() : null;
            if (imManager != null && imManager.isRegistrationPending()) return;

            if (context instanceof android.app.Activity activity) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                dockPromptedFids.add(mainFid);
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        com.fc.freer.im.ChannelSetupDialog.show(activity, null);
                    }
                });
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error checking DOCK setup after funding: " + e.getMessage(), e);
        }
    }

    /**
     * Check if top-up prompt should be shown based on balance
     * Shows TopupPromptDialog if:
     * 1. User has not already been prompted for top-up
     * 2. Main KeyInfo balance is null or 0
     */
    private void checkTopUpIfNeeded(Context context) {
        try {
            TimberLogger.i(TAG, "checkTopUpIfNeeded called. cashManager field=%s, CashManager.getInstance()=%s",
                    cashManager != null ? "set(dbSize=" + cashManager.getCashDBSize() + ")" : "null",
                    CashManager.getInstance() != null ? "set(dbSize=" + CashManager.getInstance().getCashDBSize() + ")" : "null");
            
            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                TimberLogger.w(TAG, "No current setting available, skipping topUp check");
                return;
            }

            // Check if user has already been prompted for topUp
            Boolean promotedTopUp = (Boolean) currentSetting.getStateMap().get(com.fc.freer.model.Setting.KEY_PROMOTED_TOP_UP);
            if (promotedTopUp != null && promotedTopUp) {
                TimberLogger.d(TAG, "User has already been prompted for topUp, skipping");
                return;
            }

            // Get main KeyInfo
            KeyInfo mainKeyInfo = getMainKeyInfo();
            if (mainKeyInfo == null) {
                TimberLogger.w(TAG, "Main KeyInfo not available, skipping topUp check");
                return;
            }

            // Check if balance is null or 0
            Long balance = mainKeyInfo.getBalance();
            if (balance == null || balance == 0) {
                // Before showing "ask FCH from others" dialog, check local CashManager
                // Try instance field first, then static singleton (it may be initialized before reloadManagers)
                CashManager cm = cashManager;
                if (cm == null) {
                    cm = CashManager.getInstance();
                    TimberLogger.d(TAG, "checkTopUpIfNeeded: cashManager field is null, static CashManager.getInstance()=%s",
                            cm != null ? "available" : "null");
                }
                if (cm != null) {
                    long dbSize = cm.getCashDBSize();
                    boolean hasValid = cm.hasValidCashes();
                    TimberLogger.i(TAG, "checkTopUpIfNeeded: CashManager dbSize=%d, hasValidCashes=%b", dbSize, hasValid);
                    if (hasValid) {
                        TimberLogger.i(TAG, "On-chain balance is zero but local CashManager has valid cashes (%d). Skipping topUp dialog.", dbSize);
                        return;
                    }
                } else {
                    TimberLogger.w(TAG, "checkTopUpIfNeeded: CashManager not available at all (both field and singleton are null)");
                }

                TimberLogger.d(TAG, "Balance is zero and no local cashes, showing topUp prompt dialog");

                String mainFid = getMainFid();
                if (mainFid == null) {
                    TimberLogger.w(TAG, "Main FID not available, skipping topUp prompt");
                    return;
                }

                // Show top up prompt dialog on main thread
                if (context instanceof android.app.Activity activity) {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        TimberLogger.w(TAG, "Activity is finishing/destroyed, skipping topUp prompt");
                        return;
                    }
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            showTopupPromptDialog(context, currentSetting, mainFid);
                        }
                    });
                } else {
                    TimberLogger.w(TAG, "Context is not an Activity, skipping topUp prompt dialog");
                }
            } else {
                TimberLogger.d(TAG, "Balance is not zero (%d), skipping topUp prompt", balance);
            }
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error checking topUp: " + ex.getMessage(), ex);
        }
    }

    /**
     * Show the topup prompt dialog
     */
    private void showTopupPromptDialog(Context context, Setting currentSetting, String mainFid) {
        try {
            if (context instanceof android.app.Activity activity
                    && (activity.isFinishing() || activity.isDestroyed())) {
                TimberLogger.w(TAG, "Activity no longer valid, skipping topUp dialog");
                return;
            }
            com.fc.freer.ui.TopupPromptDialog topUpDialog = new com.fc.freer.ui.TopupPromptDialog(context, mainFid, () -> {
                TimberLogger.d(TAG, "User chose to ignore topUp prompt");
                // Update the state to indicate user has been prompted
                currentSetting.getStateMap().put(com.fc.freer.model.Setting.KEY_PROMOTED_TOP_UP, true);

                // Save the updated setting
                SettingManager.getInstance().saveSettings(context, currentSetting);
            });

            topUpDialog.show();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing topup prompt dialog: " + e.getMessage(), e);
        }
    }

    /**
     * Check if SetCidActivity should be shown based on fresh API data
     * Shows SetCidActivity if:
     * 1. User has not already been prompted to set CID
     * 2. CID is not set (null or empty from API)
     * 3. Balance is greater than 0
     */
    private void checkSetCidIfNeeded(Context context, Freer freerInfo) {
        try {
            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                TimberLogger.w(TAG, "No current setting available, skipping CID check");
                return;
            }

            // Check if user has already been prompted to set CID
            Boolean promotedSetCid = (Boolean) currentSetting.getStateMap().get(com.fc.freer.model.Setting.KEY_PROMOTED_SET_CID);
            if (promotedSetCid != null && promotedSetCid) {
                TimberLogger.d(TAG, "User has already been prompted to set CID, skipping");
                return;
            }

            // Check if CID is null or empty from API data
            String cid = freerInfo.getCid();
            if (cid != null && !cid.trim().isEmpty()) {
                TimberLogger.d(TAG, "CID already set: %s, skipping CID prompt", cid);
                return;
            }

            // Check if balance is greater than 0
            Long balance = freerInfo.getBalance();
            if (balance == null || balance <= 0) {
                TimberLogger.d(TAG, "Balance is zero or null (%s), skipping CID prompt", balance);
                return;
            }

            // All conditions met - show SetCidActivity on main thread
            TimberLogger.d(TAG, "CID is not set and balance is positive (%d), showing SetCidActivity", balance);

            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(() -> {
                    showSetCidActivity(context);
                });
            } else {
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    showSetCidActivity(context);
                });
            }

        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error checking CID: " + ex.getMessage(), ex);
        }
    }

    /**
     * Show SetCidActivity
     */
    private void showSetCidActivity(Context context) {
        try {
            if (!(context instanceof android.app.Activity)) {
                TimberLogger.w(TAG, "Context is not an Activity, cannot show SetCidActivity");
                return;
            }

            android.content.Intent intent = new android.content.Intent(context, com.fc.freer.home.SetCidActivity.class);
            ((android.app.Activity) context).startActivityForResult(intent, 9997); // Using 9997 as request code for SetCidActivity
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing SetCidActivity: " + e.getMessage(), e);
        }
    }
}