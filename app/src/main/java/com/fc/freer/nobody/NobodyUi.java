package com.fc.freer.nobody;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * How a nobody looks, everywhere a FID appears: a greyscale avatar with a
 * skull badge, and a "Nobody" chip in front of its name. The CID is still
 * shown — the chip says it cannot be trusted.
 */
public final class NobodyUi {

    private static final String TAG = "NobodyUi";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ExecutorService RESOLVER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "nobody-resolver");
        thread.setDaemon(true);
        return thread;
    });

    /** Checks FIDs against the nobody index of the current FAPI service. */
    static final NobodyRegistry.Resolver FAPI_RESOLVER = fids -> {
        ApiCenter apiCenter = ApiCenter.getInstance();
        if (apiCenter == null) return null;
        FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
        // Offline, a query would only sit out its timeout behind a waiting dialog
        if (fapiClient == null || !fapiClient.isConnected()) return null;
        return fapiClient.checkNobodies(new ArrayList<>(fids));
    };

    private NobodyUi() {}

    /** Install the persisted registry and feed it from every FAPI lookup. Call once at app start. */
    public static void install() {
        NobodyRegistry.install(new MmkvNobodyStore(), MAIN::post);
        FapiClient.setNobodyObserver(new FapiClient.NobodyObserver() {
            @Override
            public void onNobodies(Collection<String> fids) {
                NobodyRegistry.get().markNobodies(fids);
            }

            @Override
            public void onNotNobodies(Collection<String> fids) {
                NobodyRegistry.get().markNotNobodies(fids);
            }
        });
    }

    public static boolean isNobody(String fid) {
        return NobodyRegistry.get().isNobody(fid);
    }

    /** Look up, in the background, whichever of these FIDs are still unknown. */
    public static void resolveAsync(Collection<String> fids) {
        resolveAsync(fids, false, null);
    }

    /**
     * Look up the unknown FIDs in the background.
     *
     * @param onDone run on the main thread with whether every needed check succeeded;
     *               immediately when nothing is unknown
     */
    public static void resolveAsync(Collection<String> fids, boolean retryFailed,
                                    @Nullable Consumer<Boolean> onDone) {
        NobodyRegistry registry = NobodyRegistry.get();
        if (registry.unknownOf(fids).isEmpty()) {
            if (onDone != null) MAIN.post(() -> onDone.accept(true));
            return;
        }
        final List<String> snapshot = new ArrayList<>(fids);
        RESOLVER.execute(() -> {
            boolean ok;
            try {
                ok = registry.resolve(snapshot, FAPI_RESOLVER, retryFailed);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Nobody resolution failed: %s", e.getMessage());
                ok = false;
            }
            if (onDone != null) {
                final boolean result = ok;
                MAIN.post(() -> onDone.accept(result));
            }
        });
    }

    /**
     * Call {@code onLearned} on the main thread whenever new nobodies are learned,
     * until the owner is destroyed.
     */
    public static void observe(LifecycleOwner owner, Consumer<Set<String>> onLearned) {
        NobodyRegistry.Listener listener = fids -> {
            if (owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.CREATED)) {
                onLearned.accept(fids);
            }
        };
        NobodyRegistry.get().addListener(listener);
        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner source) {
                NobodyRegistry.get().removeListener(listener);
            }
        });
    }

    // ---------------------------------------------------------------- avatar

    /** The avatar as it should be shown: marked when the FID is a nobody. */
    public static Bitmap avatar(Context context, String fid, Bitmap avatar) {
        if (avatar == null || !isNobody(fid)) return avatar;
        try {
            return markAvatar(context, avatar);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to mark nobody avatar: %s", e.getMessage());
            return avatar;
        }
    }

    /**
     * Greyscale plus a skull badge. The badge sits inside the inscribed
     * circle so avatars clipped to an oval keep it.
     */
    static Bitmap markAvatar(Context context, Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap marked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(marked);

        Paint grey = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        grey.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(source, 0, 0, grey);

        float size = Math.min(width, height);
        float badgeRadius = size * 0.2f;
        float offset = (size / 2f - badgeRadius) * 0.7071f;
        float cx = width / 2f + offset;
        float cy = height / 2f + offset;

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(ContextCompat.getColor(context, R.color.nobody_on_mark));
        canvas.drawCircle(cx, cy, badgeRadius * 1.12f, ring);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(ContextCompat.getColor(context, R.color.nobody_mark));
        canvas.drawCircle(cx, cy, badgeRadius, fill);

        Drawable icon = ContextCompat.getDrawable(context, R.drawable.ic_skull);
        if (icon != null) {
            icon = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(icon, ContextCompat.getColor(context, R.color.nobody_on_mark));
            int half = Math.round(badgeRadius * 0.72f);
            icon.setBounds(Math.round(cx) - half, Math.round(cy) - half,
                    Math.round(cx) + half, Math.round(cy) + half);
            icon.draw(canvas);
        }
        return marked;
    }

    // ---------------------------------------------------------------- name

    /**
     * The name as it should be shown: prefixed with a "Nobody" chip when the FID
     * is a nobody, otherwise returned unchanged. Only for display — never read a
     * decorated TextView's text back as a CID or FID.
     */
    public static CharSequence name(Context context, String fid, CharSequence name) {
        if (!isNobody(fid)) return name;
        String chip = context.getString(R.string.nobody);
        SpannableStringBuilder builder = new SpannableStringBuilder(chip);
        builder.setSpan(new ChipSpan(
                        ContextCompat.getColor(context, R.color.nobody_mark),
                        ContextCompat.getColor(context, R.color.nobody_on_mark),
                        context.getResources().getDisplayMetrics().density),
                0, chip.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (name != null && name.length() > 0) builder.append(name);
        return builder;
    }

    /** Set a TextView to a name, chip included when the FID is a nobody. */
    public static void setName(TextView view, String fid, CharSequence name) {
        if (view != null) view.setText(name(view.getContext(), fid, name));
    }

    /** Show {@code banner} with the message when the FID is a nobody; hide it otherwise. */
    public static void bindBanner(@Nullable TextView banner, String fid, @StringRes int message) {
        if (banner == null) return;
        if (isNobody(fid)) {
            banner.setText(message);
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    /**
     * {@link #bindBanner}, then look the FID up if its status is unknown; if it
     * turns out to be a nobody, show the banner and run {@code onLearned} on the
     * main thread so the caller can re-mark avatar and name.
     */
    public static void bindBannerResolving(@Nullable TextView banner, String fid, @StringRes int message,
                                           @Nullable Runnable onLearned) {
        bindBanner(banner, fid, message);
        if (fid == null || isNobody(fid)) return;
        resolveAsync(Collections.singletonList(fid), false, ok -> {
            if (!isNobody(fid)) return;
            bindBanner(banner, fid, message);
            if (onLearned != null) onLearned.run();
        });
    }

    /** A rounded label drawn in place of its text, with a gap after it. */
    static final class ChipSpan extends ReplacementSpan {
        private final int background;
        private final int foreground;
        private final float padding;
        private final float gap;
        private final float radius;

        ChipSpan(int background, int foreground, float density) {
            this.background = background;
            this.foreground = foreground;
            this.padding = 5 * density;
            this.gap = 4 * density;
            this.radius = 4 * density;
        }

        private static float textSize(Paint paint) {
            return paint.getTextSize() * 0.8f;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            float original = paint.getTextSize();
            paint.setTextSize(textSize(paint));
            float width = paint.measureText(text, start, end);
            paint.setTextSize(original);
            return Math.round(width + padding * 2 + gap);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            int originalColor = paint.getColor();
            float originalSize = paint.getTextSize();
            boolean originalBold = paint.isFakeBoldText();

            Paint.FontMetrics outer = paint.getFontMetrics();
            paint.setTextSize(textSize(paint));
            float width = paint.measureText(text, start, end);
            Paint.FontMetrics inner = paint.getFontMetrics();

            float chipTop = y + outer.ascent;
            float chipBottom = y + outer.descent;
            RectF rect = new RectF(x, chipTop, x + width + padding * 2, chipBottom);
            paint.setColor(background);
            canvas.drawRoundRect(rect, radius, radius, paint);

            float baseline = rect.centerY() - (inner.ascent + inner.descent) / 2f;
            paint.setColor(foreground);
            paint.setFakeBoldText(true);
            canvas.drawText(text, start, end, x + padding, baseline, paint);

            paint.setColor(originalColor);
            paint.setTextSize(originalSize);
            paint.setFakeBoldText(originalBold);
        }
    }

    /** A short FID for dialogs: head…tail. */
    static String shortFid(String fid) {
        if (fid == null) return "";
        return fid.length() > 14 ? fid.substring(0, 6) + "…" + fid.substring(fid.length() - 5) : fid;
    }

    static List<String> nonNull(Collection<String> fids) {
        if (fids == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (String fid : fids) if (fid != null && !fid.isEmpty()) list.add(fid);
        return list;
    }
}
