package com.fc.fc_ajdk.android;

/**
 * Central registry for FC-AJDK providers.
 * Apps should initialize these providers during application startup.
 * 
 * Example usage in Android Application.onCreate():
 * <pre>
 * FcProviders.init(
 *     new FreerLogProvider(),
 *     new FreerStorageProvider(this),
 *     new FreerMessageCallback(this)
 * );
 * </pre>
 */
public final class FcProviders {
    
    private static LogProvider logProvider;
    private static StorageProvider storageProvider;
    private static MessageCallback messageCallback;
    
    private FcProviders() {
        // Utility class
    }
    
    /**
     * Initialize all providers
     */
    public static void init(LogProvider log, StorageProvider storage, MessageCallback message) {
        logProvider = log;
        storageProvider = storage;
        messageCallback = message;
    }
    
    /**
     * Initialize with log provider only
     */
    public static void initLog(LogProvider log) {
        logProvider = log;
    }
    
    /**
     * Initialize with storage provider only
     */
    public static void initStorage(StorageProvider storage) {
        storageProvider = storage;
    }
    
    /**
     * Initialize with message callback only
     */
    public static void initMessage(MessageCallback message) {
        messageCallback = message;
    }
    
    /**
     * Get the log provider
     */
    public static LogProvider log() {
        return logProvider;
    }
    
    /**
     * Get the storage provider
     */
    public static StorageProvider storage() {
        return storageProvider;
    }
    
    /**
     * Get the message callback
     */
    public static MessageCallback message() {
        return messageCallback;
    }
    
    /**
     * Check if log provider is available
     */
    public static boolean hasLog() {
        return logProvider != null;
    }
    
    /**
     * Check if storage provider is available
     */
    public static boolean hasStorage() {
        return storageProvider != null;
    }
    
    /**
     * Check if message callback is available
     */
    public static boolean hasMessage() {
        return messageCallback != null;
    }
    
    /**
     * Reset all providers (for testing)
     */
    public static void reset() {
        logProvider = null;
        storageProvider = null;
        messageCallback = null;
    }
}

