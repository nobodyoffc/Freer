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
 * The FIMP v2 envelope: the magic prefix, the single body field, the framing
 * inside it, and the length rule that replaced v1's silent wrap.
 */
public class ImWireV2Test {

    private static final String FID_A = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
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
        byte[] wire = text("hello").toWireBytes();
        assertEquals((byte) 0xF1, wire[0]);
        assertEquals((byte) 0x02, wire[1]);
    }

    /**
     * The reason the magic exists. A v1 envelope opens with the ImType ordinal,
     * so a bare version byte of 2 would be indistinguishable from a v1 TEAM
     * message -- and a v2 reader would misparse it rather than reject it.
     */
    @Test
    public void v1EnvelopesAreRejectedNotMisparsed() {
        for (int ordinal = 0; ordinal <= 3; ordinal++) {
            byte[] wire = text("hello").toWireBytes();
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
        byte[] wire = text("hello").toWireBytes();
        wire[1] = (byte) 0x09;
        ImMessage.fromWireBytes(wire);
    }

    // ---------- body framing ----------

    @Test
    public void contentAndDataRoundTripTogether() {
        byte[] audio = new byte[] {0, 1, (byte) 0xFF, 0x7F};
        ImMessage m = ImMessage.createVoice(ImType.P2P, FID_A, FID_B, "{\"durationMs\":3400}", audio);
        m.setTimestamp(1L);

        ImMessage back = ImMessage.fromWireBytes(m.toWireBytes());
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

        ImMessage back = ImMessage.fromWireBytes(m.toWireBytes());
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

        byte[] once = m.toWireBytes();
        byte[] twice = ImMessage.fromWireBytes(once).toWireBytes();
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

        byte[] encoded = empty.toWireBytes();
        assertArrayEquals(encoded, none.toWireBytes());

        ImMessage back = ImMessage.fromWireBytes(encoded);
        assertNull(back.getContent());
        assertNull(back.getData());
        assertArrayEquals(encoded, back.toWireBytes());
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

        byte[] wire = m.toWireBytes();
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

        ImMessage back = ImMessage.fromWireBytes(m.toWireBytes());
        assertArrayEquals("the bundle is carried byte for byte", bundle, back.getBody());
        assertTrue(ImMessageBody.openWithSymkey(back, symkey));
        assertEquals("the usual place", back.getContent());
    }

    @Test
    public void sealingWithNothingToSealFails() {
        byte[] symkey = new byte[32];
        assertFalse(ImMessageBody.sealWithSymkey(text(null), symkey, 1L));
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
