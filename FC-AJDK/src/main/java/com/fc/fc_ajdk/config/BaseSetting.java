package com.fc.fc_ajdk.config;

import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for user settings in FC ecosystem applications.
 * Contains common fields and methods that can be shared across different apps.
 * 
 * App-specific implementations should extend this class and add their own fields.
 */
public abstract class BaseSetting {
    
    // Transient fields (not serialized)
    protected transient byte[] prikey;
    protected transient byte[] symkey;
    
    // Common settings
    protected Map<String, Object> settingMap;
    protected Map<String, Long> bestHeightMap;
    protected Map<String, Object> stateMap;
    
    // Centralized KeyInfo storage
    protected Map<String, KeyInfo> keyInfoMap;
    
    // FID references
    protected String mainFid;
    protected List<String> watchedFidList;
    protected List<String> multisigFidList;
    protected List<String> servantFidList;
    
    // Common setting keys
    public static final String KEY_PRIKEY_BACKED_UP = "prikeyBackedUp";
    
    /**
     * Default constructor
     */
    protected BaseSetting() {
        this.keyInfoMap = new HashMap<>();
        this.settingMap = new HashMap<>();
        this.bestHeightMap = new HashMap<>();
        this.stateMap = new HashMap<>();
        this.watchedFidList = new ArrayList<>();
        this.multisigFidList = new ArrayList<>();
        this.servantFidList = new ArrayList<>();
    }
    
    /**
     * Constructor with initial KeyInfo and symkey
     */
    protected BaseSetting(KeyInfo keyInfo, byte[] symkey) {
        this();
        if (keyInfo != null) {
            this.mainFid = keyInfo.getId();
            this.keyInfoMap.put(this.mainFid, keyInfo);
        }
        this.symkey = symkey;
    }
    
    // ==================== Abstract Methods ====================
    
    /**
     * Decrypt and return the private key.
     * Implementation should handle the decryption logic specific to the app.
     */
    public abstract byte[] decryptPrikey();
    
    // ==================== Common Getters/Setters ====================
    
    public String getMainFid() {
        return mainFid;
    }
    
    public void setMainFid(String mainFid) {
        this.mainFid = mainFid;
    }
    
    public Map<String, Object> getSettingMap() {
        return settingMap;
    }
    
    public void setSettingMap(Map<String, Object> settingMap) {
        this.settingMap = settingMap != null ? settingMap : new HashMap<>();
    }
    
    public Map<String, Long> getBestHeightMap() {
        return bestHeightMap;
    }
    
    public void setBestHeightMap(Map<String, Long> bestHeightMap) {
        this.bestHeightMap = bestHeightMap != null ? bestHeightMap : new HashMap<>();
    }
    
    public Map<String, Object> getStateMap() {
        if (stateMap == null) {
            stateMap = new HashMap<>();
        }
        return stateMap;
    }
    
    public void setStateMap(Map<String, Object> stateMap) {
        this.stateMap = stateMap;
    }
    
    public byte[] getPrikey() {
        return prikey;
    }
    
    public void setPrikey(byte[] prikey) {
        this.prikey = prikey;
    }
    
    public void setSymkey(byte[] symkey) {
        this.symkey = symkey;
    }
    
    // ==================== KeyInfo Management ====================
    
    public KeyInfo getMainKeyInfo() {
        return mainFid != null ? keyInfoMap.get(mainFid) : null;
    }
    
    public void setMainKeyInfo(KeyInfo mainKeyInfo) {
        if (mainKeyInfo != null) {
            this.mainFid = mainKeyInfo.getId();
            this.keyInfoMap.put(this.mainFid, mainKeyInfo);
        }
    }
    
    public KeyInfo getKeyInfo(String fid) {
        return fid != null ? keyInfoMap.get(fid) : null;
    }
    
    public void updateKeyInfo(String fid, KeyInfo keyInfo) {
        if (fid != null && keyInfo != null) {
            keyInfoMap.put(fid, keyInfo);
        }
    }
    
    public Map<String, KeyInfo> getKeyInfoMap() {
        return keyInfoMap;
    }
    
    public void setKeyInfoMap(Map<String, KeyInfo> keyInfoMap) {
        this.keyInfoMap = keyInfoMap != null ? keyInfoMap : new HashMap<>();
    }
    
    public List<String> getAllFids() {
        return new ArrayList<>(keyInfoMap.keySet());
    }
    
    public boolean hasKeyInfo(String fid) {
        return fid != null && keyInfoMap.containsKey(fid);
    }
    
    public String getPubkey() {
        KeyInfo mainKeyInfo = getMainKeyInfo();
        return mainKeyInfo != null ? mainKeyInfo.getPubkey() : null;
    }
    
    public String getPrikeyCipher() {
        KeyInfo mainKeyInfo = getMainKeyInfo();
        return mainKeyInfo != null ? mainKeyInfo.getPrikeyCipher() : null;
    }
    
    // ==================== Watched/Multisig/Servant KeyInfo Lists ====================
    
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
    
    public void addWatchedKeyInfo(KeyInfo keyInfo) {
        if (keyInfo != null) {
            if (watchedFidList == null) watchedFidList = new ArrayList<>();
            String fid = keyInfo.getId();
            if (!watchedFidList.contains(fid)) {
                watchedFidList.add(fid);
                keyInfoMap.put(fid, keyInfo);
            }
        }
    }
    
    public void removeWatchedKeyInfo(String fid) {
        if (fid != null && watchedFidList != null) {
            watchedFidList.remove(fid);
        }
    }
    
    public List<KeyInfo> getMultisigKeyInfoList() {
        List<KeyInfo> result = new ArrayList<>();
        if (multisigFidList != null) {
            for (String fid : multisigFidList) {
                KeyInfo keyInfo = keyInfoMap.get(fid);
                if (keyInfo != null) {
                    result.add(keyInfo);
                }
            }
        }
        return result;
    }
    
    public void addMultisigKeyInfo(KeyInfo keyInfo) {
        if (keyInfo != null) {
            if (multisigFidList == null) multisigFidList = new ArrayList<>();
            String fid = keyInfo.getId();
            if (!multisigFidList.contains(fid)) {
                multisigFidList.add(fid);
                keyInfoMap.put(fid, keyInfo);
            }
        }
    }
    
    public boolean removeMultisigKeyInfo(String fid) {
        if (fid != null && multisigFidList != null) {
            return multisigFidList.remove(fid);
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
    
    public void addServantKeyInfo(KeyInfo keyInfo) {
        if (keyInfo != null) {
            if (servantFidList == null) servantFidList = new ArrayList<>();
            String fid = keyInfo.getId();
            if (!servantFidList.contains(fid)) {
                servantFidList.add(fid);
                keyInfoMap.put(fid, keyInfo);
            }
        }
    }
    
    public boolean removeServantKeyInfo(String fid) {
        if (fid != null && servantFidList != null) {
            return servantFidList.remove(fid);
        }
        return false;
    }
    
    // ==================== Setting Helpers ====================
    
    public int getSettingInt(String key, int defaultValue) {
        if (settingMap == null) return defaultValue;
        Object value = settingMap.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    public long getSettingLong(String key, long defaultValue) {
        if (settingMap == null) return defaultValue;
        Object value = settingMap.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    public boolean getSettingBool(String key, boolean defaultValue) {
        if (settingMap == null) return defaultValue;
        Object value = settingMap.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
    
    public String getSettingString(String key, String defaultValue) {
        if (settingMap == null) return defaultValue;
        Object value = settingMap.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    public void putSetting(String key, Object value) {
        if (settingMap == null) settingMap = new HashMap<>();
        settingMap.put(key, value);
    }
}

