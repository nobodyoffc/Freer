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
            byte[] data = message.toWireBytes();
            
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
            if (cachedSquare != null && cachedSquare.getHome() != null) {
                targetDockUrl = fapiClient.getHomeServiceResolver()
                        .resolveDockFromHome(cachedSquare.getHome(), fapiClient);
            }
            if (targetDockUrl == null) {
                try {
                    targetDockUrl = fapiClient.resolveDockUrl(squareId, SQUARE);
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to resolve square DOCK URL: %s", e.getMessage());
                }
            }

            if (targetDockUrl != null && dockAvailabilityChecker != null
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
     * Save or update a square from external sync (e.g. SquareSyncManager).
     */
    public void saveSquarePublic(Square square) {
        if (square != null && square.getId() != null) {
            saveSquare(square);
        }
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
