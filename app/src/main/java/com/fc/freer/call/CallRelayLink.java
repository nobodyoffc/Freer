package com.fc.freer.call;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiRequest;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.fudp.connection.PeerConnection;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.fudp.node.NodeConfig;
import com.fc.fc_ajdk.fudp.node.NodeEventListener;
import com.fc.fc_ajdk.fudp.transport.DatagramResult;
import com.fc.fc_ajdk.utils.Hex;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One call's path to the CALL relay (VOICE_SPEC §6.2, §7.2): its own FUDP
 * node, running under the call's throwaway transport key so the FID's key
 * never touches it (§4.1), and a FAPI client to the relay on that node.
 * Media frames go as datagrams, attestations as NOTIFY dataType 0; the
 * relay's notices come back as NOTIFY dataType 1.
 * <p>
 * Blocking; call from the session's own thread, never the main thread.
 */
public final class CallRelayLink implements AutoCloseable {

    /**
     * Waits between retries of a 409 join (§6.2 step 4). Backing off keeps the
     * ten attempts inside the relay's 10 joins per key per minute (§7.6) while
     * spanning 16 s, time for the ACCEPT to reach the caller over slow IM.
     */
    static final long[] JOIN_RETRY_MS = {250, 500, 1000, 2000, 2500, 2500, 2500, 2500, 2500};

    public interface Events {
        /** On the node's receive thread: return quickly. */
        void onFrame(byte[] datagram);

        void onAttestation(byte[] attestation);

        /** A relay notice: roster, balance, kicked, ended. */
        void onNotice(Map<String, Object> notice);
    }

    /** The relay refused a request. */
    public static final class Refused extends IOException {
        public final int code;

        Refused(int code, String message) {
            super(code + " " + message);
            this.code = code;
        }
    }

    private static final Gson GSON = new Gson();

    private final String callId;
    private final Delegation delegation;
    private final FudpNode node;
    private FapiClient fapi;
    /** The relay connection datagrams use; null until join enables them. IDs are random, negative too. */
    private volatile Long relayConnection;

    public CallRelayLink(File dataDir, byte[] tPriv, String callId, Delegation delegation, Events events)
            throws IOException {
        this.callId = callId;
        this.delegation = delegation;
        NodeConfig config = new NodeConfig();
        config.setPort(0); // any free port: the relay answers where we came from
        config.setDataDir(dataDir.getAbsolutePath());
        node = new FudpNode(tPriv, config);
        node.setEventListener(new NodeEventListener() {
            @Override
            public void onDatagram(String peerId, long connectionId, byte[] data) {
                if (peerId.equals(relayFid())) events.onFrame(data);
            }

            @Override
            public void onNotifyReceived(String peerId, long messageId, int dataType, byte[] data) {
                if (!peerId.equals(relayFid())) return;
                if (dataType == 0) {
                    events.onAttestation(data);
                } else if (dataType == 1) {
                    try {
                        events.onNotice(GSON.fromJson(new String(data, StandardCharsets.UTF_8),
                                new TypeToken<Map<String, Object>>() {}.getType()));
                    } catch (RuntimeException ignored) {
                        // not a notice we can read
                    }
                }
            }
        });
    }

    /** Discovery attempts before giving up: its HELLO/PING are not retransmitted. */
    static final int DISCOVERY_ATTEMPTS = 3;

    /**
     * Start the node and find the relay: with its key and service id when the
     * INVITE carried them, which needs only FUDP's own retransmitted handshake;
     * otherwise by HELLO/PING discovery, retried, since a lossy path easily
     * loses one of its unacknowledged packets.
     */
    public void connect(String relayUrl, String relayPubkeyHex, String relaySid) throws IOException {
        node.start();
        if (relayPubkeyHex != null && relaySid != null) {
            FapiClient.Endpoint ep = FapiClient.parseFudpUrl(relayUrl);
            byte[] pub = Hex.fromHex(relayPubkeyHex);
            if (ep != null && pub != null && pub.length == 33) {
                String relayFid = com.fc.fc_ajdk.core.crypto.KeyTools.pubkeyToFchAddr(pub);
                node.addPeer(relayFid, pub, ep.host(), ep.port());
                fapi = new FapiClient(node, relayFid, relaySid);
                fapi.setServerUrl(relayUrl);
                return;
            }
        }
        for (int attempt = 1; attempt <= DISCOVERY_ATTEMPTS && fapi == null; attempt++) {
            fapi = FapiClient.bootstrapFromUrl(node, relayUrl, null);
        }
        if (fapi == null) throw new IOException("relay unreachable: " + relayUrl);
    }

    /** The relay's public key, hex, once connected: for the INVITE (§3.2). */
    public String relayPubkey() {
        PeerConnection c = node.getProtocol().getConnectionManager().getAnyConnection(relayFid());
        byte[] pub = c == null ? null : c.getPeerPublicKey();
        return pub == null ? null : Hex.toHex(pub);
    }

    /** The relay's service id, once connected. */
    public String relaySid() {
        FapiClient f = fapi;
        return f == null ? null : f.getServiceSid();
    }

    /** {@code call.create}, by the caller (§6.2 step 1). */
    public void create() throws IOException {
        Map<String, Object> p = base();
        p.put("kind", "p2p");
        request("call.create", p);
    }

    /**
     * {@code call.join}. With {@code authPriv}, a join with {@code admitSig},
     * retried on 409 while the caller has not registered yet (§6.2 step 4);
     * without, the host's own join before registration.
     *
     * @return the join result: routeId, datagram, roster, keyEpoch, speakers
     */
    public Map<String, Object> join(int ssrc, byte[] authPriv) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            Map<String, Object> p = base();
            long ts = System.currentTimeMillis();
            p.put("ssrc", Integer.toUnsignedLong(ssrc));
            p.put("ts", ts);
            if (authPriv != null) {
                p.put("admitSig", Hex.toHex(CallKeys.admitSig(authPriv, callId, delegation.tPubBytes(), ssrc, ts)));
            }
            try {
                Map<String, Object> r = request("call.join", p);
                if (Boolean.TRUE.equals(r.get("datagram"))) enableDatagrams(); // the §2.3 capability signal
                return r;
            } catch (Refused e) {
                com.fc.fc_ajdk.utils.TimberLogger.i("CallRelayLink", "join attempt %d refused: %s", attempt + 1, e.getMessage());
                if (e.code != 409 || authPriv == null || attempt >= JOIN_RETRY_MS.length) throw e;
                Thread.sleep(JOIN_RETRY_MS[attempt]);
            }
        }
    }

    /** {@code call.register}: the caller gives the relay {@code authPub} once the callee accepts (§4.4). */
    public void register(byte[] authPub) throws IOException {
        Map<String, Object> p = base();
        p.put("authPub", Hex.toHex(authPub));
        request("call.register", p);
    }

    /** Best effort: never throws, and does nothing if the relay was never reached. */
    public void leave() {
        if (fapi == null) return;
        try {
            request("call.leave", base());
        } catch (IOException | RuntimeException e) {
            // the relay drops us with the connection anyway
        }
    }

    /** A sealed media frame, as a datagram. @return false if FUDP dropped it */
    public boolean sendFrame(byte[] frame) {
        return lastSend(frame) == DatagramResult.SENT;
    }

    /** What FUDP did with a frame: SENT, or why not (FUDP7 §4.3). */
    DatagramResult lastSend(byte[] frame) {
        Long conn = relayConnection;
        return conn == null ? DatagramResult.NO_CONNECTION : node.sendDatagram(conn, frame);
    }

    /** For logs and tests: where frames go. */
    String describe() {
        StringBuilder sb = new StringBuilder("relay " + relayFid() + ", using " + relayConnection + ", have");
        for (PeerConnection c : node.getProtocol().getConnectionManager().getConnectionsByPeerId(relayFid())) {
            sb.append(' ').append(c.getConnectionId()).append('/').append(c.getState());
        }
        return sb.toString();
    }

    /** An attestation, reliably (§5.1). */
    public void sendAttestation(byte[] attestation) {
        if (fapi == null) return;
        try {
            node.sendNotify(relayFid(), attestation, 0);
        } catch (IOException | RuntimeException e) {
            // a lost attestation makes our audio unverified at the far end within 3 s
        }
    }

    /** Smoothed RTT to the relay, ms, or -1. */
    public long rttMs() {
        Long conn = relayConnection;
        PeerConnection c = conn == null ? null : node.getProtocol().getConnectionManager().getByConnectionId(conn);
        return c == null ? -1 : c.getRttEstimator().getSmoothedRtt();
    }

    @Override
    public void close() {
        node.stop();
    }

    private String relayFid() {
        FapiClient f = fapi;
        return f == null ? "" : f.getServicePeerId();
    }

    private void enableDatagrams() {
        PeerConnection c = node.getProtocol().getConnectionManager().getAnyConnection(relayFid());
        if (c != null) {
            relayConnection = c.getConnectionId();
            node.enableDatagrams(relayConnection);
        }
    }

    private Map<String, Object> base() {
        Map<String, Object> p = new HashMap<>();
        p.put("meetingId", callId);
        p.put("delegation", GSON.fromJson(delegation.toJson(), new TypeToken<Map<String, Object>>() {}.getType()));
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String api, Map<String, Object> params) throws IOException {
        FapiClient f = fapi;
        if (f == null) throw new IOException(api + ": not connected to the relay");
        FapiResponse r = f.request(FapiRequest.operation(api, params));
        if (r == null) throw new IOException(api + ": no answer from the relay");
        if (!r.isSuccess()) throw new Refused(r.getCode() == null ? 0 : r.getCode(), r.getMessage());
        Object data = r.getData();
        return data instanceof Map ? (Map<String, Object>) data : new HashMap<>();
    }

    /** The roster entries of a join result or roster notice. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> roster(Map<String, Object> m) {
        Object r = m.get("roster");
        return r instanceof List ? (List<Map<String, Object>>) r : List.of();
    }
}
