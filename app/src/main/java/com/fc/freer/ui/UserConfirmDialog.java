package com.fc.freer.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ImageView;

import com.fc.freer.R;

public class UserConfirmDialog extends Dialog {
    public enum Choice {
        STOP, NO, YES
    }

    public interface OnChoiceListener {
        void onChoice(Choice choice);
    }

    public UserConfirmDialog(Context context, String title, String prompt, OnChoiceListener listener) {
        this(context, title, prompt, listener, false);
    }

    public UserConfirmDialog(Context context, String title, String prompt, OnChoiceListener listener, boolean isWarning) {
        this(context, title, prompt, null, null, listener, isWarning);
    }

    public UserConfirmDialog(Context context, String title, String prompt, String yesButtonText, String noButtonText, OnChoiceListener listener) {
        this(context, title, prompt, yesButtonText, noButtonText, listener, false);
    }

    public UserConfirmDialog(Context context, String title, String prompt, String yesButtonText, String noButtonText, OnChoiceListener listener, boolean isWarning) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_user_confirm);
        setCancelable(false);

        TextView titleTextView = findViewById(R.id.titleTextView);
        TextView promptTextView = findViewById(R.id.promptTextView);
        Button stopButton = findViewById(R.id.stopButton);
        Button noButton = findViewById(R.id.noButton);
        Button yesButton = findViewById(R.id.yesButton);
        ImageView warningIcon = findViewById(R.id.warningIcon);

        if (title != null && !title.trim().isEmpty()) {
            titleTextView.setText(title);
            titleTextView.setVisibility(View.VISIBLE);
        } else {
            titleTextView.setVisibility(View.GONE);
        }

        promptTextView.setText(prompt);
        warningIcon.setVisibility(isWarning ? View.VISIBLE : View.GONE);

        // Set custom button text if provided
        if (yesButtonText != null && !yesButtonText.trim().isEmpty()) {
            yesButton.setText(yesButtonText);
        }
        if (noButtonText != null && !noButtonText.trim().isEmpty()) {
            noButton.setText(noButtonText);
        }

        stopButton.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onChoice(Choice.STOP);
        });
        noButton.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onChoice(Choice.NO);
        });
        yesButton.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onChoice(Choice.YES);
        });
    }
} 