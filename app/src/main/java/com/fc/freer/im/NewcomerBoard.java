package com.fc.freer.im;

import android.app.Activity;
import android.content.Context;
import android.util.Base64;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.dock.DockServiceRegistry;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The helper's side of the first-FCH request board: reading it, remembering how
 * far this identity has read, and the opt-in check at login.
 *
 * {@link NobodyBoard} owns the board identity and its wire template; this owns
 * what a helper's app does with it. Reading is a DOCK fetch of the board FID's
 * inbox performed while staying signed in as the helper — never as the nobody,
 * whose key everyone has.
 */
public final class NewcomerBoard {

    private static final String TAG = "NewcomerBoard";

    /** Per-identity state: how far this FID has read, and whether to check at login. */
    private static final String PREFS = "newcomer_board";
    private static final String KEY_CURSOR = "cursor_";
    private static final String KEY_AUTO_CHECK = "auto_check_";
    private static final String KEY_DISMISSED = "dismissed_";

    /** How long a funded-or-skipped asker stays hidden. Same as the Mac app. */
    public static final long DISMISSAL_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    /**
     * Ceiling on remembered dismissals per identity: a public board is an
     * unbounded source of FIDs. Over the cap the soonest to expire go first.
     */
    private static final int MAX_DISMISSALS = 500;

    private static final int FETCH_PAGE_SIZE = 100;

    /** Bounded wait for the FAPI client at login; the board needs it connected. */
    private static final long CONNECT_WAIT_MS = 15000;
    private static final long CONNECT_POLL_MS = 500;

    /** FIDs already prompted this app run, so a return to Home doesn't re-ask. */
    private static final Set<String> promptedFids = Collections.synchronizedSet(new HashSet<>());

    private NewcomerBoard() {}

    // ── Per-identity settings ──────────────────────────────────────────

    /**
     * Whether this identity checks the board on login. Off by default: helping
     * newcomers is something a user opts into, not something the app decides.
     */
    public static boolean isAutoCheckAtLogin(Context context, String fid) {
        if (context == null || fid == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_CHECK + fid, false);
    }

    public static void setAutoCheckAtLogin(Context context, String fid, boolean enabled) {
        if (context == null || fid == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_CHECK + fid, enabled).apply();
    }

    /**
     * The newest board createTime this identity has read past. Only the viewer
     * advances it — a login check that moved it would hide from the board the
     * very requests it just prompted about.
     */
    public static long getCursor(Context context, String fid) {
        if (context == null || fid == null) return 0L;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_CURSOR + fid, 0L);
    }

    public static void saveCursor(Context context, String fid, long cursor) {
        if (context == null || fid == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_CURSOR + fid, cursor).apply();
    }

    // ── Dismissals ─────────────────────────────────────────────────────

    /**
     * A decision already taken about one asker: this identity funded them, or
     * chose not to. Persisted so the same face doesn't come back on every open
     * of the board, and it expires — skipping somebody today is not a promise
     * never to help them.
     */
    public static final class Dismissal {
        public static final String FUNDED = "funded";
        public static final String SKIPPED = "skipped";

        public final String reason;
        /** Epoch millis after which this stops hiding anything. */
        public final long until;

        public Dismissal(String reason, long until) {
            this.reason = reason;
            this.until = until;
        }

        public boolean isFunded() {
            return FUNDED.equals(reason);
        }

        boolean isActive(long now) {
            return now < until;
        }
    }

    /** The dismissals of this identity still in force, keyed by asker FID. */
    public static Map<String, Dismissal> getActiveDismissals(Context context, String fid) {
        Map<String, Dismissal> active = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Dismissal> entry : loadDismissals(context, fid).entrySet()) {
            if (entry.getValue().isActive(now)) active.put(entry.getKey(), entry.getValue());
        }
        return active;
    }

    /** Hide these askers for {@link #DISMISSAL_WINDOW_MS}. A later decision replaces an earlier one. */
    public static Dismissal dismiss(Context context, String fid, Collection<String> askers, String reason) {
        Dismissal dismissal = new Dismissal(reason, System.currentTimeMillis() + DISMISSAL_WINDOW_MS);
        if (context == null || fid == null || askers == null) return dismissal;
        Map<String, Dismissal> all = loadDismissals(context, fid);
        for (String asker : askers) all.put(asker, dismissal);
        saveDismissals(context, fid, all);
        return dismissal;
    }

    /** Un-hide one asker: the undo for a mis-tapped Ignore, which would otherwise cost a week. */
    public static void restore(Context context, String fid, String asker) {
        if (context == null || fid == null || asker == null) return;
        Map<String, Dismissal> all = loadDismissals(context, fid);
        if (all.remove(asker) != null) saveDismissals(context, fid, all);
    }

    private static Map<String, Dismissal> loadDismissals(Context context, String fid) {
        if (context == null || fid == null) return new HashMap<>();
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DISMISSED + fid, null);
        if (json == null) return new HashMap<>();
        try {
            Map<String, Dismissal> map = new Gson().fromJson(json,
                    new TypeToken<HashMap<String, Dismissal>>() {}.getType());
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** Every write prunes: drop what has lapsed, then the soonest to expire until the row fits. */
    private static void saveDismissals(Context context, String fid, Map<String, Dismissal> all) {
        long now = System.currentTimeMillis();
        List<Map.Entry<String, Dismissal>> live = new ArrayList<>();
        for (Map.Entry<String, Dismissal> entry : all.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isActive(now)) live.add(entry);
        }
        if (live.size() > MAX_DISMISSALS) {
            live.sort((a, b) -> Long.compare(b.getValue().until, a.getValue().until));
            live = live.subList(0, MAX_DISMISSALS);
        }
        Map<String, Dismissal> kept = new HashMap<>();
        for (Map.Entry<String, Dismissal> entry : live) kept.put(entry.getKey(), entry.getValue());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DISMISSED + fid, new Gson().toJson(kept)).apply();
    }

    // ── Reading the board ──────────────────────────────────────────────

    /**
     * One board fetch: the open requests it found, the newest server createTime
     * across everything retrieved, and the server's complaint when it refused.
     *
     * The error is carried rather than swallowed because a refusal and an empty
     * board are otherwise the same screen — reading a nobody's inbox is a fetch
     * for an inbox that isn't the session's own, which a DOCK that doesn't grant
     * public-board semantics rejects.
     */
    public static final class FetchResult {
        /** Open requests, newest first, minus the ones this identity already dealt with. */
        public final List<NobodyBoard.Request> requests;
        /**
         * Open requests held back by a live {@link Dismissal}, kept rather than
         * dropped so the viewer can say how many are hidden and offer them back.
         */
        public final List<Hidden> dismissed;
        public final long maxCreateTime;
        public final String error;

        FetchResult(List<NobodyBoard.Request> requests, long maxCreateTime, String error) {
            this(requests, new ArrayList<>(), maxCreateTime, error);
        }

        FetchResult(List<NobodyBoard.Request> requests, List<Hidden> dismissed,
                    long maxCreateTime, String error) {
            this.requests = requests;
            this.dismissed = dismissed;
            this.maxCreateTime = maxCreateTime;
            this.error = error;
        }

        public boolean hasRequests() {
            return !requests.isEmpty();
        }
    }

    /** An open request and the decision that hides it. */
    public static final class Hidden {
        public final NobodyBoard.Request request;
        public final Dismissal dismissal;

        public Hidden(NobodyBoard.Request request, Dismissal dismissal) {
            this.request = request;
            this.dismissal = dismissal;
        }
    }

    /**
     * Fetch board requests newer than {@code sinceCreateTime}, keeping only
     * template-matching posts (latest per requester) whose author has never
     * received FCH — a requester with a guide was already funded, or is
     * farming, and either way no longer needs answering. See {@link #fundedAmong}.
     * Last, requests that {@code liveFid} already funded or skipped are moved to
     * {@link FetchResult#dismissed}, so the viewer and the login check count the
     * same rows.
     *
     * @param limitOpen stop once this many open requests are collected (the
     *                  login check only needs 1). {@code maxCreateTime} still covers
     *                  the whole page either way — but a caller that stopped
     *                  early has not seen every open request behind that
     *                  watermark, so it must not save it as the cursor.
     */
    public static FetchResult fetch(Context context, String liveFid, long sinceCreateTime, int limitOpen) {
        List<NobodyBoard.Request> none = new ArrayList<>();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                .getClient(Service.ServiceType.FAPI_No1_NrC7);
        FapiClient boardDockClient = resolveBoardDockClient(fapiClient);
        if (boardDockClient == null) {
            return new FetchResult(none, sinceCreateTime,
                    context.getString(R.string.newcomer_requests_no_dock));
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSort(FieldNames.CREATE_TIME, Values.DESC);
        fcdsl.setSize(String.valueOf(FETCH_PAGE_SIZE));
        // Only fetch asks newer than the watermark. The range keys off the DOCK
        // server's createTime — never the sender-controlled message timestamp,
        // which a scammer could set arbitrarily high to jump every cursor.
        if (sinceCreateTime > 0) {
            if (fcdsl.getQuery() == null) fcdsl.addNewQuery();
            fcdsl.getQuery().addNewRange().addNewFields(FieldNames.CREATE_TIME);
            fcdsl.getQuery().getRange().addGt(String.valueOf(sinceCreateTime));
        }

        List<String> recipientIds = Collections.singletonList(NobodyBoard.DEFAULT_NOBODY_FID);
        FapiResponse response = boardDockClient.dockFetch(recipientIds, fcdsl);
        if (response == null || !response.isSuccess()) {
            String message = response != null ? response.getMessage() : null;
            TimberLogger.w(TAG, "dockFetch for board failed: %s",
                    message != null ? message : "null response");
            return new FetchResult(none, sinceCreateTime,
                    context.getString(R.string.newcomer_requests_fetch_failed,
                            message != null && !message.isEmpty()
                                    ? message : context.getString(R.string.network_error_type_unknown)));
        }

        List<DockItem> items = ObjectUtils.objectToList(response.getData(), DockItem.class);
        if (items == null) return new FetchResult(none, sinceCreateTime, null);

        // Latest request per FID, and the newest server time across all items
        // (so the cursor advances past scam/funded asks too, not just shown ones).
        long maxCreateTime = sinceCreateTime;
        Map<String, NobodyBoard.Request> byFid = new LinkedHashMap<>();
        for (DockItem item : items) {
            if (item.getCreateTime() != null && item.getCreateTime() > maxCreateTime) {
                maxCreateTime = item.getCreateTime();
            }
            NobodyBoard.Request request = decodeRequest(item);
            if (request == null) continue;
            NobodyBoard.Request existing = byFid.get(request.requesterFid);
            if (existing == null || request.timestamp > existing.timestamp) {
                byFid.put(request.requesterFid, request);
            }
        }

        List<NobodyBoard.Request> latest = new ArrayList<>(byFid.values());
        latest.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        Set<String> funded = fundedAmong(fapiClient, byFid.keySet());
        Map<String, Dismissal> dismissals = getActiveDismissals(context, liveFid);
        List<NobodyBoard.Request> open = new ArrayList<>();
        List<Hidden> hidden = new ArrayList<>();
        for (NobodyBoard.Request request : latest) {
            if (funded.contains(request.requesterFid)) continue;
            Dismissal dismissal = dismissals.get(request.requesterFid);
            if (dismissal != null) {
                hidden.add(new Hidden(request, dismissal));
            } else if (open.size() < limitOpen) {
                open.add(request);
            }
        }
        return new FetchResult(open, hidden, maxCreateTime, null);
    }

    /**
     * Which of {@code fids} have already had their first FCH, in one
     * {@code base.freerByIds} call. A {@code guide} (the FID that sent the first
     * FCH) is set once and never cleared, so it still counts a requester who has
     * since spent down to zero — a balance check alone would put them back on
     * the board. A failed lookup answers "none": unknown counts as not funded.
     */
    public static Set<String> fundedAmong(Collection<String> fids) {
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                .getClient(Service.ServiceType.FAPI_No1_NrC7);
        return fundedAmong(fapiClient, fids);
    }

    private static Set<String> fundedAmong(FapiClient fapiClient, Collection<String> fids) {
        Set<String> funded = new HashSet<>();
        if (fapiClient == null || fids == null || fids.isEmpty()) return funded;
        try {
            Map<String, Freer> freers = fapiClient.freerByIds(new ArrayList<>(fids));
            if (freers == null) return funded;
            for (Map.Entry<String, Freer> entry : freers.entrySet()) {
                if (hasHadFirstFch(entry.getValue())) funded.add(entry.getKey());
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Freshness check failed: %s", e.getMessage());
        }
        return funded;
    }

    private static boolean hasHadFirstFch(Freer freer) {
        if (freer == null) return false;
        if (freer.getGuide() != null && !freer.getGuide().isEmpty()) return true;
        return freer.getBalance() != null && freer.getBalance() > 0;
    }

    private static NobodyBoard.Request decodeRequest(DockItem item) {
        if (item == null || item.getDataBase64() == null || item.getDataBase64().isEmpty()) return null;
        try {
            byte[] data = Base64.decode(item.getDataBase64(), Base64.NO_WRAP);
            // Sealed to the board's pubkey by the sender, so it has to be opened
            // with the board's (public) private key before there is any content
            // to match the template against.
            ImMessage message = NobodyBoard.openBoardMessage(data);
            if (message == null) return null;
            // Use the DOCK server's createTime as the authoritative timestamp:
            // it can't be forged by the sender, so ordering, dedup, and the
            // cursor all stay honest. Fall back to the message only if absent.
            Long timestamp = item.getCreateTime() != null
                    ? item.getCreateTime() : message.getTimestamp();
            return NobodyBoard.parseFirstFchRequest(message.getContent(), timestamp);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve and connect to the DOCK server declared in the nobody freer's
     * on-chain home, reusing the ImManager's registry so an existing connection
     * is shared.
     */
    private static FapiClient resolveBoardDockClient(FapiClient fapiClient) {
        if (fapiClient == null) return null;

        Freer boardFreer = fapiClient.getFreer(NobodyBoard.DEFAULT_NOBODY_FID);
        if (boardFreer == null || boardFreer.getHome() == null) return null;

        String dockUrl = fapiClient.getHomeServiceResolver()
                .resolveDockFromHome(boardFreer.getHome(), fapiClient);
        if (dockUrl == null) return null;

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        ImManager imManager = setting != null ? setting.getImManager() : null;
        DockServiceRegistry registry = imManager != null ? imManager.getDockRegistry() : null;
        if (registry == null) return null;

        FapiClient client = registry.getClientForDock(dockUrl);
        if (client == null) {
            client = registry.retryBootstrap(dockUrl);
        }
        return client;
    }

    // ── Login check ────────────────────────────────────────────────────

    /**
     * When this identity opted in, look for board requests it hasn't read yet
     * and offer to open the board. Silent when the setting is off, when nothing
     * new is waiting, or when the board can't be reached — a login is the wrong
     * moment to report a server problem the user didn't ask about.
     *
     * <p>Call once per login, from a background thread or not; the fetch runs on
     * its own thread and the prompt is posted to the UI thread.
     */
    public static void maybeCheckAtLogin(Activity activity) {
        if (activity == null) return;
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        String fid = setting != null ? setting.getMainFid() : null;
        if (fid == null) return;
        if (!isAutoCheckAtLogin(activity, fid)) return;
        if (!promptedFids.add(fid)) return;

        new Thread(() -> {
            try {
                if (!awaitConnectedClient()) {
                    TimberLogger.d(TAG, "Skipping login board check: no connected FAPI client");
                    return;
                }
                // One open request is all the prompt says.
                FetchResult result = fetch(activity, fid, getCursor(activity, fid), 1);
                if (result.error != null || !result.hasRequests()) return;
                activity.runOnUiThread(() -> showPrompt(activity, fid));
            } catch (Exception e) {
                TimberLogger.w(TAG, "Login board check failed: %s", e.getMessage());
            }
        }, "newcomer-board-login-check").start();
    }

    /** Wait briefly for the FAPI client to finish its bootstrap after login. */
    private static boolean awaitConnectedClient() {
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                .getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) return false;
        long deadline = System.currentTimeMillis() + CONNECT_WAIT_MS;
        while (!fapiClient.isConnected() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(CONNECT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return fapiClient.isConnected();
    }

    private static void showPrompt(Activity activity, String fid) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        DialogUtils.show(new AlertDialog.Builder(activity)
                .setTitle(R.string.newcomer_board_login_prompt_title)
                .setMessage(R.string.newcomer_board_login_prompt)
                .setPositiveButton(R.string.check, (d, which) ->
                        activity.startActivity(new android.content.Intent(
                                activity, NewcomerRequestsActivity.class)))
                .setNegativeButton(R.string.later, null)
                // "Never" is the same switch the settings screen shows, so
                // turning it off here is visible where it was turned on.
                .setNeutralButton(R.string.never, (d, which) ->
                        setAutoCheckAtLogin(activity, fid, false)));
    }
}
