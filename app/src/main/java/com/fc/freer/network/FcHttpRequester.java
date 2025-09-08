package com.fc.freer.network;

import android.content.Context;

import com.fc.fc_ajdk.clients.ApiUrl;
import com.fc.fc_ajdk.constants.CodeMessage;
import com.fc.fc_ajdk.core.fch.FchMainNetwork;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.apipData.RequestBody;
import com.fc.fc_ajdk.data.apipData.SignInMode;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.FcSession;
import com.fc.fc_ajdk.data.fcData.Signature;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.http.AuthType;

import com.fc.fc_ajdk.utils.IdNameUtils;

import com.fc.freer.FreerApplication;
import com.fc.freer.model.ApiAccount;
import com.fc.freer.model.ApiEvent;
import com.fc.freer.model.ApiProvider;
import com.fc.freer.model.FcReplyBody;
import com.fc.fc_ajdk.utils.TimberLogger;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.fc.fc_ajdk.constants.UpStrings.FID;
import static com.fc.fc_ajdk.constants.UpStrings.SESSION_NAME;
import static com.fc.fc_ajdk.constants.UpStrings.SIGN;

import androidx.annotation.NonNull;

import org.bitcoinj.core.ECKey;

/**
 * APIP请求执行器 - 专门处理APIP相关网络请求
 */
public class FcHttpRequester {
    private static final String TAG = "FcHttpRequester";
    private static final int DEFAULT_TIMEOUT = 30;

    private final Context context;
    private final HttpRequester httpRequester;
    private ApiProvider apiProvider;
    private ApiAccount apiAccount;
    private ApiUrl apiUrl;
    private ApiEvent apiEvent;

    public FcHttpRequester(Context context) {
        this(context, null, null);
    }

    public FcHttpRequester(Context context, String urlHead) {
        this.context = context.getApplicationContext();
        this.httpRequester = new HttpRequester(context);

        this.apiUrl = new ApiUrl();
        this.apiUrl.setUrlHead(urlHead);

    }
    
    public FcHttpRequester(Context context, ApiProvider apiProvider, ApiAccount apiAccount) {
        this.context = context.getApplicationContext();
        this.httpRequester = new HttpRequester(context);
        this.apiProvider = apiProvider;
        this.apiAccount = apiAccount;

        // 初始化ApiUrl
        if (apiAccount != null && apiAccount.getApiUrl() != null) {
            this.apiUrl = new ApiUrl();
            this.apiUrl.setUrlHead(apiAccount.getApiUrl());
        } else {
            this.apiUrl = new ApiUrl();
        }
    }

    /**
     * 通用GET请求方法（同步执行，返回ApiEvent）
     */
    public ApiEvent get(String urlTail, int timeout) {
        return get(urlTail, timeout, false, AuthType.FREE);
    }

    /**
     * 通用GET请求方法（同步执行，返回ApiEvent）
     */
    public ApiEvent get(String urlTail, int timeout, boolean updateAccount, AuthType authType) {
        try {
            if (apiUrl == null || apiUrl.getUrlHead() == null) {
                return createErrorEvent(CodeMessage.Code1024UrlMissed , "GET");
            }
            String fullUrl = apiUrl.getUrlHead() + urlTail;

            TimberLogger.d(TAG, "Sending GET request to: %s", fullUrl);
            
            // 创建请求头
            Map<String, String> headers = createHeaders(authType, fullUrl, null, null, null, null);

            this.apiEvent = new ApiEvent();
            apiUrl.setUrlTail(urlTail);
            this.apiEvent.setApiUrl(apiUrl);
            this.apiEvent.setRequestHeaderMap(headers);

            // 检查认证类型是否支持
            if (authType == AuthType.FC_SIGN_BODY) {
                return createErrorEvent(CodeMessage.Code1020OtherError, "GET request does not support FC_SIGN_BODY authentication", "GET");
            }
            
            // 同步执行GET请求
            return executeSyncRequest(fullUrl, headers, null, "GET", timeout, updateAccount, authType);
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error sending GET request: %s", e.getMessage(), e);
            return createErrorEvent(CodeMessage.Code1020OtherError, "Exception: " + e.getMessage(), "GET");
        }
    }

    /**
     * 通用POST请求方法（同步执行，返回ApiEvent）
     */
    public ApiEvent post(String urlTail, Fcdsl fcdsl, int timeout) {
        return post(urlTail, fcdsl, timeout, true, null, AuthType.FREE);
    }
    
    /**
     * 通用POST请求方法（同步执行，返回ApiEvent）
     */
    public ApiEvent post(String urlTail, Fcdsl fcdsl, int timeout, boolean updateAccount, SignInMode signInMode, AuthType authType) {
        return post(urlTail, fcdsl, timeout, updateAccount, signInMode, authType, null, null);
    }

    public ApiEvent post(String urlTail, Fcdsl fcdsl, int timeout, boolean updateAccount, AuthType authType) {
        return post(urlTail, fcdsl, timeout, updateAccount, null, authType, null, null);
    }

    /**
     * 通用POST请求方法（同步执行，返回ApiEvent，支持自定义请求头）
     */
    public ApiEvent post(String urlTail, Fcdsl fcdsl, int timeout, boolean updateAccount, SignInMode signInMode, AuthType authType, Map<String, String> customHeaders, byte[] prikey) {
        try {
            // 验证请求前提条件
            ApiEvent validationError = validatePostRequest(authType,signInMode);
            if (validationError != null) {
                return validationError;
            }
            String fullUrl = apiUrl.getUrlHead() + urlTail;
            TimberLogger.d(TAG, "Sending POST request to: %s", fullUrl);
            
            // 创建并序列化RequestBody
            this.apiEvent = new ApiEvent();
            apiUrl.setUrlTail(urlTail);
            this.apiEvent.setApiUrl(apiUrl);

            String requestBodyStr = createRequestBodyString(fullUrl, fcdsl, signInMode);
            byte[] requestBodyBytes = requestBodyStr.getBytes(StandardCharsets.UTF_8);

            // 创建请求头
            Map<String, String> headers = createHeaders(authType, fullUrl, requestBodyBytes, customHeaders, signInMode, prikey);

            this.apiEvent.setRequestHeaderMap(headers);

            TimberLogger.d(TAG, "Request header: %s\nRequest body:%s", JsonUtils.toNiceJson(apiEvent.getRequestHeaderMap()),apiEvent.getRequestBody().toNiceJson());

            // 同步执行POST请求
            return executeSyncRequest(fullUrl, headers, requestBodyStr, "POST", timeout, updateAccount, authType);

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error sending POST request: %s", e.getMessage(), e);
            return createErrorEvent(CodeMessage.Code1020OtherError,  "Exception: " + e.getMessage(), "POST");
        }
    }


    protected void makeHeaderAsySign(byte[] priKey,String requestBodyStr,Map<String,String> headerMap) {
        if (priKey == null) return;

        ECKey ecKey = ECKey.fromPrivate(priKey);

        String sign = ecKey.signMessage(requestBodyStr);
        String fid = ecKey.toAddress(FchMainNetwork.MAINNETWORK).toBase58();

        Signature signatureOfRequest = new Signature(fid, requestBodyStr, sign, AlgorithmId.BTC_EcdsaSignMsg_No1_NrC7, null);

        if(headerMap==null)headerMap=new HashMap<>();
        headerMap.put(FID, fid);
        headerMap.put(SIGN, signatureOfRequest.getSign());
    }

    /**
     * 创建请求头
     */
    private Map<String, String> createHeaders(AuthType authType, String fullUrl, byte[] requestBodyBytes) {
        return createHeaders(authType, fullUrl, requestBodyBytes, null, null, null);
    }

    /**
     * 创建请求头（支持自定义请求头）
     */
    private Map<String, String> createHeaders(AuthType authType, String fullUrl, byte[] requestBodyBytes, Map<String, String> customHeaders, SignInMode signInMode, byte[] prikey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "Freer-Android-App/1.0");
        headers.put("Connection", "close");
        
        // 根据认证类型添加相应的认证信息
        switch (authType) {
            case FC_SIGN_URL:
                sign(fullUrl.getBytes(StandardCharsets.UTF_8), headers, "Added URL signature: sessionName=%s, sign=%s");
                break;
            case FC_SIGN_BODY:
                if (requestBodyBytes != null) {
                    if(signInMode!=null){
                        makeHeaderAsySign(prikey,new String(requestBodyBytes),headers);
                    }else sign(requestBodyBytes, headers, "Added body signature: sessionName=%s, sign=%s");
                }
                break;
            case TOKEN:
                tokenAuth(headers);
                break;
            case FREE:
            default:
                // 不需要添加任何认证信息
                break;
        }
        
        // 添加自定义请求头（如果有的话）
        if (customHeaders != null) {
            headers.putAll(customHeaders);
        }
        
        return headers;
    }

    /**
     * 执行同步请求 - 简化版本
     */
    private ApiEvent executeSyncRequest(String fullUrl, Map<String, String> headers, String requestBodyStr, 
                                      String method, int timeout, boolean updateAccount, AuthType authType) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 执行HTTP请求
            NetworkResponse response = executeHttpRequest(fullUrl, headers, requestBodyStr, method);
            
            // 创建基础事件
            this.apiEvent = updateApiEvent(fullUrl, method, headers, startTime, response);

            if (response.isSuccessful()) {
                // 验证和解析响应
                return processSuccessfulResponse(apiEvent, response, authType, updateAccount);
            } else {
                apiEvent.setCodeMessage(response.getStatusCode());
                return apiEvent;
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in %s request: %s", method, e.getMessage());
            return createErrorEvent(CodeMessage.Code1020OtherError, "Exception: " + e.getMessage(), method);
        }
    }

    /**
     * 执行实际的HTTP请求
     */
    private NetworkResponse executeHttpRequest(String fullUrl, Map<String, String> headers, String requestBodyStr, 
                                             String method) throws Exception {
        // 执行请求
        if ("GET".equals(method)) {
            return httpRequester.get(fullUrl, headers);
        } else {
            return httpRequester.post(fullUrl, requestBodyStr, headers);
        }
    }

    /**
     * 创建基础ApiEvent
     */
    private ApiEvent updateApiEvent(String fullUrl, String method, Map<String, String> headers,
                                    long startTime, NetworkResponse response) {
        long endTime = System.currentTimeMillis();
        if(this.apiEvent==null)this.apiEvent=new ApiEvent();
        this.apiEvent.setFullUrl(fullUrl);
        this.apiEvent.setRequestMethod(method);
        this.apiEvent.setRequestHeaderMap(headers);
        this.apiEvent.setNetworkResponse(response);
        this.apiEvent.setResponseBodyStr(response.getBody());
        this.apiEvent.setRequestTiming(startTime, startTime, endTime);
        this.apiEvent.setAttemptCount(1);
        this.apiEvent.setCodeMessage(response.getStatusCode());
        
        return apiEvent;
    }

    /**
     * 处理成功的响应
     */
    private ApiEvent processSuccessfulResponse(ApiEvent event, NetworkResponse response, AuthType authType, boolean updateAccount) {
        try {
            // 验证响应签名（如果需要）
            if (!validateResponseSignatureIfNeeded(response, authType)) {
                event.setCodeMessage(CodeMessage.Code1034BadResponseSign);
                return event;
            }

            // 解析响应
            FcReplyBody fcReplyBody = parseResponseBody(response.getBody());
            event.setResponseBody(fcReplyBody);

            if (fcReplyBody != null) {
                event.setCode(fcReplyBody.getCode());
                event.setMessage(fcReplyBody.getMessage());
                
                // 更新ApiAccount信息
                if (fcReplyBody.getCode() == 0 ){
                   if( updateAccount)
                       updateApiAccountFromResponse(fcReplyBody);
                    TimberLogger.d(TAG, "%s response successful", event.getRequestMethod());
                }
            }else {
                event.setCodeMessage(CodeMessage.Code3005ResponseDataIsNull);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing %s response: %s", event.getRequestMethod(), e.getMessage());
            event.setCode(CodeMessage.Code1020OtherError);
            event.setMessage("Parse error: " + e.getMessage());
        }
        return event;
    }

    /**
     * 验证响应签名（如果需要）
     */
    private boolean validateResponseSignatureIfNeeded(NetworkResponse response, AuthType authType) {
        if ((authType == AuthType.FC_SIGN_URL || authType == AuthType.FC_SIGN_BODY) &&
            apiAccount != null && apiAccount.getSessionKey() != null) {
            return validateResponseSignature(response, apiAccount.getSessionKey());
        }
        return true; // 不需要验证或无需验证的情况
    }

    /**
     * 解析响应体
     */
    private FcReplyBody parseResponseBody(String responseBody) throws Exception {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new Exception("Empty response body");
        }
        
        Gson gson = new Gson();
        return gson.fromJson(responseBody, FcReplyBody.class);
    }

    /**
     * 创建并序列化RequestBody
     */
    private String createRequestBodyString(String fullUrl, Fcdsl fcdsl, SignInMode signInMode) {

        RequestBody requestBody = new RequestBody(fullUrl, FreerApplication.FREER_APP_DEALER,signInMode);

        if (signInMode != null) {
            if(fcdsl==null)fcdsl = new Fcdsl();
            Map<String, String>other = new HashMap<>();
            other.put("mode",signInMode.name());
            fcdsl.setOther(other);
        }
        if (fcdsl != null) {
            requestBody.setFcdsl(fcdsl);
        }
        this.apiEvent.setRequestBody(requestBody);
        Gson gson = new Gson();
        return gson.toJson(requestBody);
    }

    /**
     * 验证POST请求的前提条件
     */
    private ApiEvent validatePostRequest(AuthType authType, SignInMode signInMode) {
        if (apiUrl == null || apiUrl.getUrlHead() == null) {
            return createErrorEvent(CodeMessage.Code1024UrlMissed, "POST");
        }
        
        // 检查是否需要session key
        if (authType != AuthType.FREE && signInMode==null && (apiAccount == null || apiAccount.getSessionKey() == null)) {
            TimberLogger.w(TAG, "No session key available for POST request with auth type: %s", authType);
            return createErrorEvent(CodeMessage.Code1023MissSessionKey, "POST");
        }
        
        return null; // 验证通过
    }

    /**
     * 创建错误事件
     */

    @NonNull
    private ApiEvent createErrorEvent(int code, String method) {
        return createErrorEvent(code, null, method);
    }
    @NonNull
    private ApiEvent createErrorEvent(int code, String errorMessage, String method) {
        ApiEvent event = new ApiEvent(context);
        event.setCode(code);

        if(errorMessage!=null) {
            TimberLogger.w(TAG, errorMessage);
            event.setMessage(errorMessage);
        } else event.setMessage(CodeMessage.getMsg(code));

        event.setRequestMethod(method);
        return event;
    }

    /**
     * Token认证
     */
    private void tokenAuth(Map<String, String> headers) {
        if (apiAccount != null && apiAccount.getSessionKey() != null) {
            String token = android.util.Base64.encodeToString(apiAccount.getSessionKey(), android.util.Base64.NO_WRAP);
            headers.put("Authorization", "Bearer " + token);
            TimberLogger.d(TAG, "Added Bearer token authentication");
        } else {
            TimberLogger.w(TAG, "No session key available for token authentication");
        }
    }

    /**
     * 签名
     */
    private void sign(byte[] data, Map<String, String> headers, String format) {
        byte[] sessionKey = apiAccount.getSessionKey();
        String sign = FcSession.sign(sessionKey, data);
        String sessionName = IdNameUtils.makeKeyName(sessionKey);

        headers.put(SESSION_NAME, sessionName);
        headers.put(SIGN, sign);

        TimberLogger.d(TAG, format, sessionName, sign);
    }

    /**
     * 检测网络环境
     */
    public HttpRequester.NetworkEnvironment detectNetworkEnvironment() throws Exception {
        return httpRequester.detectNetworkEnvironment("apip.cash");
    }


    /**
     * 验证响应签名
     */
    private boolean validateResponseSignature(NetworkResponse response, byte[] sessionKey) {
        try {
            String signHeader = NetworkUtils.getFirstHeaderValue(response, SIGN);

            if (signHeader == null) {
                TimberLogger.w(TAG, "Missing signature headers in response");
                return false;
            }
            
            // 验证签名
            byte[] responseBodyBytes = response.getBody().getBytes(StandardCharsets.UTF_8);
            String expectedSign = FcSession.sign(sessionKey, responseBodyBytes);
            
            boolean isValid = signHeader.equals(expectedSign);
            if (!isValid) {
                TimberLogger.w(TAG, "Signature validation failed: expected=%s, actual=%s", expectedSign, signHeader);
            }
            
            return isValid;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error validating response signature: %s", e.getMessage());
            return false;
        }
    }
    
    /**
     * 测试备用服务器
     */
    public HttpRequester.ServerTestResult[] testAlternativeServers() {
        String[] servers = {
            "https://apip.cash",
            "https://freecash.info",
            "https://httpbin.org/get"
        };
        
        return httpRequester.testAlternativeServers(servers);
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        httpRequester.shutdown();
    }
    

    // Getter and Setter methods
    public ApiUrl getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(ApiUrl apiUrl) {
        this.apiUrl = apiUrl;
    }

    /**
     * 从响应体更新ApiAccount的信息
     */
    public void updateApiAccountFromResponse(FcReplyBody fcReplyBody) {
        if (apiAccount == null) {
            TimberLogger.w(TAG, "ApiAccount is null, cannot update");
            return;
        }
        
        if (fcReplyBody == null) {
            TimberLogger.w(TAG, "FcReplyBody is null, cannot update ApiAccount");
            return;
        }
        
        // 更新balance
        if (fcReplyBody.getBalance() != null) {
            Long newBalance = fcReplyBody.getBalance();
            if (newBalance != null && (apiAccount.getBalance()==null || !apiAccount.getBalance().equals(newBalance))) {
                apiAccount.setBalance(newBalance);
                TimberLogger.d(TAG, "Updated ApiAccount balance: %d", newBalance);
                if(apiProvider!=null && apiProvider.getApiParams()!=null && apiProvider.getApiParams().getPricePerKBytes()!=null) {
                    String pricePerKBytesStr = apiProvider.getApiParams().getPricePerKBytes();
                    if (pricePerKBytesStr != null && !pricePerKBytesStr.isEmpty()) {
                        double pricePerKBytes = Double.parseDouble(pricePerKBytesStr);
                        long price = com.fc.fc_ajdk.utils.FchUtils.coinToSatoshi(pricePerKBytes);
                        long remainingAccessCount = newBalance / price;
                        apiAccount.setRemainRequestCount(remainingAccessCount);

                    }
                }
            }
        }
        
        // 更新bestHeight
        if (fcReplyBody.getBestHeight() != null) {
            long newBestHeight = fcReplyBody.getBestHeight();
            if (apiAccount.getBestHeight() != newBestHeight) {
                apiAccount.setBestHeight(newBestHeight);
                TimberLogger.d(TAG, "Updated ApiAccount bestHeight: %d", newBestHeight);
            }
        }
        
        // 更新bestBlockId
        if (fcReplyBody.getBestBlockId() != null) {
            String newBestBlockId = fcReplyBody.getBestBlockId();
            if (!newBestBlockId.equals(apiAccount.getBestBlockId())) {
                apiAccount.setBestBlockId(newBestBlockId);
                TimberLogger.d(TAG, "Updated ApiAccount bestBlockId: %s", newBestBlockId);
            }
        }
    }

    public ApiEvent getApiEvent() {
        return apiEvent;
    }

    public void setApiEvent(ApiEvent apiEvent) {
        this.apiEvent = apiEvent;
    }

    public ApiProvider getApiProvider() {
        return apiProvider;
    }

    public void setApiProvider(ApiProvider apiProvider) {
        this.apiProvider = apiProvider;
    }

    public ApiAccount getApiAccount() {
        return apiAccount;
    }

    public void setApiAccount(ApiAccount apiAccount) {
        this.apiAccount = apiAccount;
    }
}