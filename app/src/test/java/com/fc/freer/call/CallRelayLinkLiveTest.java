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
            // Sealed frames both ways, then attestations, which the relay forwards.
            long now = System.currentTimeMillis();
            for (long seq = 0; seq < 25; seq++) {
                var result = caller.link.lastSend(caller.media.seal(
                        new EncodedFrame(caller.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) seq}), now));
                assertEquals("frame " + seq + " via " + caller.link.describe(),
                        com.fc.fc_ajdk.fudp.transport.DatagramResult.SENT, result);
                var back = callee.link.lastSend(callee.media.seal(
                        new EncodedFrame(callee.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) -seq}), now));
                assertEquals("callee frame " + seq + " via " + callee.link.describe(),
                        com.fc.fc_ajdk.fudp.transport.DatagramResult.SENT, back);
                Thread.sleep(20);
            }
            // Datagrams are send-or-drop over a real path: most, not all, must arrive.
            int heard = 0, heardBack = 0;
            byte[] d;
            while ((d = callee.frames.poll(3, TimeUnit.SECONDS)) != null) {
                assertNotNull("opens", callee.media.open(d, System.currentTimeMillis()));
                heard++;
            }
            while (caller.frames.poll(1, TimeUnit.SECONDS) != null) heardBack++;
            System.out.println("[live] frames heard " + heard + "/25, back " + heardBack + "/25");
            assertTrue("callee heard " + heard + "/25", heard >= 20);
            assertTrue("caller heard " + heardBack + "/25", heardBack >= 20);
            for (byte[] a : caller.media.finish(System.currentTimeMillis())) caller.link.sendAttestation(a);
            byte[] att = callee.attestations.poll(5, TimeUnit.SECONDS);
            assertNotNull("the attestation came through the relay", att);
            callee.media.onAttestation(att);
            callee.media.tick(System.currentTimeMillis() + 10_000);
            assertTrue("vouched for: still heard", !callee.media.isUnverified(caller.ssrc));
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
