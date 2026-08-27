package com.fc.fc_ajdk.feature.avatar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The cross-client contract for group avatars.
 *
 * <p>Every expected value below was produced by the macOS reference
 * implementation ({@code FCUI/Avatar/GroupAvatarMaker.swift}) and pasted
 * here verbatim. That is the whole point: a group must wear the same tile
 * on both clients, so these are not really assertions about Java — they
 * are assertions that this port has not drifted from Swift. If one of
 * them fails, the two apps are drawing different pictures of the same
 * group, and the fix is never to update the constant.
 *
 * <p>The shared spec is {@code GROUP_AVATAR_SPEC.md} in the FreerForMac
 * repository.
 */
public class GroupAvatarMakerTest {

    private static final String ROOM_ID = "room_9f2c1ab4de77035c81ba64f2";
    private static final String TEAM_ID =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private static final String ROOM_ID_2 = "room_00112233445566778899aabb";
    private static final String SQUARE_ID =
            "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    /** Cells as the renderer reads them: row-major, '1' lit. */
    private static String render(String groupId) {
        boolean[] cells = GroupAvatarMaker.cells(groupId);
        StringBuilder sb = new StringBuilder(cells.length);
        for (boolean cell : cells) sb.append(cell ? '1' : '0');
        return sb.toString();
    }

    // MARK: - agreement with the Swift reference

    @Test
    public void patternsMatchTheSwiftReference() {
        assertEquals("0000000000001000101011011", render(ROOM_ID));
        assertEquals("1101110001111111010100000", render(TEAM_ID));
        assertEquals("0010001010110111000100100", render(ROOM_ID_2));
        assertEquals("1000100100110110101000100", render(SQUARE_ID));
    }

    @Test
    public void huesMatchTheSwiftReference() {
        assertEquals(180, GroupAvatarMaker.hueDegrees(ROOM_ID));
        assertEquals(60, GroupAvatarMaker.hueDegrees(TEAM_ID));
        assertEquals(330, GroupAvatarMaker.hueDegrees(ROOM_ID_2));
        assertEquals(300, GroupAvatarMaker.hueDegrees(SQUARE_ID));
    }

    // MARK: - the properties the spec promises

    /**
     * Java bytes are signed and Swift's UInt8 is not. Without the
     * {@code & 0xFF} in hueDegrees, any id whose 16th or 17th hash byte
     * has the high bit set produces a different hue here than on Mac —
     * silently, and for only about three ids in four. Two of the fixtures
     * above exercise it; this pins the range for everything else.
     */
    @Test
    public void hueIsAlwaysOneOfTwelveNonNegativeSteps() {
        for (int i = 0; i < 500; i++) {
            int hue = GroupAvatarMaker.hueDegrees("room_" + i);
            assertEquals("hue " + hue + " is off the 30° grid", 0, hue % 30);
            assertTrue("hue " + hue + " out of range", hue >= 0 && hue < 360);
        }
    }

    @Test
    public void gridIsMirroredLeftToRight() {
        for (String id : new String[]{ROOM_ID, TEAM_ID, ROOM_ID_2, SQUARE_ID}) {
            boolean[] cells = GroupAvatarMaker.cells(id);
            assertEquals(25, cells.length);
            for (int row = 0; row < 5; row++) {
                assertEquals("row " + row + " of " + id,
                        cells[row * 5], cells[row * 5 + 4]);
                assertEquals("row " + row + " of " + id,
                        cells[row * 5 + 1], cells[row * 5 + 3]);
            }
        }
    }

    /** A blank tile cannot be told from another blank tile. */
    @Test
    public void noIdRendersAnEmptyGrid() {
        for (int i = 0; i < 2000; i++) {
            boolean any = false;
            for (boolean cell : GroupAvatarMaker.cells("room_" + i)) any |= cell;
            assertTrue("room_" + i + " rendered an empty grid", any);
        }
    }

    /**
     * The ids that make this class necessary. Both real group id shapes
     * are things {@link AvatarMaker} was never built to read — it wants a
     * Base58 FID — and handing it one produces a human face for a group.
     */
    @Test
    public void groupIdsAreNotFids() {
        assertEquals(29, ROOM_ID.length());          // AvatarMaker needs 30
        assertTrue(TEAM_ID.contains("0"));           // hex has 0, Base58 has not
        assertEquals(25, GroupAvatarMaker.cells(ROOM_ID).length);
        assertEquals(25, GroupAvatarMaker.cells(TEAM_ID).length);
    }
}
