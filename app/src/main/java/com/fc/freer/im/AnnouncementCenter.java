package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.Announcements;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which announcements each team and room holds, and which this identity has confirmed — the
 * bookkeeping around {@link Announcements}, FreerForMac's {@code AnnouncementsStore} the same
 * way.
 *
 * <p>An index, not a copy: the words stay with the messages, and an entry whose message is gone,
 * withdrawn or no longer an announcement is passed over when read. It exists because an
 * unconfirmed announcement can be weeks and a thousand messages old, and finding it by reading
 * every thread back to the start each time a badge is drawn would cost exactly that.
 *
 * <p>Kept in Hawk as JSON, one key per identity and conversation.
 */
public final class AnnouncementCenter {

    private AnnouncementCenter() {}

    private static final String KEY_PREFIX = "im_announcements_";
    private static final Gson GSON = new Gson();
    private static final Type LIST = new TypeToken<List<Entry>>() {}.getType();

    /** One announcement filed in a conversation. */
    public static final class Entry {
        public String id;
        public String senderId;
        public Long timestamp;
        /** When this identity confirmed it. Our own are confirmed as sent. */
        public Long confirmedAt;

        public boolean isConfirmed() { return confirmedAt != null; }
    }

    /** An announcement as a page shows it: the message, where it was said, whether confirmed. */
    public static final class Item {
        public final Conversation conversation;
        public final ImMessage message;
        public final Entry entry;

        Item(Conversation conversation, ImMessage message, Entry entry) {
            this.conversation = conversation;
            this.message = message;
            this.entry = entry;
        }

        public String body() {
            String b = Announcements.body(message.getContent());
            return b != null ? b : "";
        }

        public String conversationId() { return AnnouncementCenter.conversationId(conversation.getType(), conversation.getTargetId()); }

        /** The group's name, as the thread list shows it. */
        public String groupName() {
            String name = conversation.getDisplayName();
            if (name != null && !name.isEmpty()) return name;
            String id = conversation.getTargetId();
            return id != null && id.length() > 12 ? id.substring(0, 6) + "…" + id.substring(id.length() - 6) : id;
        }
    }

    public static String conversationId(ImType type, String targetId) {
        return type.name() + "_" + targetId;
    }

    private static String key(String liveFid, String conversationId) {
        return KEY_PREFIX + liveFid + "_" + conversationId;
    }

    private static List<Entry> load(String liveFid, String conversationId) {
        String json = Hawk.get(key(liveFid, conversationId), null);
        if (json == null) return new ArrayList<>();
        try {
            List<Entry> list = GSON.fromJson(json, LIST);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void save(String liveFid, String conversationId, List<Entry> entries) {
        Hawk.put(key(liveFid, conversationId), GSON.toJson(entries, LIST));
    }

    /**
     * File {@code message} if it is an announcement. An entry already here keeps its
     * confirmation; {@code confirmed} only ever adds one.
     */
    public static synchronized void note(String liveFid, ImMessage message, boolean confirmed) {
        if (liveFid == null || message == null || message.getId() == null) return;
        if (!Announcements.isAnnouncement(message)) return;
        String convId = conversationId(message.getType(), groupId(message));
        List<Entry> entries = load(liveFid, convId);
        Entry found = null;
        for (Entry e : entries) if (message.getId().equals(e.id)) { found = e; break; }
        if (found == null) {
            found = new Entry();
            found.id = message.getId();
            found.senderId = message.getSenderId();
            found.timestamp = message.getTimestamp();
            entries.add(found);
        } else if (!confirmed || found.confirmedAt != null) {
            return;
        }
        if (confirmed && found.confirmedAt == null) found.confirmedAt = System.currentTimeMillis();
        Collections.sort(entries, (a, b) -> Long.compare(
                a.timestamp != null ? a.timestamp : 0, b.timestamp != null ? b.timestamp : 0));
        save(liveFid, convId, entries);
    }

    /** A team's or room's message is addressed to the group: its target is the group. */
    private static String groupId(ImMessage message) {
        return message.getTargetId();
    }

    /** Whether {@code fid} may announce in the group {@code type}/{@code targetId}. */
    public static boolean mayAnnounce(ImManager im, String fid, ImType type, String targetId) {
        if (im == null || fid == null || targetId == null) return false;
        if (type == ImType.TEAM) return Announcements.mayPost(fid, im.getTeam(targetId));
        if (type == ImType.ROOM) return Announcements.mayPost(fid, im.getRoom(targetId));
        return false;
    }

    /** Who may announce in a group — the transcript draws their announcements as one. */
    public static Set<String> announcers(ImManager im, ImType type, String targetId) {
        Set<String> out = new HashSet<>();
        if (im == null || targetId == null) return out;
        if (type == ImType.TEAM) {
            com.fc.fc_ajdk.data.feipData.Team team = im.getTeam(targetId);
            if (team != null) {
                if (team.getOwner() != null) out.add(team.getOwner());
                if (team.getManagers() != null) out.addAll(team.getManagers());
            }
        } else if (type == ImType.ROOM) {
            com.fc.fc_ajdk.data.fcData.Room room = im.getRoom(targetId);
            if (room != null && room.getOwner() != null) out.add(room.getOwner());
        }
        return out;
    }

    /**
     * Every announcement in {@code conversation} from somebody who may post one, newest first —
     * its history, confirmed or not. Whether the sender may is asked of the group as it stands
     * now, so a manager's announcements go when they are dismissed.
     */
    public static List<Item> history(ImManager im, String liveFid, Conversation conversation) {
        List<Item> out = new ArrayList<>();
        if (im == null || liveFid == null || conversation == null) return out;
        ImType type = conversation.getType();
        if (type != ImType.TEAM && type != ImType.ROOM) return out;
        List<Entry> entries = load(liveFid, conversationId(type, conversation.getTargetId()));
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!mayAnnounce(im, e.senderId, type, conversation.getTargetId())) continue;
            ImMessage m = im.getMessageById(e.id);
            if (m == null || !Announcements.isAnnouncement(m)) continue;
            out.add(new Item(conversation, m, e));
        }
        return out;
    }

    /** Announcements not yet confirmed, oldest first; only those of {@code onlyType} if given. */
    public static List<Item> unconfirmed(ImManager im, String liveFid, ImType onlyType) {
        List<Item> out = new ArrayList<>();
        if (im == null || liveFid == null) return out;
        for (Conversation conv : im.getConversations()) {
            ImType type = conv.getType();
            if (type != ImType.TEAM && type != ImType.ROOM) continue;
            if (onlyType != null && type != onlyType) continue;
            if (!Hawk.contains(key(liveFid, conversationId(type, conv.getTargetId())))) continue;
            for (Item item : history(im, liveFid, conv)) {
                if (!item.entry.isConfirmed()) out.add(item);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(ts(a), ts(b)));
        return out;
    }

    /** {@link #unconfirmed}, grouped by conversation, oldest group first. */
    public static List<List<Item>> unconfirmedByGroup(ImManager im, String liveFid, ImType onlyType) {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item item : unconfirmed(im, liveFid, onlyType)) {
            List<Item> list = groups.get(item.conversationId());
            if (list == null) {
                list = new ArrayList<>();
                groups.put(item.conversationId(), list);
            }
            list.add(item);
        }
        return new ArrayList<>(groups.values());
    }

    /** The member has read it and says so: it leaves the list, and stays in the history. */
    public static synchronized void confirm(String liveFid, Item item) {
        if (liveFid == null || item == null) return;
        String convId = item.conversationId();
        List<Entry> entries = load(liveFid, convId);
        for (Entry e : entries) {
            if (item.entry.id.equals(e.id) && e.confirmedAt == null) {
                e.confirmedAt = System.currentTimeMillis();
                item.entry.confirmedAt = e.confirmedAt;
                save(liveFid, convId, entries);
                return;
            }
        }
    }

    /**
     * File any announcement the index has not seen — one from an imported history, or from
     * before this device kept the index. Reads the whole thread, so it is for opening a
     * history, not for a badge. What it finds is filed as confirmed when it is history or our
     * own, and as waiting when it is still unread.
     */
    public static void indexConversation(ImManager im, String liveFid, ImType type, String targetId) {
        if (im == null || liveFid == null || (type != ImType.TEAM && type != ImType.ROOM)) return;
        for (ImMessage m : im.getMessages(type, targetId, Integer.MAX_VALUE, null, null)) {
            if (!Announcements.isAnnouncement(m)) continue;
            boolean settled = m.isOutgoing(liveFid) || m.getStatus() == MessageStatus.IMPORTED
                    || !Boolean.TRUE.equals(m.getUnread());
            note(liveFid, m, settled);
        }
    }

    private static long ts(Item item) {
        Long t = item.message.getTimestamp();
        return t != null ? t : 0;
    }
}
