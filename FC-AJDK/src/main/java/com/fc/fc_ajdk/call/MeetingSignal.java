package com.fc.fc_ajdk.call;

import com.fc.fc_ajdk.utils.Hex;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * The content of a {@code CALL} message in a Room or Team chat (VOICE_SPEC
 * §3.3): JSON with an {@code op}. It is sealed under the entity's symkey like
 * any chat message, so only members read it.
 *
 * <pre>
 * MEETING_START  meetingId relay{url, pubkey?, sid?} nonce symkeyVersion authPub keyEpoch title? started
 * MEETING_END    meetingId duration (ms)
 * </pre>
 *
 * A host that rekeys a live meeting (§4.5) posts {@code MEETING_START} again
 * for the same {@code meetingId}, with the new keys and a higher
 * {@code keyEpoch}, so members who join later can still prove the key.
 */
public final class MeetingSignal {

    public enum Op { MEETING_START, MEETING_END }

    public static final String ID_PREFIX = "mtg_";

    private static final Gson GSON = new Gson();

    public Op op;
    public String meetingId;
    public CallSignal.Relay relay;
    public String nonce;
    public Long symkeyVersion;
    public String authPub;
    /** 0 at the start; each rekey adds one (§4.5). */
    public Long keyEpoch;
    public String title;
    public Long started;
    public Long duration;

    public static MeetingSignal start(String meetingId, CallSignal.Relay relay, byte[] nonce, long symkeyVersion,
                                      byte[] authPub, long keyEpoch, String title, long startedMs) {
        MeetingSignal s = new MeetingSignal();
        s.op = Op.MEETING_START;
        s.meetingId = meetingId;
        s.relay = relay;
        s.nonce = Hex.toHex(nonce);
        s.symkeyVersion = symkeyVersion;
        s.authPub = Hex.toHex(authPub);
        s.keyEpoch = keyEpoch;
        s.title = title == null || title.isEmpty() ? null : title;
        s.started = startedMs;
        return s;
    }

    public static MeetingSignal end(String meetingId, long durationMs) {
        MeetingSignal s = new MeetingSignal();
        s.op = Op.MEETING_END;
        s.meetingId = meetingId;
        s.duration = durationMs;
        return s;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** @return the signal, or null if the JSON is unreadable, not a meeting op, or lacks what its op needs */
    public static MeetingSignal fromJson(String json) {
        MeetingSignal s;
        try {
            s = json == null ? null : GSON.fromJson(json, MeetingSignal.class);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
        return s != null && s.wellFormed() ? s : null;
    }

    private boolean wellFormed() {
        if (op == null || !isMeetingId(meetingId)) return false;
        return switch (op) {
            case MEETING_START -> relay != null && relay.url() != null && !relay.url().isEmpty()
                    && hexLength(nonce) == 32 && hexLength(authPub) == 33 && symkeyVersion != null
                    && symkeyVersion >= 0 && started != null && (keyEpoch == null || keyEpoch >= 0);
            case MEETING_END -> true;
        };
    }

    public byte[] nonceBytes() {
        return Hex.fromHex(nonce);
    }

    public byte[] authPubBytes() {
        return Hex.fromHex(authPub);
    }

    /** 0 when a first-version host left it out. */
    public long keyEpochOrZero() {
        return keyEpoch == null ? 0 : keyEpoch;
    }

    /** {@code "mtg_"} and 24 hex digits (§8). */
    public static boolean isMeetingId(String id) {
        if (id == null || id.length() != ID_PREFIX.length() + 24 || !id.startsWith(ID_PREFIX)) return false;
        return hexLength(id.substring(ID_PREFIX.length())) == 12;
    }

    private static int hexLength(String hex) {
        try {
            byte[] b = hex == null ? null : Hex.fromHex(hex);
            return b == null ? -1 : b.length;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
