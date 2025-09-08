package com.fc.freer.model;

import android.content.Context;
import com.fc.fc_ajdk.clients.ApiUrl;
import com.fc.fc_ajdk.clients.ApipClientEvent;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.apipData.RequestBody;
import com.fc.fc_ajdk.data.fcData.Signature;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.freer.network.NetworkResponse;
import com.fc.freer.R;

import java.util.Map;

public class ApiEvent {

    //Request
    protected ApiUrl apiUrl;
    protected Fcdsl fcdsl;
    protected AuthType authType;
    protected String via;
    protected Map<String, String> requestHeaderMap;
    protected Signature signatureOfRequest;
    protected Map<String,String>requestParamMap;
    protected RequestBody requestBody;
    protected ApipClientEvent.RequestBodyType requestBodyType;
    protected String requestBodyStr;
    protected byte[] requestBodyBytes;

    //Response
    protected NetworkResponse networkResponse;

    protected Map<String, String> responseHeaderMap;
    protected FcReplyBody responseBody;
    protected String responseBodyStr;
    protected String requestFileName;
    protected ApipClientEvent.ResponseBodyType responseBodyType;
    protected String responseFileName;
    protected String responseFilePath;
    protected byte[] responseBodyBytes;
    protected Signature signatureOfResponse;

    protected Integer code;
    protected String message;

    // RequestResult 整合字段
    protected String fullUrl;
    protected String requestMethod;
    protected int timeout;
    protected long totalTime;
    protected long requestTime;
    protected long warmupTime;
    protected int attemptCount;
    protected Context context; // 用于toString方法中的字符串资源



    public enum RequestBodyType {
        NONE,STRING,BYTES,FILE,FCDSL
    }
    public enum ResponseBodyType {
        FC_REPLY,BYTES,FILE,STRING
    }

    // 构造函数
    public ApiEvent() {
        this.attemptCount = 1;
    }

    public ApiEvent(Context context) {
        this();
        this.context = context;
    }

    // 原有的getter和setter方法
    public ApiUrl getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(ApiUrl apiUrl) {
        this.apiUrl = apiUrl;
    }

    public Fcdsl getFcdsl() {
        return fcdsl;
    }

    public void setFcdsl(Fcdsl fcdsl) {
        this.fcdsl = fcdsl;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public String getVia() {
        return via;
    }

    public void setVia(String via) {
        this.via = via;
    }

    public Map<String, String> getRequestHeaderMap() {
        return requestHeaderMap;
    }

    public void setRequestHeaderMap(Map<String, String> requestHeaderMap) {
        this.requestHeaderMap = requestHeaderMap;
    }

    public Signature getSignatureOfRequest() {
        return signatureOfRequest;
    }

    public void setSignatureOfRequest(Signature signatureOfRequest) {
        this.signatureOfRequest = signatureOfRequest;
    }

    public Map<String, String> getRequestParamMap() {
        return requestParamMap;
    }

    public void setRequestParamMap(Map<String, String> requestParamMap) {
        this.requestParamMap = requestParamMap;
    }

    public RequestBody getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(RequestBody requestBody) {
        this.requestBody = requestBody;
    }

    public ApipClientEvent.RequestBodyType getRequestBodyType() {
        return requestBodyType;
    }

    public void setRequestBodyType(ApipClientEvent.RequestBodyType requestBodyType) {
        this.requestBodyType = requestBodyType;
    }

    public String getRequestBodyStr() {
        return requestBodyStr;
    }

    public void setRequestBodyStr(String requestBodyStr) {
        this.requestBodyStr = requestBodyStr;
    }

    public byte[] getRequestBodyBytes() {
        return requestBodyBytes;
    }

    public void setRequestBodyBytes(byte[] requestBodyBytes) {
        this.requestBodyBytes = requestBodyBytes;
    }

    public Map<String, String> getResponseHeaderMap() {
        return responseHeaderMap;
    }

    public void setResponseHeaderMap(Map<String, String> responseHeaderMap) {
        this.responseHeaderMap = responseHeaderMap;
    }

    public FcReplyBody getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(FcReplyBody responseBody) {
        this.responseBody = responseBody;
    }

    public String getResponseBodyStr() {
        return responseBodyStr;
    }

    public void setResponseBodyStr(String responseBodyStr) {
        this.responseBodyStr = responseBodyStr;
    }

    public String getRequestFileName() {
        return requestFileName;
    }

    public void setRequestFileName(String requestFileName) {
        this.requestFileName = requestFileName;
    }

    public ApipClientEvent.ResponseBodyType getResponseBodyType() {
        return responseBodyType;
    }

    public void setResponseBodyType(ApipClientEvent.ResponseBodyType responseBodyType) {
        this.responseBodyType = responseBodyType;
    }

    public String getResponseFileName() {
        return responseFileName;
    }

    public void setResponseFileName(String responseFileName) {
        this.responseFileName = responseFileName;
    }

    public String getResponseFilePath() {
        return responseFilePath;
    }

    public void setResponseFilePath(String responseFilePath) {
        this.responseFilePath = responseFilePath;
    }

    public byte[] getResponseBodyBytes() {
        return responseBodyBytes;
    }

    public void setResponseBodyBytes(byte[] responseBodyBytes) {
        this.responseBodyBytes = responseBodyBytes;
    }

    public Signature getSignatureOfResponse() {
        return signatureOfResponse;
    }

    public void setSignatureOfResponse(Signature signatureOfResponse) {
        this.signatureOfResponse = signatureOfResponse;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }


    public NetworkResponse getNetworkResponse() {
        return networkResponse;
    }

    public void setNetworkResponse(NetworkResponse networkResponse) {
        this.networkResponse = networkResponse;
    }

    // RequestResult 整合的getter和setter方法
    public String getFullUrl() {
        return fullUrl;
    }

    public void setFullUrl(String fullUrl) {
        this.fullUrl = fullUrl;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public long getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(long totalTime) {
        this.totalTime = totalTime;
    }

    public long getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(long requestTime) {
        this.requestTime = requestTime;
    }

    public long getWarmupTime() {
        return warmupTime;
    }

    public void setWarmupTime(long warmupTime) {
        this.warmupTime = warmupTime;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isSuccess() {
        return code != null && code == com.fc.fc_ajdk.constants.CodeMessage.Code0Success;
    }

    /**
     * Set both code and corresponding message from CodeMessage constants
     */
    public void setCodeMessage(int code) {
        this.code = code;
        this.message = com.fc.fc_ajdk.constants.CodeMessage.getMsg(code);
    }



    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }


    /**
     * 设置请求时间统计
     */
    public void setRequestTiming(long startTime, long requestStartTime, long endTime) {
        this.totalTime = endTime - startTime;
        this.requestTime = endTime - requestStartTime;
        this.warmupTime = requestStartTime - startTime;
    }



    /**
     * 获取详细的请求结果信息（类似RequestResult的toString方法）
     */
    public String getDetailedResultInfo() {
        if (context == null) {
            return getBasicResultInfo();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(context.getString(R.string.request_information)).append(" ===\n");
        sb.append(context.getString(R.string.full_url)).append(": ").append(fullUrl != null ? fullUrl : "N/A").append("\n");
        sb.append(context.getString(R.string.request_method)).append(": ").append(requestMethod != null ? requestMethod : "N/A").append("\n");
        sb.append(context.getString(R.string.request_headers)).append(": ").append(requestHeaderMap != null ? requestHeaderMap.toString() : "N/A").append("\n");
        sb.append(context.getString(R.string.timeout_setting)).append(": ").append(timeout).append("ms\n");
        sb.append(context.getString(R.string.total_time)).append(": ").append(totalTime).append("ms\n");
        sb.append(context.getString(R.string.actual_request_time)).append(": ").append(requestTime).append("ms\n");
        sb.append(context.getString(R.string.warmup_time)).append(": ").append(warmupTime).append("ms\n");
        sb.append(context.getString(R.string.attempt_count)).append(": ").append(attemptCount).append("\n");
        
        if (isSuccess()) {
            sb.append("\n=== ").append(context.getString(R.string.response_information)).append(" ===\n");
            sb.append(context.getString(R.string.http_status_code)).append(": ").append(networkResponse != null ? networkResponse.getStatusCode() : "N/A").append("\n");
            sb.append(context.getString(R.string.response_body_length)).append(": ").append(responseBodyStr != null ? responseBodyStr.length() : 0).append(" 字符\n");
            
            if (responseBody != null) {
                sb.append("\n=== ").append(context.getString(R.string.json_parse_result)).append(" ===\n");
                sb.append(context.getString(R.string.parse_successful)).append(": 是\n");
                sb.append("Response Code: ").append(responseBody.getCode()).append("\n");
                sb.append("Message: ").append(responseBody.getMessage()).append("\n");
                sb.append("Request ID: ").append(responseBody.getRequestId()).append("\n");
                sb.append("Operation: ").append(responseBody.getOp()).append("\n");
                sb.append("Nonce: ").append(responseBody.getNonce()).append("\n");
                sb.append("Time: ").append(responseBody.getTime()).append("\n");
                sb.append("Balance: ").append(responseBody.getBalance()).append("\n");
                sb.append("Best Height: ").append(responseBody.getBestHeight()).append("\n");
                sb.append("Best Block ID: ").append(responseBody.getBestBlockId()).append("\n");
                sb.append("Got: ").append(responseBody.getGot()).append("\n");
                sb.append("Total: ").append(responseBody.getTotal()).append("\n");
                
                if (responseBody.getData() != null) {
                    sb.append("Data: ").append(responseBody.getData().toString()).append("\n");
                }
                
                if (responseBody.getLast() != null && !responseBody.getLast().isEmpty()) {
                    sb.append("Last: ").append(responseBody.getLast().size()).append(" items\n");
                    sb.append("Last items: ").append(responseBody.getLast().toString()).append("\n");
                }
            }
        } else {
            sb.append("\n=== ").append(context.getString(R.string.error_information)).append(" ===\n");
            sb.append(context.getString(R.string.error)).append(": ").append(message != null ? message : "Unknown error").append("\n");
        }
        
        return sb.toString();
    }


    /**
     * 获取基本的请求结果信息（不依赖Context）
     */
    public String getBasicResultInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Request Information ===\n");
        sb.append("Full URL: ").append(fullUrl != null ? fullUrl : "N/A").append("\n");
        sb.append("Request Method: ").append(requestMethod != null ? requestMethod : "N/A").append("\n");
        sb.append("Request Headers: ").append(requestHeaderMap != null ? requestHeaderMap.toString() : "N/A").append("\n");
        sb.append("Timeout: ").append(timeout).append("ms\n");
        sb.append("Total Time: ").append(totalTime).append("ms\n");
        sb.append("Request Time: ").append(requestTime).append("ms\n");
        sb.append("Warmup Time: ").append(warmupTime).append("ms\n");
        sb.append("Attempt Count: ").append(attemptCount).append("\n");
        
        if (isSuccess()) {
            sb.append("\n=== Response Information ===\n");
            sb.append("HTTP Status Code: ").append(networkResponse != null ? networkResponse.getStatusCode() : "N/A").append("\n");
            sb.append("Response Body Length: ").append(responseBodyStr != null ? responseBodyStr.length() : 0).append(" characters\n");
            
            if (responseBody != null) {
                sb.append("\n=== JSON Parse Result ===\n");
                sb.append("Parse Successful: Yes\n");
                sb.append("Response Code: ").append(responseBody.getCode()).append("\n");
                sb.append("Message: ").append(responseBody.getMessage()).append("\n");
                sb.append("Request ID: ").append(responseBody.getRequestId()).append("\n");
                sb.append("Operation: ").append(responseBody.getOp()).append("\n");
                sb.append("Nonce: ").append(responseBody.getNonce()).append("\n");
                sb.append("Time: ").append(responseBody.getTime()).append("\n");
                sb.append("Balance: ").append(responseBody.getBalance()).append("\n");
                sb.append("Best Height: ").append(responseBody.getBestHeight()).append("\n");
                sb.append("Best Block ID: ").append(responseBody.getBestBlockId()).append("\n");
                sb.append("Got: ").append(responseBody.getGot()).append("\n");
                sb.append("Total: ").append(responseBody.getTotal()).append("\n");
                
                if (responseBody.getData() != null) {
                    sb.append("Data: ").append(responseBody.getData().toString()).append("\n");
                }
                
                if (responseBody.getLast() != null && !responseBody.getLast().isEmpty()) {
                    sb.append("Last: ").append(responseBody.getLast().size()).append(" items\n");
                    sb.append("Last items: ").append(responseBody.getLast().toString()).append("\n");
                }
            }
        } else {
            sb.append("\n=== Error Information ===\n");
            sb.append("Error: ").append(message != null ? message : "Unknown error").append("\n");
        }
        
        return sb.toString();
    }

    @Override
    public String toString() {
        return getBasicResultInfo();
    }
}
