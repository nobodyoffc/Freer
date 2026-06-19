package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.TokenOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

public class CreateTokenActivity extends BaseCryptoActivity {

    private TextInputEditText tokenIdInput;
    private TextInputEditText tokenNameInput;
    private TextInputEditText descInput;
    private TextInputEditText consensusIdInput;
    private TextInputEditText capacityInput;
    private TextInputEditText decimalInput;
    private TextInputEditText maxAmtPerIssueInput;
    private TextInputEditText minCddPerIssueInput;
    private TextInputEditText maxIssuesPerAddrInput;

    private CheckBox transferableCheckbox;
    private CheckBox closableCheckbox;
    private CheckBox openIssueCheckbox;

    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_token);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
    }

    @Override
    protected void initializeViews() {
        View tokenIdView = findViewById(R.id.tokenIdView);
        View tokenNameView = findViewById(R.id.tokenNameView);
        View descView = findViewById(R.id.descView);
        View consensusIdView = findViewById(R.id.consensusIdView);
        View capacityView = findViewById(R.id.capacityView);
        View decimalView = findViewById(R.id.decimalView);
        View maxAmtPerIssueView = findViewById(R.id.maxAmtPerIssueView);
        View minCddPerIssueView = findViewById(R.id.minCddPerIssueView);
        View maxIssuesPerAddrView = findViewById(R.id.maxIssuesPerAddrView);

        tokenIdInput = tokenIdView.findViewById(R.id.textInput);
        tokenIdInput.setHint(R.string.enter_token_id);

        tokenNameInput = tokenNameView.findViewById(R.id.textInput);
        tokenNameInput.setHint(R.string.enter_token_name);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.description);

        consensusIdInput = consensusIdView.findViewById(R.id.textInput);
        consensusIdInput.setHint(R.string.consensus_id);

        capacityInput = capacityView.findViewById(R.id.textInput);
        capacityInput.setHint(R.string.capacity);

        decimalInput = decimalView.findViewById(R.id.textInput);
        decimalInput.setHint(R.string.decimal);

        maxAmtPerIssueInput = maxAmtPerIssueView.findViewById(R.id.textInput);
        maxAmtPerIssueInput.setHint(R.string.max_amt_per_issue);

        minCddPerIssueInput = minCddPerIssueView.findViewById(R.id.textInput);
        minCddPerIssueInput.setHint(R.string.min_cdd_per_issue);

        maxIssuesPerAddrInput = maxIssuesPerAddrView.findViewById(R.id.textInput);
        maxIssuesPerAddrInput.setHint(R.string.max_issues_per_addr);

        transferableCheckbox = findViewById(R.id.transferable_checkbox);
        closableCheckbox = findViewById(R.id.closable_checkbox);
        openIssueCheckbox = findViewById(R.id.open_issue_checkbox);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        saveButton.setOnClickListener(v -> {
            hideKeyboard();
            // Local save not implemented for tokens - they must be on-chain
            ToastUtils.makeText(this, "Tokens must be published on-chain");
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishToken();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        tokenIdInput.setText("");
        tokenNameInput.setText("");
        descInput.setText("");
        consensusIdInput.setText("");
        capacityInput.setText("");
        decimalInput.setText("");
        maxAmtPerIssueInput.setText("");
        minCddPerIssueInput.setText("");
        maxIssuesPerAddrInput.setText("");
        transferableCheckbox.setChecked(true);
        closableCheckbox.setChecked(true);
        openIssueCheckbox.setChecked(false);
    }

    private void publishToken() {
        String tokenId = getText(tokenIdInput);
        String name = getText(tokenNameInput);
        String desc = getText(descInput);
        String consensusId = getText(consensusIdInput);
        String capacity = getText(capacityInput);
        String decimal = getText(decimalInput);
        String maxAmtPerIssue = getText(maxAmtPerIssueInput);
        String minCddPerIssue = getText(minCddPerIssueInput);
        String maxIssuesPerAddr = getText(maxIssuesPerAddrInput);

        String transferable = transferableCheckbox.isChecked() ? "true" : "false";
        String closable = closableCheckbox.isChecked() ? "true" : "false";
        String openIssue = openIssueCheckbox.isChecked() ? "true" : "false";

        // Validate required fields
        if (tokenId.isEmpty()) {
            ToastUtils.makeText(this, R.string.enter_token_id);
            return;
        }

        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.enter_token_name);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create FEIP data for REGISTER
        TokenOpData tokenOpData = TokenOpData.makeRegister(
            tokenId,
            name,
            desc.isEmpty() ? null : desc,
            consensusId.isEmpty() ? null : consensusId,
            capacity.isEmpty() ? null : capacity,
            decimal.isEmpty() ? null : decimal,
            transferable,
            closable,
            openIssue,
            maxAmtPerIssue.isEmpty() ? null : maxAmtPerIssue,
            minCddPerIssue.isEmpty() ? null : minCddPerIssue,
            maxIssuesPerAddr.isEmpty() ? null : maxIssuesPerAddr
        );

        Feip feip = Feip.fromName(TOKEN);
        feip.setData(tokenOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateTokenActivity.this,
                                    getString(R.string.token_created_successfully, txId));
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateTokenActivity.this,
                                    getString(R.string.failed_to_create_token) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateTokenActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateTokenActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(CreateTokenActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }
}
