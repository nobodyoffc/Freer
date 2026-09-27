package com.fc.freer.call.spike;

import android.content.Context;
import android.os.SystemClock;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.fudp.connection.PeerConnection;
import com.fc.fc_ajdk.fudp.message.ResponseMessage;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.fudp.node.NodeConfig;
import com.fc.fc_ajdk.fudp.node.NodeEventListener;
import com.fc.fc_ajdk.fudp.transport.DatagramResult;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.call.engine.EncodedFrame;
import com.fc.freer.call.engine.SpikeFrame;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Phase 2 spike's network path: its own FUDP node, and DATAGRAM frames
 * to one peer directly or to everyone through the throwaway relay
 * ({@code VoiceSpikeRelay} in FC-JDK). There is no signalling and no
 * delegation yet (Phase 3): the direct peers' keys come from a shared room
 * code, and the relay's from a constant.
 */
public final class SpikeTransport implements AutoCloseable {

    private static final String TAG = "SpikeTransport";

    public static final int PHONE_PORT = 19870;
    public static final int RELAY_PORT = 19900;

    public enum Mode {
        /** Wait on {@link #PHONE_PORT} for a phone in CALL mode with the same room code. */
        HOST,
        /** Call a HOST phone at its address. */
        CALL,
        /** Join the relay; hear everyone else in it. */
        RELAY,
        /**
         * No network: this phone's frames go straight to its own playout, so a
         * recording of a click next to it measures the device's own audio
         * delay (mic path, frame, jitter buffer, speaker path).
         */
        LOOPBACK
    }

    public interface Listener {
        /** On the node's receive thread: return quickly. */
        void onFrame(EncodedFrame frame, long arrivalMs);

        void onStatus(String status);
    }

    private final Mode mode;
    private final String roomCode;
    private final String remoteHost;
    private final File dataDir;
    private final Listener listener;
    private final List<Long> connections = new CopyOnWriteArrayList<>();
    private volatile double simulatedLoss;
    private FudpNode node;
    private java.util.concurrent.ScheduledExecutorService pinger;

    private final AtomicLong sent = new AtomicLong(), sendDrops = new AtomicLong();
    private final AtomicLong received = new AtomicLong(), simulatedDrops = new AtomicLong();

    public SpikeTransport(Context context, Mode mode, String roomCode, String remoteHost, Listener listener) {
        this.mode = mode;
        this.roomCode = roomCode;
        this.remoteHost = remoteHost;
        this.dataDir = new File(context.getCacheDir(), "voice-spike");
        this.listener = listener;
    }

    /** Drop this fraction of received frames, to test loss without netem. */
    public void setSimulatedLoss(double fraction) {
        simulatedLoss = fraction;
    }

    /** Blocking: connects before returning. Call off the main thread. */
    public void start() throws Exception {
        if (mode == Mode.LOOPBACK) {
            listener.onStatus("loopback: this phone hears itself, with no network");
            return;
        }
        byte[] priv = switch (mode) {
            case HOST -> roomKey("host");
            case CALL -> roomKey("caller");
            case RELAY -> randomKey();
            case LOOPBACK -> throw new IllegalStateException();
        };
        NodeConfig config = new NodeConfig();
        config.setPort(PHONE_PORT);
        config.setDataDir(dataDir.getAbsolutePath());
        node = new FudpNode(priv, config);
        node.setEventListener(new NodeEventListener() {
            @Override
            public void onRequestReceived(String peerId, long connectionId, long requestId,
                                          String serviceName, byte[] data) {
                // HOST: the caller announces itself; datagrams flow once both ends enable them.
                if (mode == Mode.HOST && "voice-join".equals(serviceName)) {
                    node.enableDatagrams(connectionId);
                    if (!connections.contains(connectionId)) connections.add(connectionId);
                    listener.onStatus("caller connected: " + peerId);
                }
                try {
                    node.respond(peerId, connectionId, requestId, ResponseMessage.STATUS_SUCCESS, new byte[1]);
                } catch (Exception e) {
                    TimberLogger.w(TAG, "respond failed: %s", e.getMessage());
                }
            }

            @Override
            public void onDatagram(String peerId, long connectionId, byte[] data) {
                long now = SystemClock.elapsedRealtime();
                received.incrementAndGet();
                if (simulatedLoss > 0 && ThreadLocalRandom.current().nextDouble() < simulatedLoss) {
                    simulatedDrops.incrementAndGet();
                    return;
                }
                SpikeFrame f = SpikeFrame.decode(data);
                if (f != null) listener.onFrame(f.toEncoded(), now);
            }
        });
        node.start();

        switch (mode) {
            case HOST -> listener.onStatus("waiting on UDP " + PHONE_PORT + " for a caller with room code " + roomCode);
            case CALL -> join(KeyTools.prikeyToPubkey(roomKey("host")), remoteHost, PHONE_PORT, "voice-join");
            case RELAY -> join(KeyTools.prikeyToPubkey(relayKey()), remoteHost, RELAY_PORT, "join");
            case LOOPBACK -> { }
        }
    }

    /**
     * DATAGRAM frames are never acknowledged, so they never update the RTT
     * estimate; without this it stays at whatever the join measured. One
     * small request a second keeps it current (and keeps Wi-Fi power saving
     * from batching the path).
     */
    private void startPinging(String peerFid) {
        pinger = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-spike-ping");
            t.setDaemon(true);
            return t;
        });
        pinger.scheduleWithFixedDelay(() -> {
            FudpNode n = node;
            if (n == null) return;
            try {
                n.request(peerFid, "ping", new byte[1]).get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a missed ping only leaves the RTT a second older
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void join(byte[] peerPub, String host, int port, String service) throws Exception {
        String peerFid = KeyTools.pubkeyToFchAddr(peerPub);
        node.addPeer(peerFid, peerPub, host, port);
        ResponseMessage resp = node.request(peerFid, service, new byte[1]).get(10, TimeUnit.SECONDS);
        if (!resp.isSuccess()) throw new IllegalStateException(service + " refused: " + resp.getStatusCode());
        long conn = node.getProtocol().getConnectionManager().getAnyConnection(peerFid).getConnectionId();
        node.enableDatagrams(conn);
        connections.add(conn);
        startPinging(peerFid);
        listener.onStatus("connected to " + host + ":" + port);
    }

    /** From the capture thread: one frame to every connection. */
    public void send(EncodedFrame encoded) {
        byte[] frame = SpikeFrame.of(encoded).encode();
        if (mode == Mode.LOOPBACK) {
            sent.incrementAndGet();
            received.incrementAndGet();
            listener.onFrame(encoded, SystemClock.elapsedRealtime());
            return;
        }
        for (long conn : connections) {
            if (node.sendDatagram(conn, frame) == DatagramResult.SENT) sent.incrementAndGet();
            else sendDrops.incrementAndGet();
        }
    }

    /** Smoothed RTT of the first connection, ms, or -1. */
    public long rttMs() {
        if (connections.isEmpty() || node == null) return -1;
        PeerConnection c = node.getProtocol().getConnectionManager().getByConnectionId(connections.get(0));
        return c == null ? -1 : c.getRttEstimator().getSmoothedRtt();
    }

    public Mode mode() {
        return mode;
    }

    public long sent() {
        return sent.get();
    }

    public long sendDrops() {
        return sendDrops.get();
    }

    public long received() {
        return received.get();
    }

    public long simulatedDrops() {
        return simulatedDrops.get();
    }

    @Override
    public void close() {
        if (pinger != null) pinger.shutdownNow();
        if (node == null) return;
        if (mode == Mode.RELAY) {
            try {
                String relayFid = KeyTools.pubkeyToFchAddr(KeyTools.prikeyToPubkey(relayKey()));
                node.request(relayFid, "leave", new byte[1]).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the relay drops us when the connection idles out anyway
            }
        }
        node.stop();
        node = null;
    }

    private byte[] roomKey(String role) {
        return sha256("FreerVoiceSpike|" + roomCode + "|" + role);
    }

    /** Must match VoiceSpikeRelay in FC-JDK. */
    static byte[] relayKey() {
        return sha256("FreerVoiceSpike relay");
    }

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
