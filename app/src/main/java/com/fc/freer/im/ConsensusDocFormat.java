package com.fc.freer.im;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * What a consensus document's bytes actually are.
 * <p>
 * Nothing on chain says what a {@code consensusId} points at. That it is {@code sha256x2} of
 * the document is this client family's convention, and even that says nothing about the
 * document being written rather than typeset — owners on other clients carve PDFs and word
 * processor files. So the bytes get two questions asked of them: are they prose, and if not,
 * what do they look like. Neither answer decides whether the bytes are acceptable; the hash
 * already did that, and a document this client cannot name is still the one the team carved.
 * <p>
 * Pure byte logic, deliberately free of Android types so it can be tested directly.
 */
public final class ConsensusDocFormat {

    private ConsensusDocFormat() {}

    /** What a document looks like, from its leading bytes: enough to name it and to name its file. */
    public enum Kind {
        PDF("pdf", "application/pdf"),
        DOC("doc", "application/msword"),
        RTF("rtf", "application/rtf"),
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        GIF("gif", "image/gif"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        ODT("odt", "application/vnd.oasis.opendocument.text"),
        ZIP("zip", "application/zip");

        private final String extension;
        private final String mimeType;

        Kind(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }

        public String extension() {
            return extension;
        }

        public String mimeType() {
            return mimeType;
        }
    }

    /** The MIME type to hand another app, for a kind that may be null. */
    public static String mimeTypeOf(Kind kind) {
        return kind != null ? kind.mimeType() : "application/octet-stream";
    }

    /**
     * The bytes as prose, or null when they are not prose.
     * <p>
     * UTF-8, decoded <b>strictly</b>, and UTF-16 when a byte-order mark says so outright. No
     * further guessing, and that restraint is the point: every legacy decoder turns arbitrary
     * bytes into <i>something</i>, so a decoder that kept trying would put a page of mojibake
     * under the heading "this is what your team agreed to". Saying the document is not text and
     * handing over the file is the better answer.
     */
    public static String decodeText(byte[] data) {
        if (data == null) return null;
        if (startsWith(data, 0xFF, 0xFE) || startsWith(data, 0xFE, 0xFF)) {
            // UTF_16 reads the mark and picks the byte order from it, and drops the mark.
            return decode(StandardCharsets.UTF_16, data);
        }
        // A document with NUL bytes in it is not prose, whatever the decoder makes of it;
        // real UTF-16 text has already been taken by its mark above.
        for (byte b : data) {
            if (b == 0) return null;
        }
        String text = decode(StandardCharsets.UTF_8, data);
        // A UTF-8 mark decodes to a zero-width character that no reader wants at the top of
        // the document.
        if (text != null && text.startsWith("\uFEFF")) return text.substring(1);
        return text;
    }

    private static String decode(Charset charset, byte[] data) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(data));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** What the leading bytes say the document is, or null when nothing recognises them. */
    public static Kind sniff(byte[] data) {
        if (data == null) return null;
        if (startsWithAscii(data, "%PDF")) return Kind.PDF;
        // The OLE2 compound-file header, which is every pre-2007 Office document. Which one it
        // is lives inside the container, and .doc is the only one anybody carves as a consensus.
        if (startsWith(data, 0xD0, 0xCF, 0x11, 0xE0)) return Kind.DOC;
        if (startsWithAscii(data, "{\\rtf")) return Kind.RTF;
        if (startsWith(data, 0x89, 0x50, 0x4E, 0x47)) return Kind.PNG;
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) return Kind.JPEG;
        if (startsWithAscii(data, "GIF8")) return Kind.GIF;
        if (startsWith(data, 0x50, 0x4B, 0x03, 0x04)) return zipKind(data);
        return null;
    }

    /**
     * Which flavour of zip. The modern office formats are all zips, told apart by the directory
     * names inside — read out of the first few kilobytes rather than by unpacking, because this
     * is a label on a screen and not a parse.
     */
    private static Kind zipKind(byte[] data) {
        int head = Math.min(data.length, 4096);
        if (containsAscii(data, head, "word/")) return Kind.DOCX;
        if (containsAscii(data, head, "xl/")) return Kind.XLSX;
        if (containsAscii(data, head, "ppt/")) return Kind.PPTX;
        if (containsAscii(data, head, "opendocument.text")) return Kind.ODT;
        return Kind.ZIP;
    }

    /**
     * A file name for a document the store holds under its hash alone, so that whatever opens it
     * next has an extension to go on.
     */
    public static String fileName(String did, Kind kind) {
        String stem = "consensus";
        if (did != null && !did.isEmpty()) {
            stem += "_" + (did.length() > 8 ? did.substring(0, 8) : did);
        }
        return kind != null ? stem + "." + kind.extension() : stem;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] data, String prefix) {
        byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) {
            if (data[i] != bytes[i]) return false;
        }
        return true;
    }

    private static boolean containsAscii(byte[] data, int limit, String needle) {
        byte[] bytes = needle.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + bytes.length <= limit; i++) {
            int j = 0;
            while (j < bytes.length && data[i + j] == bytes[j]) j++;
            if (j == bytes.length) return true;
        }
        return false;
    }
}
