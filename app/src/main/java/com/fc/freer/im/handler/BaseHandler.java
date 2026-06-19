package com.fc.freer.im.handler;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.im.SymkeyStore;
import com.fc.freer.im.dock.DockServiceRegistry;

/**
 * Base handler for message type-specific logic.
 * 
 * Encryption scheme:
 * 1. P2P via FUDP: Encrypted at FUDP layer with sender's prikey + recipient's pubkey. No app-level encryption needed.
 * 2. P2P via ROAD: Data to ROAD encrypted at FUDP layer (sender prikey + ROAD pubkey).
 *    Message is encrypted with sender prikey + recipient pubkey, wrapped in ROAD data.
 * 3. Team/Room via DOCK: Data to DOCK encrypted at FUDP layer.
 *    Message content encrypted with symkey before sending.
 * 4. Group via DOCK: Data to DOCK encrypted at FUDP layer.
 *    Message content is plain text (no symkey).
 */
public abstract class BaseHandler {
    protected static final String TAG = "ImHandler";
    
    protected final Context context;
    protected final String liveFid;
    protected FudpNode fudpNode;
    protected FapiClient fapiClient;
    protected SymkeyStore symkeyStore;
    
    // Listener for incoming messages
    protected MessageListener messageListener;

    // Sender for P2P messages (used by Team/Room handlers to send symkey responses)
    protected P2pSender p2pSender;

    protected SymkeyRequester symkeyRequester;
    protected ControlMessageSender controlSender;
    
    public interface MessageListener {
        void onMessageReceived(ImMessage message);
        void onMessageSent(ImMessage message);
        void onDeliveryStatusChanged(String messageId, com.fc.fc_ajdk.data.fcData.MessageStatus status);
        void onError(String error);
    }

    public interface P2pSender {
        com.fc.freer.im.MessageQueue.SendResult sendP2p(ImMessage message);
    }

    public interface SymkeyRequester {
        void requestSymkey(String entityId);
    }

    /**
     * Sender for room control messages (ROOM_LEAVE, ROOM_ACCEPT, ROOM_DISBAND,
     * ROOM_REMOVED) routed through ImManager.send() so they go through the
     * message queue without being stored as chat messages.
     */
    public interface ControlMessageSender {
        void sendControl(ImMessage controlMessage);
    }

    /**
     * Checks whether the target DOCK URL is reachable (has a working client).
     * Used by group chat handlers to prevent sending when the sender can't fetch replies.
     */
    public interface DockAvailabilityChecker {
        boolean isDockReachable(String dockUrl);
    }

    protected DockAvailabilityChecker dockAvailabilityChecker;
    protected DockServiceRegistry dockRegistry;

    public void setDockAvailabilityChecker(DockAvailabilityChecker checker) {
        this.dockAvailabilityChecker = checker;
    }

    public void setDockRegistry(DockServiceRegistry registry) {
        this.dockRegistry = registry;
    }

    protected BaseHandler(Context context, String liveFid) {
        this.context = context;
        this.liveFid = liveFid;
    }
    
    /**
     * Initialize the handler with required components.
     */
    public void initialize(FudpNode fudpNode, FapiClient fapiClient, SymkeyStore symkeyStore) {
        this.fudpNode = fudpNode;
        this.fapiClient = fapiClient;
        this.symkeyStore = symkeyStore;
    }
    
    /**
     * Set the message listener.
     */
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    /**
     * Set the P2P sender for sending symkey responses through the proper delivery chain.
     */
    public void setP2pSender(P2pSender sender) {
        this.p2pSender = sender;
    }

    public void setSymkeyRequester(SymkeyRequester requester) {
        this.symkeyRequester = requester;
    }

    public void setControlMessageSender(ControlMessageSender sender) {
        this.controlSender = sender;
    }
    
    /**
     * Get the type this handler manages.
     */
    public abstract ImType getType();
    
    /**
     * Send a message.
     * @return SendResult indicating success, transient failure, or permanent failure
     */
    public abstract com.fc.freer.im.MessageQueue.SendResult send(ImMessage message);
    
    /**
     * Handle an incoming message.
     */
    public abstract void handleIncoming(ImMessage message);
    
    /**
     * Encrypt message content (for team/room with symkey).
     * P2P encryption is handled by FUDP layer - no app-level encryption needed.
     * Group messages are plain text - no encryption.
     */
    protected String encrypt(String content, String targetId) {
        // Override in TeamHandler/RoomHandler for symkey encryption
        return content;
    }
    
    /**
     * Decrypt message content (for team/room with symkey).
     * P2P decryption is handled by FUDP layer.
     * Group messages are plain text - no decryption.
     */
    protected String decrypt(String cipher, String targetId, Long symkeyVersion) {
        // Override in TeamHandler/RoomHandler for symkey decryption
        return cipher;
    }
    
    /**
     * Get the DOCK client for an organization entity (team/room/square).
     * Prefers the org's own DOCK client from the registry (direct send, cheaper).
     * Falls back to the user's own DOCK client (forwarding via targetDockUrl).
     *
     * @param entityType "team", "room", or "square"
     * @param entityId   the entity's ID
     * @return OrgDockInfo with the client to use and optional targetDockUrl for forwarding
     */
    protected OrgDockInfo getOrgDockClient(String entityType, String entityId) {
        // Try org's DOCK directly from registry
        if (dockRegistry != null) {
            String orgDockUrl = dockRegistry.getDockUrl(entityType, entityId);
            if (orgDockUrl != null) {
                FapiClient orgClient = dockRegistry.getClientForDock(orgDockUrl);
                if (orgClient != null) {
                    return new OrgDockInfo(orgClient, null); // direct send, no forwarding
                }
            }
        }

        // Fallback: use user's own DOCK with forwarding
        return null;
    }

    protected static class OrgDockInfo {
        public final FapiClient client;
        public final String targetDockUrl; // null = direct send; non-null = forward

        OrgDockInfo(FapiClient client, String targetDockUrl) {
            this.client = client;
            this.targetDockUrl = targetDockUrl;
        }
    }

    /**
     * Notify listener of received message.
     */
    protected void notifyReceived(ImMessage message) {
        if (messageListener != null) {
            messageListener.onMessageReceived(message);
        }
    }
    
    /**
     * Notify listener of sent message.
     */
    protected void notifySent(ImMessage message) {
        if (messageListener != null) {
            messageListener.onMessageSent(message);
        }
    }
    
    /**
     * Notify listener of status change.
     */
    protected void notifyStatusChange(String messageId, com.fc.fc_ajdk.data.fcData.MessageStatus status) {
        if (messageListener != null) {
            messageListener.onDeliveryStatusChanged(messageId, status);
        }
    }
    
    /**
     * Notify listener of error.
     */
    protected void notifyError(String error) {
        TimberLogger.e(TAG, error);
        if (messageListener != null) {
            messageListener.onError(error);
        }
    }
}
