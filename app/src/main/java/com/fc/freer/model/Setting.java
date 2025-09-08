package com.fc.freer.model;

import static com.fc.fc_ajdk.config.Configure.checkPassword;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.FreerApplication;
import com.fc.freer.initiate.ClientGroup;
import com.fc.freer.manager.FcManager;
import com.fc.freer.network.FcHttpRequester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Setting {
    public final static String TAG = "Setting";
    private transient byte[] prikey;
    private Map<String,Long> bestHeightMap;
    private Map<String,Object> settingMap;
    private List<FcManager.ManagerType> managers;
    private Map<Service.ServiceType, ClientGroup> clientGroupMap;
    
    // Centralized KeyInfo storage
    private Map<String, KeyInfo> keyInfoMap;
    
    // FID references (replacing direct KeyInfo objects)
    private String mainFid;
//    private String masterFid;
    private List<String> watchedFidList;
    private List<String> multisignFidList;
    private List<String> servantFidList;


    public Setting(Map<String,Object> settingMap, KeyInfo keyInfo, byte[] symkey) {
        // Initialize keyInfoMap first
        this.keyInfoMap = new HashMap<>();
        
        // Store mainKeyInfo in keyInfoMap and set mainFid
        if (keyInfo != null) {
            this.mainFid = keyInfo.getId();
            this.keyInfoMap.put(this.mainFid, keyInfo);
            this.prikey = Decryptor.decryptPrikey(keyInfo.getPrikeyCipher(), symkey);
        }
        
        if(settingMap==null)this.settingMap = new HashMap<>();
        else this.settingMap = settingMap;
        bestHeightMap = new HashMap<>();
        managers = new ArrayList<>(Arrays.asList(FreerApplication.managers));
        
        // Initialize FID lists
        watchedFidList = new ArrayList<>();
        multisignFidList = new ArrayList<>();
        servantFidList = new ArrayList<>();
    }

    public String getFid() {
        return mainFid;
    }

    public String getPubkey() {
        KeyInfo mainKeyInfo = getMainKeyInfo();
        return mainKeyInfo != null ? mainKeyInfo.getPubkey() : null;
    }

    public String getPrikeyCipher() {
        KeyInfo mainKeyInfo = getMainKeyInfo();
        return mainKeyInfo != null ? mainKeyInfo.getPrikeyCipher() : null;
    }

    public Map<String, Long> getBestHeightMap() {
        return bestHeightMap;
    }

    public void setBestHeightMap(Map<String, Long> bestHeightMap) {
        this.bestHeightMap = bestHeightMap;
    }

    public Map<String, Object> getSettingMap() {
        return settingMap;
    }

    public void setSettingMap(Map<String, Object> settingMap) {
        this.settingMap = settingMap;
    }

    public byte[] getPrikey() {
        return prikey;
    }

    public void setPrikey(byte[] prikey) {
        this.prikey = prikey;
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

    public KeyInfo getMainKeyInfo() {
        return mainFid != null ? keyInfoMap.get(mainFid) : null;
    }

    public void setMainKeyInfo(KeyInfo mainKeyInfo) {
        if (mainKeyInfo != null) {
            this.mainFid = mainKeyInfo.getId();
            this.keyInfoMap.put(this.mainFid, mainKeyInfo);
        }
    }

    public boolean getUserConfirmTx() {
        Object value = settingMap.get("userConfirmTx");
        return value instanceof Boolean ? (Boolean) value : true;
    }

    public void setUserConfirmTx(boolean userConfirmTx) {
        settingMap.put("userConfirmTx", userConfirmTx);
    }

    public List<KeyInfo> getWatchedKeyInfoList() {
        List<KeyInfo> result = new ArrayList<>();
        if (watchedFidList != null) {
            for (String fid : watchedFidList) {
                KeyInfo keyInfo = keyInfoMap.get(fid);
                if (keyInfo != null) {
                    result.add(keyInfo);
                }
            }
        }
        return result;
    }

    public void setWatchedKeyInfoList(List<KeyInfo> watchedKeyInfoList) {
        this.watchedFidList = new ArrayList<>();
        if (watchedKeyInfoList != null) {
            for (KeyInfo keyInfo : watchedKeyInfoList) {
                if (keyInfo != null) {
                    this.watchedFidList.add(keyInfo.getId());
                    this.keyInfoMap.put(keyInfo.getId(), keyInfo);
                }
            }
        }
    }

    public List<KeyInfo> getMultisignKeyInfoList() {
        List<KeyInfo> result = new ArrayList<>();
        if (multisignFidList != null) {
            for (String fid : multisignFidList) {
                KeyInfo keyInfo = keyInfoMap.get(fid);
                if (keyInfo != null) {
                    result.add(keyInfo);
                }
            }
        }
        return result;
    }

    public void setMultisignKeyInfoList(List<KeyInfo> multisignKeyInfoList) {
        this.multisignFidList = new ArrayList<>();
        if (multisignKeyInfoList != null) {
            for (KeyInfo keyInfo : multisignKeyInfoList) {
                if (keyInfo != null) {
                    this.multisignFidList.add(keyInfo.getId());
                    this.keyInfoMap.put(keyInfo.getId(), keyInfo);
                }
            }
        }
    }

    /**
     * Add a watched KeyInfo to the list if not already present
     */
    public void addWatchedKeyInfo(KeyInfo keyInfo) {
        if (keyInfo != null && watchedFidList != null) {
            String fid = keyInfo.getId();
            if (!watchedFidList.contains(fid)) {
                watchedFidList.add(fid);
                keyInfoMap.put(fid, keyInfo);
            }
        }
    }

    /**
     * Remove a watched KeyInfo from the list
     */
    public void removeWatchedKeyInfo(String fid) {
        if (fid != null && watchedFidList != null) {
            watchedFidList.remove(fid);
            // Keep KeyInfo in keyInfoMap as it might be used elsewhere
        }
    }

    /**
     * Add a multisign KeyInfo to the list if not already present
     */
    public void addMultisignKeyInfo(KeyInfo keyInfo) {
        if (keyInfo != null && multisignFidList != null) {
            String fid = keyInfo.getId();
            if (!multisignFidList.contains(fid)) {
                multisignFidList.add(fid);
                keyInfoMap.put(fid, keyInfo);
            }
        }
    }

    /**
     * Remove a multisign KeyInfo from the list
     */
    public boolean removeMultisignKeyInfo(String fid) {
        if (fid != null && multisignFidList != null) {
            return multisignFidList.remove(fid);
            // Keep KeyInfo in keyInfoMap as it might be used elsewhere
        }
        return false;
    }

    public List<KeyInfo> getServantKeyInfoList() {
        List<KeyInfo> result = new ArrayList<>();
        if (servantFidList != null) {
            for (String fid : servantFidList) {
                KeyInfo keyInfo = keyInfoMap.get(fid);
                if (keyInfo != null) {
                    result.add(keyInfo);
                }
            }
        }
        return result;
    }

    public void setServantKeyInfoList(List<KeyInfo> servantKeyInfoList) {
        this.servantFidList = new ArrayList<>();
        if (servantKeyInfoList != null) {
            for (KeyInfo keyInfo : servantKeyInfoList) {
                if (keyInfo != null) {
                    this.servantFidList.add(keyInfo.getId());
                    this.keyInfoMap.put(keyInfo.getId(), keyInfo);
                }
            }
        }
    }

    /**
     * Add a servant KeyInfo to the list if not already present
     */
    public void addServantKeyInfo(KeyInfo keyInfo) {
        if (keyInfo != null) {
            if( servantFidList == null)servantFidList = new ArrayList<>();
            String fid = keyInfo.getId();
            if (!servantFidList.contains(fid)) {
                servantFidList.add(fid);
                keyInfoMap.put(fid, keyInfo);
            }
        }
    }

    /**
     * Remove a servant KeyInfo from the list
     */
    public boolean removeServantKeyInfo(String fid) {
        if (fid != null && servantFidList != null) {
            return servantFidList.remove(fid);
        }
        return false;
    }

    // KeyInfoMap helper methods
    
    /**
     * Get KeyInfo by FID from the centralized map
     */
    public KeyInfo getKeyInfo(String fid) {
        return fid != null ? keyInfoMap.get(fid) : null;
    }
    
    /**
     * Update or add KeyInfo to the centralized map
     */
    public void updateKeyInfo(String fid, KeyInfo keyInfo) {
        if (fid != null && keyInfo != null) {
            keyInfoMap.put(fid, keyInfo);
        }
    }
    
    /**
     * Get the centralized KeyInfo map
     */
    public Map<String, KeyInfo> getKeyInfoMap() {
        return keyInfoMap;
    }
    
    /**
     * Set the centralized KeyInfo map
     */
    public void setKeyInfoMap(Map<String, KeyInfo> keyInfoMap) {
        this.keyInfoMap = keyInfoMap != null ? keyInfoMap : new HashMap<>();
    }
    
    /**
     * Get all FIDs that have KeyInfo stored
     */
    public List<String> getAllFids() {
        return new ArrayList<>(keyInfoMap.keySet());
    }
    
    /**
     * Check if a FID has KeyInfo stored
     */
    public boolean hasKeyInfo(String fid) {
        return fid != null && keyInfoMap.containsKey(fid);
    }

}
