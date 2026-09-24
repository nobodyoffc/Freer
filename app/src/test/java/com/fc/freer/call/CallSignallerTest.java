package com.fc.freer.call;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.core.crypto.KeyTools;

import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CallSignaller (VOICE_SPEC §3.2) between simulated devices. Signals travel as
 * JSON, as they do inside a CALL message, through a queue the test pumps; a
 * FID may have several devices, each of which receives what is sent to it.
 */
public class CallSignallerTest {

    private static final SecureRandom RNG = new SecureRandom();

    long now = 1_790_000_000_000L;
    final ArrayDeque<Runnable> wire = new ArrayDeque<>();
    final Map<String, List<Device>> devices = new HashMap<>();
    long nextMessageId = 1;

    /** One device of a FID. */
    final class Device implements CallSignaller.Listener {
        final String fid;
        final byte[] fidPriv;
        final CallSignaller signaller;
        final List<CallSignaller.CallRecord> records = new ArrayList<>();
        final List<CallSignaller.Call> rang = new ArrayList<>();
        final List<CallSignaller.End> ended = new ArrayList<>();
        final List<CallSignaller.Call> answered = new ArrayList<>();
        final List<CallSignal> sent = new ArrayList<>();

        Device(byte[] fidPriv) {
            this.fidPriv = fidPriv;
            this.fid = KeyTools.pubkeyToFchAddr(KeyTools.prikeyToPubkey(fidPriv));
            this.signaller = new CallSignaller(fid, fidPriv, (peer, s) -> {
                sent.add(s);
                String json = s.toJson();
                String id = String.valueOf(nextMessageId++);
                wire.add(() -> {
                    for (Device d : devices.getOrDefault(peer, List.of())) {
                        d.signaller.onSignal(fid, id, CallSignal.fromJson(json));
                    }
                });
            }, (peer, r) -> records.add(r), () -> now);
            signaller.setListener(this);
            devices.computeIfAbsent(fid, k -> new ArrayList<>()).add(this);
        }

        @Override
        public void onIncoming(CallSignaller.Call call) {
            rang.add(call);
        }

        @Override
        public void onAnswered(CallSignaller.Call call) {
            answered.add(call);
        }

        @Override
        public void onEnded(CallSignaller.Call call, CallSignaller.End reason) {
            ended.add(reason);
        }

        CallSignaller.CallRecord lastRecord() {
            return records.isEmpty() ? null : records.get(records.size() - 1);
        }
    }

    Device alice, bob;

    static byte[] key() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    void pump() {
        while (!wire.isEmpty()) wire.poll().run();
    }

    @Before
    public void setUp() {
        alice = new Device(key());
        bob = new Device(key());
    }

    CallSignaller.Call ring() {
        CallSignaller.Call c = alice.signaller.invite(bob.fid, "fapi://relay.example:19900", null);
        pump();
        return c;
    }

    // ===== The answered call =====

    @Test
    public void anAnsweredCallGivesBothSidesTheSameKey() {
        CallSignaller.Call out = ring();
        assertEquals(1, bob.rang.size());
        CallSignaller.Call in = bob.rang.get(0);
        assertEquals(out.callId, in.callId);
        assertEquals(alice.fid, in.peerFid);
        assertEquals("fapi://relay.example:19900", in.relayUrl);

        bob.signaller.accept(in.callId, null);
        pump();
        assertEquals(1, alice.answered.size());
        byte[] a = alice.signaller.callSecret(out.callId), b = bob.signaller.callSecret(in.callId);
        assertNotNull(a);
        assertArrayEquals(a, b);
        assertEquals(CallSignaller.State.ACTIVE, alice.signaller.call(out.callId).state());
        // Each side verifies the other's delegation: it is the peer's, for this call.
        assertEquals(Delegation.Check.OK, alice.signaller.call(out.callId).peerDelegation().verify(out.callId, now / 1000));
    }

    @Test
    public void aPreparedCallRingsOnlyOnceTheRelayIsReached() {
        CallSignaller.Call out = alice.signaller.prepare(bob.fid, "fudp://relay.example:19950");
        pump();
        assertTrue("nothing is sent while the caller reaches its relay", alice.sent.isEmpty());
        assertEquals(CallSignaller.State.PREPARING, out.state());

        alice.signaller.ring(out.callId,
                new CallSignal.Relay("fudp://relay.example:19950", "02" + "ab".repeat(32), "sid-1"), null);
        pump();
        assertEquals(1, bob.rang.size());
        CallSignaller.Call in = bob.rang.get(0);
        assertEquals("the callee connects without discovery", "02" + "ab".repeat(32), in.relayPubkey);
        assertEquals("sid-1", in.relaySid);
    }

    @Test
    public void aKnockFromTheRelayAnswersWhenTheAcceptIsSlow() {
        CallSignaller.Call out = ring();
        CallSignaller.Call in = bob.rang.get(0);
        bob.signaller.accept(in.callId, null);
        // Bob's ACCEPT is still on its way; the relay passes on the delegation he joined with.
        alice.signaller.onKnock(out.callId, bob.signaller.call(in.callId).myDelegation);
        assertEquals(1, alice.answered.size());
        assertArrayEquals(alice.signaller.callSecret(out.callId), bob.signaller.callSecret(in.callId));
        pump(); // the ACCEPT arrives late and changes nothing
        assertEquals(1, alice.answered.size());
    }

    @Test
    public void aKnockFromSomeoneElseIsIgnored() {
        CallSignaller.Call out = ring();
        Device carol = new Device(key());
        byte[] tPub = KeyTools.prikeyToPubkey(key());
        alice.signaller.onKnock(out.callId, Delegation.sign(carol.fidPriv, out.callId, tPub, now / 1000 + 3600));
        assertTrue(alice.answered.isEmpty());
        assertEquals(CallSignaller.State.RINGING_OUT, alice.signaller.call(out.callId).state());
    }

    @Test
    public void cancellingAPreparedCallTellsNoOne() {
        CallSignaller.Call out = alice.signaller.prepare(bob.fid, "fudp://relay.example:19950");
        alice.signaller.cancel(out.callId);
        pump();
        assertTrue(alice.sent.isEmpty());
        assertTrue(bob.rang.isEmpty());
        assertEquals(List.of(CallSignaller.End.CANCELLED), alice.ended);
    }

    @Test
    public void hangingUpEndsBothSidesWithTheDuration() {
        CallSignaller.Call out = ring();
        bob.signaller.accept(out.callId, null);
        pump();
        now += 252_000; // 4:12
        alice.signaller.hangup(out.callId);
        pump();
        assertEquals(List.of(CallSignaller.End.LOCAL_HANGUP), alice.ended);
        assertEquals(List.of(CallSignaller.End.HUNG_UP), bob.ended);
        assertEquals(CallSignaller.CallRecord.Kind.ENDED, alice.lastRecord().kind());
        assertEquals(252_000, alice.lastRecord().durationMs());
        assertEquals(252_000, bob.lastRecord().durationMs());
        assertTrue(alice.lastRecord().outgoing());
        assertFalse(bob.lastRecord().outgoing());
        assertNull("keys are gone once it ends", alice.signaller.callSecret(out.callId));
    }

    @Test
    public void theTransportKeyIsWipedWhenTheCallEnds() {
        CallSignaller.Call out = ring();
        byte[] before = out.transportPriv();
        assertFalse(java.util.Arrays.equals(before, new byte[32]));
        alice.signaller.cancel(out.callId);
        assertArrayEquals(new byte[32], out.transportPriv());
    }

    // ===== Not answered =====

    @Test
    public void aDeclinedCallIsRecordedOnBothSides() {
        CallSignaller.Call out = ring();
        bob.signaller.reject(out.callId);
        pump();
        assertEquals(List.of(CallSignaller.End.DECLINED), alice.ended);
        assertEquals(CallSignaller.CallRecord.Kind.DECLINED, alice.lastRecord().kind());
        assertEquals(CallSignaller.CallRecord.Kind.DECLINED, bob.lastRecord().kind());
    }

    @Test
    public void aCancelledCallIsMissedByTheCallee() {
        CallSignaller.Call out = ring();
        alice.signaller.cancel(out.callId);
        pump();
        assertEquals(List.of(CallSignaller.End.MISSED), bob.ended);
        assertEquals(CallSignaller.CallRecord.Kind.MISSED, bob.lastRecord().kind());
        assertEquals(CallSignaller.CallRecord.Kind.CANCELLED, alice.lastRecord().kind());
    }

    @Test
    public void anUnansweredCallTimesOutAfter45Seconds() {
        CallSignaller.Call out = ring();
        now += CallSignal.RING_MS - 1;
        alice.signaller.tick();
        bob.signaller.tick();
        assertTrue(alice.ended.isEmpty());
        now += 1;
        alice.signaller.tick();
        pump();
        assertEquals(List.of(CallSignaller.End.NO_ANSWER), alice.ended);
        assertEquals(CallSignal.CANCEL_TIMEOUT, alice.sent.get(alice.sent.size() - 1).reason);
        assertEquals(List.of(CallSignaller.End.MISSED), bob.ended);
    }

    @Test
    public void anInviteThatArrivesExpiredIsAMissedCallNotARing() {
        CallSignaller.Call out = alice.signaller.invite(bob.fid, null, null);
        now += CallSignal.RING_MS + 60_000; // the DOCK copy, fetched a minute late
        pump();
        assertTrue(bob.rang.isEmpty());
        assertEquals(CallSignaller.CallRecord.Kind.MISSED, bob.lastRecord().kind());
        assertEquals(out.callId, bob.lastRecord().callId());
    }

    @Test
    public void aBusyCalleeRejectsANewInvite() {
        CallSignaller.Call first = ring();
        bob.signaller.accept(first.callId, null);
        pump();
        Device carol = new Device(key());
        carol.signaller.invite(bob.fid, null, null);
        pump();
        assertEquals("no second ring", 1, bob.rang.size());
        assertEquals(List.of(CallSignaller.End.BUSY), carol.ended);
        assertEquals(CallSignaller.CallRecord.Kind.MISSED, bob.lastRecord().kind());
    }

    // ===== Several devices, several channels =====

    @Test
    public void theCalleesOtherDeviceStopsRingingWhenOneAnswers() {
        Device bobTablet = new Device(bob.fidPriv); // same FID, another device
        CallSignaller.Call out = ring();
        assertEquals(1, bob.rang.size());
        assertEquals(1, bobTablet.rang.size());

        bob.signaller.accept(out.callId, null);
        pump();
        assertEquals(List.of(CallSignaller.End.ANSWERED_ELSEWHERE), bobTablet.ended);
        assertTrue("the device that answered is unaffected", bob.ended.isEmpty());
        assertEquals(CallSignaller.State.ACTIVE, bob.signaller.call(out.callId).state());
    }

    @Test
    public void theSameSignalByThreeChannelsRingsOnce() {
        CallSignal invite = CallSignal.invite(randomCallId(), KeyTools.prikeyToPubkey(key()), null, (String) null, null, now);
        byte[] tPriv = key();
        invite.transportPub = com.fc.fc_ajdk.utils.Hex.toHex(KeyTools.prikeyToPubkey(tPriv));
        invite.delegation = Delegation.sign(alice.fidPriv, invite.callId, KeyTools.prikeyToPubkey(tPriv),
                now / 1000 + 3600);
        for (int channel = 0; channel < 3; channel++) bob.signaller.onSignal(alice.fid, "m1", invite);
        assertEquals(1, bob.rang.size());
    }

    // ===== Forgeries =====

    @Test
    public void anInviteWithSomeoneElsesDelegationDoesNotRing() {
        Device mallory = new Device(key());
        String callId = randomCallId();
        byte[] tPriv = key(), tPub = KeyTools.prikeyToPubkey(tPriv);
        // Mallory sends an INVITE carrying a delegation Alice signed.
        CallSignal s = CallSignal.invite(callId, tPub, Delegation.sign(alice.fidPriv, callId, tPub, now / 1000 + 3600),
                (String) null, null, now);
        bob.signaller.onSignal(mallory.fid, "x", s);
        assertTrue(bob.rang.isEmpty());

        // A delegation for another transport key than the one the INVITE names.
        CallSignal t = CallSignal.invite(callId, KeyTools.prikeyToPubkey(key()),
                Delegation.sign(alice.fidPriv, callId, tPub, now / 1000 + 3600), (String) null, null, now);
        bob.signaller.onSignal(alice.fid, "y", t);
        assertTrue(bob.rang.isEmpty());
    }

    @Test
    public void anAcceptFromTheWrongPeerOrWithABadDelegationIsIgnored() {
        CallSignaller.Call out = ring();
        byte[] tPriv = key(), tPub = KeyTools.prikeyToPubkey(tPriv);
        Device mallory = new Device(key());
        alice.signaller.onSignal(mallory.fid, "a1", CallSignal.accept(out.callId, tPub,
                Delegation.sign(mallory.fidPriv, out.callId, tPub, now / 1000 + 3600), null));
        // Bob's FID, but a delegation for another call.
        alice.signaller.onSignal(bob.fid, "a2", CallSignal.accept(out.callId, tPub,
                Delegation.sign(bob.fidPriv, randomCallId(), tPub, now / 1000 + 3600), null));
        assertTrue(alice.answered.isEmpty());
        assertEquals(CallSignaller.State.RINGING_OUT, alice.signaller.call(out.callId).state());
        assertNull(alice.signaller.callSecret(out.callId));
    }

    @Test
    public void signalsAboutACallFromAThirdPartyAreIgnored() {
        CallSignaller.Call out = ring();
        bob.signaller.accept(out.callId, null);
        pump();
        Device mallory = new Device(key());
        bob.signaller.onSignal(mallory.fid, "h", CallSignal.hangup(out.callId, 1));
        alice.signaller.onSignal(mallory.fid, "r", CallSignal.reject(out.callId, CallSignal.REJECT_BUSY));
        assertTrue(bob.ended.isEmpty());
        assertTrue(alice.ended.isEmpty());
    }

    @Test
    public void wireFormatRejectsWhatAnOpNeeds() {
        assertNull(CallSignal.fromJson("{\"op\":\"INVITE\",\"callId\":\"00112233445566778899aabbccddeeff\"}"));
        assertNull(CallSignal.fromJson("{\"op\":\"HANGUP\",\"callId\":\"short\"}"));
        assertNull(CallSignal.fromJson("{\"op\":\"DANCE\",\"callId\":\"00112233445566778899aabbccddeeff\"}"));
        assertNull(CallSignal.fromJson("not json"));
        assertNotNull(CallSignal.fromJson("{\"op\":\"HANGUP\",\"callId\":\"00112233445566778899aabbccddeeff\",\"duration\":5}"));
    }

    static String randomCallId() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return com.fc.fc_ajdk.utils.Hex.toHex(b);
    }
}
