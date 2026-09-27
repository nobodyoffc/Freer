package com.fc.fc_ajdk.call;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** VOICE_SPEC §3.3: the meeting card's signals. */
public class MeetingSignalTest {

    private static final String ID = "mtg_00112233445566778899aabb";
    private static final byte[] NONCE = new byte[32];
    private static final byte[] AUTH_PUB = new byte[33];

    static {
        NONCE[0] = 7;
        AUTH_PUB[0] = 2;
    }

    @Test
    public void startRoundTrips() {
        MeetingSignal s = MeetingSignal.start(ID, new CallSignal.Relay("fudp://relay.example:19950", "02ab", "sid1"),
                NONCE, 1_790_000_000L, AUTH_PUB, 0, "Weekly", 1_790_000_000_000L);
        MeetingSignal back = MeetingSignal.fromJson(s.toJson());
        assertNotNull(back);
        assertEquals(MeetingSignal.Op.MEETING_START, back.op);
        assertEquals(ID, back.meetingId);
        assertEquals("fudp://relay.example:19950", back.relay.url());
        assertEquals("sid1", back.relay.sid());
        assertArrayEquals(NONCE, back.nonceBytes());
        assertArrayEquals(AUTH_PUB, back.authPubBytes());
        assertEquals(1_790_000_000L, back.symkeyVersion);
        assertEquals(0, back.keyEpochOrZero());
        assertEquals("Weekly", back.title);
    }

    @Test
    public void endRoundTrips() {
        MeetingSignal back = MeetingSignal.fromJson(MeetingSignal.end(ID, 65_000).toJson());
        assertNotNull(back);
        assertEquals(MeetingSignal.Op.MEETING_END, back.op);
        assertEquals(65_000L, back.duration);
    }

    @Test
    public void malformedIsRefused() {
        assertNull(MeetingSignal.fromJson("not json"));
        assertNull(MeetingSignal.fromJson("{\"op\":\"INVITE\",\"callId\":\"00112233445566778899aabbccddeeff\"}"),
                "a 1:1 signal is not a meeting signal");
        assertNull(MeetingSignal.fromJson(MeetingSignal.end("mtg_short", 1).toJson()), "a meeting id is mtg_ and 24 hex");
        assertNull(MeetingSignal.fromJson(MeetingSignal.end("0x_00112233445566778899aabb", 1).toJson()));
        MeetingSignal noRelay = MeetingSignal.start(ID, null, NONCE, 1, AUTH_PUB, 0, null, 1);
        assertNull(MeetingSignal.fromJson(noRelay.toJson()), "a start names its relay");
        MeetingSignal shortNonce = MeetingSignal.start(ID, new CallSignal.Relay("fudp://r:1"), new byte[16], 1,
                AUTH_PUB, 0, null, 1);
        assertNull(MeetingSignal.fromJson(shortNonce.toJson()), "the nonce is 32 bytes");
        MeetingSignal badKey = MeetingSignal.start(ID, new CallSignal.Relay("fudp://r:1"), NONCE, 1, new byte[32], 0,
                null, 1);
        assertNull(MeetingSignal.fromJson(badKey.toJson()), "authPub is a 33-byte key");
    }
}
