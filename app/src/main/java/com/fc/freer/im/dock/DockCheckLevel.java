package com.fc.freer.im.dock;

import android.content.Context;

/**
 * How often this device checks its DOCKs (VOICE_SPEC §6.3, Decision 22): the
 * user's choice between prompt rings and messages, and battery and server
 * load. An open chat's DOCK is checked every 3 s at every level.
 * <p>
 * The background intervals apply only while <i>Available for calls</i> keeps
 * the process alive; without it Android pauses the app, and every level
 * checks every 3 minutes, when it runs at all.
 */
public enum DockCheckLevel {
    FAST(3_000, 5_000, 5_000, 30_000),
    NORMAL(3_000, 10_000, 10_000, 60_000),
    BATTERY_SAVER(10_000, 60_000, 180_000, 180_000);

    /** In the background without Available for calls. */
    public static final long UNKEPT_BACKGROUND_MS = 180_000;

    private static final String PREFS = "dock_checking";
    private static final String KEY = "level";

    /** My own DOCK, where calls, invitations and 1:1 messages land, while the app is in front. */
    public final long ownForegroundMs;
    /** Every Room, Team and Square DOCK while the app is in front. */
    public final long groupsForegroundMs;
    public final long ownBackgroundMs;
    public final long groupsBackgroundMs;

    DockCheckLevel(long ownForegroundMs, long groupsForegroundMs, long ownBackgroundMs, long groupsBackgroundMs) {
        this.ownForegroundMs = ownForegroundMs;
        this.groupsForegroundMs = groupsForegroundMs;
        this.ownBackgroundMs = ownBackgroundMs;
        this.groupsBackgroundMs = groupsBackgroundMs;
    }

    public static DockCheckLevel get(Context context) {
        String name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, NORMAL.name());
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }

    public static void set(Context context, DockCheckLevel level) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name()).apply();
    }
}
