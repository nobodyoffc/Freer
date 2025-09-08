package com.fc.freer.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.fc.freer.R;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络工具类
 */
public class NetworkUtils {
    /**
     * 检查网络是否可用
     */
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) {
            return false;
        }

        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null &&
               (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
    
    /**
     * 检查是否为WiFi连接
     */
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) {
            return false;
        }

        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    
    /**
     * 检查是否为移动网络连接
     */
    public static boolean isMobileConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) {
            return false;
        }

        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }
    
    /**
     * 测试网络连接
     */
    public static void testNetworkConnection(Context context, String url, NetworkCallback callback) {
        new Thread(() -> {
            try {
                URL testUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) testUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                
                int responseCode = connection.getResponseCode();
                connection.disconnect();
                
                if (responseCode == 200) {
                    callback.onSuccess(context.getString(R.string.network_connection_normal));
                } else {
                    callback.onError(new NetworkException(String.format(context.getString(R.string.network_connection_abnormal), responseCode)));
                }
            } catch (IOException e) {
                callback.onError(new NetworkException(context.getString(R.string.network_connection_failed), e));
            }
        }).start();
    }
    
    /**
     * 构建查询参数
     */
    public static String buildQueryString(Map<String, String> paramsMap) {
        if (paramsMap == null || paramsMap.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : paramsMap.entrySet()) {
            if (!first) sb.append("&");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sb.append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            first = false;
        }
        return sb.toString();
    }
    
    /**
     * 构建带查询参数的URL
     */
    public static String buildUrlWithParams(String baseUrl, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return baseUrl;
        }
        
        String queryString = buildQueryString(params);
        return baseUrl + "?" + queryString;
    }
    
    /**
     * 创建默认请求头
     */
    public static Map<String, String> createDefaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Freer-Android/1.0");
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        return headers;
    }
    
    /**
     * 创建带认证的请求头
     */
    public static Map<String, String> createAuthTokenHeaders(String token) {
        Map<String, String> headers = createDefaultHeaders();
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    /**
     * 获取HTTP头部的第一个值
     */
    public static String getFirstHeaderValue(NetworkResponse response,String headerName) {
        Map<String, List<String>> headers = response.getHeaders();
        List<String> values = headers.get(headerName);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return null;
    }

    /**
     * 网络回调接口
     */
    public interface NetworkCallback {
        void onSuccess(String message);
        void onError(NetworkException exception);
    }
} 