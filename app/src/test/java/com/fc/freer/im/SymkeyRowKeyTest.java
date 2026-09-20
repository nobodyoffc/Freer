package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * §2 of {@code docs/SYMKEY_IDENTITY_SPEC.md}: a key is stored under
 * {@code <entityId>_<19-digit version>_<16 hex of SHA256(symkey)>}, and that row key is read by
 * slicing from the <b>end</b>.
 *
 * <p>Two things rest on the fixed widths. An entity id containing {@code _} still parses, which a
 * forward split would get wrong; and the zero-padded version dominates the key text, so "which
 * version is current" is answered from row names without decrypting anything.
 */
public class SymkeyRowKeyTest {

    private static final String TEAM =
            "0a55726009ce3a31ede1a474442be6792de7e8cfbf0d1d45b866a0b1310202d4";
    private static final String HASH =
            "9f2c4a1b7d3e6058aabbccddeeff00112233445566778899aabbccddeeff0011";

    @Test
    public void aRowKeyRoundTrips() {
        String key = SymkeyStore.rowKey(TEAM, 1_789_813_689L, HASH);
        SymkeyStore.RowKey parsed = SymkeyStore.parseRowKey(key);
        assertNotNull(parsed);
        assertEquals(TEAM, parsed.entityId);
        assertEquals(1_789_813_689L, parsed.version);
        assertEquals(HASH.substring(0, 16), parsed.hashPrefix);
    }

    /** The reason for slicing from the end rather than splitting on the first underscore. */
    @Test
    public void anEntityIdContainingUnderscoresStillParses() {
        String odd = "a_room_named_with_underscores";
        SymkeyStore.RowKey parsed = SymkeyStore.parseRowKey(SymkeyStore.rowKey(odd, 7L, HASH));
        assertNotNull(parsed);
        assertEquals(odd, parsed.entityId);
        assertEquals(7L, parsed.version);
    }

    /**
     * Zero-padded, so lexical order is version order. This is what lets "the current version" be
     * read off the row names.
     */
    @Test
    public void rowKeysSortByVersion() {
        String legacy = SymkeyStore.rowKey(TEAM, 3L, HASH);
        String minted = SymkeyStore.rowKey(TEAM, 1_789_813_689L, HASH);
        assertTrue("a timestamp always sorts above a counter", legacy.compareTo(minted) < 0);

        String earlier = SymkeyStore.rowKey(TEAM, 1_789_813_688L, HASH);
        assertTrue(earlier.compareTo(minted) < 0);
    }

    /** Two different keys at one version are two rows, not one overwriting the other. */
    @Test
    public void twoKeysAtOneVersionAreTwoRows() {
        String other = "1111222233334444aabbccddeeff00112233445566778899aabbccddeeff0011";
        String a = SymkeyStore.rowKey(TEAM, 1_789_813_689L, HASH);
        String b = SymkeyStore.rowKey(TEAM, 1_789_813_689L, other);
        assertFalse("content identity is what keeps them apart", a.equals(b));

        assertEquals(SymkeyStore.parseRowKey(a).version, SymkeyStore.parseRowKey(b).version);
        assertEquals(SymkeyStore.parseRowKey(a).entityId, SymkeyStore.parseRowKey(b).entityId);
    }

    /** The same key stored twice is the same row, so a second delivery is a no-op. */
    @Test
    public void theSameKeyIsTheSameRow() {
        assertEquals(SymkeyStore.rowKey(TEAM, 12L, HASH), SymkeyStore.rowKey(TEAM, 12L, HASH));
    }

    /** A pre-spec row key does not parse, which is how the migration recognises one. */
    @Test
    public void legacyRowKeysDoNotParse() {
        assertNull(SymkeyStore.parseRowKey(TEAM + "_1"));
        assertNull(SymkeyStore.parseRowKey(TEAM + "_1789813689"));
        assertNull(SymkeyStore.parseRowKey(TEAM));
        assertNull(SymkeyStore.parseRowKey(null));
        assertNull(SymkeyStore.parseRowKey(""));
    }

    /** Neither does anything whose fixed-width tail is not a hash and a version. */
    @Test
    public void malformedRowKeysDoNotParse() {
        // 16 trailing chars that are not hex.
        assertNull(SymkeyStore.parseRowKey(TEAM + "_0000000000000000012_zzzzzzzzzzzzzzzz"));
        // 19 chars where the digits belong, but not digits.
        assertNull(SymkeyStore.parseRowKey(TEAM + "_notanumber123456789_9f2c4a1b7d3e6058"));
        // No entity id left once the tail is sliced off.
        assertNull(SymkeyStore.parseRowKey("_0000000000000000012_9f2c4a1b7d3e6058"));
    }

    /** §1's legacy threshold: below 1e9 a version is a counter, not a time. */
    @Test
    public void theLegacyThresholdSeparatesCountersFromTimes() {
        assertTrue(SymkeyStore.isLegacyVersion(1L));
        assertTrue(SymkeyStore.isLegacyVersion(3L));
        assertTrue(SymkeyStore.isLegacyVersion(SymkeyStore.LEGACY_VERSION_THRESHOLD - 1));
        assertFalse(SymkeyStore.isLegacyVersion(SymkeyStore.LEGACY_VERSION_THRESHOLD));
        assertFalse("a real mint time is about 1.79e9", SymkeyStore.isLegacyVersion(1_789_813_689L));
    }

    /**
     * A mint time past 2^31 has to survive the wire, which reads the field unsigned. The cliff
     * that used to sit at January 2038 now sits at 2106.
     */
    @Test
    public void aVersionPastIntMaxIsStillAVersion() {
        long past2038 = 2_200_000_000L;
        assertFalse(SymkeyStore.isLegacyVersion(past2038));
        SymkeyStore.RowKey parsed =
                SymkeyStore.parseRowKey(SymkeyStore.rowKey(TEAM, past2038, HASH));
        assertNotNull(parsed);
        assertEquals(past2038, parsed.version);
    }
}
