package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.APP;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.AppOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.AppManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.AppCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class StoppedAppActivity extends BaseCryptoActivity {
    private static final String TAG = "StoppedAppActivity";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    private AppCardContainer appCardContainer;
    private LinearLayout appListContainer;
    private AppManager appManager;

    private Button recoverButton;
    private ImageButton backButton;
    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView appScrollView;
    private TextView appStatisticsTextView;

    // Sort UI elements
    private LinearLayout nameSortButton;
    private TextView nameSortLabel;
    private ImageView nameSortIcon;
    private LinearLayout timeSortButton;
    private TextView timeSortLabel;
    private ImageView timeSortIcon;

    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { NAME, TIME }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.TIME;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<App> appList = new ArrayList<>();
    private Long totalStopped;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    private WaitingDialog waitingDialog;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_stopped_app;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.stopped_apps);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize AppManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                appManager = AppManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize AppManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing AppManager with liveFid: " + e.getMessage());
        }

        if (appManager == null) {
            ToastUtils.makeText(this, getString(R.string.app_not_ready_try_later));
            finish();
            return;
        }

        // Setup toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        setupSortButtons();
        setupSwipeRefresh();

        // Load initial data from API
        loadInitialData();
    }

    @Override
    protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        appScrollView = findViewById(R.id.app_scroll_view);
        appStatisticsTextView = findViewById(R.id.app_statistics);

        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortLabel = findViewById(R.id.name_sort_label);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortLabel = findViewById(R.id.time_sort_label);
        timeSortIcon = findViewById(R.id.time_sort_icon);

        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_stopped_apps));

        new Thread(() -> {
            try {
                // Fetch stopped apps from API (active = false)
                Fcdsl fcdsl = appManager.makeFcdslForOwner(pageSize, null, false, false);
                List<App> stoppedApps = appManager.fetchPageApps(fcdsl);

                if (stoppedApps == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_stopped_apps));
                        updateUI();
                    });
                    return;
                }

                // Get total count from response
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient != null && fapiClient.getLastResponse() != null) {
                    totalStopped = fapiClient.getLastResponse().getTotal();
                }

                runOnUiThread(() -> {
                    appList.clear();
                    appList.addAll(stoppedApps);

                    hasMoreEarlierData = stoppedApps.size() >= pageSize;
                    hasMoreNewerData = false;

                    updateUI();
                    loadAppCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.error_loading_stopped_apps, e.getMessage()));
                    updateUI();
                });
            }
        }).start();
    }

    private void loadAppCardList() {
        appListContainer = findViewById(R.id.fragment_container);

        // Use CHOOSE_MULTI_CONDITIONAL to allow disabling checkboxes for closed apps
        appCardContainer = new AppCardContainer(this, appListContainer, ChooseMode.CHOOSE_MULTI);
        appCardContainer.setHideEditButton(true);

        appCardContainer.setOnAppListChangedListener(updatedAppList -> {
            updateUI();
        });

        Map<String, String> cidMap = appCardContainer.getCidMap(appList, this);
        for (App app : appList) {
            appCardContainer.addAppCard(app, cidMap);
        }

        // Disable checkboxes for closed apps (they cannot be recovered)
        disableClosedAppCheckboxes();

        setupSelectAllCheckBox();
        setupScrollListener();
        updateUI();
    }

    /**
     * Disables checkboxes for closed apps since they cannot be recovered
     */
    private void disableClosedAppCheckboxes() {
        if (appCardContainer == null || appListContainer == null) return;

        for (int i = 0; i < appList.size(); i++) {
            App app = appList.get(i);
            if (Boolean.TRUE.equals(app.isClosed())) {
                View cardView = appListContainer.getChildAt(i);
                if (cardView != null) {
                    CheckBox checkBox = cardView.findViewById(R.id.app_checkbox);
                    if (checkBox != null) {
                        checkBox.setEnabled(false);
                        checkBox.setAlpha(0.5f);
                    }
                }
            }
        }
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && appCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    // Only select non-closed apps
                    selectRecoverableApps(isChecked);
                }
            });
        }
    }

    /**
     * Selects only apps that can be recovered (not closed)
     */
    private void selectRecoverableApps(boolean select) {
        if (appCardContainer == null || appListContainer == null) return;

        for (int i = 0; i < appList.size(); i++) {
            App app = appList.get(i);
            if (!Boolean.TRUE.equals(app.isClosed())) {
                View cardView = appListContainer.getChildAt(i);
                if (cardView != null) {
                    CheckBox checkBox = cardView.findViewById(R.id.app_checkbox);
                    if (checkBox != null && checkBox.isEnabled()) {
                        checkBox.setChecked(select);
                    }
                }
            }
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && appCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            // Check if all recoverable apps are selected
            List<App> selectedApps = appCardContainer.getSelectedApps();
            int recoverableCount = 0;
            for (App app : appList) {
                if (!Boolean.TRUE.equals(app.isClosed())) {
                    recoverableCount++;
                }
            }

            if (selectedApps.size() == recoverableCount && recoverableCount > 0) {
                selectAllCheckBox.setChecked(true);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    private void setupSortButtons() {
        if (nameSortButton != null) {
            nameSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.NAME) {
                    currentSortType = SortType.NAME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortAppList();
            });
        }

        if (timeSortButton != null) {
            timeSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.TIME) {
                    currentSortType = SortType.TIME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortAppList();
            });
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::loadNewerApps);
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    private void setupScrollListener() {
        if (appScrollView != null) {
            appScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = scrollY <= 10;
                boolean scrollingDown = scrollY > oldScrollY;
                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) return;

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerApps();
                    }
                }
            });
        }
    }

    private void loadNewerApps() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;

        new Thread(() -> {
            try {
                List<String> latestSortKeys = getLatestAppSortKeys();
                Fcdsl fcdsl = appManager.makeFcdslForOwner(pageSize, latestSortKeys, false, true);
                List<App> newerApps = appManager.fetchPageApps(fcdsl);

                runOnUiThread(() -> {
                    if (newerApps != null && !newerApps.isEmpty()) {
                        Collections.reverse(newerApps);
                        
                        int currentSize = appList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + newerApps.size();
                        int itemsToRemove = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemove > 0 && appCardContainer != null) {
                            appCardContainer.removeFromEnd(itemsToRemove);
                            hasMoreEarlierData = true;
                        }

                        appList.addAll(0, newerApps);
                        if (appCardContainer != null) {
                            appCardContainer.addAppCardsToBeginning(newerApps);
                            disableClosedAppCheckboxes();
                        }

                        hasMoreNewerData = newerApps.size() >= pageSize;
                        ToastUtils.makeText(this, getString(R.string.loaded_newer_apps, newerApps.size()));
                    } else {
                        hasMoreNewerData = false;
                    }

                    isLoadingNewer = false;
                    stopSwipeRefresh();
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer apps: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    stopSwipeRefresh();
                });
            }
        }).start();
    }

    private void loadEarlierApps() {
        if (isLoadingNewer || isLoadingEarlier || !hasMoreEarlierData) return;

        isLoadingEarlier = true;

        new Thread(() -> {
            try {
                List<String> earliestSortKeys = getEarliestAppSortKeys();
                Fcdsl fcdsl = appManager.makeFcdslForOwner(pageSize, earliestSortKeys, false, false);
                List<App> earlierApps = appManager.fetchPageApps(fcdsl);

                runOnUiThread(() -> {
                    if (earlierApps != null && !earlierApps.isEmpty()) {
                        int currentSize = appList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + earlierApps.size();
                        int itemsToRemove = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemove > 0 && appCardContainer != null) {
                            appCardContainer.removeFromBeginning(itemsToRemove);
                            hasMoreNewerData = true;
                        }

                        appList.addAll(earlierApps);
                        Map<String, String> cidMap = appCardContainer.getCidMap(earlierApps, this);
                        for (App app : earlierApps) {
                            appCardContainer.addAppCard(app, cidMap);
                        }
                        disableClosedAppCheckboxes();

                        hasMoreEarlierData = earlierApps.size() >= pageSize;
                        ToastUtils.makeText(this, getString(R.string.loaded_earlier_apps, earlierApps.size()));
                    } else {
                        hasMoreEarlierData = false;
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier apps: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                });
            }
        }).start();
    }

    private void stopSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateButtonStates() {
        // Get selected apps that are recoverable (not closed)
        List<App> selectedApps = appCardContainer != null ? appCardContainer.getSelectedApps() : new ArrayList<>();
        List<App> recoverableSelected = new ArrayList<>();
        for (App app : selectedApps) {
            if (!Boolean.TRUE.equals(app.isClosed())) {
                recoverableSelected.add(app);
            }
        }

        boolean hasRecoverable = !recoverableSelected.isEmpty();
        recoverButton.setEnabled(hasRecoverable);
        recoverButton.setAlpha(hasRecoverable ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (appStatisticsTextView == null) return;

        int containerSize = appList.size();
        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        if (totalStopped != null) {
            statsText.append(getString(R.string.total_in_statistics)).append(totalStopped);
        }

        appStatisticsTextView.setText(statsText.toString());

        LinearLayout statisticsContainer = findViewById(R.id.app_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreEarlierData ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void setupButtons() {
        recoverButton.setOnClickListener(v -> {
            hideKeyboard();
            if (appCardContainer == null) return;
            
            // Get only recoverable (non-closed) selected apps
            List<App> selectedApps = appCardContainer.getSelectedApps();
            List<App> recoverableApps = new ArrayList<>();
            for (App app : selectedApps) {
                if (!Boolean.TRUE.equals(app.isClosed())) {
                    recoverableApps.add(app);
                }
            }

            if (recoverableApps.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_recoverable_apps_selected));
                return;
            }
            performRecoverOperation(recoverableApps);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });

        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    loadMoreButton.setEnabled(false);
                    loadMoreButton.setAlpha(0.5f);
                    loadEarlierApps();
                    new android.os.Handler().postDelayed(() -> {
                        if (loadMoreButton != null) {
                            loadMoreButton.setEnabled(true);
                            loadMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }
    }

    private void performRecoverOperation(List<App> appsToRecover) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        List<String> appIds = new ArrayList<>();
        for (App app : appsToRecover) {
            if (app.getId() != null) {
                appIds.add(app.getId());
            }
        }

        if (appIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_app_ids));
            return;
        }

        String feipJson = makeRecoverAppFeip(appIds);

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
                                for (App app : appsToRecover) {
                                    app.setActive(true);
                                    app.setOnChain(null);
                                    appManager.updateApp(app);
                                    appCardContainer.removeAppById(app.getId());
                                }
                                appManager.commit();
                                appList.removeAll(appsToRecover);

                                ToastUtils.makeText(StoppedAppActivity.this,
                                    getString(R.string.apps_recovered_successfully, txId));
                                updateUI();
                                setResult(Activity.RESULT_OK);
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(StoppedAppActivity.this,
                                    getString(R.string.failed_to_recover_apps) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(StoppedAppActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(StoppedAppActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(StoppedAppActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeRecoverAppFeip(List<String> appIds) {
        Feip feip = Feip.fromName(APP);
        AppOpData appOpData = AppOpData.makeRecover(appIds);
        feip.setData(appOpData);
        return feip.toJson();
    }

    private List<String> getLatestAppSortKeys() {
        if (appList.isEmpty()) return null;
        App app = appList.get(0);
        return Arrays.asList(String.valueOf(app.getLastHeight()), app.getId());
    }

    private List<String> getEarliestAppSortKeys() {
        if (appList.isEmpty()) return null;
        App app = appList.get(appList.size() - 1);
        return Arrays.asList(String.valueOf(app.getLastHeight()), app.getId());
    }

    private void cycleSortState() {
        switch (currentSortState) {
            case NONE: currentSortState = SortState.ASCENDING; break;
            case ASCENDING: currentSortState = SortState.DESCENDING; break;
            case DESCENDING: currentSortState = SortState.NONE; break;
        }
    }

    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);
        if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        if (nameSortIcon != null) nameSortIcon.setColorFilter(hintColor);
        if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        if (timeSortIcon != null) timeSortIcon.setColorFilter(hintColor);
    }

    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);
        if (currentSortType == SortType.NAME) {
            if (timeSortIcon != null) {
                timeSortIcon.setImageResource(R.drawable.ic_sort_none);
                timeSortIcon.setColorFilter(hintColor);
            }
            if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        } else {
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        }
    }

    private void updateSortIcons() {
        int iconResource = switch (currentSortState) {
            case ASCENDING -> R.drawable.ic_sort_asc;
            case DESCENDING -> R.drawable.ic_sort_desc;
            default -> R.drawable.ic_sort_none;
        };

        int color = switch (currentSortState) {
            case DESCENDING -> getResources().getColor(R.color.accent, null);
            case ASCENDING -> getResources().getColor(R.color.pink, null);
            default -> getResources().getColor(R.color.hint, null);
        };

        if (currentSortType == SortType.NAME && nameSortIcon != null && nameSortLabel != null) {
            nameSortIcon.setImageResource(iconResource);
            nameSortIcon.setColorFilter(color);
            nameSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TIME && timeSortIcon != null && timeSortLabel != null) {
            timeSortIcon.setImageResource(iconResource);
            timeSortIcon.setColorFilter(color);
            timeSortLabel.setTextColor(color);
        }
    }

    private void sortAppList() {
        if (appCardContainer != null) {
            if (currentSortType == SortType.NAME) {
                appCardContainer.sortByStdName(currentSortState == SortState.ASCENDING,
                    currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TIME) {
                appCardContainer.sortByLastTime(currentSortState == SortState.ASCENDING,
                    currentSortState != SortState.NONE);
            }

            if (currentSortState == SortState.NONE) {
                appCardContainer.sortByLastTime(false, true);
            }
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

