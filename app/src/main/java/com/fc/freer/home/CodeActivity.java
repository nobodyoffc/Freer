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

import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.CodeManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.CodeCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.fc.fc_ajdk.constants.IndicesNames.CODE;

public class CodeActivity extends BaseCryptoActivity {
    private static final String TAG = "Codes";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    private CodeCardContainer codeCardContainer;
    protected CodeManager codeManager;
    private WaitingDialog waitingDialog;

    private ImageButton moreButton;
    private ImageButton createButton;
    private ImageButton hideButton;
    private ImageButton clearButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView codeScrollView;
    private TextView codeStatisticsTextView;

    private LinearLayout nameSortButton;
    private TextView nameSortLabel;
    private ImageView nameSortIcon;
    private LinearLayout ownerSortButton;
    private TextView ownerSortLabel;
    private ImageView ownerSortIcon;
    private LinearLayout timeSortButton;
    private TextView timeSortLabel;
    private ImageView timeSortIcon;

    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { NAME, OWNER, TIME }

    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.TIME;
    private boolean scrollListenerSetup = false;
    private boolean isMyCodesMode = false;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Code> codeList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    private ActivityResultLauncher<Intent> updateCodeLauncher;
    private ActivityResultLauncher<Intent> createCodeLauncher;
    private ActivityResultLauncher<Intent> stopCodeLauncher;
    private ActivityResultLauncher<Intent> closeCodeLauncher;
    private ActivityResultLauncher<Intent> recoverCodeLauncher;
    private ActivityResultLauncher<Intent> rateCodeLauncher;
    private ActivityResultLauncher<Intent> hiddenCodeLauncher;
    private ActivityResultLauncher<Intent> stoppedCodeLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeActivityResultLaunchers();

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                codeManager = CodeManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize CodeManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing CodeManager with liveFid: " + e.getMessage());
        }

        if (codeManager == null) {
            ToastUtils.makeText(this, getString(R.string.app_not_ready_try_later));
            finish();
            return;
        }

        loadInitialData();
    }

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
        updateCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        createCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        stopCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String[] stoppedIds = data.getStringArrayExtra("stopped_code_ids");
                        if (stoppedIds != null && stoppedIds.length > 0) {
                            removeCodesFromContainerAndDB(stoppedIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        closeCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String[] closedIds = data.getStringArrayExtra("closed_code_ids");
                        if (closedIds != null && closedIds.length > 0) {
                            removeCodesFromContainerAndDB(closedIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        recoverCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        rateCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        hiddenCodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        stoppedCodeLauncher = registerForActivityResult(
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
        return R.layout.activity_code;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.codes);
    }

    @Override
    protected void initializeViews() {
        moreButton = findViewById(R.id.more_button);
        createButton = findViewById(R.id.create_button);
        hideButton = findViewById(R.id.hide_button);
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.code_more_button);

        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        codeScrollView = findViewById(R.id.code_scroll_view);

        setupSearchControls();

        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortLabel = findViewById(R.id.name_sort_label);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        ownerSortButton = findViewById(R.id.owner_sort_button);
        ownerSortLabel = findViewById(R.id.owner_sort_label);
        ownerSortIcon = findViewById(R.id.owner_sort_icon);
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortLabel = findViewById(R.id.time_sort_label);
        timeSortIcon = findViewById(R.id.time_sort_icon);

        setDefaultSortColors();

        codeStatisticsTextView = findViewById(R.id.code_statistics);

        setupSelectAllCheckBox();
        setupNameSortButton();
        setupOwnerSortButton();
        setupTimeSortButton();
        setupSwipeRefresh();
        setupCodeSpinners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }

    private void loadInitialData() {
        executeInBackground(getString(R.string.loading), () -> {
            codeManager.refreshCodesFromAPI(this);
            return codeManager.getPaginatedCodes(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Code> firstPageList = codeManager.getPaginatedCodes(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<Code> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_active_codes_found));
            updateUI();
            return;
        }

        codeList.clear();
        codeList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadCodeCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<Code> newList) {
        if (!codeList.isEmpty()) {
            latestId = codeList.get(0).getId();
            earliestId = codeList.get(codeList.size() - 1).getId();
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
    }

    private void loadCodeCardList() {
        LinearLayout codeListContainer = findViewById(R.id.fragment_container);

        if (codeCardContainer != null) {
            codeCardContainer.clearAll();
        }

        codeCardContainer = new CodeCardContainer(this, codeListContainer, ChooseMode.CHOOSE_MULTI);
        
        codeCardContainer.setHideEditButton(false);
        codeCardContainer.setShowClearButton(false);

        codeCardContainer.setOnCodeListChangedListener(updatedList -> {
            updateUI();
        });

        codeCardContainer.setUpdateCodeLauncher(updateCodeLauncher);

        codeCardContainer.setOnEditIconClickListener(this::handleEditIconClick);
        codeCardContainer.setOnRateIconClickListener(this::handleRateIconClick);
        codeCardContainer.setOnOffChainIconClickListener(this::handleOffChainIconClick);

        Map<String, String> cidMap = codeCardContainer.getCidMap(codeList, this);

        for (Code code : codeList) {
            codeCardContainer.addCodeCard(code, cidMap);
        }

        updateUI();
        setupScrollListener();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && codeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    codeCardContainer.selectAll(isChecked);
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
                sortCodeList();
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
                sortCodeList();
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
                sortCodeList();
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && codeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (codeCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (codeCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    @Override
    protected void onExitSearchMode() {
        if (codeCardContainer != null) {
            codeCardContainer.clearAll();
        }
        isMyCodesMode = false;
        loadFirstPage();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        performSearchFromAPI(null);
    }

    private void performSearchFromAPI(Code referenceCode) {
        if (codeManager == null) {
            return;
        }

        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<Code> searchResults;
                if (isMyCodesMode) {
                    searchResults = codeManager.searchMyCodesFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceCode
                    );
                } else {
                    searchResults = codeManager.searchCodesFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceCode
                    );
                }

                runOnUiThread(() -> {
                    if (searchResults != null && !searchResults.isEmpty()) {
                        if (referenceCode == null) {
                            codeList.clear();
                            codeList.addAll(searchResults);
                            hasMoreEarlierData = searchResults.size() >= pageSize;
                            hasMoreNewerData = false;
                        }

                        updatePaginationState(searchResults);

                        if (codeCardContainer != null) {
                            codeCardContainer.clearAll();
                        }
                        loadCodeCardList();

                        dismissWaitingDialog();
                        showSuccessMessage(getString(R.string.found_apps_count, searchResults.size()));
                    } else {
                        dismissWaitingDialog();
                        showInfoMessage(getString(R.string.no_active_codes_found));
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

    private void setupCodeSpinners() {
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        if (searchFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> searchableFields = Code.getSearchableFields();

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
            LinkedHashMap<String, Map<String, String>> sortableFields = Code.getSortableFields();

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
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer codes");
                loadNewerCodes();
            });

            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    private void setupScrollListener() {
        if (codeScrollView != null) {
            codeScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(codeScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerCodeToContainer();
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

    private void loadNewerCodes() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        loadMoreNewerFromDatabase();
    }

    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer codes from local database");

        if (codeList.isEmpty() || codeList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = codeList.get(0).getId();
            List<Code> newerItems = codeManager.getPaginatedCodes(pageSize, newestId, false);

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

    private void handleNewerItemsFromDatabase(List<Code> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        removeDuplicateCodes(newerItems);

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        removeEarlier(itemsToRemoveFromEnd);

        java.util.Collections.reverse(newerItems);
        codeList.addAll(0, newerItems);
        codeCardContainer.addCodeCardsToBeginning(newerItems);

        latestId = codeList.get(0).getId();
        hasMoreNewerData = newerItems.size() >= pageSize;

        updateUI();
        showSuccessMessage(getString(R.string.loaded_newer_codes, newerItems.size()));
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = codeList.size();
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

    private void removeDuplicateCodes(List<Code> newCodes) {
        if (newCodes == null || newCodes.isEmpty() || codeCardContainer == null) {
            return;
        }

        Set<String> newIds = new HashSet<>();
        for (Code newCode : newCodes) {
            if (newCode.getId() != null) {
                newIds.add(newCode.getId());
            }
        }

        if (newIds.isEmpty()) {
            return;
        }

        int removedCount = 0;
        for (int i = codeList.size() - 1; i >= 0; i--) {
            Code existing = codeList.get(i);
            if (existing.getId() != null && newIds.contains(existing.getId())) {
                codeCardContainer.removeCodeById(existing.getId());
                codeList.remove(i);
                removedCount++;
            }
        }

        if (removedCount > 0) {
            TimberLogger.i(TAG, "Removed %d duplicate code(s) with matching IDs", removedCount);
        }
    }

    private void removeEarlier(int itemsToRemoveFromEnd) {
        if (itemsToRemoveFromEnd > 0) {
            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
            codeCardContainer.removeFromEnd(itemsToRemoveFromEnd);

            if (codeCardContainer.getCodeList() != null) {
                codeList.clear();
                codeList.addAll(codeCardContainer.getCodeList());
            }

            if (!codeList.isEmpty()) {
                earliestId = codeList.get(codeList.size() - 1).getId();
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
            int added = codeManager.loadNewerCodesFromAPI(this);
            codeManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_apps_db));
            } else if (added > 0) {
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, CODE));
            } else {
                showInfoMessage(getString(R.string.no_active_codes_found));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reloading from database after API sync: %s", e.getMessage());
            refreshList();
        } finally {
            isLoadingNewer = false;
            stopSwipeRefresh();
        }
    }

    private void loadNewerCodeToContainer() {
        // Implementation for scroll-triggered loading
    }

    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more codes from local database");

        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            List<Code> moreItems = codeManager.getPaginatedCodes(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                int currentSize = codeList.size();
                int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (itemsToRemoveFromBeginning > 0) {
                    codeCardContainer.removeFromBeginning(itemsToRemoveFromBeginning);

                    if (codeCardContainer.getCodeList() != null) {
                        codeList.clear();
                        codeList.addAll(codeCardContainer.getCodeList());
                    }

                    if (!codeList.isEmpty()) {
                        latestId = codeList.get(0).getId();
                        hasMoreNewerData = true;
                    }
                }

                codeList.addAll(moreItems);
                Map<String, String> cidMap = codeCardContainer.getCidMap(moreItems, this);
                for (Code code : moreItems) {
                    codeCardContainer.addCodeCard(code, cidMap);
                }

                earliestId = codeList.get(codeList.size() - 1).getId();
                hasMoreEarlierData = moreItems.size() >= pageSize;

                updateStatistics();
                showSuccessMessage(getString(R.string.loaded_earlier_codes, moreItems.size()));
            } else {
                hasMoreEarlierData = false;
                updateStatistics();
                loadEarlierCodes();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more from database: %s", e.getMessage());
        }
    }

    private void loadEarlierCodes() {
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
            int added = codeManager.loadEarlierCodesFromAPI(this);
            codeManager.commit();
            return added;
        }, this::handleEarlierCodesResult);
    }

    private void handleEarlierCodesResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, CODE));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                updateStatistics();
                showInfoMessage(getString(R.string.no_active_codes_found));
            } else {
                showErrorMessage(getString(R.string.failed_to_update_apps_db));
            }
        } finally {
            isLoadingEarlier = false;
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
                Intent intent = new Intent(this, CreateCodeActivity.class);
                createCodeLauncher.launch(intent);
            });
        }

        if (hideButton != null) {
            hideButton.setOnClickListener(v -> {
                hideKeyboard();
                handleHideCodes();
            });
        }

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

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                clearSearchControls();
                onExitSearchMode();
            });
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void searchMyCodes() {
        isMyCodesMode = true;
        isSearchMode = true;

        if (searchEditText != null) {
            searchEditText.setText("");
            searchEditText.setHint(R.string.my_codes);
        }

        if (searchFieldSpinner != null) {
            searchFieldSpinner.setSelection(0);
        }
        if (sortFieldSpinner != null) {
            sortFieldSpinner.setSelection(0);
        }
        if (sortOrderSpinner != null) {
            sortOrderSpinner.setSelection(1);
        }

        currentSearchQuery = "";
        currentSearchField = null;
        currentSortField = null;
        currentSortOrder = "desc";

        performSearchFromAPI(null);
    }

    private void showMoreMenu(View anchorView) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_code_more, null);
        PopupWindow popupWindow = new PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        );

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.stop_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleStopCodes();
        });

        popupView.findViewById(R.id.close_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleCloseCodes();
        });

        popupView.findViewById(R.id.stopped_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowStoppedCodes();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowHiddenCodes();
        });

        popupView.findViewById(R.id.refresh_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        popupView.findViewById(R.id.my_codes_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            searchMyCodes();
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleStopCodes() {
        if (codeCardContainer == null) return;
        List<Code> selectedCodes = codeCardContainer.getSelectedCodes();
        if (selectedCodes.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Code> activeCodes = new ArrayList<>();
        for (Code code : selectedCodes) {
            if (Boolean.TRUE.equals(code.isActive())) {
                activeCodes.add(code);
            }
        }

        if (activeCodes.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_codes_to_stop);
            return;
        }

        String[] codeIds = new String[activeCodes.size()];
        for (int i = 0; i < activeCodes.size(); i++) {
            codeIds[i] = activeCodes.get(i).getId();
        }
        Intent intent = new Intent(this, StopCodeActivity.class);
        intent.putExtra(StopCodeActivity.EXTRA_CODE_IDS, codeIds);
        stopCodeLauncher.launch(intent);
    }

    private void handleCloseCodes() {
        if (codeCardContainer == null) return;
        List<Code> selectedCodes = codeCardContainer.getSelectedCodes();
        if (selectedCodes.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Code> closableCodes = new ArrayList<>();
        for (Code code : selectedCodes) {
            if (!Boolean.TRUE.equals(code.isClosed())) {
                closableCodes.add(code);
            }
        }

        if (closableCodes.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_closable_codes_found);
            return;
        }

        String[] codeIds = new String[closableCodes.size()];
        for (int i = 0; i < closableCodes.size(); i++) {
            codeIds[i] = closableCodes.get(i).getId();
        }
        Intent intent = new Intent(this, CloseCodeActivity.class);
        intent.putExtra(CloseCodeActivity.EXTRA_CODE_IDS, codeIds);
        closeCodeLauncher.launch(intent);
    }

    private void handleShowStoppedCodes() {
        Intent intent = new Intent(this, StoppedCodeActivity.class);
        stoppedCodeLauncher.launch(intent);
    }

    private void handleShowHiddenCodes() {
        Intent intent = new Intent(this, HiddenCodeActivity.class);
        hiddenCodeLauncher.launch(intent);
    }

    private void handleHideCodes() {
        if (codeCardContainer == null) return;
        List<Code> chosenObjects = codeCardContainer.getSelectedCodes();
        if (chosenObjects.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        codeManager.addToLocalDeletedList(chosenObjects);
        codeManager.removeCodes(chosenObjects);
        codeManager.commit();

        refreshList();
        showSuccessMessage("Hidden " + chosenObjects.size() + " code(s)");
    }

    private void removeCodesFromContainerAndDB(String[] codeIds) {
        if (codeIds == null || codeIds.length == 0 || codeManager == null) {
            return;
        }

        List<Code> codesToRemove = new ArrayList<>();

        for (String codeId : codeIds) {
            if (codeId == null) continue;
            
            if (codeCardContainer != null) {
                codeCardContainer.removeCodeById(codeId);
            }
            
            for (Code code : codeList) {
                if (codeId.equals(code.getId())) {
                    codesToRemove.add(code);
                    break;
                }
            }
        }

        codeList.removeAll(codesToRemove);

        if (!codesToRemove.isEmpty()) {
            codeManager.removeCodes(codesToRemove);
            codeManager.commit();
            TimberLogger.i(TAG, "Removed %d stopped/closed code(s) from container and database", codesToRemove.size());
        }

        updateUI();
    }

    private void handleEditIconClick(Code code) {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            showErrorMessage("No active FID");
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (!liveFid.equals(code.getOwner())) {
            showErrorMessage(getString(R.string.only_owner_can_update));
            return;
        }

        Intent intent = new Intent(this, UpdateCodeActivity.class);
        intent.putExtra(UpdateCodeActivity.EXTRA_CODE_JSON, com.fc.fc_ajdk.utils.JsonUtils.toJson(code));
        updateCodeLauncher.launch(intent);
    }

    private void handleRateIconClick(Code code) {
        Intent intent = new Intent(this, RateActivity.class);
        intent.putExtra(RateActivity.EXTRA_CODE_JSON, com.fc.fc_ajdk.utils.JsonUtils.toJson(code));
        rateCodeLauncher.launch(intent);
    }

    private void handleOffChainIconClick(Code code) {
        // For off-chain codes, launch UpdateCodeActivity which handles publishing
        Intent intent = new Intent(this, UpdateCodeActivity.class);
        intent.putExtra(UpdateCodeActivity.EXTRA_CODE_JSON, com.fc.fc_ajdk.utils.JsonUtils.toJson(code));
        updateCodeLauncher.launch(intent);
    }

    private void refreshList() {
        loadFirstPage();
    }

    private void clearAndReload() {
        if (codeCardContainer != null) {
            codeCardContainer.clearAll();
        }
        codeList.clear();
        resetPaginationState();
        loadInitialData();
    }

    private void updateStatistics() {
        if (codeStatisticsTextView != null) {
            int count = codeList.size();
            String stats = String.format("Showing %d code(s)", count);
            if (hasMoreEarlierData) {
                stats += " (More available)";
            }
            codeStatisticsTextView.setText(stats);
            
            LinearLayout statsContainer = findViewById(R.id.code_statistics_container);
            if (statsContainer != null) {
                statsContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    private void sortCodeList() {
        if (codeCardContainer == null) return;

        boolean ascending = currentSortState == SortState.ASCENDING;
        boolean enableSort = currentSortState != SortState.NONE;

        switch (currentSortType) {
            case NAME:
                codeCardContainer.sortByName(ascending, enableSort);
                break;
            case OWNER:
                codeCardContainer.sortByOwner(ascending, enableSort);
                break;
            case TIME:
                codeCardContainer.sortByLastTime(ascending, enableSort);
                break;
        }
    }

    private void cycleSortState() {
        switch (currentSortState) {
            case NONE:
                currentSortState = SortState.DESCENDING;
                break;
            case DESCENDING:
                currentSortState = SortState.ASCENDING;
                break;
            case ASCENDING:
                currentSortState = SortState.NONE;
                break;
        }
    }

    private void resetOtherSortIcons() {
        setDefaultSortColors();
    }

    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);
        if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        if (nameSortIcon != null) nameSortIcon.setColorFilter(hintColor);
        if (ownerSortLabel != null) ownerSortLabel.setTextColor(hintColor);
        if (ownerSortIcon != null) ownerSortIcon.setColorFilter(hintColor);
        if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        if (timeSortIcon != null) timeSortIcon.setColorFilter(hintColor);
    }

    private void updateSortIcons() {
        int activeColor = getResources().getColor(R.color.accent, null);
        int hintColor = getResources().getColor(R.color.hint, null);

        setDefaultSortColors();

        TextView activeLabel = null;
        ImageView activeIcon = null;

        switch (currentSortType) {
            case NAME:
                activeLabel = nameSortLabel;
                activeIcon = nameSortIcon;
                break;
            case OWNER:
                activeLabel = ownerSortLabel;
                activeIcon = ownerSortIcon;
                break;
            case TIME:
                activeLabel = timeSortLabel;
                activeIcon = timeSortIcon;
                break;
        }

        if (activeLabel != null && activeIcon != null) {
            if (currentSortState != SortState.NONE) {
                activeLabel.setTextColor(activeColor);
                activeIcon.setColorFilter(activeColor);
            }

            switch (currentSortState) {
                case ASCENDING:
                    activeIcon.setImageResource(R.drawable.ic_sort_asc);
                    break;
                case DESCENDING:
                    activeIcon.setImageResource(R.drawable.ic_sort_desc);
                    break;
                case NONE:
                    activeIcon.setImageResource(R.drawable.ic_sort_none);
                    break;
            }
        }
    }

    private void showWaitingDialog(String message) {
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, message);
        } else {
            waitingDialog.setHint(message);
        }
            waitingDialog.show();
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
    }
}
