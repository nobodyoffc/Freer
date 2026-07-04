package com.fc.freer.utils;

import android.app.Activity;
import android.content.Intent;

import com.fc.freer.MainActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.SettingManager;
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
     * @return true when BOTH the in-memory symkey ({@link ConfigureManager}) and a
     *         current {@link com.fc.freer.model.Setting} ({@code SettingManager}) are
     *         present. This is the requirement for post-CID screens such as
     *         {@code HomeActivity} that need a selected identity.
     *
     *         <p>It is possible for the symkey to be present while the current
     *         setting is null: after a long background sleep the auto-lock
     *         ({@code BackgroundTimeoutManager}) pushes CheckPasswordActivity, and if
     *         the process is then killed and restored, re-entering the password
     *         restores only the symkey via its "resume existing activity" fast-path -
     *         the current setting (and FidManager) are never re-established. A guard
     *         that only checks the symkey would let HomeActivity render with no
     *         setting, leaving the FID card stuck on "Loading...".
     */
    public static boolean isSessionWithSettingValid() {
        return isSessionValid()
                && SettingManager.getInstance().getCurrentSetting() != null;
    }

    /**
     * Like {@link #ensureSession(Activity)}, but also requires a current setting.
     * Use this for post-CID screens that cannot function without a selected identity
     * (e.g. {@code HomeActivity}). When the setting is missing, the user is routed
     * back through the full authentication + identity-selection flow.
     *
     * @param activity the activity to guard
     * @return true if a valid session with a current setting exists; false if the
     *         caller was redirected and should return from onCreate immediately.
     */
    public static boolean ensureSessionWithSetting(Activity activity) {
        if (isSessionWithSettingValid()) {
            return true;
        }
        return redirectToAuth(activity, "session or current setting lost "
                + "(likely process death in background)");
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
        return redirectToAuth(activity, "session lost (likely process death in background)");
    }

    private static boolean redirectToAuth(Activity activity, String reason) {
        TimberLogger.w(TAG, "%s; redirecting %s to re-authentication",
                reason, activity.getClass().getSimpleName());

        Intent intent = new Intent(activity, MainActivity.class);
        // Start a fresh task and clear the restored (stale) back stack so the
        // user re-authenticates from a clean state.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
        return false;
    }
}
