package com.fc.freer.manager;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyRegistry;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.freer.onboarding.LiveFidRecord;
import com.fc.freer.onboarding.PendingIdentityCarves;
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
    
    /** How long to wait for the FAPI connection before a balance refresh gives up. */
    private static final long CONNECT_WAIT_MS = 6000;
    private static final long CONNECT_POLL_INTERVAL_MS = 200;

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

    /**
     * A multisig (P2SH, '3'-prefixed) FID is a script address, not a key pair: it has no
     * private key to decrypt with and no public key to encrypt to. It therefore cannot take
     * part in encrypted messaging or private data storage, which makes home.DOCK/home.DISK
     * registration meaningless for it.
     */
    public static boolean isMultisigFid(String fid) {
        return fid != null && fid.startsWith("3");
    }

    /** Whether the currently active FID is a multisig FID. */
    public boolean isLiveFidMultisig() { return isMultisigFid(liveFid); }

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
                    postRefreshResult(context, onComplete, false);
                    return;
                }

                FapiClient fapiClient = (FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) {
                    TimberLogger.w(TAG, "FAPI client not available for freerInfo refresh (client=null)");
                    postRefreshResult(context, onComplete, false);
                    return;
                }

                // On first launch the FUDP bootstrap (HELLO+PING) is still in
                // flight when this runs, so the client exists but isn't connected
                // yet. Wait briefly for it — otherwise the one automatic balance
                // check bails and a brand-new zero-balance FID never sees the
                // "get your first FCH" prompt. We're on a background thread, so
                // a bounded poll is safe.
                if (!fapiClient.isConnected()) {
                    final long deadline = System.currentTimeMillis() + CONNECT_WAIT_MS;
                    while (!fapiClient.isConnected() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(CONNECT_POLL_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!fapiClient.isConnected()) {
                    TimberLogger.w(TAG, "FAPI client not connected after waiting %d ms for freerInfo refresh", CONNECT_WAIT_MS);
                    postRefreshResult(context, onComplete, false);
                    return;
                }

                // Fetch fresh freerInfo from API. One reply gives the record and the height it
                // was read at; a 404 is an answer (the FID holds nothing yet), not a failure.
                LiveFidRecord.Fetched fetched = LiveFidRecord.fetch(fapiClient, liveFid);
                // What the getting-started checklist reads: the chain as it stood in this
                // session. A CID, master or home carve it now shows has nothing left to wait for.
                LiveFidRecord.confirm(fetched.record);
                PendingIdentityCarves.of(context).reconcile(fetched.record);
                Freer freerInfo = fetched.freer;

                if (freerInfo != null) {
                    TimberLogger.d(TAG, "API returned freerInfo for FID %s - Cash: %s, Balance: %s, CD: %s",
                        liveFid, freerInfo.getCash(), freerInfo.getBalance(), freerInfo.getCd());

                    // Update KeyInfo with fresh data while preserving user-specific data
                    KeyInfo currentKeyInfo = getLiveKeyInfo();
                    if (currentKeyInfo != null) {
                        KeyInfo updatedKeyInfo = KeyInfo.updateFromCid(freerInfo,currentKeyInfo);

                        if(updatedKeyInfo.getPrikey()!=null || Boolean.TRUE.equals(freerInfo.getNobody()))
                            updatedKeyInfo.setNobody(Boolean.TRUE);

                        // The live key's prikey is published: say so once, loudly
                        if (Boolean.TRUE.equals(updatedKeyInfo.getIsNobody())) {
                            NobodyRegistry.get().mark(liveFid);
                            if (context instanceof Activity) {
                                NobodyGuard.alertOwnKeyIfNeeded((Activity) context, liveFid);
                            }
                        }

                        // Update in FidManager and save
                        if (updateKeyInfo(context, liveFid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Successfully updated KeyInfo for FID: %s", liveFid);
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
                    TimberLogger.i(TAG, "No on-chain record yet for live FID: %s", liveFid);
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
}
