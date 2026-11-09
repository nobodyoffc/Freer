package com.fc.fc_ajdk.data.fcData;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageAmend. The cases are shared with FreerForMac MessageAmendTests: an edit
 * or withdraw must land the same way on every device.
 */
public class MessageAmendTest {

    static final String ALICE = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
    static final String BOB = "F86zoAvNaQxEuYyvQssV5WxEzapNaiDtTW";
    static final long T0 = 1762300800000L;

    static ImMessage original() {
        ImMessage m = ImMessage.createText(ImType.P2P, ALICE, BOB, "helo");
        m.setId("00000000000000a1");
        m.setTimestamp(T0);
        m.setStatus(MessageStatus.SENT);
        return m;
    }

    static ImMessage edit(String id, String text, long at) {
        ImMessage op = MessageAmend.createEdit(original(), text, at);
        op.setId(id);
        return op;
    }

    static ImMessage withdraw(String id, long at) {
        ImMessage op = MessageAmend.createWithdraw(original(), "Withdrew a message", at);
        op.setId(id);
        return op;
    }

    @Test
    public void editReplacesTextAndKeepsTheOriginal() {
        ImMessage m = original();
        assertEquals(MessageAmend.Outcome.APPLIED, MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000)));
        assertEquals("hello", m.getContent());
        assertEquals(T0 + 60_000, m.getEditedAt());
        assertEquals("00000000000000e1", m.getEditId());
        assertEquals(1, m.getEditHistory().size());
        assertEquals("helo", m.getEditHistory().get(0).getContent());
        assertNull(m.getEditHistory().get(0).getEditId());
        assertEquals(T0, m.getEditHistory().get(0).getAt());
    }

    @Test
    public void editsArrivingOutOfOrderEndOnTheNewest() {
        ImMessage m = original();
        MessageAmend.apply(m, edit("00000000000000e2", "hello!", T0 + 120_000));
        MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000));
        assertEquals("hello!", m.getContent());
        assertEquals(T0 + 120_000, m.getEditedAt());
        assertEquals(2, m.getEditHistory().size());
        assertEquals("helo", m.getEditHistory().get(0).getContent());
        assertEquals("hello", m.getEditHistory().get(1).getContent());
    }

    @Test
    public void theSameEditTwiceIsADuplicate() {
        ImMessage m = original();
        MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000));
        assertEquals(MessageAmend.Outcome.DUPLICATE, MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000)));
        assertEquals(1, m.getEditHistory().size());
        MessageAmend.apply(m, edit("00000000000000e2", "hello!", T0 + 120_000));
        assertEquals(MessageAmend.Outcome.DUPLICATE, MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000)));
    }

    @Test
    public void withdrawDropsEveryVersionAndWinsOverLaterEdits() {
        ImMessage m = original();
        MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000));
        assertEquals(MessageAmend.Outcome.APPLIED, MessageAmend.apply(m, withdraw("00000000000000d1", T0 + 90_000)));
        assertTrue(m.isWithdrawn());
        assertEquals(T0 + 90_000, m.getWithdrawnAt());
        assertNull(m.getContent());
        assertNull(m.getEditHistory());
        // An edit signed before the withdraw but arriving after it changes nothing.
        assertEquals(MessageAmend.Outcome.DUPLICATE, MessageAmend.apply(m, edit("00000000000000e2", "again", T0 + 80_000)));
        assertNull(m.getContent());
    }

    @Test
    public void theWindowIsTenMinutesBetweenSignedTimestamps() {
        assertEquals(MessageAmend.Outcome.APPLIED,
                MessageAmend.apply(original(), edit("00000000000000e1", "x", T0 + MessageAmend.WINDOW_MS)));
        assertEquals(MessageAmend.Outcome.REJECTED,
                MessageAmend.apply(original(), edit("00000000000000e1", "x", T0 + MessageAmend.WINDOW_MS + 1)));
        assertEquals(MessageAmend.Outcome.REJECTED,
                MessageAmend.apply(original(), withdraw("00000000000000d1", T0 - 1)));
    }

    @Test
    public void onlyTheAuthorInTheSameThread() {
        ImMessage byBob = edit("00000000000000e1", "x", T0 + 1);
        byBob.setSenderId(BOB);
        byBob.setTargetId(ALICE);
        assertEquals(MessageAmend.Outcome.REJECTED, MessageAmend.apply(original(), byBob));

        ImMessage elsewhere = edit("00000000000000e1", "x", T0 + 1);
        elsewhere.setType(ImType.TEAM);
        assertEquals(MessageAmend.Outcome.REJECTED, MessageAmend.apply(original(), elsewhere));

        ImMessage wrongTarget = edit("00000000000000e1", "x", T0 + 1);
        wrongTarget.setRequestId("00000000000000ff");
        assertEquals(MessageAmend.Outcome.REJECTED, MessageAmend.apply(original(), wrongTarget));
    }

    @Test
    public void onlyTextIsEditableButAnythingCanBeWithdrawn() {
        ImMessage voice = original();
        voice.setContentType(ContentType.VOICE);
        voice.setData(new byte[]{1, 2, 3});
        assertEquals(MessageAmend.Outcome.REJECTED, MessageAmend.apply(voice, edit("00000000000000e1", "x", T0 + 1)));
        assertEquals(MessageAmend.Outcome.APPLIED, MessageAmend.apply(voice, withdraw("00000000000000d1", T0 + 1)));
        assertNull(voice.getData());
    }

    @Test
    public void anEmptyEditIsRejected() {
        assertEquals(MessageAmend.Outcome.REJECTED, MessageAmend.apply(original(), edit("00000000000000e1", "  ", T0 + 1)));
    }

    @Test
    public void versionsAreBounded() {
        ImMessage m = original();
        for (int i = 1; i < MessageAmend.MAX_VERSIONS; i++) {
            assertEquals(MessageAmend.Outcome.APPLIED,
                    MessageAmend.apply(m, edit(String.format("%016x", i), "v" + i, T0 + i)));
        }
        assertEquals(MessageAmend.Outcome.REJECTED,
                MessageAmend.apply(m, edit("00000000000000ff", "one more", T0 + 1000)));
    }

    @Test
    public void canAmendOnlyOwnSentMessagesInsideTheWindow() {
        ImMessage m = original();
        assertTrue(MessageAmend.canAmend(m, ALICE, T0 + MessageAmend.WINDOW_MS));
        assertFalse(MessageAmend.canAmend(m, ALICE, T0 + MessageAmend.WINDOW_MS + 1));
        assertFalse(MessageAmend.canAmend(m, BOB, T0 + 1));
        m.setStatus(MessageStatus.PENDING);
        assertFalse(MessageAmend.canAmend(m, ALICE, T0 + 1));
        m.setStatus(MessageStatus.SENT);
        m.setWithdrawn(true);
        assertFalse(MessageAmend.canAmend(m, ALICE, T0 + 1));
    }

    @Test
    public void amendStateSurvivesLocalJsonAndStaysOffTheWire() {
        ImMessage m = original();
        MessageAmend.apply(m, edit("00000000000000e1", "hello", T0 + 60_000));
        // Shared with FreerForMac: the history file is read by the other client.
        String json = m.toJson();
        assertTrue(json.endsWith("\"editedAt\":1762300860000,\"editId\":\"00000000000000e1\","
                + "\"editHistory\":[{\"content\":\"helo\",\"at\":1762300800000}],"
                + "\"id\":\"00000000000000a1\"}"), json);
        ImMessage back = ImMessage.fromJson(json);
        assertEquals("hello", back.getContent());
        assertEquals(1, back.getEditHistory().size());
        assertEquals("helo", back.getEditHistory().get(0).getContent());

        // The wire op carries the target in requestId and nothing of the local state.
        ImMessage op = edit("00000000000000e1", "hello", T0 + 60_000);
        assertEquals(ContentType.EDIT, op.getContentType());
        assertEquals("00000000000000a1", op.getRequestId());
        assertNull(op.getEditHistory());
    }
}
