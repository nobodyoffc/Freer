package com.fc.freer.home;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenHolderCardContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MyTokenActivity extends BaseCryptoActivity {
    private static final String TAG = "MyTokenActivity";

    private TokenHolderCardContainer cardContainer;
    protected TokenManager tokenManager;
    private WaitingDialog waitingDialog;

    private ImageButton moreButton;
    private ImageButton tokensButton;
    private ImageButton hideButton;
    private ImageButton clearButton;
    private ImageButton historyButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView scrollView;
    private TextView statisticsTextView;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<TokenHolder> tokenHolderList = new ArrayList<>();
    private Map<String, Token> tokenInfoMap = new HashMap<>();

    private ActivityResultLauncher<Intent> tokensLauncher;
    private ActivityResultLauncher<Intent> historyLauncher;
    private ActivityResultLauncher<Intent> sendTokenLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeActivityResultLaunchers();

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

        loadInitialData();
    }

    private void initializeActivityResultLaunchers() {
        tokensLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        historyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {}
        );

        sendTokenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_my_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.my_tokens);
    }

    @Override
    protected void initializeViews() {
        moreButton = findViewById(R.id.more_button);
        tokensButton = findViewById(R.id.tokens_button);
        hideButton = findViewById(R.id.hide_button);
        clearButton = findViewById(R.id.clear_button);
        historyButton = findViewById(R.id.history_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);

        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        scrollView = findViewById(R.id.token_holder_scroll_view);
        statisticsTextView = findViewById(R.id.statistics_text);

        setupSelectAllCheckBox();
        setupSwipeRefresh();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_token_holders));

        new Thread(() -> {
            try {
                int count = tokenManager.refreshTokenHoldersFromAPI(this);
                List<TokenHolder> holders = tokenManager.getPaginatedTokenHolders(pageSize, null, true);
                
                // Fetch token info for display
                fetchTokenInfo(holders);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showItemList(holders);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
                });
            }
        }).start();
    }

    private void fetchTokenInfo(List<TokenHolder> holders) {
        if (holders == null || holders.isEmpty()) return;
        
        for (TokenHolder holder : holders) {
            if (holder.getTokenId() != null && !tokenInfoMap.containsKey(holder.getTokenId())) {
                Token token = tokenManager.fetchTokenById(holder.getTokenId());
                if (token != null) {
                    tokenInfoMap.put(holder.getTokenId(), token);
                }
            }
        }
    }

    private void showItemList(List<TokenHolder> newList) {
        if (newList == null || newList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
            updateUI();
            return;
        }

        tokenHolderList.clear();
        tokenHolderList.addAll(newList);
        updateUI();
        loadCardList();
    }

    private void loadCardList() {
        LinearLayout listContainer = findViewById(R.id.fragment_container);

        if (cardContainer != null) {
            cardContainer.clearAll();
        }

        cardContainer = new TokenHolderCardContainer(this, listContainer, ChooseMode.CHOOSE_MULTI);
        cardContainer.setTokenInfoMap(tokenInfoMap);

        cardContainer.setOnListChangedListener(updatedList -> updateUI());

        cardContainer.setOnSendIconClickListener(this::handleSendToken);

        for (TokenHolder holder : tokenHolderList) {
            cardContainer.addTokenHolderCard(holder);
        }

        updateUI();
    }

    private void handleSendToken(TokenHolder tokenHolder) {
        Intent intent = new Intent(this, SendTokenActivity.class);
        intent.putExtra(SendTokenActivity.EXTRA_TOKEN_HOLDER_JSON, tokenHolder.toJson());
        sendTokenLauncher.launch(intent);
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

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::refreshList);
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    @Override
    protected void setupButtons() {
        if (moreButton != null) {
            moreButton.setOnClickListener(this::showMoreMenu);
        }

        if (tokensButton != null) {
            tokensButton.setOnClickListener(v -> {
                hideKeyboard();
                Intent intent = new Intent(this, TokensActivity.class);
                tokensLauncher.launch(intent);
            });
        }

        if (hideButton != null) {
            hideButton.setOnClickListener(v -> {
                hideKeyboard();
                handleHideTokenHolders();
            });
        }

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                refreshList();
            });
        }

        if (historyButton != null) {
            historyButton.setOnClickListener(v -> {
                hideKeyboard();
                Intent intent = new Intent(this, TokenHistoryActivity.class);
                intent.putExtra(TokenHistoryActivity.EXTRA_FID, FidManager.getInstance().getLiveFid());
                historyLauncher.launch(intent);
            });
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void showMoreMenu(View anchorView) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_my_token_more, null);
        PopupWindow popupWindow = new PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        );

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.refresh_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            // TODO: Show hidden token holders activity
            ToastUtils.makeText(this, "Hidden token holders not implemented yet");
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleHideTokenHolders() {
        if (cardContainer == null) return;
        List<TokenHolder> selected = cardContainer.getSelectedTokenHolders();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        tokenManager.addToLocalDeletedList(selected);
        tokenManager.removeTokenHolders(selected);
        tokenManager.commit();

        refreshList();
        ToastUtils.makeText(this, "Hidden " + selected.size() + " token holder(s)");
    }

    private void refreshList() {
        if (cardContainer != null) {
            cardContainer.clearAll();
        }
        tokenHolderList.clear();
        
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }

        loadInitialData();
    }

    private void clearAndReload() {
        showWaitingDialog(getString(R.string.loading_token_holders));

        new Thread(() -> {
            try {
                tokenManager.clearLocalDeletedList();
                int count = tokenManager.refreshTokenHoldersFromAPI(this);
                List<TokenHolder> holders = tokenManager.getPaginatedTokenHolders(pageSize, null, true);
                fetchTokenInfo(holders);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    if (cardContainer != null) {
                        cardContainer.clearAll();
                    }
                    tokenHolderList.clear();
                    showItemList(holders);
                    ToastUtils.makeText(this, getString(R.string.refresh));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, "Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void updateUI() {
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && cardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);
            selectAllCheckBox.setChecked(cardContainer.areAllSelected());
            setupSelectAllCheckBox();
        }
    }

    private void updateStatistics() {
        if (statisticsTextView == null) return;

        int containerSize = tokenHolderList.size();
        long dbSize = tokenManager != null ? tokenManager.getTokenHolderDBSize() : 0;

        String statsText = getString(R.string.above_in_statistics) + containerSize +
                          getString(R.string.local_in_statistics) + dbSize;
        statisticsTextView.setText(statsText);

        LinearLayout statisticsContainer = findViewById(R.id.statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }
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
