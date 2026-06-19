package com.fc.freer.utils;

import android.app.Activity;
import android.content.Intent;

import com.fc.freer.MainActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * Guards against the app process being killed while in the background.
 *
 * When Android reclaims the process during a long background period, it later
 * restores the task and recreates the top activity directly - bypassing the
 * normal authentication flow (MainActivity -> CheckPassword -> ChooseCid).
 * The in-memory session state (the decrypted symkey held by
 * {@link ConfigureManager} and the current {@code Setting} held by
 * {@code SettingManager}) lives only in process memory, so after a process
 * death it is null. Any post-authentication activity that touches that state
 * will then crash with a NullPointerException on resume.
 *
 * Post-authentication activities call {@link #ensureSession(Activity)} at the
 * very start of onCreate. If the session was lost, the user is sent back to
 * re-authenticate instead of crashing.
 */
public final class SessionGuard {
    private static final String TAG = "SessionGuard";

    private SessionGuard() {}

    /**
     * @return true when the in-memory session required by post-auth screens is
     *         present. The decrypted symkey held by {@link ConfigureManager} is
     *         the authoritative "app is unlocked" signal: it is set right after
     *         password verification and is lost on process death. We deliberately
     *         do NOT require a current {@link com.fc.freer.model.Setting} here,
     *         because legitimate onboarding/key-management screens (e.g. creating
     *         keys before a CID is selected) run with a valid symkey but no
     *         current setting.
     */
    public static boolean isSessionValid() {
        return ConfigureManager.getInstance().getConfigure() != null;
    }

    /**
     * Ensures a valid in-memory session exists. If it does not (typically after
     * the process was killed in the background), the user is redirected to the
     * authentication flow and the calling activity is finished.
     *
     * @param activity the activity to guard
     * @return true if the session is valid and the caller may continue;
     *         false if the caller was redirected and should return from onCreate
     *         immediately without touching session state.
     */
    public static boolean ensureSession(Activity activity) {
        if (isSessionValid()) {
            return true;
        }

        TimberLogger.w(TAG, "Session lost (likely process death in background); "
                + "redirecting %s to re-authentication", activity.getClass().getSimpleName());

        Intent intent = new Intent(activity, MainActivity.class);
        // Start a fresh task and clear the restored (stale) back stack so the
        // user re-authenticates from a clean state.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
        return false;
    }
}
