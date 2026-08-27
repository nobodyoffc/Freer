package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenCardContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shows the tokens the user hid from the browsing list and lets them reveal selected ones.
 */
public class HiddenTokenActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenTokenActivity";

    private TokenCardContainer cardContainer;
    private TokenManager tokenManager;

    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private TextView statisticsTextView;

    private List<Token> tokenList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_hidden_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.hidden_tokens);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

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

        tokenList = tokenManager.getHiddenTokenList();
        if (tokenList == null || tokenList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_hidden_tokens_found));
            finish();
            return;
        }

        loadCardList();
    }

    @Override
    protected void initializeViews() {
        revealButton = findViewById(R.id.reveal_button);
        backButton = findViewById(R.id.back_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        statisticsTextView = findViewById(R.id.statistics_text);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadCardList() {
        LinearLayout listContainer = findViewById(R.id.fragment_container);

        cardContainer = new TokenCardContainer(this, listContainer, ChooseMode.CHOOSE_MULTI);
        cardContainer.setHideEditButton(true);
        cardContainer.setOnListChangedListener(updatedList -> updateUI());

        cardContainer.setOnHistoryIconClickListener(token -> {
            Intent intent = new Intent(this, TokenHistoryActivity.class);
            intent.putExtra(TokenHistoryActivity.EXTRA_TOKEN_ID, token.getId());
            startActivity(intent);
        });

        Map<String, String> cidMap = cardContainer.getCidMap(tokenList, this);
        for (Token token : tokenList) {
            cardContainer.addTokenCard(token, cidMap);
        }

        setupSelectAllCheckBox();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed() && cardContainer != null) {
                    cardContainer.selectAll(isChecked);
                }
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && cardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);
            selectAllCheckBox.setChecked(cardContainer.areAllSelected());
            setupSelectAllCheckBox();
        }
    }

    private void updateUI() {
        boolean hasSelected = cardContainer != null && !cardContainer.getSelectedTokens().isEmpty();
        revealButton.setEnabled(hasSelected);
        revealButton.setAlpha(hasSelected ? 1.0f : 0.5f);

        updateSelectAllCheckBoxState();

        if (statisticsTextView != null) {
            int count = cardContainer != null ? cardContainer.getTokenList().size() : tokenList.size();
            statisticsTextView.setText(getString(R.string.total_count, count));
            statisticsTextView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void setupButtons() {
        revealButton.setOnClickListener(v -> {
            hideKeyboard();
            revealSelected();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void revealSelected() {
        if (cardContainer == null) return;
        List<Token> selected = cardContainer.getSelectedTokens();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        tokenManager.removeFromHiddenTokenList(selected);

        for (Token token : selected) {
            cardContainer.removeTokenById(token.getId());
        }
        tokenList.removeAll(selected);

        ToastUtils.makeText(this, getString(R.string.tokens_revealed_successfully, selected.size()));
        setResult(RESULT_OK);

        if (tokenList.isEmpty()) {
            finish();
        } else {
            updateUI();
        }
    }
}
