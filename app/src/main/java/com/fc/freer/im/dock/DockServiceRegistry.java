package com.fc.freer.im.dock;

import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;

import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.fcData.TalkPartner;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages mappings from entities (TalkPartner, Room, Team, Group) to their DOCK service URLs.
 * Also caches FapiClient instances per unique DOCK URL for efficient reuse.
 * <p>
 * Each entity (peer, room, team, group) may use a different DOCK server,
 * so the scheduler must fetch from each distinct DOCK independently.
 */
public class DockServiceRegistry {
    private static final String TAG = "DockServiceRegistry";

    /**
     * Represents a registered entity with its DOCK URL and recipient ID for fetching.
     */
    public static class DockEntry {
        public final String entityId;
        public final String entityType; // "p2p", "room", "team", "square"
        public final String dockUrl;
        public final String recipientId; // FID for P2P (the user's own FID), entity ID for others

        public DockEntry(String entityId, String entityType, String dockUrl, String recipientId) {
            this.entityId = entityId;
            this.entityType = entityType;
            this.dockUrl = dockUrl;
            this.recipientId = recipientId;
        }
    }

    private final String liveFid;
    private final HomeServiceResolver resolver;

    private final Map<String, DockEntry> entityToDock = new ConcurrentHashMap<>();

    private final Map<String, FapiClient> dockClients = new ConcurrentHashMap<>();

    /** DOCK URLs that failed bootstrap — not retried until explicitly cleared. */
    private final Set<String> failedDocks = ConcurrentHashMap.newKeySet();

    /** Timestamp of last retry attempt per DOCK URL, to avoid redundant retries. */
    private final Map<String, Long> lastRetryAttempt = new ConcurrentHashMap<>();
    private static final long RETRY_COOLDOWN_MS = 60_000; // 1 minute between retries

    /**
     * Live FUDP node used as a donor for re-bootstrapping when every cached dock
     * client has been invalidated (e.g. all connections died during a long sleep),
     * leaving no peer client to borrow a node from.
     */
    private volatile FudpNode recoveryNode;

    public DockServiceRegistry(String liveFid) {
        this.liveFid = liveFid;
        this.resolver = new HomeServiceResolver();
    }

    /**
     * Provide a long-lived FUDP node so failed docks can always be re-bootstrapped,
     * even after all per-dock clients have been invalidated.
     */
    public void setRecoveryNode(FudpNode node) {
        this.recoveryNode = node;
    }

    /**
     * Register a talk partner's DOCK service.
     */
    public void registerTalkPartner(TalkPartner partner, FapiClient fapiClient) {
        if (partner == null || partner.getFid() == null) return;
        String url = resolver.resolveDockFromHome(partner.getHome(), fapiClient);
        if (url != null) {
            DockEntry entry = new DockEntry(partner.getFid(), "p2p", url, liveFid);
            entityToDock.put("p2p:" + partner.getFid(), entry);
            ensureClient(url, fapiClient);
        }
    }

    /**
     * Register a room's DOCK service.
     */
    public void registerRoom(Room room, FapiClient fapiClient) {
        if (room == null || room.getId() == null) return;
        String url = resolver.resolveDockFromHome(room.getHome(), fapiClient);
        if (url != null) {
            DockEntry entry = new DockEntry(room.getId(), "room", url, room.getId());
            entityToDock.put("room:" + room.getId(), entry);
            ensureClient(url, fapiClient);
        }
    }

    /**
     * Register a team's DOCK service.
     */
    public void registerTeam(Team team, FapiClient fapiClient) {
        if (team == null || team.getId() == null) return;
        String url = resolver.resolveDockFromHome(team.getHome(), fapiClient);
        if (url != null) {
            DockEntry entry = new DockEntry(team.getId(), "team", url, team.getId());
            entityToDock.put("team:" + team.getId(), entry);
            ensureClient(url, fapiClient);
        }
    }

    /**
     * Register a square's DOCK service (FEIP entity type "square").
     */
    public void registerSquare(Square square, FapiClient fapiClient) {
        if (square == null || square.getId() == null) return;
        String url = resolver.resolveDockFromHome(square.getHome(), fapiClient);
        if (url != null) {
            DockEntry entry = new DockEntry(square.getId(), SQUARE, url, square.getId());
            entityToDock.put(SQUARE+":" + square.getId(), entry);
            ensureClient(url, fapiClient);
        }
    }

    /**
     * Register the user's own DOCK for P2P messages addressed to liveFid.
     * Only uses already-cached DOCK client from ApiCenter to avoid blocking
     * on network calls (which can timeout for 30s and delay all registrations).
     */
    public void registerSelfDock(FapiClient fapiClient) {
        doRegisterSelfDock();
    }

    /**
     * Ensure the user's own DOCK ("p2p_self") is registered so the fetch
     * scheduler polls it for incoming P2P messages. Cheap to call every cycle
     * (a map lookup once registered) and self-heals when the initial
     * registration at start() ran before the DOCK client was ready.
     */
    public void ensureSelfDockRegistered() {
        if (liveFid == null) return;
        if (entityToDock.get("p2p_self:" + liveFid) != null) return;
        doRegisterSelfDock();
    }

    private void doRegisterSelfDock() {
        if (liveFid == null) return;
        try {
            // Prefer the already-cached DOCK client (non-blocking). Fall back to
            // getClient(), which bootstraps the home DOCK on demand: the cached
            // client is usually null at cold start, which previously left P2P
            // receiving permanently disabled with no retry (messages could be
            // sent but never fetched).
            FapiClient homeDock = ApiCenter.getInstance().getCachedClient(ApiCenter.ConnectionRole.DOCK);
            if (homeDock == null) {
                homeDock = ApiCenter.getInstance().getClient(ApiCenter.ConnectionRole.DOCK);
            }
            if (homeDock != null) {
                String url = homeDock.getServerUrl();
                if (url != null) {
                    DockEntry entry = new DockEntry(liveFid, "p2p_self", url, liveFid);
                    entityToDock.put("p2p_self:" + liveFid, entry);
                    dockClients.put(url, homeDock);
                    TimberLogger.d(TAG, "Registered self DOCK: %s", url);
                    return;
                }
            }
            TimberLogger.d(TAG, "Self DOCK not yet available, will retry on next fetch cycle");
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to register self DOCK: %s", e.getMessage());
        }
    }

    /**
     * Unregister an entity.
     */
    public void unregister(String entityType, String entityId) {
        entityToDock.remove(entityType + ":" + entityId);
    }

    /**
     * Get all unique DOCK URLs currently registered.
     */
    public Set<String> getAllDockUrls() {
        Set<String> urls = new HashSet<>();
        for (DockEntry entry : entityToDock.values()) {
            urls.add(entry.dockUrl);
        }
        return urls;
    }

    /**
     * Get all entries grouped by DOCK URL.
     * This is the primary method used by the scheduler.
     */
    public Map<String, List<DockEntry>> getEntriesByDockUrl() {
        Map<String, List<DockEntry>> grouped = new HashMap<>();
        for (DockEntry entry : entityToDock.values()) {
            grouped.computeIfAbsent(entry.dockUrl, k -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * Get all recipient IDs for a given DOCK URL.
     */
    public List<String> getRecipientIdsForDock(String dockUrl) {
        Set<String> ids = new HashSet<>();
        for (DockEntry entry : entityToDock.values()) {
            if (entry.dockUrl.equals(dockUrl)) {
                ids.add(entry.recipientId);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Get the FapiClient for a given DOCK URL.
     * If the URL matches the default fapiClient's server, returns that.
     * Otherwise returns a client created for that specific DOCK URL.
     */
    public FapiClient getClientForDock(String dockUrl) {
        return dockClients.get(dockUrl);
    }

    /**
     * Remove the cached client for a DOCK URL so the next fetch cycle
     * triggers a fresh bootstrap (new FUDP connection).
     * Called when a fetch fails (timeout/error) indicating the connection is dead.
     */
    public void invalidateClient(String dockUrl) {
        if (dockUrl == null) return;
        FapiClient removed = dockClients.remove(dockUrl);
        if (removed != null) {
            TimberLogger.i(TAG, "Invalidated client for DOCK %s, will re-bootstrap on next fetch", dockUrl);
        }
    }

    /**
     * Mark a DOCK URL as failed. It will be skipped by the fetch scheduler
     * until explicitly cleared (e.g. when user opens a group list page).
     */
    public void markFailed(String dockUrl) {
        if (dockUrl == null) return;
        failedDocks.add(dockUrl);
        TimberLogger.i(TAG, "Marked DOCK %s as failed — will skip until retry", dockUrl);
    }

    /**
     * Check if a DOCK URL is marked as failed.
     */
    public boolean isFailed(String dockUrl) {
        return dockUrl != null && failedDocks.contains(dockUrl);
    }

    /**
     * Clear the failed state for a DOCK URL (e.g. after successful retry).
     */
    public void clearFailed(String dockUrl) {
        if (dockUrl == null) return;
        if (failedDocks.remove(dockUrl)) {
            TimberLogger.i(TAG, "Cleared failed state for DOCK %s", dockUrl);
        }
    }

    /**
     * Get all DOCK URLs that are currently marked as failed.
     */
    public Set<String> getFailedDocks() {
        return new HashSet<>(failedDocks);
    }

    /**
     * Retry bootstrap for all failed DOCKs that serve the given entity type.
     * Called from group list activities (TeamActivity, SquareActivity, RoomActivity).
     *
     * @param entityType "team", "square", or "room"
     * @return set of DOCK URLs that were successfully reconnected
     */
    public Set<String> retryFailedDocksForType(String entityType) {
        Set<String> reconnected = new HashSet<>();
        long now = System.currentTimeMillis();
        // Find failed DOCK URLs used by the given entity type
        Set<String> failedUrlsForType = new HashSet<>();
        for (DockEntry entry : entityToDock.values()) {
            if (entry.entityType.equals(entityType) && failedDocks.contains(entry.dockUrl)) {
                failedUrlsForType.add(entry.dockUrl);
            }
        }
        for (String dockUrl : failedUrlsForType) {
            // Skip if recently retried (avoids redundant 5s timeout when multiple
            // group types share the same DOCK and user opens different list pages)
            Long lastAttempt = lastRetryAttempt.get(dockUrl);
            if (lastAttempt != null && now - lastAttempt < RETRY_COOLDOWN_MS) {
                TimberLogger.d(TAG, "Skipping retry for DOCK %s (cooldown)", dockUrl);
                continue;
            }
            lastRetryAttempt.put(dockUrl, now);

            FapiClient client = retryBootstrap(dockUrl);
            if (client != null) {
                failedDocks.remove(dockUrl);
                lastRetryAttempt.remove(dockUrl);
                reconnected.add(dockUrl);
                TimberLogger.i(TAG, "Retry succeeded for DOCK %s (type=%s)", dockUrl, entityType);
            } else {
                TimberLogger.w(TAG, "Retry still failed for DOCK %s (type=%s)", dockUrl, entityType);
            }
        }
        return reconnected;
    }

    /**
     * Get entries for a specific entity.
     */
    public DockEntry getEntry(String entityType, String entityId) {
        return entityToDock.get(entityType + ":" + entityId);
    }

    /**
     * Get the DOCK URL for a given entity.
     */
    public String getDockUrl(String entityType, String entityId) {
        DockEntry entry = getEntry(entityType, entityId);
        return entry != null ? entry.dockUrl : null;
    }

    /**
     * Get the dataExpiresInDays for a DOCK service identified by its home value.
     * Uses the cached Service object that was fetched during registration.
     *
     * @param dockHomeValue The raw home map value for the DOCK key (SID or URL)
     * @return Expiry days string, or null if unavailable
     */
    public String getDockExpiryDays(String dockHomeValue) {
        String sid = HomeServiceResolver.extractSid(dockHomeValue);
        if (sid == null) return null;
        Service service = resolver.getCachedService(sid);
        if (service == null) return null;
        return service.getDataExpiresInDays();
    }

    /**
     * Total number of registered entries.
     */
    public int size() {
        return entityToDock.size();
    }

    /**
     * Clear all registrations and client caches.
     */
    public void clear() {
        entityToDock.clear();
        dockClients.clear();
        failedDocks.clear();
        lastRetryAttempt.clear();
        resolver.clearCache();
    }

    /**
     * Refresh all registrations by re-checking entities' home maps.
     * Call after entities' home maps may have changed.
     */
    public void refreshAll(List<TalkPartner> partners, List<Room> rooms,
                           List<Team> teams, List<Square> squares, FapiClient fapiClient) {
        entityToDock.clear();

        registerSelfDock(fapiClient);

        if (partners != null) {
            for (TalkPartner p : partners) {
                registerTalkPartner(p, fapiClient);
            }
        }
        if (rooms != null) {
            for (Room r : rooms) {
                registerRoom(r, fapiClient);
            }
        }
        if (teams != null) {
            for (Team t : teams) {
                registerTeam(t, fapiClient);
            }
        }
        if (squares != null) {
            for (Square s : squares) {
                registerSquare(s, fapiClient);
            }
        }

        TimberLogger.i(TAG, "Refreshed registry: %d entries across %d DOCK servers",
                entityToDock.size(), getAllDockUrls().size());
    }

    private void ensureClient(String dockUrl, FapiClient defaultClient) {
        if (dockUrl == null || dockClients.containsKey(dockUrl)) return;

        if (defaultClient != null && dockUrl.equals(defaultClient.getServerUrl())) {
            dockClients.put(dockUrl, defaultClient);
        } else if (defaultClient != null) {
            tryBootstrapClient(dockUrl, defaultClient);
        }
    }

    /**
     * Attempt to bootstrap a FapiClient for the given DOCK URL.
     * Does NOT fall back to the default client when URLs differ, because
     * sending dock.fetch to the wrong server would silently return no data or timeout.
     * Instead, the URL is left without a client so fetchFromDock can retry later.
     */
    private void tryBootstrapClient(String dockUrl, FapiClient defaultClient) {
        bootstrapWithNode(dockUrl, defaultClient != null ? defaultClient.getFudpNode() : null);
    }

    private void bootstrapWithNode(String dockUrl, FudpNode node) {
        if (node == null) {
            failedDocks.add(dockUrl);
            TimberLogger.w(TAG, "No FUDP node available to bootstrap DOCK %s, marked as failed", dockUrl);
            return;
        }
        try {
            FapiClient client = FapiClient.bootstrapFromUrl(node, dockUrl, null);
            if (client != null) {
                dockClients.put(dockUrl, client);
                failedDocks.remove(dockUrl);
                TimberLogger.i(TAG, "Bootstrapped client for DOCK %s", dockUrl);
            } else {
                failedDocks.add(dockUrl);
                TimberLogger.w(TAG, "Bootstrap returned null for DOCK %s, marked as failed", dockUrl);
            }
        } catch (Exception e) {
            failedDocks.add(dockUrl);
            TimberLogger.w(TAG, "Failed to bootstrap client for DOCK %s: %s, marked as failed",
                    dockUrl, e.getMessage());
        }
    }

    /**
     * Attempt to create a client for a DOCK URL that has no client yet.
     * Called lazily by the fetch scheduler when getClientForDock returns null.
     *
     * @return the newly created client, or null if bootstrap failed
     */
    public FapiClient retryBootstrap(String dockUrl) {
        if (dockUrl == null) return null;
        FapiClient existing = dockClients.get(dockUrl);
        if (existing != null) return existing;

        // Find any existing client to borrow its FudpNode, falling back to the
        // long-lived recovery node when every dock client has been invalidated.
        FudpNode node = null;
        for (FapiClient c : dockClients.values()) {
            if (c != null && c.getFudpNode() != null) {
                node = c.getFudpNode();
                break;
            }
        }
        if (node == null) node = recoveryNode;
        if (node == null) return null;

        bootstrapWithNode(dockUrl, node);
        return dockClients.get(dockUrl);
    }

    /**
     * Attempt to recover a DOCK that has lost its client or been marked failed
     * (e.g. the FUDP connection died while the device slept). Drops any stale
     * client, re-bootstraps a fresh connection over a live FUDP node, and clears
     * the failed flag on success. Guarded by {@link #RETRY_COOLDOWN_MS} so a
     * genuinely dead server is not hammered on every fetch cycle.
     *
     * @return a usable client, or null if still cooling down or recovery failed.
     */
    public FapiClient recoverDock(String dockUrl) {
        if (dockUrl == null) return null;
        FapiClient existing = dockClients.get(dockUrl);
        if (existing != null && !failedDocks.contains(dockUrl)) return existing;

        long now = System.currentTimeMillis();
        Long lastAttempt = lastRetryAttempt.get(dockUrl);
        if (lastAttempt != null && now - lastAttempt < RETRY_COOLDOWN_MS) {
            return null; // recently attempted, still cooling down
        }
        lastRetryAttempt.put(dockUrl, now);

        // Drop any stale client so a fresh FUDP connection is established.
        dockClients.remove(dockUrl);
        FapiClient client = retryBootstrap(dockUrl);
        if (client != null) {
            failedDocks.remove(dockUrl);
            lastRetryAttempt.remove(dockUrl);
            TimberLogger.i(TAG, "Recovered DOCK %s after connection loss", dockUrl);
        } else {
            failedDocks.add(dockUrl);
        }
        return client;
    }

    /**
     * Clear retry cooldowns so the next fetch attempts to recover every failed
     * DOCK immediately. Called when the app returns to the foreground after a
     * long sleep, where waiting out the per-dock cooldown would needlessly delay
     * reconnection.
     */
    public void resetRetryCooldowns() {
        lastRetryAttempt.clear();
    }
}
