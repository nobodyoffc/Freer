package com.fc.freer.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * When each FID last asked for its first FCH: posted to the board or copied its FID to hand
 * out. Nothing on the chain shows an ask, so this is the only record, and it only moves the
 * first-FCH step to pending; the coins arriving still tick it.
 */
public final class FirstFchAsks {
    private static final String PREFS = "first_fch_asks_v1";

    private FirstFchAsks() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void record(Context context, String fid) {
        if (fid == null) return;
        prefs(context).edit().putLong(fid, System.currentTimeMillis()).apply();
    }

    /** When {@code fid} last asked, or null if it never did. */
    public static Long askedAt(Context context, String fid) {
        if (fid == null) return null;
        long at = prefs(context).getLong(fid, 0L);
        return at > 0 ? at : null;
    }
}
