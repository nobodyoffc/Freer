package com.fc.freer.utils;

import android.app.Activity;
import android.content.Intent;
import com.fc.freer.initiate.CheckPasswordActivity;

public class BackgroundTimeoutManager {
    private static final String TAG = "BackgroundTimeoutManager";
    private static final long BACKGROUND_TIMEOUT = 15000; // 15 seconds in milliseconds
    public static final String FROM_BACKGROUND_TIMEOUT = "from_background_timeout";
    public static final String ALLOW_BACK_NAVIGATION = "allow_back_navigation";

    private static long lastBackgroundTime = 0;
    private static boolean isInBackground = false;
    
    public static void onAppBackground() {
        if (!isInBackground) {
            lastBackgroundTime = System.currentTimeMillis();
            isInBackground = true;
        }
    }
    
    public static void onAppForeground(Activity activity) {
        if (isInBackground) {
            long currentTime = System.currentTimeMillis();
            long timeInBackground = currentTime - lastBackgroundTime;

            if (timeInBackground >= BACKGROUND_TIMEOUT) {
                // Reset background state BEFORE launching password check
                // to prevent triggering another check when returning
                isInBackground = false;
                launchPasswordCheck(activity);
            } else {
                isInBackground = false;
            }
        }
    }

    private static void launchPasswordCheck(Activity activity) {
        // Don't launch CheckPasswordActivity if it's already the current activity
        // This prevents double-launching when CheckPasswordActivity itself resumes
        if (activity instanceof CheckPasswordActivity) {
            return;
        }

        Intent intent = new Intent(activity, CheckPasswordActivity.class);
        intent.putExtra(FROM_BACKGROUND_TIMEOUT, true);
        // Don't allow back navigation - user must re-authenticate after timeout
        intent.putExtra(ALLOW_BACK_NAVIGATION, false);
        // No special flags needed - just launch normally on top of current activity
        activity.startActivity(intent);
    }
} 