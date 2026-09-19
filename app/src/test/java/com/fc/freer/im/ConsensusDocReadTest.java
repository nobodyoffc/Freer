package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.freer.im.ConsensusDocFormat.Kind;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Reading a document off disk: what it is, and what that costs. */
public class ConsensusDocReadTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File write(byte[] data) throws IOException {
        File file = folder.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return file;
    }

    @Test
    public void proseIsProse() throws IOException {
        File file = write("# Team consensus\n\nWhat we agreed.".getBytes(StandardCharsets.UTF_8));
        ConsensusDocViewer.Doc doc = ConsensusDocViewer.read(file, "abc");
        assertEquals("# Team consensus\n\nWhat we agreed.", doc.text);
        assertNull(doc.kind);
    }

    /**
     * The case that used to render as mojibake: a PDF whose header happens to be valid UTF-8.
     * The sniffer answers before the decoder is ever asked.
     */
    @Test
    public void pdfWithNoNulBytesIsAFileNotText() throws IOException {
        File file = write("%PDF-1.4\nplain ascii all the way down\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII));
        ConsensusDocViewer.Doc doc = ConsensusDocViewer.read(file, "abc");
        assertNull(doc.text);
        assertEquals(Kind.PDF, doc.kind);
        assertEquals("consensus_abc.pdf", doc.fileName());
        // The bytes stay where they were: reading a document never consumes it.
        assertTrue(file.exists());
        assertEquals(file, doc.file);
    }

    /** A document too big for a dialog is handed over rather than rendered — and said to be. */
    @Test
    public void oversizeTextIsHandedOverAsAFile() throws IOException {
        byte[] big = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        File file = write(big);
        ConsensusDocViewer.Doc doc = ConsensusDocViewer.read(file, "abc");
        assertNull(doc.text);
        assertTrue(doc.tooLarge);
        assertEquals(big.length, doc.size);
    }

    @Test
    public void unrecognisedBytesAreStillADocument() throws IOException {
        File file = write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x01});
        ConsensusDocViewer.Doc doc = ConsensusDocViewer.read(file, "abcdef1234");
        assertNull(doc.text);
        assertNull(doc.kind);
        assertNotNull(doc.file);
        assertEquals("consensus_abcdef12", doc.fileName());
    }

    @Test(expected = IOException.class)
    public void missingFileIsAnError() throws IOException {
        ConsensusDocViewer.read(new File(folder.getRoot(), "nothing-here"), "abc");
    }
}
