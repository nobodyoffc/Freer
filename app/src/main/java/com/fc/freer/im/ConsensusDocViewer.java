package com.fc.freer.im;

import android.app.Activity;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders a team's consensus document for reading.
 * <p>
 * Shared by every screen that asks a user to weigh a consensus before signing it on chain:
 * joining a team, reviewing a document before publishing it, and re-signing after the owner
 * changed it. Documents are plain text; anything containing NUL bytes is reported as
 * non-text rather than rendered as mojibake.
 */
public final class ConsensusDocViewer {
    private static final String TAG = "ConsensusDocViewer";

    private ConsensusDocViewer() {}

    /** Called when the reader accepts the document shown. */
    public interface OnAccept {
        void onAccept();
    }

    /**
     * Resolve a consensus document and show it, trying the cheapest source first.
     * <p>
     * Order: the local data store, then each DISK in {@code diskSids} in turn. Reading a document
     * the owner is already holding should not cost a network round trip, and a team mid-move has
     * its consensus on either the DISK it is leaving or the one it is joining — so the caller
     * passes both and the first that answers wins. Downloaded bytes are verified against
     * {@code consensusId} before being shown.
     *
     * @param diskSids  DISKs to try, in order; nulls and repeats are skipped
     * @param acceptLabel positive button label, or 0 for a read-only dialog with just a close button
     */
    public static void fetchAndShow(Activity activity, HatManager hatManager, String title,
                                    String consensusId, List<String> diskSids,
                                    int acceptLabel, OnAccept onAccept) {
        if (consensusId == null || consensusId.isEmpty()) {
            ToastUtils.makeText(activity, activity.getString(R.string.no_consensus_doc));
            return;
        }
        new Thread(() -> {
            // The local store file belongs to the data store — read it, never delete it.
            File local = ConsensusDocHelper.resolveLocalDoc(activity, hatManager, consensusId);
            if (local != null) {
                try {
                    final String text = readAsText(local);
                    activity.runOnUiThread(() -> show(activity, title, text, acceptLabel, onAccept));
                    return;
                } catch (IOException e) {
                    TimberLogger.w(TAG, "Local consensus unreadable, falling back to DISK: %s",
                            e.getMessage());
                }
            }

            activity.runOnUiThread(() -> ToastUtils.makeText(activity,
                    activity.getString(R.string.downloading_consensus_document)));

            ConsensusDocHelper helper = new ConsensusDocHelper(activity, hatManager);
            Set<String> tried = new LinkedHashSet<>();
            if (diskSids != null) {
                for (String sid : diskSids) {
                    if (sid != null && !sid.isEmpty()) tried.add(sid);
                }
            }
            if (tried.isEmpty()) {
                // Not on this device and nowhere to look: usually a team whose home carries no
                // DISK entry. Say which it is, rather than reporting a download that never ran.
                activity.runOnUiThread(() -> ToastUtils.makeText(activity,
                        activity.getString(R.string.failed_to_download_consensus_document)
                                + ": " + activity.getString(R.string.consensus_no_disk_to_read)));
                return;
            }

            String lastError = null;
            for (String sid : tried) {
                File out = null;
                try {
                    out = File.createTempFile("consensus_view_", ".tmp", activity.getCacheDir());
                    if (helper.downloadDoc(consensusId, sid, out)) {
                        final String text = readAsText(out);
                        activity.runOnUiThread(() -> show(activity, title, text, acceptLabel, onAccept));
                        return;
                    }
                    lastError = helper.getLastError();
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Consensus review failed: %s", e.getMessage());
                    lastError = e.getMessage();
                } finally {
                    if (out != null && out.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                }
            }

            final String err = lastError;
            activity.runOnUiThread(() -> ToastUtils.makeText(activity,
                    activity.getString(R.string.failed_to_download_consensus_document)
                            + (err != null ? ": " + err : "")));
        }).start();
    }

    /** Show a local document file; used to preview a document before it is uploaded. */
    public static void showFile(Activity activity, String title, File file,
                                int acceptLabel, OnAccept onAccept) {
        try {
            show(activity, title, readAsText(file), acceptLabel, onAccept);
        } catch (IOException e) {
            TimberLogger.e(TAG, "Failed to read consensus file: %s", e.getMessage());
            ToastUtils.makeText(activity, activity.getString(R.string.failed_to_read_consensus_document));
        }
    }

    /** Show already-decoded text. Must be called on the UI thread. */
    public static void show(Activity activity, String title, String text,
                            int acceptLabel, OnAccept onAccept) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        float d = activity.getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(activity);
        TextView body = new TextView(activity);
        int pad = (int) (16 * d);
        body.setPadding(pad, pad, pad, pad);
        body.setTextIsSelectable(true);
        body.setText(text != null ? text : activity.getString(R.string.consensus_not_text));
        scroll.addView(body);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title != null ? title : activity.getString(R.string.consensus_document))
                .setView(scroll);

        if (acceptLabel != 0 && onAccept != null) {
            builder.setPositiveButton(acceptLabel, (dlg, w) -> onAccept.onAccept())
                    .setNegativeButton(R.string.cancel, null);
        } else {
            builder.setPositiveButton(R.string.close, null);
        }
        DialogUtils.show(builder);
    }

    /** Read a file as UTF-8 text, or null when the bytes look binary. */
    public static String readAsText(File file) throws IOException {
        if (file == null || !file.exists()) return null;
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int total = 0;
            while (total < data.length) {
                int r = fis.read(data, total, data.length - total);
                if (r == -1) break;
                total += r;
            }
        }
        for (byte b : data) {
            if (b == 0) return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
