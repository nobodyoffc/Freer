package com.fc.freer.im.handler;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.im.KeyAskStore;
import com.fc.freer.im.KeyLedger;
import com.fc.freer.im.SymkeyStore;
import com.fc.freer.im.dock.DockServiceRegistry;

/**
 * Base handler for message type-specific logic.
 * 
 * Encryption scheme:
 * 1. P2P via FUDP: Encrypted at FUDP layer with sender's prikey + recipient's pubkey. No app-level encryption needed.
 * 2. P2P via ROAD: Data to ROAD encrypted at FUDP layer (sender prikey + ROAD pubkey).
 *    Message is encrypted with sender prikey + recipient pubkey, wrapped in ROAD data.
 * 3. P2P via DOCK: Data to DOCK encrypted at FUDP layer, but that session ENDS AT THE
 *    DOCK — the server decrypts it to store the payload (it verifies dataHash over the
 *    plaintext bytes and later serves them back verbatim). So the body is encrypted with
 *    sender prikey + recipient pubkey before sending, exactly as for ROAD.
 * 4. Team/Room via DOCK: Data to DOCK encrypted at FUDP layer.
 *    Message content encrypted with symkey before sending.
 * 5. Group via DOCK: Data to DOCK encrypted at FUDP layer.
 *    Message content is plain text (no symkey) — anyone may join a square, so there is
 *    no secret to keep.
 *
 * Case 3 was missing from this list, and its absence was the bug: the "FUDP handles it"
 * reasoning in case 1 is true only when the FUDP session is peer-to-peer, and it was
 * applied to the DOCK path, where it is not. Since FUDP_DIRECT and ROAD_RELAY are both
 * opt-in and off by default, DOCK is the path nearly every P2P message actually takes —
 * so nearly every P2P message was being stored on the DOCK operator's disk in clear text.
 */
public abstract class BaseHandler {
    protected static final String TAG = "ImHandler";
    
    protected final Context context;
    protected final String liveFid;
    protected FudpNode fudpNode;
    protected FapiClient fapiClient;
    protected SymkeyStore symkeyStore;

    /**
     * Where every key in and out is recorded -- FIMP §9.7. Set alongside the store; a handler
     * that answers a request writes here whether it shared or refused, because a refusal is
     * part of the same record.
     */
    protected KeyLedger keyLedger;

    public void setKeyLedger(KeyLedger ledger) {
        this.keyLedger = ledger;
    }

    /**
     * The questions this device has outstanding. A handler that stores a key clears the ask it
     * answered -- on the key being <b>stored</b>, not on it arriving, since a cipher that would
     * not open left us no better off.
     */
    protected KeyAskStore keyAskStore;

    public void setKeyAskStore(KeyAskStore store) {
        this.keyAskStore = store;
    }

    /**
     * The live FID's private key. Every handler signs each envelope it sends
     * with it (FIMP0V3, see {@link #signedWire}), and the P2P handler also
     * seals bodies to a specific recipient with it (AsyTwoWay needs our
     * private half, not a shared secret). Set on all four handlers by
     * ImManager alongside {@link SymkeyStore#setUserPrikey(byte[])}.
     */
    protected byte[] userPrikey;

    public void setUserPrikey(byte[] prikey) {
        this.userPrikey = prikey;
    }

    /**
     * The message as it goes on the wire: FIMP0V3 envelope, signed with the
     * live FID's key. Every receiver checks that signature before anything
     * else, so an envelope that cannot be signed is not sent at all.
     *
     * @return the signed bytes, or null when there is no key or the encode
     *         fails (an over-long field, or a sender that is not the live FID)
     */
    protected byte[] signedWire(ImMessage message) {
        if (userPrikey == null) {
            TimberLogger.e(TAG, "Cannot sign message %s: no private key for %s", message.getId(), liveFid);
            return null;
        }
        try {
            return message.toWireBytes(userPrikey);
        } catch (RuntimeException e) {
            TimberLogger.e(TAG, "Cannot encode message %s: %s", message.getId(), e.getMessage());
            return null;
        }
    }


    // Listener for incoming messages
    protected MessageListener messageListener;

    // Sender for P2P messages (used by Team/Room handlers to send symkey responses)
    protected P2pSender p2pSender;

    protected SymkeyRequester symkeyRequester;
    protected ControlMessageSender controlSender;
    
    public interface MessageListener {
        void onMessageReceived(ImMessage message);
        void onMessageSent(ImMessage message);

        /**
         * Delivery progress reported for a message we sent.
         *
         * @param peerId the peer the progress came from — the receipt's sender,
         *               or the peer that acked at the transport layer. Message
         *               ids are only locally unique, so the listener must check
         *               this against the message's target before applying the
         *               status: otherwise any peer that guesses an id can mark
         *               a message we sent to somebody else as read.
         */
        void onDeliveryStatusChanged(String peerId, String messageId,
                                     com.fc.fc_ajdk.data.fcData.MessageStatus status);
        void onError(String error);
    }

    public interface P2pSender {
        com.fc.freer.im.MessageQueue.SendResult sendP2p(ImMessage message);
    }

    public interface SymkeyRequester {
        /**
         * Ask for the symkey of {@code entityId}.
         *
         * @param version  the version that could not be opened, or null to ask
         *                 for whatever the responder currently holds. Naming it
         *                 is what FIMP4V3 §7.4 requires: a request with no
         *                 version is answered with the responder's current key,
         *                 which is usually the one this device already has, so a
         *                 member missing an older version would never recover it.
         * @param fromFid  who to put the question to, or null for the entity's
         *                 owner. On filing a sealed message we cannot open, this
         *                 is <b>its sender</b>: they provably hold that key,
         *                 whereas the owner may have rotated past it or be gone.
         */
        void requestSymkey(String entityId, Long version, String fromFid);
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
     * Open a sealed body with whichever key held at this version actually opens it.
     *
     * <p>Two keys may share one version -- two devices of one owner minting in the same second
     * -- so a version names <i>candidates</i> rather than a key, and the body's AES-GCM tag is
     * what decides between them. In every real case there is one candidate and one attempt.
     *
     * @return true when the message now carries its plaintext
     */
    protected boolean openSealed(ImMessage message, String entityId, long version) {
        if (message == null || symkeyStore == null) return false;
        for (byte[] symkey : symkeyStore.getSymkeys(entityId, version)) {
            if (com.fc.fc_ajdk.data.fcData.ImMessageBody.openWithSymkey(message, symkey)) return true;
        }
        return false;
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
     * P2P seals per-recipient in P2pHandler instead (AsyTwoWay, not a shared key).
     * Group messages are plain text - no encryption.
     */
    protected String encrypt(String content, String targetId) {
        // Override in TeamHandler/RoomHandler for symkey encryption
        return content;
    }

    /**
     * Decrypt message content (for team/room with symkey).
     * P2P opens per-recipient in P2pHandler instead.
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

    /**
     * Whether an entity's (square/team/recipient) home map declares a DOCK
     * service. A declared-but-unresolvable DOCK (e.g. a non-existent SID) means
     * the server is broken, which callers report differently from an entity that
     * has no DOCK configured at all.
     */
    protected static boolean hasDockConfigured(java.util.Map<String, String> home) {
        if (home == null) return false;
        for (java.util.Map.Entry<String, String> e : home.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            if (key != null && key.startsWith("DOCK") && val != null && !val.isEmpty()) {
                return true;
            }
        }
        return false;
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
    protected void notifyStatusChange(String peerId, String messageId,
                                      com.fc.fc_ajdk.data.fcData.MessageStatus status) {
        if (messageListener != null) {
            messageListener.onDeliveryStatusChanged(peerId, messageId, status);
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
