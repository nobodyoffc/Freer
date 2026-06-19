package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.Values.DESC;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.RepuHist;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.feipData.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RateHistoryActivity extends BaseCryptoActivity {
    private static final String TAG = "RateHistoryActivity";

    public static final String EXTRA_RATEE_FID = "extra_ratee_fid";

    private final int pageSize = Math.min(FreerApplication.DEFAULT_REQUEST_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<RepuHist> repuHistList = new ArrayList<>();

    private LinearLayout listContainer;
    private ScrollView scrollView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private Button loadMoreButton;
    private TextView statisticsText;
    private LinearLayout statisticsContainer;
    private ImageButton backButton;

    private String rateeFid;
    private boolean isLoading = false;
    private boolean hasMoreOlderData = true;
    private Long totalOnChain = null;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_rate_history;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.rate_history);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        rateeFid = getIntent().getStringExtra(EXTRA_RATEE_FID);
        if (rateeFid == null || rateeFid.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_fid_available);
            finish();
            return;
        }

        loadInitialData();
    }

    @Override
    protected void initializeViews() {
        listContainer = findViewById(R.id.fragment_container);
        scrollView = findViewById(R.id.rate_history_scroll_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        loadMoreButton = findViewById(R.id.load_more_button);
        statisticsText = findViewById(R.id.statistics_text);
        statisticsContainer = findViewById(R.id.statistics_container);
        backButton = findViewById(R.id.back_button);

        setupSwipeRefresh();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    @Override
    protected void setupButtons() {
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> loadOlderData());
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::refreshData);
            swipeRefreshLayout.setColorSchemeResources(
                    android.R.color.holo_blue_bright,
                    android.R.color.holo_green_light,
                    android.R.color.holo_orange_light,
                    android.R.color.holo_red_light);
        }
    }

    private void loadInitialData() {
        new Thread(() -> {
            try {
                List<RepuHist> result = fetchFromApi(null);
                runOnUiThread(() -> {
                    if (result != null && !result.isEmpty()) {
                        repuHistList.clear();
                        repuHistList.addAll(result);
                        hasMoreOlderData = result.size() >= pageSize;
                        populateList();
                        showToast(getString(R.string.loaded_items_count, result.size()));
                    } else {
                        showToast(getString(R.string.no_rate_history_found));
                    }
                    updateStatistics();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> showToast(getString(R.string.error_with_message, e.getMessage())));
            }
        }).start();
    }

    private void refreshData() {
        if (isLoading) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }
        isLoading = true;

        new Thread(() -> {
            try {
                List<RepuHist> result = fetchFromApi(null);
                runOnUiThread(() -> {
                    if (result != null && !result.isEmpty()) {
                        repuHistList.clear();
                        repuHistList.addAll(result);
                        hasMoreOlderData = result.size() >= pageSize;
                        populateList();
                    } else {
                        showToast(getString(R.string.no_rate_history_found));
                    }
                    updateStatistics();
                    isLoading = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing data: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoading = false;
                    swipeRefreshLayout.setRefreshing(false);
                    showToast(getString(R.string.error_with_message, e.getMessage()));
                });
            }
        }).start();
    }

    private void loadOlderData() {
        if (isLoading || !hasMoreOlderData) return;
        isLoading = true;

        if (loadMoreButton != null) {
            loadMoreButton.setEnabled(false);
            loadMoreButton.setAlpha(0.5f);
        }

        new Thread(() -> {
            try {
                RepuHist lastItem = repuHistList.isEmpty() ? null : repuHistList.get(repuHistList.size() - 1);
                List<RepuHist> result = fetchFromApi(lastItem);
                runOnUiThread(() -> {
                    if (result != null && !result.isEmpty()) {
                        repuHistList.addAll(result);
                        hasMoreOlderData = result.size() >= pageSize;
                        populateList();
                        showToast(getString(R.string.loaded_items_count, result.size()));
                    } else {
                        hasMoreOlderData = false;
                        showToast(getString(R.string.no_more_data));
                    }
                    updateStatistics();
                    isLoading = false;
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading older data: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoading = false;
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                    showToast(getString(R.string.error_with_message, e.getMessage()));
                });
            }
        }).start();
    }

    private List<RepuHist> fetchFromApi(RepuHist afterItem) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "FapiClient not available");
            return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewTerms().addNewFields("ratee").addNewValues(rateeFid);
        fcdsl.addSort(FieldNames.HEIGHT, DESC).addSort(FieldNames.ID, DESC);
        fcdsl.addSize(pageSize);

        if (afterItem != null) {
            List<String> afterValues = new ArrayList<>();
            afterValues.add(afterItem.getHeight() != null ? String.valueOf(afterItem.getHeight()) : "0");
            afterValues.add(afterItem.getId() != null ? afterItem.getId() : "");
            fcdsl.addAfter(afterValues);
        }

        List<RepuHist> result = fapiClient.entitySearch(IndicesNames.REPUTATION_HISTORY, fcdsl, RepuHist.class);

        if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getTotal() != null) {
            totalOnChain = fapiClient.getLastResponse().getTotal();
        }

        return result;
    }

    private FapiClient loadFapiClient() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            Object client = apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client instanceof FapiClient) {
                return (FapiClient) client;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading FapiClient: %s", e.getMessage());
        }
        return null;
    }

    private void populateList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        CidFidManager cidFidManager = CidFidManager.getInstance(this);
        List<String> raterFids = new ArrayList<>();
        for (RepuHist item : repuHistList) {
            if (item.getRater() != null) {
                raterFids.add(item.getRater());
            }
        }
        Map<String, String> cidMap = (cidFidManager != null) ? cidFidManager.getCidsByFids(raterFids) : null;

        AvatarManager avatarManager = AvatarManager.getInstance(this);

        for (RepuHist item : repuHistList) {
            View cardView = LayoutInflater.from(this).inflate(R.layout.item_repu_hist_card, listContainer, false);

            ImageView avatarView = cardView.findViewById(R.id.rater_avatar);
            TextView nameView = cardView.findViewById(R.id.rater_name);
            TextView causeView = cardView.findViewById(R.id.cause_text);
            TextView timeView = cardView.findViewById(R.id.time_text);
            ImageView rateIcon = cardView.findViewById(R.id.rate_icon);
            TextView hotView = cardView.findViewById(R.id.hot_text);
            TextView idView = cardView.findViewById(R.id.id_text);

            String rater = item.getRater();
            if (rater != null) {
                String displayName = (cidMap != null && cidMap.containsKey(rater)) ? cidMap.get(rater) : rater;
                nameView.setText(displayName);

                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(rater);
                if (avatarBitmap != null) {
                    avatarView.setImageBitmap(avatarBitmap);
                } else {
                    avatarView.setImageResource(R.drawable.ic_person);
                }

                nameView.setOnClickListener(v -> copyToClipboard(rater, "FID"));
                avatarView.setOnClickListener(v -> AvatarManager.showAvatarDialog(this, rater));
            } else {
                nameView.setText("-");
            }

            String rate = item.getRate();
            if ("good".equals(rate)) {
                rateIcon.setImageResource(R.drawable.ic_good);
            } else if ("bad".equals(rate)) {
                rateIcon.setImageResource(R.drawable.ic_bad);
            } else {
                rateIcon.setVisibility(View.GONE);
            }

            Long hot = item.getHot();
            hotView.setText(hot != null ? String.valueOf(hot) : "0");

            Long time = item.getTime();
            if (time != null) {
                timeView.setText(DateUtils.longToTime(time * 1000, DateUtils.TO_MINUTE));
            } else {
                timeView.setText("-");
            }

            String cause = item.getCause();
            if (cause != null && !cause.isEmpty()) {
                causeView.setText(cause);
                causeView.setVisibility(View.VISIBLE);
            } else {
                causeView.setVisibility(View.GONE);
            }

            String itemId = item.getId();
            if (itemId != null && !itemId.isEmpty()) {
                idView.setText(truncateMiddle(itemId));
                idView.setVisibility(View.VISIBLE);
                idView.setOnClickListener(v -> copyToClipboard(itemId, "ID"));
            } else {
                idView.setVisibility(View.GONE);
            }

            listContainer.addView(cardView);
        }
    }

    private String truncateMiddle(String text) {
        if (text == null || text.length() <= 16) return text;
        return text.substring(0, 8) + "..." + text.substring(text.length() - 8);
    }

    private void updateStatistics() {
        if (statisticsText == null) return;

        int containerSize = repuHistList.size();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.above_in_statistics)).append(containerSize);
        if (totalOnChain != null) {
            sb.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }
        statisticsText.setText(sb.toString());
        statisticsContainer.setVisibility(View.VISIBLE);

        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreOlderData ? View.VISIBLE : View.GONE);
        }
    }
}
