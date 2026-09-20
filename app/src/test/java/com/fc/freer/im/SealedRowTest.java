package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The placeholder standing in for a message whose key is missing.
 *
 * <p>What is stored is a marker carrying the version, never a sentence: the row keeps its meaning
 * across a language change, and the words a person reads are built at render time. The
 * {@code [Encrypted} prefix is what every "is this still sealed?" check looks for, so it is part
 * of the contract rather than an implementation detail.
 */
public class SealedRowTest {

    @Test
    public void aPlaceholderCarriesItsVersion() {
        String row = SealedRow.placeholder(1_789_813_689L);
        assertTrue(SealedRow.isPlaceholder(row));
        assertEquals(Long.valueOf(1_789_813_689L), SealedRow.versionOf(row));
    }

    /**
     * Rows written before this format exist in the field, and a person who cannot read their
     * backlog is exactly who this feature is for. The older sentence is still read.
     */
    @Test
    public void theOlderFormatIsStillRead() {
        String legacy = "[Encrypted - missing symkey(v3)]";
        assertTrue(SealedRow.isPlaceholder(legacy));
        assertEquals(Long.valueOf(3L), SealedRow.versionOf(legacy));
    }

    /** An older row that never named a version reads as sealed with nothing to name. */
    @Test
    public void aPlaceholderWithoutAVersionIsStillAPlaceholder() {
        assertTrue(SealedRow.isPlaceholder("[Encrypted]"));
        assertNull(SealedRow.versionOf("[Encrypted]"));
        assertTrue(SealedRow.isPlaceholder("[Encrypted - decryption failed]"));
        assertNull(SealedRow.versionOf("[Encrypted - decryption failed]"));
    }

    @Test
    public void ordinaryMessagesAreNotPlaceholders() {
        assertFalse(SealedRow.isPlaceholder("hello"));
        assertFalse(SealedRow.isPlaceholder(""));
        assertFalse(SealedRow.isPlaceholder(null));
        assertFalse("a message that merely mentions one", SealedRow.isPlaceholder("see [Encrypted]"));
        assertNull(SealedRow.versionOf("hello"));
    }
}
