package com.fc.freer.ui;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.fc.freer.R;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.utils.ToastUtils;

/**
 * Dialog to display decrypted secret content with options to make QR code, copy to clipboard, or close
 */
public class SecretContentDialog {
    private final String content;
    private final String title;
    private final Context context;
    private boolean isContentVisible = false;

    public SecretContentDialog(@NonNull Context context, String title, String content) {
        this.context = context;
        this.title = title;
        this.content = content;
    }

    public void show() {
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_secret_content, null);

        // Create AlertDialog using Builder
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(false)
                .create();

        // Set title
        TextView dialogTitle = view.findViewById(R.id.dialog_title);
        if (title != null && !title.isEmpty()) {
            dialogTitle.setText(title);
        }

        // Set content (initially masked)
        TextView contentText = view.findViewById(R.id.content_text);
        String maskedContent = content != null ? "*".repeat(content.length()) : "";
        contentText.setText(maskedContent);

        // Set up buttons
        Button btnMakeQr = view.findViewById(R.id.btn_make_qr);
        Button btnCopy = view.findViewById(R.id.btn_copy);
        Button btnOk = view.findViewById(R.id.btn_ok);
        ImageButton btnVisibilityToggle = view.findViewById(R.id.btn_visibility_toggle);

        // Set up visibility toggle
        btnVisibilityToggle.setOnClickListener(v -> {
            isContentVisible = !isContentVisible;
            if (isContentVisible) {
                contentText.setText(content != null ? content : "");
                btnVisibilityToggle.setImageResource(R.drawable.ic_visibility_on);
            } else {
                String maskedText = content != null ? "*".repeat(content.length()) : "";
                contentText.setText(maskedText);
                btnVisibilityToggle.setImageResource(R.drawable.ic_visibility_off);
            }
        });

        btnMakeQr.setOnClickListener(v -> {
            if (content != null && !content.isEmpty()) {
                QRCodeGenerator.generateAndShowQRCode(context, content, title != null ? title : "Secret Content");
            } else {
                ToastUtils.makeText(context, R.string.error_creating_qr);
            }
        });

        btnCopy.setOnClickListener(v -> {
            if (content != null && !content.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Secret Content", content);
                clipboard.setPrimaryClip(clip);
                ToastUtils.makeText(context, context.getString(R.string.toast_content_copied));
            }
        });

        btnOk.setOnClickListener(v -> dialog.dismiss());

        // Show the dialog
        DialogUtils.show(dialog);
    }
}