package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHistory;
import com.fc.fc_ajdk.data.feipData.TokenOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.EntityFieldPickers;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class IssueTokenActivity extends BaseCryptoActivity {
    private static final String TAG = "IssueTokenActivity";

    public static final String EXTRA_TOKEN_JSON = "extra_token_json";

    private Token token;

    private TextView tokenNameText;
    private TextView tokenIdText;
    private TextView tokenCirculatingText;
    private TextView tokenCapacityText;
    private TextView issueRulesText;

    private LinearLayout issueToContainer;
    private ImageButton addRecipientButton;
    private ImageButton clearButton;
    private ImageButton issueButton;
    private ImageButton backButton;

    private EntityFieldPickers pickers;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_issue_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.issue_token);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String tokenJson = getIntent().getStringExtra(EXTRA_TOKEN_JSON);
        token = tokenJson != null ? JsonUtils.fromJson(tokenJson, Token.class) : null;
        if (token == null || token.getId() == null) {
            ToastUtils.makeText(this, getString(R.string.token_data_not_found));
            finish();
            return;
        }

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        pickers = new EntityFieldPickers(this);

        updateTokenCard();
        addRecipientRow();
        setupButtons();
    }

    @Override
    protected void initializeViews() {
        tokenNameText = findViewById(R.id.token_name_text);
        tokenIdText = findViewById(R.id.token_id_text);
        tokenCirculatingText = findViewById(R.id.token_circulating_text);
        tokenCapacityText = findViewById(R.id.token_capacity_text);
        issueRulesText = findViewById(R.id.token_issue_rules_text);

        issueToContainer = findViewById(R.id.issue_to_container);
        addRecipientButton = findViewById(R.id.add_recipient_button);
        clearButton = findViewById(R.id.clearButton);
        issueButton = findViewById(R.id.issueButton);
        backButton = findViewById(R.id.back_button);
    }

    private void updateTokenCard() {
        tokenNameText.setText(token.getName() != null ? token.getName() : token.getId());
        tokenIdText.setText(token.getId());
        tokenCirculatingText.setText(token.getCirculating() != null ?
            String.valueOf(token.getCirculating()) : "0");
        if (token.getCapacity() != null) {
            tokenCapacityText.setText("/ " + token.getCapacity());
        }

        if (Boolean.TRUE.equals(token.getOpenIssue())) {
            StringBuilder rules = new StringBuilder();
            if (token.getMaxAmtPerIssue() != null) {
                rules.append(getString(R.string.max_amt_per_issue)).append(": ")
                     .append(token.getMaxAmtPerIssue());
            }
            if (token.getMinCddPerIssue() != null) {
                if (rules.length() > 0) rules.append("  ");
                rules.append(getString(R.string.min_cdd_per_issue)).append(": ")
                     .append(token.getMinCddPerIssue());
            }
            if (token.getMaxIssuesPerAddr() != null) {
                if (rules.length() > 0) rules.append("  ");
                rules.append(getString(R.string.max_issues_per_addr)).append(": ")
                     .append(token.getMaxIssuesPerAddr());
            }
            if (rules.length() > 0) {
                issueRulesText.setText(rules.toString());
                issueRulesText.setVisibility(View.VISIBLE);
            }
        }
    }

    private void addRecipientRow() {
        View row = LayoutInflater.from(this).inflate(R.layout.item_issue_to_row, issueToContainer, false);
        ImageButton removeButton = row.findViewById(R.id.issue_to_remove_button);
        removeButton.setOnClickListener(v -> {
            if (issueToContainer.getChildCount() > 1) {
                issueToContainer.removeView(row);
            } else {
                clearRow(row);
            }
        });

        // The recipient is a FID: search one on chain (or pick from contacts) via the people icon.
        ImageButton peopleButton = row.findViewById(R.id.issue_to_people_button);
        TextInputEditText fidInput = row.findViewById(R.id.issue_to_fid_input);
        peopleButton.setOnClickListener(v -> {
            hideKeyboard();
            pickers.searchFidsInto(fidInput, false);
        });

        issueToContainer.addView(row);
    }

    private void clearRow(View row) {
        TextInputEditText fidInput = row.findViewById(R.id.issue_to_fid_input);
        TextInputEditText amountInput = row.findViewById(R.id.issue_to_amount_input);
        fidInput.setText("");
        amountInput.setText("");
    }

    @Override
    protected void setupButtons() {
        addRecipientButton.setOnClickListener(v -> {
            hideKeyboard();
            addRecipientRow();
        });

        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            issueToContainer.removeAllViews();
            addRecipientRow();
        });

        issueButton.setOnClickListener(v -> {
            hideKeyboard();
            issueToken();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void issueToken() {
        String liveFid = FidManager.getInstance().getLiveFid();

        // When openIssue is false the parser only accepts the deployer as signer
        if (!Boolean.TRUE.equals(token.getOpenIssue())
            && (liveFid == null || !liveFid.equals(token.getDeployer()))) {
            ToastUtils.makeText(this, R.string.only_deployer_can_issue);
            return;
        }

        if (Boolean.TRUE.equals(token.getClosed())) {
            ToastUtils.makeText(this, R.string.token_closed);
            return;
        }

        int maxDecimal = SendTokenActivity.parseDecimal(token.getDecimal());
        List<TokenHistory.FidAmount> issueTo = new ArrayList<>();
        double total = 0;

        for (int i = 0; i < issueToContainer.getChildCount(); i++) {
            View row = issueToContainer.getChildAt(i);
            TextInputEditText fidInput = row.findViewById(R.id.issue_to_fid_input);
            TextInputEditText amountInput = row.findViewById(R.id.issue_to_amount_input);

            String fid = fidInput.getText() != null ? fidInput.getText().toString().trim() : "";
            String amountStr = amountInput.getText() != null ? amountInput.getText().toString().trim() : "";

            if (fid.isEmpty() && amountStr.isEmpty()) continue;

            if (!KeyTools.isGoodFid(fid)) {
                ToastUtils.makeText(this, R.string.invalid_fid);
                return;
            }

            double amount;
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                ToastUtils.makeText(this, R.string.invalid_amount_format);
                return;
            }
            if (amount <= 0) {
                ToastUtils.makeText(this, R.string.amount_must_be_greater_than_zero);
                return;
            }
            if (SendTokenActivity.getDecimalPlaces(amountStr) > maxDecimal) {
                ToastUtils.makeText(this, getString(R.string.too_many_decimal_places, maxDecimal));
                return;
            }

            TokenHistory.FidAmount fidAmount = new TokenHistory.FidAmount();
            fidAmount.setFid(fid);
            fidAmount.setAmount(amount);
            issueTo.add(fidAmount);
            total += amount;
        }

        if (issueTo.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_recipient_address);
            return;
        }

        if (Boolean.TRUE.equals(token.getOpenIssue()) && token.getMaxAmtPerIssue() != null) {
            try {
                if (total > Double.parseDouble(token.getMaxAmtPerIssue())) {
                    ToastUtils.makeText(this, getString(R.string.exceeds_max_amt_per_issue, token.getMaxAmtPerIssue()));
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (token.getCapacity() != null) {
            try {
                double circulating = token.getCirculating() != null ? token.getCirculating() : 0;
                if (circulating + total > Double.parseDouble(token.getCapacity())) {
                    ToastUtils.makeText(this, R.string.exceeds_capacity);
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_key_available);
            return;
        }

        TokenOpData tokenOpData = TokenOpData.makeIssue(token.getId(), issueTo);

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
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(IssueTokenActivity.this,
                                    getString(R.string.failed_to_issue_token) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(IssueTokenActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(IssueTokenActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(IssueTokenActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }
}
