package com.fc.freer.im;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.widget.ImageView;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImMessageBody;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.ContactManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The "first FCH" request board built on the well-known default nobody freer.
 *
 * A nobody FID is one whose private key is publicly disclosed, so it can be
 * neither owned nor trusted: anyone can decrypt its DOCK inbox and anyone can
 * speak as it. The default nobody below is preinstalled as a public request
 * board where newcomers with zero balance post FIRST_FCH_REQUEST messages and
 * existing freers answer by sending FCH on-chain from their own FID.
 *
 * Content authored by a nobody FID must never be rendered as actionable
 * (no tappable cards, links, or payment requests) — anyone on earth can have
 * written it.
 */
public final class NobodyBoard {

    private static final String TAG = "NobodyBoard";

    /** Well-known default nobody freer. Its private key is public by design. */
    public static final String DEFAULT_NOBODY_FID = "FHG8DW2eHQ5wNAJQnLNKzYUSo2YKt7ffff";
    public static final String DEFAULT_NOBODY_PRIKEY = "d710ff828229c8fd9923407a5ebfb4a27a42504a1d69ae7ec95b9cc2c7073226";

    /** Wire prefix of a templated first-FCH request: FIRST_FCH_REQUEST|fid|note */
    public static final String FIRST_FCH_REQUEST_PREFIX = "FIRST_FCH_REQUEST|";
    public static final int REQUEST_NOTE_MAX_CHARS = 100;

    /** FIDs known to be nobodies, learned from any source during this session. */
    private static final Set<String> knownNobodies =
            Collections.synchronizedSet(new HashSet<>(Collections.singletonList(DEFAULT_NOBODY_FID)));

    /** Negative cache so adapter binds don't re-query the contact DB on the UI thread. */
    private static final Set<String> knownNotNobodies = Collections.synchronizedSet(new HashSet<>());

    /** Lazily decoded {@link #DEFAULT_NOBODY_PRIKEY}. */
    private static byte[] nobodyPrikeyBytes;

    private NobodyBoard() {}

    public static boolean isDefaultNobody(String fid) {
        return DEFAULT_NOBODY_FID.equals(fid);
    }

    /** Register a FID discovered to be a nobody (e.g. from an on-chain Freer lookup). */
    public static void markNobody(String fid) {
        if (fid != null && !fid.isEmpty()) {
            knownNobodies.add(fid);
            knownNotNobodies.remove(fid);
        }
    }

    /**
     * Whether the FID is known to be a nobody. Checks the session cache first,
     * then the local contact DB. Never touches the network — callers that
     * resolve a Freer from the API should call {@link #markNobody} themselves.
     */
    public static boolean isKnownNobody(String fid) {
        if (fid == null || fid.isEmpty()) return false;
        if (knownNobodies.contains(fid)) return true;
        if (knownNotNobodies.contains(fid)) return false;
        try {
            ContactManager contactManager = ContactManager.getInstance();
            if (contactManager != null) {
                Contact contact = contactManager.getContactByFid(fid);
                if (contact != null && Boolean.TRUE.equals(contact.getNobody())) {
                    knownNobodies.add(fid);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        knownNotNobodies.add(fid);
        return false;
    }

    /**
     * Open a board post and return its message with a readable body.
     *
     * A post reaches the board as an ordinary P2P message, so the sender seals
     * the body to the board's pubkey before handing it to a DOCK server (see
     * P2pHandler.sealedEnvelope — nothing that passes through a third party
     * travels in the clear). Every reader can open it, because the board's
     * private key is public, but a reader has to actually do it: decoding the
     * wire bytes alone yields a sealed envelope whose content is null, and the
     * whole board then looks empty.
     *
     * @return the message with its body opened, or null when it can't be read
     */
    public static ImMessage openBoardMessage(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length == 0) return null;
        try {
            ImMessage message = ImMessage.fromWireBytes(wireBytes);
            if (message == null) return null;
            if (message.isSealed()
                    && !ImMessageBody.openWithPrikey(message, defaultNobodyPrikey())) {
                return null;
            }
            return message;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Unreadable board item: %s", e.getMessage());
            return null;
        }
    }

    /** The board's private key. Published by design — this is a nobody. */
    private static synchronized byte[] defaultNobodyPrikey() {
        if (nobodyPrikeyBytes == null) {
            nobodyPrikeyBytes = BytesUtils.hexToByteArray(DEFAULT_NOBODY_PRIKEY);
        }
        return nobodyPrikeyBytes;
    }

    /** Build the templated request content sent to the board. */
    public static String buildFirstFchRequest(String requesterFid, String note) {
        String cleanNote = note == null ? "" : note.trim().replace('\n', ' ');
        if (cleanNote.length() > REQUEST_NOTE_MAX_CHARS) {
            cleanNote = cleanNote.substring(0, REQUEST_NOTE_MAX_CHARS);
        }
        return FIRST_FCH_REQUEST_PREFIX + requesterFid + "|" + cleanNote;
    }

    /** A parsed FIRST_FCH_REQUEST board item. */
    public static class Request {
        public final String requesterFid;
        public final String note;
        public final long timestamp;

        public Request(String requesterFid, String note, long timestamp) {
            this.requesterFid = requesterFid;
            this.note = note;
            this.timestamp = timestamp;
        }
    }

    /** Parse a board message; returns null when it doesn't match the template. */
    public static Request parseFirstFchRequest(String content, Long timestamp) {
        if (content == null || !content.startsWith(FIRST_FCH_REQUEST_PREFIX)) return null;
        String rest = content.substring(FIRST_FCH_REQUEST_PREFIX.length());
        int sep = rest.indexOf('|');
        String fid = sep >= 0 ? rest.substring(0, sep) : rest;
        String note = sep >= 0 ? rest.substring(sep + 1) : "";
        if (fid.isEmpty() || fid.length() > 40 || note.length() > REQUEST_NOTE_MAX_CHARS) return null;
        return new Request(fid, note, timestamp != null ? timestamp : 0L);
    }

    /** Render an avatar in black-and-white to mark a nobody identity. */
    public static void applyNobodyMark(ImageView avatarView) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        avatarView.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    public static void clearNobodyMark(ImageView avatarView) {
        avatarView.setColorFilter(null);
    }
}
