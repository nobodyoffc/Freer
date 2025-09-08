# POST请求实现文档

## 概述

本文档描述了为Freer应用添加的POST请求功能实现，包括Fcdsl查询参数处理、RequestBody创建、session签名和响应验证。

## 实现的功能

### 1. ApipRequester.java
- 添加了`post()`方法，支持Fcdsl查询参数
- 实现了RequestBody的创建和序列化
- 添加了session签名功能（使用FcSession.sign()）
- 实现了响应签名验证
- 支持重试机制和网络环境检测

### 2. ApipClient.java
- 添加了`post()`方法，委托给ApipRequester
- 支持POST请求
- 包含客户端初始化和验证逻辑

### 3. ApiHelper.java
- 添加了`executeApipPostRequest()`方法
- 提供了高级API使用接口
- 支持POST请求

## 核心功能

### Fcdsl查询参数处理
```java
Fcdsl fcdsl = new Fcdsl();
fcdsl.addIndex("mail"); // 设置索引
fcdsl.setSize("10"); // 限制返回数量
```

### RequestBody创建
```java
RequestBody requestBody = new RequestBody(fullUrl, apiAccount.getVia());
requestBody.setFcdsl(fcdsl);
```

### Session签名
```java
byte[] sessionKey = apiAccount.getSessionKey();
String sign = FcSession.sign(sessionKey, requestBodyBytes);
String sessionName = IdNameUtils.makeKeyName(sessionKey);

headers.put(SESSION_NAME, sessionName);
headers.put(SIGN, sign);
```

### 响应验证
```java
boolean isValidSign = validateResponseSignature(response, apiAccount.getSessionKey());
```

## 使用示例

### 基本POST请求
```java
ApiHelper apiHelper = ApiHelper.getInstance(context);

Fcdsl fcdsl = new Fcdsl();
fcdsl.addIndex("mail");
fcdsl.setSize("10");

apiHelper.executeApipPostRequest("getMails", fcdsl, new ApipClient.RequestCallback() {
    @Override
    public void onSuccess(ApiEvent apiEvent) {
        if (apiEvent.isSuccess()) {
            // 处理成功响应
            String responseData = apiEvent.getResponseBodyStr();
        } else {
            // 处理错误
            String error = apiEvent.getErrorMessage();
        }
    }
    
    @Override
    public void onRetry(int attempt, String errorMessage) {
        // 处理重试
    }
    
    @Override
    public void onError(ApiEvent apiEvent, Exception exception) {
        // 处理异常
    }
});
```

### 完整版本POST请求
```java
ApipClient client = getAvailableApipClient();
client.post("sn", "v1", "apiName", fcdsl, callback);
```

## 技术细节

### 签名算法
- 使用FcSession.sign()方法进行sha256双重哈希签名
- 签名数据：requestBodyBytes + sessionKey
- 签名格式：十六进制字符串

### 请求头设置
- Content-Type: application/json
- Accept: application/json
- User-Agent: Freer-Android-App/1.0
- Connection: close
- SessionName: 会话名称（6字节十六进制）
- Sign: 签名值

### 响应验证
- 验证响应头中的SessionName
- 验证响应头中的Sign
- 使用相同的签名算法验证响应体

### 错误处理
- 网络错误自动重试（最多2次）
- 签名验证失败处理
- JSON解析错误处理
- HTTP状态码错误处理

## 测试

在ApiUsageExampleActivity中添加了POST请求测试功能：
- 测试按钮：Test POST Request
- 示例查询：getMails API
- 显示响应时间和数据

## 注意事项

1. 确保ApiAccount中有有效的sessionKey
2. Fcdsl查询参数需要根据具体API要求设置
3. 响应签名验证是可选的，取决于服务器是否返回签名
4. 网络环境检测和重试机制提高了请求的可靠性

## 相关文件

- `app/src/main/java/com/fc/freer/network/ApipRequester.java`
- `app/src/main/java/com/fc/freer/network/ApipClient.java`
- `app/src/main/java/com/fc/freer/utils/ApiHelper.java`
- `app/src/main/java/com/fc/freer/home/ApiUsageExampleActivity.java`
- `app/src/main/res/layout/activity_api_usage_example.xml` 