package com.fc.freer.mail;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.NoticeFeeOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.MailManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.tx.SendTxActivity;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import android.content.Intent;
import android.widget.ImageButton;

import static com.fc.freer.manager.MailManager.PAY_BACK_NOTICE_FEE;
import static com.fc.freer.manager.MailManager.MAX_PAYING_NOTICE_FEE;

public class SetNoticeFeeActivity extends BaseCryptoActivity {

    private static final String TAG = "SetNoticeFeeActivity";

    // UI Elements
    private EditText myNoticeFeeEditText;
    private EditText maxPayingNoticeFeeEditText;
    private CheckBox payBackCheckBox;
    private Button myNoticeFeeButton;
    private Button maxPayingNoticeFeeButton;
    private ImageButton clearButton;
    private ImageButton okButton;

    // Data
    private MailManager mailManager;
    private KeyInfo liveKeyInfo;
    private String originalMyNoticeFee;
    private String originalMaxPayingNoticeFee;
    private boolean isMyNoticeFeeEditing = false;
    private boolean isMaxPayingNoticeFeeEditing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize managers
        FidManager fidManager = FidManager.getInstance();
        liveKeyInfo = fidManager != null ? fidManager.getLiveKeyInfo() : null;

        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_live_key_info));
            finish();
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (liveFid == null) {
            ToastUtils.makeText(this, getString(R.string.no_live_fid_available));
            finish();
            return;
        }

        mailManager = MailManager.getInstance(this, liveFid);
        if (mailManager == null) {
            ToastUtils.makeText(this, getString(R.string.mail_not_ready_try_later));
            finish();
            return;
        }

        // Load initial values
        loadInitialValues();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_set_notice_fee;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.set_notice_fee);
    }

    @Override
    protected void initializeViews() {
        myNoticeFeeEditText = findViewById(R.id.my_notice_fee_edit_text);
        maxPayingNoticeFeeEditText = findViewById(R.id.max_paying_notice_fee_edit_text);
        payBackCheckBox = findViewById(R.id.pay_back_notice_fee_checkbox);
        myNoticeFeeButton = findViewById(R.id.my_notice_fee_button);
        maxPayingNoticeFeeButton = findViewById(R.id.max_paying_notice_fee_button);
        clearButton = findViewById(R.id.clear_button);
        okButton = findViewById(R.id.ok_button);
    }

    @Override
    protected void setupButtons() {
        // Always pay back checkbox listener
        payBackCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mailManager.setSetting(PAY_BACK_NOTICE_FEE, String.valueOf(isChecked));
            mailManager.commit();
        });

        // My notice fee button
        myNoticeFeeButton.setOnClickListener(v -> {
            if (isMyNoticeFeeEditing) {
                // "Carve" button clicked - carve on chain
                carveNoticeFee();
            } else {
                // "Reset" button clicked - enable editing
                isMyNoticeFeeEditing = true;
                myNoticeFeeEditText.setEnabled(true);
                myNoticeFeeEditText.setBackgroundResource(R.drawable.bg_input_view);
                myNoticeFeeButton.setText(R.string.carve);
                // Change to accent color for action
                myNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.field_name)));
            }
        });

        // Max paying notice fee button
        maxPayingNoticeFeeButton.setOnClickListener(v -> {
            if (isMaxPayingNoticeFeeEditing) {
                // "Done" button clicked - save setting
                saveMaxPayingNoticeFee();
            } else {
                // "Reset" button clicked - enable editing
                isMaxPayingNoticeFeeEditing = true;
                maxPayingNoticeFeeEditText.setEnabled(true);
                maxPayingNoticeFeeEditText.setBackgroundResource(R.drawable.bg_input_view);
                maxPayingNoticeFeeButton.setText(R.string.done);
                // Change to accent color for action
                maxPayingNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.field_name)));
            }
        });

        // Clear button
        clearButton.setOnClickListener(v -> clearAllChanges());

        // Cancel button

        // OK button
        okButton.setOnClickListener(v -> {
            if (isMyNoticeFeeEditing || isMaxPayingNoticeFeeEditing) {
                ToastUtils.makeText(this, getString(R.string.unfinished_editing));
            } else {
                finish();
            }
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not needed for this activity
    }

    /**
     * Load initial values from liveKeyInfo and mailManager
     */
    private void loadInitialValues() {
        // Load my notice fee
        originalMyNoticeFee = liveKeyInfo.getNoticeFee();
        if (originalMyNoticeFee == null) {
            originalMyNoticeFee = "0";
        }
        myNoticeFeeEditText.setText(originalMyNoticeFee);

        // Load max paying notice fee
        String maxPayingFee = (String) mailManager.getSetting(MAX_PAYING_NOTICE_FEE);
        if (maxPayingFee == null) {
            maxPayingFee = "0";
        }
        originalMaxPayingNoticeFee = maxPayingFee;
        maxPayingNoticeFeeEditText.setText(originalMaxPayingNoticeFee);

        // Load always pay back setting
        Object alwaysPayBackObj = mailManager.getSetting(PAY_BACK_NOTICE_FEE);
        boolean alwaysPayBack = Values.TRUE.equalsIgnoreCase ((String)alwaysPayBackObj);
        payBackCheckBox.setChecked(alwaysPayBack);
    }

    /**
     * Clear all changes and recover original state
     */
    private void clearAllChanges() {
        // Reset my notice fee
        myNoticeFeeEditText.setText(originalMyNoticeFee);
        myNoticeFeeEditText.setEnabled(false);
        myNoticeFeeEditText.setBackgroundResource(R.drawable.bg_result_item);
        myNoticeFeeButton.setText(R.string.reset);
        myNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.accent)));
        isMyNoticeFeeEditing = false;

        // Reset max paying notice fee
        maxPayingNoticeFeeEditText.setText(originalMaxPayingNoticeFee);
        maxPayingNoticeFeeEditText.setEnabled(false);
        maxPayingNoticeFeeEditText.setBackgroundResource(R.drawable.bg_result_item);
        maxPayingNoticeFeeButton.setText(R.string.reset);
        maxPayingNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.accent)));
        isMaxPayingNoticeFeeEditing = false;

        ToastUtils.makeText(this, getString(R.string.changes_cleared));
    }

    /**
     * Save max paying notice fee to settings
     */
    private void saveMaxPayingNoticeFee() {
        String newMaxFee = maxPayingNoticeFeeEditText.getText().toString().trim();

        if (newMaxFee.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_max_notice_fee));
            return;
        }

        try {
            // Validate it's a valid number
            double feeValue = Double.parseDouble(newMaxFee);
            if (feeValue < 0) {
                ToastUtils.makeText(this, getString(R.string.invalid_fee_value));
                return;
            }
        } catch (NumberFormatException e) {
            ToastUtils.makeText(this, getString(R.string.invalid_fee_value));
            return;
        }

        // Save to settings
        mailManager.setSetting(MAX_PAYING_NOTICE_FEE, newMaxFee);
        mailManager.commit();

        // Update original value
        originalMaxPayingNoticeFee = newMaxFee;

        // Disable editing
        isMaxPayingNoticeFeeEditing = false;
        maxPayingNoticeFeeEditText.setEnabled(false);
        maxPayingNoticeFeeEditText.setBackgroundResource(R.drawable.bg_result_item);
        maxPayingNoticeFeeButton.setText(R.string.reset);
        maxPayingNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.accent)));

        ToastUtils.makeText(this, getString(R.string.max_paying_notice_fee_updated));
    }

    /**
     * Carve notice fee on chain
     */
    private void carveNoticeFee() {
        String newNoticeFee = myNoticeFeeEditText.getText().toString().trim();

        if (newNoticeFee.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_notice_fee));
            return;
        }

        // Check if value actually changed
        if (newNoticeFee.equals(originalMyNoticeFee)) {
            ToastUtils.makeText(this, getString(R.string.no_changes_to_carve));
            isMyNoticeFeeEditing = false;
            myNoticeFeeEditText.setEnabled(false);
            myNoticeFeeEditText.setBackgroundResource(R.drawable.bg_result_item);
            myNoticeFeeButton.setText(R.string.reset);
            myNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent)));
            return;
        }

        try {
            // Validate it's a valid number
            double feeValue = Double.parseDouble(newNoticeFee);
            if (feeValue < 0) {
                ToastUtils.makeText(this, getString(R.string.invalid_fee_value));
                return;
            }
        } catch (NumberFormatException e) {
            ToastUtils.makeText(this, getString(R.string.invalid_fee_value));
            return;
        }

        // Create FEIP data
        NoticeFeeOpData opData = NoticeFeeOpData.makeNoticeFee(newNoticeFee);

        Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.NOTICE_FEE);
        feip.setData(opData);

        String feipJson = JsonUtils.toJson(feip);

        // Get private key
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed_to_get_private_key));
            return;
        }

        // Use TxSender to carve on chain
        CashManager cashManager = CashManager.getInstance();
        TxSender txSender = new TxSender();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

        new Thread(() -> {
            try {
                txSender.carveSimpleFeip(
                    this,
                    liveKeyInfo.getId(),
                    feipJson,
                    prikey,
                        cashManager,
                    new TxHandler(),
                    fapiClient,
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                // Update liveKeyInfo
                                liveKeyInfo.setNoticeFee(newNoticeFee);
                                FidManager.getInstance().updateKeyInfo(SetNoticeFeeActivity.this,
                                    liveKeyInfo.getId(), liveKeyInfo);

                                // Update original value
                                originalMyNoticeFee = newNoticeFee;

                                // Disable editing
                                isMyNoticeFeeEditing = false;
                                myNoticeFeeEditText.setEnabled(false);
                                myNoticeFeeEditText.setBackgroundResource(R.drawable.bg_result_item);
                                myNoticeFeeButton.setText(R.string.reset);
                                myNoticeFeeButton.setBackgroundTintList(ColorStateList.valueOf(
                                    ContextCompat.getColor(SetNoticeFeeActivity.this, R.color.accent)));

                                ToastUtils.makeText(SetNoticeFeeActivity.this,
                                    getString(R.string.notice_fee_carved_successfully, txId));
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SetNoticeFeeActivity.this,
                                    getString(R.string.failed_to_carve_notice_fee, errorMessage));
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                Intent intent = new Intent(SetNoticeFeeActivity.this, SendTxActivity.class);
                                intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toNiceJson());
                                startActivity(intent);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                TxSender txSender = new TxSender();
                                txSender.showSignedTxAsQR(SetNoticeFeeActivity.this, signedTxHex);
                            });
                        }
                    }
                );
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error carving notice fee: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(SetNoticeFeeActivity.this,
                        getString(R.string.failed_to_carve_notice_fee, e.getMessage()));
                });
            }
        }).start();
    }
}
