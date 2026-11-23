package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.data.feipData.Team;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Announcements. The cases are shared with FreerForMac AnnouncementsTests: an announcement must
 * be one on every device.
 */
public class AnnouncementsTest {

    static final String OWNER = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
    static final String MANAGER = "FBejsS6cJaBrAwPcMjFJYH7iy6Krh2fkRD";
    static final String MEMBER = "F86zoAvNaQxEuYyvQssV5WxEzapNaiDtTW";

    @Test
    void thePrefixMakesAnAnnouncement() {
        assertEquals("[ANNOUNCEMENT]Meeting at 9", Announcements.text("  Meeting at 9  "));
        assertEquals("Meeting at 9", Announcements.body("[ANNOUNCEMENT]Meeting at 9"));
        assertEquals("会议改到周五", Announcements.body("[ANNOUNCEMENT]\n 会议改到周五 \n"));
    }

    @Test
    void onlyAtTheStartAndNeverEmpty() {
        assertNull(Announcements.body("[ANNOUNCEMENT]"));
        assertNull(Announcements.body("[ANNOUNCEMENT]   \n"));
        assertNull(Announcements.body(" [ANNOUNCEMENT]late"));
        assertNull(Announcements.body("[announcement]lower case"));
        assertNull(Announcements.body("plain text"));
        assertNull(Announcements.body(null));
    }

    @Test
    void onlyTeamAndRoomTexts() {
        String text = Announcements.text("hello");
        assertTrue(Announcements.isAnnouncement(ImMessage.createText(ImType.TEAM, OWNER, "t1", text)));
        assertTrue(Announcements.isAnnouncement(ImMessage.createText(ImType.ROOM, OWNER, "r1", text)));
        assertFalse(Announcements.isAnnouncement(ImMessage.createText(ImType.SQUARE, OWNER, "s1", text)));
        assertFalse(Announcements.isAnnouncement(ImMessage.createText(ImType.P2P, OWNER, MEMBER, text)));
        ImMessage withdrawn = ImMessage.createText(ImType.TEAM, OWNER, "t1", text);
        withdrawn.setWithdrawn(true);
        assertFalse(Announcements.isAnnouncement(withdrawn));
    }

    @Test
    void aTeamsOwnerAndManagersMayPost() {
        Team team = new Team();
        team.setOwner(OWNER);
        team.setMembers(Arrays.asList(OWNER, MANAGER, MEMBER));
        team.setManagers(Collections.singletonList(MANAGER));
        assertTrue(Announcements.mayPost(OWNER, team));
        assertTrue(Announcements.mayPost(MANAGER, team));
        assertFalse(Announcements.mayPost(MEMBER, team));
        assertFalse(Announcements.mayPost(null, team));
        assertFalse(Announcements.mayPost(OWNER, (Team) null));
    }

    @Test
    void onlyARoomsOwnerMayPost() {
        Room room = new Room();
        room.setOwner(OWNER);
        room.setMembers(Arrays.asList(OWNER, MEMBER));
        assertTrue(Announcements.mayPost(OWNER, room));
        assertFalse(Announcements.mayPost(MEMBER, room));
        assertFalse(Announcements.mayPost(OWNER, (Room) null));
    }
}
