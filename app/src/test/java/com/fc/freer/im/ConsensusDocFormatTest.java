package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fc.freer.im.ConsensusDocFormat.Kind;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * What a consensus document's bytes are allowed to say about themselves.
 * <p>
 * Everything here is built from raw bytes rather than Java string literals: a fixture written
 * as a literal is valid UTF-8 by construction, which is the one thing these tests are about.
 */
public class ConsensusDocFormatTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // ── Decoding ────────────────────────────────────────────────────────

    @Test
    public void decodesUtf8() {
        // "Team 共识" — the multi-byte sequences spelled out rather than written as a literal.
        byte[] data = concat(ascii("Team "), bytes(0xE5, 0x85, 0xB1, 0xE8, 0xAF, 0x86));
        assertEquals("Team 共识", ConsensusDocFormat.decodeText(data));
    }

    @Test
    public void emptyDocumentIsEmptyText() {
        assertEquals("", ConsensusDocFormat.decodeText(new byte[0]));
    }

    /** A lone continuation byte is not UTF-8, and there is no second decoder to fall back on. */
    @Test
    public void rejectsMalformedUtf8() {
        assertNull(ConsensusDocFormat.decodeText(concat(ascii("consensus"), bytes(0x80, 0x81))));
    }

    /** Latin-1 bytes decode cleanly in a legacy charset, which is exactly why none is tried. */
    @Test
    public void rejectsLatin1() {
        // "café" as Latin-1: the 0xE9 starts a three-byte UTF-8 sequence that never arrives.
        assertNull(ConsensusDocFormat.decodeText(bytes(0x63, 0x61, 0x66, 0xE9)));
    }

    /** A truncated multi-byte sequence at the end of the file is malformed, not "close enough". */
    @Test
    public void rejectsTruncatedUtf8() {
        assertNull(ConsensusDocFormat.decodeText(concat(ascii("ok "), bytes(0xE5, 0x85))));
    }

    @Test
    public void rejectsBytesWithNul() {
        assertNull(ConsensusDocFormat.decodeText(concat(ascii("consensus"), bytes(0x00), ascii("doc"))));
    }

    @Test
    public void decodesUtf16LittleEndianWithMark() {
        byte[] data = bytes(0xFF, 0xFE, 0x68, 0x00, 0x69, 0x00);
        assertEquals("hi", ConsensusDocFormat.decodeText(data));
    }

    @Test
    public void decodesUtf16BigEndianWithMark() {
        byte[] data = bytes(0xFE, 0xFF, 0x00, 0x68, 0x00, 0x69);
        assertEquals("hi", ConsensusDocFormat.decodeText(data));
    }

    /** UTF-16 without a mark is not guessed at: the NUL bytes say the file is not prose. */
    @Test
    public void rejectsUtf16WithoutMark() {
        assertNull(ConsensusDocFormat.decodeText(bytes(0x68, 0x00, 0x69, 0x00)));
    }

    @Test
    public void dropsUtf8ByteOrderMark() {
        assertEquals("hi", ConsensusDocFormat.decodeText(concat(bytes(0xEF, 0xBB, 0xBF), ascii("hi"))));
    }

    /**
     * The case the strict decode exists for: a PDF with no NUL byte in its header. Lenient
     * decoding would hand back a page of mojibake under "this is what your team agreed to".
     */
    @Test
    public void rejectsPdfHeaderBytes() {
        byte[] pdf = concat(ascii("%PDF-1.7\n"), bytes(0xE2, 0xE3, 0xCF, 0xD3), ascii("\n"));
        assertNull(ConsensusDocFormat.decodeText(pdf));
        assertEquals(Kind.PDF, ConsensusDocFormat.sniff(pdf));
    }

    // ── Sniffing ────────────────────────────────────────────────────────

    @Test
    public void sniffsPdf() {
        assertEquals(Kind.PDF, ConsensusDocFormat.sniff(ascii("%PDF-1.4 rest of the file")));
    }

    @Test
    public void sniffsOle2AsOlderWord() {
        byte[] ole = concat(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), new byte[32]);
        assertEquals(Kind.DOC, ConsensusDocFormat.sniff(ole));
    }

    @Test
    public void sniffsRtf() {
        assertEquals(Kind.RTF, ConsensusDocFormat.sniff(ascii("{\\rtf1\\ansi")));
    }

    @Test
    public void sniffsImages() {
        assertEquals(Kind.PNG, ConsensusDocFormat.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)));
        assertEquals(Kind.JPEG, ConsensusDocFormat.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals(Kind.GIF, ConsensusDocFormat.sniff(ascii("GIF89a")));
    }

    /** The modern office formats are all zips, told apart by the names inside. */
    @Test
    public void sniffsZipFlavours() {
        assertEquals(Kind.DOCX, ConsensusDocFormat.sniff(zip("word/document.xml")));
        assertEquals(Kind.XLSX, ConsensusDocFormat.sniff(zip("xl/workbook.xml")));
        assertEquals(Kind.PPTX, ConsensusDocFormat.sniff(zip("ppt/presentation.xml")));
        assertEquals(Kind.ODT, ConsensusDocFormat.sniff(
                zip("mimetypeapplication/vnd.oasis.opendocument.text")));
        assertEquals(Kind.ZIP, ConsensusDocFormat.sniff(zip("notes/readme.txt")));
    }

    /** A name past the first few kilobytes is not read: the label is a guess, not a parse. */
    @Test
    public void zipNamesBeyondTheHeadAreNotRead() {
        byte[] far = concat(bytes(0x50, 0x4B, 0x03, 0x04), new byte[5000], ascii("word/document.xml"));
        assertEquals(Kind.ZIP, ConsensusDocFormat.sniff(far));
    }

    private static byte[] zip(String firstEntryName) {
        return concat(bytes(0x50, 0x4B, 0x03, 0x04), new byte[26], ascii(firstEntryName));
    }

    /** Prose is not a file kind, and neither is a handful of bytes. */
    @Test
    public void sniffsNothingFromText() {
        assertNull(ConsensusDocFormat.sniff(ascii("# Team consensus\n\nWhat we agreed.")));
        assertNull(ConsensusDocFormat.sniff(bytes(0x25)));
        assertNull(ConsensusDocFormat.sniff(new byte[0]));
        assertNull(ConsensusDocFormat.sniff(null));
    }

    // ── Naming ──────────────────────────────────────────────────────────

    @Test
    public void namesFileAfterItsIdAndKind() {
        String did = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
        assertEquals("consensus_a1b2c3d4.pdf", ConsensusDocFormat.fileName(did, Kind.PDF));
        // Unrecognised bytes are still a document; it just goes out without an extension.
        assertEquals("consensus_a1b2c3d4", ConsensusDocFormat.fileName(did, null));
        assertEquals("consensus.docx", ConsensusDocFormat.fileName(null, Kind.DOCX));
    }

    @Test
    public void unknownBytesGetAGenericMimeType() {
        assertEquals("application/octet-stream", ConsensusDocFormat.mimeTypeOf(null));
        assertEquals("application/pdf", ConsensusDocFormat.mimeTypeOf(Kind.PDF));
    }
}
