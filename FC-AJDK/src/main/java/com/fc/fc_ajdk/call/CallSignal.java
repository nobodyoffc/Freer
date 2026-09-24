package com.fc.fc_ajdk.call;

import com.fc.fc_ajdk.utils.Hex;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.List;

/**
 * The content of a {@code CALL} IM message for a 1:1 call (VOICE_SPEC §3.2):
 * JSON with an {@code op}. It rides FIMP, so it is signed, sealed and
 * deduplicated like any message; the fields here are only what the op needs.
 *
 * <pre>
 * INVITE  callId transportPub delegation relay{url, pubkey?, sid?}? candidates? expires codecs
 * ACCEPT  callId transportPub delegation candidates?
 * REJECT  callId reason ∈ declined | busy | unsupported
 * CANCEL  callId reason ∈ cancelled | timeout | answered_elsewhere
 * HANGUP  callId duration (ms)
 * </pre>
 */
public final class CallSignal {

    public enum Op { INVITE, ACCEPT, REJECT, CANCEL, HANGUP }

    public static final String REJECT_DECLINED = "declined";
    public static final String REJECT_BUSY = "busy";
    public static final String REJECT_UNSUPPORTED = "unsupported";
    public static final String CANCEL_CANCELLED = "cancelled";
    public static final String CANCEL_TIMEOUT = "timeout";
    public static final String CANCEL_ANSWERED_ELSEWHERE = "answered_elsewhere";

    /** An INVITE rings for this long (§3.2). */
    public static final long RING_MS = 45_000;
    public static final List<String> CODECS = List.of("opus");

    private static final Gson GSON = new Gson();

    /**
     * The relay to meet on (§6.2). {@code pubkey} and {@code sid}, which the
     * caller learned connecting to it, let the callee connect straight away
     * instead of discovering the relay over a path that may be lossy.
     */
    public record Relay(String url, String pubkey, String sid) {
        public Relay(String url) {
            this(url, null, null);
        }
    }

    /** A direct-path address (§6.1): {@code t} is map, lan or home; {@code a} is ip:port. */
    public record Candidate(String t, String a) {}

    public Op op;
    public String callId;
    public String transportPub;
    public Delegation delegation;
    public Relay relay;
    public List<Candidate> candidates;
    public Long expires;
    public List<String> codecs;
    public String reason;
    public Long duration;

    public static CallSignal invite(String callId, byte[] tPub, Delegation d, String relayUrl,
                                    List<Candidate> candidates, long nowMs) {
        return invite(callId, tPub, d, relayUrl == null ? null : new Relay(relayUrl), candidates, nowMs);
    }

    public static CallSignal invite(String callId, byte[] tPub, Delegation d, Relay relay,
                                    List<Candidate> candidates, long nowMs) {
        CallSignal s = base(Op.INVITE, callId);
        s.transportPub = Hex.toHex(tPub);
        s.delegation = d;
        s.relay = relay;
        s.candidates = candidates == null || candidates.isEmpty() ? null : candidates;
        s.expires = nowMs + RING_MS;
        s.codecs = CODECS;
        return s;
    }

    public static CallSignal accept(String callId, byte[] tPub, Delegation d, List<Candidate> candidates) {
        CallSignal s = base(Op.ACCEPT, callId);
        s.transportPub = Hex.toHex(tPub);
        s.delegation = d;
        s.candidates = candidates == null || candidates.isEmpty() ? null : candidates;
        return s;
    }

    public static CallSignal reject(String callId, String reason) {
        CallSignal s = base(Op.REJECT, callId);
        s.reason = reason;
        return s;
    }

    public static CallSignal cancel(String callId, String reason) {
        CallSignal s = base(Op.CANCEL, callId);
        s.reason = reason;
        return s;
    }

    public static CallSignal hangup(String callId, long durationMs) {
        CallSignal s = base(Op.HANGUP, callId);
        s.duration = durationMs;
        return s;
    }

    private static CallSignal base(Op op, String callId) {
        CallSignal s = new CallSignal();
        s.op = op;
        s.callId = callId;
        return s;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * @return the signal, or null if the JSON is unreadable or lacks what its
     *         op requires. Delegations are only checked for presence here; the
     *         receiver verifies them (§3.2).
     */
    public static CallSignal fromJson(String json) {
        CallSignal s;
        try {
            s = GSON.fromJson(json, CallSignal.class);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
        return s != null && s.wellFormed() ? s : null;
    }

    private boolean wellFormed() {
        if (op == null || !isCallId(callId)) return false;
        return switch (op) {
            case INVITE -> isPub(transportPub) && delegation != null && expires != null
                    && (codecs == null || codecs.contains("opus"));
            case ACCEPT -> isPub(transportPub) && delegation != null;
            case REJECT, CANCEL -> reason != null;
            case HANGUP -> true;
        };
    }

    public byte[] transportPubBytes() {
        return Hex.fromHex(transportPub);
    }

    /** 16 random bytes, hex (§3.2). */
    public static boolean isCallId(String id) {
        if (id == null || id.length() != 32) return false;
        try {
            byte[] b = Hex.fromHex(id);
            return b != null && b.length == 16;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isPub(String hex) {
        try {
            byte[] b = hex == null ? null : Hex.fromHex(hex);
            return b != null && b.length == 33;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
