package com.fc.fc_ajdk.config;

import java.util.Map;

/**
 * Interface for providing configuration to API clients (FapiClient, ApipClient, etc.)
 * This decouples clients from specific Setting/Configure implementations.
 */
public interface ClientConfig {
    
    /**
     * Get the main FID (Freecash Identity)
     */
    String getMainFid();
    
    /**
     * Get the decrypted private key for signing
     */
    byte[] getDecryptedPrikey();
    
    /**
     * Get the public key
     */
    String getPubkey();
    
    /**
     * Get the setting map for client configuration
     */
    Map<String, Object> getSettingMap();
    
    /**
     * Get a setting value by key with default
     */
    default Object getSetting(String key, Object defaultValue) {
        Map<String, Object> map = getSettingMap();
        if (map == null) return defaultValue;
        Object value = map.get(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * Get an int setting value
     */
    default int getSettingInt(String key, int defaultValue) {
        Object value = getSetting(key, null);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    /**
     * Get a long setting value
     */
    default long getSettingLong(String key, long defaultValue) {
        Object value = getSetting(key, null);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    /**
     * Get a boolean setting value
     */
    default boolean getSettingBool(String key, boolean defaultValue) {
        Object value = getSetting(key, null);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
    
    /**
     * Get a string setting value
     */
    default String getSettingString(String key, String defaultValue) {
        Object value = getSetting(key, null);
        return value != null ? value.toString() : defaultValue;
    }
}

