package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenHolderCardContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every holder of one token, richest first, paged from the API. Read-only.
 */
public class TokenHoldersActivity extends BaseCryptoActivity {
    private static final String TAG = "TokenHoldersActivity";

    public static final String EXTRA_TOKEN_JSON = "extra_token_json";

    public static void start(android.content.Context context, Token token) {
        Intent intent = new Intent(context, TokenHoldersActivity.class);
        intent.putExtra(EXTRA_TOKEN_JSON, token.toJson());
        context.startActivity(intent);
    }

    private TokenManager tokenManager;
    private WaitingDialog waitingDialog;
    private Token token;

    private Button loadMoreButton;
    private SwipeRefreshLayout swipeRefreshLayout;
    private LinearLayout listContainer;
    private TextView statisticsTextView;
    private TokenHolderCardContainer cardContainer;

    private final int pageSize = FreerApplication.DEFAULT_PAGE_SIZE;
    private final List<TokenHolder> holderList = new ArrayList<>();
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String tokenJson = getIntent().getStringExtra(EXTRA_TOKEN_JSON);
        token = tokenJson != null ? JsonUtils.fromJson(tokenJson, Token.class) : null;

        super.onCreate(savedInstanceState);

        if (token == null || token.getId() == null) {
            ToastUtils.makeText(this, getString(R.string.token_data_not_found));
            finish();
            return;
        }

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (liveFid != null) {
                tokenManager = TokenManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize TokenManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing TokenManager: %s", e.getMessage());
        }

        if (tokenManager == null) {
            ToastUtils.makeText(this, getString(R.string.token_not_ready_try_later));
            finish();
            return;
        }

        loadPage(true);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_token_history;
    }

    @Override
    protected String getActivityTitle() {
        if (token != null && token.getName() != null) {
            return getString(R.string.token_holders) + ": " + token.getName();
        }
        return getString(R.string.token_holders);
    }

    @Override
    protected void initializeViews() {
        loadMoreButton = findViewById(R.id.load_more_button);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        listContainer = findViewById(R.id.fragment_container);
        statisticsTextView = findViewById(R.id.statistics_text);

        // Nothing local to clear: the list is only ever the API's
        View clearButton = findViewById(R.id.clear_button);
        if (clearButton != null) clearButton.setVisibility(View.GONE);

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                swipeRefreshLayout.setRefreshing(false);
                loadPage(true);
            });
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    @Override
    protected void setupButtons() {
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> loadPage(false));
        }
        ImageButton backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadPage(boolean fromStart) {
        if (loading) return;
        loading = true;
        TokenHolder after = fromStart || holderList.isEmpty() ? null : holderList.get(holderList.size() - 1);
        showWaitingDialog(getString(R.string.loading_token_holders));

        new Thread(() -> {
            List<TokenHolder> page = tokenManager.fetchHoldersOfToken(token.getId(), pageSize, after);
            Map<String, String> cids = null;
            if (page != null && !page.isEmpty()) {
                List<String> fids = new ArrayList<>();
                for (TokenHolder holder : page) {
                    if (holder.getFid() != null) fids.add(holder.getFid());
                }
                try {
                    cids = CidFidManager.getInstance(this).getCidsByFids(fids);
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Could not resolve CIDs: %s", e.getMessage());
                }
            }
            final Map<String, String> pageCids = cids;

            runOnUiThread(() -> {
                loading = false;
                dismissWaitingDialog();
                if (isFinishing() || isDestroyed()) return;
                if (fromStart) {
                    holderList.clear();
                    resetCardContainer();
                }
                if (page == null || page.isEmpty()) {
                    if (holderList.isEmpty()) {
                        ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
                    }
                    updateStatistics(false);
                    return;
                }
                cardContainer.putCids(pageCids);
                for (TokenHolder holder : page) {
                    holderList.add(holder);
                    cardContainer.addTokenHolderCard(holder);
                }
                updateStatistics(page.size() >= pageSize);
            });
        }).start();
    }

    private void resetCardContainer() {
        if (cardContainer != null) {
            cardContainer.clearAll();
        }
        cardContainer = new TokenHolderCardContainer(this, listContainer, ChooseMode.WITHOUT_CHOOSE);
        cardContainer.setShowHolder(null, token.getCirculating());
        cardContainer.setOnTokenHolderClickListener(holder -> openFreer(holder.getFid()));
    }

    /** Opens the holder's FID details; falls back to the holder record when the FID can't be fetched. */
    private void openFreer(String fid) {
        if (fid == null) return;
        new Thread(() -> {
            Freer freer = null;
            try {
                FapiClient client = tokenManager.loadFapiClient();
                if (client != null) freer = client.getFreer(fid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Could not fetch %s: %s", fid, e.getMessage());
            }
            final Freer found = freer;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (found == null) {
                    ToastUtils.makeText(this, R.string.fid_not_found);
                    return;
                }
                Intent intent = new Intent(this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, found.toJson());
                intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Freer.class.getName());
                startActivity(intent);
            });
        }).start();
    }

    private void updateStatistics(boolean mayHaveMore) {
        if (statisticsTextView == null) return;

        Long total = tokenManager.getTotalHoldersOfToken();
        String statsText = getString(R.string.above_in_statistics) + holderList.size();
        if (total != null) {
            statsText += " / " + total;
        }
        statisticsTextView.setText(statsText);

        if (loadMoreButton != null) {
            boolean more = total != null ? holderList.size() < total : mayHaveMore;
            loadMoreButton.setVisibility(more ? View.VISIBLE : View.GONE);
        }

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
