package com.fc.freer.data;

import android.content.Context;

/**
 * Persisted, time-boxed suppression for the combined server-setup prompt (DOCK + DISK).
 * <p>
 * The prompt ({@code ChannelSetupDialog}) re-pops whenever EITHER half is unconfigured. Right
 * after the user broadcasts a server-setup TX, neither half is on-chain yet (a few minutes to
 * confirm) and a half the user intentionally left unset (e.g. the current server offers no disk
 * component) never becomes configured — so without this flag the prompt nags on every return to
 * Home and on every funding refresh, including across an app restart during the confirmation
 * window.
 * <p>
 * This flag is set when the TX is broadcast and suppresses the prompt until it expires. Unlike
 * {@code ImManager.channelSetupDismissed} it is independent of ImManager (which may not be ready
 * early in Home startup) and survives process death. The per-service pending flags
 * ({@link DiskHomeManager#markRegistrationPending} for DISK, ImManager's DOCK poll) still track
 * each half's on-chain confirmation independently and drive the Talk/Data tile enablement.
 */
public final class ServerSetupState {
    private static final String PREFS = "server_setup_state";
    private static final String KEY_TX_AT = "setup_tx_at_";
    /** Match the DOCK/DISK confirmation windows so all three expire together. */
    private static final long PENDING_MS = 15 * 60_000L;

    private ServerSetupState() {}

    /** Record that a server-setup TX was just broadcast for this FID. */
    public static void markTxBroadcast(Context ctx, String fid) {
        if (ctx == null || fid == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_TX_AT + fid, System.currentTimeMillis()).apply();
    }

    /**
     * @return true if a server-setup TX is still within its unconfirmed window for this FID.
     * Expired records are cleared as a side effect.
     */
    public static boolean isTxPending(Context ctx, String fid) {
        if (ctx == null || fid == null) return false;
        long at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TX_AT + fid, 0L);
        if (at <= 0L) return false;
        if (System.currentTimeMillis() - at >= PENDING_MS) {
            clear(ctx, fid);
            return false;
        }
        return true;
    }

    /** Clear the pending flag once the setup has confirmed (or to reset it). */
    public static void clear(Context ctx, String fid) {
        if (ctx == null || fid == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TX_AT + fid).apply();
    }
}
