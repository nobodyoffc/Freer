package com.fc.freer.manager;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Image;
import com.fc.fc_ajdk.data.feipData.ImageOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.data.DataSyncManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * FEIP24 Images: the lists (mine, everyone's, my deleted ones), the upload of a picture to
 * DISK, and the carves. The chain holds only the record; the bytes go to DISK and the record
 * names them by {@code did} (sha256x2), so any reader can check what it fetched.
 */
public final class ImageManager {
    private static final String TAG = "ImageManager";

    /** Readers refuse larger pictures (see {@link TokenLogoManager}); so does the upload. */
    public static final long MAX_BYTES = 20L << 20;
    /** FEIP24 Locations rule 6: a publisher SHOULD list no more than 3. */
    public static final int MAX_LOCAS = 3;

    public enum Tab { MINE, DISCOVER, DELETED }

    private ImageManager() {}

    // ---- lists ----

    /** One page of a tab, newest first, after {@code after} (null for the first page). Blocking. */
    public static List<Image> search(Tab tab, String fid, Image after, int size) {
        FapiClient client = fapiClient();
        if (client == null) return null;
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(size);
        switch (tab) {
            case MINE:
                fcdsl.addNewQuery().addNewTerms().addNewFields(FieldNames.PUBLISHER).addNewValues(fid);
                fcdsl.addNewExcept().addNewTerms().addNewFields(FieldNames.DELETED).addNewValues(Values.TRUE);
                break;
            case DELETED:
                fcdsl.addNewQuery().addNewTerms().addNewFields(FieldNames.PUBLISHER).addNewValues(fid);
                fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.DELETED).addNewValues(Values.TRUE);
                break;
            case DISCOVER:
            default:
                fcdsl.addNewExcept().addNewTerms().addNewFields(FieldNames.DELETED).addNewValues(Values.TRUE);
                break;
        }
        fcdsl.addSort(FieldNames.LAST_HEIGHT, Values.DESC).addSort(FieldNames.ID, Values.DESC);
        if (after != null && after.getLastHeight() != null && after.getId() != null) {
            fcdsl.addAfter(Arrays.asList(String.valueOf(after.getLastHeight()), after.getId()));
        }
        try {
            return client.entitySearch(IndicesNames.IMAGE, fcdsl, Image.class);
        } catch (Exception e) {
            TimberLogger.w(TAG, "search %s: %s", tab, e.getMessage());
            return null;
        }
    }

    // ---- carves ----

    public static String publishJson(String title, String did, String lang, List<String> authors,
                                     String format, String summary, List<String> locas) {
        return envelope(ImageOpData.makePublish(title, did, lang, authors, format, summary, locas));
    }

    /** Every field is sent: an update replaces the record, nulls included. */
    public static String updateJson(String imageId, String title, String did, String lang, List<String> authors,
                                    String format, String summary, List<String> locas) {
        return envelope(ImageOpData.makeUpdate(imageId, title, did, lang, authors, format, summary, locas));
    }

    public static String deleteJson(List<String> imageIds) {
        return envelope(ImageOpData.makeDelete(imageIds));
    }

    public static String recoverJson(List<String> imageIds) {
        return envelope(ImageOpData.makeRecover(imageIds));
    }

    public static boolean fitsOpReturn(String feipJson) {
        return feipJson.getBytes(StandardCharsets.UTF_8).length <= Constants.MaxOpReturnSize;
    }

    private static String envelope(ImageOpData data) {
        Feip feip = Feip.fromName(IndicesNames.IMAGE);
        feip.setData(data);
        return feip.toJson();
    }

    public interface CarveCallback {
        /** On the main thread. */
        void onCarved(String txId);
        /** On the main thread; the message is ready to show. */
        void onFailed(String message);
    }

    /**
     * Signs and broadcasts {@code feipJson} from {@code fid}. Blocking (call off the main
     * thread); the callback runs on the main thread. A watch-only FID gets the unsigned TX as QR.
     */
    public static void carve(Activity activity, String fid, byte[] prikey, String feipJson, CarveCallback callback) {
        TxSender txSender = new TxSender();
        txSender.carveSimpleFeip(activity, fid, feipJson, prikey, CashManager.getInstance(), new TxHandler(),
                fapiClient(), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        activity.runOnUiThread(() -> callback.onCarved(txId));
                    }

                    @Override
                    public void onError(String errorMessage) {
                        activity.runOnUiThread(() -> callback.onFailed(errorMessage));
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        activity.runOnUiThread(() -> {
                            callback.onFailed(activity.getString(R.string.cannot_sign_transaction));
                            txSender.showUnsignedTxAsQR(activity, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        activity.runOnUiThread(() -> txSender.showSignedTxAsQR(activity, signedTxHex));
                    }
                });
    }

    // ---- upload ----

    /** A picture on DISK: what a publish or update record names. */
    public static final class Upload {
        public final String did;
        public final String format;
        public final List<String> locas;
        public final File file;

        Upload(String did, String format, List<String> locas, File file) {
            this.did = did;
            this.format = format;
            this.locas = locas;
            this.file = file;
        }
    }

    /** Thrown with a message ready to show. */
    public static final class UploadException extends Exception {
        public UploadException(String message) {
            super(message);
        }
    }

    /**
     * Copies the picture at {@code uri} into the app, uploads it to the default DISK for good
     * (unencrypted: a published image is public), and returns what the record names. Blocking.
     */
    public static Upload upload(Context context, String fid, Uri uri) throws UploadException {
        File dir = new File(context.getCacheDir(), "image_upload");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String name = displayName(context, uri);
        File file = new File(dir, System.currentTimeMillis() + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new UploadException(context.getString(R.string.image_read_failed));
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new UploadException(context.getString(R.string.image_too_large, MAX_BYTES >> 20));
                out.write(buffer, 0, n);
            }
        } catch (UploadException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw new UploadException(context.getString(R.string.image_read_failed));
        }

        String format = context.getContentResolver().getType(uri);
        if (format == null || !format.startsWith("image/")) format = null;

        String did;
        try {
            byte[] hash = Hash.sha256x2Bytes(file);
            if (hash == null) throw new UploadException(context.getString(R.string.image_read_failed));
            did = Hex.toHex(hash);
        } catch (UploadException e) {
            throw e;
        } catch (Exception e) {
            throw new UploadException(context.getString(R.string.image_read_failed));
        }

        HatManager hatManager = HatManager.getInstance(context, fid);
        Hat hat = hatManager.getHatById(did);
        if (hat == null) {
            hat = new Hat();
            hat.setId(did);
            hat.setName(name);
            hat.setSize(file.length());
            hat.setBorn(System.currentTimeMillis());
            hat.setLast(System.currentTimeMillis());
            hat.setState(Hat.DataState.ACTIVE);
            if (format != null) hat.setTypes(Collections.singletonList(format));
            hatManager.addHat(hat);
        }
        DataSyncManager sync = new DataSyncManager(context, hatManager);
        DiskItem item = sync.uploadRawData(file, hat, true);
        if (item == null) {
            String why = sync.getLastError();
            throw new UploadException(context.getString(R.string.image_upload_failed)
                    + (why == null || why.isEmpty() ? "" : ": " + why));
        }

        List<String> locas = new ArrayList<>();
        for (String loca : hat.getLocas() == null ? Collections.<String>emptyList() : hat.getLocas()) {
            boolean disk = loca.startsWith(DataSyncManager.SID_LOCATION_PREFIX)
                    || (loca.startsWith(DataSyncManager.FUDP_LOCATION_PREFIX)
                        && !loca.equals(DataSyncManager.FUDP_LOCATION_PREFIX + "unknown"));
            if (disk && !locas.contains(loca)) locas.add(loca);
            if (locas.size() == MAX_LOCAS) break;
        }
        TokenLogoManager.getInstance(context).seed(did, file);
        return new Upload(did, format, locas, file);
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Falls back below.
        }
        return "image";
    }

    // ---- carved here, not yet indexed ----

    private static final java.util.Map<String, Image> recent = new java.util.concurrent.ConcurrentHashMap<>();

    /** A record this phone just carved: listed under Mine until the index returns it. */
    public static void noteCarved(Context context, Image image) {
        if (image == null || image.getId() == null) return;
        recent.put(image.getId(), image);
        TokenLogoManager.getInstance(context).remember(image);
    }

    /** {@code page} (the first page of Mine) with this FID's not-yet-indexed carves ahead of it. */
    public static List<Image> withRecent(String fid, List<Image> page) {
        List<Image> out = new ArrayList<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (Image image : page) listed.add(image.getId());
        for (Image image : recent.values()) {
            if (listed.contains(image.getId())) {
                recent.remove(image.getId());
            } else if (fid != null && fid.equals(image.getPublisher()) && !Boolean.TRUE.equals(image.getDeleted())) {
                out.add(image);
            }
        }
        out.addAll(page);
        return out;
    }

    // ---- helpers ----

    public static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[,，\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    public static boolean isOwner(Image image, String fid) {
        return image != null && fid != null && fid.equals(image.getPublisher());
    }

    private static FapiClient fapiClient() {
        Object client = ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        return client instanceof FapiClient fapi ? fapi : null;
    }
}
