package com.fc.freer.im.handler;

import android.content.Context;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.DeliveryMethod;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fcData.RequestType;
import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.fcData.RoomInfo;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.im.MessageQueue.SendResult;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.utils.ApiCenter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for Room messaging.
 * Rooms are locally-defined with owner-managed membership and symkey.
 * No on-chain state - everything is local or shared via P2P using ROOM_INFO messages.
 *
 * Key difference from Team:
 *  - Owner manages name, desc, members AND symkey (team owner only manages symkey)
 *  - Any member can share room info (RoomInfo) to other members
 *  - Operations are purely local, no on-chain TX required
 */
public class RoomHandler extends BaseHandler {
    private static final String ROOMS_DB = "rooms";
    
    private LocalDB<Room> roomsDb;
    private final Map<String, Room> roomCache = new HashMap<>();
    
    public RoomHandler(Context context, String liveFid) {
        super(context, liveFid);
        
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.roomsDb = dbManager.getEntityDatabase(liveFid, Room.class, Room.class);
        
        loadRoomsIntoCache();
    }
    
    private void loadRoomsIntoCache() {
        try {
            Map<String, Room> rooms = roomsDb.getAll();
            for (Room room : rooms.values()) {
                if (room != null && (room.getActive() == null || room.getActive())) {
                    roomCache.put(room.getId(), room);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load rooms: %s", e.getMessage());
        }
    }
    
    @Override
    public ImType getType() {
        return ImType.ROOM;
    }
    
    @Override
    public SendResult send(ImMessage message) {
        if (message == null || message.getTargetId() == null) {
            notifyError("Invalid message or target room");
            return SendResult.FAIL_PERMANENT;
        }
        
        String roomId = message.getTargetId();
        Room room = getRoom(roomId);
        
        if (room == null) {
            notifyError("Room not found: " + roomId);
            return SendResult.FAIL_PERMANENT;
        }

        // Closed rooms accept no traffic, except the owner's final ROOM_DISBAND
        // broadcast which is queued before deactivation and may flush after it.
        if (isInactive(room) && message.getContentType() != ContentType.ROOM_DISBAND) {
            notifyError("Room is closed: " + roomId);
            return SendResult.FAIL_PERMANENT;
        }

        if (!room.isMember(liveFid)) {
            notifyError("Not a member of room: " + roomId);
            return SendResult.FAIL_PERMANENT;
        }
        
        if (symkeyStore == null || !symkeyStore.hasSymkey(roomId)) {
            notifyError("No symkey for room: " + roomId);
            return SendResult.FAIL_PERMANENT;
        }
        
        if (fapiClient == null) {
            notifyError("FAPI client not available");
            return SendResult.FAIL_PERMANENT;
        }
        
        try {
            byte[] symkey = symkeyStore.getSymkey(roomId);
            if (symkey == null) {
                notifyError("Failed to get symkey for room");
                return SendResult.FAIL_PERMANENT;
            }
            
            String content = message.getContent();
            String cipher = encrypt(content, symkey);
            
            message.setCipher(cipher);
            message.setContent(null);
            message.setSymkeyVersion(symkeyStore.getCurrentVersion(roomId));
            
            byte[] data = message.toWireBytes();
            
            message.setContent(content);
            
            java.util.List<String> recipients = new java.util.ArrayList<>();
            recipients.add(roomId);

            // Prefer sending directly to the room's DOCK (cheaper, no relay hop)
            OrgDockInfo orgDock = getOrgDockClient("room", roomId);
            if (orgDock != null) {
                DockItem dockItem = orgDock.client.dockPut(data, recipients, null, null, "IM");
                if (dockItem != null && dockItem.getId() != null) {
                    message.setDockId(dockItem.getId());
                    message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                    message.setStatus(MessageStatus.SENT);
                    room.setLastActive(System.currentTimeMillis());
                    saveRoom(room);
                    TimberLogger.d(TAG, "Room message sent directly to room's DOCK");
                    notifySent(message);
                    return SendResult.SUCCESS;
                }
                notifyError("Failed to store message in room's DOCK");
                return SendResult.RETRY_TRANSIENT;
            }

            // Fallback: resolve room's DOCK URL and forward via user's own DOCK
            String targetDockUrl = resolveRoomDockUrl(room);

            if (targetDockUrl != null && dockAvailabilityChecker != null
                    && !dockAvailabilityChecker.isDockReachable(targetDockUrl)) {
                notifyError("Cannot reach room's DOCK server: " + targetDockUrl);
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
                room.setLastActive(System.currentTimeMillis());
                saveRoom(room);
                TimberLogger.d(TAG, "Room message forwarded via own DOCK to: %s", targetDockUrl);
                notifySent(message);
                return SendResult.SUCCESS;
            }
            
            notifyError("Failed to store message in DOCK");
            return SendResult.RETRY_TRANSIENT;
            
        } catch (Exception e) {
            notifyError("Failed to send room message: " + e.getMessage());
            return SendResult.RETRY_TRANSIENT;
        }
    }
    
    @Override
    public void handleIncoming(ImMessage message) {
        if (message == null) return;
        
        String roomId = message.getTargetId();
        
        if (message.getCipher() != null && message.getContent() == null) {
            Long version = message.getSymkeyVersion();
            byte[] symkey = symkeyStore != null
                    ? symkeyStore.getSymkey(roomId, version != null ? version : 1L)
                    : null;
            
            if (symkey != null) {
                try {
                    String content = decrypt(message.getCipher(), symkey);
                    message.setContent(content);
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to decrypt room message: %s", e.getMessage());
                    message.setContent("[Encrypted - decryption failed]");
                }
            } else {
                message.setContent("[Encrypted - missing symkey(v" + version + ")]");
                if (symkeyRequester != null) {
                    symkeyRequester.requestSymkey(roomId);
                }
            }
        }
        
        message.setUnread(!liveFid.equals(message.getSenderId()));
        message.setDeliveredAt(System.currentTimeMillis());
        
        Room room = getRoom(roomId);
        if (room != null) {
            room.setLastActive(System.currentTimeMillis());
            saveRoom(room);
        }
        
        notifyReceived(message);
    }
    
    private String encrypt(String content, byte[] symkey) {
        try {
            return Encryptor.encryptBySymkeyToJson(content.getBytes(), symkey);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Encryption failed: %s", e.getMessage());
            return null;
        }
    }
    
    private String decrypt(String cipher, byte[] symkey) {
        try {
            return new String((new Decryptor().decrypt(cipher, symkey)).getData());
        } catch (Exception e) {
            TimberLogger.e(TAG, "Decryption failed: %s", e.getMessage());
            return null;
        }
    }
    
    // ========== Symkey request/share ==========

    public boolean handleSymkeyRequest(ImMessage requestMsg) {
        if (requestMsg == null || requestMsg.getContent() == null) return false;
        if (fapiClient == null || symkeyStore == null) return false;

        String content = requestMsg.getContent();
        String requesterFid = requestMsg.getSenderId();
        if (requesterFid == null) return false;

        int colonIdx = content.indexOf(':');
        String roomId = colonIdx > 0 ? content.substring(0, colonIdx) : content;

        Room room = getRoom(roomId);
        if (room == null) return false;
        if (isInactive(room)) {
            TimberLogger.d(TAG, "Symkey request denied: room %s is closed", roomId);
            return false;
        }

        if (!room.isMember(liveFid)) return false;
        if (!symkeyStore.hasSymkey(roomId)) return false;

        if (!room.isMember(requesterFid)) {
            TimberLogger.w(TAG, "Symkey request denied for room %s: %s is not a member", roomId, requesterFid);
            return false;
        }

        long version = symkeyStore.getCurrentVersion(roomId);

        try {
            String memberPubkey = fapiClient.getPubkey(requesterFid);
            if (memberPubkey == null) {
                TimberLogger.w(TAG, "Cannot get pubkey for requester %s", requesterFid);
                return false;
            }

            String cipher = symkeyStore.createShareCipher(roomId, version, memberPubkey);
            if (cipher == null) return false;

            String symkeyData = roomId + ":" + cipher;
            ImMessage shareMsg = ImMessage.createSymkey(
                    ImType.P2P, liveFid, requesterFid, symkeyData, version);
            if (requestMsg.getRequestId() != null) {
                shareMsg.setRequestId(requestMsg.getRequestId());
            }

            if (p2pSender != null) {
                SendResult result = p2pSender.sendP2p(shareMsg);
                TimberLogger.i(TAG, "Shared symkey v%d for room %s with member %s (result=%s)", version, roomId, requesterFid, result);
                return result == SendResult.SUCCESS;
            } else {
                TimberLogger.w(TAG, "No P2P sender available for room symkey share");
                return false;
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to share room symkey with %s: %s", requesterFid, e.getMessage());
            return false;
        }
    }

    public boolean handleSymkeyShare(ImMessage message) {
        if (message == null || message.getContent() == null) return false;

        String content = message.getContent();
        int separatorIndex = content.indexOf(':');
        if (separatorIndex <= 0) return false;

        String entityId = content.substring(0, separatorIndex);
        String cipher = content.substring(separatorIndex + 1);

        Room room = getRoom(entityId);
        if (room == null) return false;

        String senderFid = message.getSenderId();
        if (senderFid == null || !room.isMember(senderFid)) {
            TimberLogger.w(TAG, "Rejected symkey share for room %s: sender %s is not a member", entityId, senderFid);
            return false;
        }

        long version = message.getSymkeyVersion() != null ? message.getSymkeyVersion() : 1L;

        if (symkeyStore == null) return false;

        boolean fromOwner = room.isOwner(senderFid);
        boolean success = symkeyStore.receiveSharedSymkey(entityId, version, cipher, fromOwner);
        if (success) {
            TimberLogger.d(TAG, "Received symkey v%d for room %s from %s (owner=%s)", version, entityId, senderFid, fromOwner);
        }
        return success;
    }

    /**
     * Handle an incoming ROOM_INFO request from a member.
     * Shares room info (including symkey) back to the requester.
     */
    public boolean handleRoomInfoRequest(ImMessage requestMsg) {
        if (requestMsg == null || requestMsg.getContent() == null) return false;
        if (fapiClient == null || symkeyStore == null) return false;

        String requesterFid = requestMsg.getSenderId();
        if (requesterFid == null) return false;

        String content = requestMsg.getContent();
        int colonIdx = content.indexOf(':');
        String roomId = colonIdx > 0 ? content.substring(0, colonIdx) : content;

        Room room = getRoom(roomId);
        if (room == null) return false;
        if (isInactive(room)) {
            TimberLogger.d(TAG, "Room info request denied: room %s is closed", roomId);
            return false;
        }

        if (!room.isMember(liveFid)) return false;
        if (!room.isMember(requesterFid)) {
            TimberLogger.w(TAG, "Room info request denied: %s is not a member of %s", requesterFid, roomId);
            return false;
        }

        return shareRoomInfoWithRequestId(roomId, requesterFid, requestMsg.getRequestId());
    }

    // ========== Room Info Sharing ==========

    /**
     * Share room info with a specific member via P2P.
     * Builds a RoomInfo with the symkey encrypted for the target member's pubkey.
     */
    public boolean shareRoomInfo(String roomId, String targetFid) {
        Room room = getRoom(roomId);
        if (room == null) {
            notifyError("Room not found");
            return false;
        }

        if (!room.isMember(liveFid)) {
            notifyError("Not a member of this room");
            return false;
        }

        if (fapiClient == null || symkeyStore == null) {
            notifyError("Client not available");
            return false;
        }

        try {
            String targetPubkey = fapiClient.getPubkey(targetFid);
            if (targetPubkey == null) {
                TimberLogger.w(TAG, "Cannot get pubkey for %s", targetFid);
                return false;
            }

            RoomInfo roomInfo = RoomInfo.fromRoom(room);

            if (symkeyStore.hasSymkey(roomId)) {
                long version = symkeyStore.getCurrentVersion(roomId);
                String symkeyCipher = symkeyStore.createShareCipher(roomId, version, targetPubkey);
                roomInfo.setSymkey(symkeyCipher);
                roomInfo.setSymkeyVersion(version);
            }

            String roomInfoJson = roomInfo.toJson();
            ImMessage shareMsg = ImMessage.createRoomInfo(
                    ImType.P2P, liveFid, targetFid, roomInfoJson);

            if (p2pSender != null) {
                SendResult result = p2pSender.sendP2p(shareMsg);
                TimberLogger.i(TAG, "Shared room info for %s with %s (result=%s)", roomId, targetFid, result);
                return result == SendResult.SUCCESS;
            }
            return false;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to share room info with %s: %s", targetFid, e.getMessage());
            return false;
        }
    }

    /**
     * Share room info with a specific member, attaching a requestId for nonce validation.
     */
    private boolean shareRoomInfoWithRequestId(String roomId, String targetFid, String requestId) {
        Room room = getRoom(roomId);
        if (room == null || fapiClient == null) return false;

        if (!room.isMember(liveFid)) return false;

        try {
            String targetPubkey = fapiClient.getPubkey(targetFid);
            if (targetPubkey == null) {
                TimberLogger.w(TAG, "Cannot get pubkey for %s", targetFid);
                return false;
            }

            RoomInfo roomInfo = RoomInfo.fromRoom(room);

            if (symkeyStore != null && symkeyStore.hasSymkey(roomId)) {
                long version = symkeyStore.getCurrentVersion(roomId);
                String symkeyCipher = symkeyStore.createShareCipher(roomId, version, targetPubkey);
                roomInfo.setSymkey(symkeyCipher);
                roomInfo.setSymkeyVersion(version);
            }

            String roomInfoJson = roomInfo.toJson();
            ImMessage shareMsg = ImMessage.createRoomInfo(
                    ImType.P2P, liveFid, targetFid, roomInfoJson);
            if (requestId != null) {
                shareMsg.setRequestId(requestId);
            }

            if (p2pSender != null) {
                SendResult result = p2pSender.sendP2p(shareMsg);
                TimberLogger.i(TAG, "Shared room info for %s with %s (requestId=%s, result=%s)",
                        roomId, targetFid, requestId, result);
                return result == SendResult.SUCCESS;
            }
            return false;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to share room info with %s: %s", targetFid, e.getMessage());
            return false;
        }
    }

    /**
     * Share symkey with selected members (owner only, for manual sharing).
     * @return number of members successfully shared with
     */
    public int shareSymkeyToMembers(String roomId, List<String> targetFids) {
        Room room = getRoom(roomId);
        if (room == null || !room.isOwner(liveFid)) return 0;
        if (targetFids == null || targetFids.isEmpty()) return 0;

        int successCount = 0;
        for (String fid : targetFids) {
            if (!fid.equals(liveFid)) {
                shareSymkeyWithMember(roomId, fid);
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * Share room info with all members (except self).
     */
    public void shareRoomInfoToAll(String roomId) {
        Room room = getRoom(roomId);
        if (room == null || room.getMembers() == null) return;

        for (String member : room.getMembers()) {
            if (!member.equals(liveFid)) {
                shareRoomInfo(roomId, member);
            }
        }
    }

    /**
     * Handle incoming ROOM_INFO message.
     * For existing rooms: validates sender is a member, updates the room and stores symkey.
     * For new rooms: returns false so the caller can create a PendingIssue for user confirmation.
     *
     * @return true if the room already existed and was updated;
     *         false if the room is new (needs user confirmation) or the message is invalid.
     */
    public boolean handleRoomInfoShare(ImMessage message) {
        if (message == null || message.getContent() == null) return false;

        String senderFid = message.getSenderId();
        if (senderFid == null) return false;

        try {
            RoomInfo roomInfo = RoomInfo.fromJson(message.getContent());
            if (roomInfo == null || roomInfo.getId() == null) {
                TimberLogger.w(TAG, "Invalid room info received");
                return false;
            }

            String roomId = roomInfo.getId();
            Room existingRoom = getRoom(roomId);

            if (existingRoom != null) {
                if (!existingRoom.isMember(senderFid)) {
                    TimberLogger.w(TAG, "Rejected room info from non-member %s for room %s", senderFid, roomId);
                    return false;
                }
                roomInfo.applyToRoom(existingRoom);
                saveRoom(existingRoom);

                if (roomInfo.getSymkey() != null && symkeyStore != null) {
                    long version = roomInfo.getSymkeyVersion() != null ? roomInfo.getSymkeyVersion() : 1L;
                    boolean fromOwner = senderFid.equals(roomInfo.getOwner());
                    symkeyStore.receiveSharedSymkey(roomId, version, roomInfo.getSymkey(), fromOwner);
                    TimberLogger.d(TAG, "Stored symkey v%d from room info for %s", version, roomId);
                }

                TimberLogger.i(TAG, "Updated existing room info for %s from %s", roomId, senderFid);
                return true;
            } else {
                TimberLogger.i(TAG, "New room invitation for %s from %s, pending user confirmation", roomId, senderFid);
                return false;
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to process room info: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Accept a room invitation by creating the room from the stored roomInfo JSON.
     * Called when the user accepts a ROOM_INVITE PendingIssue.
     *
     * @param roomInfoJson the raw RoomInfo JSON from the invitation
     * @return the created Room, or null on failure
     */
    public Room acceptRoomInvite(String roomInfoJson) {
        if (roomInfoJson == null) return null;

        try {
            RoomInfo roomInfo = RoomInfo.fromJson(roomInfoJson);
            if (roomInfo == null || roomInfo.getId() == null) return null;

            Room newRoom = roomInfo.toRoom();
            if (!newRoom.isMember(liveFid)) {
                newRoom.addMember(liveFid);
            }
            saveRoom(newRoom);

            if (roomInfo.getSymkey() != null && symkeyStore != null) {
                long version = roomInfo.getSymkeyVersion() != null ? roomInfo.getSymkeyVersion() : 1L;
                boolean fromOwner = roomInfo.getOwner() != null;
                symkeyStore.receiveSharedSymkey(roomInfo.getId(), version, roomInfo.getSymkey(), fromOwner);
                TimberLogger.d(TAG, "Stored symkey v%d from accepted room invite for %s", version, roomInfo.getId());
            }

            // Confirm joining to the owner so the owner can clear the pending state
            String ownerFid = roomInfo.getOwner();
            if (ownerFid != null && !ownerFid.equals(liveFid) && controlSender != null) {
                controlSender.sendControl(ImMessage.createRoomAccept(
                        ImType.P2P, liveFid, ownerFid, roomInfo.getId()));
            }

            TimberLogger.i(TAG, "Accepted room invite: %s, ROOM_ACCEPT sent to owner", roomInfo.getId());
            return newRoom;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to accept room invite: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Reject a room invitation by sending a ROOM_LEAVE to the owner.
     *
     * @param roomInfoJson the raw RoomInfo JSON from the invitation
     */
    public void rejectRoomInvite(String roomInfoJson) {
        if (roomInfoJson == null) return;

        try {
            RoomInfo roomInfo = RoomInfo.fromJson(roomInfoJson);
            if (roomInfo == null || roomInfo.getId() == null || roomInfo.getOwner() == null) return;

            if (controlSender != null) {
                ImMessage leaveMsg = ImMessage.createRoomLeave(
                        ImType.P2P, liveFid, roomInfo.getOwner(), roomInfo.getId());
                controlSender.sendControl(leaveMsg);
                TimberLogger.i(TAG, "Rejected room invite %s, sent ROOM_LEAVE to owner %s",
                        roomInfo.getId(), roomInfo.getOwner());
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to reject room invite: %s", e.getMessage());
        }
    }

    // ========== Room Management ==========
    
    /**
     * Create a new room with optional members and home (DOCK) configuration.
     * After creation, sends roomInfo (including symkey) to all members.
     */
    public Room createRoom(String name, String desc, List<String> memberFids,
                           java.util.Map<String, String> home) {
        Room room = Room.create(liveFid, name, desc);

        if (home != null && !home.isEmpty()) {
            room.setHome(home);
        }

        if (memberFids != null) {
            for (String fid : memberFids) {
                if (!fid.equals(liveFid)) {
                    room.addMember(fid);
                    room.addPendingMember(fid);
                }
            }
        }

        if (symkeyStore != null) {
            byte[] symkey = symkeyStore.generateSymkey(room.getId(), 1L);
            if (symkey == null) {
                notifyError("Failed to generate room symkey");
                return null;
            }
        }

        saveRoom(room);

        if (room.getMembers() != null) {
            for (String member : room.getMembers()) {
                if (!member.equals(liveFid)) {
                    boolean queued = shareRoomInfo(room.getId(), member);
                    if (!queued) {
                        TimberLogger.w(TAG, "Room invite for %s to %s could not be queued; will not be delivered", room.getId(), member);
                    }
                }
            }
        }

        return room;
    }

    /**
     * Create a new room (simple version without members or home).
     */
    public Room createRoom(String name, String desc) {
        return createRoom(name, desc, null, null);
    }
    
    /**
     * Update room name, description and/or home (owner only).
     * Shares updated room info with all members after updating.
     */
    public boolean updateRoom(String roomId, String name, String desc,
                              java.util.Map<String, String> home) {
        Room room = getRoom(roomId);
        if (room == null) {
            notifyError("Room not found");
            return false;
        }

        if (!room.isOwner(liveFid)) {
            notifyError("Only room owner can update room");
            return false;
        }

        if (name != null && !name.isEmpty()) room.setName(name);
        if (desc != null) room.setDesc(desc);
        if (home != null) room.setHome(home);
        room.setLastUpdated(System.currentTimeMillis());
        saveRoom(room);

        shareRoomInfoToAll(roomId);
        return true;
    }

    public boolean updateRoom(String roomId, String name, String desc) {
        return updateRoom(roomId, name, desc, null);
    }

    /**
     * Add a member to a room (owner only).
     * Shares room info (including symkey) with the new member.
     */
    public boolean addMember(String roomId, String memberFid) {
        return addMembers(roomId, java.util.Collections.singletonList(memberFid)) > 0;
    }

    /**
     * Add multiple members to a room (owner only).
     * All members are added first, then room info is shared once with the final member list.
     * @return the number of members successfully added
     */
    public int addMembers(String roomId, java.util.List<String> memberFids) {
        Room room = getRoom(roomId);
        if (room == null) {
            notifyError("Room not found");
            return 0;
        }

        if (!room.isOwner(liveFid)) {
            notifyError("Only room owner can add members");
            return 0;
        }

        java.util.List<String> newlyAdded = new java.util.ArrayList<>();
        for (String fid : memberFids) {
            if (room.addMember(fid)) {
                room.addPendingMember(fid);
                newlyAdded.add(fid);
            }
        }

        if (!newlyAdded.isEmpty()) {
            saveRoom(room);
            shareRoomInfoToAll(roomId);
        }

        return newlyAdded.size();
    }
    
    /**
     * Remove a member from a room (owner only).
     * Notifies the removed member with ROOM_REMOVED, rotates the symkey and
     * shares updated room info with remaining members.
     */
    public boolean removeMember(String roomId, String memberFid) {
        Room room = getRoom(roomId);
        if (room == null) {
            notifyError("Room not found");
            return false;
        }

        if (!room.isOwner(liveFid)) {
            notifyError("Only room owner can remove members");
            return false;
        }

        if (room.removeMember(memberFid)) {
            saveRoom(room);
            if (controlSender != null) {
                controlSender.sendControl(ImMessage.createRoomRemoved(
                        ImType.P2P, liveFid, memberFid, roomId));
            }
            rotateSymkey(roomId);
            return true;
        }

        return false;
    }
    
    /**
     * Rotate the room symkey (owner only).
     * Generates a new key and distributes it with updated room info to all members.
     */
    public boolean rotateSymkey(String roomId) {
        Room room = getRoom(roomId);
        if (room == null || !room.isOwner(liveFid)) {
            return false;
        }
        
        if (symkeyStore == null) return false;
        
        long newVersion = symkeyStore.getCurrentVersion(roomId) + 1;
        byte[] symkey = symkeyStore.generateSymkey(roomId, newVersion);
        
        if (symkey == null) return false;
        
        room.rotateSymkey(null);
        saveRoom(room);
        
        shareRoomInfoToAll(roomId);
        
        return true;
    }
    
    /**
     * Share symkey with a member via P2P.
     */
    private void shareSymkeyWithMember(String roomId, String memberFid) {
        if (fapiClient == null || symkeyStore == null) return;

        long version = symkeyStore.getCurrentVersion(roomId);

        try {
            String memberPubkey = fapiClient.getPubkey(memberFid);
            if (memberPubkey == null) {
                TimberLogger.w(TAG, "Cannot get pubkey for member %s, skipping room symkey distribution", memberFid);
                return;
            }

            String cipher = symkeyStore.createShareCipher(roomId, version, memberPubkey);
            if (cipher == null) return;

            String symkeyData = roomId + ":" + cipher;
            ImMessage shareMsg = ImMessage.createSymkey(
                    ImType.P2P,
                    liveFid,
                    memberFid,
                    symkeyData,
                    version
            );

            if (p2pSender != null) {
                p2pSender.sendP2p(shareMsg);
                TimberLogger.d(TAG, "Distributed symkey v%d for room %s to %s", version, roomId, memberFid);
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to distribute room symkey to %s: %s", memberFid, e.getMessage());
        }
    }
    
    /**
     * Create and distribute symkey for a room (owner only).
     */
    public boolean createSymkey(String roomId) {
        Room room = getRoom(roomId);
        if (room == null || !room.isOwner(liveFid)) {
            notifyError("Only room owner can create symkey");
            return false;
        }

        if (symkeyStore == null) return false;

        long newVersion = symkeyStore.getCurrentVersion(roomId) + 1;
        byte[] symkey = symkeyStore.generateSymkey(roomId, newVersion);

        if (symkey == null) {
            notifyError("Failed to generate symkey");
            return false;
        }

        if (room.getMembers() != null) {
            for (String member : room.getMembers()) {
                if (!member.equals(liveFid)) {
                    shareSymkeyWithMember(roomId, member);
                }
            }
        }

        TimberLogger.d(TAG, "Created symkey for room %s version %d", roomId, newVersion);
        return true;
    }

    /**
     * Close a room (owner only).
     * Notifies every member with a P2P ROOM_DISBAND, and additionally posts one
     * ROOM_DISBAND through the room channel (encrypted under the room symkey)
     * so offline members learn of the closure on their next DOCK fetch.
     * The room is then deactivated locally.
     */
    public boolean closeRoom(String roomId) {
        Room room = getRoom(roomId);
        if (room == null || !room.isOwner(liveFid)) {
            return false;
        }

        if (isInactive(room)) {
            return true; // already closed
        }

        if (controlSender != null) {
            if (room.getMembers() != null) {
                for (String member : room.getMembers()) {
                    if (!member.equals(liveFid)) {
                        controlSender.sendControl(ImMessage.createRoomDisband(
                                ImType.P2P, liveFid, member, roomId));
                    }
                }
            }
            // Room-channel fallback for members who are offline right now
            if (symkeyStore != null && symkeyStore.hasSymkey(roomId)) {
                controlSender.sendControl(ImMessage.createRoomDisband(
                        ImType.ROOM, liveFid, roomId, roomId));
            }
        }

        room.setActive(false);
        saveRoom(room);
        roomCache.remove(roomId);

        TimberLogger.i(TAG, "Room %s closed, ROOM_DISBAND sent to members", roomId);
        return true;
    }

    /**
     * Leave a room.
     * Sends a ROOM_LEAVE notification to the owner so the owner can
     * remove this member and broadcast updated roomInfo to remaining members.
     */
    public boolean leaveRoom(String roomId) {
        Room room = getRoom(roomId);
        if (room == null) return false;

        if (room.isOwner(liveFid)) {
            return closeRoom(roomId);
        }

        String ownerFid = room.getOwner();
        if (ownerFid != null && controlSender != null) {
            ImMessage leaveMsg = ImMessage.createRoomLeave(
                    ImType.P2P, liveFid, ownerFid, roomId);
            controlSender.sendControl(leaveMsg);
        }

        room.removeMember(liveFid);
        room.setActive(false);
        saveRoom(room);
        roomCache.remove(roomId);

        return true;
    }

    /**
     * Handle an incoming ROOM_LEAVE message from a member.
     * Only the room owner processes this: removes the member, rotates the symkey,
     * and broadcasts updated roomInfo to remaining members.
     */
    public boolean handleRoomLeaveMessage(ImMessage message) {
        if (message == null || message.getContent() == null) return false;

        String roomId = message.getContent();
        String leavingFid = message.getSenderId();
        if (leavingFid == null) return false;

        Room room = getRoom(roomId);
        if (room == null || !room.isOwner(liveFid)) return false;
        if (isInactive(room)) return false;
        if (!room.isMember(leavingFid)) return false;

        room.removeMember(leavingFid);
        saveRoom(room);
        rotateSymkey(roomId);

        TimberLogger.i(TAG, "Member %s left room %s, symkey rotated and roomInfo shared", leavingFid, roomId);
        return true;
    }

    /**
     * Handle an incoming ROOM_ACCEPT message from an invitee.
     * Only the room owner processes this: marks the pending member as confirmed.
     *
     * @return true if the member was pending and is now newly confirmed
     */
    public boolean handleRoomAcceptMessage(ImMessage message) {
        if (message == null || message.getContent() == null) return false;

        String roomId = message.getContent();
        String joinerFid = message.getSenderId();
        if (joinerFid == null) return false;

        Room room = getRoom(roomId);
        if (room == null || !room.isOwner(liveFid)) return false;
        if (isInactive(room)) return false;
        if (!room.isMember(joinerFid)) {
            TimberLogger.w(TAG, "ROOM_ACCEPT from non-member %s for room %s ignored", joinerFid, roomId);
            return false;
        }

        boolean confirmed = room.confirmMember(joinerFid);
        if (confirmed) {
            saveRoom(room);
            TimberLogger.i(TAG, "Member %s confirmed joining room %s", joinerFid, roomId);
        }
        return confirmed;
    }

    /**
     * Handle an incoming ROOM_DISBAND message.
     * Only valid when the sender is the room owner: deactivates the local room.
     * Symkeys are retained so message history stays readable.
     *
     * @return true if the room was active and is now closed
     */
    public boolean handleRoomDisbandMessage(ImMessage message) {
        if (message == null || message.getContent() == null) return false;

        String roomId = message.getContent();
        String senderFid = message.getSenderId();
        if (senderFid == null) return false;

        Room room = getRoom(roomId);
        if (room == null) return false;
        if (!room.isOwner(senderFid)) {
            TimberLogger.w(TAG, "Rejected ROOM_DISBAND from non-owner %s for room %s", senderFid, roomId);
            return false;
        }
        if (liveFid.equals(senderFid)) return false; // own echo from the room channel
        if (isInactive(room)) return false; // already closed

        room.setActive(false);
        saveRoom(room);
        roomCache.remove(roomId);

        TimberLogger.i(TAG, "Room %s disbanded by owner %s", roomId, senderFid);
        return true;
    }

    /**
     * Handle an incoming ROOM_REMOVED message.
     * Only valid when the sender is the room owner: this user was removed
     * from the room, so deactivate it locally. Symkeys are retained for history.
     *
     * @return true if the room was active and is now closed locally
     */
    public boolean handleRoomRemovedMessage(ImMessage message) {
        if (message == null || message.getContent() == null) return false;

        String roomId = message.getContent();
        String senderFid = message.getSenderId();
        if (senderFid == null) return false;

        Room room = getRoom(roomId);
        if (room == null) return false;
        if (!room.isOwner(senderFid)) {
            TimberLogger.w(TAG, "Rejected ROOM_REMOVED from non-owner %s for room %s", senderFid, roomId);
            return false;
        }
        if (room.isOwner(liveFid)) return false; // owner can't be removed
        if (isInactive(room)) return false; // already closed

        room.removeMember(liveFid);
        room.setActive(false);
        saveRoom(room);
        roomCache.remove(roomId);

        TimberLogger.i(TAG, "Removed from room %s by owner %s", roomId, senderFid);
        return true;
    }
    
    /**
     * Accept room invitation (with room info and symkey).
     */
    public boolean acceptInvitation(Room room, byte[] symkey) {
        if (room == null || symkey == null) return false;
        
        if (symkeyStore != null) {
            symkeyStore.storeSymkey(room.getId(), room.getSymkeyVersion(), symkey, true);
        }
        
        saveRoom(room);
        
        return true;
    }
    
    /**
     * Fetch new messages for a room from DOCK.
     */
    public List<ImMessage> fetchRoomMessages(String roomId, Long since) {
        if (fapiClient == null) return null;
        
        try {
            java.util.List<String> recipientIds = java.util.Collections.singletonList(roomId);
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
                        handleIncoming(msg);
                        messages.add(msg);
                    }
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to parse room message: %s", e.getMessage());
                }
            }
            
            return messages;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to fetch room messages: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Get all rooms.
     */
    public List<Room> getAllRooms() {
        return new java.util.ArrayList<>(roomCache.values());
    }

    /**
     * Get all closed/inactive rooms from DB (not from cache, which only holds active rooms).
     */
    public List<Room> getClosedRooms() {
        List<Room> closed = new java.util.ArrayList<>();
        try {
            Map<String, Room> all = roomsDb.getAll();
            for (Room room : all.values()) {
                if (room != null && room.getActive() != null && !room.getActive()) {
                    closed.add(room);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load closed rooms: %s", e.getMessage());
        }
        return closed;
    }
    
    /**
     * Check if a room has been closed/disbanded locally.
     */
    private static boolean isInactive(Room room) {
        return room.getActive() != null && !room.getActive();
    }

    /**
     * Get a room by ID.
     */
    public Room getRoom(String roomId) {
        Room room = roomCache.get(roomId);
        if (room == null) {
            room = roomsDb.get(roomId);
            if (room != null && (room.getActive() == null || room.getActive())) {
                roomCache.put(roomId, room);
            }
        }
        return room;
    }
    
    /**
     * Save room to database and cache.
     */
    private void saveRoom(Room room) {
        roomsDb.put(room.getId(), room);
        if (room.getActive() == null || room.getActive()) {
            roomCache.put(room.getId(), room);
        }
    }
    
    /**
     * Clear room cache.
     */
    public void clearCache() {
        roomCache.clear();
        loadRoomsIntoCache();
    }
    
    /**
     * Resolve the DOCK URL for a room using HomeServiceResolver.
     */
    private String resolveRoomDockUrl(Room room) {
        if (room == null || room.getHome() == null || fapiClient == null) {
            return null;
        }
        return fapiClient.getHomeServiceResolver().resolveDockFromHome(room.getHome(), fapiClient);
    }
}
