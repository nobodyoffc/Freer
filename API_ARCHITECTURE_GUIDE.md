# API架构使用指南

## 概述

本项目实现了一个灵活的API数据获取架构，支持从多个不同类型的API来源获取数据。架构包含以下核心组件：

- **ApiProvider**: 描述API服务商信息
- **ApiAccount**: 描述用户在某个服务商的账户信息
- **ClientGroup**: 管理同一类型数据的多个来源
- **ApipClient**: APIP服务的客户端实现
- **ApiHelper**: 简化API使用的工具类

## 架构组件

### 1. ApiProvider
描述API服务商的基本信息，包括：
- 服务ID、名称、类型
- API URL、文档URL
- 协议、代币等信息

### 2. ApiAccount
描述用户在特定服务商的账户信息，包括：
- 账户ID、提供商ID
- 用户ID、用户名
- 加密的密码和私钥
- 会话信息、余额等

### 3. ClientGroup
管理同一类型数据的多个来源，支持多种策略：
- `USE_FIRST`: 使用第一个可用客户端
- `USE_ANY_VALID`: 使用任意有效客户端
- `USE_ALL`: 使用所有客户端
- `USE_ONE_RANDOM`: 随机使用一个客户端
- `USE_ONE_ROUND_ROBIN`: 轮询使用客户端

**客户端连接机制**:
- `connectAllClients()`: 为本组中的账户创建客户端
- 对于APIP类型：发送ping请求验证服务可用性
- 根据ping响应创建相应的客户端实例

### 4. ApipClient
APIP服务的客户端实现，提供：
- **静态服务发现方法**: `tryFreeApiService()` - 自动发现和连接免费API服务
- **静态客户端创建方法**: `createApipClient()` - 包含ping验证的客户端创建
- API请求执行
- 错误处理和重试机制
- 状态监控
- 客户端生命周期管理

## 使用流程

### 1. 初始化阶段

在`MainActivity`中，当用户选定登录ID并配置好Setting后，开始加载API服务：

```java
private void loadModules(Setting setting) {
    Configure configure = ConfigureManager.getInstance().getConfigure();
    byte[] symkey = configure.getSymkey();
    
    // 初始化API客户端
    Map<Service.ServiceType, ClientGroup> clientGroupMap = setting.getClientGroupMap();
    if (clientGroupMap == null) {
        clientGroupMap = new HashMap<>();
        setting.setClientGroupMap(clientGroupMap);
    }
    
    // 为每种服务类型初始化客户端组
    for (Service.ServiceType serviceType : FreerApplication.getServiceNumberMap().keySet()) {
        switch (serviceType) {
            case APIP -> {
                ClientGroup clientGroup = clientGroupMap.get(serviceType);
                if (clientGroup == null) {
                    // 初始化新的客户端组
                    initializeApipClientGroup(clientGroupMap, serviceType);
                } else {
                    // 重新连接现有客户端
                    clientGroup.connectAllClients(this, symkey);
                }
            }
        }
    }
}
```

### 2. 服务发现和初始化

当Setting中的clientGroupMap为空时，需要初始化：

```java
private void initializeApipClientGroup(Map<Service.ServiceType, ClientGroup> clientGroupMap, 
                                     Service.ServiceType serviceType) {
    int serviceNumber = FreerApplication.getServiceNumberMap().get(serviceType);
    ClientGroup clientGroup = new ClientGroup(serviceType);
    
    // 尝试连接免费API服务
    for (String url : ApipClient.freeAPIs) {
        if (serviceNumber <= 0) break;
        
        // 使用ApipClient的静态方法进行服务发现
        ApipClient apipClient = ApipClient.tryFreeApiService(this, url, setting);
        if (apipClient != null) {
            clientGroup.addClient(apipClient.getApiAccount().getId(), apipClient);
            clientGroup.addApiAccount(apipClient.getApiAccount());
            serviceNumber--;
        }
    }
    
    clientGroupMap.put(serviceType, clientGroup);
}
```

### 3. 在页面中使用API

使用`ApiHelper`简化API调用：

```java
public class MyActivity extends AppCompatActivity {
    private ApiHelper apiHelper;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        apiHelper = ApiHelper.getInstance(this);
    }
    
    private void makeApiRequest() {
        // 检查API服务是否可用
        if (!apiHelper.isApipServiceAvailable()) {
            showError("API service not available");
            return;
        }
        
        // 执行API请求
        apiHelper.executeApipRequest("ping", "", new ApipClient.RequestCallback() {
            @Override
            public void onSuccess(ApiEvent apiEvent) {
                if (apiEvent.isSuccess()) {
                    // 处理成功响应
                    handleSuccess(apiEvent);
                } else {
                    // 处理错误
                    handleError(apiEvent.getErrorMessage());
                }
            }
            
            @Override
            public void onRetry(int attempt, String errorMessage) {
                // 处理重试
                showMessage("Retrying... Attempt " + attempt);
            }
            
            @Override
            public void onError(ApiEvent apiEvent, Exception exception) {
                // 处理异常
                handleError(exception.getMessage());
            }
        });
    }
}
```

### 4. 直接使用ClientGroup

也可以直接使用ClientGroup：

```java
private void useClientGroupDirectly() {
    ClientGroup apipGroup = apiHelper.getApipClientGroup();
    if (apipGroup == null || !apipGroup.hasValidClients()) {
        showError("No available clients");
        return;
    }
    
    // 根据策略获取客户端
    Object client = apipGroup.getClient();
    if (client instanceof ApipClient) {
        ApipClient apipClient = (ApipClient) client;
        // 使用客户端执行请求
        apipClient.get("test", "v1", "ping", "", callback);
    }
}
```

## 配置管理

### 1. 服务数量配置

在`FreerApplication`中配置每种服务的数量：

```java
public static final Map<Service.ServiceType, Integer> serviceNumberMap = new HashMap<>();

static {
    serviceNumberMap.put(Service.ServiceType.APIP, 1);
    // 可以添加其他服务类型
}
```

### 2. 免费API配置

在`ApipClient`中配置免费API列表：

```java
public static final String[] freeAPIs = new String[]{
    "https://apip.cash/APIP",
    "https://freecash.info/APIP",
    "https://cid.cash/APIP",
    "https://help.cash/APIP"
};
```

## 错误处理

### 1. 网络错误处理

API请求会自动处理网络错误和重试：

```java
apipClient.get("test", "v1", "ping", "", new ApipClient.RequestCallback() {
    @Override
    public void onRetry(int attempt, String errorMessage) {
        // 自动重试，可以在这里显示重试状态
        showMessage("Retrying... Attempt " + attempt);
    }
    
    @Override
    public void onError(ApiEvent apiEvent, Exception exception) {
        // 处理最终错误
        handleError(exception.getMessage());
    }
});
```

### 2. 客户端验证

使用`isClientValid`方法验证客户端状态：

```java
if (client instanceof ApipClient) {
    ApipClient apipClient = (ApipClient) client;
    if (!apipClient.isAvailable()) {
        // 客户端不可用，尝试重新连接
        reconnectClients();
    }
}
```

## 最佳实践

### 1. 使用ApiHelper

推荐使用`ApiHelper`而不是直接操作ClientGroup，因为它提供了：
- 统一的错误处理
- 自动状态检查
- 简化的API调用接口

### 2. 检查服务可用性

在执行API请求前，始终检查服务可用性：

```java
if (!apiHelper.isApipServiceAvailable()) {
    showError("API service not available");
    return;
}
```

### 3. 处理异步回调

API请求是异步的，确保在UI线程中更新界面：

```java
@Override
public void onSuccess(ApiEvent apiEvent) {
    runOnUiThread(() -> {
        // 更新UI
        updateResult(apiEvent);
    });
}
```

### 4. 资源管理

在Activity销毁时清理资源：

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    // 清理资源
    if (apipClient != null) {
        apipClient.shutdown();
    }
}
```

## 扩展指南

### 1. 添加新的服务类型

1. 在`Service.ServiceType`枚举中添加新类型
2. 创建对应的客户端类（如`DiskClient`）
3. 在`FreerApplication.serviceNumberMap`中配置数量
4. 在`loadModules`方法中添加初始化逻辑

### 2. 自定义客户端策略

可以实现自定义的客户端选择策略：

```java
public class CustomClientGroup extends ClientGroup {
    @Override
    public Object getClient() {
        // 实现自定义的客户端选择逻辑
        return super.getClient();
    }
}
```

### 3. 添加新的API端点

在`ApipClient`中添加新的API方法：

```java
public void getCustomData(String param, RequestCallback callback) {
    get("custom", "v1", "getCustomData", param, callback);
}
```

## 故障排除

### 1. 客户端连接失败

- 检查网络连接
- 验证API URL是否正确
- 检查服务是否可用

### 2. 认证失败

- 检查用户凭据是否正确
- 验证会话是否过期
- 确认API密钥是否有效

### 3. 请求超时

- 增加超时时间设置
- 检查网络延迟
- 考虑使用重试机制

## 客户端连接机制

### ClientGroup.connectAllClients() 方法

`connectAllClients`方法专门为本组中的账户创建客户端，而不是连接所有保存在configure中的账户：

#### 1. 连接流程
```java
public void connectAllClients(Context context, byte[] symKey) {
    // 遍历本组中的账户ID
    for (String accountId : accountIds) {
        // 从Configure中获取账户信息
        ApiAccount apiAccount = configure.getApiAccountMap().get(accountId);
        
        // 根据服务类型创建相应的客户端
        Object client = createClientForAccount(context, apiAccount, configure, symKey);
        
        // 将客户端添加到组中
        apiAccount.setClient(client);
        addClient(apiAccount.getId(), client);
    }
}
```

#### 2. APIP客户端创建
对于APIP类型的服务：
```java
// 使用ApipClient的静态方法创建客户端
Object client = ApipClient.createApipClient(context, apiAccount, apiProvider, symKey);
```

或者直接使用：
```java
private Object createApipClient(Context context, ApiAccount apiAccount, ApiProvider apiProvider, byte[] symKey) {
    // 使用ApipClient的静态方法创建客户端
    return ApipClient.createApipClient(context, apiAccount, apiProvider, symKey);
}
```

#### 3. Ping请求验证
Ping请求验证逻辑已封装在`ApipClient.createApipClient()`静态方法中：
```java
// 内部实现包含：
// 1. 发送GET请求到 urlHead + "/v1/ping"
// 2. 解析响应JSON
// 3. 检查 data 字段是否为 true
// 4. 返回验证结果
```

#### 4. 错误处理
- 网络超时（5秒）
- JSON解析错误
- 服务不可用
- 客户端初始化失败

## 静态方法改进

### ApipClient.tryFreeApiService() 静态方法

最新版本中，我们将服务发现逻辑封装为`ApipClient`的静态方法，提供了以下优势：

#### 1. 更好的封装性
```java
// 使用静态方法进行服务发现
ApipClient apipClient = ApipClient.tryFreeApiService(context, url, setting);
```

#### 2. 完整的服务发现流程
- 自动构建服务发现URL
- 执行HTTP请求获取服务信息
- 解析Service对象
- 创建ApiProvider和ApiAccount
- 返回完整的ApipClient实例

#### 3. 错误处理
- 网络超时处理（10秒超时）
- JSON解析错误处理
- 服务验证失败处理

#### 4. 使用示例
```java
// 在MainActivity中使用
for (String url : ApipClient.freeAPIs) {
    if (serviceNumber <= 0) break;
    
    ApipClient apipClient = ApipClient.tryFreeApiService(this, url, setting);
    if (apipClient != null) {
        clientGroup.addClient(apipClient.getApiAccount().getId(), apipClient);
        clientGroup.addApiAccount(apipClient.getApiAccount());
        serviceNumber--;
    }
}
```

#### 5. 测试工具
```java
// 使用ApiTestHelper进行测试
ApiTestHelper.testTryFreeApiService(context);
ApiTestHelper.testAllFreeApis(context);
ApiTestHelper.testConnectAllClients(context);
```

### ApipClient.createApipClient() 静态方法

新增的静态方法，专门用于创建APIP客户端：

#### 1. 功能特性
- 自动ping验证服务可用性
- 客户端初始化和验证
- 完整的错误处理
- 详细的日志记录

#### 2. 使用方式
```java
// 直接创建APIP客户端
ApipClient apipClient = ApipClient.createApipClient(context, apiAccount, apiProvider, symKey);
if (apipClient != null) {
    // 客户端创建成功，可以使用
    apipClient.get("test", "v1", "ping", "", callback);
} else {
    // 客户端创建失败
    handleError("Failed to create ApipClient");
}
```

#### 3. 内部流程
1. 构建ping请求URL：`apiAccount.getApiUrl() + "/v1/ping"`
2. 发送ping请求并验证响应
3. 创建ApipClient实例
4. 初始化客户端
5. 返回可用的客户端或null

## 总结

这个API架构提供了：
- **灵活性**: 支持多种服务类型和策略
- **可靠性**: 内置错误处理和重试机制
- **易用性**: 通过ApiHelper简化使用
- **可扩展性**: 易于添加新的服务类型和功能
- **封装性**: 通过静态方法提供清晰的服务发现接口

通过遵循本指南，您可以有效地使用和管理API服务，确保应用程序的稳定性和性能。 