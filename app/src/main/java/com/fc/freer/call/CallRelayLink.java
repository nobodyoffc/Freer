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
 * One call's or meeting's path to the CALL relay (VOICE_SPEC §6.2, §7.2): its own FUDP
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
        void onFrame(byte[] datagram, boolean direct);

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

        /** 408: FAPI heard nothing back in time. The request may well have taken. */
        public boolean lostReply() {
            return code == 408;
        }
    }

    private static final Gson GSON = new Gson();

    private final String callId;
    private final Delegation delegation;
    private final FudpNode node;
    private FapiClient fapi;
    /** The relay connection datagrams use; null until join enables them. IDs are random, negative too. */
    private volatile Long relayConnection;
    /** The direct path to the peer, once there are candidates to try (§6.2 step 6). */
    private volatile CallDirectPath direct;

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
                if (peerId.equals(relayFid())) {
                    events.onFrame(data, false);
                    return;
                }
                CallDirectPath d = direct;
                if (d == null || !peerId.equals(d.peerTFid())) return; // nobody else may send us audio
                d.onDatagram(connectionId, data);
                if (data.length > 2) events.onFrame(data, true);
            }

            @Override
            public void onPeerConnected(String peerId, long connectionId) {
                CallDirectPath d = direct;
                if (d != null) d.onPeerConnected(peerId, connectionId);
            }

            @Override
            public void onNotifyReceived(String peerId, long messageId, int dataType, byte[] data) {
                CallDirectPath d = direct;
                if (d != null && peerId.equals(d.peerTFid())) {
                    if (dataType == 0) events.onAttestation(data);
                    return;
                }
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
    static final int CREATE_ATTEMPTS = 3;
    /**
     * A call waits on each relay request: better to learn in 10 s that a reply
     * was lost, and retry or read the roster, than in FAPI's usual 30.
     */
    static final long REQUEST_TIMEOUT_S = 10;
    /** How long a remembered relay key gets to prove itself before discovery. */
    static final long VERIFY_MS = 8_000;

    /**
     * Start the node and find the relay: with its key and service id when the
     * INVITE carried them, which needs only FUDP's own retransmitted handshake;
     * otherwise by HELLO/PING discovery, retried, since a lossy path easily
     * loses one of its unacknowledged packets.
     */
    public void connect(String relayUrl, String relayPubkeyHex, String relaySid) throws IOException {
        connect(relayUrl, relayPubkeyHex, relaySid, null);
    }

    /**
     * @param known relays reached before: a caller, which has no INVITE to
     *              name the key, tries the remembered one first, and
     *              remembers what discovery finds
     * @return how it connected, for the log
     */
    public String connect(String relayUrl, String relayPubkeyHex, String relaySid, KnownRelays known)
            throws IOException {
        node.start();
        if (relayPubkeyHex != null && relaySid != null && withKey(relayUrl, relayPubkeyHex, relaySid)) {
            return "with the key from the INVITE";
        }
        String[] remembered = known == null ? null : known.get(relayUrl);
        if (remembered != null && withKey(relayUrl, remembered[0], remembered[1])) {
            if (answersPing()) return "with its remembered key";
            // Stale: the relay has a new key, or is gone. Forget it and discover.
            node.removePeer(relayFid());
            fapi = null;
            known.forget(relayUrl);
        }
        FapiClient found = null;
        for (int attempt = 1; attempt <= DISCOVERY_ATTEMPTS && found == null; attempt++) {
            found = FapiClient.bootstrapFromUrl(node, relayUrl, null);
        }
        if (found == null) throw new IOException("relay unreachable: " + relayUrl);
        fapi = new FapiClient(node, found.getServicePeerId(), found.getServiceSid(), REQUEST_TIMEOUT_S);
        fapi.setServerUrl(relayUrl);
        if (known != null) known.put(relayUrl, relayPubkey(), relaySid());
        return "by discovery";
    }

    /** Set up the relay as a known peer: no packet is sent until the first request. */
    private boolean withKey(String relayUrl, String pubkeyHex, String sid) {
        FapiClient.Endpoint ep = FapiClient.parseFudpUrl(relayUrl);
        byte[] pub;
        try {
            pub = Hex.fromHex(pubkeyHex);
        } catch (RuntimeException e) {
            return false;
        }
        if (ep == null || pub == null || pub.length != 33) return false;
        String relayFid = com.fc.fc_ajdk.core.crypto.KeyTools.pubkeyToFchAddr(pub);
        node.addPeer(relayFid, pub, ep.host(), ep.port());
        fapi = new FapiClient(node, relayFid, sid, REQUEST_TIMEOUT_S);
        fapi.setServerUrl(relayUrl);
        return true;
    }

    /** A PING over FUDP's own handshake, which resends lost packets, unlike discovery's. */
    private boolean answersPing() {
        try {
            node.pingAwaitPong(relayFid(), false, VERIFY_MS).get(VERIFY_MS + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether anything at all has come back from the relay. False after a
     * request timed out means this network lets packets out to the relay but
     * drops its replies, as some mobile networks in mainland China do for
     * UDP from abroad: no retry here can help, only another network.
     */
    boolean heardFromRelay() {
        String fid = relayFid();
        if (fid.isEmpty()) return false;
        for (PeerConnection c : node.getProtocol().getConnectionManager().getConnectionsByPeerId(fid)) {
            if (c.getPacketsReceived() > 0) return true;
        }
        return false;
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
     * {@code call.create} for a meeting, by its host (§8): the admission key
     * from the start, since every join proves the key. Retried when its reply
     * is lost; "meeting exists" on a retry means the first one took.
     */
    public void createMeeting(byte[] authPub) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Map<String, Object> p = base();
            p.put("kind", "meeting");
            p.put("authPub", Hex.toHex(authPub));
            try {
                request("call.create", p);
                return;
            } catch (Refused e) {
                if (attempt > 0 && e.code == 409 && String.valueOf(e.getMessage()).contains("meeting exists")) return;
                if (!e.lostReply() || attempt >= CREATE_ATTEMPTS - 1) throw e;
            }
        }
    }

    /**
     * {@code call.control} (§7.2): the host's mute, lockMute, unmute, kick,
     * pin, unpin, handoverHost and end; anyone's unmute of itself.
     *
     * @param target a FID (all its devices) or an ssrc; null for {@code end}
     */
    public void control(String action, Object target) throws IOException {
        Map<String, Object> p = base();
        p.put("action", action);
        if (target instanceof Integer ssrc) p.put("target", Integer.toUnsignedLong(ssrc));
        else if (target != null) p.put("target", target);
        request("call.control", p);
    }

    /** {@code call.hand}: raise or lower this participant's hand. */
    public void hand(boolean raised) throws IOException {
        Map<String, Object> p = base();
        p.put("raised", raised);
        request("call.hand", p);
    }

    /** {@code call.rekey}, by the host (§4.5). @return the new key epoch */
    public int rekey(long symkeyVersion, byte[] nonce, byte[] authPub) throws IOException {
        Map<String, Object> p = base();
        p.put("symkeyVersion", symkeyVersion);
        p.put("nonce", Hex.toHex(nonce));
        p.put("authPub", Hex.toHex(authPub));
        Object epoch = request("call.rekey", p).get("keyEpoch");
        if (!(epoch instanceof Number n)) throw new IOException("call.rekey: no keyEpoch in the reply");
        return n.intValue();
    }

    /** {@code call.prove} (§4.5 step 4): this participant holds the new key. */
    public void prove(int keyEpoch, byte[] authPriv, int ssrc) throws IOException {
        Map<String, Object> p = base();
        long ts = System.currentTimeMillis();
        p.put("keyEpoch", keyEpoch);
        p.put("ts", ts);
        p.put("admitSig", Hex.toHex(CallKeys.admitSig(authPriv, callId, delegation.tPubBytes(), ssrc, ts)));
        request("call.prove", p);
    }

    /**
     * {@code call.join}. With {@code authPriv}, a join with {@code admitSig},
     * retried on 409 while the caller has not registered yet (§6.2 step 4);
     * without, the host's own join before registration.
     *
     * @return the join result: routeId, datagram, roster, keyEpoch, speakers
     */
    public Map<String, Object> join(int ssrc, byte[] authPriv) throws IOException, InterruptedException {
        return join(ssrc, authPriv, false);
    }

    /**
     * @param share give the relay our direct-path candidates to pass on (§6.1):
     *              our private addresses, and the address it sees us at. It
     *              shows our IP to the peer, so only for contacts and never
     *              with Always relay on (Decision 8).
     */
    public Map<String, Object> join(int ssrc, byte[] authPriv, boolean share) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            Map<String, Object> p = base();
            if (share) {
                p.put("reflexive", true);
                List<Map<String, String>> lan = CallDirectPath.lanCandidates(node.getLocalPort());
                if (!lan.isEmpty()) p.put("candidates", lan);
            }
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
                // An earlier join took and only its reply was lost: leave, so the next one is the one we hear.
                boolean tookEarlier = e.code == 409 && String.valueOf(e.getMessage()).contains("already in a call");
                if (tookEarlier) leave();
                boolean retry = e.lostReply() || tookEarlier || e.code == 409 && authPriv != null;
                if (!retry || attempt >= JOIN_RETRY_MS.length) throw e;
                // Rejoin at once after leaving: the peer counts an absence of 6 s as a hang-up.
                if (!tookEarlier) Thread.sleep(JOIN_RETRY_MS[attempt]);
            }
        }
    }

    /**
     * {@code call.create}, retried when its reply is lost. The meeting id is
     * this call's own, so "meeting exists" on a retry means the first one took.
     */
    public void createRetrying() throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                create();
                return;
            } catch (Refused e) {
                if (attempt > 0 && e.code == 409 && String.valueOf(e.getMessage()).contains("meeting exists")) return;
                if (!e.lostReply() || attempt >= CREATE_ATTEMPTS - 1) throw e;
                com.fc.fc_ajdk.utils.TimberLogger.i("CallRelayLink", "create attempt %d got no reply; retrying", attempt + 1);
            }
        }
    }

    /** {@code call.info}: public, needs no delegation. @return open, participants */
    public Map<String, Object> info() throws IOException {
        Map<String, Object> p = new HashMap<>();
        p.put("meetingId", callId);
        return request("call.info", p);
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

    /** Try a direct path to the peer beside the relay; at most once per call. */
    void startDirect(byte[] peerTPub, boolean initiator, List<CallDirectPath.Candidate> candidates,
                     CallDirectPath.Listener listener) {
        if (direct != null) return;
        CallDirectPath d = new CallDirectPath(node, peerTPub, initiator, candidates, listener);
        direct = d;
        d.start();
    }

    CallDirectPath direct() {
        return direct;
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
        CallDirectPath d = direct;
        if (d != null) d.stop();
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
