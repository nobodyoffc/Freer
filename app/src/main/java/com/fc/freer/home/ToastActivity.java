package com.fc.freer.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ToastManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ToastActivity extends BaseCryptoActivity {
    private static final String TAG = "ToastActivity";
    
    private LinearLayout toastListContainer;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ImageButton clearButton;
    private ImageButton copySelectedButton;
    private TextView emptyStateTextView;
    
    private ToastManager toastManager;
    private SimpleDateFormat dateFormat;
    private boolean isSelectionMode = false;
    private final List<CardEntry> cardEntries = new ArrayList<>();

    private static class CardEntry {
        final View cardView;
        final CheckBox checkBox;
        final String message;

        CardEntry(View cardView, CheckBox checkBox, String message) {
            this.cardView = cardView;
            this.checkBox = checkBox;
            this.message = message;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        
        initData();
        loadToastMessages();
    }
    
    private void initViews() {
        toastListContainer = findViewById(R.id.toast_list_container);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        clearButton = findViewById(R.id.clear_button);
        copySelectedButton = findViewById(R.id.copy_selected_button);
        emptyStateTextView = findViewById(R.id.empty_state_text);
        
        swipeRefreshLayout.setOnRefreshListener(this::loadToastMessages);
        clearButton.setOnClickListener(v -> clearAllMessages());
        copySelectedButton.setOnClickListener(v -> copySelectedMessages());
    }
    
    private void initData() {
        toastManager = ToastManager.getInstance();
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    }
    
    private void loadToastMessages() {
        try {
            swipeRefreshLayout.setRefreshing(true);
            
            List<ToastManager.ToastMessage> messages = toastManager.getToastMessages();
            
            toastListContainer.removeAllViews();
            cardEntries.clear();
            
            if (messages.isEmpty()) {
                showEmptyState();
            } else {
                hideEmptyState();
                for (ToastManager.ToastMessage message : messages) {
                    addMessageCard(message);
                }
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading toast messages: " + e.getMessage(), e);
        } finally {
            swipeRefreshLayout.setRefreshing(false);
        }
    }
    
    private void addMessageCard(ToastManager.ToastMessage message) {
        View cardView = getLayoutInflater().inflate(R.layout.item_toast_message, toastListContainer, false);

        TextView messageTextView = cardView.findViewById(R.id.message_text);
        TextView timestampTextView = cardView.findViewById(R.id.timestamp_text);
        TextView levelTextView = cardView.findViewById(R.id.level_text);
        CheckBox checkBox = cardView.findViewById(R.id.card_checkbox);

        messageTextView.setText(message.getMessage());
        timestampTextView.setText(dateFormat.format(new Date(message.getTimestamp())));
        levelTextView.setText(message.getLevel());

        int levelColor = getLevelColor(message.getLevel());
        levelTextView.setTextColor(levelColor);

        if (isSelectionMode) {
            checkBox.setVisibility(View.VISIBLE);
        }

        CardEntry entry = new CardEntry(cardView, checkBox, message.getMessage());
        cardEntries.add(entry);

        cardView.setOnClickListener(v -> {
            if (isSelectionMode) {
                checkBox.setChecked(!checkBox.isChecked());
                updateCopyButtonVisibility();
            } else {
                copyToClipboard(message.getMessage());
            }
        });

        cardView.setOnLongClickListener(v -> {
            if (!isSelectionMode) {
                enterSelectionMode();
                checkBox.setChecked(true);
                updateCopyButtonVisibility();
            }
            return true;
        });

        toastListContainer.addView(cardView);
    }
    
    private int getLevelColor(String level) {
        switch (level.toUpperCase()) {
            case "ERROR":
                return getColor(R.color.error);
            case "WARNING":
                return getColor(R.color.warning);
            case "INFO":
            default:
                return getColor(R.color.info);
        }
    }
    
    private void clearAllMessages() {
        try {
            exitSelectionMode();
            toastManager.clearToastMessages();
            loadToastMessages();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error clearing toast messages: " + e.getMessage(), e);
        }
    }

    private void enterSelectionMode() {
        isSelectionMode = true;
        for (CardEntry entry : cardEntries) {
            entry.checkBox.setVisibility(View.VISIBLE);
        }
        updateCopyButtonVisibility();
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        for (CardEntry entry : cardEntries) {
            entry.checkBox.setChecked(false);
            entry.checkBox.setVisibility(View.GONE);
        }
        copySelectedButton.setVisibility(View.GONE);
    }

    private void updateCopyButtonVisibility() {
        boolean anyChecked = false;
        for (CardEntry entry : cardEntries) {
            if (entry.checkBox.isChecked()) {
                anyChecked = true;
                break;
            }
        }
        copySelectedButton.setVisibility(anyChecked ? View.VISIBLE : View.GONE);
    }

    private void copySelectedMessages() {
        StringBuilder sb = new StringBuilder();
        for (CardEntry entry : cardEntries) {
            if (entry.checkBox.isChecked()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(entry.message);
            }
        }
        if (sb.length() > 0) {
            copyToClipboard(sb.toString());
            exitSelectionMode();
        }
    }
    
    private void showEmptyState() {
        emptyStateTextView.setVisibility(View.VISIBLE);
        clearButton.setEnabled(false);
    }
    
    private void hideEmptyState() {
        emptyStateTextView.setVisibility(View.GONE);
        clearButton.setEnabled(true);
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = ClipData.newPlainText("Toast Message", text);
            clipboardManager.setPrimaryClip(clipData);
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error copying to clipboard: " + e.getMessage(), e);
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected int getLayoutId() {
        return R.layout.activity_toast;
    }
    
    @Override
    protected String getActivityTitle() {
        return getString(R.string.app_messages);
    }
    
    @Override
    protected void initializeViews() {
        initViews();
    }
    
    @Override
    protected void setupButtons() {
        // Button setup is already done in initViews()
    }
    
    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // ToastActivity doesn't use QR scanning functionality
    }
}