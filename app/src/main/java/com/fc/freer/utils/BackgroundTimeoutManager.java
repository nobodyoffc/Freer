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
    // Number of started (visible) activities. During an in-app transition the new
    // activity is started BEFORE the old one is stopped, so this count only reaches
    // zero when the whole app actually leaves the screen (home button, screen off,
    // task switch). Tracking pause events instead would stamp a "background" time on
    // every in-app transition, and any transition slower than the timeout (heavy
    // work in a result callback, a system dialog such as the camera permission
    // prompt) would spuriously re-lock the app. All counters are updated and read
    // on the main thread from the activity lifecycle, so plain ints are safe.
    private static int startedActivities = 0;
    // Number of CheckPasswordActivity instances currently alive (created but not
    // yet destroyed).
    private static int passwordCheckInstances = 0;

    /** Called from CheckPasswordActivity.onCreate. */
    public static void onPasswordCheckCreated() {
        passwordCheckInstances++;
    }

    /** Called from CheckPasswordActivity.onDestroy. */
    public static void onPasswordCheckDestroyed() {
        if (passwordCheckInstances > 0) {
            passwordCheckInstances--;
        }
    }

    /** Called from Application.ActivityLifecycleCallbacks.onActivityStarted. */
    public static void onActivityStarted() {
        startedActivities++;
    }

    /** Called from Application.ActivityLifecycleCallbacks.onActivityStopped. */
    public static void onActivityStopped() {
        if (startedActivities > 0) {
            startedActivities--;
        }
        if (startedActivities == 0) {
            onAppBackground();
        }
    }

    private static void onAppBackground() {
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

        // Don't stack a second password screen if one is already alive but not the
        // resuming activity. This covers the window where the user has just submitted
        // the password (CheckPasswordActivity is finishing while the underlying
        // activity resumes).
        if (passwordCheckInstances > 0) {
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