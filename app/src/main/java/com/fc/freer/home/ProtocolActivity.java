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

import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ProtocolManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ProtocolCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.fc.fc_ajdk.constants.IndicesNames.PROTOCOL;

public class ProtocolActivity extends BaseCryptoActivity {
    private static final String TAG = "Protocols";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    private ProtocolCardContainer protocolCardContainer;
    protected ProtocolManager protocolManager;
    private WaitingDialog waitingDialog;

    private ImageButton moreButton;
    private ImageButton createButton;
    private ImageButton hideButton;
    private ImageButton clearButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView protocolScrollView;
    private TextView protocolStatisticsTextView;

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
    private boolean isMyProtocolsMode = false;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Protocol> protocolList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    private ActivityResultLauncher<Intent> updateProtocolLauncher;
    private ActivityResultLauncher<Intent> createProtocolLauncher;
    private ActivityResultLauncher<Intent> stopProtocolLauncher;
    private ActivityResultLauncher<Intent> closeProtocolLauncher;
    private ActivityResultLauncher<Intent> recoverProtocolLauncher;
    private ActivityResultLauncher<Intent> rateProtocolLauncher;
    private ActivityResultLauncher<Intent> hiddenProtocolLauncher;
    private ActivityResultLauncher<Intent> stoppedProtocolLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeActivityResultLaunchers();

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                protocolManager = ProtocolManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ProtocolManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ProtocolManager with liveFid: " + e.getMessage());
        }

        if (protocolManager == null) {
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
        updateProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        createProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        stopProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String[] stoppedIds = data.getStringArrayExtra("stopped_protocol_ids");
                        if (stoppedIds != null && stoppedIds.length > 0) {
                            removeProtocolsFromContainerAndDB(stoppedIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        closeProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String[] closedIds = data.getStringArrayExtra("closed_protocol_ids");
                        if (closedIds != null && closedIds.length > 0) {
                            removeProtocolsFromContainerAndDB(closedIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        recoverProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        rateProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        hiddenProtocolLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        stoppedProtocolLauncher = registerForActivityResult(
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
        return R.layout.activity_protocol;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.protocols);
    }

    @Override
    protected void initializeViews() {
        moreButton = findViewById(R.id.more_button);
        createButton = findViewById(R.id.create_button);
        hideButton = findViewById(R.id.hide_button);
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.protocol_more_button);

        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        protocolScrollView = findViewById(R.id.protocol_scroll_view);

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

        protocolStatisticsTextView = findViewById(R.id.protocol_statistics);

        setupSelectAllCheckBox();
        setupNameSortButton();
        setupOwnerSortButton();
        setupTimeSortButton();
        setupSwipeRefresh();
        setupProtocolSpinners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }

    private void loadInitialData() {
        executeInBackground(getString(R.string.loading), () -> {
            protocolManager.refreshProtocolsFromAPI(this);
            return protocolManager.getPaginatedProtocols(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Protocol> firstPageList = protocolManager.getPaginatedProtocols(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<Protocol> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_active_protocols_found));
            updateUI();
            return;
        }

        protocolList.clear();
        protocolList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadProtocolCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<Protocol> newList) {
        if (!protocolList.isEmpty()) {
            latestId = protocolList.get(0).getId();
            earliestId = protocolList.get(protocolList.size() - 1).getId();
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

    private void loadProtocolCardList() {
        LinearLayout protocolListContainer = findViewById(R.id.fragment_container);

        if (protocolCardContainer != null) {
            protocolCardContainer.clearAll();
        }

        protocolCardContainer = new ProtocolCardContainer(this, protocolListContainer, ChooseMode.CHOOSE_MULTI);
        
        protocolCardContainer.setHideEditButton(false);
        protocolCardContainer.setShowClearButton(false);

        protocolCardContainer.setOnProtocolListChangedListener(updatedList -> {
            updateUI();
        });

        protocolCardContainer.setUpdateProtocolLauncher(updateProtocolLauncher);

        protocolCardContainer.setOnEditIconClickListener(this::handleEditIconClick);
        protocolCardContainer.setOnRateIconClickListener(this::handleRateIconClick);
        protocolCardContainer.setOnOffChainIconClickListener(this::handleOffChainIconClick);

        Map<String, String> cidMap = protocolCardContainer.getCidMap(protocolList, this);

        for (Protocol protocol : protocolList) {
            protocolCardContainer.addProtocolCard(protocol, cidMap);
        }

        updateUI();
        setupScrollListener();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && protocolCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    protocolCardContainer.selectAll(isChecked);
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
                sortProtocolList();
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
                sortProtocolList();
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
                sortProtocolList();
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && protocolCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (protocolCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (protocolCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    @Override
    protected void onExitSearchMode() {
        if (protocolCardContainer != null) {
            protocolCardContainer.clearAll();
        }
        isMyProtocolsMode = false;
        loadFirstPage();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        performSearchFromAPI(null);
    }

    private void performSearchFromAPI(Protocol referenceProtocol) {
        if (protocolManager == null) {
            return;
        }

        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<Protocol> searchResults;
                if (isMyProtocolsMode) {
                    searchResults = protocolManager.searchMyProtocolsFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceProtocol
                    );
                } else {
                    searchResults = protocolManager.searchProtocolsFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceProtocol
                    );
                }

                runOnUiThread(() -> {
                    if (searchResults != null && !searchResults.isEmpty()) {
                        if (referenceProtocol == null) {
                            protocolList.clear();
                            protocolList.addAll(searchResults);
                            hasMoreEarlierData = searchResults.size() >= pageSize;
                            hasMoreNewerData = false;
                        }

                        updatePaginationState(searchResults);

                        if (protocolCardContainer != null) {
                            protocolCardContainer.clearAll();
                        }
                        loadProtocolCardList();

                        dismissWaitingDialog();
                        showSuccessMessage(getString(R.string.found_apps_count, searchResults.size()));
                    } else {
                        dismissWaitingDialog();
                        showInfoMessage(getString(R.string.no_active_protocols_found));
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

    private void setupProtocolSpinners() {
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        if (searchFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> searchableFields = Protocol.getSearchableFields();

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
            LinkedHashMap<String, Map<String, String>> sortableFields = Protocol.getSortableFields();

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
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer protocols");
                loadNewerProtocols();
            });

            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    private void setupScrollListener() {
        if (protocolScrollView != null) {
            protocolScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(protocolScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerProtocolToContainer();
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

    private void loadNewerProtocols() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        loadMoreNewerFromDatabase();
    }

    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer protocols from local database");

        if (protocolList.isEmpty() || protocolList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = protocolList.get(0).getId();
            List<Protocol> newerItems = protocolManager.getPaginatedProtocols(pageSize, newestId, false);

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

    private void handleNewerItemsFromDatabase(List<Protocol> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        removeDuplicateProtocols(newerItems);

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        removeEarlier(itemsToRemoveFromEnd);

        java.util.Collections.reverse(newerItems);
        protocolList.addAll(0, newerItems);
        protocolCardContainer.addProtocolCardsToBeginning(newerItems);

        latestId = protocolList.get(0).getId();
        hasMoreNewerData = newerItems.size() >= pageSize;

        updateUI();
        showSuccessMessage(getString(R.string.loaded_newer_protocols, newerItems.size()));
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = protocolList.size();
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

    private void removeDuplicateProtocols(List<Protocol> newProtocols) {
        if (newProtocols == null || newProtocols.isEmpty() || protocolCardContainer == null) {
            return;
        }

        Set<String> newIds = new HashSet<>();
        for (Protocol newProtocol : newProtocols) {
            if (newProtocol.getId() != null) {
                newIds.add(newProtocol.getId());
            }
        }

        if (newIds.isEmpty()) {
            return;
        }

        int removedCount = 0;
        for (int i = protocolList.size() - 1; i >= 0; i--) {
            Protocol existing = protocolList.get(i);
            if (existing.getId() != null && newIds.contains(existing.getId())) {
                protocolCardContainer.removeProtocolById(existing.getId());
                protocolList.remove(i);
                removedCount++;
            }
        }

        if (removedCount > 0) {
            TimberLogger.i(TAG, "Removed %d duplicate protocol(s) with matching IDs", removedCount);
        }
    }

    private void removeEarlier(int itemsToRemoveFromEnd) {
        if (itemsToRemoveFromEnd > 0) {
            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
            protocolCardContainer.removeFromEnd(itemsToRemoveFromEnd);

            if (protocolCardContainer.getProtocolList() != null) {
                protocolList.clear();
                protocolList.addAll(protocolCardContainer.getProtocolList());
            }

            if (!protocolList.isEmpty()) {
                earliestId = protocolList.get(protocolList.size() - 1).getId();
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
            int added = protocolManager.loadNewerProtocolsFromAPI(this);
            protocolManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_apps_db));
            } else if (added > 0) {
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, PROTOCOL));
            } else {
                showInfoMessage(getString(R.string.no_active_protocols_found));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reloading from database after API sync: %s", e.getMessage());
            refreshList();
        } finally {
            isLoadingNewer = false;
            stopSwipeRefresh();
        }
    }

    private void loadNewerProtocolToContainer() {
        // Implementation for scroll-triggered loading
    }

    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more protocols from local database");

        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            List<Protocol> moreItems = protocolManager.getPaginatedProtocols(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                int currentSize = protocolList.size();
                int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (itemsToRemoveFromBeginning > 0) {
                    protocolCardContainer.removeFromBeginning(itemsToRemoveFromBeginning);

                    if (protocolCardContainer.getProtocolList() != null) {
                        protocolList.clear();
                        protocolList.addAll(protocolCardContainer.getProtocolList());
                    }

                    if (!protocolList.isEmpty()) {
                        latestId = protocolList.get(0).getId();
                        hasMoreNewerData = true;
                    }
                }

                protocolList.addAll(moreItems);
                Map<String, String> cidMap = protocolCardContainer.getCidMap(moreItems, this);
                for (Protocol protocol : moreItems) {
                    protocolCardContainer.addProtocolCard(protocol, cidMap);
                }

                earliestId = protocolList.get(protocolList.size() - 1).getId();
                hasMoreEarlierData = moreItems.size() >= pageSize;

                updateStatistics();
                showSuccessMessage(getString(R.string.loaded_earlier_protocols, moreItems.size()));
            } else {
                hasMoreEarlierData = false;
                updateStatistics();
                loadEarlierProtocols();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more from database: %s", e.getMessage());
        }
    }

    private void loadEarlierProtocols() {
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
            int added = protocolManager.loadEarlierProtocolsFromAPI(this);
            protocolManager.commit();
            return added;
        }, this::handleEarlierProtocolsResult);
    }

    private void handleEarlierProtocolsResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, PROTOCOL));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                updateStatistics();
                showInfoMessage(getString(R.string.no_active_protocols_found));
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
                Intent intent = new Intent(this, CreateProtocolActivity.class);
                createProtocolLauncher.launch(intent);
            });
        }

        if (hideButton != null) {
            hideButton.setOnClickListener(v -> {
                hideKeyboard();
                handleHideProtocols();
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

    private void searchMyProtocols() {
        isMyProtocolsMode = true;
        isSearchMode = true;

        if (searchEditText != null) {
            searchEditText.setText("");
            searchEditText.setHint(R.string.my_protocols);
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
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_protocol_more, null);
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
            handleStopProtocols();
        });

        popupView.findViewById(R.id.close_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleCloseProtocols();
        });

        popupView.findViewById(R.id.stopped_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowStoppedProtocols();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowHiddenProtocols();
        });

        popupView.findViewById(R.id.refresh_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        popupView.findViewById(R.id.my_protocols_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            searchMyProtocols();
        });

        popupView.findViewById(R.id.about_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            com.fc.freer.utils.AboutDialog.show(this, R.string.about_protocol_title, R.string.about_protocol_message);
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleStopProtocols() {
        if (protocolCardContainer == null) return;
        List<Protocol> selectedProtocols = protocolCardContainer.getSelectedProtocols();
        if (selectedProtocols.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Protocol> activeProtocols = new ArrayList<>();
        for (Protocol protocol : selectedProtocols) {
            if (Boolean.TRUE.equals(protocol.isActive())) {
                activeProtocols.add(protocol);
            }
        }

        if (activeProtocols.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_protocols_to_stop);
            return;
        }

        // Launch StopProtocolActivity
        Intent intent = new Intent(this, StopProtocolActivity.class);
        intent.putExtra(StopProtocolActivity.EXTRA_PROTOCOL_IDS, getProtocolIds(activeProtocols));
        stopProtocolLauncher.launch(intent);
    }

    private void handleCloseProtocols() {
        if (protocolCardContainer == null) return;
        List<Protocol> selectedProtocols = protocolCardContainer.getSelectedProtocols();
        if (selectedProtocols.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Protocol> closableProtocols = new ArrayList<>();
        for (Protocol protocol : selectedProtocols) {
            if (!Boolean.TRUE.equals(protocol.isClosed())) {
                closableProtocols.add(protocol);
            }
        }

        if (closableProtocols.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_closable_protocols_found);
            return;
        }

        // Launch CloseProtocolActivity
        Intent intent = new Intent(this, CloseProtocolActivity.class);
        intent.putExtra(CloseProtocolActivity.EXTRA_PROTOCOL_IDS, getProtocolIds(closableProtocols));
        closeProtocolLauncher.launch(intent);
    }

    private void handleShowStoppedProtocols() {
        Intent intent = new Intent(this, StoppedProtocolActivity.class);
        stoppedProtocolLauncher.launch(intent);
    }

    private void handleShowHiddenProtocols() {
        Intent intent = new Intent(this, HiddenProtocolActivity.class);
        hiddenProtocolLauncher.launch(intent);
    }

    private void handleHideProtocols() {
        if (protocolCardContainer == null) return;
        List<Protocol> chosenObjects = protocolCardContainer.getSelectedProtocols();
        if (chosenObjects.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        protocolManager.addToLocalDeletedList(chosenObjects);
        protocolManager.removeProtocols(chosenObjects);
        protocolManager.commit();

        refreshList();
        showSuccessMessage("Hidden " + chosenObjects.size() + " protocol(s)");
    }

    private void removeProtocolsFromContainerAndDB(String[] protocolIds) {
        if (protocolIds == null || protocolIds.length == 0 || protocolManager == null) {
            return;
        }

        List<Protocol> protocolsToRemove = new ArrayList<>();

        for (String protocolId : protocolIds) {
            if (protocolId == null) continue;
            
            if (protocolCardContainer != null) {
                protocolCardContainer.removeProtocolById(protocolId);
            }
            
            for (Protocol protocol : protocolList) {
                if (protocolId.equals(protocol.getId())) {
                    protocolsToRemove.add(protocol);
                    break;
                }
            }
        }

        protocolList.removeAll(protocolsToRemove);

        if (!protocolsToRemove.isEmpty()) {
            protocolManager.removeProtocols(protocolsToRemove);
            protocolManager.commit();
            TimberLogger.i(TAG, "Removed %d stopped/closed protocol(s) from container and database", protocolsToRemove.size());
        }

        updateUI();
    }

    /**
     * Helper method to extract protocol IDs from a list of protocols
     */
    private String[] getProtocolIds(List<Protocol> protocols) {
        String[] ids = new String[protocols.size()];
        for (int i = 0; i < protocols.size(); i++) {
            ids[i] = protocols.get(i).getId();
        }
        return ids;
    }

    private void handleEditIconClick(Protocol protocol) {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            showErrorMessage("No active FID");
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (!liveFid.equals(protocol.getOwner())) {
            showErrorMessage(getString(R.string.only_owner_can_update));
            return;
        }

        // Launch UpdateProtocolActivity
        Intent intent = new Intent(this, UpdateProtocolActivity.class);
        intent.putExtra(UpdateProtocolActivity.EXTRA_PROTOCOL_JSON, protocol.toJson());
        updateProtocolLauncher.launch(intent);
    }

    private void handleRateIconClick(Protocol protocol) {
        // Launch RateActivity with Protocol
        Intent intent = new Intent(this, RateActivity.class);
        intent.putExtra(RateActivity.EXTRA_PROTOCOL_JSON, protocol.toJson());
        rateProtocolLauncher.launch(intent);
    }

    private void handleOffChainIconClick(Protocol protocol) {
        if (protocol == null) {
            ToastUtils.makeText(this, R.string.protocol_data_not_found);
            return;
        }

        // Check if protocol is off-chain (can be published)
        if (Boolean.TRUE.equals(protocol.getOnChain())) {
            ToastUtils.makeText(this, getString(R.string.protocol_already_on_chain));
            return;
        }

        if (protocol.getOnChain() == null) {
            ToastUtils.makeText(this, getString(R.string.protocol_pending_confirmation));
            return;
        }

        // Check if user is the owner
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            showErrorMessage("No active FID");
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (!liveFid.equals(protocol.getOwner())) {
            showErrorMessage(getString(R.string.only_owner_can_update));
            return;
        }

        // Launch UpdateProtocolActivity to publish/update the protocol
        Intent intent = new Intent(this, UpdateProtocolActivity.class);
        intent.putExtra(UpdateProtocolActivity.EXTRA_PROTOCOL_JSON, protocol.toJson());
        updateProtocolLauncher.launch(intent);
    }

    private void refreshList() {
        loadFirstPage();
    }

    private void clearAndReload() {
        if (protocolCardContainer != null) {
            protocolCardContainer.clearAll();
        }
        protocolList.clear();
        resetPaginationState();
        loadInitialData();
    }

    private void updateStatistics() {
        if (protocolStatisticsTextView != null) {
            int count = protocolList.size();
            String stats = String.format("Showing %d protocol(s)", count);
            if (hasMoreEarlierData) {
                stats += " (More available)";
            }
            protocolStatisticsTextView.setText(stats);
            
            LinearLayout statsContainer = findViewById(R.id.protocol_statistics_container);
            if (statsContainer != null) {
                statsContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    private void sortProtocolList() {
        if (protocolCardContainer == null) return;

        boolean ascending = currentSortState == SortState.ASCENDING;
        boolean enableSort = currentSortState != SortState.NONE;

        switch (currentSortType) {
            case NAME:
                protocolCardContainer.sortByName(ascending, enableSort);
                break;
            case OWNER:
                protocolCardContainer.sortByOwner(ascending, enableSort);
                break;
            case TIME:
                protocolCardContainer.sortByLastTime(ascending, enableSort);
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
