package com.fc.freer.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
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

    private final Context context;
    private final String fid;
    private final OnIgnoreListener ignoreListener;
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
                // Zero-balance users can still post a send-only request on the
                // public first-FCH board (the default nobody freer's DOCK inbox).
                askBoardButton.setOnClickListener(v -> {
                    android.content.Intent intent = new android.content.Intent(
                            context, com.fc.freer.im.ChatActivity.class);
                    intent.putExtra(com.fc.freer.im.ChatActivity.EXTRA_TYPE,
                            com.fc.fc_ajdk.data.fcData.ImType.P2P.name());
                    intent.putExtra(com.fc.freer.im.ChatActivity.EXTRA_TARGET_ID,
                            com.fc.freer.im.NobodyBoard.DEFAULT_NOBODY_FID);
                    intent.putExtra(com.fc.freer.im.ChatActivity.EXTRA_DISPLAY_NAME,
                            context.getString(R.string.first_fch_board_name));
                    context.startActivity(intent);
                    dismiss();
                });
            }

            // Show the dialog
            dialog.show();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing topup prompt dialog: " + e.getMessage(), e);
            ToastUtils.showError(context, context.getString(R.string.toast_error_showing_dialog, e.getMessage()));
        }
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
