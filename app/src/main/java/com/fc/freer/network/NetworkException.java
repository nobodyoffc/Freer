package com.fc.freer.network;

import android.content.Context;

import com.fc.freer.R;

/**
 * 网络异常类
 */
public class NetworkException extends Exception {
    private final NetworkErrorType errorType;
    
    public NetworkException(String message) {
        super(message);
        this.errorType = NetworkErrorType.UNKNOWN;
    }
    
    public NetworkException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = NetworkErrorType.UNKNOWN;
    }
    
    public NetworkException(String message, NetworkErrorType errorType) {
        super(message);
        this.errorType = errorType;
    }
    
    public NetworkException(String message, Throwable cause, NetworkErrorType errorType) {
        super(message, cause);
        this.errorType = errorType;
    }
    
    /**
     * 获取错误类型
     */
    public NetworkErrorType getErrorType() {
        return errorType;
    }
    
    /**
     * 网络错误类型枚举
     */
    public enum NetworkErrorType {
        CONNECTION_ERROR,
        TIMEOUT_ERROR,
        AUTHENTICATION_ERROR,
        SERVER_ERROR,
        CLIENT_ERROR,
        NETWORK_UNAVAILABLE,
        INVALID_URL,
        UNKNOWN;
        
        public String getDescription(Context context) {
            switch (this) {
                case CONNECTION_ERROR:
                    return context.getString(R.string.network_error_type_connection);
                case TIMEOUT_ERROR:
                    return context.getString(R.string.network_error_type_timeout);
                case AUTHENTICATION_ERROR:
                    return context.getString(R.string.network_error_type_authentication);
                case SERVER_ERROR:
                    return context.getString(R.string.network_error_type_server);
                case CLIENT_ERROR:
                    return context.getString(R.string.network_error_type_client);
                case NETWORK_UNAVAILABLE:
                    return context.getString(R.string.network_error_type_unavailable);
                case INVALID_URL:
                    return context.getString(R.string.network_error_type_invalid_url);
                case UNKNOWN:
                default:
                    return context.getString(R.string.network_error_type_unknown);
            }
        }
    }
} 