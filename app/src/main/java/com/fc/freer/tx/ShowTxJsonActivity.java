package com.fc.freer.tx;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;

public class ShowTxJsonActivity extends BaseCryptoActivity {
    private static final String TAG = "ShowTxJsonActivity";
    public static final String EXTRA_TX_INFO_JSON = "extra_tx_info_json";

    private LinearLayout jsonDisplayContainer;
    private LinearLayout buttonContainer;
    private TextView jsonDisplay;
    private ImageButton qrCodeButton;
    private ImageButton copyButton;
    private String txInfoJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        TimberLogger.i(TAG, "onCreate started");
    }

    private void initViews() {
        jsonDisplayContainer = findViewById(R.id.jsonDisplayContainer);
        buttonContainer = findViewById(R.id.buttonContainer);
        
        jsonDisplay = jsonDisplayContainer.findViewById(R.id.jsonDisplay);
        
        qrCodeButton = findViewById(R.id.qrCodeButton);
        copyButton = findViewById(R.id.copyButton);

        // Get JSON data from intent
        txInfoJson = getIntent().getStringExtra(EXTRA_TX_INFO_JSON);
        if (txInfoJson != null) {
            jsonDisplay.setText(txInfoJson);
        }
    }

    private void setupListeners() {
        qrCodeButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            
            if (txInfoJson != null && !txInfoJson.isEmpty()) {
                handleQrGeneration(txInfoJson);
            } else {
                showToast(getString(R.string.no_content_to_generate_qr_code));
            }
        });

        copyButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            
            if (txInfoJson != null && !txInfoJson.isEmpty()) {
                copyToClipboard(txInfoJson, "tx_info_json");
            } else {
                showToast(getString(R.string.nothing_to_copy));
            }
        });

    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_show_tx_json;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.show_tx_json);
    }

    @Override
    protected void initializeViews() {
        initViews();
    }

    @Override
    protected void setupButtons() {
        setupListeners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    public static Intent createIntent(Context context, String txInfoJson) {
        Intent intent = new Intent(context, ShowTxJsonActivity.class);
        intent.putExtra(EXTRA_TX_INFO_JSON, txInfoJson);
        return intent;
    }
}
