package com.fc.freer.call;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.call.Attestation;
import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.MediaFrame;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.freer.call.engine.EncodedFrame;

import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * CallMedia (VOICE_SPEC §5, §5.1): sealing, opening, and catching audio passed
 * off as someone else's, which the key alone cannot prevent since every
 * member holds it.
 */
public class CallMediaTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String CALL_ID = "00112233445566778899aabbccddeeff";

    long now = 1_000_000;
    byte[] secret;
    Party alice, bob, carol;
    final List<String> unverified = new ArrayList<>();

    static final class Party {
        final String fid;
        final byte[] tPriv = key();
        final byte[] tPub = KeyTools.prikeyToPubkey(tPriv);
        final int ssrc = RNG.nextInt();
        CallMedia media;

        Party(String fid) {
            this.fid = fid;
        }
    }

    static byte[] key() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    @Before
    public void setUp() {
        secret = key();
        alice = new Party("FAlice");
        bob = new Party("FBob");
        carol = new Party("FCarol");
        for (Party p : new Party[]{alice, bob, carol}) {
            p.media = new CallMedia(CALL_ID, secret, p.fid, p.ssrc, p.tPriv);
            p.media.setRouteId(p.ssrc ^ 0x5A5A5A5A);
            p.media.setListener((fid, ssrc) -> unverified.add(fid));
        }
        // Everyone knows everyone from the roster.
        for (Party p : new Party[]{alice, bob, carol}) {
            for (Party q : new Party[]{alice, bob, carol}) {
                if (p != q) p.media.addPeer(q.fid, q.ssrc, q.tPub);
            }
        }
    }

    static EncodedFrame frame(Party p, long seq) {
        return new EncodedFrame(p.ssrc, seq, seq * 960, 30, true, false, new byte[]{(byte) seq, 1, 2});
    }

    @Test
    public void framesOpenOnlyForTheRightPeerAndOnlyOnce() {
        byte[] wire = alice.media.seal(frame(alice, 0), now);
        EncodedFrame got = bob.media.open(wire, now);
        assertNotNull(got);
        assertEquals(alice.ssrc, got.ssrc());
        assertArrayEquals(new byte[]{0, 1, 2}, got.opus());
        assertNull("a replay", bob.media.open(wire, now));

        CallMedia stranger = new CallMedia(CALL_ID, secret, "FDave", RNG.nextInt(), key());
        assertNull("an ssrc not in the roster", stranger.open(alice.media.seal(frame(alice, 1), now), now));
        CallMedia otherCall = new CallMedia(CALL_ID, key(), bob.fid, bob.ssrc, bob.tPriv);
        otherCall.addPeer(alice.fid, alice.ssrc, alice.tPub);
        assertNull("another call's key", otherCall.open(alice.media.seal(frame(alice, 2), now), now));
    }

    @Test
    public void honestAudioIsVouchedForAndKeepsPlaying() {
        for (long seq = 0; seq < 25; seq++) {
            assertNotNull(bob.media.open(alice.media.seal(frame(alice, seq), now), now));
            now += 40;
        }
        List<byte[]> att = alice.media.takeAttestations(now);
        assertEquals(1, att.size());
        bob.media.onAttestation(att.get(0));
        now += 5_000;
        bob.media.tick(now);
        assertTrue(unverified.isEmpty());
        assertFalse(bob.media.isUnverified(alice.ssrc));
    }

    @Test
    public void aMemberPassingOffAudioAsAnothersIsCaught() {
        // Carol holds the call key, so she can seal a frame under Alice's ssrc and FID.
        byte[] aliceKey = CallKeys.senderKey(secret, alice.fid, alice.ssrc, CallMedia.KEY_EPOCH);
        byte[] forged = MediaFrame.seal(aliceKey, new MediaFrame.Header(MediaFrame.FLAG_VAD,
                alice.ssrc ^ 0x5A5A5A5A, alice.ssrc, 3, 3 * 960, 20, 0), new byte[]{9, 9, 9});
        for (long seq = 0; seq < 3; seq++) bob.media.open(alice.media.seal(frame(alice, seq), now), now);
        assertNotNull("it plays at first: encryption cannot tell", bob.media.open(forged, now));
        alice.media.seal(frame(alice, 3), now); // Alice's real frame 3, which a colluding relay dropped
        now += 1_000;
        for (byte[] a : alice.media.takeAttestations(now)) bob.media.onAttestation(a);
        assertEquals(List.of(alice.fid), unverified);
        assertTrue(bob.media.isUnverified(alice.ssrc));
        assertNull("silent for the rest of the join", bob.media.open(alice.media.seal(frame(alice, 4), now), now));
    }

    @Test
    public void audioWithNoAttestationIsSilencedAfterThreeSeconds() {
        bob.media.open(alice.media.seal(frame(alice, 0), now), now);
        now += CallMedia.ATTEST_DEADLINE_MS - 1;
        bob.media.tick(now);
        assertTrue(unverified.isEmpty());
        now += 1;
        bob.media.tick(now); // the relay withheld the attestation
        assertEquals(List.of(alice.fid), unverified);
    }

    @Test
    public void anAttestationSignedByAnotherKeyIsNotAccepted() {
        bob.media.open(alice.media.seal(frame(alice, 0), now), now);
        List<byte[]> frames = new ArrayList<>();
        frames.add(alice.media.seal(frame(alice, 0), now));
        // Carol signs a list naming Alice's ssrc: the roster says Alice's tPub signs for it.
        byte[] fake = Attestation.sign(carol.tPriv, CALL_ID, alice.ssrc ^ 0x5A5A5A5A, alice.ssrc, 0, frames).toBytes();
        bob.media.onAttestation(fake);
        now += CallMedia.ATTEST_DEADLINE_MS;
        bob.media.tick(now);
        assertEquals("so the frame stays unattested", List.of(alice.fid), unverified);
    }

    @Test
    public void dtxGapsAttestWithZeroDigests() {
        long[] sent = {0, 1, 5, 6, 30};
        for (long seq : sent) bob.media.open(alice.media.seal(new EncodedFrame(alice.ssrc, seq, seq * 960, 30,
                true, seq == 5 || seq == 30, new byte[]{(byte) seq}), now), now);
        now += 1_000;
        List<byte[]> att = alice.media.takeAttestations(now);
        assertEquals(1, att.size());
        Attestation a = Attestation.parse(att.get(0));
        assertEquals(0, a.firstSeq());
        assertEquals(30, a.lastSeq());
        assertArrayEquals("seq 2 was not sent", new byte[Attestation.DIGEST_LEN], a.digests()[2]);
        bob.media.onAttestation(att.get(0));
        now += 5_000;
        bob.media.tick(now);
        assertTrue(unverified.isEmpty());
    }

    @Test
    public void aLongSilenceStartsANewAttestationWindow() {
        alice.media.seal(frame(alice, 0), now);
        alice.media.seal(frame(alice, 500), now); // 20 s of DTX later
        now += 1_000;
        List<byte[]> att = alice.media.takeAttestations(now);
        assertEquals(2, att.size());
        assertEquals(0, Attestation.parse(att.get(0)).lastSeq());
        assertEquals(500, Attestation.parse(att.get(1)).firstSeq());
    }

    @Test
    public void attestationsHoldAtMost64Frames() {
        for (long seq = 0; seq < 100; seq++) alice.media.seal(frame(alice, seq), now);
        List<byte[]> att = alice.media.finish(now);
        assertEquals(2, att.size());
        assertEquals(63, Attestation.parse(att.get(0)).lastSeq());
        assertEquals(99, Attestation.parse(att.get(1)).lastSeq());
    }

    @Test
    public void framesOnTheDirectConnectionNeedNoAttestation() {
        // The direct path died with the attestations for its last frames (§5.1, §6.2 step 9).
        for (long seq = 0; seq < 25; seq++) {
            assertNotNull(bob.media.open(alice.media.seal(frame(alice, seq), now), now, true));
        }
        bob.media.tick(now + 10_000);
        assertFalse("the connection already proved who sent them", bob.media.isUnverified(alice.ssrc));
        assertNotNull(bob.media.open(alice.media.seal(frame(alice, 25), now), now + 10_000));
    }
}
