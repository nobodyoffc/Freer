package com.fc.freer.initiate;

import android.content.Context;

import com.fc.fc_ajdk.client.BaseClientGroup;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.freer.FreerApplication;
import com.fc.freer.manager.CashManager;
import com.fc.freer.model.Configure;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;

/**
 * Freer-specific client group that extends BaseClientGroup.
 * Manages multiple API clients for a specific service type with Freer-specific functionality.
 */
public class ClientGroup extends BaseClientGroup {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final String TAG = "ClientGroup";

    // Freer-specific: maps accountId to ApiAccount
    private transient Map<String, ApiAccount> apiAccountMap;

    public ClientGroup(Service.ServiceType groupType) {
        super(groupType);
        this.apiAccountMap = new HashMap<>();
    }

    // ==================== BaseClientGroup Abstract Method Implementation ====================

    @Override
    protected boolean isClientValid(Object client) {
        if (client == null) {
            return false;
        }
        
        // 对于FapiClient，检查其可用性
        if (client instanceof FapiClient) {
            return ((FapiClient) client).isConnected();
        }
        
        // 对于FapiClient，检查其可用性
        if (client instanceof FapiClient fapiClient) {
            return fapiClient.isConnected();
        }
        
        // 对于其他类型的客户端，暂时返回true
        return true;
    }

    // ==================== Freer-Specific Methods ====================

    public void removeApiAccount(String accountId) {
        if (apiAccountMap != null) {
            apiAccountMap.remove(accountId);
        }
    }

    public void addClient(ApiAccount apiAccount) {
        addApiAccount(apiAccount);
        addClient(apiAccount.getId(), apiAccount.getClient());
    }

    /**
     * 添加API账户
     */
    public void addApiAccount(ApiAccount apiAccount) {
        if (apiAccountMap == null) apiAccountMap = new HashMap<>();
        apiAccountMap.put(apiAccount.getId(), apiAccount);
        TimberLogger.d(TAG, "Added API account: %s", apiAccount.getId());
    }

    /**
     * 获取指定账户ID的API账户
     */
    public ApiAccount getApiAccount(String accountId) {
        if (apiAccountMap == null || !apiAccountMap.containsKey(accountId)) {
            return null;
        }
        return apiAccountMap.get(accountId);
    }

    public Map<String, ApiAccount> getApiAccountMap() {
        return apiAccountMap;
    }

    public void setApiAccountMap(Map<String, ApiAccount> apiAccountMap) {
        this.apiAccountMap = apiAccountMap;
    }

    /**
     * 连接所有客户端
     * 为本group中的account请求连接并创建client
     */
    public void connectAllClients(Context context, byte[] symKey) {
        Configure configure = ConfigureManager.getInstance().getConfigure();
        if (configure == null) {
            TimberLogger.e(TAG, "Configure is null, cannot connect clients");
            return;
        }
        
        for (String accountId : getAccountIds()) {
            ApiAccount apiAccount = configure.getApiAccountMap().get(accountId);
            if (apiAccount == null) {
                TimberLogger.w(TAG, "ApiAccount not found for ID: %s", accountId);
                continue;
            }
            
            addApiAccount(apiAccount);
            
            // 根据服务类型创建相应的客户端
            Object client = createClientForAccount(context, apiAccount, configure, symKey);
            if (client == null) {
                TimberLogger.w(TAG, "Failed to create client for account: %s", accountId);
                continue;
            }
            
            apiAccount.setClient(client);
            addClient(apiAccount.getId(), client);
            TimberLogger.d(TAG, "Successfully created client for account: %s", accountId);
        }
    }
    
    /**
     * 为账户创建相应的客户端
     */
    private Object createClientForAccount(Context context, ApiAccount apiAccount, Configure configure, byte[] symKey) {
        ApiProvider apiProvider = configure.getApiProviderMap().get(apiAccount.getProviderId());
        if (apiProvider == null) {
            TimberLogger.e(TAG, "ApiProvider not found for ID: %s", apiAccount.getProviderId());
            return null;
        }
        
        switch (getGroupType()) {
            case FAPI_No1_NrC7 -> {
                // APIP 和 FAPI 统一使用 FapiClient
                Setting setting = SettingManager.getInstance().getCurrentSetting();
                return createFapiClient(context, apiAccount, apiProvider, setting);
            }
            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", getGroupType());
                return null;
            }
        }
    }
    
    /**
     * 创建 FapiClient（统一用于 APIP 和 FAPI）
     */
    private Object createFapiClient(Context context, ApiAccount apiAccount, ApiProvider apiProvider, Setting setting) {
        try {
            if (setting == null) {
                TimberLogger.e(TAG, "Setting is null, cannot create FapiClient");
                return null;
            }
            
            // Get or initialize FudpNode
            FudpNode fudpNode = setting.getFudpNode();
            if (fudpNode == null || !fudpNode.isRunning()) {
                String dataDir = context.getFilesDir().getAbsolutePath() + "/fudp";
                fudpNode = setting.initFudpNode(dataDir);
                if (fudpNode == null) {
                    TimberLogger.e(TAG, "Failed to initialize FudpNode");
                    return null;
                }
            }
            
            // Use bootstrap to discover and connect to FAPI service
            FapiClient client = FapiClient.bootstrap(fudpNode, setting.getSettingMap());
            if (client == null) {
                TimberLogger.w(TAG, "FapiClient bootstrap failed");
                return null;
            }


            // Set auto-recharge info if needed
            String mainFid = setting.getMainFid();
            if (mainFid != null) {
                client.setAutoRechargeInfo(mainFid, setting::decryptPrikey);
                client.setUtxoProvider(CashManager.UTXO_PROVIDER);
                client.setAutoRechargeVia(FreerApplication.FREER_APP_DEALER);
            }
            
            // No MAP registration here: a device keeps its presence only in its own
            // home.ROAD server's MAP, and only when it has one (ImManager's MapPresence).

            TimberLogger.d(TAG, "Created FapiClient for account: %s", apiAccount.getId());
            return client;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating FapiClient for account %s: %s", apiAccount.getId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 移除客户端 (override to also remove from apiAccountMap)
     */
    @Override
    public void removeClient(String accountId) {
        super.removeClient(accountId);
        if (apiAccountMap != null) {
            apiAccountMap.remove(accountId);
        }
    }

    /**
     * 清空所有客户端 (override to also clear apiAccountMap)
     */
    @Override
    public void clearClients() {
        super.clearClients();
        if (apiAccountMap != null) {
            apiAccountMap.clear();
        }
    }

    // ==================== Serialization ====================

    @Override
    protected void initTransientFields() {
        super.initTransientFields();
        if (apiAccountMap == null) {
            apiAccountMap = new HashMap<>();
        }
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initTransientFields();
    }
}
