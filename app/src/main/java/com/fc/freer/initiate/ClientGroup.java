package com.fc.freer.initiate;

import android.content.Context;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.model.ApiAccount;
import com.fc.freer.model.ApiProvider;
import com.fc.freer.model.Configure;
import com.fc.freer.network.ApipClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ClientGroup implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final String TAG = "ClientGroup";

    private Service.ServiceType groupType;
    private List<String> accountIds;
    private GroupStrategy strategy;
    private transient int roundRobinIndex = 0;
    private transient Map<String, Object> clientMap;
    private transient Map<String, ApiAccount> apiAccountMap;

    public void removeApiAccount(String accountId) {
        if (apiAccountMap != null) {
            apiAccountMap.remove(accountId);
        }
    }

    public enum GroupStrategy {
        USE_FIRST,           // Use the first available client
        USE_ANY_VALID,       // Use any valid client
        USE_ALL,            // Use all clients
        USE_ONE_RANDOM,     // Use a random client
        USE_ONE_ROUND_ROBIN; // Use clients in round-robin fashion

        @Override
        public String toString() {
            return name();
        }
    }

    public ClientGroup(Service.ServiceType groupType) {
        this.groupType = groupType;
        this.accountIds = new ArrayList<>();
        this.clientMap = new HashMap<>();
        this.apiAccountMap = new HashMap<>();
        this.strategy = GroupStrategy.USE_FIRST; // default strategy
    }

    public static void main(String[] args) {
        ClientGroup clientGroup = new ClientGroup(Service.ServiceType.APIP);
        clientGroup.getAccountIds().add("account id 1");
        clientGroup.setStrategy(GroupStrategy.USE_ALL);
        Map<Service.ServiceType,ClientGroup>  map = new HashMap<>();
        map.put(Service.ServiceType.APIP,clientGroup);
        System.out.println(new Gson().toJson(map));
    }

    /**
     * 添加客户端到组中
     */
    public void addClient(String accountId, Object client) {
        if (!accountIds.contains(accountId)) {
            accountIds.add(accountId);
        }
        if(clientMap==null) clientMap = new HashMap<>();
        clientMap.put(accountId, client);
        TimberLogger.d(TAG, "Added client for account: %s", accountId);
    }

    /**
     * 将客户端添加到第一个位置
     */
    public void addToFirstClient(String accountId, Object client) {
        if (accountIds.isEmpty()) {
            accountIds.add(0, accountId);
        } else {
            accountIds.add(0, accountId);
        }
        if(clientMap==null) clientMap = new HashMap<>();
        clientMap.put(accountId, client);
        TimberLogger.d(TAG, "Added client to first position for account: %s", accountId);
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
     * 连接所有客户端
     * 为本group中的account请求连接并创建client
     */
    public void connectAllClients(Context context, byte[] symKey) {
        Configure configure = ConfigureManager.getInstance().getConfigure();
        if (configure == null) {
            TimberLogger.e(TAG, "Configure is null, cannot connect clients");
            return;
        }
        
        for (String accountId : accountIds) {
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
        
        switch (groupType) {
            case APIP -> {
                return createApipClient(context, apiAccount, apiProvider, symKey);
            }
            case DISK -> {
                return createDiskClient(context, apiAccount, apiProvider, symKey);
            }
            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", groupType);
                return null;
            }
        }
    }
    
    /**
     * 创建APIP客户端
     */
    private Object createApipClient(Context context, ApiAccount apiAccount, ApiProvider apiProvider, byte[] symKey) {
        // 使用ApipClient的静态方法创建客户端
        return ApipClient.createClient(context, apiAccount, apiProvider, symKey);
    }
    
    /**
     * 创建DISK客户端
     */
    private Object createDiskClient(Context context, ApiAccount apiAccount, ApiProvider apiProvider, byte[] symKey) {
        try {
            // 这里可以实现DISK客户端的创建逻辑
            // 暂时返回null，等待DISK客户端的实现
            TimberLogger.w(TAG, "DiskClient creation not implemented yet");
            return null;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating DiskClient for account %s: %s", apiAccount.getId(), e.getMessage(), e);
            return null;
        }
    }
    


    /**
     * 根据策略获取账户ID
     */
    public String getAccountId() {
        if (accountIds.isEmpty()) {
            return null;
        }

        switch (strategy) {
            case USE_FIRST, USE_ALL -> {
                return accountIds.get(0);
            }
            case USE_ANY_VALID -> {
                // Return first valid client found
                for (String accountId : accountIds) {
                    Object client = clientMap.get(accountId);
                    if (isClientValid(client)) {
                        return accountId;
                    }
                }
                return null;
            }
            case USE_ONE_RANDOM -> {
                return accountIds.get(new Random().nextInt(accountIds.size()));
            }
            case USE_ONE_ROUND_ROBIN -> {
                return accountIds.get(roundRobinIndex++ % accountIds.size());
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 根据策略获取客户端
     */
    public Object getClient() {
        if (accountIds.isEmpty()) {
            return null;
        }

        switch (strategy) {
            case USE_FIRST -> {
                return clientMap == null ? null : clientMap.get(accountIds.get(0));
            }
            case USE_ANY_VALID -> {
                // Return first valid client found
                for (String accountId : accountIds) {
                    Object client = clientMap.get(accountId);
                    if (isClientValid(client)) {
                        return client;
                    }
                }
                return null;
            }
            case USE_ALL -> {
                return clientMap;
            }
            case USE_ONE_RANDOM -> {
                String randomId = accountIds.get(new Random().nextInt(accountIds.size()));
                return clientMap.get(randomId);
            }
            case USE_ONE_ROUND_ROBIN -> {
                String nextId = accountIds.get(roundRobinIndex++ % accountIds.size());
                return clientMap.get(nextId);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 获取指定账户ID的客户端
     */
    public Object getClient(String accountId) {
        if (clientMap == null || !clientMap.containsKey(accountId)) {
            return null;
        }
        return clientMap.get(accountId);
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

    /**
     * 获取所有可用的客户端
     */
    public List<Object> getValidClients() {
        List<Object> validClients = new ArrayList<>();
        if (clientMap == null) {
            return validClients;
        }
        
        for (Object client : clientMap.values()) {
            if (isClientValid(client)) {
                validClients.add(client);
            }
        }
        return validClients;
    }

    /**
     * 获取所有可用的账户ID
     */
    public List<String> getValidAccountIds() {
        List<String> validAccountIds = new ArrayList<>();
        if (clientMap == null) {
            return validAccountIds;
        }
        
        for (Map.Entry<String, Object> entry : clientMap.entrySet()) {
            if (isClientValid(entry.getValue())) {
                validAccountIds.add(entry.getKey());
            }
        }
        return validAccountIds;
    }

    /**
     * 检查客户端是否有效
     */
    private boolean isClientValid(Object client) {
        if (client == null) {
            return false;
        }
        
        // 对于ApipClient，检查其可用性
        if (client instanceof ApipClient) {
            return ((ApipClient) client).getConnected();
        }
        
        // 对于其他类型的客户端，暂时返回true
        // 可以根据需要添加更多的验证逻辑
        return true;
    }

    /**
     * 移除客户端
     */
    public void removeClient(String accountId) {
        if (accountIds.contains(accountId)) {
            accountIds.remove(accountId);
        }
        if (clientMap != null && clientMap.containsKey(accountId)) {
            clientMap.remove(accountId);
        }
        if (apiAccountMap != null && apiAccountMap.containsKey(accountId)) {
            apiAccountMap.remove(accountId);
        }
        TimberLogger.d(TAG, "Removed client for account: %s", accountId);
    }

    /**
     * 清空所有客户端
     */
    public void clearClients() {
        accountIds.clear();
        if (clientMap != null) {
            clientMap.clear();
        }
        if (apiAccountMap != null) {
            apiAccountMap.clear();
        }
        roundRobinIndex = 0;
        TimberLogger.d(TAG, "Cleared all clients");
    }

    /**
     * 获取客户端数量
     */
    public int getClientCount() {
        return accountIds.size();
    }

    /**
     * 获取有效客户端数量
     */
    public int getValidClientCount() {
        return getValidClients().size();
    }

    /**
     * 检查是否有可用的客户端
     */
    public boolean hasValidClients() {
        return getValidClientCount() > 0;
    }

    /**
     * 获取组状态信息
     */
    public String getStatus() {
        int total = getClientCount();
        int valid = getValidClientCount();
        return String.format("Group[%s]: %d/%d clients available, strategy: %s", 
                           groupType, valid, total, strategy);
    }

    private void initTransientFields() {
        if (clientMap == null) {
            clientMap = new HashMap<>();
        }
        if (apiAccountMap == null) {
            apiAccountMap = new HashMap<>();
        }
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initTransientFields();
    }

    // Getters and setters
    public Service.ServiceType getGroupType() {
        return groupType;
    }

    public List<String> getAccountIds() {
        return accountIds;
    }

    public void addAccountIds(String accountId) {
        if(accountIds==null) accountIds = new ArrayList<>();
        else if(accountIds.contains(accountId))return;
        accountIds.add(accountId);
    }

    public Map<String, Object> getClientMap() {
        return clientMap;
    }

    public Map<String, ApiAccount> getApiAccountMap() {
        return apiAccountMap;
    }

    public GroupStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(GroupStrategy strategy) {
        this.strategy = strategy;
    }

    public void setGroupType(Service.ServiceType groupType) {
        this.groupType = groupType;
    }

    public void setAccountIds(List<String> accountIds) {
        this.accountIds = accountIds;
    }

    public void setClientMap(Map<String, Object> clientMap) {
        this.clientMap = clientMap;
    }

    public void setApiAccountMap(Map<String, ApiAccount> apiAccountMap) {
        this.apiAccountMap = apiAccountMap;
    }

    public int getRoundRobinIndex() {
        return roundRobinIndex;
    }

    public void setRoundRobinIndex(int roundRobinIndex) {
        this.roundRobinIndex = roundRobinIndex;
    }
}