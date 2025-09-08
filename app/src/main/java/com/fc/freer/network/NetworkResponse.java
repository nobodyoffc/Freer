package com.fc.freer.network;

import java.util.Map;

/**
 * 网络响应模型
 */
public class NetworkResponse {
    private final int statusCode;
    private final String body;
    private final Map<String, java.util.List<String>> headers;
    private final long timestamp;
    private final byte[] bodyBytes; // 新增：二进制响应数据
    private final String filePath; // 新增：下载文件路径
    
    public NetworkResponse(int statusCode, String body, Map<String, java.util.List<String>> headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
        this.timestamp = System.currentTimeMillis();
        this.bodyBytes = null;
        this.filePath = null;
    }
    
    /**
     * 构造函数 - 用于二进制数据
     */
    public NetworkResponse(int statusCode, byte[] bodyBytes, Map<String, java.util.List<String>> headers) {
        this.statusCode = statusCode;
        this.body = null;
        this.headers = headers;
        this.timestamp = System.currentTimeMillis();
        this.bodyBytes = bodyBytes;
        this.filePath = null;
    }
    
    /**
     * 构造函数 - 用于文件下载
     */
    public NetworkResponse(int statusCode, String filePath, Map<String, java.util.List<String>> headers, boolean isFile) {
        this.statusCode = statusCode;
        this.body = null;
        this.headers = headers;
        this.timestamp = System.currentTimeMillis();
        this.bodyBytes = null;
        this.filePath = filePath;
    }
    
    /**
     * 获取HTTP状态码
     */
    public int getStatusCode() {
        return statusCode;
    }
    
    /**
     * 获取响应体（文本）
     */
    public String getBody() {
        return body;
    }
    
    /**
     * 获取响应体（二进制）
     */
    public byte[] getBodyBytes() {
        return bodyBytes;
    }
    
    /**
     * 获取下载文件路径
     */
    public String getFilePath() {
        return filePath;
    }
    
    /**
     * 检查是否为文本响应
     */
    public boolean isTextResponse() {
        return body != null;
    }
    
    /**
     * 检查是否为二进制响应
     */
    public boolean isBinaryResponse() {
        return bodyBytes != null;
    }
    
    /**
     * 检查是否为文件下载响应
     */
    public boolean isFileResponse() {
        return filePath != null;
    }
    
    /**
     * 获取响应头
     */
    public Map<String, java.util.List<String>> getHeaders() {
        return headers;
    }
    
    /**
     * 获取响应时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 检查请求是否成功
     */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }
    
    /**
     * 检查是否为客户端错误
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }
    
    /**
     * 检查是否为服务器错误
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }
    
    /**
     * 获取特定响应头的值
     */
    public String getHeader(String name) {
        if (headers != null && headers.containsKey(name)) {
            java.util.List<String> values = headers.get(name);
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }
    
    /**
     * 获取响应体长度
     */
    public int getBodyLength() {
        if (body != null) {
            return body.length();
        } else if (bodyBytes != null) {
            return bodyBytes.length;
        } else {
            return 0;
        }
    }
    
    /**
     * 获取内容类型
     */
    public String getContentType() {
        return getHeader("Content-Type");
    }
    
    /**
     * 检查是否为图片响应
     */
    public boolean isImageResponse() {
        String contentType = getContentType();
        return contentType != null && contentType.startsWith("image/");
    }
    
    /**
     * 检查是否为JSON响应
     */
    public boolean isJsonResponse() {
        String contentType = getContentType();
        return contentType != null && contentType.contains("application/json");
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("NetworkResponse{");
        sb.append("statusCode=").append(statusCode);
        sb.append(", timestamp=").append(timestamp);
        sb.append(", isSuccessful=").append(isSuccessful());
        
        if (isTextResponse()) {
            sb.append(", bodyLength=").append(getBodyLength());
        } else if (isBinaryResponse()) {
            sb.append(", binaryLength=").append(getBodyLength());
        } else if (isFileResponse()) {
            sb.append(", filePath=").append(filePath);
        }
        
        sb.append('}');
        return sb.toString();
    }
} 