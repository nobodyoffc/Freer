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

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ServiceManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ServiceCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.fc.fc_ajdk.constants.IndicesNames.SERVICE;

public class ServiceActivity extends BaseCryptoActivity {
    private static final String TAG = "Services";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    private ServiceCardContainer serviceCardContainer;
    protected ServiceManager serviceManager;
    private WaitingDialog waitingDialog;

    private ImageButton moreButton;
    private ImageButton createButton;
    private ImageButton hideButton;
    private ImageButton clearButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView serviceScrollView;
    private TextView serviceStatisticsTextView;

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
    private boolean isMyServicesMode = false;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Service> serviceList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    private ActivityResultLauncher<Intent> updateServiceLauncher;
    private ActivityResultLauncher<Intent> createServiceLauncher;
    private ActivityResultLauncher<Intent> stopServiceLauncher;
    private ActivityResultLauncher<Intent> closeServiceLauncher;
    private ActivityResultLauncher<Intent> recoverServiceLauncher;
    private ActivityResultLauncher<Intent> rateServiceLauncher;
    private ActivityResultLauncher<Intent> hiddenServiceLauncher;
    private ActivityResultLauncher<Intent> stoppedServiceLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeActivityResultLaunchers();

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                serviceManager = ServiceManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ServiceManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ServiceManager with liveFid: " + e.getMessage());
        }

        if (serviceManager == null) {
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
        updateServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        createServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        stopServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String[] stoppedIds = data.getStringArrayExtra("stopped_service_ids");
                        if (stoppedIds != null && stoppedIds.length > 0) {
                            removeServicesFromContainerAndDB(stoppedIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        closeServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String[] closedIds = data.getStringArrayExtra("closed_service_ids");
                        if (closedIds != null && closedIds.length > 0) {
                            removeServicesFromContainerAndDB(closedIds);
                        }
                    }
                    refreshList();
                }
            }
        );

        recoverServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        rateServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        hiddenServiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        stoppedServiceLauncher = registerForActivityResult(
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
        return R.layout.activity_service;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.services);
    }

    @Override
    protected void initializeViews() {
        moreButton = findViewById(R.id.more_button);
        createButton = findViewById(R.id.create_button);
        hideButton = findViewById(R.id.hide_button);
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.service_more_button);

        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        serviceScrollView = findViewById(R.id.service_scroll_view);

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

        serviceStatisticsTextView = findViewById(R.id.service_statistics);

        setupSelectAllCheckBox();
        setupNameSortButton();
        setupOwnerSortButton();
        setupTimeSortButton();
        setupSwipeRefresh();
        setupServiceSpinners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }

    private void loadInitialData() {
        executeInBackground(getString(R.string.loading), () -> {
            serviceManager.refreshServicesFromAPI(this);
            return serviceManager.getPaginatedServices(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Service> firstPageList = serviceManager.getPaginatedServices(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<Service> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_active_services_found));
            updateUI();
            return;
        }

        serviceList.clear();
        serviceList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadServiceCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<Service> newList) {
        if (!serviceList.isEmpty()) {
            latestId = serviceList.get(0).getId();
            earliestId = serviceList.get(serviceList.size() - 1).getId();
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

    private void loadServiceCardList() {
        LinearLayout serviceListContainer = findViewById(R.id.fragment_container);

        if (serviceCardContainer != null) {
            serviceCardContainer.clearAll();
        }

        serviceCardContainer = new ServiceCardContainer(this, serviceListContainer, ChooseMode.CHOOSE_MULTI);
        
        serviceCardContainer.setHideEditButton(false);
        serviceCardContainer.setShowClearButton(false);

        serviceCardContainer.setOnServiceListChangedListener(updatedList -> {
            updateUI();
        });

        serviceCardContainer.setUpdateServiceLauncher(updateServiceLauncher);

        serviceCardContainer.setOnEditIconClickListener(this::handleEditIconClick);
        serviceCardContainer.setOnRateIconClickListener(this::handleRateIconClick);
        serviceCardContainer.setOnOffChainIconClickListener(this::handleOffChainIconClick);

        Map<String, String> cidMap = serviceCardContainer.getCidMap(serviceList, this);

        for (Service service : serviceList) {
            serviceCardContainer.addServiceCard(service, cidMap);
        }

        updateUI();
        setupScrollListener();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && serviceCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    serviceCardContainer.selectAll(isChecked);
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
                sortServiceList();
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
                sortServiceList();
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
                sortServiceList();
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && serviceCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (serviceCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (serviceCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    @Override
    protected void onExitSearchMode() {
        if (serviceCardContainer != null) {
            serviceCardContainer.clearAll();
        }
        isMyServicesMode = false;
        loadFirstPage();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        performSearchFromAPI(null);
    }

    private void performSearchFromAPI(Service referenceService) {
        if (serviceManager == null) {
            return;
        }

        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<Service> searchResults;
                if (isMyServicesMode) {
                    searchResults = serviceManager.searchMyServicesFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceService
                    );
                } else {
                    searchResults = serviceManager.searchServicesFromApi(
                        this,
                        currentSearchQuery,
                        currentSearchField,
                        currentSortField,
                        currentSortOrder,
                        referenceService
                    );
                }

                runOnUiThread(() -> {
                    if (searchResults != null && !searchResults.isEmpty()) {
                        if (referenceService == null) {
                            serviceList.clear();
                            serviceList.addAll(searchResults);
                            hasMoreEarlierData = searchResults.size() >= pageSize;
                            hasMoreNewerData = false;
                        }

                        updatePaginationState(searchResults);

                        if (serviceCardContainer != null) {
                            serviceCardContainer.clearAll();
                        }
                        loadServiceCardList();

                        dismissWaitingDialog();
                        showSuccessMessage(getString(R.string.found_apps_count, searchResults.size()));
                    } else {
                        dismissWaitingDialog();
                        showInfoMessage(getString(R.string.no_active_services_found));
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

    private void setupServiceSpinners() {
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        if (searchFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> searchableFields = Service.getSearchableFields();

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
            LinkedHashMap<String, Map<String, String>> sortableFields = Service.getSortableFields();

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
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer services");
                loadNewerServices();
            });

            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    private void setupScrollListener() {
        if (serviceScrollView != null) {
            serviceScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(serviceScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerServiceToContainer();
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

    private void loadNewerServices() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        loadMoreNewerFromDatabase();
    }

    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer services from local database");

        if (serviceList.isEmpty() || serviceList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = serviceList.get(0).getId();
            List<Service> newerItems = serviceManager.getPaginatedServices(pageSize, newestId, false);

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

    private void handleNewerItemsFromDatabase(List<Service> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        removeDuplicateServices(newerItems);

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        removeEarlier(itemsToRemoveFromEnd);

        java.util.Collections.reverse(newerItems);
        serviceList.addAll(0, newerItems);
        serviceCardContainer.addServiceCardsToBeginning(newerItems);

        latestId = serviceList.get(0).getId();
        hasMoreNewerData = newerItems.size() >= pageSize;

        updateUI();
        showSuccessMessage(getString(R.string.loaded_newer_services, newerItems.size()));
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = serviceList.size();
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

    private void removeDuplicateServices(List<Service> newServices) {
        if (newServices == null || newServices.isEmpty() || serviceCardContainer == null) {
            return;
        }

        Set<String> newIds = new HashSet<>();
        for (Service newService : newServices) {
            if (newService.getId() != null) {
                newIds.add(newService.getId());
            }
        }

        if (newIds.isEmpty()) {
            return;
        }

        int removedCount = 0;
        for (int i = serviceList.size() - 1; i >= 0; i--) {
            Service existing = serviceList.get(i);
            if (existing.getId() != null && newIds.contains(existing.getId())) {
                serviceCardContainer.removeServiceById(existing.getId());
                serviceList.remove(i);
                removedCount++;
            }
        }

        if (removedCount > 0) {
            TimberLogger.i(TAG, "Removed %d duplicate service(s) with matching IDs", removedCount);
        }
    }

    private void removeEarlier(int itemsToRemoveFromEnd) {
        if (itemsToRemoveFromEnd > 0) {
            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
            serviceCardContainer.removeFromEnd(itemsToRemoveFromEnd);

            if (serviceCardContainer.getServiceList() != null) {
                serviceList.clear();
                serviceList.addAll(serviceCardContainer.getServiceList());
            }

            if (!serviceList.isEmpty()) {
                earliestId = serviceList.get(serviceList.size() - 1).getId();
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
            int added = serviceManager.loadNewerServicesFromAPI(this);
            serviceManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_apps_db));
            } else if (added > 0) {
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, SERVICE));
            } else {
                showInfoMessage(getString(R.string.no_active_services_found));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reloading from database after API sync: %s", e.getMessage());
            refreshList();
        } finally {
            isLoadingNewer = false;
            stopSwipeRefresh();
        }
    }

    private void loadNewerServiceToContainer() {
        // Implementation for scroll-triggered loading
    }

    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more services from local database");

        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            List<Service> moreItems = serviceManager.getPaginatedServices(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                int currentSize = serviceList.size();
                int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (itemsToRemoveFromBeginning > 0) {
                    serviceCardContainer.removeFromBeginning(itemsToRemoveFromBeginning);

                    if (serviceCardContainer.getServiceList() != null) {
                        serviceList.clear();
                        serviceList.addAll(serviceCardContainer.getServiceList());
                    }

                    if (!serviceList.isEmpty()) {
                        latestId = serviceList.get(0).getId();
                        hasMoreNewerData = true;
                    }
                }

                serviceList.addAll(moreItems);
                Map<String, String> cidMap = serviceCardContainer.getCidMap(moreItems, this);
                for (Service service : moreItems) {
                    serviceCardContainer.addServiceCard(service, cidMap);
                }

                earliestId = serviceList.get(serviceList.size() - 1).getId();
                hasMoreEarlierData = moreItems.size() >= pageSize;

                updateStatistics();
                showSuccessMessage(getString(R.string.loaded_earlier_services, moreItems.size()));
            } else {
                hasMoreEarlierData = false;
                updateStatistics();
                loadEarlierServices();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more from database: %s", e.getMessage());
        }
    }

    private void loadEarlierServices() {
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
            int added = serviceManager.loadEarlierServicesFromAPI(this);
            serviceManager.commit();
            return added;
        }, this::handleEarlierServicesResult);
    }

    private void handleEarlierServicesResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, SERVICE));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                updateStatistics();
                showInfoMessage(getString(R.string.no_active_services_found));
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
                Intent intent = new Intent(this, CreateServiceActivity.class);
                createServiceLauncher.launch(intent);
            });
        }

        if (hideButton != null) {
            hideButton.setOnClickListener(v -> {
                hideKeyboard();
                handleHideServices();
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

    private void searchMyServices() {
        isMyServicesMode = true;
        isSearchMode = true;

        if (searchEditText != null) {
            searchEditText.setText("");
            searchEditText.setHint(R.string.my_services);
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
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_service_more, null);
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
            handleStopServices();
        });

        popupView.findViewById(R.id.close_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleCloseServices();
        });

        popupView.findViewById(R.id.stopped_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowStoppedServices();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleShowHiddenServices();
        });

        popupView.findViewById(R.id.refresh_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        popupView.findViewById(R.id.my_services_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            searchMyServices();
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleStopServices() {
        if (serviceCardContainer == null) return;
        List<Service> selectedServices = serviceCardContainer.getSelectedServices();
        if (selectedServices.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Service> activeServices = new ArrayList<>();
        for (Service service : selectedServices) {
            if (Boolean.TRUE.equals(service.getActive())) {
                activeServices.add(service);
            }
        }

        if (activeServices.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_services_to_stop);
            return;
        }

        String[] serviceIds = new String[activeServices.size()];
        for (int i = 0; i < activeServices.size(); i++) {
            serviceIds[i] = activeServices.get(i).getId();
        }
        Intent intent = new Intent(this, StopServiceActivity.class);
        intent.putExtra(StopServiceActivity.EXTRA_SERVICE_IDS, serviceIds);
        stopServiceLauncher.launch(intent);
    }

    private void handleCloseServices() {
        if (serviceCardContainer == null) return;
        List<Service> selectedServices = serviceCardContainer.getSelectedServices();
        if (selectedServices.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Service> closableServices = new ArrayList<>();
        for (Service service : selectedServices) {
            if (!Boolean.TRUE.equals(service.isClosed())) {
                closableServices.add(service);
            }
        }

        if (closableServices.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_closable_services_found);
            return;
        }

        String[] serviceIds = new String[closableServices.size()];
        for (int i = 0; i < closableServices.size(); i++) {
            serviceIds[i] = closableServices.get(i).getId();
        }
        Intent intent = new Intent(this, CloseServiceActivity.class);
        intent.putExtra(CloseServiceActivity.EXTRA_SERVICE_IDS, serviceIds);
        closeServiceLauncher.launch(intent);
    }

    private void handleShowStoppedServices() {
        Intent intent = new Intent(this, StoppedServiceActivity.class);
        stoppedServiceLauncher.launch(intent);
    }

    private void handleShowHiddenServices() {
        Intent intent = new Intent(this, HiddenServiceActivity.class);
        hiddenServiceLauncher.launch(intent);
    }

    private void handleHideServices() {
        if (serviceCardContainer == null) return;
        List<Service> chosenObjects = serviceCardContainer.getSelectedServices();
        if (chosenObjects.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        serviceManager.addToLocalDeletedList(chosenObjects);
        serviceManager.removeServices(chosenObjects);
        serviceManager.commit();

        refreshList();
        showSuccessMessage("Hidden " + chosenObjects.size() + " service(s)");
    }

    private void removeServicesFromContainerAndDB(String[] serviceIds) {
        if (serviceIds == null || serviceIds.length == 0 || serviceManager == null) {
            return;
        }

        List<Service> servicesToRemove = new ArrayList<>();

        for (String serviceId : serviceIds) {
            if (serviceId == null) continue;
            
            if (serviceCardContainer != null) {
                serviceCardContainer.removeServiceById(serviceId);
            }
            
            for (Service service : serviceList) {
                if (serviceId.equals(service.getId())) {
                    servicesToRemove.add(service);
                    break;
                }
            }
        }

        serviceList.removeAll(servicesToRemove);

        if (!servicesToRemove.isEmpty()) {
            serviceManager.removeServices(servicesToRemove);
            serviceManager.commit();
            TimberLogger.i(TAG, "Removed %d stopped/closed service(s) from container and database", servicesToRemove.size());
        }

        updateUI();
    }

    private void handleEditIconClick(Service service) {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            showErrorMessage("No active FID");
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (!liveFid.equals(service.getOwner())) {
            showErrorMessage(getString(R.string.only_owner_can_update));
            return;
        }

        Intent intent = new Intent(this, UpdateServiceActivity.class);
        intent.putExtra(UpdateServiceActivity.EXTRA_SERVICE_JSON, com.fc.fc_ajdk.utils.JsonUtils.toJson(service));
        updateServiceLauncher.launch(intent);
    }

    private void handleRateIconClick(Service service) {
        Intent intent = new Intent(this, RateActivity.class);
        intent.putExtra(RateActivity.EXTRA_SERVICE_JSON, com.fc.fc_ajdk.utils.JsonUtils.toJson(service));
        rateServiceLauncher.launch(intent);
    }

    private void handleOffChainIconClick(Service service) {
        // For off-chain services, launch UpdateServiceActivity which handles publishing
        Intent intent = new Intent(this, UpdateServiceActivity.class);
        intent.putExtra(UpdateServiceActivity.EXTRA_SERVICE_JSON, com.fc.fc_ajdk.utils.JsonUtils.toJson(service));
        updateServiceLauncher.launch(intent);
    }

    private void refreshList() {
        loadFirstPage();
    }

    private void clearAndReload() {
        if (serviceCardContainer != null) {
            serviceCardContainer.clearAll();
        }
        serviceList.clear();
        resetPaginationState();
        loadInitialData();
    }

    private void updateStatistics() {
        if (serviceStatisticsTextView != null) {
            int count = serviceList.size();
            String stats = String.format("Showing %d service(s)", count);
            if (hasMoreEarlierData) {
                stats += " (More available)";
            }
            serviceStatisticsTextView.setText(stats);

            LinearLayout statsContainer = findViewById(R.id.service_statistics_container);
            if (statsContainer != null) {
                statsContainer.setVisibility(View.VISIBLE);
            }
        }

        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreEarlierData ? View.VISIBLE : View.GONE);
        }
    }

    private void sortServiceList() {
        if (serviceCardContainer == null) return;

        boolean ascending = currentSortState == SortState.ASCENDING;
        boolean enableSort = currentSortState != SortState.NONE;

        switch (currentSortType) {
            case NAME:
                serviceCardContainer.sortByStdName(ascending, enableSort);
                break;
            case OWNER:
                serviceCardContainer.sortByOwner(ascending, enableSort);
                break;
            case TIME:
                serviceCardContainer.sortByLastTime(ascending, enableSort);
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
