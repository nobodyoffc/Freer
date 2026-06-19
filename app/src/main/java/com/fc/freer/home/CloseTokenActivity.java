package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenCardContainer;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CloseTokenActivity extends BaseCryptoActivity {
    private static final String TAG = "CloseTokenActivity";

    public static final String EXTRA_TOKEN_IDS = "extra_token_ids";

    private TokenCardContainer cardContainer;
    private LinearLayout listContainer;
    private TokenManager tokenManager;

    private ImageButton closeButton;
    private ImageButton backButton;
    private ScrollView scrollView;
    private TextView statisticsTextView;

    private List<Token> tokenList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_close_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.close_tokens);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the token IDs from intent
        String[] tokenIds = getIntent().getStringArrayExtra(EXTRA_TOKEN_IDS);
        if (tokenIds == null || tokenIds.length == 0) {
            ToastUtils.makeText(this, getString(R.string.no_tokens_found));
            finish();
            return;
        }

        // Initialize TokenManager
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        
        try {
            if (fidManager != null && liveFid != null) {
                tokenManager = TokenManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize TokenManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing TokenManager: " + e.getMessage());
        }

        if (tokenManager == null) {
            ToastUtils.makeText(this, getString(R.string.token_not_ready_try_later));
            finish();
            return;
        }

        // Fetch tokens by IDs - only include tokens that are closable
        loadTokens(tokenIds, liveFid);

        // Setup toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
    }

    private void loadTokens(String[] tokenIds, String liveFid) {
        new Thread(() -> {
            for (String tokenId : tokenIds) {
                Token token = tokenManager.fetchTokenById(tokenId);
                if (token != null && liveFid != null && liveFid.equals(token.getDeployer())
                    && "true".equalsIgnoreCase(token.getClosable())
                    && !"true".equalsIgnoreCase(token.getClosed())) {
                    tokenList.add(token);
                }
            }

            runOnUiThread(() -> {
                if (tokenList.isEmpty()) {
                    ToastUtils.makeText(this, getString(R.string.no_closable_tokens_found));
                    finish();
                    return;
                }
                loadCardList();
            });
        }).start();
    }

    @Override
    protected void initializeViews() {
        closeButton = findViewById(R.id.close_button);
        backButton = findViewById(R.id.back_button);
        scrollView = findViewById(R.id.token_scroll_view);
        statisticsTextView = findViewById(R.id.statistics_text);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadCardList() {
        listContainer = findViewById(R.id.fragment_container);

        cardContainer = new TokenCardContainer(this, listContainer, ChooseMode.WITHOUT_CHOOSE);
        cardContainer.setHideEditButton(true);
        cardContainer.setShowClearButton(true);
        cardContainer.setOnClearIconClickListener(token -> {
            cardContainer.removeTokenById(token.getId());
            tokenList.removeIf(t -> t.getId().equals(token.getId()));
            updateUI();
        });

        cardContainer.setOnListChangedListener(updatedList -> {
            tokenList.clear();
            tokenList.addAll(updatedList);
            updateUI();
        });

        Map<String, String> cidMap = cardContainer.getCidMap(tokenList, this);
        for (Token token : tokenList) {
            cardContainer.addTokenCard(token, cidMap);
        }

        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasTokens = cardContainer != null && !cardContainer.getTokenList().isEmpty();
        closeButton.setEnabled(hasTokens);
        closeButton.setAlpha(hasTokens ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (statisticsTextView == null || cardContainer == null) {
            return;
        }

        int containerSize = cardContainer.getTokenList().size();
        statisticsTextView.setText(getString(R.string.total_count, containerSize));
        statisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        closeButton.setOnClickListener(v -> {
            hideKeyboard();
            if (cardContainer == null) return;
            List<Token> tokensToClose = new ArrayList<>(cardContainer.getTokenList());
            if (tokensToClose.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_tokens_to_close));
                return;
            }
            performCloseOperation(tokensToClose);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performCloseOperation(List<Token> tokensToClose) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        // Extract IDs for the close operation
        List<String> tokenIds = new ArrayList<>();
        for (Token token : tokensToClose) {
            if (token.getId() != null) {
                tokenIds.add(token.getId());
            }
        }

        if (tokenIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_token_ids));
            return;
        }

        // Create FEIP for close operation
        String feipJson = makeCloseTokenFeip(tokenIds);

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
                                // Return token IDs in result intent
                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("closed_token_ids", tokenIds.toArray(new String[0]));
                                setResult(Activity.RESULT_OK, resultIntent);

                                ToastUtils.makeText(CloseTokenActivity.this, 
                                    getString(R.string.token_closed_successfully, txId));
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CloseTokenActivity.this, 
                                    getString(R.string.failed_to_close_tokens) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CloseTokenActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CloseTokenActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(CloseTokenActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeCloseTokenFeip(List<String> tokenIds) {
        Feip feip = Feip.fromName(TOKEN);
        TokenOpData tokenOpData = TokenOpData.makeClose(tokenIds);
        feip.setData(tokenOpData);
        return feip.toJson();
    }
}
