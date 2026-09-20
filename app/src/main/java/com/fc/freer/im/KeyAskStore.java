package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.DatabaseManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The symkey questions this device has put to other people and not yet had answered -- §4 of
 * {@code docs/SYMKEY_IDENTITY_SPEC.md}.
 *
 * <p><b>An ask is state, not a toast.</b> Three things need it to be durable:
 *
 * <ul>
 *   <li>It is what makes acceptance checkable. FIMP4V3 §4.2 admits a non-owner's key only when
 *       it answers a request <i>this device</i> made, and an ask that sent messages without
 *       recording them would have every answer dropped on arrival — the request goes out, the
 *       member answers, and nothing happens, permanently. That is the failure this app had in
 *       the field for member-to-member answers, invisible because the <i>owner's</i> answers
 *       still got through.</li>
 *   <li>It paces retries. The cooldown is <b>per person</b>, not per question: what the limit
 *       bounds is cost borne by whoever answers, so what must be capped is how often one member
 *       is made to answer the same thing. Counting per question would mean a user who asked one
 *       member and got nothing could not ask a second for two minutes, which throttles recovery
 *       rather than traffic.</li>
 *   <li>It is what the UI has to show: who was asked, and how long recovery has been stalled.</li>
 * </ul>
 *
 * <p>An ask is cleared when a key for its version is <b>stored</b>, not when one arrives: a
 * cipher that would not open left us no better off.
 */
public class KeyAskStore {

    /** One request per (entity, version, responder) per two minutes. FIMP4V3 §7.4. */
    public static final long COOLDOWN_MS = 120_000L;

    /** How long an unanswered ask is remembered. Longer than a DOCK holds anything. */
    static final long ASK_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;

    /** Version 0: "whatever you hold now", which any version answers. */
    public static final long CURRENT = 0L;

    /** What was asked for. A room's details answer carries a key too, so it is an ask as well. */
    public enum Kind { SYMKEY, ROOM_INFO }

    private final LocalDB<KeyAsk> db;

    public KeyAskStore(String liveFid) {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, KeyAsk.class, KeyAsk.class);
    }

    private static String askId(Kind kind, String entityId, long version) {
        return kind.name() + "_" + entityId + "_" + version;
    }

    /** The unexpired ask for this question, or null. An expired one is forgotten on the way. */
    public KeyAsk get(Kind kind, String entityId, long version) {
        if (db == null || entityId == null) return null;
        String id = askId(kind, entityId, version);
        KeyAsk ask = db.get(id);
        if (ask == null) return null;
        if (isExpired(ask, System.currentTimeMillis())) {
            db.remove(id);
            return null;
        }
        return ask;
    }

    /**
     * Whether this member was put this same question inside the cooldown.
     *
     * <p>Read-only: asking is recorded by {@link #recordAsk}, once the message has actually been
     * sent. A check that also stamped the clock would silence the next attempt after a send that
     * failed.
     */
    public boolean onCooldown(Kind kind, String entityId, long version, String fid) {
        KeyAsk ask = get(kind, entityId, version);
        if (ask == null || fid == null) return false;
        Long last = ask.getAskedAt().get(fid);
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    /** How long until this member may be asked again, in ms, or 0 when they may be now. */
    public long cooldownRemaining(Kind kind, String entityId, long version, String fid) {
        KeyAsk ask = get(kind, entityId, version);
        if (ask == null || fid == null) return 0;
        Long last = ask.getAskedAt().get(fid);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return elapsed >= COOLDOWN_MS ? 0 : COOLDOWN_MS - elapsed;
    }

    /**
     * Record that this question went to this member under this request id.
     *
     * <p>Called on <b>every</b> ask path, automatic and manual alike, because
     * {@link #isSolicited} reads what this writes.
     */
    public void recordAsk(Kind kind, String entityId, long version, String fid, String requestId) {
        if (db == null || entityId == null || fid == null) return;
        long now = System.currentTimeMillis();
        String id = askId(kind, entityId, version);

        KeyAsk ask = db.get(id);
        if (ask == null || isExpired(ask, now)) {
            ask = new KeyAsk();
            ask.setId(id);
            ask.setKind(kind);
            ask.setEntityId(entityId);
            ask.setVersion(version);
            ask.setFirstAskedAt(now);
        }
        if (!ask.getAskedFids().contains(fid)) ask.getAskedFids().add(fid);
        ask.getAskedAt().put(fid, now);
        if (requestId != null && !ask.getRequestIds().contains(requestId)) {
            ask.getRequestIds().add(requestId);
        }
        ask.setAttempts(ask.getAttempts() + 1);
        db.put(id, ask);
    }

    /**
     * Whether a delivered key answers something this device asked -- FIMP4V3 §4.2.
     *
     * <p>Both halves matter. The request id must be one we sent, <b>and</b> the version
     * delivered must be one that ask named, so an answer to "give me v1" cannot smuggle in a
     * key at some other version. An ask for {@link #CURRENT} names no version and so accepts
     * whichever one comes back, which is the whole point of asking that way.
     *
     * <p>Solicitation is never inferred from the mere presence of a request id.
     */
    public boolean isSolicited(String requestId, String entityId, long version) {
        if (db == null || requestId == null || entityId == null) return false;
        long now = System.currentTimeMillis();
        Map<String, KeyAsk> all = db.getAll();
        if (all == null) return false;
        for (KeyAsk ask : all.values()) {
            if (ask == null || isExpired(ask, now)) continue;
            if (!entityId.equals(ask.getEntityId())) continue;
            if (!ask.getRequestIds().contains(requestId)) continue;
            long asked = ask.getVersion() != null ? ask.getVersion() : CURRENT;
            if (asked == CURRENT || asked == version) return true;
        }
        return false;
    }

    /**
     * Clear the asks a stored key has answered: the one naming that version, and any
     * "whatever you hold now" ask for the same entity.
     */
    public void resolve(String entityId, long version) {
        if (db == null || entityId == null) return;
        List<String> done = new ArrayList<>();
        Map<String, KeyAsk> all = db.getAll();
        if (all == null) return;
        for (Map.Entry<String, KeyAsk> row : all.entrySet()) {
            KeyAsk ask = row.getValue();
            if (ask == null || !entityId.equals(ask.getEntityId())) continue;
            long asked = ask.getVersion() != null ? ask.getVersion() : CURRENT;
            if (asked == CURRENT || asked == version) done.add(row.getKey());
        }
        if (!done.isEmpty()) {
            db.removeList(done);
            TimberLogger.d("KeyAskStore", "Resolved %d ask(s) for %s v%d", done.size(), entityId, version);
        }
    }

    /** The user has given up on one question. */
    public void giveUp(Kind kind, String entityId, long version) {
        if (db == null || entityId == null) return;
        db.remove(askId(kind, entityId, version));
    }

    /** The user has given up on every outstanding question about an entity. */
    public void giveUpAll(String entityId) {
        if (db == null || entityId == null) return;
        List<String> ids = new ArrayList<>();
        for (KeyAsk ask : outstanding(entityId)) {
            if (ask.getId() != null) ids.add(ask.getId());
        }
        if (!ids.isEmpty()) db.removeList(ids);
    }

    /** Every unexpired ask about an entity, oldest first. */
    public List<KeyAsk> outstanding(String entityId) {
        List<KeyAsk> result = new ArrayList<>();
        if (db == null) return result;
        Map<String, KeyAsk> all = db.getAll();
        if (all == null) return result;
        long now = System.currentTimeMillis();
        for (KeyAsk ask : all.values()) {
            if (ask == null || isExpired(ask, now)) continue;
            if (entityId != null && !entityId.equals(ask.getEntityId())) continue;
            result.add(ask);
        }
        result.sort((a, b) -> Long.compare(
                a.getFirstAskedAt() != null ? a.getFirstAskedAt() : 0L,
                b.getFirstAskedAt() != null ? b.getFirstAskedAt() : 0L));
        return result;
    }

    public void pruneExpired() {
        if (db == null) return;
        Map<String, KeyAsk> all = db.getAll();
        if (all == null) return;
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, KeyAsk> row : all.entrySet()) {
            if (row.getValue() == null || isExpired(row.getValue(), now)) expired.add(row.getKey());
        }
        if (!expired.isEmpty()) db.removeList(expired);
    }

    private static boolean isExpired(KeyAsk ask, long now) {
        Long first = ask.getFirstAskedAt();
        return first == null || now - first > ASK_LIFETIME_MS;
    }

    /** One outstanding question, and everyone it has been put to. */
    public static class KeyAsk extends FcEntity {
        private Kind kind;
        private String entityId;
        private Long version;                  // 0 means "whatever you hold now"
        private List<String> askedFids;        // who was asked, in the order they were asked
        private Map<String, Long> askedAt;     // fid -> when we last put this to them
        private List<String> requestIds;       // an answer must carry one of these
        private Long firstAskedAt;             // how long recovery has been stalled
        private Integer attempts;              // and what it has cost

        public Kind getKind() { return kind != null ? kind : Kind.SYMKEY; }
        public void setKind(Kind kind) { this.kind = kind; }

        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }

        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }

        public List<String> getAskedFids() {
            if (askedFids == null) askedFids = new ArrayList<>();
            return askedFids;
        }
        public void setAskedFids(List<String> askedFids) { this.askedFids = askedFids; }

        public Map<String, Long> getAskedAt() {
            if (askedAt == null) askedAt = new LinkedHashMap<>();
            return askedAt;
        }
        public void setAskedAt(Map<String, Long> askedAt) { this.askedAt = askedAt; }

        public List<String> getRequestIds() {
            if (requestIds == null) requestIds = new ArrayList<>();
            return requestIds;
        }
        public void setRequestIds(List<String> requestIds) { this.requestIds = requestIds; }

        public Long getFirstAskedAt() { return firstAskedAt; }
        public void setFirstAskedAt(Long firstAskedAt) { this.firstAskedAt = firstAskedAt; }

        public int getAttempts() { return attempts != null ? attempts : 0; }
        public void setAttempts(Integer attempts) { this.attempts = attempts; }

        /** When this question was last put to anybody, or 0. */
        public long lastAskedAt() {
            long last = 0;
            for (Long at : getAskedAt().values()) {
                if (at != null && at > last) last = at;
            }
            return last;
        }

        public String toJson() { return JsonUtils.toJson(this); }
        public static KeyAsk fromJson(String json) { return JsonUtils.fromJson(json, KeyAsk.class); }
    }
}
