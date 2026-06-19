package com.fc.fc_ajdk.android;

import java.io.File;

/**
 * Interface for storage operations in FC-AJDK.
 * Implementations provide platform-specific file/storage access.
 */
public interface StorageProvider {
    
    /**
     * Get the application's private data directory
     */
    String getDataDir();
    
    /**
     * Get the cache directory
     */
    String getCacheDir();
    
    /**
     * Get a subdirectory within the data directory
     */
    default String getSubDir(String name) {
        String dataDir = getDataDir();
        if (dataDir == null) return null;
        String path = dataDir + File.separator + name;
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return path;
    }
    
    /**
     * Check if a file exists
     */
    default boolean fileExists(String path) {
        return path != null && new File(path).exists();
    }
    
    /**
     * Delete a file
     */
    default boolean deleteFile(String path) {
        if (path == null) return false;
        File file = new File(path);
        return file.exists() && file.delete();
    }
    
    /**
     * Get the FUDP data directory
     */
    default String getFudpDataDir() {
        return getSubDir("fudp");
    }
    
    /**
     * Get the config directory
     */
    default String getConfigDir() {
        return getSubDir("config");
    }
}

