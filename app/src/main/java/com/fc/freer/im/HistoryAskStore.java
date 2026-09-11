package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.manager.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * History requests this identity has sent and not yet had answered, and answers received but not
 * yet imported.
 * <p>
 * Kept in the encrypted database rather than in memory: an answer needs a person on the other
 * side to approve it, so it usually arrives after this app has been closed and reopened. Each
 * entry also records who was asked and which thread the answer may fill, so an answer is accepted
 * only from that member and imported only into that thread and range.
 */
public class HistoryAskStore {

    /** How long an unanswered request is remembered. Longer than a DOCK holds anything. */
    static final long ASK_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * Automatic download attempts before an answer waits for the user to press Retry, rather
     * than asking a DISK that has said no on every pass.
     */
    static final int AUTOMATIC_ATTEMPTS = 3;

    private final LocalDB<HistoryAsk> db;
    private final LocalDB<ReceivedShare> receivedDb;

    public HistoryAskStore(String liveFid) {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, HistoryAsk.class, HistoryAsk.class);
        this.receivedDb = dbManager.getEntityDatabase(liveFid, ReceivedShare.class, ReceivedShare.class);
    }

    // ---- answers received, waiting to be fetched and imported ----

    public void recordReceived(ReceivedShare share) {
        if (receivedDb == null || share == null || share.getId() == null) return;
        receivedDb.put(share.getId(), share);
    }

    public ReceivedShare getReceived(String nonce) {
        if (receivedDb == null || nonce == null) return null;
        return receivedDb.get(nonce);
    }

    /** Every answer still waiting, oldest first — the order they are worked through. */
    public List<ReceivedShare> allReceived() {
        List<ReceivedShare> result = new ArrayList<>();
        if (receivedDb == null) return result;
        Map<String, ReceivedShare> all = receivedDb.getAll();
        if (all == null) return result;
        for (ReceivedShare share : all.values()) {
            if (share != null && share.getId() != null) result.add(share);
        }
        result.sort((a, b) -> Long.compare(
                a.getReceivedAt() != null ? a.getReceivedAt() : 0L,
                b.getReceivedAt() != null ? b.getReceivedAt() : 0L));
        return result;
    }

    public void removeReceived(String nonce) {
        if (receivedDb == null || nonce == null) return;
        receivedDb.remove(nonce);
    }

    public void record(HistoryAsk ask) {
        if (db == null || ask == null || ask.getId() == null) return;
        db.put(ask.getId(), ask);
    }

    /** The unexpired request with this nonce, or null. An expired one is forgotten on the way. */
    public HistoryAsk get(String nonce) {
        if (db == null || nonce == null) return null;
        HistoryAsk ask = db.get(nonce);
        if (ask != null && isExpired(ask, System.currentTimeMillis())) {
            db.remove(nonce);
            return null;
        }
        return ask;
    }

    public void remove(String nonce) {
        if (db == null || nonce == null) return;
        db.remove(nonce);
    }

    public void pruneExpired() {
        if (db == null) return;
        Map<String, HistoryAsk> all = db.getAll();
        if (all == null) return;
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, HistoryAsk> e : all.entrySet()) {
            if (e.getValue() == null || isExpired(e.getValue(), now)) expired.add(e.getKey());
        }
        if (!expired.isEmpty()) db.removeList(expired);
    }

    private static boolean isExpired(HistoryAsk ask, long now) {
        return ask.getSentAt() == null || now - ask.getSentAt() > ASK_LIFETIME_MS;
    }

    /** One request sent. The id is the nonce the answer must echo as its requestId. */
    public static class HistoryAsk extends FcEntity {
        private String askedFid;   // who was asked; an answer from anybody else is not an answer
        private ImType imType;     // the thread on this device the answer fills
        private String targetId;
        private Long since;        // inclusive, ms
        private Long before;       // exclusive, ms
        private Long sentAt;

        public String getAskedFid() { return askedFid; }
        public void setAskedFid(String askedFid) { this.askedFid = askedFid; }

        public ImType getImType() { return imType; }
        public void setImType(ImType imType) { this.imType = imType; }

        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }

        public Long getSince() { return since; }
        public void setSince(Long since) { this.since = since; }

        public Long getBefore() { return before; }
        public void setBefore(Long before) { this.before = before; }

        public Long getSentAt() { return sentAt; }
        public void setSentAt(Long sentAt) { this.sentAt = sentAt; }

        public String toJson() { return JsonUtils.toJson(this); }
        public static HistoryAsk fromJson(String json) { return JsonUtils.fromJson(json, HistoryAsk.class); }
    }

    /**
     * An answer that matched one of our requests, kept until its file is fetched and imported.
     * Receiving the answer and downloading the file are split by this row so a download that
     * fails — DISK unreachable, app killed mid-way — can be tried again instead of lost. The id
     * is the request nonce. {@code hatJson} carries the file key; the store is encrypted.
     */
    public static class ReceivedShare extends FcEntity {
        private String senderFid;
        private ImType imType;      // the thread on this device the import fills
        private String targetId;
        private Long since;         // inclusive, ms
        private Long before;        // exclusive, ms
        private String hatJson;
        private Long receivedAt;
        private Integer attempts;
        private Long lastAttemptAt;
        private String lastError;

        public static ReceivedShare fromAsk(HistoryAsk ask, String senderFid, String hatJson, long now) {
            ReceivedShare share = new ReceivedShare();
            share.setId(ask.getId());
            share.senderFid = senderFid;
            share.imType = ask.getImType();
            share.targetId = ask.getTargetId();
            share.since = ask.getSince();
            share.before = ask.getBefore();
            share.hatJson = hatJson;
            share.receivedAt = now;
            share.attempts = 0;
            return share;
        }

        /** Out of automatic attempts: only the user pressing Retry tries again. */
        public boolean waitsForRetry() { return getAttempts() >= AUTOMATIC_ATTEMPTS; }

        public String getSenderFid() { return senderFid; }
        public void setSenderFid(String senderFid) { this.senderFid = senderFid; }

        public ImType getImType() { return imType; }
        public void setImType(ImType imType) { this.imType = imType; }

        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }

        public Long getSince() { return since; }
        public void setSince(Long since) { this.since = since; }

        public Long getBefore() { return before; }
        public void setBefore(Long before) { this.before = before; }

        public String getHatJson() { return hatJson; }
        public void setHatJson(String hatJson) { this.hatJson = hatJson; }

        public Long getReceivedAt() { return receivedAt; }
        public void setReceivedAt(Long receivedAt) { this.receivedAt = receivedAt; }

        public int getAttempts() { return attempts != null ? attempts : 0; }
        public void setAttempts(Integer attempts) { this.attempts = attempts; }

        public Long getLastAttemptAt() { return lastAttemptAt; }
        public void setLastAttemptAt(Long lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }

        public String toJson() { return JsonUtils.toJson(this); }
        public static ReceivedShare fromJson(String json) { return JsonUtils.fromJson(json, ReceivedShare.class); }
    }
}
