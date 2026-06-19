package com.fc.fc_ajdk.android;

/**
 * Interface for displaying messages to users.
 * Implementations can use Android Toast, Snackbar, or other UI mechanisms.
 */
public interface MessageCallback {
    
    /**
     * Message levels
     */
    enum Level {
        INFO,
        WARNING,
        ERROR,
        SUCCESS
    }
    
    /**
     * Show a message to the user
     * @param message The message to display
     * @param level The message level
     */
    void showMessage(String message, Level level);
    
    /**
     * Show an info message
     */
    default void showInfo(String message) {
        showMessage(message, Level.INFO);
    }
    
    /**
     * Show a warning message
     */
    default void showWarning(String message) {
        showMessage(message, Level.WARNING);
    }
    
    /**
     * Show an error message
     */
    default void showError(String message) {
        showMessage(message, Level.ERROR);
    }
    
    /**
     * Show a success message
     */
    default void showSuccess(String message) {
        showMessage(message, Level.SUCCESS);
    }
}

