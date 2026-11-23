package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.Mentions;
import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.freer.manager.CidFidManager;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who has {@code @}ed the live identity where it has not yet looked, for the Todo page, the
 * group list's banner and the thread list's chip. The rules of a mention are
 * {@link Mentions}; this is the bookkeeping around them. FreerForMac's {@code unreadMentions}
 * and {@code SeenSelfMentions}, the same way.
 */
public final class MentionCenter {

    private MentionCenter() {}

    /** How far back a thread is looked through for one of our own. */
    public static final int WINDOW = 50;
    private static final int KEPT = 1000;
    private static final String SEEN_KEY_PREFIX = "im_seen_self_mentions_";

    /** A group thread with messages naming us that we have not seen. */
    public static final class UnreadMention {
        public final Conversation conversation;
        public final int count;

        UnreadMention(Conversation conversation, int count) {
            this.conversation = conversation;
            this.count = count;
        }

        public String conversationId() {
            return conversation.getType().name() + "_" + conversation.getTargetId();
        }

        /** The group's name, as the thread list shows it. */
        public String groupName() {
            String name = conversation.getDisplayName();
            if (name != null && !name.isEmpty()) return name;
            String id = conversation.getTargetId();
            return id != null && id.length() > 12 ? id.substring(0, 6) + "…" + id.substring(id.length() - 6) : id;
        }
    }

    /** The CID this device knows for {@code fid}, or null. */
    public static String cidOf(String fid) {
        CidFidManager cids = CidFidManager.getInstance();
        return cids != null && fid != null ? cids.getCidByFid(fid) : null;
    }

    /** How a group names the live identity besides its FID. */
    public static String myCid(String liveFid) {
        return cidOf(liveFid);
    }

    /**
     * Every group thread with an unseen message naming us, most recent first; only those of
     * {@code onlyType} when it is not null.
     *
     * <p>Unseen means unread for another member's message. Our own — an {@code @} of ourselves,
     * which is how a message is flagged for our other devices — is filed as read wherever it
     * arrives, so for those the seen list stands in for the flag.
     */
    public static List<UnreadMention> unread(ImManager im, String liveFid, ImType onlyType) {
        List<UnreadMention> found = new ArrayList<>();
        if (im == null || liveFid == null) return found;
        String cid = myCid(liveFid);
        Set<String> seen = seen(liveFid);
        for (Conversation conv : im.getConversations()) {
            ImType type = conv.getType();
            if (type == null || type == ImType.P2P) continue;
            if (onlyType != null && type != onlyType) continue;
            int unread = conv.getUnreadCount() != null ? conv.getUnreadCount() : 0;
            // The unread messages, and never fewer than the last few: our own are not counted
            // unread, so the count cannot say whether one of them is new.
            int limit = Math.max(Math.min(unread, 200), WINDOW);
            int count = 0;
            for (ImMessage m : im.getMessages(type, conv.getTargetId(), limit, null, null)) {
                if (!Mentions.mentions(m, liveFid, cid)) continue;
                if (m.isOutgoing(liveFid)) {
                    if (m.getId() != null && !seen.contains(m.getId())) count++;
                } else if (Boolean.TRUE.equals(m.getUnread())) {
                    count++;
                }
            }
            if (count > 0) found.add(new UnreadMention(conv, count));
        }
        Collections.sort(found, (a, b) -> {
            long at = a.conversation.getLastMessageTime() != null ? a.conversation.getLastMessageTime() : 0;
            long bt = b.conversation.getLastMessageTime() != null ? b.conversation.getLastMessageTime() : 0;
            return Long.compare(bt, at);
        });
        return found;
    }

    /** The conversation ids of {@link #unread}, for the thread list's chip. */
    public static Set<String> unreadConversationIds(ImManager im, String liveFid, ImType onlyType) {
        Set<String> ids = new HashSet<>();
        for (UnreadMention m : unread(im, liveFid, onlyType)) ids.add(m.conversationId());
        return ids;
    }

    private static Set<String> seen(String liveFid) {
        List<String> list = Hawk.get(SEEN_KEY_PREFIX + liveFid, new ArrayList<>());
        return new HashSet<>(list);
    }

    /** Of {@code messages}, the ones we wrote that name us have been seen: their thread is open. */
    public static void markSeen(List<ImMessage> messages, String liveFid) {
        if (messages == null || liveFid == null) return;
        String cid = myCid(liveFid);
        List<String> list = new ArrayList<>(Hawk.get(SEEN_KEY_PREFIX + liveFid, new ArrayList<>()));
        Set<String> known = new HashSet<>(list);
        boolean added = false;
        for (ImMessage m : messages) {
            if (m.getId() == null || !m.isOutgoing(liveFid) || !Mentions.mentions(m, liveFid, cid)) continue;
            if (known.add(m.getId())) {
                list.add(m.getId());
                added = true;
            }
        }
        if (!added) return;
        if (list.size() > KEPT) list = new ArrayList<>(list.subList(list.size() - KEPT, list.size()));
        Hawk.put(SEEN_KEY_PREFIX + liveFid, list);
    }

    /**
     * Whom an {@code @} can name in a group, in the order the list offers them: ourselves (an
     * {@code @} of yourself flags a message for your other devices), then whoever spoke last,
     * then the members as this device holds them — for a square, possibly thousands.
     */
    public static List<String> candidates(ImManager im, ImType type, String targetId, String liveFid,
                                          List<ImMessage> onScreen) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (liveFid != null) out.add(liveFid);
        if (onScreen != null) {
            for (int i = onScreen.size() - 1; i >= 0; i--) {
                String s = onScreen.get(i).getSenderId();
                if (s != null && !s.isEmpty()) out.add(s);
            }
        }
        if (im != null) {
            if (type == ImType.TEAM) {
                Team team = im.getTeam(targetId);
                if (team != null) {
                    if (team.getOwner() != null) out.add(team.getOwner());
                    if (team.getMembers() != null) out.addAll(team.getMembers());
                }
            } else if (type == ImType.ROOM) {
                Room room = im.getRoom(targetId);
                if (room != null) {
                    if (room.getOwner() != null) out.add(room.getOwner());
                    if (room.getMembers() != null) out.addAll(room.getMembers());
                }
            } else if (type == ImType.SQUARE) {
                Square square = im.getSquare(targetId);
                if (square != null && square.getMembers() != null) out.addAll(square.getMembers());
            }
        }
        out.remove("");
        return new ArrayList<>(out);
    }
}
