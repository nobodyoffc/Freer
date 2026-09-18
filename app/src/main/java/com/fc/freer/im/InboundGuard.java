package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The two receive checks a signature cannot make on its own (FIMP0V3 §3.5,
 * steps 3 and 4). {@link ImMessage#fromWireBytes(byte[])} has already proven
 * who wrote a message; this decides whether it is for us, and whether we have
 * taken it in before.
 *
 * <p><b>Addressed to us.</b> A validly signed message can be put on a DOCK for
 * someone it was never for. A P2P message must name this identity as its
 * target, and when the DOCK says who an item is stored for, the target must be
 * one of those recipients.
 *
 * <p><b>Not seen before.</b> Anyone who has seen a signed envelope can put the
 * same bytes back under a new DOCK item id, and they verify every time. A
 * replayed chat line is a duplicate row; a replayed key request, key share or
 * room notice is an action taken twice. So every message taken in is
 * remembered by (senderId, id) — ids are unique per sender, not globally — for
 * a little longer than a DOCK may keep an item.
 */
public final class InboundGuard {
    private static final String TAG = "InboundGuard";
    private static final String SEEN_MAP = "fimp_seen_messages";
    public static final long RETENTION_MS = 400L * 24 * 60 * 60 * 1000;

    private final String liveFid;
    private final LocalDB<?> db;

    public InboundGuard(String liveFid, LocalDB<?> db) {
        this.liveFid = liveFid;
        this.db = db;
    }

    /**
     * Why {@code message} is not for us, or null when it is.
     *
     * @param itemRecipients who the DOCK stored the item for; null when the
     *                       message did not come from a DOCK item
     */
    public String misaddressed(ImMessage message, Collection<String> itemRecipients) {
        String target = message.getTargetId();
        if (target == null || target.isEmpty()) return "it names no target";
        if (message.getType() == ImType.P2P && !target.equals(liveFid)) {
            return "it is for " + target;
        }
        if (itemRecipients != null && !itemRecipients.isEmpty() && !itemRecipients.contains(target)) {
            return "it is for " + target + " but was stored for someone else";
        }
        return null;
    }

    /** True when this (senderId, id) has been taken in before. */
    public boolean isReplay(ImMessage message) {
        String key = key(message);
        if (key == null || db == null) return false;
        return db.getFromMap(SEEN_MAP, key) != null;
    }

    public void markSeen(ImMessage message) {
        String key = key(message);
        if (key == null || db == null) return;
        db.putInMap(SEEN_MAP, key, System.currentTimeMillis());
    }

    /**
     * Both checks, and the record, in one step: returns true when the message
     * should be processed, having remembered it.
     */
    public boolean admit(ImMessage message, Collection<String> itemRecipients) {
        String reason = misaddressed(message, itemRecipients);
        if (reason != null) {
            TimberLogger.i(TAG, "Dropped a message from %s not addressed to us: %s", message.getSenderId(), reason);
            return false;
        }
        if (isReplay(message)) {
            TimberLogger.d(TAG, "Dropped a replay of %s from %s", message.getId(), message.getSenderId());
            return false;
        }
        markSeen(message);
        return true;
    }

    /** Forget entries older than {@link #RETENTION_MS}. Returns how many went. */
    public int prune() {
        if (db == null) return 0;
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        Map<String, Object> all = db.getAllFromMap(SEEN_MAP);
        if (all == null) return 0;
        List<String> old = new ArrayList<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (e.getValue() instanceof Number && ((Number) e.getValue()).longValue() < cutoff) {
                old.add(e.getKey());
            }
        }
        if (!old.isEmpty()) db.removeFromMap(SEEN_MAP, old);
        return old.size();
    }

    private static String key(ImMessage message) {
        if (message.getSenderId() == null || message.getId() == null) return null;
        return message.getSenderId() + "|" + message.getId();
    }
}
