package com.fc.freer.im;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where every symkey that entered or left this device is recorded -- FIMP §9.7, §5 of
 * {@code docs/SYMKEY_IDENTITY_SPEC.md}.
 *
 * <p><b>Why this exists.</b> A device answers a symkey request automatically, on a membership
 * check it makes silently, and the key it hands over opens every message under that version —
 * including messages sent long before the requester joined. Nothing else leaves a trace of
 * that, so the ledger is the only place the question "who can read this conversation" has an
 * answer. {@link #holders(String, long)} is that question; it is deliberately <i>not</i> the
 * membership list, because a member the owner never managed to push to can read nothing, and a
 * FID removed from the group still holds every version it was ever given.
 *
 * <p><b>Durable, and local.</b> No expiry and no cap: after a suspected leak the row that
 * matters is characteristically the oldest, which is exactly what a bounded log has already
 * dropped. It is never transmitted — a history share is built from stored messages and nothing
 * else — and it is deleted only with the conversation it describes, since keeping a map of who
 * could read an erased chat is its own disclosure.
 *
 * <p><b>Growth is bounded by coalescing, not by a limit.</b> An admitted key costs its sender
 * ownership of the entity or a request we made, so those rows are bounded by honest activity.
 * A <i>refusal</i> costs an attacker only the sending, so a member pushing junk keys in a loop
 * would otherwise write a row per attempt. Identical events inside {@link #COALESCE_WINDOW_MS}
 * fold into the row already there and bump {@code repeats}, which keeps the signal a person
 * needs — "Carol keeps pushing keys at me", with a first and a last time — at one row an hour
 * instead of thousands. A refusal and a share are never folded together.
 */
public class KeyLedger {
    private static final String TAG = "KeyLedger";

    /** Identical events inside this window fold into one row. */
    public static final long COALESCE_WINDOW_MS = 60L * 60 * 1000;

    private static final int TIME_DIGITS = 19;
    private static final int IDENTITY_CHARS = 12;

    /** Which way a key moved. */
    public enum Direction { SENT, RECEIVED }

    /** What became of it. */
    public enum Outcome {
        /** Handed over. */
        SHARED,
        /** Arrived and is now held. */
        STORED,
        /** Arrived and was already held. */
        DUPLICATE,
        /** Arrived from somebody with no standing to send it, and was dropped. */
        REFUSED,
        /** Arrived as a cipher that would not open. */
        UNREADABLE,
        /** Asked for, and not held here. */
        NOT_HELD,
        /** Asked for by somebody who is not a member. */
        NOT_A_MEMBER,
        /** Asked for by somebody whose pubkey could not be resolved, so nothing could be sealed. */
        NO_PUBKEY
    }

    private final LocalDB<KeyEvent> db;

    /** Event identity -> the row currently absorbing its repeats. */
    private final Map<String, String> openRows = new ConcurrentHashMap<>();
    private volatile boolean indexed = false;

    public KeyLedger(String liveFid) {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, KeyEvent.class, KeyEvent.class);
    }

    // ================= writing =================

    /** A key we handed to somebody. */
    public void recordSent(String entityId, long version, String counterparty,
                           Outcome outcome, boolean solicited, String requestId) {
        record(entityId, version, counterparty, Direction.SENT, outcome, solicited, requestId);
    }

    /** A key somebody handed us. */
    public void recordReceived(String entityId, long version, String counterparty,
                               Outcome outcome, boolean solicited, String requestId) {
        record(entityId, version, counterparty, Direction.RECEIVED, outcome, solicited, requestId);
    }

    /**
     * Write one event.
     *
     * <p><b>Never fails the exchange.</b> A lost row must not cost somebody their key, so every
     * failure here is logged and swallowed.
     */
    public void record(String entityId, long version, String counterparty, Direction direction,
                       Outcome outcome, boolean solicited, String requestId) {
        if (db == null || direction == null || outcome == null) return;
        try {
            long now = System.currentTimeMillis();
            String identity = identityOf(entityId, version, counterparty, direction, outcome);

            ensureIndexed();
            String openId = openRows.get(identity);
            if (openId != null) {
                KeyEvent existing = db.get(openId);
                if (existing != null && existing.getAt() != null
                        && now - existing.getAt() < COALESCE_WINDOW_MS) {
                    existing.setLastAt(now);
                    existing.setRepeats(existing.getRepeats() + 1);
                    db.put(openId, existing);
                    return;
                }
            }

            KeyEvent event = new KeyEvent();
            // The ms in the row key makes row order the time order with no sort; the identity
            // suffix keeps two different events in one millisecond distinct, and makes the
            // *same* event in the same millisecond the same row, so a replayed delivery cannot
            // write twice.
            event.setId(String.format("%0" + TIME_DIGITS + "d", now) + "_" + identity);
            event.setEntityId(entityId);
            event.setVersion(version);
            event.setCounterparty(counterparty);
            event.setDirection(direction);
            event.setOutcome(outcome);
            event.setSolicited(solicited);
            event.setRequestId(requestId);
            event.setAt(now);
            event.setLastAt(now);
            event.setRepeats(1);

            db.put(event.getId(), event);
            openRows.put(identity, event.getId());
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to record key event: %s", e.getMessage());
        }
    }

    /** The ledger's word for what the store did with a key that arrived. */
    public static Outcome outcomeOf(SymkeyStore.StoreResult result) {
        if (result == null) return Outcome.REFUSED;
        return switch (result) {
            case STORED -> Outcome.STORED;
            case DUPLICATE -> Outcome.DUPLICATE;
            case UNREADABLE -> Outcome.UNREADABLE;
            default -> Outcome.REFUSED;
        };
    }

    // ================= reading =================

    /** Every event, newest first. */
    public List<KeyEvent> all() {
        return forEntity(null);
    }

    /** Every event about one entity, newest first. */
    public List<KeyEvent> forEntity(String entityId) {
        List<KeyEvent> result = new ArrayList<>();
        if (db == null) return result;
        Map<String, KeyEvent> all = db.getAll();
        if (all == null) return result;
        for (KeyEvent event : all.values()) {
            if (event == null) continue;
            if (entityId != null && !entityId.equals(event.getEntityId())) continue;
            result.add(event);
        }
        result.sort((a, b) -> Long.compare(
                b.getLastAt() != null ? b.getLastAt() : 0L,
                a.getLastAt() != null ? a.getLastAt() : 0L));
        return result;
    }

    /**
     * The leak radius of one key: everyone we gave it to, and everyone who gave it to us.
     *
     * <p>Refusals and keys that never opened are not holders, so they are left out.
     */
    public Set<String> holders(String entityId, long version) {
        Set<String> fids = new LinkedHashSet<>();
        for (KeyEvent event : forEntity(entityId)) {
            if (event.getVersion() == null || event.getVersion() != version) continue;
            if (event.getCounterparty() == null) continue;
            Outcome outcome = event.getOutcome();
            if (outcome != Outcome.SHARED && outcome != Outcome.STORED && outcome != Outcome.DUPLICATE) {
                continue;
            }
            fids.add(event.getCounterparty());
        }
        return fids;
    }

    /** The same question about one person: which versions of an entity they got from us. */
    public List<Long> versionsGiven(String fid, String entityId) {
        TreeSet<Long> versions = new TreeSet<>();
        if (fid == null) return new ArrayList<>(versions);
        for (KeyEvent event : forEntity(entityId)) {
            if (event.getDirection() != Direction.SENT) continue;
            if (event.getOutcome() != Outcome.SHARED) continue;
            if (!fid.equals(event.getCounterparty())) continue;
            if (event.getVersion() != null) versions.add(event.getVersion());
        }
        return new ArrayList<>(versions);
    }

    /**
     * Erase an entity's rows. Called only when the conversation itself is erased: keeping a map
     * of who could read a chat that no longer exists is its own disclosure.
     */
    public void forget(String entityId) {
        if (db == null || entityId == null) return;
        List<String> ids = new ArrayList<>();
        for (KeyEvent event : forEntity(entityId)) {
            if (event.getId() != null) ids.add(event.getId());
        }
        if (ids.isEmpty()) return;
        db.removeList(ids);
        openRows.values().removeAll(ids);
    }

    // ================= internals =================

    /**
     * What makes two events "the same event". Outcome is part of it, so a refusal never folds
     * into a share.
     */
    private static String identityOf(String entityId, long version, String counterparty,
                                     Direction direction, Outcome outcome) {
        String material = entityId + "|" + version + "|" + counterparty + "|"
                + direction.name() + "|" + outcome.name();
        String hex = Hex.toHex(Hash.sha256(material.getBytes(StandardCharsets.UTF_8)));
        return hex.substring(0, IDENTITY_CHARS);
    }

    /** Rebuild the identity -> open row map once per session, from the rows already on disk. */
    private void ensureIndexed() {
        if (indexed || db == null) return;
        synchronized (this) {
            if (indexed) return;
            try {
                Map<String, KeyEvent> all = db.getAll();
                if (all != null) {
                    for (Map.Entry<String, KeyEvent> row : all.entrySet()) {
                        String id = row.getKey();
                        if (id == null || id.length() <= TIME_DIGITS + 1) continue;
                        String identity = id.substring(TIME_DIGITS + 1);
                        String held = openRows.get(identity);
                        if (held == null || held.compareTo(id) < 0) openRows.put(identity, id);
                    }
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to index key ledger: %s", e.getMessage());
            }
            indexed = true;
        }
    }

    /** One recorded movement of one key. Never leaves this device. */
    public static class KeyEvent extends FcEntity {
        private String entityId;
        private Long version;          // 0 when the event was about no particular key
        private String counterparty;   // the FID it went to, or came from
        private Direction direction;
        private Outcome outcome;
        private Boolean solicited;     // did it answer a request, or was it a proactive push
        private String requestId;      // when solicited
        private Long at;               // first occurrence
        private Long lastAt;           // and last
        private Integer repeats;

        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }

        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }

        public String getCounterparty() { return counterparty; }
        public void setCounterparty(String counterparty) { this.counterparty = counterparty; }

        public Direction getDirection() { return direction; }
        public void setDirection(Direction direction) { this.direction = direction; }

        public Outcome getOutcome() { return outcome; }
        public void setOutcome(Outcome outcome) { this.outcome = outcome; }

        public boolean isSolicited() { return solicited != null && solicited; }
        public void setSolicited(Boolean solicited) { this.solicited = solicited; }

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }

        public Long getAt() { return at; }
        public void setAt(Long at) { this.at = at; }

        public Long getLastAt() { return lastAt; }
        public void setLastAt(Long lastAt) { this.lastAt = lastAt; }

        public int getRepeats() { return repeats != null ? repeats : 1; }
        public void setRepeats(Integer repeats) { this.repeats = repeats; }

        public String toJson() { return JsonUtils.toJson(this); }
        public static KeyEvent fromJson(String json) { return JsonUtils.fromJson(json, KeyEvent.class); }
    }
}
