package com.fc.freer.model;


import com.fc.fc_ajdk.config.BaseSetting;
import com.fc.fc_ajdk.config.ClientConfig;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.fudp.node.NodeConfig;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.initiate.ClientGroup;
import com.fc.freer.manager.FcManager;
import com.fc.freer.im.ImManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Freer-specific settings class that extends BaseSetting.
 * Contains user settings and configuration for the Freer app.
 */
public class Setting extends BaseSetting implements ClientConfig {
    public final static String TAG = "Setting";

    // Freer-specific fields
    private List<FcManager.ManagerType> managers;
    private Map<Service.ServiceType, ClientGroup> clientGroupMap;
    
    // FUDP/FAPI related fields
    private transient FudpNode fudpNode;
    
    // Messaging
    private transient ImManager imManager;

    // Freer-specific constants
    // Getting-started checklist: the steps skipped (comma-joined OnboardingStep keys) and
    // whether the checklist was ever shown here with a required step still open, and whether
    // every step has been seen settled.
    public static final String KEY_ONBOARDING_SKIPPED = "onboardingSkipped";
    public static final String KEY_ONBOARDING_STARTED = "onboardingStarted";
    public static final String KEY_ONBOARDING_COMPLETED = "onboardingCompleted";
    public static final String KEY_API_PAID_TIME_PREFIX = "apiPaidTime_";
    public static final String KEY_LAST_TEAM_UPDATE_HEIGHT = "lastTeamUpdateHeight";
    public static final String KEY_LAST_GROUP_UPDATE_HEIGHT = "lastGroupUpdateHeight";

    // P2P channel toggles. DOCK is always available; FUDP and ROAD are opt-in.
    public static final String KEY_USE_FUDP_DIRECT = "imUseFudpDirect";
    public static final String KEY_USE_ROAD_RELAY = "imUseRoadRelay";
    public static final String KEY_FOLLOW_HOME_BASE = "followHomeBase";
    public static final String KEY_HOME_BASE_URL = "homeBaseUrl";

    public Setting(Map<String,Object> settingMap, KeyInfo keyInfo, byte[] symkey) {
        super(keyInfo, symkey);
        
        // Initialize setting map
        if (settingMap != null) {
            setSettingMap(settingMap);
        }
        
        // Decrypt prikey if we have the key info and symkey
        if (keyInfo != null && symkey != null) {
            this.prikey = Decryptor.decryptPrikey(keyInfo.getPrikeyCipher(), symkey);
        }
        
        // Initialize Freer-specific fields
        managers = new ArrayList<>(Arrays.asList(FreerApplication.managers));
    }

    // ==================== ClientConfig Interface Implementation ====================
    
    @Override
    public String getMainFid() {
        return mainFid;
    }
    
    @Override
    public byte[] getDecryptedPrikey() {
        return decryptPrikey();
    }
    
    @Override
    public String getPubkey() {
        return super.getPubkey();
    }
    
    // ==================== BaseSetting Abstract Method Implementation ====================
    
    @Override
    public byte[] decryptPrikey() {
        // If prikey is already decrypted and cached, return it
        if (prikey != null) {
            return prikey;
        }
        
        // Otherwise try to decrypt from cipher
        KeyInfo mainKeyInfo = getMainKeyInfo();
        if (mainKeyInfo != null && mainKeyInfo.getPrikeyCipher() != null && symkey != null) {
            prikey = Decryptor.decryptPrikey(mainKeyInfo.getPrikeyCipher(), symkey);
            return prikey;
        }
        
        return null;
    }
    
    // ==================== Freer-Specific Methods ====================

    public String getFid() {
        return mainFid;
    }

    public List<FcManager.ManagerType> getManagers() {
        return managers;
    }

    public void setManagers(List<FcManager.ManagerType> managers) {
        this.managers = managers;
    }

    public Map<Service.ServiceType, ClientGroup> getClientGroupMap() {
        return clientGroupMap;
    }

    public void setClientGroupMap(Map<Service.ServiceType, ClientGroup> clientGroupMap) {
        this.clientGroupMap = clientGroupMap;
    }

    public boolean getUserConfirmTx() {
        return getSettingBool("userConfirmTx", true);
    }

    public void setUserConfirmTx(boolean userConfirmTx) {
        putSetting("userConfirmTx", userConfirmTx);
    }

    public void setWatchedKeyInfoList(List<KeyInfo> watchedKeyInfoList) {
        if (watchedFidList == null) watchedFidList = new ArrayList<>();
        watchedFidList.clear();
        if (watchedKeyInfoList != null) {
            for (KeyInfo keyInfo : watchedKeyInfoList) {
                if (keyInfo != null) {
                    watchedFidList.add(keyInfo.getId());
                    keyInfoMap.put(keyInfo.getId(), keyInfo);
                }
            }
        }
    }

    public void setMultisigKeyInfoList(List<KeyInfo> multisigKeyInfoList) {
        if (multisigFidList == null) multisigFidList = new ArrayList<>();
        multisigFidList.clear();
        if (multisigKeyInfoList != null) {
            for (KeyInfo keyInfo : multisigKeyInfoList) {
                if (keyInfo != null) {
                    multisigFidList.add(keyInfo.getId());
                    keyInfoMap.put(keyInfo.getId(), keyInfo);
                }
            }
        }
    }

    public void setServantKeyInfoList(List<KeyInfo> servantKeyInfoList) {
        if (servantFidList == null) servantFidList = new ArrayList<>();
        servantFidList.clear();
        if (servantKeyInfoList != null) {
            for (KeyInfo keyInfo : servantKeyInfoList) {
                if (keyInfo != null) {
                    servantFidList.add(keyInfo.getId());
                    keyInfoMap.put(keyInfo.getId(), keyInfo);
                }
            }
        }
    }

    // ==================== FUDP/FAPI Related Methods ====================
    
    /**
     * Set the symkey for decrypting prikey
     */
    @Override
    public void setSymkey(byte[] symkey) {
        super.setSymkey(symkey);
        // Re-decrypt prikey with new symkey
        KeyInfo mainKeyInfo = getMainKeyInfo();
        if (mainKeyInfo != null && mainKeyInfo.getPrikeyCipher() != null && symkey != null) {
            this.prikey = Decryptor.decryptPrikey(mainKeyInfo.getPrikeyCipher(), symkey);
        }
    }
    
    /**
     * Get the FUDP node
     */
    public FudpNode getFudpNode() {
        return fudpNode;
    }
    
    /**
     * Set the FUDP node
     */
    public void setFudpNode(FudpNode node) {
        this.fudpNode = node;
    }
    
    /**
     * Get the IM Manager for messaging
     */
    public ImManager getImManager() {
        return imManager;
    }
    
    /**
     * Set the IM Manager
     */
    public void setImManager(ImManager imManager) {
        this.imManager = imManager;
    }
    
    /**
     * Get or create the ImManager, initializing and starting it if needed.
     * @param context Android context (application context will be used)
     * @param fapiClient optional FapiClient for ROAD/DOCK delivery
     * @return initialized ImManager, or null if prikey is unavailable
     */
    public synchronized ImManager getOrCreateImManager(android.content.Context context,
                                          com.fc.fc_ajdk.fapi.client.FapiClient fapiClient) {
        if (imManager != null) {
            if (imManager.isReady()) {
                // The manager may have been built before the FAPI client finished
                // (re)connecting (e.g. after waking from a long sleep), leaving its
                // handlers without a client. Heal it in place if we now have one.
                if (fapiClient != null) {
                    imManager.ensureFapiClient(fapiClient);
                }
                return imManager;
            }
            TimberLogger.w(TAG, "Discarding incomplete ImManager for %s", mainFid);
            try {
                imManager.stop();
            } catch (Exception e) {
                TimberLogger.w(TAG, "Error stopping incomplete ImManager: %s", e.getMessage());
            }
            imManager = null;
        }
        
        if (mainFid == null) {
            TimberLogger.e(TAG, "Cannot create ImManager: mainFid is null");
            return null;
        }
        
        byte[] userPrikey = decryptPrikey();
        if (userPrikey == null) {
            TimberLogger.e(TAG, "Cannot create ImManager: prikey not available");
            return null;
        }
        
        ImManager newManager = new ImManager(context, mainFid);
        try {
            newManager.initialize(userPrikey);
            newManager.start(fudpNode, fapiClient);
            imManager = newManager;
            TimberLogger.i(TAG, "ImManager created and started for %s", mainFid);
            return imManager;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create ImManager for %s: %s", mainFid, e.getMessage());
            try {
                newManager.stop();
            } catch (Exception stopError) {
                TimberLogger.w(TAG, "Error stopping failed ImManager: %s", stopError.getMessage());
            }
            return null;
        }
    }
    
    /** Maximum number of ports to try when the default port is not available */
    private static final int MAX_PORT_ATTEMPTS = 10;

    /**
     * Live FUDP nodes, keyed by the FID whose private key runs them.
     * <p>
     * The node is transient state on a Setting, but a Setting is not a singleton:
     * {@code SettingManager.getOrCreateSetting} deserializes a fresh object from
     * SharedPreferences on every identity selection, so the object that owns a
     * running node is routinely dropped while the node keeps running. Without this
     * registry the next Setting for the same FID sees {@code fudpNode == null},
     * starts a second node, fails to bind the configured port, and lands on the
     * next one — leaving two nodes alive under one FID on two UDP ports (and so
     * two NAT mappings). Peers then see one FID with two session epochs and two
     * source addresses, steer that FID's traffic at whichever socket spoke last,
     * and every request issued on the other socket times out.
     * <p>
     * Keyed by FID rather than by Setting instance so the guard survives that churn.
     */
    private static final java.util.Map<String, FudpNode> LIVE_NODES = new java.util.HashMap<>();

    /**
     * Guards {@link #LIVE_NODES} and the whole start/stop sequence. A per-instance
     * lock would not help: the racing callers hold different Setting objects.
     */
    private static final Object NODE_LOCK = new Object();

    /**
     * Initialize and start FUDP node with port fallback logic.
     * If the configured port is not available, tries subsequent ports (e.g., 8501, 8502, etc.)
     * <p>
     * Start-to-finish under {@link #NODE_LOCK}, and shared through {@link #LIVE_NODES},
     * so at most one node per FID is running no matter how many Setting objects or
     * threads ask for one.
     */
    public FudpNode initFudpNode(String dataDir) {
        synchronized (NODE_LOCK) {
            return initFudpNodeLocked(dataDir);
        }
    }

    private FudpNode initFudpNodeLocked(String dataDir) {
        if (fudpNode != null && fudpNode.isRunning()) {
            return fudpNode;
        }

        // A previous Setting object for this identity may still own a running node.
        // Adopt it instead of starting a second one on another port.
        if (mainFid != null) {
            FudpNode live = LIVE_NODES.get(mainFid);
            if (live != null) {
                if (live.isRunning()) {
                    TimberLogger.d(TAG, "Adopting live FUDP node for %s", mainFid);
                    fudpNode = live;
                    return fudpNode;
                }
                LIVE_NODES.remove(mainFid);
            }
        }

        byte[] nodePrikey = decryptPrikey();
        if (nodePrikey == null) {
            TimberLogger.e(TAG, "Cannot init FudpNode: prikey not available");
            return null;
        }

        int basePort = getSettingInt(NodeConfig.FUDP_PORT, FudpNode.DEFAULT_PORT);
        
        // Try ports starting from basePort, up to MAX_PORT_ATTEMPTS
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            int port = basePort + attempt;
            
            NodeConfig config = new NodeConfig();
            config.setPort(port);
            config.setDataDir(dataDir);
            config.setMaxPacketSize(8000);
            config.setSocketBufferSize(4 * 1024 * 1024);
            
            try {
                // Build into a local first: the field must never publish a node
                // that has not started, nor one whose bind is about to fail.
                FudpNode node = new FudpNode(nodePrikey, config);
                node.start();
                fudpNode = node;
                if (mainFid != null) {
                    LIVE_NODES.put(mainFid, node);
                }

                // If successfully started on a different port than configured, save the new port
                if (attempt > 0) {
                    putSetting(NodeConfig.FUDP_PORT, port);
                    TimberLogger.i(TAG, "FUDP Node started on fallback port %d (base port %d was unavailable)", port, basePort);
                } else {
                    TimberLogger.d(TAG, "FUDP Node started on port %d with dataDir %s", port, dataDir);
                }

                return fudpNode;

            } catch (java.net.BindException e) {
                // Port is in use, try next port
                TimberLogger.w(TAG, "Port %d is not available: %s, trying next port...", port, e.getMessage());
                fudpNode = null;
            } catch (Exception e) {
                // Other errors (not port-related), log and try next port
                String message = e.getMessage();
                if (message != null && (message.contains("Address already in use") || message.contains("bind"))) {
                    TimberLogger.w(TAG, "Port %d is not available: %s, trying next port...", port, message);
                    fudpNode = null;
                } else {
                    // Non-port related error, don't continue trying
                    TimberLogger.e(TAG, "Failed to start FUDP Node on port %d: %s", port, message);
                    fudpNode = null;
                    return null;
                }
            }
        }
        
        TimberLogger.e(TAG, "Failed to start FUDP Node: no available port found after trying %d ports (starting from %d)", 
                MAX_PORT_ATTEMPTS, basePort);
        return null;
    }
    
    public boolean isUseFudpDirect() {
        return getSettingBool(KEY_USE_FUDP_DIRECT, false);
    }

    public void setUseFudpDirect(boolean value) {
        putSetting(KEY_USE_FUDP_DIRECT, value);
    }

    /**
     * Connect to the BASE the main FID's home names, once it is known and checks out. On by
     * default. Off keeps this device on the built-in servers — the way past a home BASE that
     * misbehaves, without carving anything.
     */
    public boolean isFollowHomeBase() {
        return getSettingBool(KEY_FOLLOW_HOME_BASE, true);
    }

    public void setFollowHomeBase(boolean value) {
        putSetting(KEY_FOLLOW_HOME_BASE, value);
    }

    /**
     * The home BASE last found, checked and reached ({@code host:port}), or null. Remembered so
     * that turning following off can tell that saved provider apart from one the app chose.
     */
    public String getHomeBaseUrl() {
        String url = getSettingString(KEY_HOME_BASE_URL, null);
        return url != null && !url.isEmpty() ? url : null;
    }

    public void setHomeBaseUrl(String url) {
        putSetting(KEY_HOME_BASE_URL, url != null ? url : "");
    }

    public boolean isUseRoadRelay() {
        return getSettingBool(KEY_USE_ROAD_RELAY, false);
    }

    public void setUseRoadRelay(boolean value) {
        putSetting(KEY_USE_ROAD_RELAY, value);
    }

    public long getLastTeamUpdateHeight() {
        return getSettingLong(KEY_LAST_TEAM_UPDATE_HEIGHT, 0);
    }

    public void setLastTeamUpdateHeight(long height) {
        putSetting(KEY_LAST_TEAM_UPDATE_HEIGHT, height);
    }

    public long getLastGroupUpdateHeight() {
        return getSettingLong(KEY_LAST_GROUP_UPDATE_HEIGHT, 0);
    }

    public void setLastGroupUpdateHeight(long height) {
        putSetting(KEY_LAST_GROUP_UPDATE_HEIGHT, height);
    }

    /** Whether the user has taken a copy of the main prikey. Only the user can say so. */
    public boolean isPrikeyBackedUp() {
        return readStateBoolean(KEY_PRIKEY_BACKED_UP);
    }

    public void setPrikeyBackedUp(boolean backedUp) {
        getStateMap().put(KEY_PRIKEY_BACKED_UP, backedUp);
    }

    /** Getting-started steps left undone for good. Names this build does not know are dropped. */
    public java.util.Set<com.fc.freer.onboarding.OnboardingStep> getOnboardingSkipped() {
        Object v = getStateMap().get(KEY_ONBOARDING_SKIPPED);
        return com.fc.freer.onboarding.OnboardingStep.parseKeys(v != null ? v.toString() : null);
    }

    public void setOnboardingSkipped(java.util.Set<com.fc.freer.onboarding.OnboardingStep> skipped) {
        getStateMap().put(KEY_ONBOARDING_SKIPPED, com.fc.freer.onboarding.OnboardingStep.joinKeys(skipped));
    }

    /**
     * Whether the checklist has ever been shown here with a required step open. See
     * {@link com.fc.freer.onboarding.Onboarding#shouldShow} for why an identity that was already
     * set up never sees it.
     */
    public boolean isOnboardingStarted() {
        return readStateBoolean(KEY_ONBOARDING_STARTED);
    }

    public void setOnboardingStarted(boolean started) {
        getStateMap().put(KEY_ONBOARDING_STARTED, started);
    }

    /**
     * Whether every step has been seen settled — done or skipped — on a record the chain answered
     * for. Once set the checklist is gone for good: at launch the chain has not answered yet, every
     * chain-backed step reads as unknown, and without this a finished identity would see the card
     * come back full of unknowns until it does (or for as long as it is offline). A step that later
     * goes backwards is Settings' to show.
     */
    public boolean isOnboardingCompleted() {
        return readStateBoolean(KEY_ONBOARDING_COMPLETED);
    }

    public void setOnboardingCompleted(boolean completed) {
        getStateMap().put(KEY_ONBOARDING_COMPLETED, completed);
    }

    private boolean readStateBoolean(String key) {
        Object v = getStateMap().get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    /**
     * Stop FUDP node
     */
    public void stopFudpNode() {
        for (FudpNode node : detachFudpNodes()) {
            stopNode(node);
        }
    }

    /**
     * Same as {@link #stopFudpNode()} but returns as soon as the node is detached,
     * doing the blocking shutdown (scheduler drain + receive-thread join, up to
     * several seconds) on a background thread. Detaching is what matters for
     * correctness — once unregistered, no later {@code initFudpNode} can adopt the
     * node — so this is safe to call from the UI thread.
     */
    public void stopFudpNodeAsync() {
        List<FudpNode> nodes = detachFudpNodes();
        if (nodes.isEmpty()) return;
        new Thread(() -> {
            for (FudpNode node : nodes) stopNode(node);
        }, "fudp-stop").start();
    }

    /**
     * Unregister this identity's node(s) and clear the field, under {@link #NODE_LOCK}.
     * Returns what still needs stopping.
     * <p>
     * Takes whatever is live for this identity, not only what this particular Setting
     * object holds: after an identity switch the object that started the node is
     * usually already gone.
     */
    private List<FudpNode> detachFudpNodes() {
        List<FudpNode> nodes = new ArrayList<>(2);
        synchronized (NODE_LOCK) {
            FudpNode live = (mainFid != null) ? LIVE_NODES.remove(mainFid) : null;
            if (live != null) nodes.add(live);
            if (fudpNode != null && fudpNode != live) nodes.add(fudpNode);
            fudpNode = null;
        }
        return nodes;
    }

    private void stopNode(FudpNode node) {
        if (node == null || !node.isRunning()) return;
        try {
            node.stop();
            TimberLogger.d(TAG, "FUDP Node stopped for %s", mainFid);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Error stopping FUDP Node for %s: %s", mainFid, e.getMessage());
        }
    }
}
