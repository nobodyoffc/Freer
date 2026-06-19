package com.fc.freer.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHistory;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TokenHistoryActivity extends BaseCryptoActivity {
    private static final String TAG = "TokenHistoryActivity";

    public static final String EXTRA_TOKEN_ID = "extra_token_id";
    public static final String EXTRA_FID = "extra_fid";

    private TokenManager tokenManager;
    private WaitingDialog waitingDialog;

    private ImageButton clearButton;
    private ImageButton backButton;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView scrollView;
    private LinearLayout listContainer;
    private TextView statisticsTextView;

    private final int pageSize = FreerApplication.DEFAULT_PAGE_SIZE;
    private final List<TokenHistory> historyList = new ArrayList<>();

    private String filterTokenId;
    private String filterFid;
    private Token tokenInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get filter parameters from intent
        filterTokenId = getIntent().getStringExtra(EXTRA_TOKEN_ID);
        filterFid = getIntent().getStringExtra(EXTRA_FID);

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

        // Update title based on filter
        updateTitle();

        loadInitialData();
    }

    private void updateTitle() {
        if (filterTokenId != null) {
            // Fetch token info for display
            new Thread(() -> {
                tokenInfo = tokenManager.fetchTokenById(filterTokenId);
                runOnUiThread(() -> {
                    if (tokenInfo != null && tokenInfo.getName() != null) {
                        setTitle(getString(R.string.token_history) + ": " + tokenInfo.getName());
                    } else {
                        setTitle(getString(R.string.token_history));
                    }
                });
            }).start();
        } else if (filterFid != null) {
            setTitle(getString(R.string.my_token_history));
        } else {
            setTitle(getString(R.string.token_history));
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_token_history;
    }

    @Override
    protected String getActivityTitle() {
        if (filterFid != null) {
            return getString(R.string.my_token_history);
        }
        return getString(R.string.token_history);
    }

    @Override
    protected void initializeViews() {
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        scrollView = findViewById(R.id.history_scroll_view);
        listContainer = findViewById(R.id.fragment_container);
        statisticsTextView = findViewById(R.id.statistics_text);

        setupSwipeRefresh();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_token_history));

        new Thread(() -> {
            try {
                List<TokenHistory> history = tokenManager.fetchTokenHistoryFromAPI(
                    filterTokenId, filterFid, "refresh", null);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showItemList(history);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.no_token_history_found));
                });
            }
        }).start();
    }

    private void showItemList(List<TokenHistory> newList) {
        if (newList == null || newList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_token_history_found));
            updateStatistics();
            return;
        }

        historyList.clear();
        historyList.addAll(newList);
        loadCardList();
        updateStatistics();
    }

    private void loadCardList() {
        listContainer.removeAllViews();

        for (TokenHistory history : historyList) {
            View cardView = LayoutInflater.from(this).inflate(R.layout.item_token_history_card, listContainer, false);
            setupHistoryCard(cardView, history);
            listContainer.addView(cardView);
        }
    }

    private void setupHistoryCard(View cardView, TokenHistory history) {
        TextView opValue = cardView.findViewById(R.id.history_op_value);
        TextView timeValue = cardView.findViewById(R.id.history_time_value);
        TextView tokenValue = cardView.findViewById(R.id.history_token_value);
        TextView signerValue = cardView.findViewById(R.id.history_signer_value);
        LinearLayout recipientRow = cardView.findViewById(R.id.recipient_row);
        TextView recipientValue = cardView.findViewById(R.id.history_recipient_value);
        LinearLayout amountRow = cardView.findViewById(R.id.amount_row);
        TextView amountValue = cardView.findViewById(R.id.history_amount_value);

        // Set operation
        opValue.setText(history.getOp() != null ? history.getOp().toUpperCase() : "");

        // Set time
        if (history.getTime() != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            timeValue.setText(sdf.format(new Date(history.getTime())));
        } else {
            timeValue.setText("");
        }

        // Set token name or ID
        String tokenDisplay = history.getName() != null ? history.getName() : history.getTokenId();
        tokenValue.setText(tokenDisplay != null ? tokenDisplay : "");

        // Set signer
        signerValue.setText(history.getSigner() != null ? history.getSigner() : "");

        // Set recipient (for transfer operations)
        if (history.getRecipient() != null && !history.getRecipient().isEmpty()) {
            recipientRow.setVisibility(View.VISIBLE);
            recipientValue.setText(history.getRecipient());
        } else {
            recipientRow.setVisibility(View.GONE);
        }

        // Set amount (from transferTo or issueTo)
        Double amount = null;
        if (history.getTransferTo() != null && !history.getTransferTo().isEmpty()) {
            amount = history.getTransferTo().get(0).getAmount();
        } else if (history.getIssueTo() != null && !history.getIssueTo().isEmpty()) {
            amount = history.getIssueTo().get(0).getAmount();
        }

        if (amount != null) {
            amountRow.setVisibility(View.VISIBLE);
            amountValue.setText(formatAmount(amount));
        } else {
            amountRow.setVisibility(View.GONE);
        }

        // Click listener to show detail
        cardView.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, com.fc.freer.ui.DetailActivity.class);
            intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, history.toJson());
            intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, TokenHistory.class.getName());
            startActivity(intent);
        });
    }

    private String formatAmount(Double amount) {
        if (amount == null) return "0";
        if (amount >= 1_000_000_000) {
            return String.format(Locale.getDefault(), "%.2fB", amount / 1_000_000_000.0);
        } else if (amount >= 1_000_000) {
            return String.format(Locale.getDefault(), "%.2fM", amount / 1_000_000.0);
        } else if (amount >= 1_000) {
            return String.format(Locale.getDefault(), "%.2fK", amount / 1_000.0);
        } else if (amount == amount.longValue()) {
            return String.valueOf(amount.longValue());
        }
        return String.format(Locale.getDefault(), "%.4f", amount);
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
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                historyList.clear();
                listContainer.removeAllViews();
                updateStatistics();
            });
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void refreshList() {
        historyList.clear();
        listContainer.removeAllViews();
        
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }

        loadInitialData();
    }

    private void updateStatistics() {
        if (statisticsTextView == null) return;

        int containerSize = historyList.size();
        Long total = tokenManager != null ? tokenManager.getTotalTokenHistory() : null;

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
