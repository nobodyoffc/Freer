# 网络模块架构

本模块提供了完整的网络请求管理功能，包括基础网络请求、配置管理、工具类和专门的APIP服务管理。

## 核心组件

### 1. NetworkManager
网络请求的核心管理器，负责执行HTTP请求。

**主要功能：**
- GET/POST请求执行
- 请求头管理
- 超时控制
- 异步处理
- 错误处理

**使用示例：**
```java
NetworkManager httpRequester = new NetworkManager(context);

Map<String, String> headers = new HashMap<>();
headers.put("Content-Type", "application/json");

httpRequester.get("/api/endpoint", headers, new NetworkManager.NetworkCallback() {
    @Override
    public void onSuccess(NetworkResponse response) {
        // 处理成功响应
    }
    
    @Override
    public void onError(NetworkException exception) {
        // 处理错误
    }
});
```

### 2. NetworkConfig
网络配置管理器，负责管理网络请求的配置参数。

**主要功能：**
- Base URL管理
- API Key管理
- 超时设置
- 默认请求头管理
- 配置验证

**使用示例：**
```java
NetworkConfig config = NetworkConfig.getInstance(context);
config.setBaseUrl("https://api.example.com");
config.setApiKey("your-api-key");
config.setTimeout(30000);
config.addDefaultHeader("User-Agent", "MyApp/1.0");
```

### 3. NetworkUtils
网络工具类，提供网络状态检查和URL构建功能。

**主要功能：**
- 网络状态检查
- WiFi/移动网络检测
- URL参数构建
- 请求头创建
- 网络连接测试

**使用示例：**
```java
// 检查网络状态
boolean isAvailable = NetworkUtils.isNetworkAvailable(context);
boolean isWifi = NetworkUtils.isWifiConnected(context);

// 构建URL参数
Map<String, String> params = new HashMap<>();
params.put("key", "value");
String url = NetworkUtils.buildUrlWithParams("https://api.example.com", params);

// 测试网络连接
NetworkUtils.testNetworkConnection("https://api.example.com", new NetworkUtils.NetworkCallback() {
    @Override
    public void onSuccess(String message) {
        // 连接成功
    }
    
    @Override
    public void onError(NetworkException exception) {
        // 连接失败
    }
});
```

### 4. ApipServiceManager
专门处理APIP相关请求的服务管理器，整合了网络环境检测、重试机制和备用服务器功能。

**主要功能：**
- 网络环境检测（DNS解析、延迟、可达性）
- 自动重试机制
- 备用服务器测试
- 详细的请求结果分析
- JSON响应解析

**使用示例：**
```java
ApipServiceManager apipManager = new ApipServiceManager(context);

// 执行APIP请求（包含网络环境检测和重试）
apipManager.requestApipService(new ApipServiceManager.RequestCallback() {
    @Override
    public void onSuccess(ApipServiceManager.RequestResult result) {
        // 请求成功，包含详细的请求和响应信息
        System.out.println(result.toString());
    }
    
    @Override
    public void onRetry(int attempt, String errorMessage) {
        // 重试中
        System.out.println("Retry attempt " + attempt + ": " + errorMessage);
    }
    
    @Override
    public void onError(ApipServiceManager.RequestResult result, Exception exception) {
        // 最终失败
        System.out.println("Final error: " + result.toString());
    }
});

// 测试备用服务器
apipManager.testAlternativeServers(new ApipServiceManager.ServerTestCallback() {
    @Override
    public void onServerResult(ApipServiceManager.ServerTestResult result) {
        // 单个服务器测试结果
        System.out.println(result.toString());
    }
    
    @Override
    public void onComplete() {
        // 所有服务器测试完成
        System.out.println("All servers tested");
    }
});

// 检测网络环境
apipManager.detectNetworkEnvironment(new ApipServiceManager.NetworkEnvironmentCallback() {
    @Override
    public void onSuccess(ApipServiceManager.NetworkEnvironment environment) {
        // 网络环境检测成功
        System.out.println(environment.toString());
    }
    
    @Override
    public void onError(ApipServiceManager.NetworkEnvironment environment, Exception e) {
        // 网络环境检测失败
        System.out.println("Environment detection failed: " + e.getMessage());
    }
});
```

### 5. ApiService
API服务示例，展示如何使用网络框架进行常见的API调用。

**主要功能：**
- 用户信息获取
- 用户创建
- 交易历史查询
- 交易发送
- 市场数据获取

**使用示例：**
```java
ApiService apiService = new ApiService(context);

// 获取用户信息
apiService.getUserInfo("user-id", new ApiService.ApiCallback() {
    @Override
    public void onSuccess(String response) {
        // 处理成功响应
    }
    
    @Override
    public void onError(NetworkException exception) {
        // 处理错误
    }
});
```

### 6. NetworkExample
网络使用示例，展示各种网络功能的用法。

**主要功能：**
- 简单GET请求
- 带参数GET请求
- POST请求
- 认证请求
- 网络状态检查
- 错误处理演示

## 数据模型

### NetworkResponse
网络响应数据模型，包含状态码、响应体和响应头。

### NetworkException
网络异常类，包含详细的错误信息。

### FcReplyBody
APIP响应的数据模型，用于解析JSON响应。

## 网络环境适配

### 网络环境检测
ApipServiceManager提供了完整的网络环境检测功能：

1. **网络类型检测**：WiFi、移动网络、未知
2. **DNS解析时间**：检测DNS解析性能
3. **服务器IP获取**：获取目标服务器的IP地址
4. **网络延迟测试**：测试到服务器的网络延迟
5. **可达性测试**：测试服务器是否可达
6. **环境质量评估**：根据检测结果评估网络环境质量

### 重试机制
- 自动重试失败的请求
- 可配置的重试次数和延迟
- 详细的重试日志

### 备用服务器
- 支持多个备用服务器
- 自动测试服务器可用性
- 服务器性能对比

## 错误处理

### 网络错误类型
1. **连接超时**：网络连接超时
2. **读取超时**：数据读取超时
3. **DNS解析失败**：域名解析失败
4. **HTTP错误**：服务器返回错误状态码
5. **JSON解析错误**：响应数据格式错误

### 错误处理策略
1. **自动重试**：对于临时性错误自动重试
2. **备用服务器**：主服务器失败时尝试备用服务器
3. **网络环境检测**：根据网络环境调整请求策略
4. **详细错误信息**：提供详细的错误诊断信息

## 最佳实践

### 1. 初始化
```java
// 在Application或Activity中初始化
NetworkConfig config = NetworkConfig.getInstance(context);
config.setBaseUrl("https://your-api-server.com");
config.setTimeout(30000);

NetworkManager httpRequester = new NetworkManager(context);
ApipServiceManager apipManager = new ApipServiceManager(context);
```

### 2. 错误处理
```java
// 始终检查网络状态
if (!NetworkUtils.isNetworkAvailable(context)) {
    // 提示用户检查网络连接
    return;
}

// 使用try-catch处理异常
try {
    // 网络请求
} catch (NetworkException e) {
    // 处理网络错误
    TimberLogger.e(TAG, "Network error: %s", e.getMessage());
}
```

### 3. 资源管理
```java
// 在Activity销毁时关闭网络管理器
@Override
protected void onDestroy() {
    super.onDestroy();
    if (httpRequester != null) {
        httpRequester.shutdown();
    }
    if (apipManager != null) {
        apipManager.shutdown();
    }
}
```

### 4. 网络环境适配
```java
// 根据网络环境调整请求策略
if (NetworkUtils.isWifiConnected(context)) {
    // WiFi环境：使用较短的超时时间
    config.setTimeout(15000);
} else if (NetworkUtils.isMobileConnected(context)) {
    // 移动网络：使用较长的超时时间
    config.setTimeout(30000);
}
```

## 网络安全配置

### AndroidManifest.xml配置
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

### network_security_config.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">apip.cash</domain>
        <domain includeSubdomains="true">api.cash</domain>
    </domain-config>
</network-security-config>
```

## 总结

本网络模块提供了完整的网络请求解决方案，特别针对APIP服务进行了优化。通过ApipServiceManager，可以：

1. **自动检测网络环境**：了解当前网络状况
2. **智能重试机制**：提高请求成功率
3. **备用服务器支持**：确保服务可用性
4. **详细诊断信息**：便于问题排查
5. **网络环境适配**：根据网络状况调整策略

这种架构设计使得应用能够在不同的网络环境下稳定运行，特别是在网络环境较差的地区。 