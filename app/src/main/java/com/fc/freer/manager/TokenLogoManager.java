package com.fc.freer.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.data.feipData.Image;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.data.DataSyncManager;
import com.fc.freer.utils.ApiCenter;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A token's logo (FEIP20 {@code cover}): the FEIP24 Image the deploy names, shown only when
 * that Image was published by the token's deployer (FEIP20 Cover rule 3). A token can never
 * be updated, so an Image someone else published would be a logo they control, or one borrowed
 * from another token.
 *
 * <p>Fetched once and kept on the phone: the Image record for a few hours (an Image can be
 * updated, so its did can change), the bytes for good, named by their did and stored only
 * after they hash back to it.
 */
public final class TokenLogoManager {
    private static final String TAG = "TokenLogoManager";
    private static final String PREFS = "token_logo_records";
    private static final long RECORD_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long RETRY_AFTER_MS = 10 * 60 * 1000L;
    private static final long MAX_BYTES = 20L << 20;
    private static final int MAX_FILES = 200;

    private static TokenLogoManager instance;

    private final Context context;
    private final File dir;
    private final SharedPreferences prefs;
    private final LruCache<String, Bitmap> bitmaps = new LruCache<>(64);
    // One thread: two cards naming the same logo never download it twice.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    public interface Callback {
        /** On the main thread; null when the token shows no logo. */
        void onLogo(Bitmap logo);
    }

    public static synchronized TokenLogoManager getInstance(Context context) {
        if (instance == null) instance = new TokenLogoManager(context.getApplicationContext());
        return instance;
    }

    private TokenLogoManager(Context context) {
        this.context = context;
        this.dir = new File(context.getFilesDir(), "token_logo_cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** FEIP20 Cover rule 3: only the deployer's own, undeleted Image is the logo. */
    public static boolean showsLogo(Token token, Image image) {
        return token != null && image != null && hasCover(token)
                && token.getDeployer() != null && !token.getDeployer().isEmpty()
                && token.getDeployer().equals(image.getPublisher())
                && !Boolean.TRUE.equals(image.getDeleted());
    }

    public static boolean hasCover(Token token) {
        return token != null && token.getCover() != null && token.getCover().matches("[0-9a-f]{64}");
    }

    /** The logo at about {@code sizePx}, or null, delivered to {@code callback} on the main thread. */
    public void load(Token token, int sizePx, Callback callback) {
        if (!hasCover(token)) {
            callback.onLogo(null);
            return;
        }
        executor.execute(() -> {
            Bitmap logo = null;
            try {
                logo = loadBlocking(token, sizePx);
            } catch (Exception e) {
                TimberLogger.w(TAG, "logo of %s: %s", token.getId(), e.getMessage());
            }
            Bitmap result = logo;
            main.post(() -> callback.onLogo(result));
        });
    }

    private Bitmap loadBlocking(Token token, int sizePx) {
        Image image = imageRecord(token.getCover());
        if (!showsLogo(token, image)) return null;
        String did = image.getDid() == null ? null : image.getDid().toLowerCase();
        if (did == null || !did.matches("[0-9a-f]{64}")) return null;

        String key = did + "@" + sizePx;
        Bitmap cached = bitmaps.get(key);
        if (cached != null) return cached;

        File file = new File(dir, did);
        if (!file.exists()) {
            Long failed = failedAt.get(did);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return null;
            File tmp = new File(dir, did + ".part");
            boolean ok = new DataSyncManager(context, null).downloadVerified(did, image.getLocas(), tmp, MAX_BYTES);
            if (!ok || !tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                failedAt.put(did, System.currentTimeMillis());
                return null;
            }
            prune();
        }
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
        Bitmap logo = decode(file, sizePx);
        if (logo != null) bitmaps.put(key, logo);
        return logo;
    }

    /** The Image record, from the phone while fresh, else from FAPI (falling back to a stale copy). */
    private Image imageRecord(String imageId) {
        String stored = prefs.getString(imageId, null);
        long fetched = prefs.getLong(imageId + ".at", 0);
        Image cached = null;
        if (stored != null) {
            try {
                cached = JsonUtils.fromJson(stored, Image.class);
            } catch (Exception ignored) {
                // Re-fetched below.
            }
        }
        if (cached != null && System.currentTimeMillis() - fetched < RECORD_TTL_MS) return cached;

        try {
            Object client = ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client instanceof com.fc.fc_ajdk.fapi.client.FapiClient fapiClient) {
                Image image = fapiClient.entityById(IndicesNames.IMAGE, Image.class, imageId);
                if (image != null) {
                    prefs.edit()
                            .putString(imageId, JsonUtils.toJson(image))
                            .putLong(imageId + ".at", System.currentTimeMillis())
                            .apply();
                    return image;
                }
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Image %s: %s", imageId, e.getMessage());
        }
        return cached;
    }

    private static Bitmap decode(File file, int sizePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    /** Keeps the newest {@link #MAX_FILES} logos. */
    private void prune() {
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
