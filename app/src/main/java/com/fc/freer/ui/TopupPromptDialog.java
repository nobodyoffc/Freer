package com.fc.freer.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.im.NobodyBoard;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Dialog to prompt user to get FCH when their balance is zero
 */
public class TopupPromptDialog {
    private static final String TAG = "TopupPromptDialog";

    /** One-shot guard: a FID may post to the board only once. */
    private static final String ASK_PREFS = "first_fch_board";
    private static final String KEY_ASKED = "asked_";

    private final Context context;
    private final String fid;
    private final OnIgnoreListener ignoreListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;

    /**
     * Listener for when user clicks ignore button
     */
    public interface OnIgnoreListener {
        void onIgnore();
    }

    /**
     * Create a topup prompt dialog
     *
     * @param context The context
     * @param fid The FID to display
     * @param ignoreListener Listener for ignore button
     */
    public TopupPromptDialog(Context context, String fid, OnIgnoreListener ignoreListener) {
        this.context = context;
        this.fid = fid;
        this.ignoreListener = ignoreListener;
    }

    /**
     * Show the dialog
     */
    public void show() {
        try {
            if (context instanceof android.app.Activity activity
                    && (activity.isFinishing() || activity.isDestroyed())) {
                TimberLogger.w(TAG, "Activity no longer valid, cannot show dialog");
                return;
            }
            // Inflate the dialog layout
            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.dialog_topup_prompt, null);

            // Find views
            TextView fidTextView = dialogView.findViewById(R.id.topupFidText);
            ImageView qrCodeImageView = dialogView.findViewById(R.id.topupQrCode);
            Button ignoreButton = dialogView.findViewById(R.id.topupIgnoreButton);
            Button copyButton = dialogView.findViewById(R.id.topupCopyButton);
            Button okButton = dialogView.findViewById(R.id.topupOkButton);
            Button askBoardButton = dialogView.findViewById(R.id.topupAskBoardButton);

            // Set FID text
            if (fidTextView != null) {
                fidTextView.setText(fid);
            }

            // Generate and set QR code
            if (qrCodeImageView != null) {
                try {
                    Bitmap qrBitmap = generateQRCodeBitmap(fid, 300, 300);
                    if (qrBitmap != null) {
                        qrCodeImageView.setImageBitmap(qrBitmap);
                    } else {
                        TimberLogger.w(TAG, "Failed to generate QR code bitmap");
                    }
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error generating QR code: " + e.getMessage(), e);
                }
            }

            // Create the dialog
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.get_some_fch);
            builder.setView(dialogView);
            builder.setCancelable(true);

            dialog = builder.create();

            // Set up button listeners
            if (ignoreButton != null) {
                ignoreButton.setOnClickListener(v -> {
                    if (ignoreListener != null) {
                        ignoreListener.onIgnore();
                    }
                    dismiss();
                });
            }

            if (copyButton != null) {
                copyButton.setOnClickListener(v -> {
                    copyFidToClipboard();
                });
            }

            if (okButton != null) {
                okButton.setOnClickListener(v -> {
                    dismiss();
                });
            }

            if (askBoardButton != null) {
                // Zero-balance users can still post a request on the public
                // first-FCH board (the default nobody freer's DOCK inbox). The
                // request is posted directly — no chat, and each FID may ask once.
                askBoardButton.setOnClickListener(v -> {
                    if (hasAlreadyAsked()) {
                        showPostedMessage();
                    } else {
                        showAskBoardConfirm();
                    }
                });
            }

            // Show the dialog
            dialog.show();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing topup prompt dialog: " + e.getMessage(), e);
            ToastUtils.showError(context, context.getString(R.string.toast_error_showing_dialog, e.getMessage()));
        }
    }

    // ── First-FCH board ──────────────────────────────────────────────────

    private SharedPreferences askPrefs() {
        return context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE);
    }

    private boolean hasAlreadyAsked() {
        return askPrefs().getBoolean(KEY_ASKED + fid, false);
    }

    private void markAsked() {
        askPrefs().edit().putBoolean(KEY_ASKED + fid, true).apply();
    }

    /**
     * Resolve (creating if necessary) the current identity's ImManager. Built
     * lazily at tap time — on a brand-new FID the manager may not exist yet when
     * this dialog is first shown, so capturing it at construction would be null.
     * Call off the UI thread: creation opens a DB and starts the transport.
     */
    private ImManager resolveImManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return null;
        ImManager existing = setting.getImManager();
        if (existing != null) return existing;
        FapiClient fapiClient = null;
        ApiCenter apiCenter = ApiCenter.getInstance();
        if (apiCenter != null) {
            fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
        }
        return setting.getOrCreateImManager(context.getApplicationContext(), fapiClient);
    }

    /**
     * Confirm dialog shown before posting: a single safety line and an optional
     * note. Tapping send posts the request directly to the board.
     */
    private void showAskBoardConfirm() {
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20, context.getResources().getDisplayMetrics());

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, 0);

        TextView notice = new TextView(context);
        notice.setText(R.string.first_fch_safety_notice);
        layout.addView(notice);

        EditText noteInput = new EditText(context);
        noteInput.setHint(R.string.first_fch_note_hint);
        noteInput.setSingleLine(true);
        noteInput.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(NobodyBoard.REQUEST_NOTE_MAX_CHARS)});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        noteInput.setLayoutParams(lp);
        layout.addView(noteInput);

        new AlertDialog.Builder(context)
                .setTitle(R.string.first_fch_confirm_title)
                .setView(layout)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.send, (d, which) ->
                        postBoardRequest(noteInput.getText().toString()))
                .show();
    }

    private void postBoardRequest(String note) {
        // Resolve/create the ImManager off the UI thread (it may open a DB and
        // start the transport); the send callback returns on the main thread.
        new Thread(() -> {
            ImManager im = resolveImManager();
            if (im == null) {
                mainHandler.post(() ->
                        ToastUtils.makeText(context, R.string.im_manager_init_failed));
                return;
            }
            im.sendFirstFchBoardRequest(note, success -> {
                if (success) {
                    markAsked();
                    showPostedMessage();
                } else {
                    ToastUtils.makeText(context, R.string.first_fch_send_failed);
                }
            });
        }).start();
    }

    /**
     * Confirmation shown after a successful post (and whenever the user asks
     * again from the same FID): the request is on the board, now wait for
     * someone to send FCH — or ask people you know to send to this FID.
     */
    private void showPostedMessage() {
        if (context instanceof android.app.Activity activity
                && (activity.isFinishing() || activity.isDestroyed())) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.first_fch_posted_title)
                .setMessage(context.getString(R.string.first_fch_posted_message, fid))
                .setNeutralButton(R.string.copy, (d, which) -> copyFidToClipboard())
                .setPositiveButton(R.string.ok, (d, which) -> dismiss())
                .show();
    }

    /**
     * Dismiss the dialog
     */
    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /**
     * Copy FID to clipboard
     */
    private void copyFidToClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("FID", fid);
            clipboard.setPrimaryClip(clip);
            ToastUtils.makeText(context, R.string.copied);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error copying FID to clipboard: " + e.getMessage(), e);
            ToastUtils.showError(context, context.getString(R.string.toast_error_copying_fid));
        }
    }

    /**
     * Generate a QR code bitmap from text
     *
     * @param content The content to encode
     * @param width The width of the QR code
     * @param height The height of the QR code
     * @return The QR code bitmap, or null if generation failed
     */
    private Bitmap generateQRCodeBitmap(String content, int width, int height) {
        try {
            // Set up QR code hints
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            // Generate the QR code matrix
            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                width,
                height,
                hints
            );

            // Convert to bitmap
            return createBitmapFromBitMatrix(bitMatrix);

        } catch (WriterException e) {
            TimberLogger.e(TAG, "Error encoding QR code: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert a BitMatrix to a Bitmap
     *
     * @param bitMatrix The BitMatrix to convert
     * @return The bitmap
     */
    private Bitmap createBitmapFromBitMatrix(BitMatrix bitMatrix) {
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        int[] pixels = new int[width * height];

        // Convert bit matrix to pixel array
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }

        // Create the bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        return bitmap;
    }
}
