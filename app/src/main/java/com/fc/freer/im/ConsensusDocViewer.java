package com.fc.freer.im;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.data.FileTypeHandler;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders a team's consensus document for reading.
 * <p>
 * Shared by every screen that shows a member what their team agreed to: joining a team,
 * reviewing a document before publishing it, re-signing after the owner changed it, and the
 * team menu's plain read.
 * <p>
 * A consensus is not always prose. Nothing on chain says what a {@code consensusId} points at,
 * and owners on other clients carve PDFs and word processor files. Bytes that are not text are
 * not a failure: they are fetched, verified against the id, kept, and handed over as a file
 * (see {@link ConsensusDocFormat}).
 */
public final class ConsensusDocViewer {
    private static final String TAG = "ConsensusDocViewer";

    /**
     * Past this, a document is handed over as a file rather than poured into a dialog. Prose
     * consensus documents are a page or two; anything of this size is a publication.
     */
    private static final long MAX_TEXT_BYTES = 4L * 1024 * 1024;

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
     * {@code consensusId} before being shown, and kept afterwards.
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
                    final Doc doc = read(local, consensusId);
                    activity.runOnUiThread(() -> present(activity, title, doc, acceptLabel, onAccept));
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
                        Doc doc = read(out, consensusId);
                        if (doc.text == null) {
                            // Verified bytes the reader cannot render are still the team's
                            // document: keep them in the data store and hand over the file.
                            File kept = helper.adoptDownloadedDoc(activity, out, consensusId,
                                    doc.fileName(), ConsensusDocFormat.mimeTypeOf(doc.kind));
                            if (kept != null) {
                                out = null;  // Moved into the store; not ours to delete any more.
                                doc = doc.at(kept);
                            } else {
                                TimberLogger.w(TAG, "Could not keep the consensus document: %s",
                                        helper.getLastError());
                                doc = doc.at(null);
                            }
                        }
                        final Doc shown = doc;
                        activity.runOnUiThread(() -> present(activity, title, shown, acceptLabel, onAccept));
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
            present(activity, title, read(file, null), acceptLabel, onAccept);
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

        addDecision(builder, acceptLabel, onAccept);
        DialogUtils.show(builder);
    }

    // ── The document, once read ─────────────────────────────────────────

    /**
     * A consensus document as it came back: the file holding the verified bytes, their size,
     * the prose if prose is what they are, and what they look like if not.
     * <p>
     * {@code text} being null is an answer, not a failure.
     */
    public static final class Doc {
        @Nullable final File file;
        final String did;
        final long size;
        @Nullable final String text;
        @Nullable final ConsensusDocFormat.Kind kind;
        /** Text this long is handed over as a file rather than rendered; see MAX_TEXT_BYTES. */
        final boolean tooLarge;

        Doc(@Nullable File file, String did, long size, @Nullable String text,
            @Nullable ConsensusDocFormat.Kind kind, boolean tooLarge) {
            this.file = file;
            this.did = did;
            this.size = size;
            this.text = text;
            this.kind = kind;
            this.tooLarge = tooLarge;
        }

        /** The same document, now held by (or no longer held in) another file. */
        Doc at(@Nullable File other) {
            return new Doc(other, did, size, text, kind, tooLarge);
        }

        String fileName() {
            return ConsensusDocFormat.fileName(did, kind);
        }
    }

    /**
     * Read a document and decide what it is. Must be called off the main thread.
     * <p>
     * The leading bytes settle the common cases without reading the whole file: anything the
     * sniffer recognises is a file to hand over, not text to decode.
     */
    static Doc read(File file, @Nullable String did) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("No document file");
        }
        long size = file.length();
        byte[] head = readBytes(file, (int) Math.min(size, 4096));
        ConsensusDocFormat.Kind kind = ConsensusDocFormat.sniff(head);
        if (kind != null || size > MAX_TEXT_BYTES) {
            return new Doc(file, did, size, null, kind, kind == null);
        }
        byte[] data = readBytes(file, (int) size);
        String text = ConsensusDocFormat.decodeText(data);
        return new Doc(file, did, size, text,
                text == null ? ConsensusDocFormat.sniff(data) : null, false);
    }

    private static byte[] readBytes(File file, int count) throws IOException {
        byte[] data = new byte[count];
        try (FileInputStream fis = new FileInputStream(file)) {
            int total = 0;
            while (total < count) {
                int r = fis.read(data, total, count - total);
                if (r == -1) break;
                total += r;
            }
            if (total < count) {
                byte[] shorter = new byte[total];
                System.arraycopy(data, 0, shorter, 0, total);
                return shorter;
            }
        }
        return data;
    }

    // ── Presentation ────────────────────────────────────────────────────

    /** Show prose as prose, and anything else as the file it is. Must be called on the UI thread. */
    private static void present(Activity activity, String title, Doc doc,
                                int acceptLabel, OnAccept onAccept) {
        if (doc.text != null) {
            show(activity, title, doc.text, acceptLabel, onAccept);
        } else {
            showBinary(activity, title, doc, acceptLabel, onAccept);
        }
    }

    /**
     * The document exists, was fetched and hashed correctly, and is not something this dialog can
     * render. Say what it is and hand it over — telling a member their consensus arrived and then
     * giving them nothing is the one answer worth avoiding here.
     */
    private static void showBinary(Activity activity, String title, Doc doc,
                                   int acceptLabel, OnAccept onAccept) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        View content = activity.getLayoutInflater().inflate(R.layout.dialog_consensus_file, null);
        TextView message = content.findViewById(R.id.consensusFileMessage);
        String size = FileShareHelper.formatSize(doc.size);
        message.setText(doc.tooLarge
                ? activity.getString(R.string.consensus_too_large_detail, size)
                : activity.getString(R.string.consensus_not_text_detail,
                        activity.getString(labelRes(doc.kind)), size));

        View actions = content.findViewById(R.id.consensusFileActions);
        if (doc.file == null) {
            actions.setVisibility(View.GONE);
        } else {
            content.findViewById(R.id.consensusOpenButton)
                    .setOnClickListener(v -> handOver(activity, doc, false));
            content.findViewById(R.id.consensusShareButton)
                    .setOnClickListener(v -> handOver(activity, doc, true));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title != null ? title : activity.getString(R.string.consensus_document))
                .setView(content);

        addDecision(builder, acceptLabel, onAccept);
        DialogUtils.show(builder);
    }

    /**
     * The dialog's own buttons: the caller's decision when there is one to make, and otherwise
     * just a way out. A document that cannot be rendered still has to be signable — the reader
     * opens it in another app and comes back to this dialog to answer.
     */
    private static void addDecision(AlertDialog.Builder builder, int acceptLabel, OnAccept onAccept) {
        if (acceptLabel != 0 && onAccept != null) {
            builder.setPositiveButton(acceptLabel, (dlg, w) -> onAccept.onAccept())
                    .setNegativeButton(R.string.cancel, null);
        } else {
            builder.setPositiveButton(R.string.close, null);
        }
    }

    /**
     * Give the file to another app, under a name it can make sense of. The copy is made off the
     * main thread; the store's own file is only ever read.
     */
    private static void handOver(Activity activity, Doc doc, boolean share) {
        final File source = doc.file;
        if (source == null) return;
        final String name = doc.fileName();
        final String mimeType = ConsensusDocFormat.mimeTypeOf(doc.kind);
        new Thread(() -> {
            File named;
            try {
                named = ConsensusDocHelper.namedCopy(activity, source, name);
            } catch (IOException e) {
                TimberLogger.e(TAG, "Failed to prepare the consensus file: %s", e.getMessage());
                activity.runOnUiThread(() -> ToastUtils.showError(activity,
                        activity.getString(R.string.error_opening_file)));
                return;
            }
            final File file = named;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Intent intent = share
                        ? FileTypeHandler.getShareIntent(activity, file, mimeType)
                        : FileTypeHandler.getViewIntent(activity, file, mimeType);
                if (intent == null) {
                    ToastUtils.showError(activity, activity.getString(R.string.no_app_to_open_file));
                    return;
                }
                // Launched rather than asked about first: from API 30 a package-visibility filter
                // can answer "nobody handles this" about an app that in fact does.
                try {
                    activity.startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    ToastUtils.showError(activity, activity.getString(R.string.no_app_to_open_file));
                }
            });
        }).start();
    }

    /** What to call a document that is not prose. Unrecognised bytes are still a document. */
    private static int labelRes(@Nullable ConsensusDocFormat.Kind kind) {
        if (kind == null) return R.string.consensus_kind_unknown;
        switch (kind) {
            case PDF: return R.string.consensus_kind_pdf;
            case DOC: return R.string.consensus_kind_doc;
            case RTF: return R.string.consensus_kind_rtf;
            case PNG: return R.string.consensus_kind_png;
            case JPEG: return R.string.consensus_kind_jpeg;
            case GIF: return R.string.consensus_kind_gif;
            case DOCX: return R.string.consensus_kind_docx;
            case XLSX: return R.string.consensus_kind_xlsx;
            case PPTX: return R.string.consensus_kind_pptx;
            case ODT: return R.string.consensus_kind_odt;
            case ZIP: return R.string.consensus_kind_zip;
            default: return R.string.consensus_kind_unknown;
        }
    }
}
