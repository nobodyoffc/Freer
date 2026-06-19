package com.fc.freer.account;

import static com.fc.fc_ajdk.constants.FieldNames.HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.SIGNER;
import static com.fc.fc_ajdk.constants.FieldNames.TX_INDEX;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.NEWER;
import static com.fc.fc_ajdk.constants.Values.OLDER;
import static com.fc.fc_ajdk.constants.Values.REFRESH;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fchData.OpReturn;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Displays the on-chain OP_RETURN history (records) of the current live FID.
 * Modeled after {@link IncomeActivity}: a swipe-to-refresh list with "load more"
 * pagination, ordered from newest to oldest.
 */
public class OpReturnActivity extends BaseCryptoActivity {
    private static final String TAG = "OpReturn";

    private final int pageSize = Math.min(FreerApplication.DEFAULT_REQUEST_SIZE, FreerApplication.MAX_CONTAINER_SIZE);

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView scrollView;
    private LinearLayout listContainer;
    private ImageButton loadMoreButton;
    private TextView statisticsTextView;

    private String liveFid;

    private final List<OpReturn> opReturnList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean isLoadingOlder = false;
    private boolean hasMoreOlderData = true;

    private Long totalOnChain = null;

    private WaitingDialog waitingDialog;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_op_return;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.op_return_history);
    }

    @Override
    protected void initializeViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        scrollView = findViewById(R.id.op_return_scroll_view);
        listContainer = findViewById(R.id.op_return_list_container);
        loadMoreButton = findViewById(R.id.load_more_button);
        statisticsTextView = findViewById(R.id.op_return_statistics);

        liveFid = FidManager.getInstance().getLiveFid();

        setupSwipeRefresh();

        loadInitialData();
    }

    @Override
    protected void setupButtons() {
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> loadOlder());
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // OpReturnActivity does not use QR scanning.
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::loadNewer);
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    /**
     * Gets the FapiClient used to search the OpReturn index.
     */
    private FapiClient loadFapiClient() {
        try {
            Object client = ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client instanceof FapiClient) {
                return (FapiClient) client;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting FapiClient: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Fetches OpReturn records signed by the live FID.
     *
     * @param operationType REFRESH, NEWER or OLDER
     * @param reference     boundary record for pagination (latest for NEWER, earliest for OLDER)
     * @return list of OpReturn records, or null on failure
     */
    private List<OpReturn> fetchOpReturnFromAPI(String operationType, OpReturn reference) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch OpReturn: FapiClient not available");
            return null;
        }
        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch OpReturn: liveFid not set");
            return null;
        }

        String order;
        List<String> last = null;
        switch (operationType) {
            case NEWER -> {
                order = ASC;
                if (reference != null) last = boundaryOf(reference);
            }
            case OLDER -> {
                order = DESC;
                if (reference != null) last = boundaryOf(reference);
            }
            default -> { // REFRESH
                order = DESC;
            }
        }

        try {
            Fcdsl fcdsl = new Fcdsl();
            fcdsl.addNewQuery().addNewTerms().addNewFields(SIGNER).addNewValues(liveFid);
            fcdsl.addSize(pageSize);
            fcdsl.addSort(HEIGHT, order);
            fcdsl.addSort(TX_INDEX, order);
            if (last != null && !last.isEmpty()) fcdsl.addAfter(last);

            List<OpReturn> result = fapiClient.opReturnSearch(fcdsl);

            // Capture total for statistics
            try {
                if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getTotal() != null) {
                    totalOnChain = fapiClient.getLastResponse().getTotal();
                }
            } catch (Exception ignored) {
            }

            return result;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching OpReturn from API: %s", e.getMessage());
            return null;
        }
    }

    private List<String> boundaryOf(OpReturn opReturn) {
        long height = opReturn.getHeight() != null ? opReturn.getHeight() : 0L;
        int txIndex = opReturn.getTxIndex() != null ? opReturn.getTxIndex() : 0;
        return Arrays.asList(String.valueOf(height), String.valueOf(txIndex));
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading));

        new Thread(() -> {
            try {
                if (NetworkUtils.isNetworkDownToast(this)) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        showToast(getString(R.string.network_connection_failed));
                    });
                    return;
                }

                List<OpReturn> result = fetchOpReturnFromAPI(REFRESH, null);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    if (result != null && !result.isEmpty()) {
                        opReturnList.clear();
                        opReturnList.addAll(result);
                        hasMoreOlderData = result.size() >= pageSize;
                        renderList();
                        showToast(getString(R.string.loaded_op_returns, result.size()));
                    } else {
                        showToast(getString(R.string.no_op_return_found));
                        updateStatistics();
                    }
                    updateLoadMoreButton();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial OpReturn data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast(getString(R.string.error_loading_op_return, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Loads newer records (swipe down from top).
     */
    private void loadNewer() {
        if (isLoadingNewer || isLoadingOlder) {
            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            return;
        }
        isLoadingNewer = true;

        OpReturn latest = opReturnList.isEmpty() ? null : opReturnList.get(0);

        new Thread(() -> {
            try {
                List<OpReturn> result = fetchOpReturnFromAPI(NEWER, latest);
                runOnUiThread(() -> {
                    if (result != null && !result.isEmpty()) {
                        // Returned ASC (oldest first), reverse to newest first before prepending
                        Collections.reverse(result);
                        opReturnList.addAll(0, result);
                        renderList();
                        showToast(getString(R.string.added_new_op_returns, result.size()));
                    } else {
                        showToast(getString(R.string.no_new_op_return_found));
                    }
                    isLoadingNewer = false;
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer OpReturn: %s", e.getMessage());
                runOnUiThread(() -> {
                    showToast(getString(R.string.error_loading_op_return, e.getMessage()));
                    isLoadingNewer = false;
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                });
            }
        }).start();
    }

    /**
     * Loads older records (load more button).
     */
    private void loadOlder() {
        if (isLoadingNewer || isLoadingOlder || !hasMoreOlderData) {
            return;
        }
        isLoadingOlder = true;
        if (loadMoreButton != null) {
            loadMoreButton.setEnabled(false);
            loadMoreButton.setAlpha(0.5f);
        }

        OpReturn earliest = opReturnList.isEmpty() ? null : opReturnList.get(opReturnList.size() - 1);

        new Thread(() -> {
            try {
                List<OpReturn> result = fetchOpReturnFromAPI(OLDER, earliest);
                runOnUiThread(() -> {
                    if (result != null && !result.isEmpty()) {
                        opReturnList.addAll(result);
                        hasMoreOlderData = result.size() >= pageSize;
                        renderList();
                        showToast(getString(R.string.loaded_op_returns, result.size()));
                    } else {
                        hasMoreOlderData = false;
                        showToast(getString(R.string.no_more_op_return_available));
                    }
                    isLoadingOlder = false;
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                    updateLoadMoreButton();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading older OpReturn: %s", e.getMessage());
                runOnUiThread(() -> {
                    showToast(getString(R.string.error_loading_op_return, e.getMessage()));
                    isLoadingOlder = false;
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                });
            }
        }).start();
    }

    /**
     * Rebuilds the card list from {@link #opReturnList}.
     */
    private void renderList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(this);
        for (OpReturn opReturn : opReturnList) {
            View card = inflater.inflate(R.layout.item_op_return_card, listContainer, false);
            bindCard(card, opReturn);
            listContainer.addView(card);
        }

        updateStatistics();
    }

    private void bindCard(View card, OpReturn opReturn) {
        TextView timeView = card.findViewById(R.id.op_return_time);
        TextView heightView = card.findViewById(R.id.op_return_height);
        TextView textView = card.findViewById(R.id.op_return_text);
        TextView recipientView = card.findViewById(R.id.op_return_recipient);
        TextView paidView = card.findViewById(R.id.op_return_paid);

        if (opReturn.getTime() != null) {
            timeView.setText(DateUtils.longShortToTime(opReturn.getTime(), DateUtils.TO_MINUTE));
        } else {
            timeView.setText("");
        }

        if (opReturn.getHeight() != null) {
            heightView.setText(getString(R.string.height) + ": " + opReturn.getHeight());
        } else {
            heightView.setText("");
        }

        textView.setText(opReturn.getOpReturn() != null ? opReturn.getOpReturn() : "");

        if (opReturn.getRecipient() != null && !opReturn.getRecipient().isEmpty()) {
            recipientView.setText(getString(R.string.recipient) + ": " + opReturn.getRecipient());
            recipientView.setVisibility(View.VISIBLE);
        } else {
            recipientView.setVisibility(View.INVISIBLE);
        }

        if (opReturn.getPaid() != null && opReturn.getPaid() > 0) {
            paidView.setText(getString(R.string.paid) + ": " + FchUtils.formatSatoshiToCoin(opReturn.getPaid()) + " F");
            paidView.setVisibility(View.VISIBLE);
        } else {
            paidView.setVisibility(View.GONE);
        }
    }

    private void updateLoadMoreButton() {
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreOlderData && !opReturnList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateStatistics() {
        if (statisticsTextView == null) return;
        StringBuilder stats = new StringBuilder();
        stats.append(getString(R.string.op_return_statistics_above, opReturnList.size()));
        if (totalOnChain != null) {
            stats.append(getString(R.string.op_return_statistics_onchain, totalOnChain));
        }
        statisticsTextView.setText(stats.toString());
        statisticsTextView.setVisibility(View.VISIBLE);
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
