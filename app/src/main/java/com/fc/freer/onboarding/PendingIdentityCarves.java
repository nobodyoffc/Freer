package com.fc.freer.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * One pending carve per identity and kind: a newer carve of the same record replaces the
 * older, because only the newer one says what the chain should end up holding.
 * <p>
 * This is the only record of broadcast CID, master and home carves. The DOCK poll in
 * {@link com.fc.freer.im.ImManager} still watches for the DOCK to go live, but no screen asks
 * it whether to offer a carve.
 */
public final class PendingIdentityCarves {
    private static final String TAG = "PendingIdentityCarves";
    private static final String PREFS = "identity_pending_carves_v1";

    /** Where the records live; an interface so tests can hold them in memory. */
    public interface Store {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    private final Store store;

    public PendingIdentityCarves(Store store) {
        this.store = store;
    }

    public static PendingIdentityCarves of(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new PendingIdentityCarves(new Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }

            @Override
            public void remove(String key) {
                prefs.edit().remove(key).apply();
            }
        });
    }

    static String key(String fid, PendingIdentityCarve.Kind kind) {
        return fid + "|" + kind.name();
    }

    public void record(PendingIdentityCarve pending) {
        if (pending == null || pending.fid == null || pending.kind == null) return;
        store.put(key(pending.fid, pending.kind), JsonUtils.toJson(pending));
    }

    public PendingIdentityCarve get(String fid, PendingIdentityCarve.Kind kind) {
        if (fid == null || kind == null) return null;
        String json = store.get(key(fid, kind));
        if (json == null) return null;
        try {
            return JsonUtils.fromJson(json, PendingIdentityCarve.class);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Dropping unreadable pending carve %s: %s", key(fid, kind), e.getMessage());
            store.remove(key(fid, kind));
            return null;
        }
    }

    /** The pending carve of this kind that has not yet gone overdue, or null. */
    public PendingIdentityCarve getInFlight(String fid, PendingIdentityCarve.Kind kind, long nowMs) {
        PendingIdentityCarve pending = get(fid, kind);
        return pending != null && !pending.isOverdue(nowMs) ? pending : null;
    }

    /** Drop what {@code record} shows landed. Returns how many were cleared. */
    public int reconcile(LiveFidRecord record) {
        if (record == null || record.fid == null) return 0;
        int cleared = 0;
        for (PendingIdentityCarve.Kind kind : PendingIdentityCarve.Kind.values()) {
            PendingIdentityCarve pending = get(record.fid, kind);
            if (pending != null && pending.isLanded(record)) {
                store.remove(key(record.fid, kind));
                cleared++;
            }
        }
        return cleared;
    }
}
