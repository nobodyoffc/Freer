package com.fc.fc_ajdk.config;

import com.fc.fc_ajdk.constants.FreeApi;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.ServiceMask;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.IdNameUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for application configuration in FC ecosystem applications.
 * Contains common fields and methods that can be shared across different apps.
 * 
 * App-specific implementations should extend this class and add their own fields.
 * Storage/persistence logic should be implemented in subclasses or via StorageProvider.
 */
public abstract class BaseConfigure extends FcObject {
    
    public static final String TAG = "BaseConfigure";
    
    // Password and authentication
    protected String nonce;
    protected String passwordName;
    protected String passwordHash;
    protected transient byte[] symkey;
    /** The vault's data key wrapped under the password (VaultKey). Null for a legacy vault that has not been moved yet. */
    protected String dekCipher;
    /** VaultMigration.State while a legacy vault is being moved to a data key; null once that is done. */
    protected String vaultState;
    /** The vault id a legacy vault is being moved to. */
    protected String pendingVaultId;
    /** The legacy password-derived name whose storage is still to be deleted. */
    protected String legacyName;
    
    // Ownership
    protected List<String> ownerList;
    
    // User/Account info
    protected Map<String, KeyInfo> mainCidInfoMap;
    
    // Service configuration
    protected Map<String, ServiceMask> myServiceMaskMap;
    protected Map<Service.ServiceType, List<FreeApi>> freeApiListMap;
    
    /**
     * Default constructor
     */
    protected BaseConfigure() {
        this.ownerList = new ArrayList<>();
        this.mainCidInfoMap = new HashMap<>();
        this.myServiceMaskMap = new HashMap<>();
        this.freeApiListMap = new HashMap<>();
    }
    
    // ==================== Abstract Methods ====================
    
    /**
     * Save the configuration to persistent storage.
     * Implementation should handle platform-specific storage logic.
     */
    public abstract void save();
    
    /**
     * Load the configuration from persistent storage.
     * Implementation should handle platform-specific storage logic.
     */
    public abstract void load();
    
    // ==================== Password/Symkey Methods ====================
    
    public String getNonce() {
        return nonce;
    }
    
    public void setNonce(String nonce) {
        this.nonce = nonce;
    }
    
    public String getPasswordName() {
        return passwordName;
    }
    
    public void setPasswordName(String passwordName) {
        this.passwordName = passwordName;
    }
    
    public String getPasswordHash() {
        return passwordHash;
    }
    
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
    
    public byte[] getSymkey() {
        return symkey;
    }
    
    public void setSymkey(byte[] symkey) {
        this.symkey = symkey;
    }
    
    /**
     * Generate symkey from password bytes
     */
    public static byte[] getSymkeyFromPassword(byte[] passwordBytes) {
        return Hash.sha256(passwordBytes);
    }
    
    /**
     * Generate symkey from password bytes and nonce
     */
    public static byte[] getSymkeyFromPasswordAndNonce(byte[] passwordBytes, byte[] nonce) {
        return Hash.sha256(BytesUtils.bytesMerger(passwordBytes, nonce));
    }
    
    /**
     * Make symkey from password and store it
     */
    public byte[] makeSymkeyFromPassword(byte[] passwordBytes) {
        this.symkey = Hash.sha256(passwordBytes);
        return this.symkey;
    }
    
    /**
     * Get symkey from password using stored nonce
     */
    public byte[] getSymkeyFromPasswordAndNonce(byte[] passwordBytes) {
        if (nonce == null) return null;
        setSymkey(getSymkeyFromPasswordAndNonce(passwordBytes, Hex.fromHex(nonce)));
        return this.symkey;
    }
    
    /**
     * Generate password hash name from password bytes
     */
    public static String makePasswordHashName(byte[] passwordBytes) {
        return IdNameUtils.makePasswordHashName(passwordBytes);
    }
    
    /**
     * Initialize a new configure with password
     */
    protected void initWithPassword(byte[] passwordBytes) {
        byte[] nonceBytes = BytesUtils.getRandomBytes(16);
        this.symkey = getSymkeyFromPassword(passwordBytes);
        this.nonce = Hex.toHex(nonceBytes);
        this.passwordName = IdNameUtils.makePasswordHashName(passwordBytes);
    }
    
    public String getDekCipher() {
        return dekCipher;
    }

    public void setDekCipher(String dekCipher) {
        this.dekCipher = dekCipher;
    }

    public String getVaultState() {
        return vaultState;
    }

    public void setVaultState(String vaultState) {
        this.vaultState = vaultState;
    }

    public String getPendingVaultId() {
        return pendingVaultId;
    }

    public void setPendingVaultId(String pendingVaultId) {
        this.pendingVaultId = pendingVaultId;
    }

    public String getLegacyName() {
        return legacyName;
    }

    public void setLegacyName(String legacyName) {
        this.legacyName = legacyName;
    }

    // ==================== Owner Management ====================
    
    public List<String> getOwnerList() {
        return ownerList;
    }
    
    public void setOwnerList(List<String> ownerList) {
        this.ownerList = ownerList != null ? ownerList : new ArrayList<>();
    }
    
    public void addOwner(String fid) {
        if (fid != null && ownerList != null && !ownerList.contains(fid)) {
            ownerList.add(fid);
        }
    }
    
    public boolean removeOwner(String fid) {
        return fid != null && ownerList != null && ownerList.remove(fid);
    }
    
    // ==================== KeyInfo Management ====================
    
    public Map<String, KeyInfo> getMainCidInfoMap() {
        return mainCidInfoMap;
    }
    
    public void setMainCidInfoMap(Map<String, KeyInfo> mainCidInfoMap) {
        this.mainCidInfoMap = mainCidInfoMap != null ? mainCidInfoMap : new HashMap<>();
    }
    
    public KeyInfo getMainCidInfo(String fid) {
        return mainCidInfoMap != null ? mainCidInfoMap.get(fid) : null;
    }
    
    public void putMainCidInfo(String fid, KeyInfo keyInfo) {
        if (mainCidInfoMap == null) mainCidInfoMap = new HashMap<>();
        if (fid != null && keyInfo != null) {
            mainCidInfoMap.put(fid, keyInfo);
        }
    }
    
    // ==================== Service Configuration ====================
    
    public Map<String, ServiceMask> getMyServiceMaskMap() {
        return myServiceMaskMap;
    }
    
    public void setMyServiceMaskMap(Map<String, ServiceMask> myServiceMaskMap) {
        this.myServiceMaskMap = myServiceMaskMap != null ? myServiceMaskMap : new HashMap<>();
    }
    
    public Map<Service.ServiceType, List<FreeApi>> getFreeApiListMap() {
        return freeApiListMap;
    }
    
    public void setFreeApiListMap(Map<Service.ServiceType, List<FreeApi>> freeApiListMap) {
        this.freeApiListMap = freeApiListMap != null ? freeApiListMap : new HashMap<>();
    }
    
    /**
     * Initialize default free API endpoints.
     * Subclasses can override to add app-specific defaults.
     */
    public void initFreeApiListMap() {
        if (freeApiListMap == null) {
            freeApiListMap = new HashMap<>();
        }
        
        // Add FAPI default nodes if not exists
        if (freeApiListMap.get(Service.ServiceType.FAPI_No1_NrC7) == null) {
            List<FreeApi> freeFapiList = new ArrayList<>();
            for(String api: FapiClient.DEFAULT_BOOTSTRAP_APIS) {
                freeFapiList.add(new FreeApi(api, true, Service.ServiceType.FAPI_No1_NrC7));
            }
            freeApiListMap.put(Service.ServiceType.FAPI_No1_NrC7, freeFapiList);
        }
    }
    
    /**
     * Get default FAPI bootstrap endpoints
     */
    public String[] getDefaultFapiEndpoints() {
        if (freeApiListMap == null || freeApiListMap.get(Service.ServiceType.FAPI_No1_NrC7) == null) {
            initFreeApiListMap();
        }
        
        List<FreeApi> fapiList = freeApiListMap.get(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiList == null || fapiList.isEmpty()) {
            return FapiClient.DEFAULT_BOOTSTRAP_APIS;
        }
        
        return fapiList.stream()
            .filter(FreeApi::getActive)
            .map(FreeApi::getUrl)
            .toArray(String[]::new);
    }
    
    /**
     * Get free API list for a service type
     */
    public List<FreeApi> getFreeApiList(Service.ServiceType type) {
        return freeApiListMap != null ? freeApiListMap.get(type) : null;
    }
    
    /**
     * Add a free API for a service type
     */
    public void addFreeApi(Service.ServiceType type, FreeApi freeApi) {
        if (freeApiListMap == null) freeApiListMap = new HashMap<>();
        List<FreeApi> list = freeApiListMap.computeIfAbsent(type, k -> new ArrayList<>());
        list.add(freeApi);
    }
}

