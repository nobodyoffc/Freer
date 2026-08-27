package com.fc.freer.data;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified logic for opening a HAT file:
 *   1. Use local copy if available.
 *   2. Auto-download from DISK (decrypting if needed) when only a remote copy exists.
 *   3. Open editable files in TextEditorActivity; everything else via system viewer.
 */
public class HatFileOpener {
    private static final String TAG = "HatFileOpener";

    private final Activity activity;
    private final HatManager hatManager;
    private final DataSyncManager dataSyncManager;
    @Nullable private ActivityResultLauncher<Intent> textEditorLauncher;

    /**
     * Observes a remote download started by {@link #open(Hat, DownloadObserver)}.
     * All callbacks run on the UI thread and are skipped if the Activity is finishing.
     */
    public interface DownloadObserver {
        /** A remote download actually started (not called when the file opens locally). */
        void onDownloadStarted(DownloadHandle handle, long totalBytes);

        /** Cumulative bytes received; may slightly exceed totalBytes due to protocol framing. */
        void onProgress(long bytes);

        /** Terminal state. Not called after {@link DownloadHandle#cancel()}. */
        void onFinished(boolean success, @Nullable String error);
    }

    /**
     * Cancelling abandons the wait: no further observer callbacks fire and the file is
     * not opened. The transfer itself may still complete in the background — a completed
     * file is kept (it has been paid for) so the next tap opens it instantly.
     */
    public static class DownloadHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    public HatFileOpener(Activity activity, HatManager hatManager) {
        this.activity = activity;
        this.hatManager = hatManager;
        this.dataSyncManager = new DataSyncManager(activity, hatManager);
    }

    public void setTextEditorLauncher(@Nullable ActivityResultLauncher<Intent> launcher) {
        this.textEditorLauncher = launcher;
    }

    public void open(Hat hat) {
        open(hat, null);
    }

    public void open(Hat hat, @Nullable DownloadObserver observer) {
        String localPath = findLocalPath(hat);
        if (localPath != null) {
            openLocal(hat, localPath);
        } else {
            tryDownload(hat, observer, true);
        }
    }

    /**
     * Download the file without opening it, saving a copy to the public Downloads folder.
     * If a local copy already exists, it is exported directly without hitting the network.
     */
    public void download(Hat hat, @Nullable DownloadObserver observer) {
        String localPath = findLocalPath(hat);
        if (localPath != null) {
            exportToDownloads(hat, new File(localPath));
        } else {
            tryDownload(hat, observer, false);
        }
    }

    private void openLocal(Hat hat, String localPath) {
        File file = new File(localPath);
        if (!file.exists()) {
            ToastUtils.showError(activity, activity.getString(R.string.file_not_found));
            return;
        }

        String mimeType = resolveMimeType(hat);
        if (FileTypeHandler.isEditable(mimeType) || FileTypeHandler.isEditableByExtension(hat.getName())) {
            Intent intent = new Intent(activity, TextEditorActivity.class);
            intent.putExtra(TextEditorActivity.EXTRA_HAT_ID, hat.getId());
            if (textEditorLauncher != null) {
                textEditorLauncher.launch(intent);
            } else {
                activity.startActivity(intent);
            }
        } else {
            try {
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", file);
                Intent viewIntent = FileTypeHandler.getViewIntent(activity, uri, mimeType);
                // No installed app can handle this file — tell the user it can't be played
                // instead of silently doing nothing or reporting a generic error.
                if (viewIntent == null
                        || viewIntent.resolveActivity(activity.getPackageManager()) == null) {
                    ToastUtils.showError(activity, activity.getString(R.string.no_app_to_open_file));
                    return;
                }
                activity.startActivity(viewIntent);
                hat.setLast(System.currentTimeMillis());
                hatManager.updateHat(hat);
                hatManager.commit();
            } catch (android.content.ActivityNotFoundException e) {
                TimberLogger.e(TAG, "No app to open file: %s", e.getMessage());
                ToastUtils.showError(activity, activity.getString(R.string.no_app_to_open_file));
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error opening file: %s", e.getMessage());
                ToastUtils.showError(activity, activity.getString(R.string.error_opening_file));
            }
        }
    }

    private void tryDownload(Hat hat, @Nullable DownloadObserver observer, boolean openAfter) {
        if (!hasDiskSource(hat)) {
            ToastUtils.showError(activity, activity.getString(R.string.file_not_available_locally));
            return;
        }

        String plainKey = hat.getKey();
        if (plainKey != null && !plainKey.isEmpty()) {
            downloadData(hat, null, observer, openAfter);
            return;
        }

        FidManager fidManager = FidManager.getInstance();
        KeyInfo keyInfo = fidManager != null ? fidManager.getLiveKeyInfo() : null;
        if (keyInfo == null || keyInfo.getPrikeyCipher() == null) {
            ToastUtils.showError(activity, activity.getString(R.string.private_key_not_available));
            return;
        }

        String reason = openAfter ? activity.getString(R.string.open)
                : activity.getString(R.string.download);
        SecurePrikeyManager.requestPrikey(activity, reason,
                keyInfo.getPrikeyCipher(), new SecurePrikeyManager.PrikeyCallback() {
                    @Override
                    public void onPrikeyDecrypted(byte[] prikey) {
                        downloadData(hat, prikey, observer, openAfter);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        ToastUtils.showError(activity, errorMessage);
                    }

                    @Override
                    public void onUserDenied() {}
                });
    }

    private void downloadData(Hat hat, @Nullable byte[] prikey, @Nullable DownloadObserver observer,
                              boolean openAfter) {
        DownloadHandle handle = new DownloadHandle();
        if (observer != null) {
            long total = hat.getSize() != null ? hat.getSize() : 0L;
            observer.onDownloadStarted(handle, total);
        } else {
            ToastUtils.makeText(activity, activity.getString(R.string.downloading_data));
        }
        new Thread(() -> {
            File dataDir = new File(activity.getFilesDir(), "data");
            dataDir.mkdirs();
            File localFile = new File(dataDir, hat.getId());

            boolean success = dataSyncManager.downloadData(hat, prikey, localFile,
                    observer == null ? null : bytes -> runOnUi(() -> {
                        if (!handle.isCancelled()) observer.onProgress(bytes);
                    }));
            if (success) {
                hatManager.addHatLocation(hat.getId(), "local://" + localFile.getAbsolutePath());
                hatManager.commit();
            } else if (localFile.exists()) {
                localFile.delete();
            }
            // A cancelled-but-completed file is kept (already recorded above) but not opened.
            if (handle.isCancelled()) return;
            runOnUi(() -> {
                if (success) {
                    if (observer != null) observer.onFinished(true, null);
                    if (openAfter) {
                        openLocal(hat, localFile.getAbsolutePath());
                    } else {
                        exportToDownloads(hat, localFile);
                    }
                } else {
                    if (observer != null) {
                        observer.onFinished(false, dataSyncManager.getLastError());
                    } else {
                        ToastUtils.showError(activity,
                                activity.getString(R.string.download_failed, dataSyncManager.getLastError()));
                    }
                }
            });
        }).start();
    }

    /**
     * Copies a downloaded/local HAT file into the device's public Downloads folder so the user
     * keeps an accessible copy. The app-private cache copy is left in place for later opening.
     */
    private void exportToDownloads(Hat hat, File source) {
        if (!source.exists()) {
            ToastUtils.showError(activity, activity.getString(R.string.file_not_found));
            return;
        }
        String fileName = resolveFileName(hat);
        new Thread(() -> {
            try {
                String savedLocation;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    savedLocation = exportViaMediaStore(hat, source, fileName);
                } else {
                    savedLocation = exportViaFile(source, fileName);
                }
                runOnUi(() -> ToastUtils.makeText(activity,
                        activity.getString(R.string.file_saved) + ": " + savedLocation));
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to export file to Downloads: %s", e.getMessage());
                runOnUi(() -> ToastUtils.showError(activity,
                        activity.getString(R.string.failed_to_save_file)));
            }
        }).start();
    }

    /** API 29+: insert into MediaStore.Downloads (no storage permission required). */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private String exportViaMediaStore(Hat hat, File source, String fileName) throws Exception {
        android.content.ContentResolver resolver = activity.getContentResolver();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
        String mime = resolveMimeType(hat);
        if (mime != null) values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
        values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS);
        Uri uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("MediaStore insert returned null");
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             java.io.OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("openOutputStream returned null");
            copyStream(in, out);
        }
        return android.os.Environment.DIRECTORY_DOWNLOADS + "/" + fileName;
    }

    /** API 28 and below: direct write to the public Downloads directory. */
    private String exportViaFile(File source, String fileName) throws Exception {
        File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) downloadDir.mkdirs();
        File target = uniqueFile(downloadDir, fileName);
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            copyStream(in, out);
        }
        return target.getAbsolutePath();
    }

    private void copyStream(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    /** Uses the HAT name when present, falling back to the HAT id. */
    private String resolveFileName(Hat hat) {
        String name = hat.getName();
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return hat.getId();
    }

    /** Avoids clobbering an existing Downloads file by appending " (1)", " (2)", … before the extension. */
    private File uniqueFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; ; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
    }

    /** Runs on the UI thread, skipping if the Activity is gone (rotation, back navigation). */
    private void runOnUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || activity.isFinishing()) return;
            action.run();
        });
    }

    private boolean hasDiskSource(Hat hat) {
        List<String> locas = hat.getLocas();
        if (locas != null) {
            for (String loca : locas) {
                if (loca != null && (loca.startsWith(DataSyncManager.FUDP_LOCATION_PREFIX)
                        || loca.startsWith(DataSyncManager.SID_LOCATION_PREFIX))) {
                    return true;
                }
            }
        }
        List<String> cipherIds = hat.getCipherIds();
        return cipherIds != null && !cipherIds.isEmpty();
    }

    String findLocalPath(Hat hat) {
        List<String> locas = hat.getLocas();
        if (locas != null) {
            for (String loca : locas) {
                if (loca != null && loca.startsWith("local://")) {
                    String path = loca.substring("local://".length());
                    if (new File(path).exists()) return path;
                }
            }
        }
        File defaultFile = new File(new File(activity.getFilesDir(), "data"), hat.getId());
        if (defaultFile.exists()) return defaultFile.getAbsolutePath();
        return null;
    }

    private String resolveMimeType(Hat hat) {
        List<String> types = hat.getTypes();
        if (types != null && !types.isEmpty()) return types.get(0);
        return FileTypeHandler.getMimeType(hat.getName());
    }
}
