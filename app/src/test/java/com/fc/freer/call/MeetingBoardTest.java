package com.fc.freer.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.MeetingSignal;

import org.junit.Before;
import org.junit.Test;

/** MeetingBoard (VOICE_SPEC §3.3): the meeting cards' state. */
public class MeetingBoardTest {

    static final String TEAM = "FTeam";
    static final String ID = "mtg_00112233445566778899aabb";

    String saved;
    MeetingBoard board;

    static MeetingSignal start(String id, int epoch, byte authPubByte) {
        byte[] authPub = new byte[33];
        authPub[0] = 2;
        authPub[1] = authPubByte;
        return MeetingSignal.start(id, new CallSignal.Relay("fudp://relay:19950"), new byte[32], 1_790_000_000L,
                authPub, epoch, "Standup", 1_000L + epoch);
    }

    @Before
    public void setUp() {
        saved = null;
        board = new MeetingBoard(store());
    }

    MeetingBoard.Store store() {
        return new MeetingBoard.Store() {
            @Override
            public String load() {
                return saved;
            }

            @Override
            public void save(String json) {
                saved = json;
            }
        };
    }

    @Test
    public void aStartMakesOneCardAndARekeyUpdatesIt() {
        assertEquals(MeetingBoard.Result.NEW, board.onStart(TEAM, "TEAM", "FHost", start(ID, 0, (byte) 1)));
        assertEquals("the same post again", MeetingBoard.Result.UNCHANGED,
                board.onStart(TEAM, "TEAM", "FHost", start(ID, 0, (byte) 1)));
        assertEquals(MeetingBoard.Result.UPDATED, board.onStart(TEAM, "TEAM", "FHost", start(ID, 1, (byte) 2)));
        MeetingBoard.Meeting m = board.get(ID);
        assertEquals("FHost", m.hostFid);
        assertEquals(2, m.keys.size());
        assertEquals("newest key first", 1, m.newestKeys().keyEpoch());
        assertEquals(1, board.open(TEAM).size());
    }

    @Test
    public void onlyTheHostEndsAMeetingOutright() {
        board.onStart(TEAM, "TEAM", "FHost", start(ID, 0, (byte) 1));
        assertEquals("another member's end is only a hint", MeetingBoard.Result.CONFIRM,
                board.onEnd(TEAM, "FOther", MeetingSignal.end(ID, 5_000)));
        assertFalse(board.isEnded(ID));
        assertEquals(MeetingBoard.Result.UPDATED, board.onEnd(TEAM, "FHost", MeetingSignal.end(ID, 65_000)));
        assertTrue(board.isEnded(ID));
        assertEquals(65_000, board.get(ID).duration);
        assertTrue(board.open(TEAM).isEmpty());
        assertEquals(MeetingBoard.Result.UNCHANGED, board.onEnd(TEAM, "FHost", MeetingSignal.end(ID, 1)));
    }

    @Test
    public void aMeetingIdFromAnotherEntityChangesNothing() {
        board.onStart(TEAM, "TEAM", "FHost", start(ID, 0, (byte) 1));
        assertEquals(MeetingBoard.Result.UNCHANGED, board.onStart("FElsewhere", "ROOM", "FHost", start(ID, 1, (byte) 9)));
        assertEquals(MeetingBoard.Result.UNCHANGED, board.onEnd("FElsewhere", "FHost", MeetingSignal.end(ID, 1)));
        assertEquals(1, board.get(ID).keys.size());
        assertFalse(board.isEnded(ID));
    }

    @Test
    public void itSurvivesARestart() {
        board.onStart(TEAM, "TEAM", "FHost", start(ID, 0, (byte) 1));
        board.markEnded(ID, 42);
        MeetingBoard again = new MeetingBoard(store());
        assertNotNull(again.get(ID));
        assertTrue(again.isEnded(ID));
        assertEquals("fudp://relay:19950", again.get(ID).relay.url());
    }

    @Test
    public void onlyTheNewestMeetingsAreKept() {
        for (int i = 0; i < MeetingBoard.LIMIT + 5; i++) {
            String id = String.format("mtg_%024x", i);
            board.onStart(TEAM, "TEAM", "FHost", MeetingSignal.start(id, new CallSignal.Relay("fudp://r:1"),
                    new byte[32], 1, new byte[33], 0, null, i));
        }
        assertNull("the oldest went", board.get(String.format("mtg_%024x", 0)));
        assertNotNull(board.get(String.format("mtg_%024x", MeetingBoard.LIMIT + 4)));
    }
}
