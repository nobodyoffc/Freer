package com.fc.fc_ajdk.android;

/**
 * Interface for logging in FC-AJDK.
 * Implementations can use Android Timber, SLF4J, or other logging frameworks.
 */
public interface LogProvider {
    
    /**
     * Log a debug message
     */
    void d(String tag, String message);
    
    /**
     * Log a debug message with format args
     */
    void d(String tag, String format, Object... args);
    
    /**
     * Log an info message
     */
    void i(String tag, String message);
    
    /**
     * Log an info message with format args
     */
    void i(String tag, String format, Object... args);
    
    /**
     * Log a warning message
     */
    void w(String tag, String message);
    
    /**
     * Log a warning message with format args
     */
    void w(String tag, String format, Object... args);
    
    /**
     * Log an error message
     */
    void e(String tag, String message);
    
    /**
     * Log an error message with format args
     */
    void e(String tag, String format, Object... args);
    
    /**
     * Log an error message with throwable
     */
    void e(String tag, String message, Throwable t);
    
    /**
     * Log an error message with format args and throwable
     */
    void e(String tag, Throwable t, String format, Object... args);
}

