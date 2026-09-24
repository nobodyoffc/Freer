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
        public void onFrame(byte[] datagram) {
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
        String relay = System.getenv("CALL_RELAY");
        assumeTrue("set CALL_RELAY to run against a live relay", relay != null && !relay.isEmpty());
        String callId = Hex.toHex(key()).substring(0, 32);
        Side caller = new Side(), callee = new Side();
        try {
            // Caller: connect, create, join, and wait (§6.2 step 1).
            caller.open(callId, relay);
            caller.link.create();
            Map<String, Object> cj = caller.link.join(caller.ssrc, null);
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
            Thread register = new Thread(() -> {
                try {
                    Thread.sleep(6000); // a slow ACCEPT: the callee backs off within the join limit
                    caller.link.register(CallKeys.authPub(authPriv));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            register.start();
            Map<String, Object> ej = callee.link.join(callee.ssrc, authPriv);
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

            System.out.println("[live] caller " + caller.link.describe());
            System.out.println("[live] callee " + callee.link.describe());
            // 8 s of audio both ways, run as CallSession runs it: attestations checked
            // every 200 ms, the 3 s deadline ticking. Nobody may be muted (§5.1).
            int[] heard = new int[2];
            long startMs = System.currentTimeMillis(), nextCheck = startMs;
            for (long seq = 0; System.currentTimeMillis() - startMs < 8_000; seq++) {
                long now = System.currentTimeMillis();
                caller.link.sendFrame(caller.media.seal(
                        new EncodedFrame(caller.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) seq}), now));
                callee.link.sendFrame(callee.media.seal(
                        new EncodedFrame(callee.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) -seq}), now));
                int i = 0;
                for (Side s : List.of(caller, callee)) {
                    byte[] d;
                    while ((d = s.frames.poll()) != null) if (s.media.open(d, now) != null) heard[i]++;
                    while ((d = s.attestations.poll()) != null) s.media.onAttestation(d);
                    if (now >= nextCheck) {
                        for (byte[] a : s.media.takeAttestations(now)) s.link.sendAttestation(a);
                        s.media.tick(now);
                    }
                    i++;
                }
                if (now >= nextCheck) nextCheck = now + 200;
                Thread.sleep(40);
            }
            System.out.println("[live] frames heard by caller " + heard[0] + ", by callee " + heard[1]);
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
