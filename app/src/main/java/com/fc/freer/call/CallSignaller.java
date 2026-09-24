package com.fc.freer.call;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.CallSignal.Op;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.Hex;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signalling for 1:1 calls on one device (VOICE_SPEC §3.2): the INVITE /
 * ACCEPT / REJECT / CANCEL / HANGUP state machine, the throwaway transport
 * key and delegation of each call, and the local call records the chat shows.
 * <p>
 * No Android and no IM in here: signals go out through {@link Outbox} and
 * come in through {@link #onSignal}, after the IM layer has decoded, checked
 * the FIMP signature, deduplicated channels and applied the stranger gate.
 * Every method is synchronized; the listener is called with the lock held,
 * so it must not block.
 */
public final class CallSignaller {

    /** A delegation lasts this long: longer than any call, well inside the 24 h cap (§4.1). */
    static final long DELEGATION_SEC = 12 * 3600;
    /** Message ids remembered for deduplication across FUDP, ROAD and DOCK (§6.3). */
    static final int SEEN_LIMIT = 512;

    public interface Outbox {
        /** Send on every channel at once (§6.3). */
        void send(String peerFid, CallSignal signal);
    }

    public interface Records {
        /** Add a local entry to the chat with {@code peerFid}. Never sent (§10). */
        void record(String peerFid, CallRecord record);
    }

    public interface Clock {
        long nowMs();
    }

    public interface Listener {
        /** Ring. */
        void onIncoming(Call call);

        /** The callee answered; {@link #callSecret} now works on both sides. */
        void onAnswered(Call call);

        /** The call is over, or never started, for {@code reason}. */
        void onEnded(Call call, End reason);
    }

    public enum End { DECLINED, BUSY, UNSUPPORTED, WRONG_RELAY, CANCELLED, NO_ANSWER, MISSED, ANSWERED_ELSEWHERE, HUNG_UP,
        LOCAL_HANGUP }

    /** PREPARING: the caller is reaching its relay; nothing has been sent yet. */
    public enum State { PREPARING, RINGING_OUT, RINGING_IN, ACTIVE, ENDED }

    /** What the chat shows for a call. */
    public record CallRecord(Kind kind, boolean outgoing, long atMs, long durationMs, String callId) {
        public enum Kind { ENDED, MISSED, DECLINED, NO_ANSWER, BUSY, CANCELLED, ANSWERED_ELSEWHERE }
    }

    public static final class Call {
        public final String callId;
        public final String peerFid;
        public final boolean outgoing;
        final byte[] tPriv;
        public final byte[] tPub;
        public final Delegation myDelegation;
        public final String relayUrl;
        /** The relay's key and service id, when the caller sent them (§3.2). */
        public String relayPubkey, relaySid;
        public final List<CallSignal.Candidate> peerCandidates = new ArrayList<>();
        byte[] peerTPub;
        Delegation peerDelegation;
        long expiresMs;
        long answeredAtMs = -1;
        State state;

        Call(String callId, String peerFid, boolean outgoing, byte[] tPriv, Delegation myDelegation, String relayUrl,
             State state) {
            this.callId = callId;
            this.peerFid = peerFid;
            this.outgoing = outgoing;
            this.tPriv = tPriv;
            this.tPub = KeyTools.prikeyToPubkey(tPriv);
            this.myDelegation = myDelegation;
            this.relayUrl = relayUrl;
            this.state = state;
        }

        public State state() {
            return state;
        }

        /** The transport key the call's FUDP node runs under (§4.1). Deleted when the call ends. */
        public byte[] transportPriv() {
            return tPriv.clone();
        }

        public Delegation peerDelegation() {
            return peerDelegation;
        }
    }

    private final String myFid;
    private final byte[] fidPriv;
    private final Outbox outbox;
    private final Records records;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Which relays I answer on: my own home.CALL (§6.2). Anything, until set. */
    private volatile java.util.function.Predicate<CallSignal.Relay> relayPolicy = r -> true;
    private final Map<String, Call> calls = new HashMap<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > SEEN_LIMIT;
        }
    };
    private Listener listener;

    public CallSignaller(String myFid, byte[] fidPriv, Outbox outbox, Records records, Clock clock) {
        this.myFid = myFid;
        this.fidPriv = fidPriv.clone();
        this.outbox = outbox;
        this.records = records;
        this.clock = clock;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    // ===== Outgoing =====

    /**
     * Ring {@code peerFid}. The relay is the one to meet on if no direct path
     * works (§6.2); candidates are only for contacts, and never with Always
     * relay on (Decision 8), which is the caller's to decide.
     */
    public synchronized Call invite(String peerFid, String relayUrl, List<CallSignal.Candidate> candidates) {
        Call c = prepare(peerFid, relayUrl);
        ring(c.callId, relayUrl == null ? null : new CallSignal.Relay(relayUrl), candidates);
        return c;
    }

    /**
     * A call's keys and delegation, with nothing sent: the caller reaches its
     * relay first, so the callee never rings for a call that cannot connect.
     */
    public synchronized Call prepare(String peerFid, String relayUrl) {
        if (busy(null)) throw new IllegalStateException("already in a call");
        long now = clock.nowMs();
        byte[] id = new byte[16];
        random.nextBytes(id);
        String callId = Hex.toHex(id);
        byte[] tPriv = newTransportKey();
        Delegation d = Delegation.sign(fidPriv, callId, KeyTools.prikeyToPubkey(tPriv), now / 1000 + DELEGATION_SEC);
        Call c = new Call(callId, peerFid, true, tPriv, d, relayUrl, State.PREPARING);
        c.expiresMs = Long.MAX_VALUE;
        calls.put(callId, c);
        return c;
    }

    /** Send the INVITE of a prepared call; its 45 s ring starts now. */
    public synchronized void ring(String callId, CallSignal.Relay relay, List<CallSignal.Candidate> candidates) {
        Call c = calls.get(callId);
        if (c == null || c.state != State.PREPARING) return;
        long now = clock.nowMs();
        c.state = State.RINGING_OUT;
        c.expiresMs = now + CallSignal.RING_MS;
        if (relay != null) {
            c.relayPubkey = relay.pubkey();
            c.relaySid = relay.sid();
        }
        outbox.send(c.peerFid, CallSignal.invite(callId, c.tPub, c.myDelegation, relay, candidates, now));
    }

    /** Answer only INVITEs whose relay passes: my own home.CALL (§6.2). */
    public void setRelayPolicy(java.util.function.Predicate<CallSignal.Relay> policy) {
        relayPolicy = policy == null ? r -> true : policy;
    }

    /** The caller gives up before an answer. */
    public synchronized void cancel(String callId) {
        Call c = calls.get(callId);
        if (c == null || !c.outgoing || (c.state != State.RINGING_OUT && c.state != State.PREPARING)) return;
        // A prepared call never rang: there is no one to tell.
        if (c.state == State.RINGING_OUT) outbox.send(c.peerFid, CallSignal.cancel(callId, CallSignal.CANCEL_CANCELLED));
        end(c, End.CANCELLED, CallRecord.Kind.CANCELLED);
    }

    // ===== Incoming =====

    /** Answer. The caller derives the key once this ACCEPT verifies (§3.2). */
    public synchronized Call accept(String callId, List<CallSignal.Candidate> candidates) {
        Call c = calls.get(callId);
        if (c == null || c.outgoing || c.state != State.RINGING_IN) return null;
        c.state = State.ACTIVE;
        c.answeredAtMs = clock.nowMs();
        outbox.send(c.peerFid, CallSignal.accept(callId, c.tPub, c.myDelegation, candidates));
        return c;
    }

    public synchronized void reject(String callId) {
        Call c = calls.get(callId);
        if (c == null || c.outgoing || c.state != State.RINGING_IN) return;
        outbox.send(c.peerFid, CallSignal.reject(callId, CallSignal.REJECT_DECLINED));
        end(c, End.DECLINED, CallRecord.Kind.DECLINED);
    }

    /** Either side ends an answered call. */
    public synchronized void hangup(String callId) {
        Call c = calls.get(callId);
        if (c == null || c.state != State.ACTIVE) return;
        long duration = clock.nowMs() - c.answeredAtMs;
        outbox.send(c.peerFid, CallSignal.hangup(callId, duration));
        end(c, End.LOCAL_HANGUP, CallRecord.Kind.ENDED);
    }

    /**
     * The peer left the relay and did not come back: it hung up, and its
     * HANGUP, which travels over IM, is slow or lost. Ends the call as a
     * HANGUP would; nothing is sent.
     */
    public synchronized void peerLeft(String callId) {
        Call c = calls.get(callId);
        if (c == null || c.state != State.ACTIVE) return;
        end(c, End.HUNG_UP, CallRecord.Kind.ENDED);
    }

    // ===== Received =====

    /**
     * A decoded, signature-checked CALL message from {@code senderFid}, from a
     * peer the user has accepted. The IM layer handles strangers (§3.2) and
     * records their INVITEs as missed calls itself.
     */
    public synchronized void onSignal(String senderFid, String messageId, CallSignal s) {
        if (senderFid == null || senderFid.equals(myFid) || s == null) return;
        // The same signal arrives by FUDP, ROAD and DOCK (§6.3).
        if (seen.put(senderFid + "|" + messageId, Boolean.TRUE) != null) return;
        switch (s.op) {
            case INVITE -> onInvite(senderFid, s);
            case ACCEPT -> onAccept(senderFid, s);
            case REJECT -> onReject(senderFid, s);
            case CANCEL -> onCancel(senderFid, s);
            case HANGUP -> onHangup(senderFid, s);
        }
    }

    private void onInvite(String caller, CallSignal s) {
        if (calls.containsKey(s.callId)) return;
        long now = clock.nowMs();
        if (!delegationHolds(s, caller)) return;
        // The DOCK copy of an INVITE usually arrives after it expired: a missed call, no ring.
        if (s.expires <= now) {
            records.record(caller, new CallRecord(CallRecord.Kind.MISSED, false, now, 0, s.callId));
            return;
        }
        if (!relayPolicy.test(s.relay)) {
            // Not my CALL service: the caller does not get to choose where I talk.
            outbox.send(caller, CallSignal.reject(s.callId, CallSignal.REJECT_RELAY));
            records.record(caller, new CallRecord(CallRecord.Kind.MISSED, false, now, 0, s.callId));
            return;
        }
        if (busy(s.callId)) {
            outbox.send(caller, CallSignal.reject(s.callId, CallSignal.REJECT_BUSY));
            records.record(caller, new CallRecord(CallRecord.Kind.MISSED, false, now, 0, s.callId));
            return;
        }
        byte[] tPriv = newTransportKey();
        Delegation mine = Delegation.sign(fidPriv, s.callId, KeyTools.prikeyToPubkey(tPriv),
                now / 1000 + DELEGATION_SEC);
        Call c = new Call(s.callId, caller, false, tPriv, mine, s.relay == null ? null : s.relay.url(),
                State.RINGING_IN);
        if (s.relay != null) {
            c.relayPubkey = s.relay.pubkey();
            c.relaySid = s.relay.sid();
        }
        c.peerTPub = s.transportPubBytes();
        c.peerDelegation = s.delegation;
        c.expiresMs = s.expires;
        if (s.candidates != null) c.peerCandidates.addAll(s.candidates);
        calls.put(s.callId, c);
        if (listener != null) listener.onIncoming(c);
    }

    private void onAccept(String callee, CallSignal s) {
        Call c = calls.get(s.callId);
        if (c == null || !c.outgoing || c.state != State.RINGING_OUT || !c.peerFid.equals(callee)) return;
        // No ACCEPT-dependent traffic before its delegation verifies (§3.2).
        if (!delegationHolds(s, callee)) return;
        c.peerTPub = s.transportPubBytes();
        c.peerDelegation = s.delegation;
        if (s.candidates != null) c.peerCandidates.addAll(s.candidates);
        c.state = State.ACTIVE;
        c.answeredAtMs = clock.nowMs();
        // The callee's other devices are still ringing.
        outbox.send(callee, CallSignal.cancel(s.callId, CallSignal.CANCEL_ANSWERED_ELSEWHERE));
        if (listener != null) listener.onAnswered(c);
    }

    /**
     * The relay says the callee is waiting to join, with the delegation it
     * joined under (§6.2 step 3). That delegation is the callee's own signed
     * statement for this call, made only on answering, so it answers the call
     * as an ACCEPT would: the ACCEPT itself may be slow over IM, or lost.
     */
    public synchronized void onKnock(String callId, Delegation d) {
        if (d == null || d.fid == null) return;
        onAccept(d.fid, CallSignal.accept(callId, d.tPubBytes(), d, null));
    }

    private void onReject(String callee, CallSignal s) {
        Call c = calls.get(s.callId);
        if (c == null || !c.outgoing || c.state != State.RINGING_OUT || !c.peerFid.equals(callee)) return;
        switch (s.reason) {
            case CallSignal.REJECT_BUSY -> end(c, End.BUSY, CallRecord.Kind.BUSY);
            case CallSignal.REJECT_UNSUPPORTED -> end(c, End.UNSUPPORTED, CallRecord.Kind.DECLINED);
            case CallSignal.REJECT_RELAY -> end(c, End.WRONG_RELAY, CallRecord.Kind.NO_ANSWER);
            default -> end(c, End.DECLINED, CallRecord.Kind.DECLINED);
        }
    }

    private void onCancel(String caller, CallSignal s) {
        Call c = calls.get(s.callId);
        if (c == null || c.outgoing || !c.peerFid.equals(caller)) return;
        if (c.state != State.RINGING_IN) return; // e.g. answered_elsewhere reaching the device that answered
        if (CallSignal.CANCEL_ANSWERED_ELSEWHERE.equals(s.reason)) {
            end(c, End.ANSWERED_ELSEWHERE, CallRecord.Kind.ANSWERED_ELSEWHERE);
        } else {
            end(c, End.MISSED, CallRecord.Kind.MISSED);
        }
    }

    private void onHangup(String peer, CallSignal s) {
        Call c = calls.get(s.callId);
        if (c == null || c.state != State.ACTIVE || !c.peerFid.equals(peer)) return;
        end(c, End.HUNG_UP, CallRecord.Kind.ENDED);
    }

    // ===== Time =====

    /** Call about once a second: an unanswered INVITE expires after 45 s on both sides. */
    public synchronized void tick() {
        long now = clock.nowMs();
        for (Call c : new ArrayList<>(calls.values())) {
            if (c.state == State.RINGING_OUT && now >= c.expiresMs) {
                outbox.send(c.peerFid, CallSignal.cancel(c.callId, CallSignal.CANCEL_TIMEOUT));
                end(c, End.NO_ANSWER, CallRecord.Kind.NO_ANSWER);
            } else if (c.state == State.RINGING_IN && now >= c.expiresMs) {
                end(c, End.MISSED, CallRecord.Kind.MISSED);
            }
        }
        calls.values().removeIf(c -> c.state == State.ENDED);
    }

    // ===== Keys (§4.2) =====

    /** The call's forward-secret key, once both transport keys are known. */
    public synchronized byte[] callSecret(String callId) {
        Call c = calls.get(callId);
        if (c == null || c.peerTPub == null || c.state == State.ENDED) return null;
        return CallKeys.p2pSecret(c.tPriv, c.peerTPub, callId, myFid, c.peerFid);
    }

    public synchronized Call call(String callId) {
        return calls.get(callId);
    }

    /** A call is in progress, other than {@code except}: new INVITEs get REJECT busy (§3.2). */
    public synchronized boolean busy(String except) {
        for (Call c : calls.values()) {
            if (c.state != State.ENDED && !c.callId.equals(except)) return true;
        }
        return false;
    }

    // ===== Helpers =====

    /** The signal's delegation is from {@code fid}, for this call, for the transport key it names (§4.1). */
    private boolean delegationHolds(CallSignal s, String fid) {
        Delegation d = s.delegation;
        return d != null && fid.equals(d.fid) && d.verify(s.callId, clock.nowMs() / 1000) == Delegation.Check.OK
                && Arrays.equals(d.tPubBytes(), s.transportPubBytes());
    }

    private void end(Call c, End reason, CallRecord.Kind kind) {
        long now = clock.nowMs();
        long duration = c.answeredAtMs >= 0 ? now - c.answeredAtMs : 0;
        c.state = State.ENDED;
        Arrays.fill(c.tPriv, (byte) 0); // §4.1: deleted at hang-up; this is what makes 1:1 forward secret
        records.record(c.peerFid, new CallRecord(kind, c.outgoing, now, duration, c.callId));
        if (listener != null) listener.onEnded(c, reason);
    }

    private byte[] newTransportKey() {
        byte[] k = new byte[32];
        random.nextBytes(k);
        return k;
    }
}
