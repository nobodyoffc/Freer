package com.fc.freer.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ImageButton;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.fc.freer.R;

public class StringInputDialog extends Dialog {

    public interface OnInputListener {
        void onConfirm(String input);
        void onCancel();
    }

    public StringInputDialog(Context context, String prompt, String hint, String initialText, OnInputListener listener) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_string_input);
        setCancelable(true);

        TextView promptTextView = findViewById(R.id.promptTextView);
        TextInputLayout textInputLayout = findViewById(R.id.textInputLayout);
        TextInputEditText textInputEditText = findViewById(R.id.textInputEditText);
        ImageButton scanQrButton = findViewById(R.id.scanQrButton);
        Button clearButton = findViewById(R.id.clearButton);
        Button cancelButton = findViewById(R.id.cancelButton);
        Button doneButton = findViewById(R.id.doneButton);

        // Set prompt text
        if (prompt != null && !prompt.trim().isEmpty()) {
            promptTextView.setText(prompt);
            promptTextView.setVisibility(View.VISIBLE);
        } else {
            promptTextView.setVisibility(View.GONE);
        }

        // Set hint
        if (hint != null && !hint.trim().isEmpty()) {
            textInputLayout.setHint(hint);
        }

        // Set initial text if provided
        if (initialText != null) {
            textInputEditText.setText(initialText);
            textInputEditText.setSelection(initialText.length()); // Move cursor to end
        }

        // Hide scan button for label input (not needed)
        scanQrButton.setVisibility(View.GONE);

        // Set up button listeners
        clearButton.setOnClickListener(v -> {
            textInputEditText.setText("");
        });

        cancelButton.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onCancel();
        });

        doneButton.setOnClickListener(v -> {
            String input = textInputEditText.getText() != null ? textInputEditText.getText().toString().trim() : "";
            dismiss();
            if (listener != null) listener.onConfirm(input);
        });

        // Handle dialog cancel
        setOnCancelListener(dialog -> {
            if (listener != null) listener.onCancel();
        });
    }
}