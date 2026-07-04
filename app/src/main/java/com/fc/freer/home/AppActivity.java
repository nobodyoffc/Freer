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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.AppOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.AppManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.manager.CashManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.AppCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.fc.fc_ajdk.constants.IndicesNames.APP;

public class AppActivity extends BaseCryptoActivity {
    // Constants
    private static final String TAG = "Apps";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    // Core components
    private AppCardContainer appCardContainer;
    protected AppManager appManager;
    private WaitingDialog waitingDialog;

    // UI Elements - Buttons
    private ImageButton moreButton;
    private ImageButton createButton;
    private ImageButton hideButton;
    private ImageButton clearButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    // UI Elements - Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView appScrollView;
    private TextView appStatisticsTextView;

    // UI Elements - Sort
    private LinearLayout nameSortButton;
    private TextView nameSortLabel;
    private ImageView nameSortIcon;
    private LinearLayout ownerSortButton;
    private TextView ownerSortLabel;
    private ImageView ownerSortIcon;
    private LinearLayout timeSortButton;
    private TextView timeSortLabel;
    private ImageView timeSortIcon;

    // Enums
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { NAME, OWNER, TIME }

    // State management
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.TIME;
    private boolean scrollListenerSetup = false;
    private boolean isMyAppsMode = false;

    // Data and pagination
    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<App> appList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    // Activity result launchers
    private ActivityResultLauncher<Intent> updateAppLauncher;
    private ActivityResultLauncher<Intent> createAppLauncher;
    private ActivityResultLauncher<Intent> stopAppLauncher;
    private ActivityResultLauncher<Intent> closeAppLauncher;
    private ActivityResultLauncher<Intent> recoverAppLauncher;
    private ActivityResultLauncher<Intent> rateAppLauncher;
    private ActivityResultLauncher<Intent> hiddenAppLauncher;
    private ActivityResultLauncher<Intent> stoppedAppLauncher;

    // ================================
    // LIFECYCLE METHODS
    // ================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        // Initialize AppManager with current live FID
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

        // Load initial page of App objects
        loadInitialData();
    }

    // ================================
    // HELPER METHODS - BACKGROUND OPERATIONS
    // ================================

    private <T> void executeInBackground(String loadingMessage, BackgroundTask<T> task, ResultHandler<T> handler) {
        if (loadingMessage != null) {
            showWaitingDialog(loadingMessage);
        }

        new Thread(() -> {
            try {
                T result = task.execute();
                runOnUiThread(() -> {
                    if (loadingMessage != null) {
                        dismissWaitingDialog();
                    }
                    handler.handle(result);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Background task error: %s", e.getMessage());
                runOnUiThread(() -> {
                    if (loadingMessage != null) {
                        dismissWaitingDialog();
                    }
                    showErrorMessage("Error: " + e.getMessage());
                });
            }
        }).start();
    }

    @FunctionalInterface
    private interface BackgroundTask<T> {
        T execute() throws Exception;
    }

    @FunctionalInterface
    private interface ResultHandler<T> {
        void handle(T result);
    }

    // ================================
    // HELPER METHODS - UI MESSAGES
    // ================================

    private void showSuccessMessage(String message) {
        ToastUtils.makeText(this, message);
    }

    private void showErrorMessage(String message) {
        ToastUtils.makeText(this, message);
    }

    private void showInfoMessage(String message) {
        ToastUtils.makeText(this, message);
    }

    private void initializeActivityResultLaunchers() {
        // Initialize update app launcher
        updateAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize create app launcher
        createAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize stop app launcher
        stopAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // Get stopped app IDs from result
                    Intent data = result.getData();
                    if (data != null) {
                        String[] stoppedAppIds = data.getStringArrayExtra("stopped_app_ids");
                        if (stoppedAppIds != null && stoppedAppIds.length > 0) {
                            removeAppsFromContainerAndDB(stoppedAppIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        // Initialize close app launcher
        closeAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // Get closed app IDs from result
                    Intent data = result.getData();
                    if (data != null) {
                        String[] closedAppIds = data.getStringArrayExtra("closed_app_ids");
                        if (closedAppIds != null && closedAppIds.length > 0) {
                            removeAppsFromContainerAndDB(closedAppIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        // Initialize recover app launcher
        recoverAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize rate app launcher
        rateAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize hidden app launcher
        hiddenAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize stopped app launcher
        stoppedAppLauncher = registerForActivityResult(
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
        return R.layout.activity_app;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.apps);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        moreButton = findViewById(R.id.more_button);
        createButton = findViewById(R.id.create_button);
        hideButton = findViewById(R.id.hide_button);
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.app_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        appScrollView = findViewById(R.id.app_scroll_view);

        // Initialize search controls from base class
        setupSearchControls();

        // Initialize sort button elements
        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortLabel = findViewById(R.id.name_sort_label);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        ownerSortButton = findViewById(R.id.owner_sort_button);
        ownerSortLabel = findViewById(R.id.owner_sort_label);
        ownerSortIcon = findViewById(R.id.owner_sort_icon);
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortLabel = findViewById(R.id.time_sort_label);
        timeSortIcon = findViewById(R.id.time_sort_icon);

        // Set default colors for sort labels and icons to hint
        setDefaultSortColors();

        // Initialize statistics TextView
        appStatisticsTextView = findViewById(R.id.app_statistics);

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupNameSortButton();
        setupOwnerSortButton();
        setupTimeSortButton();

        // Set up swipe refresh
        setupSwipeRefresh();

        // Set up spinners
        setupAppSpinners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }

    // ================================
    // DATA LOADING METHODS
    // ================================

    private void loadInitialData() {
        executeInBackground(getString(R.string.loading_apps), () -> {
            appManager.refreshAppsFromAPI(this);
            return appManager.getPaginatedApps(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<App> firstPageList = appManager.getPaginatedApps(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<App> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_apps_found));
            updateUI();
            return;
        }

        appList.clear();
        appList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadAppCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<App> newList) {
        if (!appList.isEmpty()) {
            latestId = appList.get(0).getId();
            earliestId = appList.get(appList.size() - 1).getId();
            hasMoreData = newList.size() == pageSize;
            hasMoreEarlierData = hasMoreData;
            hasMoreNewerData = false;
        } else {
            resetPaginationState();
        }
    }

    private void resetPaginationState() {
        hasMoreData = false;
        hasMoreEarlierData = false;
        hasMoreNewerData = false;
        earliestId = null;
        latestId = null;
    }

    private void updateButtonStates() {
        // Button states can be updated here if needed in the future
    }

    private void loadAppCardList() {
        LinearLayout appListContainer = findViewById(R.id.fragment_container);

        // Clear existing cards before creating new container to prevent duplicates
        if (appCardContainer != null) {
            appCardContainer.clearAll();
        }

        appCardContainer = new AppCardContainer(this, appListContainer, ChooseMode.CHOOSE_MULTI);
        
        // Ensure edit button shows and clear button doesn't show in AppActivity
        appCardContainer.setHideEditButton(false);
        appCardContainer.setShowClearButton(false);

        appCardContainer.setOnAppListChangedListener(updatedAppList -> {
            updateUI();
        });

        appCardContainer.setUpdateAppLauncher(updateAppLauncher);

        // Set up edit icon click listener
        appCardContainer.setOnEditIconClickListener(this::handleEditIconClick);

        // Set up rate icon click listener
        appCardContainer.setOnRateIconClickListener(this::handleRateIconClick);

        // Set up off-chain icon click listener
        appCardContainer.setOnOffChainIconClickListener(this::sendAppOnChain);

        Map<String, String> cidMap = appCardContainer.getCidMap(appList, this);

        for (App app : appList) {
            appCardContainer.addAppCard(app, cidMap);
        }

        updateUI();
        setupScrollListener();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && appCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    appCardContainer.selectAll(isChecked);
                }
            });
        }
    }

    private void setupNameSortButton() {
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
    }

    private void setupOwnerSortButton() {
        if (ownerSortButton != null) {
            ownerSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.OWNER) {
                    currentSortType = SortType.OWNER;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortAppList();
            });
        }
    }

    private void setupTimeSortButton() {
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

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && appCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (appCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (appCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    @Override
    protected void onExitSearchMode() {
        if (appCardContainer != null) {
            appCardContainer.clearAll();
        }
        isMyAppsMode = false;
        loadFirstPage();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        performSearchFromAPI(null);
    }

    private void performSearchFromAPI(App referenceApp) {
        if (appManager == null) {
            return;
        }

        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<App> searchResults;
                if (isMyAppsMode) {
                    searchResults = appManager.searchMyAppsFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceApp
                    );
                } else {
                    searchResults = appManager.searchAppsFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceApp
                    );
                }

                runOnUiThread(() -> {
                    if (searchResults != null && !searchResults.isEmpty()) {
                        if (referenceApp == null) {
                            appList.clear();
                            appList.addAll(searchResults);
                            hasMoreEarlierData = searchResults.size() >= pageSize;
                            hasMoreNewerData = false;
                        }

                        updatePaginationState(searchResults);

                        if (appCardContainer != null) {
                            appCardContainer.clearAll();
                        }
                        loadAppCardList();

                        dismissWaitingDialog();
                        showSuccessMessage(getString(R.string.found_apps_count, searchResults.size()));
                    } else {
                        dismissWaitingDialog();
                        showInfoMessage(getString(R.string.no_apps_found));
                    }

                    updateUI();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error performing search: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showErrorMessage(getString(R.string.search_failed) + ": " + e.getMessage());
                });
            }
        }).start();
    }

    private void setupAppSpinners() {
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        if (searchFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> searchableFields = App.getSearchableFields();

            List<String> fieldOptionsList = new ArrayList<>();
            fieldOptionsList.add(getString(R.string.search_in));

            for (Map.Entry<String, Map<String, String>> entry : searchableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                fieldOptionsList.add(displayName);
            }

            String[] fieldOptions = fieldOptionsList.toArray(new String[0]);
            setupSpinner(searchFieldSpinner, fieldOptions, R.string.field);
        }

        if (sortFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> sortableFields = App.getSortableFields();

            List<String> sortOptionsList = new ArrayList<>();
            sortOptionsList.add(getString(R.string.sort_by));

            for (Map.Entry<String, Map<String, String>> entry : sortableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                sortOptionsList.add(displayName);
            }

            String[] sortOptions = sortOptionsList.toArray(new String[0]);
            setupSpinner(sortFieldSpinner, sortOptions, R.string.field);
        }

        if (sortOrderSpinner != null) {
            String[] orderOptions = new String[]{"Order", "DESC", "ASC"};
            setupSpinner(sortOrderSpinner, orderOptions, R.string.order);
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer apps");
                loadNewerApps();
            });

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
                boolean atTop = isAtTop(appScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerAppToContainer();
                    }
                }
            });
        }
    }

    private boolean isAtTop(View scrollView) {
        if (scrollView instanceof ScrollView sv) {
            int scrollY = sv.getScrollY();
            return scrollY <= 10;
        }
        return false;
    }

    private void loadNewerApps() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        loadMoreNewerFromDatabase();
    }

    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer apps from local database");

        if (appList.isEmpty() || appList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = appList.get(0).getId();
            List<App> newerItems = appManager.getPaginatedApps(pageSize, newestId, false);

            if (newerItems != null && !newerItems.isEmpty()) {
                handleNewerItemsFromDatabase(newerItems);
            } else {
                TimberLogger.i(TAG, "No newer items in database, trying API");
                loadNewerFromAPI();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer from database: %s", e.getMessage());
            stopSwipeRefresh();
            loadNewerFromAPI();
        }
    }

    private void handleNewerItemsFromDatabase(List<App> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        // Remove old cards with matching IDs before adding new ones
        removeDuplicateApps(newerItems);

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        removeEarlier(itemsToRemoveFromEnd);

        java.util.Collections.reverse(newerItems);
        appList.addAll(0, newerItems);
        appCardContainer.addAppCardsToBeginning(newerItems);

        latestId = appList.get(0).getId();
        hasMoreNewerData = newerItems.size() >= pageSize;

        updateUI();
        showSuccessMessage("Loaded " + newerItems.size() + " newer apps from database");
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = appList.size();
        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
        int newTotalSize = currentSize + newItemsSize;
        return Math.max(0, newTotalSize - maxWindowSize);
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
        currentSortState = SortState.NONE;
        currentSortType = SortType.TIME;
        setDefaultSortColors();
    }

    /**
     * Removes old app cards that have the same IDs as the new apps being added
     * This prevents duplicates when loading newer apps from API
     */
    private void removeDuplicateApps(List<App> newApps) {
        if (newApps == null || newApps.isEmpty() || appCardContainer == null) {
            return;
        }

        // Collect IDs of new apps
        Set<String> newAppIds = new HashSet<>();
        for (App newApp : newApps) {
            if (newApp.getId() != null) {
                newAppIds.add(newApp.getId());
            }
        }

        if (newAppIds.isEmpty()) {
            return;
        }

        // Remove old apps with matching IDs
        int removedCount = 0;
        for (int i = appList.size() - 1; i >= 0; i--) {
            App existingApp = appList.get(i);
            if (existingApp.getId() != null && newAppIds.contains(existingApp.getId())) {
                appCardContainer.removeAppById(existingApp.getId());
                appList.remove(i);
                removedCount++;
            }
        }

        if (removedCount > 0) {
            TimberLogger.i(TAG, "Removed %d duplicate app(s) with matching IDs", removedCount);
        }
    }

    private void removeEarlier(int itemsToRemoveFromEnd) {
        if (itemsToRemoveFromEnd > 0) {
            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
            appCardContainer.removeFromEnd(itemsToRemoveFromEnd);

            if (appCardContainer.getAppList() != null) {
                appList.clear();
                appList.addAll(appCardContainer.getAppList());
            }

            if (!appList.isEmpty()) {
                earliestId = appList.get(appList.size() - 1).getId();
                hasMoreEarlierData = true;
            }

            updateStatistics();
        }
    }

    private void loadNewerFromAPI() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;
        executeInBackground(null, () -> {
            int added = appManager.loadNewerAppsFromAPI(this);
            appManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_apps_db));
            } else if (added > 0) {
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, APP));
            } else {
                showInfoMessage(getString(R.string.no_new_apps_found));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reloading from database after API sync: %s", e.getMessage());
            refreshList();
        } finally {
            isLoadingNewer = false;
            stopSwipeRefresh();
        }
    }

    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more apps from local database");

        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            int currentSize = appList.size();
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            List<App> moreItems = appManager.getPaginatedApps(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (itemsToRemoveFromBeginning > 0) {
                    TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", itemsToRemoveFromBeginning);
                    appCardContainer.removeFromBeginning(itemsToRemoveFromBeginning);

                    if (appCardContainer.getAppList() != null) {
                        appList.clear();
                        appList.addAll(appCardContainer.getAppList());
                    }

                    if (!appList.isEmpty()) {
                        latestId = appList.get(0).getId();
                        hasMoreNewerData = true;
                    }
                }

                appList.addAll(moreItems);
                updateStatistics();

                Map<String, String> cidMap = appCardContainer.getCidMap(appList, this);
                for (App newApp : moreItems) {
                    appCardContainer.addAppCard(newApp, cidMap);
                }

                earliestId = appList.get(appList.size() - 1).getId();

                TimberLogger.i(TAG, "Loaded %d app items, window size: %d/%d",
                    moreItems.size(), appList.size(), maxWindowSize);
                ToastUtils.makeText(this, getString(R.string.toast_loaded_apps, moreItems.size()));
            } else {
                loadEarlierApps();
            }

        } catch (Exception e) {
            loadEarlierApps();
        }
    }

    private void loadNewerAppToContainer() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if (!hasMoreNewerData) {
            ToastUtils.makeText(this, getString(R.string.no_more_data));
            return;
        }

        TimberLogger.i(TAG, "Loading newer apps to container from local database");

        try {
            int currentSize = appList.size();
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            String latestIdValue = latestId;
            if (latestIdValue == null) {
                TimberLogger.w(TAG, "Latest ID is null, cannot load newer apps");
                return;
            }

            List<App> newerItems = appManager.getPaginatedApps(pageSize, latestIdValue, true);

            if (newerItems != null && !newerItems.isEmpty()) {
                // Remove old cards with matching IDs before adding new ones
                removeDuplicateApps(newerItems);

                int newTotalSize = appList.size() + newerItems.size();
                int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                if (itemsToRemoveFromEnd > 0) {
                    TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
                    appCardContainer.removeFromEnd(itemsToRemoveFromEnd);

                    if (!appList.isEmpty()) {
                        earliestId = appList.get(appList.size() - 1).getId();
                        hasMoreEarlierData = true;
                    }

                    updateStatistics();
                }

                appCardContainer.addAppCardsToBeginning(newerItems);
                
                // Update appList to include new items
                if (appCardContainer.getAppList() != null) {
                    appList.clear();
                    appList.addAll(appCardContainer.getAppList());
                }
                
                if (!appList.isEmpty()) {
                    latestId = appList.get(0).getId();
                }

                if (newerItems.size() < pageSize) {
                    hasMoreNewerData = false;
                }

                TimberLogger.i(TAG, "Loaded %d newer app items to container, window size: %d/%d",
                    newerItems.size(), appList.size(), maxWindowSize);
                ToastUtils.makeText(this, getString(R.string.toast_loaded_newer_apps, newerItems.size()));
            } else {
                hasMoreNewerData = false;
                TimberLogger.i(TAG, "No more newer app items available");
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer apps to container: %s", e.getMessage());
        }
    }

    private void loadEarlierApps() {
        if (isLoadingNewer || isLoadingEarlier) {
            TimberLogger.i(TAG, "Already loading data, skipping");
            return;
        }

        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        isLoadingEarlier = true;
        ToastUtils.makeText(this, getString(R.string.loading_more_from_apip));
        executeInBackground(null, () -> {
            int added = appManager.loadEarlierAppsFromAPI(this);
            appManager.commit();
            return added;
        }, this::handleEarlierAppsResult);
    }

    private void handleEarlierAppsResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, APP));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                updateStatistics();
                showInfoMessage(getString(R.string.no_more_earlier_apps_found));
            } else {
                showErrorMessage(getString(R.string.failed_to_update_apps_db));
            }
        } finally {
            isLoadingEarlier = false;
        }
    }

    // ================================
    // UI SETUP AND EVENT HANDLERS
    // ================================

    @Override
    protected void setupButtons() {
        // Set click listener for More button to show popup menu
        if (moreButton != null) {
            moreButton.setOnClickListener(this::showMoreMenu);
        }

        // Set click listener for Create button
        if (createButton != null) {
            createButton.setOnClickListener(v -> {
                hideKeyboard();
                Intent intent = new Intent(this, CreateAppActivity.class);
                createAppLauncher.launch(intent);
            });
        }

        // Set click listener for Hide button
        if (hideButton != null) {
            hideButton.setOnClickListener(v -> {
                hideKeyboard();
                handleHideApps();
            });
        }

        // Set click listener for Load More button
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    loadMoreButton.setEnabled(false);
                    loadMoreButton.setAlpha(0.5f);
                    loadMoreEarlierFromDatabase();
                    new android.os.Handler().postDelayed(() -> {
                        if (loadMoreButton != null) {
                            loadMoreButton.setEnabled(true);
                            loadMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }

        // Set click listener for Clear button
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                clearSearchControls();
                onExitSearchMode();
            });
        }

        // Set click listener for Back button
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    /**
     * Searches for apps owned by the current liveFid
     */
    private void searchMyApps() {
        isMyAppsMode = true;
        isSearchMode = true;

        // Update search bar to show "My Apps"
        if (searchEditText != null) {
            searchEditText.setText("");
            searchEditText.setHint(R.string.my_apps);
        }

        // Reset spinners
        if (searchFieldSpinner != null) {
            searchFieldSpinner.setSelection(0);
        }
        if (sortFieldSpinner != null) {
            sortFieldSpinner.setSelection(0);
        }
        if (sortOrderSpinner != null) {
            sortOrderSpinner.setSelection(1); // DESC
        }

        currentSearchQuery = "";
        currentSearchField = null;
        currentSortField = null;
        currentSortOrder = "desc";

        performSearchFromAPI(null);
    }

    // ================================
    // POPUP MENU
    // ================================

    private void showMoreMenu(View anchorView) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_app_more, null);
        PopupWindow popupWindow = new PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        );

        // Configure popup window - these settings are required for the popup to display properly
        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        // Set background drawable - required for PopupWindow to render properly
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        // Set click listeners for menu items
        popupView.findViewById(R.id.stop_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleStopApps();
        });

        popupView.findViewById(R.id.close_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleCloseApps();
        });

        popupView.findViewById(R.id.stopped_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowStoppedApps();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowHiddenApps();
        });

        popupView.findViewById(R.id.refresh_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        popupView.findViewById(R.id.my_apps_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            searchMyApps();
        });

        popupView.findViewById(R.id.about_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            com.fc.freer.utils.AboutDialog.show(this, R.string.about_app_title, R.string.about_app_message);
        });

        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        // Show the popup above the anchor view to prevent it from being cut off at the bottom
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleStopApps() {
        if (appCardContainer == null) return;
        List<App> selectedApps = appCardContainer.getSelectedApps();
        if (selectedApps.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        // Filter to only active apps (not already stopped)
        List<App> activeApps = new ArrayList<>();
        for (App app : selectedApps) {
            if (Boolean.TRUE.equals(app.isActive())) {
                activeApps.add(app);
            }
        }

        if (activeApps.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_active_apps_selected);
            return;
        }

        // Launch StopAppActivity
        Intent intent = new Intent(this, StopAppActivity.class);
        intent.putExtra(StopAppActivity.EXTRA_APP_IDS, getAppIds(activeApps));
        stopAppLauncher.launch(intent);
    }

    private void handleCloseApps() {
        if (appCardContainer == null) return;
        List<App> selectedApps = appCardContainer.getSelectedApps();
        if (selectedApps.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        // Filter to only non-closed apps
        List<App> closableApps = new ArrayList<>();
        for (App app : selectedApps) {
            if (!Boolean.TRUE.equals(app.isClosed())) {
                closableApps.add(app);
            }
        }

        if (closableApps.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_closable_apps_selected);
            return;
        }

        // Launch CloseAppActivity
        Intent intent = new Intent(this, CloseAppActivity.class);
        intent.putExtra(CloseAppActivity.EXTRA_APP_IDS, getAppIds(closableApps));
        closeAppLauncher.launch(intent);
    }

    /**
     * Shows the StoppedAppActivity to display stopped/closed apps from API
     */
    private void handleShowStoppedApps() {
        Intent intent = new Intent(this, StoppedAppActivity.class);
        stoppedAppLauncher.launch(intent);
    }

    /**
     * Shows the HiddenAppActivity to display hidden apps
     */
    private void handleShowHiddenApps() {
        Intent intent = new Intent(this, HiddenAppActivity.class);
        hiddenAppLauncher.launch(intent);
    }

    private void handleHideApps() {
        if (appCardContainer == null) return;
        List<App> chosenObjects = appCardContainer.getSelectedApps();
        if (chosenObjects.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        appManager.addToLocalDeletedList(chosenObjects);
        appManager.removeApps(chosenObjects);
        appManager.commit();

        refreshList();
        showSuccessMessage("Hidden " + chosenObjects.size() + " app(s)");
    }

    private String[] getAppIds(List<App> apps) {
        String[] ids = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            ids[i] = apps.get(i).getId();
        }
        return ids;
    }

    /**
     * Removes apps from both the card container and local database
     * Used when apps are successfully stopped or closed
     */
    private void removeAppsFromContainerAndDB(String[] appIds) {
        if (appIds == null || appIds.length == 0 || appManager == null) {
            return;
        }

        List<App> appsToRemove = new ArrayList<>();
        
        // Find apps in the current list
        for (String appId : appIds) {
            if (appId == null) continue;
            
            // Remove from container
            if (appCardContainer != null) {
                appCardContainer.removeAppById(appId);
            }
            
            // Find and collect for database removal
            for (App app : appList) {
                if (appId.equals(app.getId())) {
                    appsToRemove.add(app);
                    break;
                }
            }
        }

        // Remove from appList
        appList.removeAll(appsToRemove);

        // Remove from database
        if (!appsToRemove.isEmpty()) {
            appManager.removeApps(appsToRemove);
            appManager.commit();
            TimberLogger.i(TAG, "Removed %d stopped/closed app(s) from container and database", appsToRemove.size());
        }

        // Update UI
        updateUI();
    }

    // ================================
    // ICON CLICK HANDLERS
    // ================================

    private void handleEditIconClick(App app) {
        // Check if user is the owner
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            showErrorMessage("No active FID");
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (!liveFid.equals(app.getOwner())) {
            showErrorMessage(getString(R.string.only_owner_can_update));
            return;
        }

        // Launch UpdateAppActivity
        Intent intent = new Intent(this, UpdateAppActivity.class);
        intent.putExtra(UpdateAppActivity.EXTRA_APP_JSON, app.toJson());
        updateAppLauncher.launch(intent);
    }

    private void handleRateIconClick(App app) {
        // Launch RateActivity
        Intent intent = new Intent(this, RateActivity.class);
        intent.putExtra(RateActivity.EXTRA_APP_JSON, app.toJson());
        rateAppLauncher.launch(intent);
    }

    /**
     * Sends an off-chain app to the blockchain with 'publish' or 'update' operation
     * Only allows carving when onChain is false (not carved yet)
     * onChain = true: confirmed on-chain, cannot carve again
     * onChain = null: carved but pending confirmation, cannot carve again
     * onChain = false: off-chain, can be carved
     */
    private void sendAppOnChain(App app) {
        if (app == null) {
            ToastUtils.makeText(this, R.string.app_data_not_found);
            return;
        }

        Boolean onChain = app.getOnChain();
        
        // Only allow carving when onChain is explicitly false
        if (Boolean.TRUE.equals(onChain)) {
            ToastUtils.makeText(this, R.string.app_is_already_on_chain);
            return;
        }
        
        if (onChain == null) {
            // Already carved but pending confirmation
            ToastUtils.makeText(this, R.string.app_pending_confirmation);
            return;
        }
        
        // onChain == false: off-chain, can be carved

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Determine if this is a new app (publish) or an existing app (update)
        // New apps created locally have IDs starting with "local_"
        boolean isNewApp = app.getId() != null && app.getId().startsWith("local_");
        
        AppOpData appOpData;
        if (isNewApp) {
            // Create FEIP data for PUBLISH (new app on-chain)
            appOpData = AppOpData.makePublish(
                    // aid is null for new publish
                app.getStdName(),
                app.getLocalNames(),
                app.getDesc(),
                app.getTypes(),
                app.getHome(),
                app.getDownloads(),
                app.getWaiters(),
                app.getProtocols(),
                app.getCodes(),
                app.getServices()
            );
        } else {
            // Create FEIP data for UPDATE (existing on-chain app)
            appOpData = AppOpData.makeUpdate(
                app.getId(), // aid is the existing app ID
                app.getStdName(),
                app.getLocalNames(),
                app.getDesc(),
                app.getTypes(),
                app.getHome(),
                app.getDownloads(),
                app.getWaiters(),
                app.getProtocols(),
                app.getCodes(),
                app.getServices()
            );
        }

        if (app.getVer() != null && !app.getVer().isEmpty()) {
            appOpData.setVer(app.getVer());
        }

        Feip feip = Feip.fromName(APP);
        feip.setData(appOpData);
        String feipJson = feip.toJson();

        // Show waiting dialog before fetching private key
        showWaitingDialog(getString(R.string.carving_app_on_chain));

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            dismissWaitingDialog();
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        new Thread(() -> {
            try {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();

                                // Check if this was a new app (publish) or existing app (update)
                                boolean wasNewApp = app.getId() != null && app.getId().startsWith("local_");
                                
                                if (wasNewApp) {
                                    // For new apps: remove old local entry and create new with txId
                                    appManager.removeApp(app);
                                    app.setId(txId);
                                }
                                // For updates: keep the same ID
                                
                                app.setOnChain(null); // null indicates pending confirmation
                                app.setLastTime(System.currentTimeMillis());

                                // Save the updated app to database
                                if (wasNewApp) {
                                    appManager.addApp(app);
                                } else {
                                    appManager.updateApp(app);
                                }
                                appManager.commit();

                                // Refresh the UI to show updated status
                                refreshList();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                ToastUtils.makeText(AppActivity.this,
                                    getString(R.string.failed_to_carve_app) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                ToastUtils.makeText(AppActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(AppActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                txSender.showSignedTxAsQR(AppActivity.this, signedTxHex);
                            });
                        }
                    });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(AppActivity.this,
                        getString(R.string.failed_to_carve_app) + ": " + e.getMessage());
                });
            } finally {
                dismissWaitingDialog();
                BytesUtils.clearByteArray(prikey);
            }
        }).start();
    }

    // ================================
    // UTILITY METHODS
    // ================================

    private void refreshList() {
        clearSearchControls();

        if (appCardContainer != null) {
            appCardContainer.clearAll();
        }

        appList.clear();

        earliestId = null;
        latestId = null;
        hasMoreData = true;
        hasMoreEarlierData = true;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingEarlier = false;
        scrollListenerSetup = false;
        isMyAppsMode = false;

        updateUI();
        loadFirstPage();
    }

    private void clearAndReload() {
        if (appManager == null) {
            ToastUtils.makeText(this, getString(R.string.app_not_ready_try_later));
            return;
        }

        showWaitingDialog(getString(R.string.clearing_and_reloading));

        executeInBackground(null, () -> {
            appManager.clearLocalDeletedList();
            TimberLogger.i(TAG, "Cleared local deleted list");

            int count = appManager.freshAppDB(this);
            TimberLogger.i(TAG, "Refreshed app database, loaded %d apps", count);

            return appManager.getPaginatedApps(pageSize, null, true);
        }, result -> {
            if (appCardContainer != null) {
                appCardContainer.clearAll();
            }
            appList.clear();

            clearSearchControls();

            earliestId = null;
            latestId = null;
            hasMoreData = true;
            hasMoreEarlierData = true;
            hasMoreNewerData = false;
            isLoadingNewer = false;
            isLoadingEarlier = false;
            scrollListenerSetup = false;
            isMyAppsMode = false;

            showItemList(result);
            showSuccessMessage(getString(R.string.database_cleared_and_reloaded));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        dismissWaitingDialog();

        if (!scrollListenerSetup) {
            if (appScrollView != null) {
                setupScrollListener();
                scrollListenerSetup = true;
            } else {
                new android.os.Handler().postDelayed(() -> {
                    if (appScrollView != null && !scrollListenerSetup) {
                        setupScrollListener();
                        scrollListenerSetup = true;
                    }
                }, 100);
            }
        }
    }

    private void cycleSortState() {
        switch (currentSortState) {
            case NONE:
                currentSortState = SortState.ASCENDING;
                break;
            case ASCENDING:
                currentSortState = SortState.DESCENDING;
                break;
            case DESCENDING:
                currentSortState = SortState.NONE;
                break;
        }
    }

    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);

        if (currentSortType == SortType.NAME) {
            if (ownerSortIcon != null) {
                ownerSortIcon.setImageResource(R.drawable.ic_sort_none);
                ownerSortIcon.setColorFilter(hintColor);
            }
            if (ownerSortLabel != null) {
                ownerSortLabel.setTextColor(hintColor);
            }
            if (timeSortIcon != null) {
                timeSortIcon.setImageResource(R.drawable.ic_sort_none);
                timeSortIcon.setColorFilter(hintColor);
            }
            if (timeSortLabel != null) {
                timeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.OWNER) {
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(hintColor);
            }
            if (timeSortIcon != null) {
                timeSortIcon.setImageResource(R.drawable.ic_sort_none);
                timeSortIcon.setColorFilter(hintColor);
            }
            if (timeSortLabel != null) {
                timeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.TIME) {
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(hintColor);
            }
            if (ownerSortIcon != null) {
                ownerSortIcon.setImageResource(R.drawable.ic_sort_none);
                ownerSortIcon.setColorFilter(hintColor);
            }
            if (ownerSortLabel != null) {
                ownerSortLabel.setTextColor(hintColor);
            }
        }
    }

    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);

        if (nameSortLabel != null) {
            nameSortLabel.setTextColor(hintColor);
        }
        if (nameSortIcon != null) {
            nameSortIcon.setColorFilter(hintColor);
        }
        if (ownerSortLabel != null) {
            ownerSortLabel.setTextColor(hintColor);
        }
        if (ownerSortIcon != null) {
            ownerSortIcon.setColorFilter(hintColor);
        }
        if (timeSortLabel != null) {
            timeSortLabel.setTextColor(hintColor);
        }
        if (timeSortIcon != null) {
            timeSortIcon.setColorFilter(hintColor);
        }
    }

    private void updateSortIcons() {
        int iconResource = switch (currentSortState) {
            case ASCENDING -> R.drawable.ic_sort_asc;
            case DESCENDING -> R.drawable.ic_sort_desc;
            default -> R.drawable.ic_sort_none;
        };

        int color;
        switch (currentSortState) {
            case DESCENDING:
                color = getResources().getColor(R.color.accent, null);
                break;
            case ASCENDING:
                color = getResources().getColor(R.color.pink, null);
                break;
            default:
                color = getResources().getColor(R.color.hint, null);
                break;
        }

        if (currentSortType == SortType.NAME && nameSortIcon != null && nameSortLabel != null) {
            nameSortIcon.setImageResource(iconResource);
            nameSortIcon.setColorFilter(color);
            nameSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.OWNER && ownerSortIcon != null && ownerSortLabel != null) {
            ownerSortIcon.setImageResource(iconResource);
            ownerSortIcon.setColorFilter(color);
            ownerSortLabel.setTextColor(color);
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
            } else if (currentSortType == SortType.OWNER) {
                appCardContainer.sortByOwner(currentSortState == SortState.ASCENDING,
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

    private void updateStatistics() {
        if (appStatisticsTextView == null || appManager == null) {
            return;
        }

        int containerSize = appList != null ? appList.size() : 0;
        long dbSize = appManager.getAppDBSize();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        statsText.append(getString(R.string.local_in_statistics)).append(dbSize);

        appStatisticsTextView.setText(statsText.toString());

        LinearLayout statisticsContainer = findViewById(R.id.app_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreEarlierData && !isSearchMode ? View.VISIBLE : View.GONE);
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

