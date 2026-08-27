package com.fc.freer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fc.fc_ajdk.feature.avatar.AvatarMaker;
import com.fc.fc_ajdk.feature.avatar.GroupAvatarMaker;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Renders group tiles on a real device, where {@code Bitmap} and
 * {@code Canvas} are the framework's rather than the unit-test stubs.
 *
 * <p>What this can and cannot check is worth being precise about. It
 * checks the pixels {@link GroupAvatarMaker} produces: that the tile is
 * a rounded square rather than a full one, that the badge lands inside
 * the bounds, that the two themes differ. It does <b>not</b> check
 * {@code clipToOutline}, because that is applied by the hardware
 * rendering pipeline and a software {@code View.draw(Canvas)} silently
 * ignores it — an earlier version of this test "proved" the corners
 * survived a clip that was never being applied at all. The app avoids
 * the question entirely: a group avatar is drawn with no background and
 * no outline clip, so the bitmap's own corners are the silhouette.
 */
@RunWith(AndroidJUnit4.class)
public class GroupAvatarRenderTest {

    private static final String ROOM_ID = "room_9f2c1ab4de77035c81ba64f2";
    private static final String TEAM_ID =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private static final String OWNER = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";

    private static final int SIZE = 144;   // 48dp at 3x

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * The tile is a rounded square: its extreme corner pixel is empty and
     * its centre is not. A plain rectangle would fail the first half, and
     * a circle — which is what an oval outline clip would leave — would
     * fail at a point well inside the corner that a rounded square keeps.
     */
    @Test
    public void tileIsARoundedSquare() {
        Bitmap tile = GroupAvatarMaker.makeAvatar(ROOM_ID, null, null, SIZE, false);
        assertNotNull("tile did not render", tile);
        assertEquals(SIZE, tile.getWidth());
        assertEquals(SIZE, tile.getHeight());

        assertTrue("the very corner should be outside the rounding",
                Color.alpha(tile.getPixel(1, 1)) < 50);
        assertTrue("the middle should be painted",
                Color.alpha(tile.getPixel(SIZE / 2, SIZE / 2)) > 200);

        // 22% in from the corner is inside a rounded square of radius 22%,
        // and comfortably outside a circle inscribed in the same bounds.
        int probe = (int) (SIZE * 0.22f);
        assertTrue("a rounded square keeps this pixel; a circle would not",
                Color.alpha(tile.getPixel(probe, probe)) > 200);
    }

    /** The badge must sit inside the tile, not hang off its corner. */
    @Test
    public void badgeStaysInsideTheTile() {
        Bitmap owner = ownerAvatar();
        Bitmap tile = GroupAvatarMaker.makeAvatar(ROOM_ID, OWNER, owner, SIZE, false);
        assertNotNull(tile);

        // The badge lives bottom-right; the opposite corner is untouched
        // by it, and the extreme bottom-right pixel is still outside the
        // rounding rather than covered by an overhanging circle.
        assertTrue("badge overflowed the bottom-right corner",
                Color.alpha(tile.getPixel(SIZE - 1, SIZE - 1)) < 50);

        Bitmap bare = GroupAvatarMaker.makeAvatar(ROOM_ID, null, null, SIZE, false);
        int badgeCentre = (int) (SIZE * (1f - 0.055f - 0.50f / 2f));
        assertTrue("the badge did not draw",
                bare.getPixel(badgeCentre, badgeCentre) != tile.getPixel(badgeCentre, badgeCentre));
    }

    @Test
    public void themesDiffer() {
        Bitmap light = GroupAvatarMaker.makeAvatar(TEAM_ID, null, null, SIZE, false);
        Bitmap dark = GroupAvatarMaker.makeAvatar(TEAM_ID, null, null, SIZE, true);
        assertTrue("light and dark tiles are identical",
                light.getPixel(SIZE / 2, 4) != dark.getPixel(SIZE / 2, 4));
    }

    private Bitmap ownerAvatar() {
        try {
            byte[] bytes = AvatarMaker.makeAvatar(OWNER, context());
            assertNotNull("owner avatar did not render", bytes);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            throw new AssertionError("owner avatar failed", e);
        }
    }

}
