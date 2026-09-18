package com.fc.fc_ajdk.data.fcData;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The FIMP envelope: the magic prefix, the single body field, the framing
 * inside it, the length rule that replaced v1's silent wrap, and the v3
 * signature trailer.
 */
public class ImWireV2Test {

    private static final String FID_A = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
    /** FTSP0 §2.1 example key for FID_A: every FIMP0V3 envelope is signed by its sender. */
    private static final byte[] PRIKEY_A = org.bouncycastle.util.encoders.Hex.decode(
            "a048f6c843f92bfe036057f7fc2bf2c27353c624cf7ad97e98ed41432f700575");
    private static final String FID_B = "F6vqNGkbAqZQ1YkPWLcXfNfwvJXCTGmzUM";

    private ImMessage text(String body) {
        ImMessage m = ImMessage.createText(ImType.P2P, FID_A, FID_B, body);
        m.setTimestamp(1_700_000_000_000L);
        m.setId("0000000000000001");
        return m;
    }

    // ---------- magic and version ----------

    @Test
    public void envelopeOpensWithMagicAndVersion() {
        byte[] wire = text("hello").toWireBytes(PRIKEY_A);
        assertEquals((byte) 0xF1, wire[0]);
        assertEquals((byte) 0x03, wire[1]);
    }

    /**
     * The reason the magic exists. A v1 envelope opens with the ImType ordinal,
     * so a bare version byte of 2 would be indistinguishable from a v1 TEAM
     * message -- and a v2 reader would misparse it rather than reject it.
     */
    @Test
    public void v1EnvelopesAreRejectedNotMisparsed() {
        for (int ordinal = 0; ordinal <= 3; ordinal++) {
            byte[] wire = text("hello").toWireBytes(PRIKEY_A);
            wire[0] = (byte) ordinal;
            try {
                ImMessage.fromWireBytes(wire);
                fail("ordinal " + ordinal + " should be refused as non-v2");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("v1"));
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownVersionIsRejected() {
        byte[] wire = text("hello").toWireBytes(PRIKEY_A);
        wire[1] = (byte) 0x09;
        ImMessage.fromWireBytes(wire);
    }

    // ---------- body framing ----------

    @Test
    public void contentAndDataRoundTripTogether() {
        byte[] audio = new byte[] {0, 1, (byte) 0xFF, 0x7F};
        ImMessage m = ImMessage.createVoice(ImType.P2P, FID_A, FID_B, "{\"durationMs\":3400}", audio);
        m.setTimestamp(1L);

        ImMessage back = ImMessage.fromWireBytes(m.toWireBytes(PRIKEY_A));
        assertEquals("{\"durationMs\":3400}", back.getContent());
        assertArrayEquals(audio, back.getData());
    }

    /**
     * A payload far past v1's 16-bit length prefix. v1 wrote lengths with
     * putShort(), which wrapped silently and corrupted every field after it --
     * the failure this version exists to end.
     */
    @Test
    public void payloadBeyondSixtyFiveThousandBytesSurvives() {
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);

        ImMessage m = ImMessage.createStream(ImType.P2P, FID_A, FID_B, "{}", payload);
        m.setTimestamp(1L);
        m.setId("00000000deadbeef");
        m.setThreadId("thread-9");

        ImMessage back = ImMessage.fromWireBytes(m.toWireBytes(PRIKEY_A));
        assertArrayEquals(payload, back.getData());
        // The fields written after the body are the ones v1's wrap destroyed.
        assertEquals("00000000deadbeef", back.getId());
        assertEquals("thread-9", back.getThreadId());
    }

    @Test
    public void roundTripIsStable() {
        ImMessage m = ImMessage.createStream(ImType.ROOM, FID_A, FID_B, "{\"name\":\"a\"}",
                new byte[] {9, 8, 7});
        m.setTimestamp(42L);
        m.setId("000000000000000f");
        m.setSymkeyVersion(3L);

        byte[] once = m.toWireBytes(PRIKEY_A);
        byte[] twice = ImMessage.fromWireBytes(once).toWireBytes(PRIKEY_A);
        assertArrayEquals(once, twice);
    }

    /**
     * Empty counts as absent. The framing records a length, not a presence, so
     * an empty section decodes to null; encoding it as an all-zero framing
     * would make the round trip unstable.
     */
    @Test
    public void emptyPayloadEncodesTheSameAsNoPayload() {
        ImMessage empty = text("");
        ImMessage none = text(null);

        byte[] encoded = empty.toWireBytes(PRIKEY_A);
        assertArrayEquals(encoded, none.toWireBytes(PRIKEY_A));

        ImMessage back = ImMessage.fromWireBytes(encoded);
        assertNull(back.getContent());
        assertNull(back.getData());
        assertArrayEquals(encoded, back.toWireBytes(PRIKEY_A));
    }

    // ---------- sealing is all-or-nothing ----------

    /**
     * The point of the single body field. In v1 this message would have gone
     * out with its metadata encrypted and its audio in the clear.
     */
    @Test
    public void sealingCoversTheBinaryPayloadToo() {
        byte[] symkey = new byte[32];
        for (int i = 0; i < 32; i++) symkey[i] = (byte) i;

        byte[] audio = new byte[4000];
        java.util.Arrays.fill(audio, (byte) 0xAB);
        String meta = "{\"durationMs\":3400,\"format\":\"aac\"}";

        ImMessage m = ImMessage.createVoice(ImType.TEAM, FID_A, FID_B, meta, audio);
        m.setTimestamp(1L);

        assertTrue(ImMessageBody.sealWithSymkey(m, symkey, 7L));
        assertNull("the metadata is inside the seal", m.getContent());
        assertNull("and so is the audio", m.getData());
        assertNotNull(m.getBody());

        byte[] wire = m.toWireBytes(PRIKEY_A);
        assertFalse("no cleartext audio anywhere in the envelope", contains(wire, audio));
        assertFalse("nor cleartext metadata", contains(wire, meta.getBytes()));

        ImMessage received = ImMessage.fromWireBytes(wire);
        assertTrue(received.isSealed());
        assertEquals(Long.valueOf(7L), received.getSymkeyVersion());
        assertTrue(ImMessageBody.openWithSymkey(received, symkey));
        assertEquals(meta, received.getContent());
        assertArrayEquals(audio, received.getData());
    }

    @Test
    public void sealedBodySurvivesTheWireUnchanged() {
        byte[] symkey = new byte[32];
        java.util.Arrays.fill(symkey, (byte) 0x44);

        ImMessage m = text("the usual place");
        assertTrue(ImMessageBody.sealWithSymkey(m, symkey, 2L));
        byte[] bundle = m.getBody();

        ImMessage back = ImMessage.fromWireBytes(m.toWireBytes(PRIKEY_A));
        assertArrayEquals("the bundle is carried byte for byte", bundle, back.getBody());
        assertTrue(ImMessageBody.openWithSymkey(back, symkey));
        assertEquals("the usual place", back.getContent());
    }

    @Test
    public void sealingWithNothingToSealFails() {
        byte[] symkey = new byte[32];
        assertFalse(ImMessageBody.sealWithSymkey(text(null), symkey, 1L));
    }

    // ---------- signature (FIMP0V3) ----------

    /**
     * The macOS client's bytes for the same message (its golden vector
     * "p2p-text", generated from this class). The Schnorr nonce is
     * deterministic on both sides, so the two implementations must agree to
     * the byte, signature included.
     */
    @Test
    public void signedBytesMatchTheMacClient() {
        ImMessage m = ImMessage.createText(ImType.P2P, FID_A, "F86zoAvNaQxEuYyvQssV5WxEzapNaiDtTW", "Meet me at the usual place.");
        m.setTimestamp(1755100000123L);
        m.setId("00000000deadbeef");
        assertEquals("f10300002246456b34314b716a61723435664c4472697a74554454556b646b69376d6d636a574b224638367a6f41764e615178457559797651737356355778457a61704e61694474545700000198a41caf7b0081000000230000001b4d656574206d652061742074686520757375616c20706c6163652e00000000001030303030303030306465616462656566030be1d7e633feb2338a74a860e76d893bac525f35a5813cb7b21e27ba1bc8312a6eef573988a0ea8732942803940b6208f4ada3c81d07b1282cb875edbec9f5f249b2cffdea5915d7ba9c4569846c995dad64cf1c62e946a7ff4c79bff2e0b51e",
                org.bouncycastle.util.encoders.Hex.toHexString(m.toWireBytes(PRIKEY_A)));
    }

    @Test
    public void tamperedEnvelopeIsRejected() {
        byte[] wire = text("hello").toWireBytes(PRIKEY_A);
        wire[wire.length - ImMessage.SIGNATURE_TRAILER_SIZE - 1] ^= 0x01;
        try {
            ImMessage.fromWireBytes(wire);
            fail("a changed byte must not verify");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("verify"));
        }
    }

    @Test
    public void unsignedEnvelopeIsRejected() {
        byte[] wire = text("hello").toWireBytes(PRIKEY_A);
        byte[] unsigned = java.util.Arrays.copyOf(wire, wire.length - ImMessage.SIGNATURE_TRAILER_SIZE);
        try {
            ImMessage.fromWireBytes(unsigned);
            fail("an envelope without its trailer must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("trailer"));
        }
    }

    /** B's valid signature on a message naming A is not A's message. */
    @Test
    public void signatureByAnotherFidIsRejected() {
        byte[] prikeyB = org.bouncycastle.util.encoders.Hex.decode(
                "ee72e6dd4047ef7f4c9886059cbab42eaab08afe7799cbc0539269ee7e2ec30c");
        String fidB = "F86zoAvNaQxEuYyvQssV5WxEzapNaiDtTW";
        ImMessage m = ImMessage.createText(ImType.P2P, fidB, FID_A, "it's A, honest");
        m.setTimestamp(1L);
        byte[] wire = m.toWireBytes(prikeyB);
        byte[] from = fidB.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] to = FID_A.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // The sender field starts after magic, version, type, contentType and its length byte.
        System.arraycopy(to, 0, wire, 5, from.length);
        try {
            ImMessage.fromWireBytes(wire);
            fail("the trailer's pubkey is not FID_A's");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not by the sender"));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void signingSomeoneElsesMessageThrows() {
        ImMessage m = ImMessage.createText(ImType.P2P, FID_B, FID_A, "not mine");
        m.setTimestamp(1L);
        m.toWireBytes(PRIKEY_A);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
