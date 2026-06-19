package com.fc.freer.im.dock;

import android.util.Base64;

import com.fc.fc_ajdk.data.fcData.DeliveryMethod;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.im.handler.SquareHandler;
import com.fc.freer.im.handler.P2pHandler;
import com.fc.freer.im.handler.RoomHandler;
import com.fc.freer.im.handler.TeamHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes fetched DockItems to the appropriate handler based on dataType.
 * <p>
 * - "IM" items are decoded into ImMessage and dispatched to the correct handler
 *   (P2P, Group, Team, Room) based on ImMessage.type.
 * - Unknown dataType items are stored in a local DB for user inspection.
 * <p>
 * Deduplication: processed dock IDs are tracked to avoid re-processing.
 */
public class DockItemRouter {
    private static final String TAG = "DockItemRouter";
    private static final String DOCK_ITEMS_DB = "im_dock_items";
    private static final int MAX_PROCESSED_IDS = 10_000;

    public static final String DATA_TYPE_IM = "IM";
    public static final String DATA_TYPE_SYMKEY_REQ = "SYMKEY_REQ";
    public static final String DATA_TYPE_SYMKEY = "SYMKEY";

    private final String liveFid;

    private P2pHandler p2pHandler;
    private SquareHandler squareHandler;
    private TeamHandler teamHandler;
    private RoomHandler roomHandler;

    private LocalDB<DockItem> unknownItemsDb;

    private final Set<String> processedDockIds = ConcurrentHashMap.newKeySet();

    private UnknownItemCallback unknownItemCallback;

    public interface UnknownItemCallback {
        void onUnknownDockItem(DockItem item);
    }

    public DockItemRouter(String liveFid) {
        this.liveFid = liveFid;
    }

    public void setHandlers(P2pHandler p2pHandler, SquareHandler squareHandler,
                            TeamHandler teamHandler, RoomHandler roomHandler) {
        this.p2pHandler = p2pHandler;
        this.squareHandler = squareHandler;
        this.teamHandler = teamHandler;
        this.roomHandler = roomHandler;
    }

    public void setUnknownItemsDb(LocalDB<DockItem> db) {
        this.unknownItemsDb = db;
    }

    public void setUnknownItemCallback(UnknownItemCallback callback) {
        this.unknownItemCallback = callback;
    }

    /**
     * Route a batch of fetched dock items.
     *
     * @param items   Items fetched from a single DOCK server
     * @param dockUrl The DOCK URL they came from
     * @return Number of items successfully processed
     */
    public int routeItems(List<DockItem> items, String dockUrl) {
        if (items == null || items.isEmpty()) return 0;

        int processed = 0;
        for (DockItem item : items) {
            if (item == null || item.getId() == null) continue;

            if (processedDockIds.contains(item.getId())) {
                continue;
            }

            try {
                if (routeSingleItem(item)) {
                    processed++;
                }
                markProcessed(item.getId());
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to route dock item %s: %s", item.getId(), e.getMessage());
            }
        }

        return processed;
    }

    private boolean routeSingleItem(DockItem item) {
        String dataType = item.getDataType();

        if (DATA_TYPE_IM.equals(dataType)
                || DATA_TYPE_SYMKEY_REQ.equals(dataType)
                || DATA_TYPE_SYMKEY.equals(dataType)) {
            return routeImItem(item);
        } else {
            return routeUnknownItem(item);
        }
    }

    private boolean routeImItem(DockItem item) {
        String dataBase64 = item.getDataBase64();
        if (dataBase64 == null || dataBase64.isEmpty()) {
            TimberLogger.w(TAG, "IM dock item %s has no data", item.getId());
            return false;
        }

        try {
            byte[] data = Base64.decode(dataBase64, Base64.NO_WRAP);
            ImMessage message = ImMessage.fromWireBytes(data);
            if (message == null) {
                TimberLogger.w(TAG, "Failed to parse ImMessage from dock item %s", item.getId());
                return false;
            }

            message.setDockId(item.getId());
            message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);

            if (message.getType() == null) {
                TimberLogger.w(TAG, "ImMessage from dock item %s has no type", item.getId());
                return false;
            }

            switch (message.getType()) {
                case P2P:
                    if (p2pHandler != null) {
                        p2pHandler.handleIncoming(message);
                        return true;
                    }
                    break;
                case SQUARE:
                    if (squareHandler != null) {
                        squareHandler.handleIncoming(message);
                        return true;
                    }
                    break;
                case TEAM:
                    if (teamHandler != null) {
                        teamHandler.handleIncoming(message);
                        return true;
                    }
                    break;
                case ROOM:
                    if (roomHandler != null) {
                        roomHandler.handleIncoming(message);
                        return true;
                    }
                    break;
            }

            TimberLogger.w(TAG, "No handler for IM message type: %s", message.getType());
            return false;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decoding IM from dock item %s: %s", item.getId(), e.getMessage());
            return false;
        }
    }

    private boolean routeUnknownItem(DockItem item) {
        if (unknownItemsDb != null) {
            unknownItemsDb.put(item.getId(), item);
        }

        if (unknownItemCallback != null) {
            unknownItemCallback.onUnknownDockItem(item);
        }

        TimberLogger.d(TAG, "Unknown dock item type '%s' stored: %s", item.getDataType(), item.getId());
        return true;
    }

    /**
     * Check if a dock item has been processed.
     */
    public boolean isProcessed(String dockId) {
        return processedDockIds.contains(dockId);
    }

    /**
     * Mark a dock ID as processed.
     */
    public void markProcessed(String dockId) {
        if (processedDockIds.size() >= MAX_PROCESSED_IDS) {
            processedDockIds.clear();
        }
        processedDockIds.add(dockId);
    }

    /**
     * Get the number of unknown items stored.
     */
    public int getUnknownItemCount() {
        if (unknownItemsDb == null) return 0;
        try {
            return unknownItemsDb.getAll().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get all unknown dock items.
     */
    public java.util.Collection<DockItem> getUnknownItems() {
        if (unknownItemsDb == null) return java.util.Collections.emptyList();
        return unknownItemsDb.getAll().values();
    }

    /**
     * Remove an unknown item after handling.
     */
    public void removeUnknownItem(String dockId) {
        if (unknownItemsDb != null) {
            unknownItemsDb.remove(dockId);
        }
    }

    /**
     * Clear all processed IDs (forces re-processing on next fetch).
     */
    public void clearProcessedIds() {
        processedDockIds.clear();
    }
}
