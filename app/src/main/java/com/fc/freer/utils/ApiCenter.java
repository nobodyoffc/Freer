package com.fc.freer.utils;

import static com.fc.fc_ajdk.data.fcData.AlgorithmId.FC_AesGcm256_No1_NrC7;

import android.content.Context;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.fapi.FapiCode;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.ClientGroup;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Configure;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.fc_ajdk.fapi.client.ApiProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiCenter {
    private static final String TAG = "ApiCenter";
    
    /**
     * Distinguishes different FapiClient roles resolved from freer.home.
     * BASE is the default FAPI client; others are lazy-created from mainFid's home map.
     */
    public enum ConnectionRole {
        BASE(Constants.BASE_NO1_NRC7), DISK(Constants.DISK_NO1_NRC7), MAP(Constants.MAP_NO1_NRC7), DOCK(Constants.DOCK_NO1_NRC7), ROAD(Constants.ROAD_NO1_NRC7);
        private final String displayName;
        ConnectionRole(String displayName){
            this.displayName = displayName;
        }

        public final String displayName(){
            return displayName;
        }
    }

    private static ApiCenter instance;
    private Setting currentSetting;
    private Configure currentConfigure;
    private Map<Service.ServiceType, ClientGroup> clientGroupMap;
    private final Map<ConnectionRole, FapiClient> homeClientMap = new ConcurrentHashMap<>();
    private Context appContext;

    private ApiCenter() {
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
        
        if (context == null) {
            TimberLogger.e(TAG, "Context is null, cannot initialize");
            return;
        }
        
        this.appContext = context.getApplicationContext();
        
        // 加载当前配置和设置
        if (!loadCurrentState()) {
            TimberLogger.e(TAG, "Failed to load current state, cannot initialize");
            return;
        }
        
        // 初始化客户端组映射
        if (clientGroupMap == null) {
            clientGroupMap = new HashMap<>();
        }
        
        // Always bootstrap fresh for FUDP transport (HELLO+PING ~500ms).
        // Saved ApiAccount/ApiProvider metadata is loaded from Configure but the
        // connection is always re-established to avoid stale address issues
        // (e.g. saved 127.0.0.1 URL in emulator causing self-loop).
        boolean hasSavedAccounts = currentSetting != null
                && currentSetting.getClientGroupMap() != null
                && !currentSetting.getClientGroupMap().isEmpty();
        if (hasSavedAccounts) {
            TimberLogger.d(TAG, "Using initialization strategy: BOOTSTRAP_WITH_SAVED_ACCOUNTS");
            connectAll(context);
        } else {
            TimberLogger.d(TAG, "Using initialization strategy: CREATE_NEW");
            createAll(context);
        }
        
        TimberLogger.d(TAG, "API service initialization completed");

        currentSetting.setClientGroupMap(clientGroupMap);
        SettingManager.getInstance().saveSettings(context, currentSetting);
        TimberLogger.d(TAG, "Successfully initialized API services with %d client groups", clientGroupMap.size());
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
            
            if (createAndTestClient(context, serviceType, apiUrl, clientGroup)) {
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
            
            if (createAndTestClient(context, serviceType, apiUrl, clientGroup)) {
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
    /**
     * Bootstrap fresh for each saved service type, loading saved ApiAccount metadata.
     * Always uses HELLO+PING (~500ms) instead of reusing saved connection addresses,
     * which avoids stale address issues (e.g. saved 127.0.0.1 in emulator).
     */
    public void connectAll(Context context) {
        TimberLogger.d(TAG, "Bootstrapping fresh connections for saved API services");

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

        if (clientGroupMap == null) {
            clientGroupMap = new HashMap<>();
        }

        for (Map.Entry<Service.ServiceType, ClientGroup> entry : currentSetting.getClientGroupMap().entrySet()) {
            Service.ServiceType serviceType = entry.getKey();
            ClientGroup savedGroup = entry.getValue();
            if (savedGroup == null) savedGroup = new ClientGroup(serviceType);

            TimberLogger.d(TAG, "Connecting clients for service type: %s", serviceType);

            // Load saved account metadata (session, balance, etc.)
            List<String> accountIds = savedGroup.getAccountIds();
            ApiAccount savedApiAccount = null;
            ApiProvider savedApiProvider = null;
            if (accountIds != null && !accountIds.isEmpty()) {
                String firstAccountId = accountIds.get(0);
                savedApiAccount = currentConfigure.getApiAccountMap().get(firstAccountId);
                if (savedApiAccount != null) {
                    // Decrypt session key from saved account
                    if (savedApiAccount.getSession() != null && savedApiAccount.getSession().getKeyCipher() != null) {
                        byte[] sessionKey = decryptSessionKey(savedApiAccount.getSession().getKeyCipher(), symkey);
                        if (sessionKey != null) {
                            savedApiAccount.setSessionKey(sessionKey);
                        }
                    }
                    savedApiProvider = currentConfigure.getApiProviderMap().get(savedApiAccount.getProviderId());
                }
            }

            // Bootstrap fresh — use saved preferred URL if available, fall back to DEFAULT_BOOTSTRAP_APIS
            ClientGroup clientGroup = new ClientGroup(serviceType);
            String savedUrl = (savedApiProvider != null) ? savedApiProvider.getApiUrl() : null;
            Object client = createClientForServiceType(context, serviceType, savedUrl);
            if (client instanceof FapiClient fapiClient) {
                // Attach saved account metadata to the fresh client
                if (savedApiAccount != null) {
                    fapiClient.setApiAccount(savedApiAccount);
                }
                String mainFid = currentSetting.getMainFid();
                if (mainFid != null) {
                    fapiClient.setAutoRechargeInfo(mainFid, currentSetting::decryptPrikey);
                    fapiClient.setUtxoProvider(CashManager.UTXO_PROVIDER);
                }
                if (savedApiProvider != null) {
                    fapiClient.cacheServiceForRecharge(savedApiProvider);
                }

                String accountId = savedApiAccount != null ? savedApiAccount.getId()
                        : (fapiClient.getApiAccount() != null ? fapiClient.getApiAccount().getId() : "default");
                clientGroup.addClient(accountId, fapiClient);
                if (savedApiAccount != null) {
                    clientGroup.addApiAccount(savedApiAccount);
                } else if (fapiClient.getApiAccount() != null) {
                    clientGroup.addApiAccount(fapiClient.getApiAccount());
                }

                clientGroupMap.put(serviceType, clientGroup);
                TimberLogger.d(TAG, "Successfully bootstrapped client for service type: %s", serviceType);
            } else {
                TimberLogger.w(TAG, "Failed to bootstrap client for service type: %s", serviceType);
            }
        }

        TimberLogger.d(TAG, "Completed bootstrapping all saved API services");
    }

    /**
     * 创建并连接client — always uses fresh bootstrap
     */
    private boolean createAndConnectClient(Context context, Service.ServiceType serviceType, ApiAccount apiAccount, ApiProvider apiProvider,
                                         byte[] symkey, String accountId, ClientGroup clientGroup) {
        try {
            TimberLogger.d(TAG, "Attempting to create client for account: %s, provider: %s", accountId, apiProvider.getId());

            // Always bootstrap fresh for reliable FUDP connection
            Object client = createClientForServiceType(context, serviceType, null);
            if (client == null) {
                TimberLogger.w(TAG, "Bootstrap failed for account: %s", accountId);
                return false;
            }

            if (client instanceof FapiClient fapiClient) {
                // Attach saved account metadata to the fresh client
                fapiClient.setApiAccount(apiAccount);
                String mainFid = currentSetting.getMainFid();
                if (mainFid != null) {
                    fapiClient.setAutoRechargeInfo(mainFid, currentSetting::decryptPrikey);
                    fapiClient.setUtxoProvider(CashManager.UTXO_PROVIDER);
                }
                fapiClient.cacheServiceForRecharge(apiProvider);
            }

            clientGroup.addClient(apiAccount.getId(), client);
            clientGroup.addApiAccount(apiAccount);
            TimberLogger.d(TAG, "Successfully created client for account: %s", accountId);
            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating client for account %s: %s", accountId, e.getMessage());
            return false;
        }
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
     * Get a FapiClient for a specific ConnectionRole.
     * <p>
     * For BASE: returns the default FapiClient from clientGroupMap.
     * For DISK/MAP/DOCK/ROAD: returns the home-appointed client (lazy-created from mainFid's freer.home).
     * If the home client cannot be resolved or connected, silently falls back to the default BASE client.
     * Only returns null when the default BASE client itself is unavailable.
     *
     * @param role the connection role
     * @return the FapiClient for that role, or null if BASE is also unavailable
     */
    public FapiClient getClient(ConnectionRole role) {
        if (role == null) return null;

        if (role == ConnectionRole.BASE) {
            return getDefaultFapiClient();
        }

        FapiClient cached = homeClientMap.get(role);
        if (cached != null) return cached;

        return getOrCreateHomeClient(role);
    }

    /**
     * Return the cached client for the given role, or null if not yet created.
     * Unlike {@link #getClient(ConnectionRole)}, this never triggers blocking
     * network calls (no home resolution, no bootstrap), so it is safe to call
     * from hot paths that must not block.
     */
    public FapiClient getCachedClient(ConnectionRole role) {
        if (role == null) return null;
        if (role == ConnectionRole.BASE) {
            return getDefaultFapiClient();
        }
        return homeClientMap.get(role);
    }

    /**
     * Lazy-create a home-appointed FapiClient for the given role.
     * Falls back to the default FapiClient if the home service cannot be resolved or connected.
     * The result (including fallback) is cached in homeClientMap.
     */
    private synchronized FapiClient getOrCreateHomeClient(ConnectionRole role) {
        FapiClient cached = homeClientMap.get(role);
        if (cached != null) return cached;

        FapiClient defaultClient = getDefaultFapiClient();

        // BASE readiness gate: until the default (BASE) FapiClient is connected, freer.home
        // cannot be refreshed and home SIDs cannot be resolved (resolution does serviceById,
        // which needs BASE). In that window — especially when the default server is also the
        // DOCK/DISK server — attempting resolution can stall the default client's own startup.
        // Defer without caching so the next call retries once BASE is ready.
        if (defaultClient == null || !defaultClient.isConfigured()) {
            TimberLogger.d(TAG, "BASE not ready; deferring home %s client resolution", role.displayName());
            return (role == ConnectionRole.DOCK) ? null : defaultClient;
        }

        Map<String, String> home = getMainFidHome(defaultClient);
        if (home == null || home.isEmpty()) {
            if (role == ConnectionRole.DOCK) {
                // DOCK: no home configured means no dock — return null so callers know
                TimberLogger.d(TAG, "No home configured, DOCK unavailable");
                return null;
            }
            if (defaultClient != null) {
                homeClientMap.put(role, defaultClient);
            }
            return defaultClient;
        }

        String theKey = role.displayName();
        com.fc.fc_ajdk.fapi.client.HomeServiceResolver resolver =
                defaultClient != null ? defaultClient.getHomeServiceResolver() : new com.fc.fc_ajdk.fapi.client.HomeServiceResolver();

        String url;
        if (role == ConnectionRole.DOCK) {
            url = resolver.resolveDockFromHome(home, defaultClient);
        } else {
            url = resolver.resolveFromHome(home, theKey, defaultClient);
        }

        if (url == null || url.isEmpty()) {
            if (role == ConnectionRole.DOCK) {
                TimberLogger.d(TAG, "No home.DOCK configured, DOCK unavailable");
                return null;
            }
            // For DISK and others: fall back to default FapiClient
            TimberLogger.d(TAG, "No home.%s configured, using default FapiClient", theKey);
            if (defaultClient != null) {
                homeClientMap.put(role, defaultClient);
            }
            return defaultClient;
        }

        if (defaultClient != null && url.equals(defaultClient.getServerUrl())) {
            homeClientMap.put(role, defaultClient);
            return defaultClient;
        }

        try {
            FudpNode fudpNode = currentSetting != null ? currentSetting.getFudpNode() : null;
            FapiClient homeClient = FapiClient.bootstrapFromUrl(fudpNode, url,
                    currentSetting != null ? currentSetting.getSettingMap() : null);
            if (homeClient != null && homeClient.isConfigured()) {
                if (currentSetting != null) {
                    String mainFid = currentSetting.getMainFid();
                    if (mainFid != null) {
                        homeClient.setAutoRechargeInfo(mainFid, currentSetting::decryptPrikey);
                        homeClient.setUtxoProvider(CashManager.UTXO_PROVIDER);
                    }
                }
                homeClientMap.put(role, homeClient);
                persistHomeClient(role, homeClient);
                TimberLogger.d(TAG, "Created home %s client at %s", theKey, url);
                return homeClient;
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to bootstrap home %s client at %s: %s", theKey, url, e.getMessage());
        }

        if (role == ConnectionRole.DOCK) {
            // DOCK: bootstrap failed — return null, don't cache failure.
            // Next call will retry the bootstrap.
            TimberLogger.w(TAG, "Home DOCK bootstrap failed for %s, will retry later", url);
            return null;
        }

        TimberLogger.d(TAG, "Home %s bootstrap failed, falling back to default FapiClient", theKey);
        if (defaultClient != null) {
            homeClientMap.put(role, defaultClient);
        }
        return defaultClient;
    }

    /**
     * Get the mainFid's freer.home map. Tries FidManager cache first, then fetches via BASE client.
     */
    private Map<String, String> getMainFidHome(FapiClient defaultClient) {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) return null;
        
        String mainFid = fidManager.getMainFid();
        if (mainFid == null && currentSetting != null) {
            mainFid = currentSetting.getMainFid();
        }
        if (mainFid == null) return null;
        
        KeyInfo mainKeyInfo = fidManager.getMainKeyInfo();
        if (mainKeyInfo != null && mainKeyInfo.getHome() != null && !mainKeyInfo.getHome().isEmpty()) {
            return mainKeyInfo.getHome();
        }
        
        if (defaultClient != null) {
            try {
                com.fc.fc_ajdk.data.fchData.Freer freer = defaultClient.getFreer(mainFid);
                if (freer != null && freer.getHome() != null) {
                    return freer.getHome();
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to fetch mainFid home: %s", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Check if the default FapiClient's service includes a DISK component.
     * Fetches service info by SID and checks the components list.
     */
    private boolean defaultClientHasDisk(FapiClient client) {
        try {
            String sid = null;
            if (client.getApiAccount() != null) {
                sid = client.getApiAccount().getProviderId();
            }
            if (sid == null) sid = client.getServiceSid();
            if (sid == null) return false;

            Service service = client.serviceById(sid);
            if (service != null && service.getComponents() != null) {
                for (String comp : service.getComponents()) {
                    if (Constants.DISK_NO1_NRC7.equalsIgnoreCase(comp)) return true;
                }
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to check default client DISK capability: %s", e.getMessage());
        }
        return false;
    }

    /**
     * Check if DISK service is available and functional.
     * Returns true if either home.DISK is configured, or the default FapiClient's service has DISK.
     */
    public boolean isDiskAvailable() {
        FapiClient diskClient = getClient(ConnectionRole.DISK);
        return diskClient != null;
    }

    /**
     * @return true if the default BASE FapiClient's service advertises a "disk" component.
     */
    public boolean baseHasDiskComponent() {
        FapiClient client = getDefaultFapiClient();
        return client != null && defaultClientHasDisk(client);
    }

    /**
     * @return the service SID of the default BASE FapiClient, or null if unavailable.
     */
    public String getBaseServiceSid() {
        FapiClient client = getDefaultFapiClient();
        if (client == null) return null;
        if (client.getApiAccount() != null && client.getApiAccount().getProviderId() != null) {
            return client.getApiAccount().getProviderId();
        }
        return client.getServiceSid();
    }

    /**
     * Explicitly set a DISK FapiClient (e.g., from SetDiskActivity).
     * Caches the client and persists the ApiAccount.
     */
    public void setDiskClient(FapiClient client) {
        if (client == null) return;
        homeClientMap.put(ConnectionRole.DISK, client);
        if (currentSetting != null) {
            String mainFid = currentSetting.getMainFid();
            if (mainFid != null) {
                client.setAutoRechargeInfo(mainFid, currentSetting::decryptPrikey);
                client.setUtxoProvider(CashManager.UTXO_PROVIDER);
            }
        }
        persistHomeClient(ConnectionRole.DISK, client);
        TimberLogger.d(TAG, "DISK client set explicitly: %s", client.getServerUrl());
    }

    /**
     * Get the default BASE FapiClient from clientGroupMap.
     */
    private FapiClient getDefaultFapiClient() {
        Object client = getClient(Service.ServiceType.FAPI_No1_NrC7);
        return (client instanceof FapiClient) ? (FapiClient) client : null;
    }

    /**
     * Persist ApiAccount for a home-appointed client with role-prefixed key.
     */
    private void persistHomeClient(ConnectionRole role, FapiClient client) {
        if (currentConfigure == null || currentSetting == null || client == null) return;

        ApiAccount apiAccount = client.getApiAccount();
        if (apiAccount == null) return;

        String mainFid = currentSetting.getMainFid();
        if (mainFid == null) return;

        String roleKey = mainFid + "_" + role.displayName;

        Map<String, ApiAccount> accountMap = currentConfigure.getApiAccountMap();
        if (accountMap == null) {
            accountMap = new HashMap<>();
            currentConfigure.setApiAccountMap(accountMap);
        }
        accountMap.put(roleKey, apiAccount);

        // Persist the entire Configure to the correct SharedPreferences ("fc_config_prefs")
        // Must use storeConfigure since the key is roleKey, not apiAccount.getId()
        ConfigureManager.getInstance().storeConfigure(appContext, currentConfigure);
        TimberLogger.d(TAG, "Persisted home %s client (key=%s)", role.displayName, roleKey);
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
    private boolean createAndTestClient(Context context, Service.ServiceType serviceType, String apiUrl, ClientGroup clientGroup) {
        try {
            // 创建客户端
            Object client = createClientForServiceType(context, serviceType, apiUrl);
            if (client == null) {
                TimberLogger.w(TAG, "Failed to create client for URL: %s", apiUrl);
                return false;
            }
            
            // TODO 目前只支持FapiClient
            if (client instanceof FapiClient fapiClient) {
                return AddFapiClientToGroup(fapiClient, clientGroup);
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
    private boolean AddFapiClientToGroup(FapiClient fapiClient, ClientGroup clientGroup) {
        try {
            // 获取API账户信息
            ApiAccount apiAccount = fapiClient.getApiAccount();
            if (apiAccount == null) {
                TimberLogger.w(TAG, "FapiClient has null ApiAccount");
                return false;
            }

            // Use isConfigured() instead of isConnected() - FUDP connectivity was
            // verified during bootstrap. isConnected() sends a base.health FAPI request
            // which fails when credit is exceeded, blocking client creation entirely.
            if (!fapiClient.isConfigured()) {
                TimberLogger.w(TAG, "FapiClient for account %s is not configured", apiAccount.getId());
                return false;
            }
            
            // 添加到客户端组
            clientGroup.addClient(apiAccount.getId(), fapiClient);
            clientGroup.addApiAccount(apiAccount);
            
            TimberLogger.d(TAG, "Successfully added FapiClient for account: %s", apiAccount.getId());
            return true;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error validating and adding FapiClient: %s", e.getMessage());
            return false;
        }
    }

    /**
     * 根据服务类型获取默认API URL列表
     * 注：APIP 和 FAPI 现在统一使用 FapiClient
     */
    private String[] getDefaultAPIsForServiceType(Service.ServiceType serviceType) {
        return switch (serviceType) {
            case FAPI_No1_NrC7 -> {

                if (currentConfigure != null) {
                    String[] endpoints = currentConfigure.getDefaultFapiEndpoints();
                    if (endpoints != null && endpoints.length > 0) {
                        yield endpoints;
                    }
                }
                yield FapiClient.DEFAULT_BOOTSTRAP_APIS;
            }
            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", serviceType);
                yield new String[0];
            }
        };
    }

    /**
     * 根据服务类型和URL创建client
     * 注：APIP 和 FAPI 现在统一使用 FapiClient
     */
    private Object createClientForServiceType(Context context, Service.ServiceType serviceType, String url) {
        return switch (serviceType) {
            case FAPI_No1_NrC7 -> createFapiClientForUrl(context, serviceType, url);

            default -> {
                TimberLogger.w(TAG, "Unsupported service type: %s", serviceType);
                yield null;
            }
        };
    }
    
    /**
     * 创建FAPI客户端并持久化ApiProvider和ApiAccount（首次启动用）
     * 使用bootstrapWithResult获取完整的服务发现信息，创建并保存ApiProvider和ApiAccount
     */
    /**
     * Pay a service from the main FID once, as auto-recharge would, without
     * adopting it as one of the app's providers: for a call to a callee whose
     * CALL service holds too little of my balance (VOICE_SPEC §7.5). The amount
     * is the service's usual purchase, capped by the recharge limit.
     * Blocking; the user has confirmed.
     *
     * @return the result, or null if the service could not be reached
     */
    public com.fc.fc_ajdk.fapi.client.AutoRechargeManager.RechargeResult topUpService(Context context, String url) {
        if (currentSetting == null || currentConfigure == null) return null;
        if (currentConfigure.getSymkey() != null) currentSetting.setSymkey(currentConfigure.getSymkey());
        FudpNode fudpNode = initFudpNode(context);
        if (fudpNode == null) return null;
        FapiClient.BootstrapResult r = FapiClient.bootstrapFromUrlWithResult(
                fudpNode, Service.ServiceType.FAPI_No1_NrC7, url, currentSetting.getSettingMap());
        if (r == null || r.getClient() == null || r.getService() == null) return null;
        FapiClient client = r.getClient();
        Service service = r.getService();
        Service full = client.serviceById(service.getId());
        if (full != null) service = full;
        String mainFid = currentSetting.getMainFid();
        ApiAccount account = new ApiAccount();
        account.setId(ApiAccount.makeApiAccountId(service.getId(), mainFid));
        account.setProviderId(service.getId());
        account.setUserId(mainFid);
        account.setUserPubkey(currentSetting.getPubkey());
        account.setApiUrl(url);
        account.setService(service);
        client.setApiAccount(account);
        client.setAutoRechargeInfo(mainFid, currentSetting::decryptPrikey);
        client.setUtxoProvider(CashManager.UTXO_PROVIDER);
        client.cacheServiceForRecharge(service);
        return client.manualRecharge();
    }

    private FapiClient createFapiClientForUrl(Context context, Service.ServiceType serviceType, String url) {
        try {
            if (currentSetting == null) {
                TimberLogger.e(TAG, "Current setting is null, cannot create FapiClient");
                return null;
            }
            
            if (currentConfigure == null) {
                TimberLogger.e(TAG, "Current configure is null, cannot create FapiClient");
                return null;
            }
            
            // Ensure symkey is set in setting for decrypting prikey
            if (currentConfigure.getSymkey() != null) {
                currentSetting.setSymkey(currentConfigure.getSymkey());
            }
            
            // Initialize FudpNode if needed
            FudpNode fudpNode = initFudpNode(context);
            if (fudpNode == null) {
                return null;
            }
            
            // Bootstrap: try saved preferred URL first, fall back to default APIs
            FapiClient.BootstrapResult bootstrapResult = null;
            if (url != null && !url.isEmpty()) {
                TimberLogger.d(TAG, "Attempting bootstrap from saved preferred URL: %s", url);
                bootstrapResult = FapiClient.bootstrapFromUrlWithResult(
                        fudpNode, serviceType, url, currentSetting.getSettingMap());
            }
            if (bootstrapResult == null) {
                TimberLogger.d(TAG, "Falling back to default bootstrap APIs");
                bootstrapResult = FapiClient.bootstrapWithResult(
                        fudpNode, serviceType, currentSetting.getSettingMap());
            }
            
            if (bootstrapResult == null) {
                TimberLogger.w(TAG, "FapiClient bootstrap failed for type: %s", serviceType);
                return null;
            }
            
            FapiClient client = bootstrapResult.getClient();
            if(client==null)return null;
            Service service = bootstrapResult.getService();
            String endpointUrl = bootstrapResult.getEndpointUrl();
            
            // Try to get full service details; fall back to bootstrap info on failure
            Service fullService = client.serviceById(service.getId());
            if (fullService != null) {
                service = fullService;
            } else {
                TimberLogger.w(TAG, "serviceById failed (code=%s), using bootstrap service info",
                        client.getLastResponse() != null ? client.getLastResponse().getCode() : "null");
            }

            ApiProvider apiProvider = ApiProvider.fromService(service, serviceType);
            if (apiProvider == null) {
                TimberLogger.e(TAG, "Failed to create ApiProvider from service");
                return null;
            }
            // Always use the actual bootstrap endpoint, not the on-chain self-reported URL.
            // The on-chain URL may be 127.0.0.1 which is wrong for emulator/remote clients.
            apiProvider.setApiUrl(endpointUrl);
            
            // Get user info for ApiAccount
            String mainFid = currentSetting.getMainFid();
            String pubkey = currentSetting.getPubkey();
            if (mainFid == null) {
                TimberLogger.e(TAG, "Main FID is null, cannot create ApiAccount");
                return null;
            }
            
            // Create ApiAccount
            String providerId = service.getId();  // serviceSid
            String accountId = ApiAccount.makeApiAccountId(providerId, mainFid);
            
            ApiAccount apiAccount = new ApiAccount();
            apiAccount.setId(accountId);
            apiAccount.setProviderId(providerId);
            apiAccount.setUserId(mainFid);
            apiAccount.setUserPubkey(pubkey);
            apiAccount.setApiUrl(apiProvider.getApiUrl());
            apiAccount.setService(service);
            
            // Set ApiAccount on client
            client.setApiAccount(apiAccount);
            
            // Set auto-recharge info
            client.setAutoRechargeInfo(mainFid, currentSetting::decryptPrikey);
            client.setUtxoProvider(CashManager.UTXO_PROVIDER);
            
            // Pre-cache the bootstrap service info in the auto-recharge manager so it
            // can compute payment amounts even when FAPI requests are blocked by credit limit
            client.cacheServiceForRecharge(service);
            
            // If the serviceById request failed with a balance-related error, the server
            // included the current balance in the error response. Trigger auto-recharge now.
            FapiResponse lastResp = client.getLastResponse();
            if (lastResp != null && lastResp.getCode() != null
                    && lastResp.getCode() == FapiCode.PAYMENT_REQUIRED) {
                TimberLogger.w(TAG, "Credit exceeded detected, triggering auto-recharge (balance=%s)",
                        lastResp.getBalance());
                client.triggerAutoRecharge();
            }
            
            // Persist ApiProvider and ApiAccount to Configure
            persistApiProviderAndAccount(apiProvider, apiAccount);
            
            TimberLogger.d(TAG, "Created FapiClient with persistence: accountId=%s, providerId=%s, endpoint=%s", 
                    accountId, providerId, endpointUrl);
            
            return client;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating FapiClient: %s", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Initialize FudpNode if needed
     */
    private FudpNode initFudpNode(Context context) {
        FudpNode fudpNode = currentSetting.getFudpNode();
        if (fudpNode == null || !fudpNode.isRunning()) {
            String dataDir = context.getFilesDir().getAbsolutePath() + "/fudp";
            fudpNode = currentSetting.initFudpNode(dataDir);
            if (fudpNode == null) {
                TimberLogger.e(TAG, "Failed to initialize FudpNode");
                return null;
            }
        }
        return fudpNode;
    }
    
    /**
     * Persist ApiProvider and ApiAccount to Configure
     */
    private void persistApiProviderAndAccount(ApiProvider apiProvider, ApiAccount apiAccount) {
        if (currentConfigure == null) {
            TimberLogger.e(TAG, "Current configure is null, cannot persist");
            return;
        }

        // Get or create maps
        Map<String, ApiProvider> apiProviderMap = currentConfigure.getApiProviderMap();
        if (apiProviderMap == null) {
            apiProviderMap = new HashMap<>();
            currentConfigure.setApiProviderMap(apiProviderMap);
        }

        Map<String, ApiAccount> apiAccountMap = currentConfigure.getApiAccountMap();
        if (apiAccountMap == null) {
            apiAccountMap = new HashMap<>();
            currentConfigure.setApiAccountMap(apiAccountMap);
        }

        // Save ApiProvider using service ID as key
        apiProviderMap.put(apiProvider.getId(), apiProvider);

        // Save ApiAccount using account ID as key
        apiAccountMap.put(apiAccount.getId(), apiAccount);

        // Persist to the correct SharedPreferences via ConfigureManager
        ConfigureManager.getInstance().storeConfigure(appContext, currentConfigure);

        TimberLogger.d(TAG, "Persisted ApiProvider (id=%s) and ApiAccount (id=%s)",
                apiProvider.getId(), apiAccount.getId());
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
            Encryptor encryptor = new Encryptor(FC_AesGcm256_No1_NrC7);
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

    public void setCurrentSetting(Setting newSetting) {
        // Check if we're switching to a different main FID
        if (this.currentSetting != null && newSetting != null) {
            String oldFid = this.currentSetting.getFid();
            String newFid = newSetting.getFid();

            if (oldFid != null && !oldFid.equals(newFid)) {
                TimberLogger.d(TAG, "Switching main FID from %s to %s - closing old clients", oldFid, newFid);
                closeHomeClients();
                closeAllClients();

                // Note: closeAllClients() already sets clientGroupMap to null,
                // but we'll set currentSetting and currentConfigure to null to be explicit
                this.currentSetting = null;
                this.currentConfigure = null;
            }
        }

        this.currentSetting = newSetting;
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

    /**
     * Close all clients and clean up resources
     * This is called when password changes or when cleaning up the session
     */
    /**
     * Close all home-appointed clients and clear the map.
     */
    private void closeHomeClients() {
        for (Map.Entry<ConnectionRole, FapiClient> entry : homeClientMap.entrySet()) {
            FapiClient client = entry.getValue();
            FapiClient defaultClient = getDefaultFapiClient();
            if (client != null && client != defaultClient) {
                client.close();
            }
        }
        homeClientMap.clear();
        TimberLogger.d(TAG, "Closed all home-appointed clients");
    }

    /**
     * Same as {@link #closeAllClients()} but returns without waiting for the FUDP
     * node's shutdown (scheduler drain + receive-thread join, up to several seconds).
     * The node is detached synchronously, so nothing can adopt or reuse it afterwards.
     * Use this from the UI thread.
     */
    public void closeAllClientsAsync() {
        closeAllClients(true);
    }

    public void closeAllClients() {
        closeAllClients(false);
    }

    private void closeAllClients(boolean asyncNodeShutdown) {
        TimberLogger.d(TAG, "Closing all API clients");

        closeHomeClients();

        if (clientGroupMap != null) {
            // Close all client groups
            for (Map.Entry<Service.ServiceType, ClientGroup> entry : clientGroupMap.entrySet()) {
                Service.ServiceType serviceType = entry.getKey();
                ClientGroup clientGroup = entry.getValue();

                if (clientGroup != null) {
                    // Get all clients and close them if they have a close method
                    Map<String, Object> clients = clientGroup.getClientMap();
                    if (clients != null) {
                        for (Map.Entry<String, Object> clientEntry : clients.entrySet()) {
                            Object client = clientEntry.getValue();
                            if(client instanceof FapiClient fapiClient){
                                fapiClient.close();
                            }
                            // FapiClient doesn't need explicit shutdown - FudpNode handles cleanup
                            TimberLogger.d(TAG, "Closing client: %s for service type: %s",
                                clientEntry.getKey(), serviceType);
                        }
                    }
                }
            }

            // Clear the client group map
            clientGroupMap.clear();
            clientGroupMap = null;
            TimberLogger.d(TAG, "All client groups cleared");
        }
        
        // Stop FudpNode if running
        if (currentSetting != null) {
            if (asyncNodeShutdown) {
                currentSetting.stopFudpNodeAsync();
            } else {
                currentSetting.stopFudpNode();
            }
        }

        // Clear current setting and configure references
        currentSetting = null;
        currentConfigure = null;

        TimberLogger.d(TAG, "ApiCenter cleanup completed");
    }

    /**
     * Full reset of ApiCenter - for password change scenarios
     * This is more thorough than just closing clients
     */
    public void fullReset() {
        TimberLogger.d(TAG, "Starting full reset of ApiCenter");

        closeAllClients();

        // Reset the singleton instance (nuclear option)
        // This ensures no stale state remains
        instance = null;

        TimberLogger.d(TAG, "ApiCenter full reset completed");
    }

    /**
     * Replace or add a client in the client group
     *
     * @param context Application context
     * @param serviceType The service type
     * @param selectedProvider The selected API provider
     * @param oldProviderId The account ID to replace (null to add new)
     * @return true if client was added or replaced successfully, false otherwise
     */
    public boolean replaceClient(Context context, Service.ServiceType serviceType, ApiProvider selectedProvider, String oldProviderId) {

        String mainFid = FidManager.getInstance().getMainFid();

        if (context == null || serviceType == null || selectedProvider == null) {
            TimberLogger.e(TAG, "Invalid parameters for replaceClient");
            showToastOnUiThread(context, "Invalid parameters for replaceClient", "ERROR");
            return false;
        }

        // 1. Load or create ApiAccount
        ApiAccount apiAccount = loadOrCreateApiAccount(selectedProvider);
        if (apiAccount == null) {
            TimberLogger.e(TAG, "Failed to create ApiAccount");
            showToastOnUiThread(context, "Failed to create ApiAccount", "ERROR");
            return false;
        }

        // 2. Create client via fresh bootstrap
        Object client = createClientForServiceType(context, serviceType, selectedProvider.getApiUrl());
        if (client == null) {
            TimberLogger.e(TAG, "Failed to create client for service type: %s", serviceType);
            showToastOnUiThread(context, "Failed to create client for service type: " + serviceType, "ERROR");
            return false;
        }
        if (client instanceof FapiClient fapiClient) {
            fapiClient.setApiAccount(apiAccount);
            fapiClient.cacheServiceForRecharge(selectedProvider);
            String fid = currentSetting != null ? currentSetting.getMainFid() : null;
            if (fid != null) {
                fapiClient.setAutoRechargeInfo(fid, currentSetting::decryptPrikey);
                fapiClient.setUtxoProvider(CashManager.UTXO_PROVIDER);
            }
        }

        // Check if client is connected
        boolean isConnected = false;
        if (client instanceof FapiClient fapiClient) {
            isConnected = fapiClient.isConnected();
        }

        if (!isConnected) {
            TimberLogger.w(TAG, "Client is not connected for service type: %s", serviceType);
            showToastOnUiThread(context, context.getString(R.string.client_is_not_connected_for_service_type_s,serviceType), "WARNING");
            return false;
        }

        // Set the client on the apiAccount so it can be added to the client group
        apiAccount.setClient(client);

        // Get or create client group
        if (clientGroupMap == null) {
            clientGroupMap = new HashMap<>();
        }

        ClientGroup clientGroup = clientGroupMap.get(serviceType);
        String oldAccountId = null;

        if (oldProviderId != null) {
            oldAccountId = ApiAccount.makeApiAccountId(oldProviderId, mainFid);
        }

        if (clientGroup == null) {
            clientGroup = new ClientGroup(serviceType);
        }
        //Remove old account and client
        if (oldAccountId != null) {
            clientGroup.removeClient(oldAccountId);
        }

        //Add new account
        clientGroup.addClient(apiAccount);
        clientGroupMap.put(serviceType, clientGroup);

        Integer targetCount = FreerApplication.getServiceNumberMap().get(serviceType);

        String message = context.getString(R.string.added_new_client_for_s_total_d_d,
                serviceType, clientGroup.getClientCount(), targetCount);
        TimberLogger.d(TAG, message);
        showToastOnUiThread(context, message, "INFO");

        // Persist ApiProvider and ApiAccount to Configure so they survive app restart
        // Must use ConfigureManager which saves to the correct SharedPreferences ("fc_config_prefs")
        ConfigureManager.saveApiProviderAndAccount(context, selectedProvider, apiAccount);

        currentSetting.setClientGroupMap(clientGroupMap);
        SettingManager.getInstance().saveSettings(context,currentSetting);

        return true;
    }

    /**
     * Helper method to show toast on UI thread
     */
    private void showToastOnUiThread(Context context, String message, String level) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() ->
                ToastUtils.makeText(context, message, android.widget.Toast.LENGTH_SHORT, level)
            );
        } else {
            // For non-Activity contexts, try to show toast directly
            ToastUtils.makeText(context, message, android.widget.Toast.LENGTH_SHORT, level);
        }
    }

    /**
     * Load or create ApiAccount for the given ApiProvider
     */
    private ApiAccount loadOrCreateApiAccount(ApiProvider apiProvider) {
        if (currentConfigure == null) {
            TimberLogger.e(TAG, "Current configure is null");
            return null;
        }

        KeyInfo mainFidKeyInfo = FidManager.getInstance().getMainKeyInfo();
        String accountId = ApiAccount.makeApiAccountId(apiProvider.getId(), mainFidKeyInfo.getId());
        // Check if account already exists in configure
        if (currentConfigure.getApiAccountMap() != null) {
            ApiAccount existingAccount = currentConfigure.getApiAccountMap().get(accountId);
            if (existingAccount != null) {
                TimberLogger.d(TAG, "Found existing ApiAccount: %s", accountId);
                return existingAccount;
            }
        }

        // Create new ApiAccount
        ApiAccount newAccount = new ApiAccount();
        newAccount.setId(accountId);
        newAccount.setProviderId(apiProvider.getId());
        newAccount.setUserPubkey(mainFidKeyInfo.getPubkey());
        newAccount.setUserId(mainFidKeyInfo.getId());
        newAccount.setUserName(mainFidKeyInfo.getCid()==null?mainFidKeyInfo.getId():mainFidKeyInfo.getCid());
        newAccount.setApiUrl(apiProvider.getApiUrl());

        TimberLogger.d(TAG, "Created new ApiAccount: %s", accountId);
        return newAccount;
    }

}