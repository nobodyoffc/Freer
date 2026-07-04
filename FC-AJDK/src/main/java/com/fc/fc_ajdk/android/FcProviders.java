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
    
    private static boolean bouncyCastleEnsured = false;

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
     * Ensure the full bundled Bouncy Castle is the provider registered under the
     * name "BC".
     *
     * <p>Android pre-registers a stripped-down provider named "BC", which makes the
     * usual {@code Security.addProvider(new BouncyCastleProvider())} a silent no-op
     * (a provider with that name already exists). As a result every
     * {@code Cipher.getInstance(transformation, "BC")} call across the crypto code
     * binds to Android's crippled BC, whose AES/GCM behavior differs from the full
     * Bouncy Castle used on the desktop JVM — so file encryption/decryption succeeds
     * in the IDE but fails on-device.
     *
     * <p>Replacing the existing "BC" entry with the bundled provider fixes this. It is
     * registered at the end of the provider list (lowest priority) so it is used only
     * when requested by name ("BC") and does not override Android's preferred providers
     * (e.g. Conscrypt) for unqualified lookups such as TLS. AES/GCM ciphertext is
     * standard and provider-interoperable, so data encrypted before this change still
     * decrypts. Must be called once during application startup, before any crypto runs.
     * Idempotent.
     */
    public static synchronized void ensureFullBouncyCastle() {
        if (bouncyCastleEnsured) return;
        try {
            java.security.Provider existing = java.security.Security.getProvider("BC");
            if (!(existing instanceof org.bouncycastle.jce.provider.BouncyCastleProvider)) {
                java.security.Security.removeProvider("BC");
                // addProvider appends at the lowest priority and is a no-op if a
                // provider named "BC" still exists, so only add after a successful remove.
                java.security.Security.addProvider(
                        new org.bouncycastle.jce.provider.BouncyCastleProvider());
            }
        } catch (Throwable ignored) {
            // Best effort: on the desktop JVM "BC" is typically already the full provider.
        }
        bouncyCastleEnsured = true;
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

