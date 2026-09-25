package com.fc.freer.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.call.engine.EncodedFrame;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A 1:1 call's network path against a real CALL relay (VOICE_SPEC §6.2):
 * CallRelayLink and CallMedia exactly as a phone runs them, minus audio
 * devices and IM. Needs a running relay, so it runs only when CALL_RELAY
 * names one, e.g. {@code CALL_RELAY=fudp://127.0.0.1:19950} with FC-JDK's
 * CallRelayServer running.
 */
public class CallRelayLinkLiveTest {

    private static final SecureRandom RNG = new SecureRandom();

    static byte[] key() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    /** One side: a FID, the call's transport key and delegation, its link and media. */
    static final class Side implements CallRelayLink.Events {
        final byte[] fidPriv = key(), tPriv = key();
        final byte[] tPub = KeyTools.prikeyToPubkey(tPriv);
        final String fid = KeyTools.pubkeyToFchAddr(KeyTools.prikeyToPubkey(fidPriv));
        final int ssrc = RNG.nextInt();
        final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Map<String, Object>> notices = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> attestations = new LinkedBlockingQueue<>();
        Delegation delegation;
        String knownPubkey, knownSid;
        CallRelayLink link;
        CallMedia media;

        void open(String callId, String relay) throws Exception {
            delegation = Delegation.sign(fidPriv, callId, tPub, System.currentTimeMillis() / 1000 + 3600);
            File dir = Files.createTempDirectory("call-link").toFile();
            link = new CallRelayLink(dir, tPriv, callId, delegation, this);
            link.connect(relay, knownPubkey, knownSid);
        }

        @Override
        public void onFrame(byte[] datagram, boolean direct) {
            frames.add(datagram);
        }

        @Override
        public void onAttestation(byte[] attestation) {
            attestations.add(attestation);
        }

        @Override
        public void onNotice(Map<String, Object> notice) {
            notices.add(notice);
        }
    }

    @Test
    public void aCallThroughTheRelay() throws Exception {
        run(false);
    }

    /**
     * Both share candidates through the relay's roster (§6.1), punch, and move
     * audio to the direct path (§6.2 steps 6-8). Needs a relay that knows
     * candidates, and both sides on this machine or one network.
     */
    @Test
    public void aCallGoesDirectWhenItCan() throws Exception {
        run(true);
    }

    /**
     * A caller remembers the relay's key after discovery, and the next call
     * connects with it; a stale remembered key is forgotten and discovery
     * runs instead (§6.2 step 1).
     */
    @Test
    public void aRelayOnceFoundIsReachedWithItsKey() throws Exception {
        String relay = System.getenv("CALL_RELAY");
        assumeTrue("set CALL_RELAY to run against a live relay", relay != null && !relay.isEmpty());
        java.util.Map<String, String[]> store = new java.util.HashMap<>();
        KnownRelays known = new KnownRelays() {
            @Override
            public String[] get(String url) {
                return store.get(url);
            }

            @Override
            public void put(String url, String pubkeyHex, String sid) {
                store.put(url, new String[]{pubkeyHex, sid});
            }

            @Override
            public void forget(String url) {
                store.remove(url);
            }
        };
        for (String expected : List.of("by discovery", "with its remembered key")) {
            assertEquals(expected, connectAndCreate(relay, known));
            assertNotNull(store.get(relay));
        }
        // A key the relay does not have: forgotten, then discovery.
        store.put(relay, new String[]{Hex.toHex(KeyTools.prikeyToPubkey(key())), store.get(relay)[1]});
        assertEquals("by discovery", connectAndCreate(relay, known));
    }

    private static String connectAndCreate(String relay, KnownRelays known) throws Exception {
        Side s = new Side();
        String callId = Hex.toHex(key()).substring(0, 32);
        s.delegation = Delegation.sign(s.fidPriv, callId, s.tPub, System.currentTimeMillis() / 1000 + 3600);
        s.link = new CallRelayLink(Files.createTempDirectory("call-link").toFile(), s.tPriv, callId, s.delegation, s);
        try {
            String how = s.link.connect(relay, null, null, known);
            s.link.create(); // and the connection really works
            return how;
        } finally {
            s.link.close();
        }
    }

    @SuppressWarnings("unchecked")
    static List<CallDirectPath.Candidate> candidatesOf(Map<String, Object> roster, String fid) {
        for (Map<String, Object> e : CallRelayLink.roster(roster)) {
            if (!fid.equals(e.get("fid")) || !(e.get("candidates") instanceof List<?> list)) continue;
            List<CallDirectPath.Candidate> out = new java.util.ArrayList<>();
            for (Object o : list) out.add(CallDirectPath.Candidate.parse((Map<?, ?>) o));
            return out;
        }
        return List.of();
    }

    /** Where CallSession sends: the direct path once it is up, else the relay. */
    static void send(Side s, byte[] frame) {
        CallDirectPath d = s.link.direct();
        if (d != null && d.isUp()) d.send(frame);
        else s.link.sendFrame(frame);
    }

    static void attest(Side s, byte[] a) {
        CallDirectPath d = s.link.direct();
        if (d != null && d.isUp()) d.sendAttestation(a);
        else s.link.sendAttestation(a);
    }

    private void run(boolean direct) throws Exception {
        String relay = System.getenv("CALL_RELAY");
        assumeTrue("set CALL_RELAY to run against a live relay", relay != null && !relay.isEmpty());
        String callId = Hex.toHex(key()).substring(0, 32);
        Side caller = new Side(), callee = new Side();
        try {
            // Caller: connect, create, join, and wait (§6.2 step 1).
            caller.open(callId, relay);
            caller.link.create();
            Map<String, Object> cj = caller.link.join(caller.ssrc, null, direct);
            assertEquals(Boolean.TRUE, cj.get("datagram"));

            // INVITE/ACCEPT carry the transport keys; both sides derive the secret.
            byte[] secret = CallKeys.p2pSecret(caller.tPriv, callee.tPub, callId, caller.fid, callee.fid);
            byte[] authPriv = CallKeys.authPriv(secret);

            // The INVITE carries the relay's key and sid, so the callee skips discovery (§3.2).
            callee.knownPubkey = caller.link.relayPubkey();
            callee.knownSid = caller.link.relaySid();
            assertNotNull(callee.knownPubkey);
            assertNotNull(callee.knownSid);
            // Callee joins first and retries on 409 while the caller registers (§6.2 step 4).
            callee.open(callId, relay);
            // The ACCEPT is slow over IM: the caller registers on the relay's knock (§6.2 step 3).
            Thread register = new Thread(() -> {
                try {
                    Map<String, Object> knock;
                    do {
                        knock = caller.notices.poll(10, TimeUnit.SECONDS);
                        assertNotNull("the relay knocks for the waiting callee", knock);
                    } while (!"knock".equals(knock.get("type")));
                    assertEquals(callee.fid, knock.get("fid"));
                    caller.link.register(CallKeys.authPub(authPriv));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            register.start();
            Map<String, Object> ej = callee.link.join(callee.ssrc, authPriv, direct);
            register.join();

            caller.media = new CallMedia(callId, secret, caller.fid, caller.ssrc, caller.tPriv);
            caller.media.setRouteId((int) ((Number) cj.get("routeId")).longValue());
            callee.media = new CallMedia(callId, secret, callee.fid, callee.ssrc, callee.tPriv);
            callee.media.setRouteId((int) ((Number) ej.get("routeId")).longValue());
            caller.media.addPeer(callee.fid, callee.ssrc, callee.tPub);
            callee.media.addPeer(caller.fid, caller.ssrc, caller.tPub);

            // The caller hears of the callee through a roster notice naming its delegation.
            Map<String, Object> roster = caller.notices.poll(5, TimeUnit.SECONDS);
            assertNotNull("roster notice", roster);
            assertEquals("roster", roster.get("type"));
            assertTrue(CallRelayLink.roster(roster).stream().anyMatch(e -> callee.fid.equals(e.get("fid"))));

            if (direct) {
                List<CallDirectPath.Candidate> toCallee = candidatesOf(roster, callee.fid);
                List<CallDirectPath.Candidate> toCaller = candidatesOf(ej, caller.fid);
                System.out.println("[live] callee shares " + toCallee + "; caller shares " + toCaller);
                assertTrue("the relay adds what it sees (map)", toCallee.stream().anyMatch(c -> "map".equals(c.t())));
                assertTrue(toCaller.stream().anyMatch(c -> "map".equals(c.t())));
                java.util.concurrent.CountDownLatch up = new java.util.concurrent.CountDownLatch(2);
                for (Side s : List.of(caller, callee)) {
                    Side peer = s == caller ? callee : caller;
                    s.link.startDirect(peer.tPub, s.fid.compareTo(peer.fid) < 0, s == caller ? toCallee : toCaller,
                            new CallDirectPath.Listener() {
                                @Override
                                public void onUp() {
                                    s.media.setRouteId(0);
                                    up.countDown();
                                }

                                @Override
                                public void onDown() {}

                                @Override
                                public void log(String what) {
                                    System.out.println("[live direct " + (s == caller ? "caller" : "callee") + "] " + what);
                                }
                            });
                }
                assertTrue("both sides go direct", up.await(10, TimeUnit.SECONDS));
            }

            System.out.println("[live] caller " + caller.link.describe());
            System.out.println("[live] callee " + callee.link.describe());
            // 8 s of audio both ways, run as CallSession runs it: attestations checked
            // every 200 ms, the 3 s deadline ticking. Nobody may be muted (§5.1).
            int[] heard = new int[2];
            long startMs = System.currentTimeMillis(), nextCheck = startMs;
            for (long seq = 0; System.currentTimeMillis() - startMs < 8_000; seq++) {
                long now = System.currentTimeMillis();
                send(caller, caller.media.seal(
                        new EncodedFrame(caller.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) seq}), now));
                send(callee, callee.media.seal(
                        new EncodedFrame(callee.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) -seq}), now));
                int i = 0;
                for (Side s : List.of(caller, callee)) {
                    byte[] d;
                    while ((d = s.frames.poll()) != null) if (s.media.open(d, now) != null) heard[i]++;
                    while ((d = s.attestations.poll()) != null) s.media.onAttestation(d);
                    if (now >= nextCheck) {
                        for (byte[] a : s.media.takeAttestations(now)) attest(s, a);
                        s.media.tick(now);
                    }
                    i++;
                }
                if (now >= nextCheck) nextCheck = now + 200;
                Thread.sleep(40);
            }
            System.out.println("[live] frames heard by caller " + heard[0] + ", by callee " + heard[1]
                    + (direct ? ", direct: " + caller.link.direct().isUp() + "/" + callee.link.direct().isUp() : ""));
            if (direct) assertTrue(caller.link.direct().isUp() && callee.link.direct().isUp());
            assertTrue("caller was muted: attestations late", !caller.media.isUnverified(callee.ssrc));
            assertTrue("callee was muted: attestations late", !callee.media.isUnverified(caller.ssrc));
            assertTrue("caller heard " + heard[0], heard[0] > 150);
            assertTrue("callee heard " + heard[1], heard[1] > 150);
        } finally {
            for (Side s : List.of(caller, callee)) {
                if (s.link != null) {
                    s.link.leave();
                    s.link.close();
                }
            }
        }
    }
}
