package com.fc.fc_ajdk.data.fcData;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mentions. The cases are shared with FreerForMac MentionsTests: an {@code @} must name the
 * same people on every device.
 */
public class MentionsTest {

    static final String ME = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";

    private static List<String> names(String text) {
        List<String> out = new ArrayList<>();
        for (Mentions.Token t : Mentions.tokens(text)) out.add(t.name);
        return out;
    }

    @Test
    void tokensStopAtSpacesPunctuationAndTheNextAt() {
        assertEquals(Arrays.asList("Alice", "Bob_1234", "Carol"), names("@Alice, hi @Bob_1234。 see @Carol@Dave"));
        // Only after a space or at the start: "(@Eve" is not a mention.
        assertEquals(Arrays.asList("Frank"), names("(@Eve) and @Frank!"));
        assertEquals(Arrays.asList("Grace", "Heidi"), names("@Grace，你好\n@Heidi"));
    }

    @Test
    void anAtInsideAWordIsNotAMention() {
        assertEquals(Arrays.asList(), names("mail a@b.com"));
        assertEquals(Arrays.asList(), names("@"));
        assertEquals(Arrays.asList(), names("@ alone"));
    }

    @Test
    void mentionedByFidOrCid() {
        assertTrue(Mentions.mentions("hey @" + ME + "!", ME, null));
        assertTrue(Mentions.mentions("hey @Alice", ME, "Alice"));
        assertFalse(Mentions.mentions("hey @Alicia", ME, "Alice"));
        assertFalse(Mentions.mentions("hey Alice", ME, "Alice"));
        assertFalse(Mentions.mentions("hey @", ME, ""));
        assertFalse(Mentions.mentions((String) null, ME, "Alice"));
    }

    @Test
    void onlyGroupTextsCount() {
        ImMessage text = ImMessage.createText(ImType.ROOM, "Fother", "room1", "@Alice look");
        assertTrue(Mentions.mentions(text, ME, "Alice"));
        ImMessage p2p = ImMessage.createText(ImType.P2P, "Fother", ME, "@Alice look");
        assertFalse(Mentions.mentions(p2p, ME, "Alice"));
    }

    @Test
    void pendingQueryIsTheNameBeingTypedAtTheEnd() {
        assertEquals("Al", Mentions.pendingQuery("hi @Al"));
        assertEquals("", Mentions.pendingQuery("@"));
        assertEquals("", Mentions.pendingQuery("hi @"));
        assertNull(Mentions.pendingQuery("a@b"));
        assertNull(Mentions.pendingQuery("hi @Al x"));
        assertNull(Mentions.pendingQuery("hi"));
    }

    @Test
    void completeReplacesWhatIsTypedOrAppends() {
        assertEquals("hi @Alice ", Mentions.complete("hi @Al", "Alice"));
        assertEquals("hi @Alice ", Mentions.complete("hi @", "Alice"));
        assertEquals("hi @Bob ", Mentions.complete("hi", "Bob"));
        assertEquals("hi @Bob ", Mentions.complete("hi ", "Bob"));
        assertEquals("@Bob ", Mentions.complete("", "Bob"));
    }
}
