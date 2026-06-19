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
    public static final String KEY_PROMOTED_TOP_UP = "promotedTopUp";
    public static final String KEY_PROMOTED_SET_CID = "promotedSetCid";
    public static final String KEY_API_PAID_TIME_PREFIX = "apiPaidTime_";
    public static final String KEY_LAST_TEAM_UPDATE_HEIGHT = "lastTeamUpdateHeight";
    public static final String KEY_LAST_GROUP_UPDATE_HEIGHT = "lastGroupUpdateHeight";

    // P2P channel toggles. DOCK is always available; FUDP and ROAD are opt-in.
    public static final String KEY_USE_FUDP_DIRECT = "imUseFudpDirect";
    public static final String KEY_USE_ROAD_RELAY = "imUseRoadRelay";

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
     * Initialize and start FUDP node with port fallback logic.
     * If the configured port is not available, tries subsequent ports (e.g., 8501, 8502, etc.)
     */
    public FudpNode initFudpNode(String dataDir) {
        if (fudpNode != null && fudpNode.isRunning()) {
            return fudpNode;
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
                fudpNode = new FudpNode(nodePrikey, config);
                fudpNode.start();
                
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

    /**
     * Stop FUDP node
     */
    public void stopFudpNode() {
        if (fudpNode != null && fudpNode.isRunning()) {
            fudpNode.stop();
            TimberLogger.d(TAG, "FUDP Node stopped");
        }
        fudpNode = null;
    }
}
