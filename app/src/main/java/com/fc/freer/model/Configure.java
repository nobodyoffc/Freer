package com.fc.freer.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.fc.fc_ajdk.constants.FreeApi;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.ServiceMask;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Configure extends FcObject {
    public static final String TAG = "Configure";
    protected String nonce;
    protected String passwordName;
    protected String passwordHash;
    protected List<String> ownerList;  //Owners for servers.
    protected Map<String, KeyInfo> mainCidInfoMap; //Users for clients or accounts for servers.
    private String esAccountId;

    private Map<String, ServiceMask> myServiceMaskMap;
    private Map<String, ApiProvider> apiProviderMap;
    private Map<String, ApiAccount> apiAccountMap;
    private transient byte[] symkey;


//    private List<FreeApi> freeApipUrlList;
    private Map<Service.ServiceType,List<FreeApi>> freeApiListMap;
    public static Map<String,Configure> configureMap;
//    public static List<String> freeApipUrls;

    private static Context context;
    private static final String SHARED_PREFS_NAME = "fc_ajdk_prefs";
    private static final String CONFIG_KEY = "config_json";


    public Configure() {
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Map<String, com.fc.freer.model.ApiProvider> getApiProviderMap() {
        return apiProviderMap;
    }

    public void setApiProviderMap(Map<String, com.fc.freer.model.ApiProvider> apiProviderMap) {
        this.apiProviderMap = apiProviderMap;
    }

    public Map<String, ApiAccount> getApiAccountMap() {
        return apiAccountMap;
    }

    public void setApiAccountMap(Map<String, ApiAccount> apiAccountMap) {
        this.apiAccountMap = apiAccountMap;
    }

    public static void saveConfig() {
        if (context == null) {
            TimberLogger.w(TAG,"Context is null, cannot save config");
            return;
        }
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            String configJson = JsonUtils.toJson(configureMap);
            editor.putString(CONFIG_KEY, configJson);
            editor.apply();
            TimberLogger.d(TAG, "Config saved successfully");
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to save config: " + e.getMessage());
        }
    }

    public List<String> getOwnerList() {
        return ownerList;
    }

    public void setOwnerList(List<String> ownerList) {
        this.ownerList = ownerList;
    }


    private static Configure createNewConfigure(byte[] passwordBytes) {
        Configure configure = new Configure();
        byte[] symkey;
        byte[] nonceBytes;
        nonceBytes = BytesUtils.getRandomBytes(16);
        symkey = getSymkeyFromPassword(passwordBytes);
        configure.nonce = Hex.toHex(nonceBytes);
        configure.setSymkey(symkey);
        String name = IdNameUtils.makePasswordHashName(passwordBytes);
        configure.setPasswordName(name);
        configureMap.put(name,configure);
        saveConfig();
        BytesUtils.clearByteArray(passwordBytes);
        return configure;
    }

    public static byte[] getSymkeyFromPasswordAndNonce(byte[] passwordBytes, byte[] nonce) {
        return Hash.sha256(BytesUtils.bytesMerger(passwordBytes, nonce));
    }

    public static byte[] getSymkeyFromPassword(byte[] passwordBytes) {
        return Hash.sha256(passwordBytes);
    }

    public byte[] makeSymkeyFromPassword(byte[] passwordBytes) {
        this.symkey = Hash.sha256(passwordBytes);
        return this.symkey;
    }

    public byte[] getSymkeyFromPasswordAndNonce(byte[] passwordBytes) {
        setSymkey(getSymkeyFromPasswordAndNonce(passwordBytes,Hex.fromHex(nonce)));
        return this.symkey;
    }

    public static  <T> T parseMyServiceParams(Service myService, Class<T> tClass){
        Gson gson = new Gson();
        T params = gson.fromJson(gson.toJson(myService.getParams()), tClass);
        myService.setParams(params);
        return params;
    }

    public static String getConfDir(){
        if (context != null) {
            return context.getFilesDir().getAbsolutePath() + "/config/";
        }
        return System.getProperty("user.dir") + "/config/";
    }

    public static void loadConfig(String path, BufferedReader br){
            // Android environment - use SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            String configJson = prefs.getString(CONFIG_KEY, null);
            if (configJson != null) {
                try {
                    configureMap = JsonUtils.jsonToMap(configJson, String.class, Configure.class);
                } catch (Exception e) {
                    TimberLogger.e("Failed to parse config from SharedPreferences: " + e.getMessage());
                    configureMap = new HashMap<>();
                }
            } else {
                configureMap = new HashMap<>();
            }

        if(configureMap == null) {
            configureMap = new HashMap<>();
        }
    }


    public byte[] getSymkey() {
        return symkey;
    }

    public void setSymkey(byte[] symkey) {
        this.symkey = symkey;
    }

    public String getPasswordName() {
        return passwordName;
    }

    public void setPasswordName(String passwordName) {
        this.passwordName = passwordName;
    }

    public static Map<String, Configure> getConfigureMap() {
        return configureMap;
    }

    public static void setConfigureMap(Map<String, Configure> configureMap) {
        Configure.configureMap = configureMap;
    }

    // Add method to get all account IDs for a service type
    public List<String> getAccountIdsForServiceType(Service.ServiceType type) {
        List<String> accountIds = new ArrayList<>();
        if (apiAccountMap != null) {
            for (Map.Entry<String, ApiAccount> entry : apiAccountMap.entrySet()) {
                ApiProvider provider = apiProviderMap.get(entry.getValue().getProviderId());
                if (provider != null && provider.getType() == type) {
                    accountIds.add(entry.getKey());
                }
            }
        }
        return accountIds;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public static void setContext(Context androidContext) {
        context = androidContext;
    }

    public Map<String, KeyInfo> getMainCidInfoMap() {
        return mainCidInfoMap;
    }

    public void setMainCidInfoMap(Map<String, KeyInfo> mainCidInfoMap) {
        this.mainCidInfoMap = mainCidInfoMap;
    }

    public String getEsAccountId() {
        return esAccountId;
    }

    public void setEsAccountId(String esAccountId) {
        this.esAccountId = esAccountId;
    }

    public Map<String, ServiceMask> getMyServiceMaskMap() {
        return myServiceMaskMap;
    }

    public void setMyServiceMaskMap(Map<String, ServiceMask> myServiceMaskMap) {
        this.myServiceMaskMap = myServiceMaskMap;
    }

    public Map<Service.ServiceType, List<FreeApi>> getFreeApiListMap() {
        return freeApiListMap;
    }

    public void setFreeApiListMap(Map<Service.ServiceType, List<FreeApi>> freeApiListMap) {
        this.freeApiListMap = freeApiListMap;
    }

    public static Context getContext() {
        return context;
    }
}
