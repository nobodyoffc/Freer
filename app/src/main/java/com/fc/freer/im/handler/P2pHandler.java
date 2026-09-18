package com.fc.freer.im.handler;

import static com.fc.fc_ajdk.constants.Constants.ROAD_NO1_NRC7;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.DeliveryMethod;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImMessageBody;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.TalkPartner;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.MessageQueue.SendResult;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ApiCenter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for P2P (person-to-person) direct messaging.
 *
 * Default channel is DOCK (store-and-forward). FUDP_DIRECT and ROAD_RELAY
 * are opt-in via Setting (isUseFudpDirect / isUseRoadRelay) and, when
 * enabled, are tried before DOCK. All channels carry the same compact
 * binary ImMessage envelope (toWireBytes / fromWireBytes); there is no
 * legacy text fast path.
 *
 * Encryption depends on the route, because the routes do not all end in the
 * same place. FUDP_DIRECT is already sender-to-recipient, so the transport is
 * the encryption. ROAD and DOCK are not: each terminates its FUDP session at a
 * third party that decrypts the request to do its job — the DOCK verifies a
 * hash over the plaintext payload, stores it, and serves those bytes back on
 * fetch. So bodies going out over ROAD or DOCK are sealed to the recipient
 * first (AsyTwoWay: our prikey + their pubkey), and opened again on arrival.
 * See {@link BaseHandler} for the full per-route table.
 */
public class P2pHandler extends BaseHandler {

    private final Map<String, PeerInfo> peerBook = new ConcurrentHashMap<>();

    private TalkPartnerProvider talkPartnerProvider;
    private TalkPartnerHomeUpdater talkPartnerHomeUpdater;

    public interface TalkPartnerProvider {
        TalkPartner getTalkPartner(String fid);
    }

    /** Persists a freshly-fetched recipient home back onto the stored TalkPartner. */
    public interface TalkPartnerHomeUpdater {
        void updateTalkPartnerHome(String fid, Map<String, String> home);
    }

    public P2pHandler(Context context, String liveFid) {
        super(context, liveFid);
    }

    public void setTalkPartnerProvider(TalkPartnerProvider provider) {
        this.talkPartnerProvider = provider;
    }

    public void setTalkPartnerHomeUpdater(TalkPartnerHomeUpdater updater) {
        this.talkPartnerHomeUpdater = updater;
    }

    @Override
    public ImType getType() {
        return ImType.P2P;
    }

    @Override
    public SendResult send(ImMessage message) {
        if (message == null || message.getTargetId() == null) {
            notifyError("Invalid message or target");
            return SendResult.FAIL_PERMANENT;
        }

        // markAsRead bypasses ImManager.send so we may need to allocate an id here.
        if (!message.hasFudpId() && fudpNode != null) {
            message.setIdFromLong(fudpNode.generateMessageId());
        }

        String targetFid = message.getTargetId();
        TimberLogger.d(TAG, "P2P send: target=%s, contentType=%s, id=%s",
                targetFid, message.getContentType(), message.getId());

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        boolean useFudp = setting != null && setting.isUseFudpDirect();
        boolean useRoad = setting != null && setting.isUseRoadRelay();

        byte[] envelope = signedWire(message);
        if (envelope == null) {
            notifyError("Cannot sign message");
            return SendResult.FAIL_PERMANENT;
        }

        // 1. Optional FUDP_DIRECT.
        if (useFudp && isFudpAllowed() && hasTargetFudpRegistered(targetFid) && fudpNode != null) {
            try {
                long fudpMsgId = fudpNode.sendNotifyWithAck(targetFid, envelope);
                TimberLogger.d(TAG, "Sent via FUDP_DIRECT: fudpMsgId=%d, id=%s",
                        fudpMsgId, message.getId());
                message.setDeliveryMethod(DeliveryMethod.FUDP_DIRECT);
                message.setStatus(MessageStatus.SENT);
                notifySent(message);
                PeerInfo peer = peerBook.get(targetFid);
                if (peer != null) {
                    peer.setLastSeen(System.currentTimeMillis());
                    peer.setLastDeliveryMethod(DeliveryMethod.FUDP_DIRECT);
                }
                return SendResult.SUCCESS;
            } catch (Exception e) {
                TimberLogger.w(TAG, "FUDP send failed: %s", e.getMessage());
            }
        }

        String targetDockUrl = null;
        String targetRoadUrl = null;
        Map<String, String> recipientHome = null;
        Map<String, String> freshHome = null; // set when we fetch a fresh freer from chain
        TalkPartner partner = (talkPartnerProvider != null) ? talkPartnerProvider.getTalkPartner(targetFid) : null;
        if (partner != null && partner.getHome() != null && fapiClient != null) {
            recipientHome = partner.getHome();
            HomeServiceResolver resolver = fapiClient.getHomeServiceResolver();
            targetDockUrl = resolver.resolveDockFromHome(partner.getHome(), fapiClient);
            if (useRoad) {
                targetRoadUrl = resolver.resolveFromHome(partner.getHome(), ROAD_NO1_NRC7, fapiClient);
            }
        }

        if ((targetDockUrl == null || (useRoad && targetRoadUrl == null)) && fapiClient != null) {
            try {
                com.fc.fc_ajdk.data.fchData.Freer freer = fapiClient.getFreer(targetFid);
                if (freer != null && freer.getHome() != null) {
                    recipientHome = freer.getHome();
                    freshHome = freer.getHome();
                    // Write the fresh home back so a just-registered DOCK is not
                    // re-fetched on every send and the partner list/registry update.
                    persistFreshHome(targetFid, freshHome);
                    HomeServiceResolver resolver = fapiClient.getHomeServiceResolver();
                    if (useRoad && targetRoadUrl == null) {
                        targetRoadUrl = resolver.resolveFromHome(freer.getHome(), ROAD_NO1_NRC7, fapiClient);
                    }
                    if (targetDockUrl == null) {
                        targetDockUrl = resolver.resolveDockFromHome(freer.getHome(), fapiClient);
                    }
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to resolve target home: %s", e.getMessage());
            }
        }

        // Everything from here leaves the message with a third party — a ROAD
        // relay or a DOCK server — both of which terminate their own FUDP
        // session and see the payload. So the body is sealed to the recipient
        // now. The FUDP_DIRECT attempt above needed none of this: that session
        // already runs sender-to-recipient.
        //
        // A body we cannot seal is not sent in the clear as a fallback. Failing
        // to encrypt is exactly the bug this closes, and a message that silently
        // downgrades is worse than one that visibly does not go.
        //
        // Only when a third-party route actually exists: with no ROAD and no
        // DOCK there is nothing to seal for, and sealing first would replace
        // the accurate "no route" report below with a misleading crypto error.
        boolean hasRelayRoute = targetDockUrl != null || (useRoad && targetRoadUrl != null);
        byte[] relayEnvelope = hasRelayRoute ? sealedEnvelope(message, targetFid) : null;
        if (hasRelayRoute && relayEnvelope == null) {
            message.setStatus(MessageStatus.FAILED);
            // Receipts are best-effort background traffic; a toast for one
            // would be noise the user cannot act on.
            if (message.getContentType() != ContentType.RECEIPT) {
                notifyError(context.getString(R.string.im_cannot_encrypt_for_recipient));
            }
            TimberLogger.e(TAG, "Refusing to send %s to %s unencrypted", message.getId(), targetFid);
            return SendResult.FAIL_PERMANENT;
        }

        // 2. Optional ROAD_RELAY.
        if (useRoad && targetRoadUrl != null) {
            FapiClient roadClient = ApiCenter.getInstance().getClient(ApiCenter.ConnectionRole.ROAD);
            if (roadClient != null) {
                try {
                    FapiClient.RoadRelayResult result = roadClient.roadRelay(targetFid, relayEnvelope, targetRoadUrl);
                    if (result != null && result.success()) {
                        message.setDeliveryMethod(DeliveryMethod.ROAD_RELAY);
                        message.setStatus(MessageStatus.SENT);
                        notifySent(message);
                        return SendResult.SUCCESS;
                    }
                } catch (Exception e) {
                    TimberLogger.w(TAG, "ROAD relay failed: %s", e.getMessage());
                }
            }
        }

        // 3. DOCK_STORED — default channel. Preference order:
        //    a. Direct put to the recipient's own DOCK. One hop, and the sender
        //       pays only the recipient DOCK's ingress+storage (no extra local
        //       forwarding fees). Works even when the sender's own DOCK has
        //       forwarding disabled or the sender can't afford the forward fees.
        //       A client to the target DOCK is bootstrapped on demand if one
        //       isn't already cached — this is the reliable cross-DOCK path and
        //       must not depend on whether the own DOCK forwards.
        //    b. Own DOCK with targetDockUrl — the server forwards synchronously and
        //       returns the remote dockId, so success means the item is stored remotely.
        //       Used as a fallback when a direct connection can't be established.
        FapiClient ownDockClient = ApiCenter.getInstance().getClient(ApiCenter.ConnectionRole.DOCK);

        if (targetDockUrl != null) {
            if (deliverToDock(targetDockUrl, relayEnvelope, message, targetFid, ownDockClient)) {
                return SendResult.SUCCESS;
            }

            // Delivery to the resolved DOCK failed. When that DOCK came from the
            // stored (possibly stale) TalkPartner home, the FID may have moved or
            // retired it. Refresh the freer once; if it now resolves a *different*
            // DOCK, persist the fresh home and retry against it before giving up.
            if (fapiClient != null) {
                Map<String, String> refreshed = freshHome;
                if (refreshed == null) {
                    try {
                        com.fc.fc_ajdk.data.fchData.Freer freer = fapiClient.getFreer(targetFid);
                        if (freer != null) refreshed = freer.getHome();
                    } catch (Exception e) {
                        TimberLogger.w(TAG, "Failed to refresh target home on DOCK failure: %s", e.getMessage());
                    }
                }
                if (refreshed != null) {
                    persistFreshHome(targetFid, refreshed);
                    String freshDock = fapiClient.getHomeServiceResolver()
                            .resolveDockFromHome(refreshed, fapiClient);
                    if (freshDock != null && !freshDock.equals(targetDockUrl)) {
                        TimberLogger.i(TAG, "Target DOCK changed for %s; retrying with %s", targetFid, freshDock);
                        recipientHome = refreshed;
                        targetDockUrl = freshDock;
                        if (deliverToDock(freshDock, relayEnvelope, message, targetFid, ownDockClient)) {
                            return SendResult.SUCCESS;
                        }
                    }
                }
            }
        }

        TimberLogger.e(TAG, "All delivery methods failed for message %s to %s", message.getId(), targetFid);
        message.setStatus(MessageStatus.FAILED);

        // The recipient declared a DOCK we could not resolve (e.g. a non-existent
        // SID in their home). DOCK is the only store-and-forward channel, so
        // retrying will never succeed — surface a clear error and stop, mirroring
        // the Square/Team handlers.
        if (targetDockUrl == null && hasDockConfigured(recipientHome)) {
            notifyError(context.getString(R.string.im_dock_unresolvable));
            return SendResult.FAIL_PERMANENT;
        }

        if (ownDockClient == null && targetDockUrl == null) {
            return SendResult.FAIL_PERMANENT;
        }
        return SendResult.RETRY_TRANSIENT;
    }

    /**
     * Deliver the envelope to {@code targetDockUrl}, trying a direct put to the
     * recipient's own DOCK first and falling back to an own-DOCK forward.
     * See the preference-order note at the call site.
     *
     * @return true if the item was stored (direct or forwarded)
     */
    private boolean deliverToDock(String targetDockUrl, byte[] envelope, ImMessage message,
                                  String targetFid, FapiClient ownDockClient) {
        // a. Direct put to the recipient's own DOCK.
        FapiClient directClient = (dockRegistry != null && !dockRegistry.isFailed(targetDockUrl))
                ? dockRegistry.getClientForDock(targetDockUrl) : null;
        if (directClient == null && dockRegistry != null) {
            // No cached client for the target DOCK — bootstrap one so a direct
            // put can be attempted regardless of whether an own DOCK exists.
            directClient = dockRegistry.retryBootstrap(targetDockUrl);
        }

        if (directClient != null && directClient != ownDockClient) {
            Boolean direct = putToDock(directClient, envelope, message, targetFid, null,
                    "directly in target DOCK " + targetDockUrl);
            if (Boolean.TRUE.equals(direct)) return true;
            if (direct == null && dockRegistry != null) {
                // Connection-level failure — drop the dead client so a later
                // attempt re-bootstraps instead of reusing it.
                dockRegistry.invalidateClient(targetDockUrl);
            }
        }

        // b. Ask our own DOCK to forward to the target DOCK. Runs as a recovery
        //    path when the direct attempt failed, and is also the path taken when
        //    the target IS our own DOCK (directClient == ownDockClient, so the
        //    direct branch is skipped): dockPut drops TARGET_DOCK_URL when it
        //    equals the server URL, making this a plain local store.
        if (ownDockClient != null) {
            Boolean forwarded = putToDock(ownDockClient, envelope, message, targetFid, targetDockUrl,
                    "in target DOCK " + targetDockUrl + " via own DOCK forward");
            if (Boolean.TRUE.equals(forwarded)) return true;
        }
        return false;
    }

    /**
     * Persist a freshly-fetched recipient home onto the stored TalkPartner, but
     * only when it actually differs from what is stored (avoids redundant writes).
     */
    private void persistFreshHome(String targetFid, Map<String, String> freshHome) {
        if (talkPartnerHomeUpdater == null || freshHome == null) return;
        TalkPartner partner = (talkPartnerProvider != null)
                ? talkPartnerProvider.getTalkPartner(targetFid) : null;
        Map<String, String> stored = (partner != null) ? partner.getHome() : null;
        if (!freshHome.equals(stored)) {
            talkPartnerHomeUpdater.updateTalkPartnerHome(targetFid, freshHome);
        }
    }

    /**
     * Put the message envelope into a DOCK for the target FID.
     * With forwardUrl == null the item is stored on the connected DOCK itself;
     * otherwise the connected DOCK forwards to forwardUrl and returns the
     * remote dockId on success.
     *
     * @return TRUE on success, FALSE when the server rejected the put,
     *         null on a connection-level failure
     */
    private Boolean putToDock(FapiClient dockClient, byte[] envelope, ImMessage message,
                              String targetFid, String forwardUrl, String via) {
        try {
            List<String> recipients = new ArrayList<>();
            recipients.add(targetFid);
            com.fc.fc_ajdk.data.fcData.DockItem dockItem =
                    dockClient.dockPut(envelope, recipients, null, forwardUrl, "IM");
            if (dockItem != null && dockItem.getId() != null) {
                message.setDockId(dockItem.getId());
                message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                message.setStatus(MessageStatus.SENT);
                TimberLogger.d(TAG, "Stored %s", via);
                notifySent(message);
                return Boolean.TRUE;
            }
            String err = dockClient.getLastError() != null ? dockClient.getLastError() : "no result";
            TimberLogger.w(TAG, "DOCK put failed (%s): %s", via, err);
            return Boolean.FALSE;
        } catch (Exception e) {
            TimberLogger.w(TAG, "DOCK put failed (%s): %s", via, e.getMessage());
            return null;
        }
    }

    // ========== body encryption ==========

    /**
     * Seal the body to {@code targetFid} and return the wire bytes to hand to a
     * third party (a DOCK server or a ROAD relay).
     * <p>
     * The FUDP hop to that third party is encrypted, but it terminates there:
     * the DOCK decrypts the request to store the payload, and hands those exact
     * bytes back to whoever fetches. So anything travelling this way has to be
     * sealed to the recipient first, the same way the ROAD path already was.
     * <p>
     * The message object keeps its plaintext content — we can obviously read
     * what we sent, and the sender's own transcript would otherwise show
     * "[Encrypted]" for their own message. Only the wire copy is sealed.
     *
     * @return wire bytes with the body sealed, or null when it could not be
     *         sealed — callers must treat that as a failure and must NOT fall
     *         back to sending the plaintext envelope.
     */
    private byte[] sealedEnvelope(ImMessage message, String targetFid) {
        // Nothing to seal: a message with no payload at all -- a typing ping, a
        // bare presence -- goes as it is.
        //
        // v1 also skipped sealing for a STREAM "whose payload rides in
        // dataBase64", which meant every inline binary went out in the clear.
        // In v2 both payloads are one body, so this test cannot miss one.
        if (message.getContent() == null && message.getData() == null) {
            return signedWire(message);
        }

        if (userPrikey == null) {
            TimberLogger.e(TAG, "Cannot seal message: no private key for %s", liveFid);
            return null;
        }
        byte[] pubkey = peerPubkey(targetFid);
        if (pubkey == null) {
            TimberLogger.e(TAG, "Cannot seal message: no pubkey for %s", targetFid);
            return null;
        }

        // Seal on a copy, so the caller's object keeps its plaintext --
        // notifySent and the local transcript both read that same object, and
        // the sender cannot reopen an AsyTwoWay bundle to get it back.
        ImMessage outgoing = ImMessage.fromJson(message.toJson());
        if (outgoing == null) {
            TimberLogger.e(TAG, "Cannot seal message %s: could not copy it", message.getId());
            return null;
        }
        boolean selfChat = targetFid.equals(liveFid);
        if (!ImMessageBody.sealForPeer(outgoing, userPrikey, pubkey, selfChat)) {
            TimberLogger.e(TAG, "Failed to seal message for %s", targetFid);
            return null;
        }
        // An over-long field throws instead of wrapping (v1 truncated the
        // length and corrupted everything after it); signedWire logs it.
        return signedWire(outgoing);
    }

    /**
     * Open a sealed body in place. A no-op for anything already readable, so it
     * is safe on every inbound message whatever route it arrived by -- a
     * FUDP_DIRECT message carries a plaintext body and simply passes through.
     */
    private void openBody(ImMessage message) {
        if (!message.isSealed()) return;
        if (userPrikey == null) {
            TimberLogger.w(TAG, "Cannot open sealed message: no private key for %s", liveFid);
            return;
        }
        if (ImMessageBody.openWithPrikey(message, userPrikey)) {
            // Once the plaintext is beside it the bundle is redundant, and
            // keeping it would leave the transcript holding two copies.
            message.setBody(null);
        }
    }

    /**
     * The recipient's public key, from the cheapest source that has it.
     * <p>
     * Cached in the peer book once found: it never changes, and a chain lookup
     * on every send would put a round trip in front of every message.
     */
    private byte[] peerPubkey(String targetFid) {
        PeerInfo cached = peerBook.get(targetFid);
        if (cached != null && cached.getPubkey() != null) return cached.getPubkey();

        String hex = null;
        TalkPartner partner = (talkPartnerProvider != null)
                ? talkPartnerProvider.getTalkPartner(targetFid) : null;
        if (partner != null && partner.getPubkey() != null && !partner.getPubkey().isEmpty()) {
            hex = partner.getPubkey();
        } else if (fapiClient != null) {
            try {
                hex = fapiClient.getPubkey(targetFid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Pubkey lookup failed for %s: %s", targetFid, e.getMessage());
            }
        }
        if (hex == null || hex.isEmpty()) return null;

        byte[] pubkey;
        try {
            pubkey = Hex.fromHex(hex);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Malformed pubkey for %s: %s", targetFid, e.getMessage());
            return null;
        }
        if (pubkey == null || pubkey.length != 33) return null;

        // Cached directly rather than through updatePeerBook: that also stamps
        // lastSeen, and learning someone's key off the chain is not a sighting
        // of them.
        PeerInfo info = peerBook.get(targetFid);
        if (info == null) {
            info = new PeerInfo();
            info.setFid(targetFid);
            peerBook.put(targetFid, info);
        }
        info.setPubkey(pubkey);
        return pubkey;
    }

    /**
     * Handle a NOTIFY_ACK from the FUDP layer. The FUDP message id equals the ImMessage hex id.
     */
    public void handleNotifyAck(String peerId, long fudpMessageId, long rttMs) {
        String imMessageId = ImMessage.longIdToHex(fudpMessageId);
        TimberLogger.d(TAG, "NOTIFY_ACK: imId=%s, peer=%s, rtt=%dms", imMessageId, peerId, rttMs);

        PeerInfo peer = peerBook.get(peerId);
        if (peer != null) {
            peer.setLastSeen(System.currentTimeMillis());
            peer.setLastDeliveryMethod(DeliveryMethod.FUDP_DIRECT);
        }

        notifyStatusChange(peerId, imMessageId, MessageStatus.DELIVERED);
    }

    @Override
    public void handleIncoming(ImMessage message) {
        if (message == null) return;

        // Before anything reads getContent(): a receipt is sealed like any
        // other body, so opening it after the RECEIPT branch would leave
        // read/delivered receipts permanently unreadable.
        openBody(message);

        if (message.getContentType() == ContentType.RECEIPT) {
            handleReceipt(message);
            return;
        }

        boolean isSelf = liveFid.equals(message.getSenderId());
        message.setUnread(!isSelf);
        message.setDeliveredAt(System.currentTimeMillis());

        // Send delivery receipt only for messages from others, and only on non-FUDP paths.
        // For FUDP_DIRECT, NOTIFY_ACK already confirmed delivery at the transport layer.
        if (!isSelf && message.getDeliveryMethod() != DeliveryMethod.FUDP_DIRECT) {
            sendDeliveryReceipt(message);
        }

        notifyReceived(message);
    }

    /**
     * Mark a message as read and send a read receipt.
     */
    public void markAsRead(ImMessage message) {
        if (message == null || message.getSenderId() == null) return;
        ImMessage receipt = ImMessage.createReceipt(
                liveFid, message.getSenderId(), message.getId(), true);
        enqueueReceipt(receipt);
    }

    /**
     * Apply an inbound receipt to the message it acknowledges.
     * <p>
     * The message is looked up by id in the message store rather than in any
     * in-memory table of what we sent. A receipt for a DOCK-delivered message
     * arrives only once the recipient comes back online, which may be days
     * later and across any number of restarts of this app -- an in-memory
     * table is empty exactly when it is needed, which left the sender's
     * message stuck on SENT forever.
     * <p>
     * Ordering is not guaranteed either: delivered and read receipts can take
     * different routes and overtake each other, so the listener applies the
     * status monotonically and drops one that would walk the message
     * backwards.
     */
    private void handleReceipt(ImMessage receiptMsg) {
        String originalId = receiptMsg.getRequestId();
        if (originalId == null) return;
        boolean isRead = "read".equals(receiptMsg.getContent());

        TimberLogger.d(TAG, "Receipt for %s from %s: %s",
                originalId, receiptMsg.getSenderId(), isRead ? "read" : "delivered");

        notifyStatusChange(receiptMsg.getSenderId(), originalId,
                isRead ? MessageStatus.READ : MessageStatus.DELIVERED);
    }

    private void sendDeliveryReceipt(ImMessage message) {
        ImMessage receipt = ImMessage.createReceipt(
                liveFid, message.getSenderId(), message.getId(), false);
        enqueueReceipt(receipt);
    }

    /**
     * Hand a receipt to the durable send queue instead of firing it straight
     * at the network.
     * <p>
     * A receipt is the only thing that ever moves the sender's message past
     * SENT, and nothing regenerates it: once we have recorded the message as
     * delivered locally, a receipt lost to a network flap is lost for good.
     * That flap is likely precisely here -- delivery receipts are produced
     * while draining a DOCK backlog, i.e. seconds after coming back online,
     * against a DOCK connection that may still be re-bootstrapping. The queue
     * persists across restarts and retries with backoff.
     * <p>
     * Queue entries are keyed by message id, so a receipt we could not assign
     * an id to falls back to the old best-effort direct send rather than
     * poisoning the queue with a null key.
     */
    private void enqueueReceipt(ImMessage receipt) {
        if (!receipt.hasFudpId() && fudpNode != null) {
            receipt.setIdFromLong(fudpNode.generateMessageId());
        }
        if (p2pSender != null && receipt.hasFudpId()) {
            // Returns immediately: the queue owns the send from here, which
            // also keeps a backlog of receipts off the caller's thread.
            p2pSender.sendP2p(receipt);
            return;
        }
        new Thread(() -> send(receipt)).start();
    }

    public void updatePeerBook(String fid, InetSocketAddress address, String pubkey) {
        updatePeerBook(fid, address, pubkey, null);
    }

    public void updatePeerBook(String fid, InetSocketAddress address, String pubkey, DeliveryMethod deliveryMethod) {
        PeerInfo existing = peerBook.get(fid);
        PeerInfo info = (existing != null) ? existing : new PeerInfo();
        info.setFid(fid);
        if (address != null) info.setAddress(address);
        if (pubkey != null) info.setPubkey(Hex.fromHex(pubkey));
        if (deliveryMethod != null) info.setLastDeliveryMethod(deliveryMethod);
        info.setLastSeen(System.currentTimeMillis());
        peerBook.put(fid, info);
    }

    public PeerInfo getPeerInfo(String fid) {
        return peerBook.get(fid);
    }

    public Boolean isPeerOnlineOrNull(String fid) {
        PeerInfo info = peerBook.get(fid);
        if (info != null && info.isOnline()) return Boolean.TRUE;

        // FUDP is UDP-based; ESTABLISHED state can be stale, so require a recent lastSeen.
        if (fudpNode != null) {
            com.fc.fc_ajdk.fudp.node.Peer peer = fudpNode.getPeer(fid);
            if (peer != null && peer.getState() == com.fc.fc_ajdk.fudp.connection.ConnectionState.ESTABLISHED) {
                long elapsed = System.currentTimeMillis() - peer.getLastSeen();
                if (elapsed < PeerInfo.ONLINE_THRESHOLD_MS) return Boolean.TRUE;
            }
        }

        if (info != null) return Boolean.FALSE;
        if (fudpNode != null && fudpNode.getPeer(fid) != null) return Boolean.FALSE;
        return null;
    }

    public boolean isPeerOnline(String fid) {
        Boolean result = isPeerOnlineOrNull(fid);
        return result != null && result;
    }

    /**
     * Whether the local identity may send via FUDP at all
     * (mainFid only — sub-FIDs have no prikey for the FUDP handshake).
     */
    private boolean isFudpAllowed() {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) return false;
        String mainFid = fidManager.getMainFid();
        if (mainFid == null || !mainFid.equals(liveFid)) return false;

        com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfo = fidManager.getMainKeyInfo();
        Map<String, String> home = (mainKeyInfo != null) ? mainKeyInfo.getHome() : null;
        if (home == null || home.isEmpty()) return false;
        return homeContainsFudp(home);
    }

    private boolean hasTargetFudpRegistered(String targetFid) {
        PeerInfo cachedPeer = peerBook.get(targetFid);
        if (cachedPeer != null && cachedPeer.fudpRegisteredKnown) return cachedPeer.fudpRegistered;

        TalkPartner partner = (talkPartnerProvider != null) ? talkPartnerProvider.getTalkPartner(targetFid) : null;
        if (partner != null && partner.getHome() != null) {
            boolean hasFudp = homeContainsFudp(partner.getHome());
            cacheFudpRegistered(targetFid, hasFudp);
            return hasFudp;
        }

        if (fapiClient != null) {
            try {
                com.fc.fc_ajdk.data.fchData.Freer freer = fapiClient.getFreer(targetFid);
                if (freer != null && freer.getHome() != null) {
                    boolean hasFudp = homeContainsFudp(freer.getHome());
                    cacheFudpRegistered(targetFid, hasFudp);
                    return hasFudp;
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to check target FUDP registration: %s", e.getMessage());
            }
        }
        cacheFudpRegistered(targetFid, false);
        return false;
    }

    private boolean homeContainsFudp(Map<String, String> home) {
        if (home == null) return false;
        for (Map.Entry<String, String> e : home.entrySet()) {
            String key = e.getKey();
            if (key != null && (key.equals("FUDP") || key.startsWith("FUDP@"))) {
                String val = e.getValue();
                if (val != null && !val.isEmpty()) return true;
            }
        }
        return false;
    }

    private void cacheFudpRegistered(String fid, boolean hasFudp) {
        PeerInfo info = peerBook.get(fid);
        if (info == null) {
            info = new PeerInfo();
            info.setFid(fid);
            peerBook.put(fid, info);
        }
        info.fudpRegistered = hasFudp;
        info.fudpRegisteredKnown = true;
    }

    public static class PeerInfo {
        private String fid;
        private InetSocketAddress address;
        private byte[] pubkey;
        private long lastSeen;
        private DeliveryMethod lastDeliveryMethod;

        boolean fudpRegistered;
        boolean fudpRegisteredKnown;

        private static final long VALIDITY_MS = 5 * 60 * 1000;
        private static final long ONLINE_THRESHOLD_MS = 2 * 60 * 1000;

        public boolean isValid() {
            return address != null && System.currentTimeMillis() - lastSeen < VALIDITY_MS;
        }

        public boolean isOnline() {
            return System.currentTimeMillis() - lastSeen < ONLINE_THRESHOLD_MS &&
                    (lastDeliveryMethod == DeliveryMethod.FUDP_DIRECT ||
                     lastDeliveryMethod == DeliveryMethod.ROAD_RELAY);
        }

        public String getFid() { return fid; }
        public void setFid(String fid) { this.fid = fid; }

        public InetSocketAddress getAddress() { return address; }
        public void setAddress(InetSocketAddress address) { this.address = address; }

        public byte[] getPubkey() { return pubkey; }
        public void setPubkey(byte[] pubkey) { this.pubkey = pubkey; }

        public long getLastSeen() { return lastSeen; }
        public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

        public DeliveryMethod getLastDeliveryMethod() { return lastDeliveryMethod; }
        public void setLastDeliveryMethod(DeliveryMethod method) { this.lastDeliveryMethod = method; }
    }
}
