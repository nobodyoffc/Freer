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
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.Collections;
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
        public final List<NobodyBoard.Request> requests;
        public final long maxCreateTime;
        public final String error;

        FetchResult(List<NobodyBoard.Request> requests, long maxCreateTime, String error) {
            this.requests = requests;
            this.maxCreateTime = maxCreateTime;
            this.error = error;
        }

        public boolean hasRequests() {
            return !requests.isEmpty();
        }
    }

    /**
     * Fetch board requests newer than {@code sinceCreateTime}, keeping only
     * template-matching posts (latest per requester) whose author still has no
     * on-chain balance — a requester with a balance was already funded, or is
     * farming, and either way no longer needs answering.
     *
     * @param limitOpen stop once this many open requests are confirmed. The
     *                  freshness check is one API call per requester, so the
     *                  login check asks for 1 rather than paying for a page of
     *                  lookups it won't show. {@code maxCreateTime} still covers
     *                  the whole page either way — but a caller that stopped
     *                  early has not seen every open request behind that
     *                  watermark, so it must not save it as the cursor.
     */
    public static FetchResult fetch(Context context, long sinceCreateTime, int limitOpen) {
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

        List<NobodyBoard.Request> open = new ArrayList<>();
        for (NobodyBoard.Request request : byFid.values()) {
            if (open.size() >= limitOpen) break;
            if (isAlreadyFunded(fapiClient, request.requesterFid)) continue;
            open.add(request);
        }
        return new FetchResult(open, maxCreateTime, null);
    }

    /** True when the requester holds on-chain balance; unknown counts as not funded. */
    private static boolean isAlreadyFunded(FapiClient fapiClient, String requesterFid) {
        try {
            Freer requester = fapiClient.getFreer(requesterFid);
            return requester != null && requester.getBalance() != null && requester.getBalance() > 0;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Freshness check failed for %s: %s", requesterFid, e.getMessage());
            return false;
        }
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
                // One open request is all the prompt says, so pay for one
                // freshness lookup rather than a page of them.
                FetchResult result = fetch(activity, getCursor(activity, fid), 1);
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
        new AlertDialog.Builder(activity)
                .setTitle(R.string.newcomer_board_login_prompt_title)
                .setMessage(R.string.newcomer_board_login_prompt)
                .setPositiveButton(R.string.check, (d, which) ->
                        activity.startActivity(new android.content.Intent(
                                activity, NewcomerRequestsActivity.class)))
                .setNegativeButton(R.string.later, null)
                // "Never" is the same switch the settings screen shows, so
                // turning it off here is visible where it was turned on.
                .setNeutralButton(R.string.never, (d, which) ->
                        setAutoCheckAtLogin(activity, fid, false))
                .show();
    }
}
