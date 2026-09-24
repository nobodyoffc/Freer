package com.fc.freer.call;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.fudp.connection.ConnectionState;
import com.fc.fc_ajdk.fudp.connection.PeerConnection;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.fudp.transport.DatagramResult;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A direct FUDP path to the other side of a 1:1 call (VOICE_SPEC §6.2 steps
 * 6-9), on the call's own node, beside the relay path that stays joined.
 * <p>
 * Punching: HELLO to every candidate every 100 ms for 3 s; a PUBLIC_KEY answer
 * that is the peer's transport key marks an address that works. The side with
 * the lower FID then opens the connection there. A connection counts only if
 * its FUDP peer id is the peer's transport key, from a delegation already
 * verified (§6.2 step 7). Then probes both ways: once each side has heard
 * the other say it hears it, the path is up. Probes keep going every 500 ms
 * as a keepalive; 2 s without anything from the peer takes the path down for
 * the rest of the call (no second punching).
 */
final class CallDirectPath {

    /** A probe datagram: {@code kind = 0x03, heard}. Never a MediaFrame (0x01) or Attestation (0x02). */
    static final int PROBE = 0x03;
    static final long PUNCH_EVERY_MS = 100, PUNCH_FOR_MS = 3_000;
    /** Punching found nothing, or the connection never came up: give up. */
    static final long GIVE_UP_MS = 8_000;
    static final long PROBE_EVERY_MS = 100, KEEPALIVE_EVERY_MS = 500;
    static final long QUIET_MS = 2_000;
    private static final String TAG = "CallDirectPath";

    interface Listener {
        /** Probes passed both ways: send on this path now. */
        void onUp();

        /** The path went quiet: back to the relay, for good. */
        void onDown();

        void log(String what);
    }

    /** A candidate endpoint: {@code t} is lan or map, as in the roster (§6.1). */
    record Candidate(String t, String host, int port) {
        static Candidate parse(Map<?, ?> m) {
            if (!(m.get("t") instanceof String t) || !(m.get("a") instanceof String a)) return null;
            int colon = a.lastIndexOf(':');
            if (colon <= 0) return null;
            String host = a.substring(0, colon);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            try {
                return new Candidate(t, host, Integer.parseInt(a.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final FudpNode node;
    private final byte[] peerTPub;
    private final String peerTFid;
    private final boolean initiator;
    private final List<Candidate> candidates;
    private final Listener listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "call-direct");
        t.setDaemon(true);
        return t;
    });

    private final long startMs = System.currentTimeMillis();
    private volatile Candidate working;
    private volatile boolean connecting;
    private volatile Long connection;
    private volatile boolean heard, heardBack, up, down;
    private volatile long lastRxMs;
    private ScheduledFuture<?> tick;
    private long ticksUp;

    /**
     * @param initiator true on the side with the lexicographically lower FID,
     *                  which opens the connection so there is only one
     */
    CallDirectPath(FudpNode node, byte[] peerTPub, boolean initiator, List<Candidate> candidates, Listener listener) {
        this.node = node;
        this.peerTPub = peerTPub.clone();
        this.peerTFid = KeyTools.pubkeyToFchAddr(peerTPub);
        this.initiator = initiator;
        this.candidates = List.copyOf(candidates);
        this.listener = listener;
    }

    /** The FUDP peer id the direct connection has: the peer's transport key's address. */
    String peerTFid() {
        return peerTFid;
    }

    boolean isUp() {
        return up && !down;
    }

    void start() {
        listener.log("punching " + candidates.size() + " candidate(s) as " + (initiator ? "initiator" : "responder"));
        tick = timer.scheduleWithFixedDelay(this::tick, 0, PROBE_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    void stop() {
        down = true;
        timer.shutdownNow();
    }

    /** A datagram from the peer on the direct connection: a probe, or media (which counts as life). */
    void onDatagram(long connectionId, byte[] data) {
        if (down) return;
        lastRxMs = System.currentTimeMillis();
        if (connection == null) adopt(connectionId);
        if (data.length == 2 && (data[0] & 0xFF) == PROBE) {
            heard = true;
            if (data[1] == 1 && !heardBack) {
                heardBack = true;
                if (!up) {
                    up = true;
                    listener.log("probes passed both ways after " + (System.currentTimeMillis() - startMs) + " ms");
                    listener.onUp();
                }
            }
        }
    }

    /** The node connected to someone: if it is the peer, this is our connection. */
    void onPeerConnected(String peerId, long connectionId) {
        if (peerTFid.equals(peerId) && connection == null && !down) adopt(connectionId);
    }

    /** @return what FUDP did; NO_CONNECTION before the path is up */
    DatagramResult send(byte[] frame) {
        Long c = connection;
        return c == null || !isUp() ? DatagramResult.NO_CONNECTION : node.sendDatagram(c, frame);
    }

    /** Smoothed RTT on the direct connection, ms, or -1. */
    long rttMs() {
        Long c = connection;
        PeerConnection pc = c == null ? null : node.getProtocol().getConnectionManager().getByConnectionId(c);
        return pc == null ? -1 : pc.getRttEstimator().getSmoothedRtt();
    }

    /** An attestation, reliably, on the direct connection (§5.1). */
    void sendAttestation(byte[] attestation) {
        try {
            node.sendNotify(peerTFid, attestation, 0);
        } catch (IOException | RuntimeException e) {
            // unverified at the far end within 3 s, as on the relay
        }
    }

    // ===== Internals =====

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (connection == null) findConnection();
            if (now - startMs < PUNCH_FOR_MS) punch();
            if (initiator && working != null && connection == null && !connecting) connect(working);
            Long c = connection;
            if (c == null) {
                if (now - startMs > GIVE_UP_MS) giveUp("no direct connection");
                return;
            }
            // Probe fast until up, then as a keepalive.
            if (!up || ++ticksUp % (KEEPALIVE_EVERY_MS / PROBE_EVERY_MS) == 0) {
                node.sendDatagram(c, new byte[]{(byte) PROBE, (byte) (heard ? 1 : 0)});
            }
            if (up && now - lastRxMs > QUIET_MS) {
                listener.log("direct path quiet for " + (now - lastRxMs) + " ms");
                down = true;
                listener.onDown();
                timer.shutdown();
            } else if (!up && now - startMs > GIVE_UP_MS) {
                giveUp("probes did not pass both ways");
            }
        } catch (RuntimeException e) {
            TimberLogger.w(TAG, "direct path tick: %s", e);
        }
    }

    private void punch() {
        for (Candidate c : candidates) {
            try {
                node.discoverPublicKey(c.host(), c.port(), PUNCH_FOR_MS).thenAccept(pub -> {
                    if (working == null && Arrays.equals(pub, peerTPub)) {
                        working = c;
                        listener.log("the peer answered at " + c.t() + " " + c.host() + ":" + c.port());
                    }
                });
            } catch (IOException | RuntimeException e) {
                // unreachable from here, e.g. an IPv6 candidate on an IPv4-only network
            }
        }
    }

    private void connect(Candidate c) {
        connecting = true;
        node.addPeer(peerTFid, peerTPub, c.host(), c.port());
        try {
            node.ping(peerTFid); // the first encrypted packet opens the connection
        } catch (IOException | RuntimeException e) {
            listener.log("could not open the direct connection: " + e.getMessage());
        }
    }

    /** The responder learns of the connection by event, or finds it here. */
    private void findConnection() {
        PeerConnection c = node.getProtocol().getConnectionManager().getAnyConnection(peerTFid);
        if (c != null && c.getState() == ConnectionState.ESTABLISHED) adopt(c.getConnectionId());
    }

    private synchronized void adopt(long connectionId) {
        if (connection != null) return;
        PeerConnection c = node.getProtocol().getConnectionManager().getByConnectionId(connectionId);
        // §6.2 step 7: the connection's key must be the one the peer's delegation names.
        if (c == null || !Arrays.equals(c.getPeerPublicKey(), peerTPub)) return;
        node.enableDatagrams(connectionId);
        connection = connectionId;
        listener.log("direct connection " + connectionId + " to " + c.getPeerAddress());
    }

    private void giveUp(String why) {
        if (down) return;
        listener.log("no direct path: " + why + "; staying on the relay");
        down = true;
        timer.shutdown();
    }

    // ===== Our own candidates =====

    /**
     * Private addresses of this device's interfaces (RFC 1918, or IPv6 ULA),
     * at {@code port}: two phones on one Wi-Fi reach each other there
     * without going through NAT (§6.1). Never public, loopback or link-local.
     */
    static List<Map<String, String>> lanCandidates(int port) {
        List<Map<String, String>> out = new ArrayList<>();
        if (port <= 0) return out;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    String host = null;
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        host = a.getHostAddress();
                    } else if (a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC) {
                        String h = a.getHostAddress();
                        int zone = h.indexOf('%');
                        host = "[" + (zone < 0 ? h : h.substring(0, zone)) + "]";
                    }
                    if (host != null && out.size() < 6) {
                        Map<String, String> c = new HashMap<>();
                        c.put("t", "lan");
                        c.put("a", host + ":" + port);
                        out.add(c);
                    }
                }
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "listing interfaces: %s", e.getMessage());
        }
        return out;
    }
}
