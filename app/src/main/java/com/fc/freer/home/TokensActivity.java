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
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenCardContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TokensActivity extends BaseCryptoActivity {
    private static final String TAG = "TokensActivity";

    private TokenCardContainer cardContainer;
    protected TokenManager tokenManager;
    private WaitingDialog waitingDialog;

    private ImageButton moreButton;
    private ImageButton createButton;
    private ImageButton hideButton;
    private ImageButton homeButton;
    private ImageButton clearButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView scrollView;
    private TextView statisticsTextView;

    private final int pageSize = FreerApplication.DEFAULT_PAGE_SIZE;
    private final List<Token> tokenList = new ArrayList<>();
    private boolean isHomeMode = false;

    private ActivityResultLauncher<Intent> createTokenLauncher;
    private ActivityResultLauncher<Intent> closeTokenLauncher;
    private ActivityResultLauncher<Intent> historyLauncher;

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
        createTokenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        closeTokenLauncher = registerForActivityResult(
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
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_tokens;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.tokens);
    }

    @Override
    protected void initializeViews() {
        moreButton = findViewById(R.id.more_button);
        createButton = findViewById(R.id.create_button);
        hideButton = findViewById(R.id.hide_button);
        homeButton = findViewById(R.id.home_button);
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);

        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        scrollView = findViewById(R.id.token_scroll_view);
        statisticsTextView = findViewById(R.id.statistics_text);

        setupSelectAllCheckBox();
        setupSwipeRefresh();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_tokens));

        new Thread(() -> {
            try {
                List<Token> tokens = tokenManager.fetchTokensFromAPI("refresh", null);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showItemList(tokens);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.no_tokens_found));
                });
            }
        }).start();
    }

    private void showItemList(List<Token> newList) {
        if (newList == null || newList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_tokens_found));
            updateUI();
            return;
        }

        tokenList.clear();
        tokenList.addAll(newList);
        updateUI();
        loadCardList();
    }

    private void loadCardList() {
        LinearLayout listContainer = findViewById(R.id.fragment_container);

        if (cardContainer != null) {
            cardContainer.clearAll();
        }

        cardContainer = new TokenCardContainer(this, listContainer, ChooseMode.CHOOSE_MULTI);

        cardContainer.setOnListChangedListener(updatedList -> updateUI());

        cardContainer.setOnHistoryIconClickListener(token -> {
            Intent intent = new Intent(this, TokenHistoryActivity.class);
            intent.putExtra(TokenHistoryActivity.EXTRA_TOKEN_ID, token.getId());
            historyLauncher.launch(intent);
        });

        Map<String, String> cidMap = cardContainer.getCidMap(tokenList, this);
        for (Token token : tokenList) {
            cardContainer.addTokenCard(token, cidMap);
        }

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

        if (createButton != null) {
            createButton.setOnClickListener(v -> {
                hideKeyboard();
                Intent intent = new Intent(this, CreateTokenActivity.class);
                createTokenLauncher.launch(intent);
            });
        }

        if (hideButton != null) {
            hideButton.setOnClickListener(v -> {
                hideKeyboard();
                handleHideTokens();
            });
        }

        if (homeButton != null) {
            homeButton.setOnClickListener(v -> {
                hideKeyboard();
                toggleHomeMode();
            });
        }

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                isHomeMode = false;
                refreshList();
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
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_tokens_more, null);
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
            isHomeMode = false;
            refreshList();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            // TODO: Show hidden tokens activity
            ToastUtils.makeText(this, "Hidden tokens not implemented yet");
        });

        popupView.findViewById(R.id.close_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleCloseTokens();
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleHideTokens() {
        if (cardContainer == null) return;
        List<Token> selected = cardContainer.getSelectedTokens();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        // Save to cached list and remove from display
        for (Token token : selected) {
            cardContainer.removeTokenById(token.getId());
            tokenList.remove(token);
        }

        ToastUtils.makeText(this, "Hidden " + selected.size() + " token(s)");
        updateUI();
    }

    private void handleCloseTokens() {
        if (cardContainer == null) return;
        List<Token> selected = cardContainer.getSelectedTokens();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        // Filter closable tokens (deployer = liveFid AND closable = "true")
        String liveFid = FidManager.getInstance().getLiveFid();
        List<Token> closableTokens = new ArrayList<>();
        for (Token token : selected) {
            if (liveFid != null && liveFid.equals(token.getDeployer()) 
                && "true".equalsIgnoreCase(token.getClosable())) {
                closableTokens.add(token);
            }
        }

        if (closableTokens.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_closable_tokens_selected);
            return;
        }

        // Launch CloseTokenActivity
        Intent intent = new Intent(this, CloseTokenActivity.class);
        String[] tokenIds = new String[closableTokens.size()];
        for (int i = 0; i < closableTokens.size(); i++) {
            tokenIds[i] = closableTokens.get(i).getId();
        }
        intent.putExtra(CloseTokenActivity.EXTRA_TOKEN_IDS, tokenIds);
        closeTokenLauncher.launch(intent);
    }

    private void toggleHomeMode() {
        isHomeMode = !isHomeMode;
        
        showWaitingDialog(getString(R.string.loading_tokens));

        new Thread(() -> {
            try {
                List<Token> tokens;
                if (isHomeMode) {
                    tokens = tokenManager.fetchMyTokensFromAPI(null);
                } else {
                    tokens = tokenManager.fetchTokensFromAPI("refresh", null);
                }

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showItemList(tokens);
                    ToastUtils.makeText(this, isHomeMode ? 
                        getString(R.string.home_tokens_mode) : getString(R.string.tokens));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, "Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void refreshList() {
        if (cardContainer != null) {
            cardContainer.clearAll();
        }
        tokenList.clear();
        
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }

        if (isHomeMode) {
            showWaitingDialog(getString(R.string.loading_tokens));
            new Thread(() -> {
                try {
                    List<Token> tokens = tokenManager.fetchMyTokensFromAPI(null);
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        showItemList(tokens);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, "Error: " + e.getMessage());
                    });
                }
            }).start();
        } else {
            loadInitialData();
        }
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

        int containerSize = tokenList.size();
        Long total = tokenManager != null ? tokenManager.getTotalTokens() : null;

        String statsText = getString(R.string.above_in_statistics) + containerSize;
        if (total != null) {
            statsText += " / " + total;
        }
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
