package com.fc.freer.call;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.fudp.node.NodeConfig;
import com.fc.fc_ajdk.fudp.node.NodeEventListener;
import com.fc.fc_ajdk.fudp.transport.DatagramResult;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The direct path of a 1:1 call (VOICE_SPEC §6.2 steps 6-9) between two real
 * FUDP nodes on this machine: punching, one connection, probes both ways,
 * media, and falling back when the peer goes quiet.
 */
public class CallDirectPathTest {

    private static final SecureRandom RNG = new SecureRandom();

    /** One call node, routing events as CallRelayLink does. */
    static final class Side implements CallDirectPath.Listener {
        final byte[] priv = new byte[32];
        final byte[] pub;
        final FudpNode node;
        final CountDownLatch up = new CountDownLatch(1), down = new CountDownLatch(1);
        final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final List<String> log = new ArrayList<>();
        volatile CallDirectPath path;

        Side() throws Exception {
            RNG.nextBytes(priv);
            pub = KeyTools.prikeyToPubkey(priv);
            NodeConfig config = new NodeConfig();
            config.setPort(0);
            config.setDataDir(Files.createTempDirectory("call-direct").toString());
            node = new FudpNode(priv, config);
            node.setEventListener(new NodeEventListener() {
                @Override
                public void onDatagram(String peerId, long connectionId, byte[] data) {
                    CallDirectPath p = path;
                    if (p == null || !peerId.equals(p.peerTFid())) return;
                    p.onDatagram(connectionId, data);
                    if (data.length > 2) frames.add(data);
                }

                @Override
                public void onPeerConnected(String peerId, long connectionId) {
                    CallDirectPath p = path;
                    if (p != null) p.onPeerConnected(peerId, connectionId);
                }
            });
            node.start();
        }

        CallDirectPath.Candidate here() {
            return new CallDirectPath.Candidate("lan", "127.0.0.1", node.getLocalPort());
        }

        void punch(Side peer, boolean initiator, List<CallDirectPath.Candidate> candidates) {
            path = new CallDirectPath(node, peer.pub, initiator, candidates, this);
            path.start();
        }

        @Override
        public void onUp() {
            up.countDown();
        }

        @Override
        public void onDown() {
            down.countDown();
        }

        @Override
        public synchronized void log(String what) {
            log.add(what);
            System.out.println("[direct " + KeyTools.pubkeyToFchAddr(pub).substring(0, 6) + "] " + what);
        }

        void close() {
            if (path != null) path.stop();
            node.stop();
        }
    }

    private final List<Side> sides = new ArrayList<>();

    Side side() throws Exception {
        Side s = new Side();
        sides.add(s);
        return s;
    }

    @After
    public void closeAll() {
        for (Side s : sides) s.close();
    }

    @Test
    public void punchingFindsThePeerAndProbesPassBothWays() throws Exception {
        Side a = side(), b = side();
        // A bad candidate first: punching must try them all.
        a.punch(b, true, List.of(new CallDirectPath.Candidate("lan", "127.0.0.1", 9), b.here()));
        b.punch(a, false, List.of(a.here()));
        assertTrue("A up: " + a.log, a.up.await(6, TimeUnit.SECONDS));
        assertTrue("B up: " + b.log, b.up.await(6, TimeUnit.SECONDS));
        assertTrue(a.path.isUp() && b.path.isUp());

        byte[] frame = new byte[60];
        RNG.nextBytes(frame);
        frame[0] = 0x01;
        assertEquals(DatagramResult.SENT, a.path.send(frame));
        byte[] got = b.frames.poll(3, TimeUnit.SECONDS);
        assertNotNull("media crosses the direct path", got);
        assertArrayEquals(frame, got);
        assertEquals("exactly one connection each way", 1,
                a.node.getProtocol().getConnectionManager().getConnectionsByPeerId(KeyTools.pubkeyToFchAddr(b.pub))
                        .size());
    }

    @Test
    public void aPeerThatGoesQuietTakesThePathDown() throws Exception {
        Side a = side(), b = side();
        a.punch(b, true, List.of(b.here()));
        b.punch(a, false, List.of(a.here()));
        assertTrue(a.up.await(6, TimeUnit.SECONDS));
        b.close(); // the other phone's network went away
        assertTrue("down within the 2 s quiet window: " + a.log, a.down.await(5, TimeUnit.SECONDS));
        assertFalse(a.path.isUp());
        assertEquals(DatagramResult.NO_CONNECTION, a.path.send(new byte[60]));
    }

    @Test
    public void nothingReachableGivesUpQuietly() throws Exception {
        Side a = side(), b = side();
        a.punch(b, true, List.of(new CallDirectPath.Candidate("lan", "127.0.0.1", 9)));
        assertFalse("never up", a.up.await(CallDirectPath.GIVE_UP_MS + 2_000, TimeUnit.MILLISECONDS));
        assertEquals("no onDown for a path that never came up", 1, a.down.getCount());
        assertTrue(a.log.stream().anyMatch(l -> l.startsWith("no direct path")));
    }
}
