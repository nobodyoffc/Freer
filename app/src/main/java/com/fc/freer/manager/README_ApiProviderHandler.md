# ApiProviderHandler 使用指南

## 概述

`ApiProviderHandler` 是一个用于管理 `ApiProvider` 实例的工具类，提供了创建、删除、修改、读取等完整的CRUD操作功能。所有的 `ApiProvider` 实例都存储在 `Configure` 对象的 `apiProviderMap` 中，以ID作为key。

## 主要功能

### 1. 创建ApiProvider

#### 基本创建方法
```java
// 创建ApiProviderHandler实例
ApiProviderHandler handler = new ApiProviderHandler(configure);

// 创建APIP类型的ApiProvider
handler.createApiProvider(activity, Service.ServiceType.APIP, apipClient, REQUEST_CODE);
```

#### 支持的服务类型
- `APIP` - APIP服务
- `DISK` - 磁盘服务  
- `MAP` - 地图服务
- `FEIP` - FEIP服务
- `SWAP_HALL` - 交换大厅服务
- 其他类型

#### 创建流程
1. 当服务类型为支持的类型且 `apipClient` 不为空时，会尝试从链上获取服务信息
2. 其他情况下，使用 `InputObjectActivity` 创建新的ApiProvider
3. 用户填写表单后，结果通过 `handleCreateResult()` 方法处理

### 2. 删除ApiProvider

```java
// 删除指定ID的ApiProvider
boolean success = handler.deleteApiProvider("provider_id");
if (success) {
    // 删除成功
} else {
    // 删除失败
}
```

### 3. 修改ApiProvider

#### 通过ID修改
```java
// 通过ID修改ApiProvider
handler.updateApiProvider(activity, "provider_id", REQUEST_CODE);
```

#### 通过实例修改
```java
// 通过ApiProvider实例修改
ApiProvider provider = handler.getApiProvider("provider_id");
if (provider != null) {
    handler.updateApiProvider(activity, provider, REQUEST_CODE);
}
```

#### 修改流程
1. 使用 `UpdateObjectActivity` 显示当前值
2. 用户修改后，结果通过 `handleUpdateResult()` 方法处理
3. 如果ID发生变化，会自动更新Map中的key

### 4. 读取ApiProvider

#### 读取单个ApiProvider
```java
// 根据ID获取ApiProvider
ApiProvider provider = handler.getApiProvider("provider_id");
if (provider != null) {
    // 使用provider
}
```

#### 读取特定类型的ApiProvider列表
```java
// 获取APIP类型的所有ApiProvider
List<ApiProvider> apipProviders = handler.getApiProvidersByType(Service.ServiceType.APIP);
for (ApiProvider provider : apipProviders) {
    System.out.println(provider.getId() + ": " + provider.getName());
}
```

#### 读取所有ApiProvider
```java
// 获取所有ApiProvider
List<ApiProvider> allProviders = handler.getAllApiProviders();
System.out.println("Total providers: " + allProviders.size());
```

### 5. 从链上服务创建ApiProvider

#### 通过Service实例创建
```java
// 从Service实例创建ApiProvider
Service service = ...; // 从某处获取的Service实例
ApiProvider provider = handler.createFromService(service, Service.ServiceType.APIP);
```

#### 通过Service ID创建
```java
// 通过Service ID创建ApiProvider
ApiProvider provider = handler.createFromServiceId(apipClient, "service_id", Service.ServiceType.APIP);
```

## 在Activity中的完整使用示例

### 1. 初始化
```java
public class MyActivity extends AppCompatActivity {
    private ApiProviderHandler handler;
    private String currentProviderId; // 用于跟踪当前操作的provider ID
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化handler
        Configure configure = ...; // 获取Configure实例
        handler = new ApiProviderHandler(configure);
    }
}
```

### 2. 创建ApiProvider
```java
public void onCreateProviderClicked(View view) {
    // 创建APIP类型的ApiProvider
    handler.createApiProvider(this, Service.ServiceType.APIP, apipClient, REQUEST_CREATE_PROVIDER);
}

private static final int REQUEST_CREATE_PROVIDER = 1001;
```

### 3. 修改ApiProvider
```java
public void onUpdateProviderClicked(String providerId) {
    currentProviderId = providerId; // 保存当前操作的provider ID
    handler.updateApiProvider(this, providerId, REQUEST_UPDATE_PROVIDER);
}

private static final int REQUEST_UPDATE_PROVIDER = 1002;
```

### 4. 删除ApiProvider
```java
public void onDeleteProviderClicked(String providerId) {
    boolean success = handler.deleteApiProvider(providerId);
    if (success) {
        // 删除成功，刷新UI
        refreshProviderList();
    } else {
        // 删除失败，显示错误信息
        showError("Failed to delete provider");
    }
}
```

### 5. 处理Activity结果
```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    
    if (resultCode != RESULT_OK) {
        return;
    }
    
    switch (requestCode) {
        case REQUEST_CREATE_PROVIDER:
            handleCreateResult(data);
            break;
        case REQUEST_UPDATE_PROVIDER:
            handleUpdateResult(data);
            break;
    }
}

private void handleCreateResult(Intent data) {
    ApiProvider provider = handler.handleCreateResult(data, Service.ServiceType.APIP);
    if (provider != null) {
        // 创建成功，刷新UI
        refreshProviderList();
        showSuccess("Provider created successfully");
    } else {
        // 创建失败，显示错误信息
        showError("Failed to create provider");
    }
}

private void handleUpdateResult(Intent data) {
    if (currentProviderId != null) {
        ApiProvider provider = handler.handleUpdateResult(data, currentProviderId);
        if (provider != null) {
            // 更新成功，刷新UI
            refreshProviderList();
            showSuccess("Provider updated successfully");
        } else {
            // 更新失败，显示错误信息
            showError("Failed to update provider");
        }
    }
}
```

## 字段说明

ApiProvider包含以下字段：

| 字段名 | 类型 | 必填 | 说明      |
|--------|------|------|---------|
| id | String | 是 | 提供商ID   |
| name | String | 是 | 提供商名称   |
| type | ServiceType | 是 | 服务类型    |
| apiUrl | String | 是 | API URL |
| orgUrl | String | 否 | 组织网址    |
| docUrl | String | 否 | 文档网址    |
| owner | String | 否 | 所有者     |
| feipProtocol | String[] | 否 | 协议列表    |
| ticks | String[] | 否 | 代币列表    |
| dealerPubkey | String | 否 | 掌柜公钥    |

## 注意事项

1. **线程安全**: `ApiProviderHandler` 不是线程安全的，请在主线程中使用
2. **错误处理**: 所有方法都包含适当的错误处理和日志记录
3. **数据持久化**: 所有操作都会自动保存到 `Configure` 中
4. **ID唯一性**: ApiProvider的ID必须唯一，修改ID时会自动更新Map中的key
5. **类型验证**: 创建和修改时会验证ServiceType的有效性

## 依赖关系

- `Configure` - 配置管理类
- `ApiProvider` - 提供商模型类
- `InputObjectActivity` - 输入界面
- `UpdateObjectActivity` - 更新界面
- `ApipClient` - APIP客户端
- `Service` - 服务模型类

## 扩展功能

如果需要添加新的功能，可以：

1. 在 `ApiProviderHandler` 中添加新的方法
2. 在 `ApiProvider` 模型中添加新的字段
3. 更新字段映射和验证逻辑
4. 更新UI界面以支持新字段 