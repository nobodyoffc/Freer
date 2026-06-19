package com.fc.freer.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.ToastManager;

public class ToastUtils {
    private static final String TAG = "ToastUtils";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void makeText(Context context, CharSequence text) {
        makeText(context, text, Toast.LENGTH_SHORT, type.INFO.name());
    }

    public enum type{
        INFO,
        WARNING,
        ERROR
    }

    public static void makeText(Context context, CharSequence text, int duration, String level) {
        // Guard against null context
        if (context == null) {
            TimberLogger.e(TAG, "Cannot show toast: context is null. Message: %s", text);
            return;
        }

        // Check if we're on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, show toast directly
            showToastInternal(context, text, duration, level);
        } else {
            // Post to main thread
            mainHandler.post(() -> showToastInternal(context, text, duration, level));
        }
    }

    private static void showToastInternal(Context context, CharSequence text, int duration, String level) {
        try {
            // Show the toast
            Toast.makeText(context, text, duration).show();

            // Save to ToastManager - try to get instance with context first, fallback to singleton
            try {
                ToastManager toastManager = ToastManager.getInstance();
                if (toastManager != null) {
                    toastManager.saveToastMessage(text.toString(), level);
                }
            } catch (Exception managerError) {
                TimberLogger.e(TAG, "Error saving to ToastManager: " + managerError.getMessage(), managerError);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing and saving toast: " + e.getMessage(), e);
        }
    }
    
    public static void makeText(Context context, int resId) {
        makeText(context, resId, Toast.LENGTH_SHORT, type.INFO.name());
    }

    public static void makeText(Context context, int resId, int duration, String level) {
        // Guard against null context
        if (context == null) {
            TimberLogger.e(TAG, "Cannot show toast: context is null. Resource ID: %d", resId);
            return;
        }

        try {
            String text = context.getString(resId);
            makeText(context, text, duration, level);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting string resource: " + e.getMessage(), e);
        }
    }
    
    // Convenience methods for different levels
    public static void showInfo(Context context, CharSequence text) {
        makeText(context, text, Toast.LENGTH_SHORT, type.INFO.name());
    }
    
    public static void showInfo(Context context, int resId) {
        makeText(context, resId, Toast.LENGTH_SHORT, type.INFO.name());
    }
    
    public static void showWarning(Context context, CharSequence text) {
        makeText(context, text, Toast.LENGTH_LONG, type.WARNING.name());
    }
    
    public static void showWarning(Context context, int resId) {
        makeText(context, resId, Toast.LENGTH_LONG, type.WARNING.name());
    }
    
    public static void showError(Context context, CharSequence text) {
        makeText(context, text, Toast.LENGTH_LONG, type.ERROR.name());
    }
    
    public static void showError(Context context, int resId) {
        makeText(context, resId, Toast.LENGTH_LONG, type.ERROR.name());
    }
}