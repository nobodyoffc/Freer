package com.fc.freer.convert;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.QRCodeGenerator;

public class BroadcastActivity extends BaseCryptoActivity {
    private static final String TAG = "BroadcastActivity";

    protected EditText rawTxEditText;
    private LinearLayout buttonContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupListeners();

        // Check if raw transaction was passed from another activity
        String rawTxFromIntent = getIntent().getStringExtra("EXTRA_RAW_TX");
        if (rawTxFromIntent != null && !rawTxFromIntent.isEmpty()) {
            rawTxEditText.setText(rawTxFromIntent);
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_broadcast;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.broadcast_tx);
    }

    protected void initializeViews() {
        rawTxEditText = findViewById(R.id.keyView).findViewById(R.id.textInput);
        rawTxEditText.setHint(R.string.input_raw_tx);
        resultTextView = findViewById(R.id.resultView).findViewById(R.id.textBoxWithMakeQrLayout);
        resultTextView.setHint(R.string.result);

        clearButton = findViewById(R.id.clearButton);
        convertButton = findViewById(R.id.decodeButton);
        buttonContainer = findViewById(R.id.buttonContainer);

        setupIoIconsView(R.id.keyView, R.id.scanIcon, false, false, true, true,
                false, null, null, () -> pasteFromClipboard(rawTxEditText), null, () -> startQrScan(0));

        setupIoIconsView(R.id.resultView, R.id.makeQrIcon, true, false, false, false,
                false, () -> {
                String content = resultTextView.getText().toString();
                if (!TextUtils.isEmpty(content)) {
                    QRCodeGenerator.generateAndShowQRCode(this, content);
                } else {
                    showToast(getString(R.string.no_content_to_generate_qr_code));
                }
            }, null, null, null, null);
    }

    private void setupListeners() {
        // Set click listeners
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });
        convertButton.setOnClickListener(v -> {
            hideKeyboard();
            decodeTx();
        });

        // Add broadcast button listener
        findViewById(R.id.broadcastButton).setOnClickListener(v -> {
            hideKeyboard();
            broadcastTx();
        });
    }

    @Override
    protected void setupButtons() {
        setupButton(clearButton, v -> {
            hideKeyboard();
            clearInputs();
        });
        setupButton(convertButton, v -> {
            hideKeyboard();
            decodeTx();
        });
        setupButton(findViewById(R.id.broadcastButton), v -> {
            hideKeyboard();
            broadcastTx();
        });
    }

    private void clearInputs() {
        rawTxEditText.setText("");
        rawTxEditText.setEnabled(true);
        rawTxEditText.setTextColor(getResources().getColor(R.color.text, getTheme()));
        rawTxEditText.setTag(null);
        updateResultText(null);
    }

    private void decodeTx() {
        // Always clear the result first
        resultTextView.setText("");

        String rawTx = rawTxEditText.getText().toString();

        if (TextUtils.isEmpty(rawTx)) {
            showToast(getString(R.string.please_input_raw_tx));
            return;
        }

        try {
            TxHandler txHandler = new TxHandler();
            String decodedTx = txHandler.decodeTx(rawTx);

            if (decodedTx != null) {
                updateResultText(decodedTx);
            } else {
                showToast(getString(R.string.invalid_raw_tx_format));
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decoding TX: " + e.getMessage());
            showToast(getString(R.string.error_decoding_tx, e.getMessage()));
        }
    }

    private void broadcastTx() {
        String rawTx = rawTxEditText.getText().toString();

        if (TextUtils.isEmpty(rawTx)) {
            showToast(getString(R.string.please_input_raw_tx));
            return;
        }

        // Validate raw transaction by decoding it first
        try {
            TxHandler txHandler = new TxHandler();
            String decodedTx = txHandler.decodeTx(rawTx);

            if (decodedTx == null) {
                showToast(getString(R.string.invalid_raw_tx_format));
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error validating TX before broadcast: " + e.getMessage());
            showToast(getString(R.string.error_decoding_tx, e.getMessage()));
            return;
        }

        try {
            // Show loading state
            showToast(getString(R.string.broadcasting_tx));

            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient == null) {
                showToast(getString(R.string.apip_client_not_available));
                return;
            }

            // Broadcast transaction in background thread
            new Thread(() -> {
                try {
                    String result = fapiClient.broadcastTx(rawTx);
                    if(result==null && fapiClient.getLastResponse().getMessage()!=null)
                        result = fapiClient.getLastResponse().getMessage();
                    String finalResult = result;
                    runOnUiThread(() -> {
                        if (finalResult != null) {

                            if(Hex.isHex32(finalResult)) {
                                // Update cash database after successful broadcast
                                try {
                                    TxSender txSender = new TxSender();
                                    CashManager cashManager = CashManager.getInstance();
                                    FidManager fidManager = FidManager.getInstance();
                                    String sender = fidManager.getLiveFid();

                                    if (sender != null && cashManager != null) {
                                        boolean updated = txSender.updateCashOfTx(rawTx, sender, BroadcastActivity.this, cashManager);
                                        if (!updated) {
                                            TimberLogger.w(TAG, "Failed to update cash DB after broadcast");
                                        }
                                    }
                                } catch (Exception e) {
                                    TimberLogger.e(TAG, "Error updating cash DB: " + e.getMessage());
                                }

                                updateResultText(getString(R.string.broadcast_result, finalResult));
                                showToast(getString(R.string.tx_broadcasted));
                                // Return the transaction ID to the calling activity
                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("EXTRA_TXID", finalResult);
                                setResult(RESULT_OK, resultIntent);
                                finish();
                            }else{
                                showToast(getString(R.string.tx_broadcast_failed));
                                updateResultText(getString(R.string.broadcast_result, finalResult));
                            }
                        } else {
                            showToast(getString(R.string.tx_broadcast_failed));
                            setResult(RESULT_CANCELED);
                        }
                    });

                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error broadcasting TX: " + e.getMessage());
                    runOnUiThread(() -> {
                        showToast(getString(R.string.error_broadcasting_tx, e.getMessage()));
                        setResult(RESULT_CANCELED);
                    });
                }
            }).start();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error preparing broadcast: " + e.getMessage());
            showToast(getString(R.string.error_broadcasting_tx, e.getMessage()));
            setResult(RESULT_CANCELED);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        rawTxEditText.setText(qrContent);
    }
}