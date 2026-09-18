package com.fc.freer.im.handler;

import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.DeliveryMethod;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.MessageQueue.SendResult;
import com.fc.freer.manager.DatabaseManager;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.apipData.Filter;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for Square messaging.
 * Squares are on-chain with open membership - anyone can join or leave.
 * Messages are NOT encrypted (public).
 */
public class SquareHandler extends BaseHandler {
    
    private final Map<String, Square> squareCache = new HashMap<>();
    private final LocalDB<Square> squaresDb;
    
    public SquareHandler(Context context, String liveFid) {
        super(context, liveFid);
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.squaresDb = dbManager.getEntityDatabase(liveFid, Square.class, Square.class);
        loadSquaresIntoCache();
    }
    
    private void loadSquaresIntoCache() {
        try {
            Map<String, Square> stored = squaresDb.getAll();
            for (Square square : stored.values()) {
                // Only promote a pending square to "confirmed on chain" once our own
                // membership is actually reflected on chain. A *joined* square always
                // has a birthHeight (it pre-existed), so promoting on birthHeight alone
                // would prematurely strip its pending status before the join confirms,
                // hiding it from the list (no conversation yet, no pending card).
                if (square.getOnChain() == null && square.getBirthHeight() != null
                        && square.getMembers() != null && square.getMembers().contains(liveFid)) {
                    square.setOnChain(true);
                    squaresDb.put(square.getId(), square);
                }
            }
            squareCache.putAll(stored);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load squares from local DB: %s", e.getMessage());
        }
    }
    
    @Override
    public ImType getType() {
        return ImType.SQUARE;
    }
    
    @Override
    public SendResult send(ImMessage message) {
        if (message == null || message.getTargetId() == null) {
            notifyError("Invalid message or target square");
            return SendResult.FAIL_PERMANENT;
        }
        
        String squareId = message.getTargetId();
        
        if (fapiClient == null) {
            notifyError("FAPI client not available");
            return SendResult.FAIL_PERMANENT;
        }
        
        try {
            byte[] data = signedWire(message);
            if (data == null) {
                notifyError("Cannot sign square message");
                return SendResult.FAIL_PERMANENT;
            }
            
            java.util.List<String> recipients = new java.util.ArrayList<>();
            recipients.add(squareId);

            // Prefer sending directly to the square's DOCK (cheaper, no relay hop)
            OrgDockInfo orgDock = getOrgDockClient(SQUARE, squareId);
            if (orgDock != null) {
                DockItem dockItem = orgDock.client.dockPut(data, recipients, null, null, "IM");
                if (dockItem != null && dockItem.getId() != null) {
                    message.setDockId(dockItem.getId());
                    message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                    message.setStatus(MessageStatus.SENT);
                    TimberLogger.d(TAG, "Square message sent directly to square's DOCK");
                    notifySent(message);
                    return SendResult.SUCCESS;
                }
                notifyError("Failed to store message in square's DOCK");
                return SendResult.RETRY_TRANSIENT;
            }

            // Fallback: resolve square's DOCK URL and forward via user's own DOCK
            String targetDockUrl = null;
            Square cachedSquare = squareCache.get(squareId);
            if (cachedSquare == null) {
                cachedSquare = loadSquareInfo(squareId);
            }
            Map<String, String> squareHome = (cachedSquare != null) ? cachedSquare.getHome() : null;
            if (squareHome != null) {
                targetDockUrl = fapiClient.getHomeServiceResolver()
                        .resolveDockFromHome(squareHome, fapiClient);
            }
            if (targetDockUrl == null) {
                try {
                    targetDockUrl = fapiClient.resolveDockUrl(squareId, SQUARE);
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to resolve square DOCK URL: %s", e.getMessage());
                }
            }

            // A square's messages must land on the square's own DOCK so every
            // member fetches them from the same server. If we cannot resolve a
            // target DOCK, sending via our own DOCK would store the message where
            // only we can read it and falsely report "sent". Fail loudly instead.
            if (targetDockUrl == null) {
                notifyError(context.getString(hasDockConfigured(squareHome)
                        ? R.string.im_dock_unresolvable
                        : R.string.im_dock_not_configured));
                return SendResult.FAIL_PERMANENT;
            }

            if (dockAvailabilityChecker != null
                    && !dockAvailabilityChecker.isDockReachable(targetDockUrl)) {
                notifyError("Cannot reach square's DOCK server: " + targetDockUrl);
                return SendResult.RETRY_TRANSIENT;
            }

            FapiClient dockClient = ApiCenter.getInstance().getClient(ApiCenter.ConnectionRole.DOCK);
            if (dockClient == null) {
                notifyError("DOCK service not available. Configure DOCK in home settings.");
                return SendResult.RETRY_TRANSIENT;
            }
            DockItem dockItem = dockClient.dockPut(data, recipients, null, targetDockUrl, "IM");

            if (dockItem != null && dockItem.getId() != null) {
                message.setDockId(dockItem.getId());
                message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                message.setStatus(MessageStatus.SENT);
                TimberLogger.d(TAG, "Square message forwarded via own DOCK to: %s", targetDockUrl);
                notifySent(message);
                return SendResult.SUCCESS;
            }
            
            notifyError("Failed to store message in DOCK");
            return SendResult.RETRY_TRANSIENT;
            
        } catch (Exception e) {
            notifyError("Failed to send square message: " + e.getMessage());
            return SendResult.RETRY_TRANSIENT;
        }
    }
    
    @Override
    public void handleIncoming(ImMessage message) {
        if (message == null) return;
        
        // Mark as unread if from someone else
        message.setUnread(!liveFid.equals(message.getSenderId()));
        message.setDeliveredAt(System.currentTimeMillis());
        
        // Notify listener
        notifyReceived(message);
    }
    
    /**
     * Fetch new messages for a square from DOCK using dock.fetch (inline data).
     */
    public List<ImMessage> fetchSquareMessages(String squareId, Long since) {
        if (fapiClient == null) return null;
        
        try {
            java.util.List<String> recipientIds = java.util.Collections.singletonList(squareId);
            com.fc.fc_ajdk.data.apipData.Fcdsl fcdsl = new com.fc.fc_ajdk.data.apipData.Fcdsl();
            fcdsl.setSize("50");
            
            com.fc.fc_ajdk.fapi.message.FapiResponse response = fapiClient.dockFetch(recipientIds, fcdsl);
            if (response == null || !response.isSuccess()) return null;
            
            List<DockItem> items = com.fc.fc_ajdk.utils.ObjectUtils.objectToList(
                    response.getData(), DockItem.class);
            if (items == null) return null;
            
            java.util.List<ImMessage> messages = new java.util.ArrayList<>();
            
            for (DockItem item : items) {
                if (since != null && item.getCreateTime() != null && item.getCreateTime() <= since) {
                    continue;
                }
                if (item.getDataBase64() == null) continue;
                
                try {
                    byte[] data = android.util.Base64.decode(item.getDataBase64(), android.util.Base64.NO_WRAP);
                    ImMessage msg = ImMessage.fromWireBytes(data);
                    if (msg != null) {
                        msg.setDockId(item.getId());
                        msg.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                        messages.add(msg);
                    }
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to parse message from DOCK: %s", e.getMessage());
                }
            }
            
            return messages;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to fetch square messages: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Load square info. Returns from local cache/DB first, fetches from chain if missing.
     */
    public Square loadSquareInfo(String squareId) {
        if (squareCache.containsKey(squareId)) {
            return squareCache.get(squareId);
        }
        
        if (fapiClient == null) return null;
        
        try {
            Square square = fapiClient.entityById(SQUARE, Square.class, squareId);
            if (square != null) {
                square.setOnChain(true);
                saveSquare(square);
            }
            return square;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load square info: %s", e.getMessage());
            return null;
        }
    }
    
    private void saveSquare(Square square) {
        squareCache.put(square.getId(), square);
        squaresDb.put(square.getId(), square);
    }
    
    /**
     * Save or update a square from external sync (confirmed on chain).
     */
    public void saveSquarePublic(Square square) {
        if (square == null || square.getId() == null) return;
        square.setOnChain(true);
        saveSquare(square);
    }

    /**
     * Save a locally-created pending square (TX broadcast, not yet confirmed).
     */
    public void savePendingSquare(Square square) {
        if (square == null || square.getId() == null) return;
        square.setOnChain(null);
        saveSquare(square);
    }

    /**
     * Return all squares with onChain==null (TX submitted, awaiting confirmation).
     */
    public List<Square> getPendingSquares() {
        List<Square> pending = new java.util.ArrayList<>();
        for (Square square : squareCache.values()) {
            if (square.getOnChain() == null) {
                pending.add(square);
            }
        }
        return pending;
    }
    
    /**
     * Whether the chain shows {@code fid} as a member of any square stored here. A square only
     * carries a birth height once the chain has it, so a create or join still in flight does not
     * count.
     */
    public boolean hasJoinedOnChain(String fid) {
        if (fid == null) return false;
        for (Square square : squareCache.values()) {
            if (square.getBirthHeight() != null && square.getMembers() != null
                    && square.getMembers().contains(fid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The txid of a pending create or join. A create's square id is its txid; a join records
     * its txid in {@code lastTxId} until the chain's copy replaces the square.
     */
    public static String pendingTxid(Square square) {
        return square.getLastTxId() != null ? square.getLastTxId() : square.getId();
    }

    /**
     * When a pending create or join was broadcast, epoch ms. Both record it in {@code lastTime}
     * in milliseconds; a chain {@code lastTime} in seconds reads as long ago, so an older record
     * is treated as overdue rather than fresh.
     */
    public static long pendingBroadcastAt(Square square) {
        Long t = square.getLastTime();
        if (t == null) return 0L;
        return t < 100_000_000_000L ? t * 1000L : t;
    }

    /** The pending square act on {@code squareId} broadcast less than {@code windowMs} ago, or null. */
    public Square getRecentPending(String squareId, long nowMs, long windowMs) {
        Square square = squareId != null ? squareCache.get(squareId) : null;
        if (square == null || square.getOnChain() != null) return null;
        return nowMs - pendingBroadcastAt(square) < windowMs ? square : null;
    }

    /**
     * Check if user is a member of the square.
     */
    public boolean isMember(String squareId, String fid) {
        Square square = loadSquareInfo(squareId);
        if (square == null || square.getMembers() == null) return false;
        return square.getMembers().contains(fid);
    }
    
    /**
     * Get cached square or fetch.
     */
    public Square getSquare(String squareId) {
        return squareCache.getOrDefault(squareId, loadSquareInfo(squareId));
    }
    
    /**
     * Get all locally cached squares.
     */
    public List<Square> getAllSquares() {
        return new java.util.ArrayList<>(squareCache.values());
    }
    
    /**
     * Refresh squares updated on-chain since the given height.
     * Queries FAPI for squares where liveFid is a member and lastHeight > lastUpdateHeight.
     *
     * @return the maximum lastHeight among returned squares, or lastUpdateHeight if none found
     */
    public long refreshUpdatedSquares(FapiClient client, String memberFid, long lastUpdateHeight) {
        if (client == null || memberFid == null) return lastUpdateHeight;

        try {
            Fcdsl fcdsl = new Fcdsl();
            Filter filter = new Filter();
            filter.addNewTerms().addNewFields("members").addNewValues(memberFid);
            filter.addNewRange().addNewFields("lastHeight").addGt(String.valueOf(lastUpdateHeight));
            fcdsl.setFilter(filter);
            fcdsl.setSize("200");

            List<Square> squares = client.squareSearch(fcdsl);
            if (squares == null || squares.isEmpty()) return lastUpdateHeight;

            long maxHeight = lastUpdateHeight;
            for (Square square : squares) {
                // Read from the chain, so confirmed: left null it would pass for a pending act.
                square.setOnChain(true);
                saveSquare(square);
                if (square.getLastHeight() != null && square.getLastHeight() > maxHeight) {
                    maxHeight = square.getLastHeight();
                }
            }
            TimberLogger.i(TAG, "Refreshed %d updated squares (lastHeight > %d, new max=%d)",
                    squares.size(), lastUpdateHeight, maxHeight);
            return maxHeight;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to refresh updated squares: %s", e.getMessage());
            return lastUpdateHeight;
        }
    }

    /**
     * Clear in-memory cache and reload from local DB.
     */
    public void clearCache() {
        squareCache.clear();
        loadSquaresIntoCache();
    }
}
