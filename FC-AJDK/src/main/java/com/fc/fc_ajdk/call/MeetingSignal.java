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
 * MEETING_END    meetingId duration (ms) [entityId entityType, when sent 1:1]
 * MEETING_INVITE meetingId entityId entityType relay nonce authPub key title? started
 * </pre>
 *
 * A meeting of chosen people (Decision 20) is keyed by a random {@code key}
 * instead of the entity's symkey, and each invitee gets it in a
 * {@code MEETING_INVITE}: a 1:1 message, sealed to that invitee alone. Its
 * {@code symkeyVersion} is 1, and the key stands in for the symkey of an
 * entity named by the {@code meetingId} (§4.2).
 *
 * A host that rekeys a live meeting (§4.5) posts {@code MEETING_START} again
 * for the same {@code meetingId}, with the new keys and a higher
 * {@code keyEpoch}, so members who join later can still prove the key.
 */
public final class MeetingSignal {

    public enum Op { MEETING_START, MEETING_END, MEETING_INVITE }

    /** A chosen-people meeting's key is the symkey of this version of an entity named by the meetingId. */
    public static final long INVITED_VERSION = 1;

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
    /** MEETING_INVITE, and a 1:1 MEETING_END: the Room or Team the meeting belongs to. */
    public String entityId;
    /** TEAM or ROOM. */
    public String entityType;
    /** MEETING_INVITE: the meeting's key, 32 bytes hex. Only ever inside a message sealed to one invitee. */
    public String key;

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

    /** A chosen-people meeting's invitation (Decision 20). */
    public static MeetingSignal invite(String meetingId, String entityId, String entityType, CallSignal.Relay relay,
                                       byte[] nonce, byte[] authPub, byte[] key, String title, long startedMs) {
        MeetingSignal s = start(meetingId, relay, nonce, INVITED_VERSION, authPub, 0, title, startedMs);
        s.op = Op.MEETING_INVITE;
        s.entityId = entityId;
        s.entityType = entityType;
        s.key = Hex.toHex(key);
        return s;
    }

    /** The card for a chat: this invitation without its key. */
    public MeetingSignal withoutKey() {
        MeetingSignal s = fromJson(toJson());
        if (s == null) return null;
        s.op = Op.MEETING_START;
        s.key = null;
        return s;
    }

    public byte[] keyBytes() {
        return key == null ? null : Hex.fromHex(key);
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
            case MEETING_INVITE -> relay != null && relay.url() != null && !relay.url().isEmpty()
                    && hexLength(nonce) == 32 && hexLength(authPub) == 33 && hexLength(key) == 32
                    && entityId != null && !entityId.isEmpty()
                    && ("TEAM".equals(entityType) || "ROOM".equals(entityType)) && started != null;
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
