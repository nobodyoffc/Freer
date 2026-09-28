package com.fc.freer.im.dock;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically fetches DockItems from all registered DOCK servers.
 * <p>
 * Each DOCK is due by its own interval, checked once a second: an open
 * chat's every 3 s; my own DOCK, where calls and invitations land, and every
 * other DOCK at the intervals {@link #setIntervals} gives, which follow the
 * user's {@link DockCheckLevel} and whether the app is in front (VOICE_SPEC
 * §6.3). A DOCK is never fetched twice at once: its cursor would race.
 * <p>
 * For each DOCK URL, fetches all recipient IDs in a single dock.fetch call,
 * tracks pagination cursors per DOCK URL, and delegates items to a DockItemRouter.
 */
public class DockFetchScheduler {
    private static final String TAG = "DockFetchScheduler";

    public static final long ACTIVE_INTERVAL_MS = 10_000;
    public static final long NORMAL_INTERVAL_MS = 60_000;
    public static final long BACKGROUND_INTERVAL_MS = 3 * 60_000;
    public static final long PRIORITY_INTERVAL_MS = 3_000;
    private static final int FETCH_PAGE_SIZE = 50;

    public enum FetchLayer {
        ACTIVE, NORMAL, BACKGROUND
    }

    private static final long TICK_MS = 1_000;

    /**
     * Callback for fetched dock items.
     */
    public interface FetchCallback {
        void onItemsFetched(List<DockItem> items, String dockUrl);
        void onFetchError(String dockUrl, String error);
        /** A fetch pass reached the DOCK and finished, whether or not it found anything. */
        default void onFetchSucceeded(String dockUrl) {}
    }

    private final DockServiceRegistry registry;
    private FetchCallback callback;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService fetchExecutor;
    private ScheduledFuture<?> scheduledTask;

    private volatile FetchLayer currentLayer = FetchLayer.NORMAL;
    private volatile boolean running = false;
    private final String liveFid;
    /** My own DOCK's interval, and every other's; set by the owner from the check level. */
    private volatile long ownIntervalMs = NORMAL_INTERVAL_MS, othersIntervalMs = NORMAL_INTERVAL_MS;
    /** When each DOCK was last asked; 0 until it is. */
    private final Map<String, Long> lastFetchMs = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final Set<String> priorityDockUrls = ConcurrentHashMap.newKeySet();

    private static final String MMKV_PREFIX = "dock_cursors_";
    private static final String CURSORS_KEY = "cursors";

    /**
     * Cursors per dock URL: [createTime, id] of the last fetched item.
     * Persisted to MMKV so they survive app restarts.
     */
    private final Map<String, List<String>> dockCursors = new ConcurrentHashMap<>();
    private final MMKV cursorStore;
    private final Gson gson = new Gson();

    public DockFetchScheduler(DockServiceRegistry registry, String liveFid) {
        this.registry = registry;
        this.liveFid = liveFid;
        this.cursorStore = MMKV.mmkvWithID(MMKV_PREFIX + liveFid, MMKV.SINGLE_PROCESS_MODE);
        loadCursors();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DockFetchScheduler");
            t.setDaemon(true);
            return t;
        });
        this.fetchExecutor = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "DockFetch-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void setCallback(FetchCallback callback) {
        this.callback = callback;
    }

    /**
     * Start the scheduler with the current layer interval.
     * Also activates any priority docks that were added before the scheduler was started.
     */
    public void start() {
        if (running) return;
        running = true;
        scheduledTask = scheduler.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        TimberLogger.i(TAG, "Started with layer: %s, pending priority docks: %d",
                currentLayer, priorityDockUrls.size());
    }

    /**
     * Stop the scheduler.
     */
    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        priorityDockUrls.clear();
        TimberLogger.i(TAG, "Stopped");
    }

    /**
     * How often to check my own DOCK and every other DOCK (not an open chat's,
     * which is always 3 s). A shorter interval than before takes effect at the
     * next tick.
     */
    public void setIntervals(long ownMs, long othersMs) {
        if (ownMs == ownIntervalMs && othersMs == othersIntervalMs) return;
        ownIntervalMs = ownMs;
        othersIntervalMs = othersMs;
        TimberLogger.i(TAG, "Intervals: own DOCK %d s, others %d s", ownMs / 1000, othersMs / 1000);
    }

    /**
     * Change the fetch layer (adjusts polling interval).
     * @deprecated the intervals come from {@link #setIntervals}; kept for callers that still set it
     */
    public void setLayer(FetchLayer layer) {
        if (layer == currentLayer) return;
        currentLayer = layer;
        TimberLogger.d(TAG, "Layer changed to: %s", layer);
    }

    /**
     * Set a dock URL as priority so it gets fetched every {@link #PRIORITY_INTERVAL_MS} (3s).
     * The same dock URL is excluded from the normal fetch cycle to avoid duplicate requests.
     */
    public void setPriorityDock(String dockUrl) {
        if (dockUrl == null) return;
        boolean added = priorityDockUrls.add(dockUrl);
        if (running && added) lastFetchMs.remove(dockUrl); // due at the next tick
        TimberLogger.d(TAG, "Priority dock added: %s (total: %d)", dockUrl, priorityDockUrls.size());
    }

    /**
     * Remove all priority docks. They return to the normal fetch cycle.
     */
    public void clearPriorityDock() {
        priorityDockUrls.clear();
        TimberLogger.d(TAG, "Priority docks cleared");
    }

    /**
     * Trigger an immediate fetch (e.g. when entering a chat).
     */
    public void fetchNow() {
        if (!running) return;
        fetchExecutor.execute(this::doFetchAll);
    }

    public FetchLayer getCurrentLayer() {
        return currentLayer;
    }

    public boolean isRunning() {
        return running;
    }

    /** Once a second: fetch every DOCK that is due and not already being fetched. */
    private void tick() {
        if (!running) return;
        try {
            registry.ensureSelfDockRegistered();
            Map<String, List<DockServiceRegistry.DockEntry>> grouped = registry.getEntriesByDockUrl();
            String own = registry.getDockUrl("p2p_self", liveFid);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, List<DockServiceRegistry.DockEntry>> entry : grouped.entrySet()) {
                String dockUrl = entry.getKey();
                long every = priorityDockUrls.contains(dockUrl) ? PRIORITY_INTERVAL_MS
                        : dockUrl.equals(own) ? ownIntervalMs : othersIntervalMs;
                Long last = lastFetchMs.get(dockUrl);
                if (last != null && now - last < every) continue;
                fetch(dockUrl, entry.getValue());
            }
        } catch (RuntimeException e) {
            TimberLogger.w(TAG, "tick: %s", e.getMessage());
        }
    }

    private void doFetchAll() {
        if (!running) return;
        registry.ensureSelfDockRegistered();
        Map<String, List<DockServiceRegistry.DockEntry>> grouped = registry.getEntriesByDockUrl();
        if (grouped.isEmpty()) {
            TimberLogger.d(TAG, "No DOCK servers registered, skipping fetch");
            return;
        }
        // Failed docks are not skipped here: fetchFromDock attempts a
        // cooldown-guarded reconnect so a dock that died during sleep recovers.
        for (Map.Entry<String, List<DockServiceRegistry.DockEntry>> entry : grouped.entrySet()) {
            fetch(entry.getKey(), entry.getValue());
        }
    }

    /** One fetch of one DOCK, unless one is already running: two would race on its cursor. */
    private void fetch(String dockUrl, List<DockServiceRegistry.DockEntry> entries) {
        if (!inFlight.add(dockUrl)) return;
        lastFetchMs.put(dockUrl, System.currentTimeMillis());
        try {
            fetchExecutor.execute(() -> {
                try {
                    fetchFromDock(dockUrl, entries);
                } finally {
                    inFlight.remove(dockUrl);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            inFlight.remove(dockUrl);
        }
    }

    private void fetchFromDock(String dockUrl, List<DockServiceRegistry.DockEntry> entries) {
        FapiClient client = registry.getClientForDock(dockUrl);
        if (client == null || registry.isFailed(dockUrl)) {
            // No client yet, or the connection died (e.g. during a long sleep).
            // Attempt a cooldown-guarded reconnect instead of skipping forever.
            client = registry.recoverDock(dockUrl);
            if (client == null) {
                return; // still cooling down or reconnect failed; retry next cycle
            }
            TimberLogger.i(TAG, "Re-established DOCK connection for %s", dockUrl);
        }

        List<String> recipientIds = new ArrayList<>();
        for (DockServiceRegistry.DockEntry e : entries) {
            // P2P messages always rest in the recipient's own DOCK (direct put or
            // dock-to-dock forward), so partners' docks are never polled for P2P.
            if ("p2p".equals(e.entityType)) continue;
            if (!recipientIds.contains(e.recipientId)) {
                recipientIds.add(e.recipientId);
            }
        }

        if (recipientIds.isEmpty()) return;

        try {
            List<DockItem> allItems = new ArrayList<>();
            List<String> cursor = dockCursors.get(dockUrl);
            boolean hasMore = true;
            boolean reached = true;

            while (hasMore && running) {
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addSort(FieldNames.CREATE_TIME, Values.ASC);
                fcdsl.addSort(FieldNames.ID,Values.ASC);
                fcdsl.setSize(String.valueOf(FETCH_PAGE_SIZE));
                if (cursor != null) {
                    fcdsl.setAfter(cursor);
                }

                FapiResponse response = client.dockFetch(recipientIds, fcdsl);
                if (response == null || !response.isSuccess()) {
                    String errorMsg = response != null ? response.getMessage() : "null response";
                    TimberLogger.w(TAG, "dockFetch failed for %s: %s", dockUrl, errorMsg);
                    // Do NOT clear the cursor on transient errors — keep it so the
                    // next successful fetch resumes from where we left off rather
                    // than re-delivering up to 7 days of already-seen messages.
                    registry.invalidateClient(dockUrl);
                    registry.markFailed(dockUrl);
                    if (callback != null) {
                        callback.onFetchError(dockUrl, errorMsg);
                    }
                    reached = false;
                    break;
                }

                List<DockItem> items = ObjectUtils.objectToList(response.getData(), DockItem.class);
                if (items == null || items.isEmpty()) {
                    hasMore = false;
                } else {
                    allItems.addAll(items);

                    if (response.getLast() != null && !response.getLast().isEmpty()) {
                        cursor = response.getLast();
                        dockCursors.put(dockUrl, cursor);
                        saveCursors();
                    }

                    if (items.size() < FETCH_PAGE_SIZE) {
                        hasMore = false;
                    }
                }
            }

            if (!allItems.isEmpty() && callback != null) {
                callback.onItemsFetched(allItems, dockUrl);
            }
            if (reached && running && callback != null) {
                callback.onFetchSucceeded(dockUrl);
            }

            if (!allItems.isEmpty()) {
                TimberLogger.d(TAG, "Fetched %d new items from DOCK %s", allItems.size(), dockUrl);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching from DOCK %s: %s", dockUrl, e.getMessage());
            // Do NOT clear the cursor on exceptions — same reason as the error-response
            // path above: preserve progress so re-delivery doesn't occur after recovery.
            registry.invalidateClient(dockUrl);
            registry.markFailed(dockUrl);
            if (callback != null) {
                callback.onFetchError(dockUrl, e.getMessage());
            }
        }
    }

    /**
     * Reset cursor for a specific DOCK, forcing a full re-fetch on the next cycle.
     */
    public void resetCursor(String dockUrl) {
        dockCursors.remove(dockUrl);
        saveCursors();
    }

    /**
     * Reset all cursors, forcing a full re-fetch for every DOCK.
     */
    public void resetAllCursors() {
        dockCursors.clear();
        saveCursors();
    }

    private void loadCursors() {
        try {
            String json = cursorStore.decodeString(CURSORS_KEY);
            if (json != null) {
                Map<String, List<String>> saved = gson.fromJson(json,
                        new TypeToken<Map<String, List<String>>>(){}.getType());
                if (saved != null) {
                    dockCursors.putAll(saved);
                    TimberLogger.d(TAG, "Loaded %d persisted dock cursors", saved.size());
                }
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to load dock cursors: %s", e.getMessage());
        }
    }

    private void saveCursors() {
        try {
            String json = gson.toJson(dockCursors);
            cursorStore.encode(CURSORS_KEY, json);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to save dock cursors: %s", e.getMessage());
        }
    }
}
