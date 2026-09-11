package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHistory;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.data.feipData.TokenOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class SendTokenActivity extends BaseCryptoActivity {
    private static final String TAG = "SendTokenActivity";

    public static final String EXTRA_TOKEN_HOLDER_JSON = "extra_token_holder_json";

    private TokenManager tokenManager;
    private TokenHolder tokenHolder;
    private Token tokenInfo;
    private WaitingDialog waitingDialog;

    private TextView tokenNameText;
    private TextView tokenIdText;
    private TextView tokenBalanceText;
    private TextInputEditText recipientInput;
    private TextInputEditText amountInput;

    private ImageButton clearButton;
    private ImageButton sendButton;
    private ImageButton backButton;

    private ActivityResultLauncher<Intent> chooseContactLauncher;
    private ActivityResultLauncher<Intent> scanQrLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_send_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.send_token);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize launchers
        initializeLaunchers();

        // Get token holder from intent
        String tokenHolderJson = getIntent().getStringExtra(EXTRA_TOKEN_HOLDER_JSON);
        if (tokenHolderJson == null || tokenHolderJson.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.token_data_not_found));
            finish();
            return;
        }

        tokenHolder = JsonUtils.fromJson(tokenHolderJson, TokenHolder.class);
        if (tokenHolder == null) {
            ToastUtils.makeText(this, getString(R.string.token_data_not_found));
            finish();
            return;
        }

        // Initialize TokenManager
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        
        if (fidManager != null && liveFid != null) {
            tokenManager = TokenManager.getInstance(this, liveFid);
        }

        if (tokenManager == null) {
            ToastUtils.makeText(this, getString(R.string.token_not_ready_try_later));
            finish();
            return;
        }

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        // Fetch token info
        fetchTokenInfo();

        setupButtons();
    }

    private void initializeLaunchers() {
        chooseContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleChooseContactResult(result.getData());
                }
            }
        );

        scanQrLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String qrContent = result.getData().getStringExtra("qr_content");
                    if (qrContent != null && KeyTools.isGoodFid(qrContent)) {
                        recipientInput.setText(qrContent);
                    }
                }
            }
        );
    }

    private void fetchTokenInfo() {
        showWaitingDialog(getString(R.string.loading_tokens));
        
        new Thread(() -> {
            tokenInfo = tokenManager.fetchTokenById(tokenHolder.getTokenId());
            
            runOnUiThread(() -> {
                dismissWaitingDialog();
                updateTokenCard();
            });
        }).start();
    }

    private void updateTokenCard() {
        if (tokenNameText != null) {
            String name = tokenInfo != null && tokenInfo.getName() != null ? 
                          tokenInfo.getName() : tokenHolder.getTokenId();
            tokenNameText.setText(name);
        }

        if (tokenIdText != null) {
            tokenIdText.setText(tokenHolder.getTokenId());
        }

        if (tokenBalanceText != null && tokenHolder.getBalance() != null) {
            tokenBalanceText.setText(formatBalance(tokenHolder.getBalance()));
        }
    }

    private String formatBalance(Double balance) {
        if (balance == null) return "0";
        if (balance >= 1_000_000_000) {
            return String.format("%.2fB", balance / 1_000_000_000.0);
        } else if (balance >= 1_000_000) {
            return String.format("%.2fM", balance / 1_000_000.0);
        } else if (balance >= 1_000) {
            return String.format("%.2fK", balance / 1_000.0);
        } else if (balance == balance.longValue()) {
            return String.valueOf(balance.longValue());
        }
        return String.format("%.4f", balance);
    }

    @Override
    protected void initializeViews() {
        tokenNameText = findViewById(R.id.token_name_text);
        tokenIdText = findViewById(R.id.token_id_text);
        tokenBalanceText = findViewById(R.id.token_balance_text);

        View recipientContainer = findViewById(R.id.recipientInputContainer);
        recipientInput = recipientContainer.findViewById(R.id.keyInput);
        recipientInput.setHint(getString(R.string.recipient));

        amountInput = findViewById(R.id.amountInput);

        clearButton = findViewById(R.id.clearButton);
        sendButton = findViewById(R.id.sendButton);
        backButton = findViewById(R.id.back_button);

        // Setup IoIconsView for recipient input
        setupIoIconsView(
            R.id.recipientInputContainer,
            R.id.peopleAndScanIcons,
            false, true, true, true, false,
            null,
            isSingleChoice -> onPeopleClick(),
            () -> pasteFromClipboard(recipientInput),
            null,
            this::launchQrScanner
        );

        // Root click listener to hide keyboard
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> hideKeyboard());
    }

    private void onPeopleClick() {
        hideKeyboard();
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
        chooseContactLauncher.launch(intent);
    }

    private void handleChooseContactResult(Intent data) {
        java.util.List<String> fids = data.getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
        if (fids != null && !fids.isEmpty()) {
            recipientInput.setText(fids.get(0));
        }
    }

    private void launchQrScanner() {
        Intent intent = new Intent(this, com.fc.freer.qr.QrCodeActivity.class);
        intent.putExtra("is_return_string", true);
        scanQrLauncher.launch(intent);
    }

    @Override
    protected void setupButtons() {
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                recipientInput.setText("");
                amountInput.setText("");
            });
        }

        if (sendButton != null) {
            sendButton.setOnClickListener(v -> {
                hideKeyboard();
                handleSend();
            });
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void handleSend() {
        String recipient = recipientInput.getText() != null ? recipientInput.getText().toString().trim() : "";
        String amountStr = amountInput.getText() != null ? amountInput.getText().toString().trim() : "";

        // Validate inputs
        if (recipient.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_recipient_address);
            return;
        }

        if (!KeyTools.isGoodFid(recipient)) {
            ToastUtils.makeText(this, R.string.invalid_fid);
            return;
        }

        if (amountStr.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_amount);
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
            if (amount <= 0) {
                ToastUtils.makeText(this, R.string.amount_must_be_greater_than_zero);
                return;
            }
        } catch (NumberFormatException e) {
            ToastUtils.makeText(this, R.string.invalid_amount_format);
            return;
        }

        // Check balance
        if (tokenHolder.getBalance() != null && amount > tokenHolder.getBalance()) {
            ToastUtils.makeText(this, R.string.insufficient_balance);
            return;
        }

        // The parser rejects transfers of closed tokens and amounts with more
        // decimal places than the token's decimal
        if (tokenInfo != null) {
            if (Boolean.TRUE.equals(tokenInfo.getClosed())) {
                ToastUtils.makeText(this, R.string.token_closed);
                return;
            }

            if (!Boolean.TRUE.equals(tokenInfo.getTransferable())) {
                ToastUtils.makeText(this, R.string.token_not_transferable);
                return;
            }

            int maxDecimal = parseDecimal(tokenInfo.getDecimal());
            if (getDecimalPlaces(amountStr) > maxDecimal) {
                ToastUtils.makeText(this, getString(R.string.too_many_decimal_places, maxDecimal));
                return;
            }
        }

        // Perform transfer, once any nobody recipient is confirmed
        NobodyGuard.confirm(this, java.util.Collections.singletonList(recipient),
                R.string.nobody_consequence_send, () -> performTransfer(recipient, amount));
    }

    static int parseDecimal(String decimal) {
        if (decimal == null) return 0;
        try {
            return Integer.parseInt(decimal);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int getDecimalPlaces(String amountStr) {
        try {
            return Math.max(0, new java.math.BigDecimal(amountStr).stripTrailingZeros().scale());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private void performTransfer(String recipient, double amount) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_key_available);
            return;
        }

        showWaitingDialog(getString(R.string.sending_transaction));

        // Create transfer data
        List<TokenHistory.FidAmount> transferTo = new ArrayList<>();
        TokenHistory.FidAmount fidAmount = new TokenHistory.FidAmount();
        fidAmount.setFid(recipient);
        fidAmount.setAmount(amount);
        transferTo.add(fidAmount);

        TokenOpData tokenOpData = TokenOpData.makeTransfer(tokenHolder.getTokenId(), transferTo);

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
                                dismissWaitingDialog();
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                ToastUtils.makeText(SendTokenActivity.this,
                                    getString(R.string.failed_to_transfer_token) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                ToastUtils.makeText(SendTokenActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(SendTokenActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                txSender.showSignedTxAsQR(SendTokenActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            dismissWaitingDialog();
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used - handled by launcher
    }

    private void showWaitingDialog(String message) {
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, message);
        } else {
            waitingDialog.setHint(message);
        }
        if (!isFinishing() && !waitingDialog.isShowing()) {
            waitingDialog.show();
        }
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing() && !isFinishing()) {
            waitingDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
        waitingDialog = null;
    }
}
