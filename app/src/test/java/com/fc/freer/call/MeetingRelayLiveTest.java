package com.fc.freer.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.call.MeetingSignal;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.call.engine.EncodedFrame;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A meeting's network path against a real CALL relay (VOICE_SPEC §7, §8):
 * CallRelayLink, CallMedia and MeetingKeys as MeetingSession runs them,
 * minus audio devices and IM. Runs only when CALL_RELAY names a relay, e.g.
 * {@code CALL_RELAY=fudp://127.0.0.1:19950} with FC-JDK's CallRelayServer.
 */
public class MeetingRelayLiveTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TEAM = "FTeamForTheLiveTest";

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** One member's device in the meeting. */
    static final class Member implements CallRelayLink.Events {
        final byte[] fidPriv = random(32), tPriv = random(32);
        final byte[] tPub = KeyTools.prikeyToPubkey(tPriv);
        final String fid = KeyTools.pubkeyToFchAddr(KeyTools.prikeyToPubkey(fidPriv));
        final int ssrc = RNG.nextInt();
        final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Map<String, Object>> notices = new LinkedBlockingQueue<>();
        CallRelayLink link;
        CallMedia media;
        long seq;

        void open(String meetingId, String relay) throws Exception {
            Delegation d = Delegation.sign(fidPriv, meetingId, tPub, System.currentTimeMillis() / 1000 + 3600);
            File dir = Files.createTempDirectory("meeting-link").toFile();
            link = new CallRelayLink(dir, tPriv, meetingId, d, this);
            link.connect(relay, null, null);
        }

        byte[] send() {
            long s = seq++;
            byte[] frame = media.seal(new EncodedFrame(ssrc, s, s * 1920, 30, true, false, new byte[]{(byte) s, 7}),
                    System.currentTimeMillis());
            link.sendFrame(frame);
            return frame;
        }

        /** The next notice of {@code type}, skipping others, or null after {@code ms}. */
        Map<String, Object> notice(String type, long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            for (long left = ms; left > 0; left = until - System.currentTimeMillis()) {
                Map<String, Object> n = notices.poll(left, TimeUnit.MILLISECONDS);
                if (n != null && type.equals(n.get("type"))) return n;
            }
            return null;
        }

        @Override
        public void onFrame(byte[] datagram, boolean direct) {
            frames.add(datagram);
        }

        @Override
        public void onAttestation(byte[] attestation) {
            if (media != null) media.onAttestation(attestation);
        }

        @Override
        public void onNotice(Map<String, Object> notice) {
            notices.add(notice);
        }
    }

    final List<Member> members = new ArrayList<>();

    @After
    public void close() {
        for (Member m : members) if (m.link != null) m.link.close();
    }

    /** Hear everyone in a roster whose delegation verifies, as MeetingSession does (§5.1 step 1). */
    @SuppressWarnings("unchecked")
    static void hear(Member me, Map<String, Object> roster, String meetingId) {
        for (Map<String, Object> e : CallRelayLink.roster(roster)) {
            Delegation d = Delegation.fromJson((String) e.get("delegation"));
            int ssrc = (int) ((Number) e.get("ssrc")).longValue();
            if (ssrc != me.ssrc && d.verify(meetingId, System.currentTimeMillis() / 1000) == Delegation.Check.OK) {
                me.media.addPeer(d.fid, ssrc, d.tPubBytes());
            }
        }
    }

    @Test
    public void aMeetingThroughTheRelay() throws Exception {
        String relay = System.getenv("CALL_RELAY");
        assumeTrue("set CALL_RELAY=fudp://host:port to run", relay != null);
        String meetingId = MeetingSignal.ID_PREFIX + Hex.toHex(random(12));
        byte[] symkey = random(32), nonce = random(32);
        byte[] secret = CallKeys.meetingSecret(symkey, nonce, TEAM, 1, meetingId);
        String authPub = Hex.toHex(CallKeys.authPub(CallKeys.authPriv(secret)));
        // Each member derives the secret from its own copy of the symkey, as a phone does.
        byte[] derived = MeetingKeys.secret(List.of(random(32), symkey), nonce, TEAM, 1, meetingId, authPub);
        assertNotNull(derived);

        Member host = new Member(), bob = new Member(), carol = new Member();
        members.addAll(List.of(host, bob, carol));
        Map<String, Object> last = null;
        for (Member m : members) {
            m.open(meetingId, relay);
            if (m == host) m.link.createMeeting(Hex.fromHex(authPub));
            last = m.link.join(m.ssrc, CallKeys.authPriv(derived), false);
            assertEquals("the join names the host", host.fid, last.get("host"));
            m.media = new CallMedia(meetingId, derived, m.fid, m.ssrc, m.tPriv);
            m.media.setRouteId((int) ((Number) last.get("routeId")).longValue());
        }
        for (Member m : members) hear(m, last, meetingId);

        // Frames flow, and their attestations vouch for them: nobody pauses.
        for (int i = 0; i < 30; i++) {
            host.send();
            Thread.sleep(40);
        }
        for (byte[] a : host.media.finish(System.currentTimeMillis())) host.link.sendAttestation(a);
        int opened = 0;
        for (byte[] f; (f = bob.frames.poll(1, TimeUnit.SECONDS)) != null; ) {
            if (bob.media.open(f, System.currentTimeMillis()) != null) opened++;
            if (opened == 30) break;
            if (bob.frames.isEmpty() && opened >= 25) break;
        }
        // A new speaker's first frames can precede the relay's next selection (every 100 ms, §7.4).
        assertTrue("Bob hears the host: " + opened, opened >= 25);
        Thread.sleep(1_500);
        bob.media.tick(System.currentTimeMillis() + CallMedia.ATTEST_DEADLINE_MS);
        assertFalse("attested, not paused", bob.media.isPaused(host.ssrc));

        // A member who did not create the meeting speaks for 3 s, attesting each second as
        // MeetingSession does: Carol plays all of it and never pauses (Decision 15).
        carol.frames.clear();
        int carolOpened = 0;
        long speakUntil = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < speakUntil) {
            bob.send();
            for (byte[] att : bob.media.takeAttestations(System.currentTimeMillis())) bob.link.sendAttestation(att);
            for (byte[] fr; (fr = carol.frames.poll()) != null; ) {
                if (carol.media.open(fr, System.currentTimeMillis()) != null) carolOpened++;
            }
            carol.media.tick(System.currentTimeMillis());
            Thread.sleep(40);
        }
        for (byte[] att : bob.media.finish(System.currentTimeMillis())) bob.link.sendAttestation(att);
        Thread.sleep(1_000);
        for (byte[] fr; (fr = carol.frames.poll()) != null; ) {
            if (carol.media.open(fr, System.currentTimeMillis()) != null) carolOpened++;
        }
        carol.media.tick(System.currentTimeMillis());
        assertFalse("Bob's audio is attested at Carol", carol.media.isPaused(bob.ssrc));
        assertTrue("Carol plays Bob: " + carolOpened + " of " + bob.seq, carolOpened >= bob.seq - 5);

        // A raised hand shows in everyone's roster.
        carol.link.hand(true);
        boolean handShown = false;
        for (Map<String, Object> roster; !handShown && (roster = bob.notice("roster", 5_000)) != null; ) {
            handShown = CallRelayLink.roster(roster).stream()
                    .anyMatch(e -> carol.fid.equals(e.get("fid")) && Boolean.TRUE.equals(e.get("hand")));
        }
        assertTrue("Carol's hand is in Bob's roster", handShown);

        // The host mutes Carol: she is told, and the relay drops her frames.
        host.link.control("mute", carol.ssrc);
        assertNotNull(carol.notice("muted", 5_000));
        bob.frames.clear();
        for (int i = 0; i < 10; i++) carol.send();
        assertNull("the relay dropped them", bob.frames.poll(1, TimeUnit.SECONDS));
        try {
            bob.link.control("kick", carol.fid);
            throw new AssertionError("only the host moderates");
        } catch (CallRelayLink.Refused e) {
            assertEquals(403, e.code);
        }

        // The owner rotated the symkey: the host rekeys, the others prove and switch (§4.5).
        byte[] symkey2 = random(32), nonce2 = random(32);
        byte[] secret2 = CallKeys.meetingSecret(symkey2, nonce2, TEAM, 2, meetingId);
        int epoch = host.link.rekey(2, nonce2, CallKeys.authPub(CallKeys.authPriv(secret2)));
        assertEquals(1, epoch);
        for (Member m : List.of(host, bob)) {
            Map<String, Object> n = m.notice("rekey", 5_000);
            assertNotNull(n);
            byte[] s2 = MeetingKeys.secret(List.of(symkey2), Hex.fromHex((String) n.get("nonce")), TEAM,
                    ((Number) n.get("symkeyVersion")).longValue(), meetingId, (String) n.get("authPub"));
            assertNotNull("the notice names keys the new symkey gives", s2);
            m.media.rekey(s2, epoch, System.currentTimeMillis());
            m.link.prove(epoch, CallKeys.authPriv(s2), m.ssrc);
        }
        bob.frames.clear();
        host.send();
        byte[] f = bob.frames.poll(2, TimeUnit.SECONDS);
        assertNotNull(f);
        assertNotNull("frames under the new epoch open", bob.media.open(f, System.currentTimeMillis()));
        try {
            carol.link.prove(epoch, CallKeys.authPriv(secret), carol.ssrc); // the old key
            throw new AssertionError("the old key proves nothing");
        } catch (CallRelayLink.Refused e) {
            assertEquals(401, e.code);
        }

        // The host ends it for everyone.
        host.link.control("end", null);
        assertNotNull(bob.notice("ended", 5_000));
        assertEquals(false, bob.link.info().get("open"));
    }
}
