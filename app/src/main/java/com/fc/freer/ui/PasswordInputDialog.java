package com.fc.freer.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.fc.freer.R;

public class PasswordInputDialog extends Dialog {

    public interface OnPasswordInputListener {
        void onConfirm(String password);
        void onCancel();
    }

    public PasswordInputDialog(Context context, String prompt, String hint, OnPasswordInputListener listener) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_password_input);
        setCancelable(true);

        TextView promptTextView = findViewById(R.id.promptTextView);
        TextInputLayout textInputLayout = findViewById(R.id.textInputLayout);
        TextInputEditText textInputEditText = findViewById(R.id.textInputEditText);
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

        // Set up button listeners
        clearButton.setOnClickListener(v -> {
            textInputEditText.setText("");
        });

        cancelButton.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onCancel();
        });

        doneButton.setOnClickListener(v -> {
            String password = textInputEditText.getText() != null ? textInputEditText.getText().toString() : "";
            dismiss();
            if (listener != null) listener.onConfirm(password);
        });

        // Handle dialog cancel
        setOnCancelListener(dialog -> {
            if (listener != null) listener.onCancel();
        });
    }
}
