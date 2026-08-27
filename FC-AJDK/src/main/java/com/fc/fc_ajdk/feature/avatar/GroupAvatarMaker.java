package com.fc.fc_ajdk.feature.avatar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import com.fc.fc_ajdk.core.crypto.Hash;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic avatar for a <b>group</b> — a room, a team, or a square.
 *
 * <p>{@link AvatarMaker} cannot serve here, and not merely as a matter of
 * taste. It requires a Base58 string of at least 30 characters and reads
 * positions 20–29 of it, which is a description of a FID and of nothing
 * else. A room id is {@code room_} + 24 hex characters — 29 long — and a
 * team or square id is a 64-character txid whose hex alphabet contains
 * {@code 0}, which Base58 does not. Feeding either one in produces a
 * human face composited out of an identifier, a face belonging to no one.
 *
 * <p>So a group gets its own generator, built on the one fact about a
 * group that never changes: <b>its id</b>. An owner can be transferred, a
 * square can be renamed by whoever last paid to rename it, membership is
 * rewritten every time somebody joins. The id is issued once and outlives
 * all of it, which is why it is the mark and the owner is only a badge
 * drawn on top of it.
 *
 * <h3>The format</h3>
 *
 * Let {@code H = sha256(utf8(groupId))}, 32 bytes.
 *
 * <p><b>Pattern</b> — a 5×5 grid mirrored left-to-right, so only the
 * three left columns are free: 15 cells. For {@code i} in {@code 0..14},
 * cell {@code i} is lit when {@code H[i]} is odd; {@code i} is
 * {@code column * 5 + row}, column-major from the top-left, {@code row}
 * counting downward. Columns 3 and 4 mirror columns 1 and 0. If the whole
 * grid comes up dark — 1 group in 32768 — every cell is inverted, because
 * a blank tile is the one mark that cannot be told from another blank.
 *
 * <p><b>Hue</b> — {@code ((H[15] << 8) | H[16]) % 12 * 30} degrees.
 * Quantising to twelve 30° steps is deliberate: a continuous hue makes
 * 100° and 103° two different groups that no one can tell apart, which
 * spends the entropy without buying any distinctness.
 *
 * <p>This is a byte-for-byte port of the macOS reference implementation
 * in {@code FCUI/Avatar/GroupAvatarMaker.swift}; the shared spec, with
 * the palette and geometry tables, is {@code GROUP_AVATAR_SPEC.md} in the
 * FreerForMac repository. The two clients must render the same tile for
 * the same group, so nothing here may be "improved" on one side alone.
 *
 * <p>Note the {@code & 0xFF} in {@link #hueDegrees(String)}: Java bytes
 * are signed and Swift's {@code UInt8} is not. That is the one place a
 * port diverges silently.
 */
public class GroupAvatarMaker {

    public static final int GRID_SIDE = 5;
    public static final int FREE_COLUMNS = 3;
    public static final int FREE_CELLS = GRID_SIDE * FREE_COLUMNS;   // 15
    public static final int HUE_STEPS = 12;

    // Geometry, as fractions of the side.
    private static final float CORNER_FRACTION = 0.22f;
    private static final float INSET_FRACTION = 0.12f;
    // One half: large enough that the owner's face is recognisable in a
    // 48dp list row, small enough that the mark is still the thing the
    // tile is mostly made of. It covers roughly the bottom-right quadrant.
    // Mirroring makes most of that free — the right columns are only
    // reflections — and the centre column, which has no copy, is only
    // clipped at the right edge of its lowest cells.
    private static final float BADGE_FRACTION = 0.50f;
    private static final float RING_FRACTION = 0.055f;

    // Palette: tile and mark share the hue and differ in saturation and
    // value. Defined outright per theme rather than one derived from the
    // other by alpha, so a tile does not change when the row behind it
    // is selected.
    private static final float[] TILE_LIGHT = {0.16f, 0.97f};
    private static final float[] MARK_LIGHT = {0.64f, 0.70f};
    private static final float[] TILE_NIGHT = {0.28f, 0.30f};
    private static final float[] MARK_NIGHT = {0.52f, 0.86f};

    private static final int MAX_CACHED = 64;

    /**
     * Rendered tiles, keyed by everything that changes the pixels. The
     * mark itself is cheap — 25 rectangles — but a RecyclerView binds on
     * every scroll frame, and allocating a bitmap per bind is the kind of
     * churn that shows up as jank rather than as a bug.
     */
    private static final Map<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_CACHED;
                }
            };

    /**
     * The 25 cells of the grid, <b>row-major</b> ({@code row * 5 + column}),
     * which is the order a renderer wants — unlike the column-major order
     * the hash is read in, which is the order the spec is written in.
     * Keeping the two orders explicitly separate is what stops a port from
     * quietly transposing every mark.
     */
    public static boolean[] cells(String groupId) {
        byte[] h = Hash.sha256(groupId.getBytes(StandardCharsets.UTF_8));

        boolean[] free = new boolean[FREE_CELLS];
        boolean any = false;
        for (int i = 0; i < FREE_CELLS; i++) {
            free[i] = (h[i] & 1) == 1;
            any |= free[i];
        }
        // A blank mark is the only unusable one: invert rather than ship it.
        if (!any) {
            for (int i = 0; i < FREE_CELLS; i++) free[i] = true;
        }

        boolean[] grid = new boolean[GRID_SIDE * GRID_SIDE];
        for (int column = 0; column < GRID_SIDE; column++) {
            // Columns 3, 4 reflect columns 1, 0.
            int source = column < FREE_COLUMNS ? column : GRID_SIDE - 1 - column;
            for (int row = 0; row < GRID_SIDE; row++) {
                grid[row * GRID_SIDE + column] = free[source * GRID_SIDE + row];
            }
        }
        return grid;
    }

    /** The mark's hue in degrees, snapped to one of {@link #HUE_STEPS}. */
    public static int hueDegrees(String groupId) {
        byte[] h = Hash.sha256(groupId.getBytes(StandardCharsets.UTF_8));
        int raw = ((h[15] & 0xFF) << 8) | (h[16] & 0xFF);
        return (raw % HUE_STEPS) * (360 / HUE_STEPS);
    }

    /**
     * The finished tile.
     *
     * @param groupId     room id, or the team/square txid — used verbatim
     * @param ownerFid    the badged FID, used as the cache key. Passed
     *                    separately from the bitmap because
     *                    {@code AvatarManager.getAvatarBitmap} decodes a
     *                    fresh {@code Bitmap} on every call: keying on the
     *                    object would miss every time and grow the cache
     *                    without bound.
     * @param ownerAvatar that FID's avatar, already rendered by
     *                    {@link AvatarMaker}; null draws no badge, which
     *                    is what an unknown owner should look like
     * @param sizePx      side length in pixels
     * @param night       true to use the dark palette
     */
    public static Bitmap makeAvatar(String groupId, String ownerFid, Bitmap ownerAvatar,
                                    int sizePx, boolean night) {
        if (groupId == null || groupId.isEmpty() || sizePx <= 0) return null;

        String key = groupId + "|" + (ownerFid == null ? "" : ownerFid) + "|" + sizePx + "|" + night;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null && !hit.isRecycled()) return hit;
        }

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float hue = hueDegrees(groupId);
        int tileColor = hsv(hue, night ? TILE_NIGHT : TILE_LIGHT);
        int markColor = hsv(hue, night ? MARK_NIGHT : MARK_LIGHT);

        float corner = sizePx * CORNER_FRACTION;
        RectF bounds = new RectF(0, 0, sizePx, sizePx);

        // The tile. Everything after this is clipped to it, so a lit cell
        // in a corner cannot spill past the rounding.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tileColor);
        canvas.drawRoundRect(bounds, corner, corner, paint);

        Path tile = new Path();
        tile.addRoundRect(bounds, corner, corner, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(tile);

        // The mark.
        boolean[] cells = cells(groupId);
        float inset = sizePx * INSET_FRACTION;
        float cell = (sizePx - inset * 2) / GRID_SIDE;
        paint.setColor(markColor);
        for (int row = 0; row < GRID_SIDE; row++) {
            for (int column = 0; column < GRID_SIDE; column++) {
                if (!cells[row * GRID_SIDE + column]) continue;
                float left = inset + column * cell;
                float top = inset + row * cell;
                canvas.drawRect(left, top, left + cell, top + cell, paint);
            }
        }

        // The owner badge. Bottom-right, inside the bounds: the ring is
        // drawn outside the face, so without the inset the badge would
        // paint past the tile it belongs to.
        if (ownerAvatar != null && !ownerAvatar.isRecycled()) {
            float diameter = sizePx * BADGE_FRACTION;
            float ring = Math.max(1f, sizePx * RING_FRACTION);
            float centre = sizePx - ring - diameter / 2f;

            paint.setColor(tileColor);
            canvas.drawCircle(centre, centre, diameter / 2f + ring, paint);

            Path face = new Path();
            face.addCircle(centre, centre, diameter / 2f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(face);
            canvas.drawBitmap(
                    ownerAvatar,
                    new Rect(0, 0, ownerAvatar.getWidth(), ownerAvatar.getHeight()),
                    new RectF(centre - diameter / 2f, centre - diameter / 2f,
                              centre + diameter / 2f, centre + diameter / 2f),
                    paint);
            canvas.restore();
        }

        canvas.restore();

        // A hairline so a pale tile still has an edge against a pale row.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, sizePx * 0.01f));
        paint.setColor(Color.argb(64, Color.red(markColor), Color.green(markColor), Color.blue(markColor)));
        float half = paint.getStrokeWidth() / 2f;
        canvas.drawRoundRect(
                new RectF(half, half, sizePx - half, sizePx - half), corner, corner, paint);

        synchronized (CACHE) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    private static int hsv(float hueDegrees, float[] saturationValue) {
        return Color.HSVToColor(new float[]{hueDegrees, saturationValue[0], saturationValue[1]});
    }
}
