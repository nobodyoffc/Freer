package com.fc.freer.call;

import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.MeetingSignal;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this identity knows of the meetings in its Rooms and Teams (VOICE_SPEC
 * §3.3): who started each, where it runs, the key sets its host has posted,
 * and whether it has ended. The chat shows one card per meeting from it, and
 * joining reads the keys from it.
 * <p>
 * A host that rekeys posts {@code MEETING_START} again with a higher
 * {@code keyEpoch}; each is kept, newest first, so a late joiner can still
 * prove the key. Updates are taken from any member, since the host role can
 * pass on; one that does not work only costs the joiner a failed attempt.
 * <p>
 * No Android in here: it persists through {@link Store}. Every method is
 * synchronized.
 */
public final class MeetingBoard {

    /** Meetings remembered, newest first; older ones are forgotten. */
    static final int LIMIT = 200;

    public interface Store {
        String load();

        void save(String json);
    }

    /** One key set of a meeting (§4.2): what a member derives the call secret from. */
    public record Keys(String nonce, long symkeyVersion, String authPub, long keyEpoch) {}

    public static final class Meeting {
        public String meetingId;
        public String entityId;
        /** TEAM or ROOM. */
        public String entityType;
        public String hostFid;
        public CallSignal.Relay relay;
        public String title;
        public long started;
        /** Newest key epoch first. */
        public List<Keys> keys = new ArrayList<>();
        public boolean ended;
        public long duration;

        public Keys newestKeys() {
            return keys.isEmpty() ? null : keys.get(0);
        }
    }

    public enum Result {
        /** A meeting not seen before: show its card. */
        NEW,
        /** Known, and this changed it: redraw the card. */
        UPDATED,
        /** Nothing new. */
        UNCHANGED,
        /** A MEETING_END from someone other than its host: ask the relay before believing it. */
        CONFIRM
    }

    private static final Gson GSON = new Gson();

    private final Store store;
    private final Map<String, Meeting> meetings = new LinkedHashMap<>();

    public MeetingBoard(Store store) {
        this.store = store;
        try {
            String json = store.load();
            Map<String, Meeting> saved = json == null ? null
                    : GSON.fromJson(json, new TypeToken<LinkedHashMap<String, Meeting>>() {}.getType());
            if (saved != null) meetings.putAll(saved);
        } catch (RuntimeException ignored) {
            // an unreadable board is an empty one: the chat still has the messages
        }
    }

    public synchronized Meeting get(String meetingId) {
        return meetingId == null ? null : meetings.get(meetingId);
    }

    public synchronized boolean isEnded(String meetingId) {
        Meeting m = get(meetingId);
        return m != null && m.ended;
    }

    /** Open meetings of one Room or Team, newest first. */
    public synchronized List<Meeting> open(String entityId) {
        List<Meeting> out = new ArrayList<>();
        for (Meeting m : meetings.values()) if (!m.ended && m.entityId.equals(entityId)) out.add(m);
        out.sort(Comparator.comparingLong((Meeting m) -> m.started).reversed());
        return out;
    }

    /**
     * A {@code MEETING_START} from {@code senderFid}, a member of the entity:
     * the caller has checked that, as FIMP requires (§3.3).
     */
    public synchronized Result onStart(String entityId, String entityType, String senderFid, MeetingSignal s) {
        if (s == null || s.op != MeetingSignal.Op.MEETING_START) return Result.UNCHANGED;
        Keys k = new Keys(s.nonce, s.symkeyVersion, s.authPub, s.keyEpochOrZero());
        Meeting m = meetings.get(s.meetingId);
        if (m == null) {
            m = new Meeting();
            m.meetingId = s.meetingId;
            m.entityId = entityId;
            m.entityType = entityType;
            m.hostFid = senderFid;
            m.relay = s.relay;
            m.title = s.title;
            m.started = s.started;
            m.keys.add(k);
            meetings.put(s.meetingId, m);
            trim();
            save();
            return Result.NEW;
        }
        // The same meeting id in another entity is not this meeting.
        if (!m.entityId.equals(entityId)) return Result.UNCHANGED;
        for (Keys have : m.keys) if (have.authPub().equals(k.authPub())) return Result.UNCHANGED;
        m.keys.add(k);
        m.keys.sort(Comparator.comparingLong(Keys::keyEpoch).reversed());
        save();
        return Result.UPDATED;
    }

    /**
     * A {@code MEETING_END}: from the host who started it, the meeting is over;
     * from anyone else, only once the relay confirms it (§3.3).
     */
    public synchronized Result onEnd(String entityId, String senderFid, MeetingSignal s) {
        Meeting m = s == null ? null : meetings.get(s.meetingId);
        if (m == null || m.ended || !m.entityId.equals(entityId)) return Result.UNCHANGED;
        if (!m.hostFid.equals(senderFid)) return Result.CONFIRM;
        return markEnded(s.meetingId, s.duration == null ? 0 : s.duration) ? Result.UPDATED : Result.UNCHANGED;
    }

    /** The meeting is over: its host said so, or the relay confirmed it. @return false if already known */
    public synchronized boolean markEnded(String meetingId, long durationMs) {
        Meeting m = meetings.get(meetingId);
        if (m == null || m.ended) return false;
        m.ended = true;
        m.duration = Math.max(0, durationMs);
        save();
        return true;
    }

    private void trim() {
        if (meetings.size() <= LIMIT) return;
        List<Meeting> all = new ArrayList<>(meetings.values());
        all.sort(Comparator.comparingLong(m -> m.started));
        for (int i = 0; i < all.size() - LIMIT; i++) meetings.remove(all.get(i).meetingId);
    }

    private void save() {
        try {
            store.save(GSON.toJson(meetings));
        } catch (RuntimeException ignored) {
            // kept in memory; the chat messages remain the record
        }
    }
}
