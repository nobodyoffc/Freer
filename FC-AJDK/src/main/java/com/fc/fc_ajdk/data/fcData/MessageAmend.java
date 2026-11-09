package com.fc.fc_ajdk.data.fcData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Editing and withdrawing a message you sent: the rules, independent of any
 * transport, store or UI. The Mac client has the same rules in
 * MessageAmend.swift, with the same test vectors.
 *
 * <h3>Wire</h3>
 * An amendment is an ordinary signed message from the original's author:
 * <ul>
 *   <li>{@link ContentType#EDIT}: {@code content} is the new text.</li>
 *   <li>{@link ContentType#DELETE} (shown as "withdraw"): {@code content} is a
 *       short human line, read only by clients that predate this feature.</li>
 * </ul>
 * Both name the original in {@code requestId}, as a RECEIPT does, and are sent
 * to the same thread (type + targetId) as the original. An edit always names
 * the ORIGINAL, never an earlier edit, so the versions form a flat list and the
 * arrival order does not matter.
 *
 * <h3>What it cannot do</h3>
 * A withdraw is a request to well-behaved clients. A recipient that already
 * holds the message keeps whatever it chooses to keep. The author's client also
 * deletes the DOCK item when it can, which is the only real erasure: it stops
 * delivery to whoever has not fetched it yet.
 *
 * <h3>The window</h3>
 * {@link #WINDOW_MS} is measured between the two SIGNED timestamps, not against
 * any receiver's clock: a recipient that was offline for a day fetches the
 * original and its edit together, and its own clock says nothing about when
 * either was written. An author could forge their timestamps, but that only
 * lets them amend their own message late, which is no worse than sending a new
 * one.
 */
public final class MessageAmend {

    /** How long after sending a message its author may edit or withdraw it. */
    public static final long WINDOW_MS = 10 * 60_000L;

    /** Bound on versions kept per message, so one message cannot grow without limit. */
    public static final int MAX_VERSIONS = 20;

    private MessageAmend() {}

    public enum Outcome {
        /** The original changed; persist it and redraw its row. */
        APPLIED,
        /** Already applied, or the original is withdrawn: nothing to do. */
        DUPLICATE,
        /** Not a valid amendment of this original: drop it. */
        REJECTED
    }

    /** Whether this message is an EDIT or a withdraw rather than content. */
    public static boolean isAmend(ImMessage m) {
        return m != null && (m.getContentType() == ContentType.EDIT
                || m.getContentType() == ContentType.DELETE);
    }

    /** The id of the message an amendment targets, or null. */
    public static String targetOf(ImMessage op) {
        return isAmend(op) ? op.getRequestId() : null;
    }

    /** Whether a message of this kind can ever be edited (withdraw applies to any). */
    public static boolean isEditable(ImMessage original) {
        return original != null && original.getContentType() == ContentType.TEXT;
    }

    /**
     * Whether {@code myFid} may still edit or withdraw {@code original} at {@code now}:
     * their own, already sent, not withdrawn, inside the window.
     */
    public static boolean canAmend(ImMessage original, String myFid, long now) {
        if (original == null || myFid == null || !original.isOutgoing(myFid)) return false;
        if (original.isWithdrawn() || isAmend(original)) return false;
        if (!original.hasFudpId() || original.getTimestamp() == null) return false;
        MessageStatus s = original.getStatus();
        if (s == MessageStatus.PENDING || s == MessageStatus.FAILED) return false;
        long age = now - original.getTimestamp();
        return age >= 0 && age <= WINDOW_MS;
    }

    /** An EDIT of {@code original} carrying {@code newText}. The caller assigns its id. */
    public static ImMessage createEdit(ImMessage original, String newText, long now) {
        ImMessage op = base(original, ContentType.EDIT, now);
        op.setContent(newText);
        return op;
    }

    /**
     * A withdraw of {@code original}. {@code fallbackText} is what a client that
     * predates this feature shows as a plain message; current clients ignore it.
     */
    public static ImMessage createWithdraw(ImMessage original, String fallbackText, long now) {
        ImMessage op = base(original, ContentType.DELETE, now);
        op.setContent(fallbackText);
        return op;
    }

    private static ImMessage base(ImMessage original, ContentType ct, long now) {
        ImMessage op = new ImMessage();
        op.setType(original.getType());
        op.setSenderId(original.getSenderId());
        op.setTargetId(original.getTargetId());
        op.setContentType(ct);
        op.setRequestId(original.getId());
        op.setTimestamp(now);
        op.setStatus(MessageStatus.PENDING);
        op.setUnread(false);
        return op;
    }

    /**
     * Whether {@code op} is a well-formed amendment of {@code original}: the same
     * author (the envelope signature already proved who wrote {@code op}), the
     * same thread, and signed within the window.
     */
    public static boolean isValidFor(ImMessage original, ImMessage op) {
        if (original == null || !isAmend(op)) return false;
        if (original.getId() == null || !original.getId().equals(op.getRequestId())) return false;
        if (isAmend(original)) return false;
        if (op.getSenderId() == null || !op.getSenderId().equals(original.getSenderId())) return false;
        if (op.getType() != original.getType()) return false;
        if (op.getTargetId() == null || !op.getTargetId().equals(original.getTargetId())) return false;
        Long t0 = original.getTimestamp();
        Long t1 = op.getTimestamp();
        if (t0 == null || t1 == null) return false;
        long gap = t1 - t0;
        if (gap < 0 || gap > WINDOW_MS) return false;
        if (op.getContentType() == ContentType.EDIT) {
            if (!isEditable(original)) return false;
            String text = op.getContent();
            if (text == null || text.trim().isEmpty()) return false;
        }
        return true;
    }

    /**
     * Apply {@code op} to {@code original} in place.
     *
     * <p>A withdraw wins over everything: once withdrawn, the content, the data,
     * the sealed body and every earlier version are dropped, and later edits are
     * ignored. Edits are merged by their signed timestamp, so an older edit that
     * arrives late lands in the history instead of overwriting a newer one.
     */
    public static Outcome apply(ImMessage original, ImMessage op) {
        if (!isValidFor(original, op)) return Outcome.REJECTED;
        if (original.isWithdrawn()) return Outcome.DUPLICATE;

        if (op.getContentType() == ContentType.DELETE) {
            original.setWithdrawn(true);
            original.setWithdrawnAt(op.getTimestamp());
            original.setContent(null);
            original.setData(null);
            original.setBody(null);
            original.setEditHistory(null);
            original.setEditId(null);
            return Outcome.APPLIED;
        }

        // Every version, current one included, then the new one, ordered by when signed.
        List<ImMessage.Edition> versions = new ArrayList<>();
        if (original.getEditHistory() != null) versions.addAll(original.getEditHistory());
        Long currentAt = original.getEditedAt() != null ? original.getEditedAt() : original.getTimestamp();
        versions.add(new ImMessage.Edition(original.getContent(), currentAt, original.getEditId()));
        for (ImMessage.Edition v : versions) {
            if (op.getId() != null && op.getId().equals(v.getEditId())) return Outcome.DUPLICATE;
        }
        if (versions.size() >= MAX_VERSIONS) return Outcome.REJECTED;
        versions.add(new ImMessage.Edition(op.getContent(), op.getTimestamp(), op.getId()));

        // The original (null editId) sorts first at equal times; ties between edits go by id.
        versions.sort(Comparator
                .comparing((ImMessage.Edition v) -> v.getAt() != null ? v.getAt() : 0L)
                .thenComparing(v -> v.getEditId() == null ? "" : v.getEditId()));

        ImMessage.Edition latest = versions.remove(versions.size() - 1);
        original.setContent(latest.getContent());
        original.setEditId(latest.getEditId());
        original.setEditedAt(latest.getEditId() == null ? null : latest.getAt());
        original.setEditHistory(versions.isEmpty() ? null : versions);
        return Outcome.APPLIED;
    }
}
