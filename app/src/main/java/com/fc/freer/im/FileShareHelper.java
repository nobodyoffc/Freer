package com.fc.freer.im;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.data.DataSyncManager;
import com.fc.freer.manager.HatManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Orchestrates file sharing in IM chats via the HAT model.
 * All file shares -- regardless of size or channel (P2P, GROUP, ROOM, TEAM) --
 * go through the same DISK+HAT flow:
 * <ol>
 *   <li>Compute raw DID from file content</li>
 *   <li>Create raw HAT with file metadata</li>
 *   <li>Encrypt and upload to DISK via DataSyncManager (creates cipher HAT)</li>
 *   <li>Build an ImMessage with ContentType.HAT containing the raw HAT JSON</li>
 * </ol>
 */
public class FileShareHelper {
    private static final String TAG = "FileShareHelper";

    private final Context context;
    private final HatManager hatManager;
    private final DataSyncManager dataSyncManager;
    private String lastError;

    public FileShareHelper(Context context, HatManager hatManager) {
        this.context = context;
        this.hatManager = hatManager;
        this.dataSyncManager = new DataSyncManager(context, hatManager);
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Fired once the file has been hashed and a preliminary {@link ImMessage} exists,
     * before the (potentially slow) DISK upload begins. Lets the UI show the message
     * bubble immediately and track upload progress on it, keyed by {@code hatId}.
     */
    public interface PrepareCallback {
        void onPrepared(ImMessage message, String hatId, long fileSize);
    }

    /**
     * Share a file in a chat via the DISK+HAT flow.
     * Must be called on a background thread.
     *
     * @param file        The file to share
     * @param fileName    Display name for the file
     * @param imType      Chat mode (P2P, GROUP, ROOM, TEAM)
     * @param senderId    Sender FID
     * @param targetId    Target FID / group / room / team ID
     * @param desc        Optional description
     * @param progress    Optional progress callback (bytes uploaded)
     * @return The ImMessage (ContentType.HAT) to send, or null on failure
     */
    public ImMessage shareFile(File file, String fileName, ImType imType,
                               String senderId, String targetId,
                               String desc, PrepareCallback onPrepared, LongConsumer progress) {
        if (file == null || !file.exists()) {
            lastError = "File does not exist";
            return null;
        }

        return shareViaHat(file, fileName, file.length(), imType, senderId, targetId, desc,
                onPrepared, progress);
    }

    /**
     * Share a file picked from a content URI.
     */
    public ImMessage shareUri(Uri uri, ImType imType, String senderId,
                              String targetId, String desc,
                              PrepareCallback onPrepared, LongConsumer progress) {
        File tempFile = null;
        try {
            String fileName = resolveFileName(uri);
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                lastError = "Cannot open content URI";
                return null;
            }

            tempFile = File.createTempFile("share_", "_" + sanitize(fileName), context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
            }
            is.close();

            return shareFile(tempFile, fileName, imType, senderId, targetId, desc, onPrepared, progress);
        } catch (Exception e) {
            lastError = "Failed to copy content: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return null;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // ── DISK+HAT flow ────────────────────────────────────────────────────

    private ImMessage shareViaHat(File file, String fileName, long fileSize,
                                  ImType imType, String senderId, String targetId,
                                  String desc, PrepareCallback onPrepared, LongConsumer progress) {
        try {
            byte[] rawHash = Hash.sha256x2Bytes(file);
            if (rawHash == null) {
                lastError = "Failed to compute file hash";
                return null;
            }
            String rawDid = Hex.toHex(rawHash);

            Hat rawHat = new Hat();
            rawHat.setId(rawDid);
            rawHat.setName(fileName);
            rawHat.setSize(fileSize);
            rawHat.setBorn(System.currentTimeMillis());
            rawHat.setLast(System.currentTimeMillis());
            rawHat.setState(Hat.DataState.ACTIVE);

            String mimeType = guessMimeType(fileName);
            if (mimeType != null) {
                rawHat.setTypes(Collections.singletonList(mimeType));
            }

            if (desc != null && !desc.isEmpty()) {
                rawHat.setDesc(desc);
            } else {
                rawHat.setDesc("Shared in " + imType.name().toLowerCase() + " chat");
            }

            hatManager.addHat(rawHat);

            // Build the message now, before the (slow) upload, so the UI can show the
            // bubble immediately and render upload progress on it (keyed by rawDid).
            // Content is refreshed with the finalized HAT (symkey + locas) after upload.
            ImMessage msg = ImMessage.createHat(imType, senderId, targetId, rawHat);
            if (onPrepared != null) {
                onPrepared.onPrepared(msg, rawDid, fileSize);
            }

            DiskItem diskItem = dataSyncManager.uploadData(file, rawHat, false, null, progress);

            if (diskItem == null) {
                // DataSyncManager already returns a descriptive, localized error
                // (e.g. "上传到磁盘失败：…"), so don't wrap it with another prefix.
                lastError = dataSyncManager.getLastError();
                TimberLogger.e(TAG, lastError);
                return null;
            }

            Hat updatedRawHat = hatManager.getHatById(rawDid);
            if (updatedRawHat != null) {
                rawHat = updatedRawHat;
            }

            // Set plaintext symkey so receivers can decrypt
            byte[] symkey = dataSyncManager.getLastSymkey();
            if (symkey != null) {
                rawHat.setKey(Hex.toHex(symkey));
            }

            // Copy cipher HAT's DISK locations into rawHat so receivers know where to download
            List<String> cipherIds = rawHat.getCipherIds();
            if (cipherIds != null) {
                for (String cipherId : cipherIds) {
                    Hat cipherHat = hatManager.getHatById(cipherId);
                    if (cipherHat != null && cipherHat.getLocas() != null) {
                        List<String> rawLocas = rawHat.getLocas();
                        if (rawLocas == null) {
                            rawLocas = new java.util.ArrayList<>();
                        }
                        for (String loca : cipherHat.getLocas()) {
                            if (loca != null && !rawLocas.contains(loca)) {
                                rawLocas.add(loca);
                            }
                        }
                        rawHat.setLocas(rawLocas);
                    }
                }
            }

            // Refresh the message content with the finalized HAT (symkey + locas).
            msg.setContent(rawHat.toJson());
            return msg;

        } catch (Exception e) {
            lastError = "HAT share failed: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String resolveFileName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name != null ? name : "file";
    }

    private static String guessMimeType(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    private static String sanitize(String name) {
        if (name == null) return "file";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Returns a type icon resource ID based on MIME type or file extension.
     */
    public static int getTypeIconRes(Hat hat) {
        List<String> types = hat.getTypes();
        String mimeType = (types != null && !types.isEmpty()) ? types.get(0) : null;

        if (mimeType == null && hat.getName() != null) {
            mimeType = guessMimeType(hat.getName());
        }
        if (mimeType == null) return android.R.drawable.ic_menu_save;

        if (mimeType.startsWith("image/")) return android.R.drawable.ic_menu_gallery;
        if (mimeType.startsWith("audio/")) return android.R.drawable.ic_lock_silent_mode_off;
        if (mimeType.startsWith("video/")) return R.drawable.ic_video_file;
        if (mimeType.equals("application/json") || mimeType.equals("text/json")) return R.drawable.ic_json;
        if (mimeType.startsWith("text/")) return android.R.drawable.ic_menu_edit;
        return android.R.drawable.ic_menu_save;
    }

    /**
     * Formats byte size to human-readable string.
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
