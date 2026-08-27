package com.fc.freer.home;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenHolderCardContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the token holders the user hid locally and lets them reveal selected ones.
 */
public class HiddenTokenHolderActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenTokenHolderActivity";

    private TokenHolderCardContainer cardContainer;
    private TokenManager tokenManager;

    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private TextView statisticsTextView;

    private List<TokenHolder> tokenHolderList = new ArrayList<>();
    private final Map<String, Token> tokenInfoMap = new HashMap<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_hidden_token_holder;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.hidden_token_holders);
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

        tokenHolderList = tokenManager.getLocalHiddenTokenHolders();
        if (tokenHolderList == null || tokenHolderList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_hidden_token_holders_found));
            finish();
            return;
        }

        new Thread(() -> {
            fetchTokenInfo(tokenHolderList);
            runOnUiThread(this::loadCardList);
        }).start();
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

    private void fetchTokenInfo(List<TokenHolder> holders) {
        List<String> missingIds = new ArrayList<>();
        for (TokenHolder holder : holders) {
            if (holder.getTokenId() != null && !tokenInfoMap.containsKey(holder.getTokenId())
                && !missingIds.contains(holder.getTokenId())) {
                missingIds.add(holder.getTokenId());
            }
        }
        if (missingIds.isEmpty()) return;

        Map<String, Token> tokens = tokenManager.fetchTokensByIds(missingIds);
        if (tokens != null) {
            for (Map.Entry<String, Token> entry : tokens.entrySet()) {
                if (entry.getValue() != null) {
                    tokenInfoMap.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void loadCardList() {
        LinearLayout listContainer = findViewById(R.id.fragment_container);

        cardContainer = new TokenHolderCardContainer(this, listContainer, ChooseMode.CHOOSE_MULTI);
        cardContainer.setTokenInfoMap(tokenInfoMap);
        cardContainer.setOnListChangedListener(updatedList -> updateUI());

        for (TokenHolder holder : tokenHolderList) {
            cardContainer.addTokenHolderCard(holder);
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
        boolean hasSelected = cardContainer != null && !cardContainer.getSelectedTokenHolders().isEmpty();
        revealButton.setEnabled(hasSelected);
        revealButton.setAlpha(hasSelected ? 1.0f : 0.5f);

        updateSelectAllCheckBoxState();

        if (statisticsTextView != null) {
            int count = cardContainer != null ? cardContainer.getTokenHolderList().size() : tokenHolderList.size();
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
        List<TokenHolder> selected = cardContainer.getSelectedTokenHolders();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        // Remove from the local deleted list and put back into the main table
        tokenManager.removeFromLocalDeletedList(selected);
        for (TokenHolder holder : selected) {
            tokenManager.addTokenHolder(holder);
        }
        tokenManager.commit();

        for (TokenHolder holder : selected) {
            cardContainer.removeTokenHolderById(holder.getId());
        }
        tokenHolderList.removeAll(selected);

        ToastUtils.makeText(this, getString(R.string.token_holders_revealed_successfully, selected.size()));
        setResult(RESULT_OK);

        if (tokenHolderList.isEmpty()) {
            finish();
        } else {
            updateUI();
        }
    }
}
