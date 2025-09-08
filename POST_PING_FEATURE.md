# POST Ping 功能说明

## 概述

为 `ApipRequester` 添加了 POST 方法的 ping 功能，支持：
- 使用空的 FCDSL 请求体
- 请求签名验证
- 响应签名验证
- 自动更新 `ApiAccount` 的 `balance`、`bestHeight`、`bestBlockId` 等值

## 主要功能

### 1. POST Ping 请求
- 创建空的 `Fcdsl` 对象作为请求体
- 使用 `ApiAccount` 的 session key 对请求进行签名
- 发送 POST 请求到 `/v1/ping` 端点

### 2. 响应签名验证
- 验证响应头中的签名信息
- 确保响应的完整性和真实性

### 3. 账户信息更新
- 自动从响应中提取 `balance`、`bestHeight`、`bestBlockId` 等信息
- 更新 `ApiAccount` 对象的相应字段
- 提供可重复使用的 `updateApiAccountFromResponse()` 方法

## 使用方法

### 基本使用

```java
// 创建 ApipRequester 实例
ApipRequester requester = new ApipRequester(context, apiProvider, apiAccount);

// 执行 POST ping 请求
ApiEvent event = requester.pingWithEvent(RequestMethod.POST);

if (event.isSuccess()) {
    // 请求成功，账户信息已自动更新
    System.out.println("Ping successful");
} else {
    System.out.println("Ping failed: " + event.getErrorMessage());
}
```

### 使用 ApiHelper

```java
ApiHelper apiHelper = ApiHelper.getInstance(context);

// 执行 POST ping 请求
ApiEvent event = apiHelper.checkApipServiceAvailabilityWithPost();

if (event.isSuccess()) {
    // 显示更新后的账户信息
    if (event.getResponseBody() != null) {
        System.out.println("Balance: " + event.getResponseBody().getBalance());
        System.out.println("Best Height: " + event.getResponseBody().getBestHeight());
        System.out.println("Best Block ID: " + event.getResponseBody().getBestBlockId());
    }
}
```

### 手动更新账户信息

```java
// 从响应体手动更新账户信息
FcReplyBody responseBody = event.getResponseBody();
requester.updateApiAccountFromResponse(responseBody);
```

## 新增的方法

### ApipRequester 类

1. **pingWithEvent(RequestMethod method)**
   - 根据请求方法选择 GET 或 POST ping
   - 返回 `ApiEvent` 对象

2. **pingPostWithEvent()**
   - 执行 POST ping 请求
   - 包含签名验证和账户信息更新

3. **updateApiAccountFromResponse(FcReplyBody fcReplyBody)**
   - 从响应体更新 `ApiAccount` 信息
   - 可重复使用的方法

### ApiHelper 类

1. **checkApipServiceAvailabilityWithPost()**
   - 使用 POST 方法检查 API 服务可用性
   - 包含签名验证和账户信息更新

## 测试示例

在 `ApiUsageExampleActivity` 中添加了测试按钮：
- "Test POST Ping (with signature)" 按钮
- 演示如何使用新的 POST ping 功能
- 显示更新后的账户信息

## 注意事项

1. **Session Key 要求**
   - POST ping 需要有效的 session key
   - 如果 session key 不可用，会返回错误

2. **签名验证**
   - 请求和响应都会进行签名验证
   - 签名验证失败会导致请求失败

3. **错误处理**
   - 网络错误、超时、签名验证失败等都有相应的错误处理
   - 详细的错误信息会记录在日志中

4. **性能考虑**
   - POST ping 比 GET ping 需要更多资源
   - 建议在需要更新账户信息时使用 POST ping
   - 简单的连接测试可以使用 GET ping

## 日志输出

功能会输出详细的日志信息：
- 请求和响应的详细信息
- 签名验证结果
- 账户信息更新状态
- 错误和异常信息

使用 `TimberLogger` 查看详细日志：
```java
TimberLogger.d(TAG, "POST ping response data: %s", dataValue);
TimberLogger.d(TAG, "Updated ApiAccount balance: %d", newBalance);
```
