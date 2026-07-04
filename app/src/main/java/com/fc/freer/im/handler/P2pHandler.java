package com.fc.freer.im.handler;

import static com.fc.fc_ajdk.constants.Constants.ROAD_NO1_NRC7;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.DeliveryMethod;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fcData.TalkPartner;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * Encryption is provided by the underlying transport (FUDP on every hop).
 */
public class P2pHandler extends BaseHandler {

    private static final int PENDING_RECEIPTS_MAX = 256;

    private final Map<String, PeerInfo> peerBook = new ConcurrentHashMap<>();

    // Bounded LRU; entries are evicted when capacity is exceeded.
    private final Map<String, ImMessage> pendingReceipts = Collections.synchronizedMap(
            new LinkedHashMap<String, ImMessage>(PENDING_RECEIPTS_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImMessage> eldest) {
                    return size() > PENDING_RECEIPTS_MAX;
                }
            });

    private TalkPartnerProvider talkPartnerProvider;

    public interface TalkPartnerProvider {
        TalkPartner getTalkPartner(String fid);
    }

    public P2pHandler(Context context, String liveFid) {
        super(context, liveFid);
    }

    public void setTalkPartnerProvider(TalkPartnerProvider provider) {
        this.talkPartnerProvider = provider;
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

        byte[] envelope = message.toWireBytes();

        // 1. Optional FUDP_DIRECT.
        if (useFudp && isFudpAllowed() && hasTargetFudpRegistered(targetFid) && fudpNode != null) {
            try {
                long fudpMsgId = fudpNode.sendNotifyWithAck(targetFid, envelope);
                TimberLogger.d(TAG, "Sent via FUDP_DIRECT: fudpMsgId=%d, id=%s",
                        fudpMsgId, message.getId());
                message.setDeliveryMethod(DeliveryMethod.FUDP_DIRECT);
                message.setStatus(MessageStatus.SENT);
                trackForReceipt(message);
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

        // 2. Optional ROAD_RELAY.
        if (useRoad && targetRoadUrl != null) {
            FapiClient roadClient = ApiCenter.getInstance().getClient(ApiCenter.ConnectionRole.ROAD);
            if (roadClient != null) {
                try {
                    FapiClient.RoadRelayResult result = roadClient.roadRelay(targetFid, envelope, targetRoadUrl);
                    if (result != null && result.success()) {
                        message.setDeliveryMethod(DeliveryMethod.ROAD_RELAY);
                        message.setStatus(MessageStatus.SENT);
                        trackForReceipt(message);
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
                if (Boolean.TRUE.equals(direct)) return SendResult.SUCCESS;
                if (direct == null) {
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
                if (Boolean.TRUE.equals(forwarded)) return SendResult.SUCCESS;
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
                trackForReceipt(message);
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

        notifyStatusChange(imMessageId, MessageStatus.DELIVERED);
    }

    @Override
    public void handleIncoming(ImMessage message) {
        if (message == null) return;

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
        send(receipt);
    }

    private void handleReceipt(ImMessage receiptMsg) {
        String originalId = receiptMsg.getRequestId();
        if (originalId == null) return;
        boolean isRead = "read".equals(receiptMsg.getContent());

        ImMessage original;
        if (isRead) {
            original = pendingReceipts.remove(originalId);
        } else {
            original = pendingReceipts.get(originalId);
        }
        if (original == null) return;

        if (isRead) {
            original.setStatus(MessageStatus.READ);
            original.setReadAt(receiptMsg.getTimestamp());
        } else {
            original.setStatus(MessageStatus.DELIVERED);
            original.setDeliveredAt(receiptMsg.getTimestamp());
        }
        notifyStatusChange(originalId, original.getStatus());
    }

    private void sendDeliveryReceipt(ImMessage message) {
        ImMessage receipt = ImMessage.createReceipt(
                liveFid, message.getSenderId(), message.getId(), false);
        new Thread(() -> send(receipt)).start();
    }

    private void trackForReceipt(ImMessage message) {
        if (message.getContentType() != ContentType.RECEIPT) {
            pendingReceipts.put(message.getId(), message);
        }
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
