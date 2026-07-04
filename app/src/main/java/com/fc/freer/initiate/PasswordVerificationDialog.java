package com.fc.freer.initiate;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;

import com.fc.freer.utils.ToastUtils;

import androidx.annotation.NonNull;

import com.fc.freer.R;

public class PasswordVerificationDialog extends Dialog {
    private final PasswordVerificationListener listener;
    private EditText passwordInput;

    public interface PasswordVerificationListener {
        void onPasswordVerified(byte[] passwordBytes);
        void onVerificationCancelled();
    }

    public PasswordVerificationDialog(@NonNull Context context, PasswordVerificationListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_password_verification);

        passwordInput = findViewById(R.id.passwordInput);
        Button verifyButton = findViewById(R.id.verifyButton);
        Button cancelButton = findViewById(R.id.cancelButton);

        verifyButton.setOnClickListener(v -> {
            String password = passwordInput.getText().toString();
            if (password.isEmpty()) {
                ToastUtils.makeText(getContext(), getContext().getString(R.string.toast_please_enter_password));
                return;
            }
            byte[] passwordBytes = password.getBytes();
            listener.onPasswordVerified(passwordBytes);
            dismiss();
        });

        cancelButton.setOnClickListener(v -> {
            listener.onVerificationCancelled();
            dismiss();
        });
    }
} 