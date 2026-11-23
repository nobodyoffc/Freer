package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.data.feipData.Team;

/**
 * A team's or a room's announcement: a notice from whoever runs the group that every member is
 * asked to open and confirm.
 *
 * <p><b>Text on the wire</b>, as the team notices are: an ordinary group text whose content
 * starts with {@link #PREFIX}. It is sealed, stored and shown like any other message, and a
 * client that has never heard of announcements shows it as a line beginning
 * {@code [ANNOUNCEMENT]} — the words still arrive.
 *
 * <p><b>Who may post one is the receiver's to check.</b> Anybody in the group can type the
 * prefix. The sender of a group message is proven by its signature, so what remains is whether
 * that sender runs the group: a team's owner or one of its managers, on the chain; a room's
 * owner, the only one a room has. An announcement from anyone else is a plain message.
 *
 * <p>The rules are FreerForMac's {@code Announcements} too, case for case — see
 * {@code AnnouncementsTest} and {@code AnnouncementsTests}.
 */
public final class Announcements {

    private Announcements() {}

    public static final String PREFIX = "[ANNOUNCEMENT]";

    /** The wire text for an announcement saying {@code body}. */
    public static String text(String body) {
        return PREFIX + trim(body == null ? "" : body);
    }

    /** What an announcement says, or null when {@code content} is not one, or says nothing. */
    public static String body(String content) {
        if (content == null || !content.startsWith(PREFIX)) return null;
        String body = trim(content.substring(PREFIX.length()));
        return body.isEmpty() ? null : body;
    }

    /**
     * Whether {@code message} is shaped as an announcement: a team or room text with the prefix
     * and something after it, still standing. Says nothing about whether its sender may post one.
     */
    public static boolean isAnnouncement(ImMessage message) {
        if (message == null) return false;
        ImType type = message.getType();
        return (type == ImType.TEAM || type == ImType.ROOM)
                && message.getContentType() == ContentType.TEXT
                && !message.isWithdrawn() && !Boolean.TRUE.equals(message.getDeleted())
                && body(message.getContent()) != null;
    }

    /** Whether {@code fid} may announce in a team: its owner or a manager. */
    public static boolean mayPost(String fid, Team team) {
        if (fid == null || team == null) return false;
        if (fid.equals(team.getOwner())) return true;
        return team.getManagers() != null && team.getManagers().contains(fid);
    }

    /** Whether {@code fid} may announce in a room: its owner. A room has no managers. */
    public static boolean mayPost(String fid, Room room) {
        return fid != null && room != null && fid.equals(room.getOwner());
    }

    /** Swift's whitespacesAndNewlines trim: Unicode spaces too, which String.trim leaves. */
    private static String trim(String s) {
        int start = 0, end = s.length();
        while (start < end && isSpace(s.charAt(start))) start++;
        while (end > start && isSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
