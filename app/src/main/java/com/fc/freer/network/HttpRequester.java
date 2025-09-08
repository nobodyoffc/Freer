package com.fc.freer.network;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 网络管理器 - 处理HTTP请求
 */
public class HttpRequester {
    private static final String TAG = "HttpRequester";
    private static final int CONNECT_TIMEOUT = 10000; // 10秒
    private static final int READ_TIMEOUT = 15000; // 15秒
    
    private final Context context;
//    private final ApiConfig networkConfig;
    
    public HttpRequester(Context context) {
        this.context = context.getApplicationContext();
//        this.networkConfig = ApiConfig.getInstance(context);
    }
    
    /**
     * 执行GET请求
     */
    public NetworkResponse get(String url, Map<String, String> headers) throws NetworkException {
        try {
            TimberLogger.d(TAG, "Starting GET request to: %s", url);
            return executeGetRequest(url, headers);
        } catch (Exception e) {
            String errorMessage = String.format(context.getString(R.string.get_request_failed), e.getMessage());
            TimberLogger.e(TAG, errorMessage);
            TimberLogger.e(TAG, "Exception details: %s", e.toString());
            if (e.getCause() != null) {
                TimberLogger.e(TAG, "Root cause: %s", e.getCause().getMessage());
            }
            throw new NetworkException(errorMessage, e);
        }
    }
    
    /**
     * 执行POST请求
     */
    public NetworkResponse post(String url, String jsonBody, Map<String, String> headers) throws NetworkException {
        try {
            return executePostRequest(url, jsonBody, headers);
        } catch (Exception e) {
            TimberLogger.e(TAG, "POST request failed: %s", e.getMessage());
            throw new NetworkException(context.getString(R.string.post_request_failed), e);
        }
    }
    
    /**
     * 执行GET请求获取二进制数据
     */
    public NetworkResponse getBinary(String url, Map<String, String> headers) throws NetworkException {
        try {
            TimberLogger.d(TAG, "Starting binary GET request to: %s", url);
            return executeBinaryGetRequest(url, headers);
        } catch (Exception e) {
            String errorMessage = String.format(context.getString(R.string.get_request_failed), e.getMessage());
            TimberLogger.e(TAG, errorMessage);
            throw new NetworkException(errorMessage, e);
        }
    }
    
    /**
     * 执行GET请求下载文件
     */
    public NetworkResponse downloadFile(String url, String fileName, String downloadPath, Map<String, String> headers) throws NetworkException {
        try {
            TimberLogger.d(TAG, "Starting file download from: %s", url);
            return executeFileDownloadRequest(url, fileName, downloadPath, headers);
        } catch (Exception e) {
            String errorMessage = String.format(context.getString(R.string.download_failed), e.getMessage());
            TimberLogger.e(TAG, errorMessage);
            throw new NetworkException(errorMessage, e);
        }
    }
    
    /**
     * 执行GET请求的具体实现
     */
    private NetworkResponse executeGetRequest(String urlString, Map<String, String> headers) throws IOException {
        TimberLogger.d(TAG, "Creating connection to: %s", urlString);
        
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            TimberLogger.d(TAG, "Setting connection properties");
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            
            // 设置默认headers
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            
            // 设置自定义headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                    TimberLogger.d(TAG, "Setting header: %s = %s", entry.getKey(), entry.getValue());
                }
            }
            
            TimberLogger.d(TAG, "Connecting to server...");
            int responseCode = connection.getResponseCode();
            TimberLogger.d(TAG, "Response code: %d", responseCode);
            
            String responseBody = readResponse(connection);
            TimberLogger.d(TAG, "Response body length: %d", responseBody.length());
            
            return new NetworkResponse(responseCode, responseBody, connection.getHeaderFields());
            
        } catch (IOException e) {
            TimberLogger.e(TAG, String.format(context.getString(R.string.connection_failed), e.getMessage()));
            TimberLogger.e(TAG, "URL: %s", urlString);
            TimberLogger.e(TAG, "Connect timeout: %d ms", CONNECT_TIMEOUT);
            TimberLogger.e(TAG, "Read timeout: %d ms", READ_TIMEOUT);
            throw e;
        } finally {
            connection.disconnect();
            TimberLogger.d(TAG, context.getString(R.string.connection_closed));
        }
    }
    
    /**
     * 执行POST请求的具体实现
     */
    private NetworkResponse executePostRequest(String urlString, String jsonBody, Map<String, String> headers) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setDoOutput(true);
            
            // 设置默认headers
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            
            // 设置自定义headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            // 写入请求体
            if (jsonBody != null && !jsonBody.isEmpty()) {
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                } catch (Exception e) {
                    TimberLogger.d(TAG, "Error writing request body: - Stack trace: " + android.util.Log.getStackTraceString(e));
                }
            }
            
            int responseCode = connection.getResponseCode();
            String responseBody = readResponse(connection);
            
            return new NetworkResponse(responseCode, responseBody, connection.getHeaderFields());
            
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * 执行二进制GET请求的具体实现
     */
    private NetworkResponse executeBinaryGetRequest(String urlString, Map<String, String> headers) throws IOException {
        TimberLogger.d(TAG, "Creating binary connection to: %s", urlString);
        
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            TimberLogger.d(TAG, "Setting connection properties for binary data");
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            
            // 设置自定义headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                    TimberLogger.d(TAG, "Setting header: %s = %s", entry.getKey(), entry.getValue());
                }
            }
            
            TimberLogger.d(TAG, "Connecting to server for binary data...");
            int responseCode = connection.getResponseCode();
            TimberLogger.d(TAG, "Response code: %d", responseCode);
            
            byte[] responseBytes = readBinaryResponse(connection);
            TimberLogger.d(TAG, "Binary response length: %d bytes", responseBytes.length);
            
            return new NetworkResponse(responseCode, responseBytes, connection.getHeaderFields());
            
        } catch (IOException e) {
            TimberLogger.e(TAG, String.format(context.getString(R.string.connection_failed), e.getMessage()));
            throw e;
        } finally {
            connection.disconnect();
            TimberLogger.d(TAG, context.getString(R.string.connection_closed));
        }
    }
    
    /**
     * 执行文件下载请求的具体实现
     */
    private NetworkResponse executeFileDownloadRequest(String urlString, String fileName, String downloadPath, Map<String, String> headers) throws IOException {
        TimberLogger.d(TAG, "Creating file download connection to: %s", urlString);
        
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            TimberLogger.d(TAG, "Setting connection properties for file download");
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            
            // 设置自定义headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            TimberLogger.d(TAG, "Connecting to server for file download...");
            int responseCode = connection.getResponseCode();
            TimberLogger.d(TAG, "Response code: %d", responseCode);
            
            String filePath = downloadFileFromResponse(connection, fileName, downloadPath);
            TimberLogger.d(TAG, "File downloaded to: %s", filePath);
            
            return new NetworkResponse(responseCode, filePath, connection.getHeaderFields(), true);
            
        } catch (IOException e) {
            TimberLogger.e(TAG, String.format(context.getString(R.string.connection_failed), e.getMessage()));
            throw e;
        } finally {
            connection.disconnect();
            TimberLogger.d(TAG, context.getString(R.string.connection_closed));
        }
    }
    
    /**
     * 读取响应内容
     */
    private String readResponse(HttpURLConnection connection) throws IOException {
        InputStream inputStream;
        if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
            inputStream = connection.getInputStream();
        } else {
            inputStream = connection.getErrorStream();
        }
        
        if (inputStream == null) {
            return "";
        }
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
    
    /**
     * 读取二进制响应内容
     */
    private byte[] readBinaryResponse(HttpURLConnection connection) throws IOException {
        InputStream inputStream;
        if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
            inputStream = connection.getInputStream();
        } else {
            inputStream = connection.getErrorStream();
        }
        
        if (inputStream == null) {
            return new byte[0];
        }
        
        try (java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream.toByteArray();
        }
    }
    
    /**
     * 从响应中下载文件
     */
    private String downloadFileFromResponse(HttpURLConnection connection, String fileName, String downloadPath) throws IOException {
        InputStream inputStream;
        if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
            inputStream = connection.getInputStream();
        } else {
            inputStream = connection.getErrorStream();
        }
        
        if (inputStream == null) {
            throw new IOException("No input stream available");
        }
        
        // 确保下载路径存在
        java.io.File downloadDir = new java.io.File(downloadPath);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        
        // 生成最终文件名
        String finalFileName = generateUniqueFileName(downloadPath, fileName);
        java.io.File outputFile = new java.io.File(downloadPath, finalFileName);
        
        try (java.io.FileOutputStream outputStream = new java.io.FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                
                // 记录下载进度
                if (totalBytes % (1024 * 1024) == 0) { // 每MB记录一次
                    TimberLogger.d(TAG, "Downloaded %d bytes", totalBytes);
                }
            }
            
            TimberLogger.d(TAG, "File download completed: %s (%d bytes)", outputFile.getAbsolutePath(), totalBytes);
            return outputFile.getAbsolutePath();
        }
    }
    
    /**
     * 生成唯一的文件名
     */
    private String generateUniqueFileName(String downloadPath, String originalFileName) {
        if (originalFileName == null || originalFileName.isEmpty()) {
            originalFileName = "download_" + System.currentTimeMillis();
        }
        
        java.io.File file = new java.io.File(downloadPath, originalFileName);
        if (!file.exists()) {
            return originalFileName;
        }
        
        // 如果文件已存在，添加数字后缀
        String nameWithoutExt = originalFileName;
        String extension = "";
        
        int lastDotIndex = originalFileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            nameWithoutExt = originalFileName.substring(0, lastDotIndex);
            extension = originalFileName.substring(lastDotIndex);
        }
        
        int counter = 1;
        String newFileName;
        do {
            newFileName = nameWithoutExt + "_" + counter + extension;
            file = new java.io.File(downloadPath, newFileName);
            counter++;
        } while (file.exists());
        
        return newFileName;
    }
    
    /**
     * 关闭网络管理器
     */
    public void shutdown() {
        // No resources to clean up in synchronous version
    }
    

    /**
     * 网络环境信息
     */
    public class NetworkEnvironment {
        public String networkType;
        public long dnsResolveTime;
        public String serverIp;
        public long pingTime;
        public boolean isReachable;
        public String quality;
        public String suggestions;
        
        @NonNull
        @Override
        public String toString() {
            return "=== " + context.getString(R.string.network_environment_detection) + " ===\n" +
                    context.getString(R.string.network_type) + ": " + networkType + "\n" +
                    context.getString(R.string.dns_resolve_time) + ": " + dnsResolveTime + "ms\n" +
                    context.getString(R.string.apip_server_ip) + ": " + serverIp + "\n" +
                    context.getString(R.string.network_latency) + ": " + pingTime + "ms\n" +
                    context.getString(R.string.reachability) + ": " + (isReachable ? "是" : "否") + "\n" +
                    context.getString(R.string.network_quality) + ": " + quality + "\n" +
                    "\n=== " + context.getString(R.string.network_suggestions) + " ===\n" +
                    suggestions;
        }
    }
    
    /**
     * 检测网络环境
     */
    public NetworkEnvironment detectNetworkEnvironment(String targetHost) throws Exception {
        NetworkEnvironment env = new NetworkEnvironment();
        
        // 检测网络类型
        boolean isWifi = NetworkUtils.isWifiConnected(context);
        boolean isMobile = NetworkUtils.isMobileConnected(context);
        env.networkType = isWifi ? "WiFi" : isMobile ? context.getString(R.string.mobile_connected) : "未知";
        
        // 检测DNS解析
        long dnsStart = System.currentTimeMillis();
        InetAddress targetAddress = InetAddress.getByName(targetHost);
        env.dnsResolveTime = System.currentTimeMillis() - dnsStart;
        env.serverIp = targetAddress.getHostAddress();
        
        // 检测网络延迟
        long pingStart = System.currentTimeMillis();
        env.isReachable = targetAddress.isReachable(5000);
        env.pingTime = System.currentTimeMillis() - pingStart;
        
        // 评估网络环境质量
        if (env.dnsResolveTime > 3000) {
            env.quality = context.getString(R.string.network_environment_poor) + " (DNS解析慢)";
        } else if (env.pingTime > 2000) {
            env.quality = context.getString(R.string.network_environment_fair) + " (网络延迟高)";
        } else if (!env.isReachable) {
            env.quality = context.getString(R.string.network_environment_restricted) + " (网络可达性差)";
        } else {
            env.quality = context.getString(R.string.network_environment_good);
        }
        
        // 生成建议
        if (env.dnsResolveTime > 3000 || !env.isReachable) {
            env.suggestions = context.getString(R.string.network_warning_message);
        } else {
            env.suggestions = context.getString(R.string.network_environment_good_message);
        }
        
        return env;
    }
    
    /**
     * 测试备用服务器
     */
    public ServerTestResult[] testAlternativeServers(String[] servers) {
        ServerTestResult[] results = new ServerTestResult[servers.length];
        
        for (int i = 0; i < servers.length; i++) {
            String serverUrl = servers[i];
            String endpoint = serverUrl.equals("https://apip.cash") ? "/v1/getService" : "";
            String fullUrl = serverUrl + endpoint;
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            headers.put("User-Agent", "Freer-Android-App/1.0");
            
            long startTime = System.currentTimeMillis();
            ServerTestResult result = new ServerTestResult();
            result.serverIndex = i + 1;
            result.serverUrl = fullUrl;
            
            try {
                NetworkResponse response = get(serverUrl + endpoint, headers);
                long duration = System.currentTimeMillis() - startTime;
                
                result.statusCode = response.getStatusCode();
                result.duration = duration;
                result.responseLength = response.getBody() != null ? response.getBody().length() : 0;
                result.isSuccess = response.isSuccessful();
            } catch (NetworkException exception) {

                result.duration = System.currentTimeMillis() - startTime;
                result.errorMessage = exception.getMessage();
                result.isSuccess = false;
            }
            
            results[i] = result;
        }
        
        return results;
    }
    
    /**
     * 服务器测试结果
     */
    public class ServerTestResult {
        public int serverIndex;
        public String serverUrl;
        public int statusCode;
        public long duration;
        public int responseLength;
        public String errorMessage;
        public boolean isSuccess;
        
        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(context.getString(R.string.server)).append(" ").append(serverIndex).append(" ===\n");
            sb.append("URL: ").append(serverUrl).append("\n");
            if (isSuccess) {
                sb.append(context.getString(R.string.status_code)).append(": ").append(statusCode).append("\n");
                sb.append(context.getString(R.string.duration)).append(": ").append(duration).append("ms\n");
                sb.append(context.getString(R.string.response_length)).append(": ").append(responseLength).append(" 字符\n");
                sb.append(context.getString(R.string.result)).append(": ✅ ").append(context.getString(R.string.success)).append("\n");
            } else {
                sb.append(context.getString(R.string.duration)).append(": ").append(duration).append("ms\n");
                sb.append(context.getString(R.string.result)).append(": ❌ ").append(context.getString(R.string.failed)).append("\n");
                sb.append(context.getString(R.string.error)).append(": ").append(errorMessage).append("\n");
            }
            return sb.toString();
        }
    }
    
} 