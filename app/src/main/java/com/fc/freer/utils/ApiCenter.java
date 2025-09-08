package com.fc.freer.utils;

import android.content.Context;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.initiate.ClientGroup;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.model.Configure;
import com.fc.freer.model.Setting;
import com.fc.freer.model.ApiAccount;
import com.fc.freer.model.ApiProvider;
import com.fc.freer.network.ApipClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiCenter {
    public static final int MIN_REST_REQUEST_KB = 10;
    private static final String TAG = "ApiCenter";
    
    private static ApiCenter instance;
    private Setting currentSetting;
    private Configure currentConfigure;
    private Map<Service.ServiceType, ClientGroup> clientGroupMap;
    private ApipClient defaultApipClient; // 新增：默认ApipClient

    private ApiCenter() {
        // No context storage - context will be passed as parameters when needed
    }
    
    public static synchronized ApiCenter getInstance() {
        if (instance == null) {
            instance = new ApiCenter();
        }
        return instance;
    }

    /**
     * 初始化API服务管理
     * 根据当前状态决定是创建新的客户端组还是重连已有的客户端组
     */
    public void initiate(Context context) {
        TimberLogger.d(TAG, "Starting API service initialization");
        
        // 验证基本依赖
        if (context == null) {
            TimberLogger.e(TAG, "Context is null, cannot initialize");
            return;
        }
        
        // 加载当前配置和设置
        if (!loadCurrentState()) {
            TimberLogger.e(TAG, "Failed to load current state, cannot initialize");
            return;
        }
        
        // 初始化客户端组映射
        if (clientGroupMap == null) {
            clientGroupMap = new HashMap<>();
        }
        
        // 根据已有状态决定初始化策略
        InitializationStrategy strategy = determineInitializationStrategy();
        TimberLogger.d(TAG, "Using initialization strategy: %s", strategy);
        
        switch (strategy) {
            case CREATE_NEW -> {
                TimberLogger.d(TAG, "Creating new client groups");
                createAll(context);
            }
            case RECONNECT_EXISTING -> {
                TimberLogger.d(TAG, "Reconnecting to existing client groups");
                connectAll(context);
            }
            case HYBRID -> {
                TimberLogger.d(TAG, "Using hybrid approach - reconnect existing and create missing");
                hybridInitialization(context);
            }
        }
        
        TimberLogger.d(TAG, "API service initialization completed");

        currentSetting.setClientGroupMap(clientGroupMap);
        SettingManager.getInstance().saveSettings(context, currentSetting);
        TimberLogger.d(TAG, "Successfully initialized API services with %d client groups", clientGroupMap.size());
    }
    
    /**
     * 初始化策略枚举
     */
    private enum InitializationStrategy {
        CREATE_NEW,        // 创建全新的客户端组
        RECONNECT_EXISTING, // 重连已有的客户端组
        HYBRID            // 混合模式：重连已有的，创建缺失的
    }
    
    /**
     * 加载当前状态（配置和设置）
     */
    private boolean loadCurrentState() {
        // 加载配置
        if (currentConfigure == null) {
            currentConfigure = ConfigureManager.getInstance().getConfigure();
            if (currentConfigure == null) {
                TimberLogger.e(TAG, "Failed to load configure");
                return false;
            }
        }
        
        // 验证symkey
        byte[] symkey = currentConfigure.getSymkey();
        if (symkey == null) {
            TimberLogger.e(TAG, "Symkey is null in configure");
            return false;
        }
        
        // 加载设置（如果尚未加载）
        if (currentSetting == null) {
            currentSetting = SettingManager.getInstance().getCurrentSetting();
        }
        
        return true;
    }
    
    /**
     * 确定初始化策略
     */
    private InitializationStrategy determineInitializationStrategy() {
        // 如果没有当前设置或客户端组映射，创建新的
        if (currentSetting == null || 
            currentSetting.getClientGroupMap() == null || 
            currentSetting.getClientGroupMap().isEmpty()) {
            return InitializationStrategy.CREATE_NEW;
        }
        
        // 获取应用所需的服务类型
        Map<Service.ServiceType, Integer> requiredServices = FreerApplication.getServiceNumberMap();
        if (requiredServices == null || requiredServices.isEmpty()) {
            TimberLogger.w(TAG, "No required services defined, using CREATE_NEW strategy");
            return InitializationStrategy.CREATE_NEW;
        }
        
        // 检查已有的客户端组是否满足所有需求
        Map<Service.ServiceType, ClientGroup> existingGroups = currentSetting.getClientGroupMap();
        boolean hasAllRequiredTypes = requiredServices.keySet().stream()
            .allMatch(existingGroups::containsKey);
        
        if (hasAllRequiredTypes) {
            return InitializationStrategy.RECONNECT_EXISTING;
        } else {
            return InitializationStrategy.HYBRID;
        }
    }
    
    /**
     * 混合初始化：重连已有的，创建缺失的
     */
    private void hybridInitialization(Context context) {
        Map<Service.ServiceType, Integer> requiredServices = FreerApplication.getServiceNumberMap();
        Map<Service.ServiceType, ClientGroup> existingGroups = currentSetting.getClientGroupMap();
        
        for (Map.Entry<Service.ServiceType, Integer> entry : requiredServices.entrySet()) {
            Service.ServiceType serviceType = entry.getKey();
            
            if (existingGroups.containsKey(serviceType)) {
                // 重连已有的客户端组
                TimberLogger.d(TAG, "Reconnecting existing client group for: %s", serviceType);
                reconnectClientGroup(context, serviceType, existingGroups.get(serviceType));
            } else {
                // 创建新的客户端组
                TimberLogger.d(TAG, "Creating new client group for: %s", serviceType);
                createClientGroup(context, serviceType, entry.getValue());
            }
        }
    }
    
    /**
     * 重连单个客户端组
     */
    private void reconnectClientGroup(Context context, Service.ServiceType serviceType, ClientGroup savedGroup) {
        if (currentConfigure == null) {
            TimberLogger.e(TAG, "Current configure is null, cannot reconnect group for: %s", serviceType);
            return;
        }
        
        byte[] symkey = currentConfigure.getSymkey();
        ClientGroup newGroup = new ClientGroup(serviceType);
        
        List<String> accountIds = savedGroup.getAccountIds();
        if (accountIds == null || accountIds.isEmpty()) {
            TimberLogger.w(TAG, "No account IDs found for service type: %s", serviceType);
            return;
        }
        
        for (String accountId : accountIds) {
            if (reconnectSingleClient(context, serviceType, accountId, symkey, newGroup)) {
                TimberLogger.d(TAG, "Successfully reconnected client: %s", accountId);
            } else {
                TimberLogger.w(TAG, "Failed to reconnect client: %s", accountId);
            }
        }
        
        if (newGroup.getClientCount() > 0) {
            clientGroupMap.put(serviceType, newGroup);
        }
    }
    
    /**
     * 重连单个客户端
     */
    private boolean reconnectSingleClient(Context context, Service.ServiceType serviceType, 
                                        String accountId, byte[] symkey, ClientGroup clientGroup) {
        ApiAccount apiAccount = currentConfigure.getApiAccountMap().get(accountId);
        if (apiAccount == null) {
            TimberLogger.w(TAG, "ApiAccount not found for ID: %s", accountId);
            return false;
        }
        
        // 解密sessionKey
        if (apiAccount.getSession() != null && apiAccount.getSession().getKeyCipher() != null) {
            byte[] sessionKey = decryptSessionKey(apiAccount.getSession().getKeyCipher(), symkey);
            if (sessionKey != null) {
                apiAccount.setSessionKey(sessionKey);
            } else {
                TimberLogger.w(TAG, "Failed to decrypt session key for account: %s", accountId);
            }
        }
        
        ApiProvider apiProvider = currentConfigure.getApiProviderMap().get(apiAccount.getProviderId());
        if (apiProvider == null) {
            TimberLogger.w(TAG, "ApiProvider not found for ID: %s", apiAccount.getProviderId());
            return false;
        }
        
        return createAndConnectClient(context, serviceType, apiAccount, apiProvider, symkey, accountId, clientGroup);
    }
    
    /**
     * 创建单个客户端组
     */
    private void createClientGroup(Context context, Service.ServiceType serviceType, int targetCount) {
        if (currentConfigure == null) {
            TimberLogger.e(TAG, "Current configure is null, cannot create group for: %s", serviceType);
            return;
        }
        
        byte[] symkey = currentConfigure.getSymkey();
        ClientGroup clientGroup = new ClientGroup(serviceType);
        
        String[] defaultAPIs = getDefaultAPIsForServiceType(serviceType);
        if (defaultAPIs == null || defaultAPIs.length == 0) {
            TimberLogger.w(TAG, "No default APIs found for service type: %s", serviceType);
            return;
        }
        
        int connectedCount = 0;
        for (String apiUrl : defaultAPIs) {
            if (connectedCount >= targetCount) {
                break;
            }
            
            if (createAndTestClient(context, serviceType, apiUrl, symkey, clientGroup)) {
                connectedCount++;
                TimberLogger.d(TAG, "Successfully connected client for URL: %s", apiUrl);
            }
        }
        
        if (connectedCount > 0) {
            clientGroupMap.put(serviceType, clientGroup);
            updateSettingsWithClientGroup(context, serviceType, clientGroup);
        }
    }
    
    /**
     * 更新设置中的客户端组
     */
    private void updateSettingsWithClientGroup(Context context, Service.ServiceType serviceType, ClientGroup clientGroup) {
        if (currentSetting != null) {
            if (currentSetting.getClientGroupMap() == null) {
                currentSetting.setClientGroupMap(new HashMap<>());
            }
            currentSetting.getClientGroupMap().put(serviceType, clientGroup);
            SettingManager.getInstance().saveSettings(context, currentSetting);
        }
    }

    /**
     * 创建所有API服务的客户端组
     * 从FreerApplication中获取serviceNumberMap，为每个serviceType创建相应数量的client
     */
    public void createAll(Context context) {
        TimberLogger.d(TAG, "Creating all API service client groups");
        
        // 验证前置条件（重用已有的状态加载逻辑）
        if (!validateCreateAllPreconditions(context)) {
            return;
        }
        
        // 获取应用所需的服务配置
        Map<Service.ServiceType, Integer> serviceRequirements = getServiceRequirements();
        if (serviceRequirements.isEmpty()) {
            TimberLogger.w(TAG, "No service requirements defined, nothing to create");
            return;
        }
        
        // 初始化客户端组映射
        initializeClientGroupMap();
        
        // 创建结果跟踪
        CreateAllResult result = new CreateAllResult();
        
        // 为每个服务类型创建客户端组
        for (Map.Entry<Service.ServiceType, Integer> entry : serviceRequirements.entrySet()) {
            Service.ServiceType serviceType = entry.getKey();
            int targetCount = entry.getValue();
            
            CreateGroupResult groupResult = createSingleServiceGroup(context, serviceType, targetCount);
            result.addGroupResult(serviceType, groupResult);
            
            if (groupResult.isSuccessful()) {
                clientGroupMap.put(serviceType, groupResult.getClientGroup());
                TimberLogger.d(TAG, "Created client group for %s with %d clients", 
                    serviceType, groupResult.getConnectedCount());
            }
        }
        
        // 统一保存所有设置（避免多次保存）
        saveAllClientGroups(context, result);
        
        // 记录创建结果摘要
        logCreateAllSummary(result);
    }
    
    /**
     * 验证createAll的前置条件
     */
    private boolean validateCreateAllPreconditions(Context context) {
        if (context == null) {
            TimberLogger.e(TAG, "Context is null, cannot create client groups");
            return false;
        }
        
        // 重用已有的状态加载逻辑，避免代码重复
        if (!loadCurrentState()) {
            TimberLogger.e(TAG, "Failed to load required state for createAll");
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取服务需求配置
     */
    private Map<Service.ServiceType, Integer> getServiceRequirements() {
        Map<Service.ServiceType, Integer> serviceNumberMap = FreerApplication.getServiceNumberMap();
        if (serviceNumberMap == null || serviceNumberMap.isEmpty()) {
            TimberLogger.w(TAG, "Service number map is empty");
            return new HashMap<>();
        }
        return serviceNumberMap;
    }
    
    /**
     * 初始化客户端组映射
     */
    private void initializeClientGroupMap() {
        if (clientGroupMap == null) {
            clientGroupMap = new HashMap<>();
        }
    }
    
    /**
     * 创建单个服务类型的客户端组
     */
    private CreateGroupResult createSingleServiceGroup(Context context, Service.ServiceType serviceType, int targetCount) {
        TimberLogger.d(TAG, "Creating client group for service type: %s, target count: %d", serviceType, targetCount);
        
        CreateGroupResult result = new CreateGroupResult(serviceType, targetCount);
        
        // 获取可用的API端点
        String[] availableAPIs = getDefaultAPIsForServiceType(serviceType);
        if (availableAPIs == null || availableAPIs.length == 0) {
            TimberLogger.w(TAG, "No default APIs available for service type: %s", serviceType);
            result.setFailureReason("No default APIs available");
            return result;
        }
        
        ClientGroup clientGroup = new ClientGroup(serviceType);
        byte[] symkey = currentConfigure.getSymkey();
        
        // 尝试连接到各个API端点
        for (String apiUrl : availableAPIs) {
            if (result.getConnectedCount() >= targetCount) {
                break; // 已达到目标数量
            }
            
            if (attemptClientConnection(context, serviceType, apiUrl, symkey, clientGroup, result)) {
                result.incrementConnectedCount();
                TimberLogger.d(TAG, "Successfully connected to %s (%d/%d)", 
                    apiUrl, result.getConnectedCount(), targetCount);
            } else {
                result.addFailedUrl(apiUrl);
            }
        }
        
        result.setClientGroup(clientGroup);
        result.markComplete();
        
        return result;
    }
    
    /**
     * 尝试连接到单个API端点
     */
    private boolean attemptClientConnection(Context context, Service.ServiceType serviceType, 
                                          String apiUrl, byte[] symkey, ClientGroup clientGroup, 
                                          CreateGroupResult result) {
        try {
            TimberLogger.d(TAG, "Attempting connection to: %s", apiUrl);
            
            if (createAndTestClient(context, serviceType, apiUrl, symkey, clientGroup)) {
                result.addSuccessfulUrl(apiUrl);
                return true;
            }
            return false;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Exception connecting to %s: %s", apiUrl, e.getMessage());
            result.addFailedUrl(apiUrl, e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存所有客户端组到设置
     */
    private void saveAllClientGroups(Context context, CreateAllResult result) {
        if (currentSetting == null) {
            TimberLogger.w(TAG, "Current setting is null, cannot save client groups");
            return;
        }
        
        // 只有在有成功创建的客户端组时才保存
        if (result.hasSuccessfulGroups()) {
            currentSetting.setClientGroupMap(clientGroupMap);
            SettingManager.getInstance().saveSettings(context, currentSetting);
            TimberLogger.d(TAG, "Saved %d client groups to settings", result.getSuccessfulGroupCount());
        } else {
            TimberLogger.w(TAG, "No successful client groups to save");
        }
    }
    
    /**
     * 记录创建结果摘要
     */
    private void logCreateAllSummary(CreateAllResult result) {
        TimberLogger.d(TAG, "CreateAll completed - Success: %d groups, Failed: %d groups, Total connections: %d", 
            result.getSuccessfulGroupCount(), 
            result.getFailedGroupCount(),
            result.getTotalConnections());
        
        if (result.hasFailures()) {
            TimberLogger.w(TAG, "Some service groups failed to initialize: %s", 
                result.getFailedServiceTypes());
        }
    }
    
    /**
     * 创建结果类 - 跟踪整个创建过程的结果
     */
    private static class CreateAllResult {
        private final Map<Service.ServiceType, CreateGroupResult> groupResults = new HashMap<>();
        
        public void addGroupResult(Service.ServiceType serviceType, CreateGroupResult result) {
            groupResults.put(serviceType, result);
        }
        
        public boolean hasSuccessfulGroups() {
            return groupResults.values().stream().anyMatch(CreateGroupResult::isSuccessful);
        }
        
        public int getSuccessfulGroupCount() {
            return (int) groupResults.values().stream().filter(CreateGroupResult::isSuccessful).count();
        }
        
        public int getFailedGroupCount() {
            return (int) groupResults.values().stream().filter(result -> !result.isSuccessful()).count();
        }
        
        public int getTotalConnections() {
            return groupResults.values().stream().mapToInt(CreateGroupResult::getConnectedCount).sum();
        }
        
        public boolean hasFailures() {
            return groupResults.values().stream().anyMatch(result -> !result.isSuccessful());
        }
        
        public String getFailedServiceTypes() {
            return groupResults.entrySet().stream()
                .filter(entry -> !entry.getValue().isSuccessful())
                .map(entry -> entry.getKey().toString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
        }
    }
    
    /**
     * 单个服务组创建结果类
     */
    private static class CreateGroupResult {
        private final Service.ServiceType serviceType;
        private final int targetCount;
        private int connectedCount = 0;
        private ClientGroup clientGroup;
        private String failureReason;
        private final List<String> successfulUrls = new ArrayList<>();
        private final List<String> failedUrls = new ArrayList<>();
        private final Map<String, String> failureDetails = new HashMap<>();
        private boolean completed = false;
        
        public CreateGroupResult(Service.ServiceType serviceType, int targetCount) {
            this.serviceType = serviceType;
            this.targetCount = targetCount;
        }
        
        public void incrementConnectedCount() { connectedCount++; }
        public void setClientGroup(ClientGroup clientGroup) { this.clientGroup = clientGroup; }
        public void setFailureReason(String reason) { this.failureReason = reason; }
        public void addSuccessfulUrl(String url) { successfulUrls.add(url); }
        public void addFailedUrl(String url) { failedUrls.add(url); }
        public void addFailedUrl(String url, String reason) { 
            failedUrls.add(url);
            failureDetails.put(url, reason);
        }
        public void markComplete() { completed = true; }
        
        public boolean isSuccessful() { return completed && connectedCount > 0; }
        public int getConnectedCount() { return connectedCount; }
        public ClientGroup getClientGroup() { return clientGroup; }
        public Service.ServiceType getServiceType() { return serviceType; }
    }


    /**
     * 连接所有已保存的API服务
     * 对每个类型的clientGroup获取apiAccountIds，重新建立连接
     */
    public void connectAll(Context context) {
        TimberLogger.d(TAG, "Connecting to all saved API services");
        
        if (currentSetting == null || currentSetting.getClientGroupMap() == null) {
            TimberLogger.w(TAG, "No client groups found in current setting");
            return;
        }
        
        if (currentConfigure == null) {
            TimberLogger.e(TAG, "Current configure is null, cannot connect clients");
            return;
        }
        
        byte[] symkey = currentConfigure.getSymkey();
        if (symkey == null) {
            TimberLogger.e(TAG, "Symkey is null, cannot connect clients");
            return;
        }
        
        // 初始化clientGroupMap
        if (clientGroupMap == null) {
            clientGroupMap = new HashMap<>();
        }
        
        // 对每个类型的clientGroup
        for (Map.Entry<Service.ServiceType, ClientGroup> entry : currentSetting.getClientGroupMap().entrySet()) {
            Service.ServiceType serviceType = entry.getKey();
            ClientGroup clientGroup = entry.getValue();
            if(clientGroup==null)clientGroup=new ClientGroup(serviceType);
            
            TimberLogger.d(TAG, "Connecting clients for service type: %s", serviceType);
            
            // 获取apiAccountIds
            List<String> accountIds = clientGroup.getAccountIds();
            if (accountIds == null || accountIds.isEmpty()) {
                TimberLogger.w(TAG, "No account IDs found for service type: %s", serviceType);
                continue;
            }
            
            // 对每个apiAccountId
            for (String accountId : accountIds) {
                // 从configure中获取apiAccount
                ApiAccount apiAccount = currentConfigure.getApiAccountMap().get(accountId);
                if (apiAccount == null) {
                    TimberLogger.w(TAG, "ApiAccount not found for ID: %s", accountId);
                    continue;
                }
                
                // 解密sessionKey
                if (apiAccount.getSession()!= null && apiAccount.getSession().getKeyCipher() != null) {
                    byte[] sessionKey = decryptSessionKey(apiAccount.getSession().getKeyCipher(), symkey);
                    if (sessionKey != null) {
                        apiAccount.setSessionKey(sessionKey);
                        TimberLogger.d(TAG, "Successfully decrypted session key for account: %s", accountId);
                    } else {
                        TimberLogger.w(TAG, "Failed to decrypt session key for account: %s", accountId);
                    }
                }
                
                // 用apiAccount.providerId从configure中获取apiProvider
                ApiProvider apiProvider = currentConfigure.getApiProviderMap().get(apiAccount.getProviderId());
                if (apiProvider == null) {
                    TimberLogger.w(TAG, "ApiProvider not found for ID: %s", apiAccount.getProviderId());
                    continue;
                }
                
                // 创建并连接client
                if (createAndConnectClient(context, serviceType, apiAccount, apiProvider, symkey, accountId, clientGroup)) {
                    TimberLogger.d(TAG, "Successfully reconnected client for account: %s", accountId);
                } else {
                    TimberLogger.w(TAG, "Failed to reconnect client for account: %s", accountId);
                }
            }

            // 保存clientGroup
            if (clientGroup.getClientCount() > 0) {
                clientGroupMap.put(serviceType, clientGroup);
                TimberLogger.d(TAG, "Saved client group for service type: %s with %d connected clients", serviceType, clientGroup.getClientCount());
            }
        }

        TimberLogger.d(TAG, "Completed connecting to all saved API services");
    }

    /**
     * 创建并连接client
     */
    private boolean createAndConnectClient(Context context, Service.ServiceType serviceType, ApiAccount apiAccount, ApiProvider apiProvider, 
                                         byte[] symkey, String accountId, ClientGroup clientGroup) {
        try {
            // 尝试用原始配置创建client
            Object client = createClientFromAccount(context, serviceType, apiAccount, apiProvider, symkey);
            if (client ==null) {
                TimberLogger.w(TAG, "Original connection failed for account: %s, trying default URLs", accountId);
                return tryDefaultUrls(context, serviceType, symkey, accountId, apiAccount, clientGroup);
            }
            clientGroup.addClient(apiAccount.getId(),client);
            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating client for account %s: %s", accountId, e.getMessage());
            return false;
        }
    }

    /**
     * 测试并添加client到组中
     */
    private boolean testAndAddClient(Context context, ApipClient client, String accountId, ApiAccount apiAccount, ClientGroup clientGroup) {
        boolean pingResult = client.ping(context,com.fc.fc_ajdk.utils.http.RequestMethod.POST);
        if (pingResult) {
            client.setConnected(true);
            clientGroup.addClient(accountId, client);
            clientGroup.addApiAccount(apiAccount);

            if (defaultApipClient == null) {
                defaultApipClient = client;
                TimberLogger.d(TAG, "Set defaultApipClient for account: %s", accountId);
            }
            return true;
        }
        return false;
    }

    /**
     * 尝试使用默认URLs重连
     */
    private boolean tryDefaultUrls(Context context, Service.ServiceType serviceType, byte[] symkey, String accountId, 
                                 ApiAccount apiAccount, ClientGroup clientGroup) {
        String[] defaultAPIs = getDefaultAPIsForServiceType(serviceType);
        
        for (String apiUrl : defaultAPIs) {
            // 跳过已在使用的URL
            if (isUrlInUse(apiUrl, clientGroup)) continue;

            Object newClient = createClientForServiceType(context, serviceType, apiUrl, symkey);
            if (newClient instanceof ApipClient newApipClient) {
                if (testAndAddClient(context, newApipClient, accountId, apiAccount, clientGroup)) {
                    TimberLogger.d(TAG, "Successfully reconnected account %s using URL: %s", accountId, apiUrl);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查URL是否已被使用
     */
    private boolean isUrlInUse(String apiUrl, ClientGroup clientGroup) {
        if (apiUrl == null || clientGroup == null) {
            return false;
        }
        
        Map<String, ApiAccount> apiAccountMap = clientGroup.getApiAccountMap();
        if (apiAccountMap == null || apiAccountMap.isEmpty()) {
            return false;
        }
        
        // 使用Stream API提高效率和可读性
        return apiAccountMap.values().stream()
            .anyMatch(account -> apiUrl.equals(account.getApiUrl()));
    }

    /**
     * 获取指定类型的已连接client
     * @param type 服务类型
     * @return 已连接的client，如果没有则返回null
     */
    public Object getClient(Service.ServiceType type) {
        try {
            if (clientGroupMap == null) {
                TimberLogger.w(TAG, "ClientGroupMap is null, no clients available");
                return null;
            }
            
            ClientGroup clientGroup = clientGroupMap.get(type);
            if (clientGroup == null) {
                TimberLogger.w(TAG, "No client group found for service type: %s", type);
                return null;
            }
            
            Object client = clientGroup.getClient();
            if (client == null) {
                TimberLogger.w(TAG, "No connected client found for service type: %s", type);
            }
            
            return client;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting client for type %s: %s", type, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取指定类型的所有已连接client
     * @param type 服务类型
     * @return client映射，key为apiAccountId
     */
    public Map<String, Object> getClientMap(Service.ServiceType type) {
        try {
            if (clientGroupMap == null) {
                TimberLogger.w(TAG, "ClientGroupMap is null, returning empty map");
                return new HashMap<>();
            }
            
            ClientGroup clientGroup = clientGroupMap.get(type);
            if (clientGroup == null) {
                TimberLogger.w(TAG, "No client group found for service type: %s, returning empty map", type);
                return new HashMap<>();
            }
            
            Map<String, Object> clientMap = clientGroup.getClientMap();
            return clientMap != null ? clientMap : new HashMap<>();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting client map for type %s: %s", type, e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * 获取指定类型的clientGroup
     * @param type 服务类型
     * @return clientGroup，如果没有则返回null
     */
    public ClientGroup getClientGroup(Service.ServiceType type) {
        try {
            if (clientGroupMap == null) {
                TimberLogger.w(TAG, "ClientGroupMap is null, no client groups available");
                return null;
            }
            
            ClientGroup clientGroup = clientGroupMap.get(type);
            if (clientGroup == null) {
                TimberLogger.w(TAG, "No client group found for service type: %s", type);
            }
            
            return clientGroup;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting client group for type %s: %s", type, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 创建并测试client连接
     */
    private boolean createAndTestClient(Context context, Service.ServiceType serviceType, String apiUrl, byte[] symkey, ClientGroup clientGroup) {
        try {
            // 创建客户端
            Object client = createClientForServiceType(context, serviceType, apiUrl, symkey);
            if (client == null) {
                TimberLogger.w(TAG, "Failed to create client for URL: %s", apiUrl);
                return false;
            }
            
            // 目前只支持ApipClient
            if (client instanceof ApipClient apipClient) {
                return validateAndAddApipClient(apipClient, clientGroup);
            } else {
                TimberLogger.w(TAG, "Unsupported client type for URL: %s", apiUrl);
                return false;
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Exception creating and testing client for URL %s: %s", apiUrl, e.getMessage());
            return false;
        }
    }
    
    /**
     * 验证并添加APIP客户端到组中
     */
    private boolean validateAndAddApipClient(ApipClient apipClient, ClientGroup clientGroup) {
        try {
            // 获取API账户信息
            ApiAccount apiAccount = apipClient.getApiAccount();
            if (apiAccount == null) {
                TimberLogger.w(TAG, "ApipClient has null ApiAccount");
                return false;
            }
            
            // 检查客户端是否已连接（创建时可能已经测试过连接）
            if (apipClient.getConnected()==null || !apipClient.getConnected()) {
                TimberLogger.w(TAG, "ApipClient for account %s is not connected", apiAccount.getId());
                return false;
            }
            
            // 添加到客户端组
            clientGroup.addClient(apiAccount.getId(), apipClient);
            clientGroup.addApiAccount(apiAccount);
            clientGroup.addAccountIds(apiAccount.getId());

            // 设置默认APIP客户端（如果尚未设置）
            setDefaultApipClientIfNeeded(apipClient, apiAccount);
            
            TimberLogger.d(TAG, "Successfully added ApipClient for account: %s", apiAccount.getId());
            return true;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error validating and adding ApipClient: %s", e.getMessage());
            return false;
        }
    }
    
    /**
     * 如果需要，设置默认APIP客户端
     */
    private void setDefaultApipClientIfNeeded(ApipClient apipClient, ApiAccount apiAccount) {
        if (defaultApipClient == null) {
            defaultApipClient = apipClient;
            TimberLogger.d(TAG, "Set default ApipClient for account: %s", apiAccount.getId());
        }
    }

    /**
     * 根据服务类型获取默认API URL列表
     */
    private String[] getDefaultAPIsForServiceType(Service.ServiceType serviceType) {
        return switch (serviceType) {
            case APIP -> ApipClient.defaultAPIs;
            case DISK ->
                // TODO: 实现DISK客户端的defaultAPIs
                    new String[0];
            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", serviceType);
                yield new String[0];
            }
        };
    }

    /**
     * 根据服务类型和URL创建client
     */
    private Object createClientForServiceType(Context context, Service.ServiceType serviceType, String url, byte[] symkey) {
        return switch (serviceType) {
            case APIP -> ApipClient.createClient(context, url, symkey);
            case DISK ->
                // TODO: 实现DISK客户端的创建
                    null;
            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", serviceType);
                yield null;
            }
        };
    }

    /**
     * 根据ApiAccount和ApiProvider创建client
     */
    private Object createClientFromAccount(Context context, Service.ServiceType serviceType, ApiAccount apiAccount, ApiProvider apiProvider, byte[] symkey) {
        return switch (serviceType) {
            case APIP -> ApipClient.createClient(context, apiAccount, apiProvider, symkey);
            case DISK ->
                // TODO: 实现DISK客户端的创建
                    null;
            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", serviceType);
                yield null;
            }
        };
    }


    /**
     * 处理session key的加密转换
     */
    private String processSessionKey(String sessionKeyCipher, byte[] prikey, byte[] symkey) {
        try {
            // 用prikey解密sessionKeyCipher
            Decryptor decryptor = new Decryptor();
            CryptoDataByte cryptoDataByte = decryptor.decryptJsonByAsyOneWay(sessionKeyCipher, prikey);
            if (cryptoDataByte.getCode() != 0) {
                return null;
            }
            
            byte[] sessionKey = cryptoDataByte.getData();
            
            // 用symkey重新加密
            Encryptor encryptor = new Encryptor();
            CryptoDataByte newCryptoDataByte = encryptor.encryptBySymkey(sessionKey, symkey);
            if (newCryptoDataByte.getCode() != 0) {
                return null;
            }
            
            return newCryptoDataByte.toJson();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to process session key: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除指定账户ID的客户端
     * @param serviceType 服务类型
     * @param accountId 账户ID
     * @param clientGroup 客户端组
     */
    private void deleteClient(Context context, Service.ServiceType serviceType, String accountId, ClientGroup clientGroup) {
        // 从clientGroup中删除该apiAccount和client
        clientGroup.removeClient(accountId);
        clientGroup.removeApiAccount(accountId);
        
        // 更新currentSetting中的clientGroup并永久化保存
        if (currentSetting != null && currentSetting.getClientGroupMap() != null) {
            currentSetting.getClientGroupMap().put(serviceType, clientGroup);
            SettingManager.getInstance().saveSettings(context, currentSetting);
            TimberLogger.d(TAG, "Deleted client for account: %s and saved updated setting", accountId);
        }
    }

    /**
     * 解密session key
     */
    private byte[] decryptSessionKey(String sessionKeyCipher, byte[] symkey) {
        try {
            Decryptor decryptor = new Decryptor();
            CryptoDataByte cryptoDataByte = decryptor.decryptJsonBySymkey(sessionKeyCipher, symkey);
            if (cryptoDataByte.getCode() == 0) {
                return cryptoDataByte.getData();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to decrypt session key: %s", e.getMessage(), e);
        }
        
        return null;
    }

    public Setting getCurrentSetting() {
        return currentSetting;
    }

    public void setCurrentSetting(Setting currentSetting) {
        this.currentSetting = currentSetting;
    }

    public Configure getCurrentConfigure() {
        return currentConfigure;
    }

    public void setCurrentConfigure(Configure currentConfigure) {
        this.currentConfigure = currentConfigure;
    }

    public Map<Service.ServiceType, ClientGroup> getClientGroupMap() {
        return clientGroupMap;
    }

    public void setClientGroupMap(Map<Service.ServiceType, ClientGroup> clientGroupMap) {
        this.clientGroupMap = clientGroupMap;
    }

    public ApipClient getDefaultApipClient() {
        return defaultApipClient;
    }

    public void setDefaultApipClient(ApipClient defaultApipClient) {
        this.defaultApipClient = defaultApipClient;
    }
}