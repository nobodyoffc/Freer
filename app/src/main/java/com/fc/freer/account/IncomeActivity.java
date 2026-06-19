package com.fc.freer.account;

import static com.fc.fc_ajdk.constants.Values.NEWER;
import static com.fc.fc_ajdk.constants.Values.OLDER;
import static com.fc.fc_ajdk.constants.Values.REFRESH;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.FreerApplication;
import com.fc.freer.BaseCryptoActivity;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.IncomeCardContainer;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.network.NetworkUtils;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.manager.CashManager;
import com.fc.freer.model.Income;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IncomeActivity extends BaseCryptoActivity {
    private static final String TAG = "Income";
    private static final int DEFAULT_PAGE_SIZE = 50;

    private IncomeCardContainer incomeCardContainer;
    private LinearLayout incomeListContainer;
    protected CashManager cashManager;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_REQUEST_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView incomeScrollView;

    // Sort-related UI elements
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout fromSortButton;
    private ImageView fromSortIcon;
    private TextView fromSortLabel;

    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { FCH, FROM }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.FCH;

    // Summary card UI elements
    private TextView selectedIncomeCountTextView;
    private TextView selectedIncomeAmountTextView;

    // Statistics TextView
    private TextView incomeStatisticsTextView;

    private final List<Income> incomeList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean isLoadingOlder = false;
    private boolean hasMoreOlderData = true;
    private boolean hasMoreNewerData = false;
    private boolean scrollListenerSetup = false;

    // Track latest and earliest incomes for pagination
    private Income latestIncome = null;  // For fetching newer incomes
    private Income earliestIncome = null; // For fetching older incomes

    private WaitingDialog waitingDialog;

    /**
     * Helper method to collect issuer FIDs from income list and fetch their CIDs
     * @param incomes List of Income objects to process
     */
    private void fetchAndStoreCidsForIncomes(List<Income> incomes) {
        if (incomes == null || incomes.isEmpty()) {
            return;
        }
        
        // Collect unique issuer FIDs
        Set<String> issuerFidSet = new HashSet<>();
        for (Income income : incomes) {
            if (income.getFrom() != null && !income.getFrom().isEmpty()) {
                issuerFidSet.add(income.getFrom());
            }
        }
        
        if (issuerFidSet.isEmpty()) {
            return;
        }
        
        // Convert set to list for API call
        List<String> fidList = new ArrayList<>(issuerFidSet);
        
        // Perform API call on background thread
        new Thread(() -> {
            try {
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient != null) {
                    Map<String, String> fidCidMap = fapiClient.getFidCidMap(fidList);
                    
                    if (fidCidMap != null && !fidCidMap.isEmpty()) {
                        // Store the mappings in CidFidManager
                        CidFidManager cidFidManager = CidFidManager.getInstance(this);
                        cidFidManager.addAll(fidCidMap);
//                        loadListContainer();
                        TimberLogger.d(TAG, "Stored %d FID-CID mappings for income issuers", fidCidMap.size());
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching CIDs for income issuers: %s", e.getMessage());
            }
        }).start();
    }

    /**
     * Updates the income cards to display CIDs instead of FIDs when CID mappings are available
     */
    private void updateIncomeCardsWithCids() {
        if (incomeCardContainer != null) {
            incomeCardContainer.updateFromFieldsWithCids();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize CashManager with current live FID instead of main FID
        try {
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && fidManager.getCashManager() != null && liveFid != null) {
                // Reuse the CashManager prepared during FID switch
                cashManager = fidManager.getCashManager();
            } else if (liveFid != null) {
                // Fallback: create or switch CashManager to current live FID
                cashManager = CashManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize CashManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing CashManager with liveFid: " + e.getMessage());
        }

        // Load initial page of Income objects
        loadInitialData();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_income;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.income);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        loadMoreButton = findViewById(R.id.load_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        
        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        incomeScrollView = findViewById(R.id.income_scroll_view);
        
        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        fromSortButton = findViewById(R.id.from_sort_button);
        fromSortIcon = findViewById(R.id.from_sort_icon);
        fromSortLabel = findViewById(R.id.from_sort_label);

        // Initialize summary card UI elements
        selectedIncomeCountTextView = findViewById(R.id.selectedIncomeCount);
        selectedIncomeAmountTextView = findViewById(R.id.selectedIncomeAmount);

        // Initialize statistics TextView
        incomeStatisticsTextView = findViewById(R.id.income_statistics);
        
        // Initialize summary card with zeros
        updateSummaryCard();

        // Set up sort button listeners
        setupSortButton();
        setupFromSortButton();

        // Set up swipe refresh
        setupSwipeUpdateIncome();

        // Set default sort colors
        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // IncomeActivity doesn't use QR scanning functionality for main activity
    }

    /**
     * Loads the initial data - first try from cache, then from API
     */
    private void loadInitialData() {
        // Show loading indicator
        showWaitingDialog(getString(R.string.loading));

        // Perform operations on background thread
        new Thread(() -> {
            try {
                // Check network connectivity first
                boolean networkAvailable = !NetworkUtils.isNetworkDownToast(this);

                if (networkAvailable) {
                    // Network is available, load directly from API
                    loadFromAPI(Values.REFRESH);
                } else {
                    // No network, try to load from cached income list
                    List<Income> cachedIncomeList = cashManager.getCachedIncomeList();

                    if (!cachedIncomeList.isEmpty()) {
                        // Cached data found, display it
                        runOnUiThread(() -> {
                            incomeList.clear();
                            incomeList.addAll(cachedIncomeList);

                            // Update latest and earliest income tracking
                            updateLatestAndEarliestIncome();

                            updateButtonStates();
                            loadListContainer();

                            dismissWaitingDialog();
                        });
                    } else {
                        // No network and no cached data
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            showToast(getString(R.string.network_connection_failed));
                        });
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast(getString(R.string.error_loading_income, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Loads fresh data from API using page size
     * @param operationType
     */
    private void loadFromAPI(String operationType) {
        try {
            // Fetch from API using refresh (DESC from now) with page size
            List<Income> apiIncomes = cashManager.fetchIncomeFromAPI(this, operationType, null);

            // Fetch and store CIDs for income issuers
            fetchAndStoreCidsForIncomes(apiIncomes);

            runOnUiThread(() -> {
                if (apiIncomes != null && !apiIncomes.isEmpty()) {
                    // Load the newly fetched data
                    incomeList.clear();

                    updateIncomeListAndFlags(operationType, apiIncomes);

                    // Update latest and earliest income tracking
                    updateLatestAndEarliestIncome();

                    loadListContainer();
                    dismissWaitingDialog();
                    showToast(getString(R.string.added_new_income, apiIncomes.size()));
                } else {
                    dismissWaitingDialog();
                    showToast(getString(R.string.no_income_found));
                }

                updateButtonStates();
            });
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading from API: %s", e.getMessage());
            runOnUiThread(() -> {
                dismissWaitingDialog();
                showToast(getString(R.string.error_loading_income, e.getMessage()));
            });
        }
    }

    private void updateIncomeListAndFlags(String operationType, List<Income> apiIncomes) {
        switch (operationType){
            case REFRESH -> {
                incomeList.addAll(apiIncomes);
                hasMoreOlderData = apiIncomes.size() >= pageSize;
                hasMoreNewerData = false; // We start from latest, so no newer data initially
            }
            case OLDER -> {
                incomeList.addAll(apiIncomes);
                hasMoreOlderData = apiIncomes.size() >= pageSize;
            }
            case NEWER -> {
                java.util.Collections.reverse(apiIncomes);
                incomeList.addAll(0, apiIncomes);
                hasMoreNewerData = apiIncomes.size() >= pageSize;
            }
        }
    }

    /**
     * Saves the current income list to cache for next loadInitialData
     */
    private void saveCurrentListToCache() {
        new Thread(() -> {
            try {
                int savedCount = cashManager.saveCurrentIncomeList(incomeList);
                if (savedCount > 0) {
                    cashManager.commit();
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error saving current list to cache: %s", e.getMessage());
            }
        }).start();
    }

    /**
     * Updates the latest and earliest income tracking based on current list.
     * Latest income is the one with highest time, earliest is the one with lowest time.
     */
    private void updateLatestAndEarliestIncome() {
        if (incomeList.isEmpty()) {
            latestIncome = null;
            earliestIncome = null;
            return;
        }
        
        // Since the list is displayed in time DESC order, first item is latest, last item is earliest
        latestIncome = incomeList.get(0);
        earliestIncome = incomeList.get(incomeList.size() - 1);
        
    }

    private void updateButtonStates() {
        boolean hasIncome = !(incomeList == null || incomeList.isEmpty());

        // Show/hide load more button based on hasMoreOlderData
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreOlderData ? View.VISIBLE : View.GONE);
        }
    }

    private void loadListContainer() {
        TimberLogger.d(TAG, "Loading list container with %d income items", incomeList.size());

        // Initialize the LinearLayout container for income cards
        incomeListContainer = findViewById(R.id.fragment_container);

        // Create IncomeCardContainer with selection enabled (checkbox mode)
        incomeCardContainer = new IncomeCardContainer(this, incomeListContainer, false); // false = multiple selection
        TimberLogger.d(TAG, "Created IncomeCardContainer");

        // Set the ScrollView for scroll position management
        incomeCardContainer.setScrollView(incomeScrollView);

        // Set up selection change listener
        incomeCardContainer.setOnIncomeListChangedListener(updatedIncomeList -> {
            updateUI();
        });

        // Add all income cards to the manager
        for (Income income : incomeList) {
            incomeCardContainer.addIncomeCard(income);
        }
        TimberLogger.d(TAG, "Added %d income cards to container", incomeList.size());

        // Clear any boundary indicators when initially loading the container
        if (incomeCardContainer != null) {
            incomeCardContainer.clearAllBoundaryIndicators();
        }

        // Set up select all checkbox listener after container is created
        setupSelectAllCheckBox();

        // Update UI after cards are loaded
        updateUI();

        // Update income cards with CIDs if available
        updateIncomeCardsWithCids();

        TimberLogger.d(TAG, "List container loading completed");
    }

    /**
     * Updates the statistics TextView with container size and total on-chain
     */
    private void updateStatistics() {
        if (incomeStatisticsTextView == null || cashManager == null) {
            return;
        }

        // Get container size
        int containerSize = incomeList != null ? incomeList.size() : 0;

        // Get total on-chain from CashManager (this will be set when we fetch income from API)
        Long totalOnChain = cashManager.getTotalIncome();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        incomeStatisticsTextView.setText(statsText.toString());
        incomeStatisticsTextView.setVisibility(View.VISIBLE);
    }

    private void updateUI() {
        updateButtonStates();
        updateSummaryCard();
        updateSelectAllCheckBoxState();
        updateStatistics();
        currentSortState = SortState.NONE;
        currentSortType = SortType.FCH;
        setDefaultSortColors();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && incomeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    incomeCardContainer.selectAll(isChecked);
                }
            });
        }
    }
    
    /**
     * Sets up the sort button listener
     */
    private void setupSortButton() {
        if (sortButton != null) {
            sortButton.setOnClickListener(v -> {
                // Reset FROM sort to NONE if it was active
                if (currentSortType != SortType.FCH) {
                    currentSortType = SortType.FCH;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortIncomeList();
            });
        }
    }
    
    /**
     * Sets up the FROM sort button listener
     */
    private void setupFromSortButton() {
        if (fromSortButton != null) {
            fromSortButton.setOnClickListener(v -> {
                // Reset FCH sort to NONE if it was active
                if (currentSortType != SortType.FROM) {
                    currentSortType = SortType.FROM;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortIncomeList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current income selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && incomeCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (incomeCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (incomeCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                // Some are selected - show intermediate state (unchecked)
                selectAllCheckBox.setChecked(false);
            }

            // Re-attach listener
            setupSelectAllCheckBox();
        }
    }

    /**
     * Sets up swipe refresh functionality for loading newer income
     */
    private void setupSwipeUpdateIncome() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer income");
                loadNewerIncome();
            });
            
            // Set colors for the refresh indicator
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }
    
    /**
     * Loads newer incomes when user swipes down from top
     */
    private void loadNewerIncome() {
        if (isLoadingNewer || isLoadingOlder) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch newer incomes using latest income as reference (ASC order after latest)
                List<Income> newerIncomes = cashManager.fetchIncomeFromAPI(this, "newer", latestIncome);

                // Fetch and store CIDs for income issuers
                fetchAndStoreCidsForIncomes(newerIncomes);

                runOnUiThread(() -> {
                    handleNewerIncomeResult(newerIncomes);
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer income: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_loading_newer_income, e.getMessage()));
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            }
        }).start();
    }

    @Override
    protected void setupButtons() {
        // Set click listener for Load More button
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                loadEarlierIncomes();
            });
        }
    }
    
    private void refreshList() {
        // Clear existing income card manager
        if (incomeCardContainer != null) {
            incomeCardContainer.clearAll();
        }

        // Clear existing list
        incomeList.clear();

        // Reset pagination variables
        hasMoreOlderData = true;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingOlder = false;

        // Reset income tracking
        latestIncome = null;
        earliestIncome = null;

        // Update button states
        updateButtonStates();

        // Load initial data again
        loadInitialData();
    }


    /**
     * Loads older incomes when user clicks Load More button
     */
    private void loadEarlierIncomes() {
        if (isLoadingNewer || isLoadingOlder || !hasMoreOlderData) {
            return;
        }

        isLoadingOlder = true;

        // Disable the load more button while loading
        if (loadMoreButton != null) {
            loadMoreButton.setEnabled(false);
            loadMoreButton.setAlpha(0.5f);
        }

        showToast(getString(R.string.loading_more_from_apip));

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch older incomes using earliest income as reference (DESC order after earliest)
                List<Income> olderIncomes = cashManager.fetchIncomeFromAPI(this, "older", earliestIncome);

                // Fetch and store CIDs for income issuers
                fetchAndStoreCidsForIncomes(olderIncomes);

                runOnUiThread(() -> {
                    handleOlderIncomeResult(olderIncomes);
                    isLoadingOlder = false;

                    // Re-enable the load more button after loading
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }

                    // Update button states to show/hide the load more button
                    updateButtonStates();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading older incomes: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingOlder = false;
                    showToast(getString(R.string.error_loading_more_income, e.getMessage()));

                    // Re-enable the load more button after error
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                });
            }
        }).start();
    }

    /**
     * Helper methods for managing the sliding window
     */
    private void handleNewerIncomeResult(List<Income> newerIncomes) {
        if (newerIncomes == null) {
            ToastUtils.makeText(this, getString(R.string.failed_to_update_income));
            return;
        }

        if (newerIncomes.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_new_income_found));
            return;
        }

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerIncomes.size());

        if (itemsToRemoveFromEnd > 0) {
            hasMoreOlderData = true;
            // Remove from end of data list
            removeItemsFromEnd(itemsToRemoveFromEnd);
        }

        // Reverse the list to get newest first
        java.util.Collections.reverse(newerIncomes);

        // Add new items to the beginning of data list
        incomeList.addAll(0, newerIncomes);
        updateLatestAndEarliestIncome();

        // Update the container with scroll preservation
        // This will remove items from bottom if needed and add new items to top
        // while adjusting the scroll position so user stays at the same visual location
        if (incomeCardContainer != null) {
            incomeCardContainer.addIncomeCardsToBeginningWithBottomRemoval(newerIncomes, FreerApplication.MAX_CONTAINER_SIZE);
        }

        hasMoreNewerData = newerIncomes.size() >= pageSize;

        updateStatistics();
        ToastUtils.makeText(this, getString(R.string.added_new_income, newerIncomes.size()));

        TimberLogger.i(TAG, "Loaded %d newer income items, window size: %d/%d",
            newerIncomes.size(), incomeList.size(), FreerApplication.MAX_CONTAINER_SIZE);
    }

    private void handleOlderIncomeResult(List<Income> olderIncomes) {
        if (olderIncomes != null && !olderIncomes.isEmpty()) {
            int itemsToRemoveFromBeginning = calculateItemsToRemove(olderIncomes.size());

            if (itemsToRemoveFromBeginning > 0) {
                hasMoreNewerData = true;
                // Remove from beginning of data list
                removeItemsFromBeginning(itemsToRemoveFromBeginning);
            }

            // Add new items to the end of data list
            incomeList.addAll(olderIncomes);
            updateLatestAndEarliestIncome();

            // Update the container with scroll preservation
            // This will remove items from top if needed and add new items to bottom
            // while preserving the scroll position so user can scroll down to see new items
            if (incomeCardContainer != null) {
                incomeCardContainer.addIncomeCardsToEndWithTopRemoval(olderIncomes, FreerApplication.MAX_CONTAINER_SIZE);
            }

            if (olderIncomes.size() < pageSize) {
                hasMoreOlderData = false;
            }

            updateStatistics();
            showToast(getString(R.string.loaded_more_income, olderIncomes.size()));

            TimberLogger.i(TAG, "Loaded %d older income items, window size: %d/%d",
                olderIncomes.size(), incomeList.size(), FreerApplication.MAX_CONTAINER_SIZE);
        } else {
            hasMoreOlderData = false;
            showToast(getString(R.string.no_more_income_available));

            // Update the More button visibility
            if (loadMoreButton != null) {
                loadMoreButton.setVisibility(View.GONE);
            }
        }
    }

    private int calculateItemsToRemove(int newItemsCount) {
        int currentSize = incomeList.size();
        int newTotalSize = currentSize + newItemsCount;
        return Math.max(0, newTotalSize - FreerApplication.MAX_CONTAINER_SIZE);
    }

    private void removeItemsFromBeginning(int count) {
        TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", count);
        for (int i = 0; i < count && !incomeList.isEmpty(); i++) {
            incomeList.remove(0);
        }
    }

    private void removeItemsFromEnd(int count) {
        TimberLogger.i(TAG, "Removing %d items from end to maintain window size", count);
        for (int i = 0; i < count && !incomeList.isEmpty(); i++) {
            incomeList.remove(incomeList.size() - 1);
        }
    }


    /**
     * Updates the summary card with totals from selected income list
     */
    private void updateSummaryCard() {
        List<Income> selectedIncomeList = incomeCardContainer != null ? incomeCardContainer.getSelectedIncomes() : new ArrayList<>();
        
        // Calculate totals
        int incomeCount = selectedIncomeList.size();
        long totalValue = 0;  // in satoshi
        
        for (Income income : selectedIncomeList) {
            // Sum values
            if (income.getValue() != null) {
                totalValue += income.getValue();
            }
        }
        
        // Update UI elements
        if (selectedIncomeCountTextView != null) {
            selectedIncomeCountTextView.setText(String.valueOf(incomeCount));
        }
        
        if (selectedIncomeAmountTextView != null) {
            // Convert satoshi to formatted string using FchUtils.formatSatoshiValue
            String formattedAmount = FchUtils.formatSatoshiToCoin(totalValue);
            selectedIncomeAmountTextView.setText(formattedAmount);
        }
    }
    
    /**
     * Cycles through the sort states: NONE -> ASCENDING -> DESCENDING -> NONE
     */
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
    
    /**
     * Sets default colors for all sort labels and icons
     */
    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);

        if (sortLabel != null) {
            sortLabel.setTextColor(hintColor);
        }
        if (sortIcon != null) {
            sortIcon.setColorFilter(hintColor);
        }

        if (fromSortLabel != null) {
            fromSortLabel.setTextColor(hintColor);
        }
        if (fromSortIcon != null) {
            fromSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.FCH) {
            // Reset FROM sort icon and label
            if (fromSortIcon != null) {
                fromSortIcon.setImageResource(R.drawable.ic_sort_none);
                fromSortIcon.setColorFilter(hintColor);
            }
            if (fromSortLabel != null) {
                fromSortLabel.setTextColor(hintColor);
            }
        } else {
            // Reset FCH sort icon and label
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
        }
    }
    
    /**
     * Updates the sort icons based on current sort state and type
     */
    private void updateSortIcons() {
        int iconResource;
        switch (currentSortState) {
            case NONE:
                iconResource = R.drawable.ic_sort_none;
                break;
            case ASCENDING:
                iconResource = R.drawable.ic_sort_asc;
                break;
            case DESCENDING:
                iconResource = R.drawable.ic_sort_desc;
                break;
            default:
                iconResource = R.drawable.ic_sort_none;
        }

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

        if (currentSortType == SortType.FCH && sortIcon != null && sortLabel != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.FROM && fromSortIcon != null && fromSortLabel != null) {
            fromSortIcon.setImageResource(iconResource);
            fromSortIcon.setColorFilter(color);
            fromSortLabel.setTextColor(color);
        }
    }
    
    /**
     * Sorts the income list according to current sort state and type
     */
    private void sortIncomeList() {
        if (incomeCardContainer != null) {
            if (currentSortType == SortType.FCH) {
                incomeCardContainer.sortByValue(currentSortState == SortState.ASCENDING,
                                            currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.FROM) {
                incomeCardContainer.sortByFrom(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            }
            
            // If all sort buttons are in NONE state, restore default order (desc by time)
            if (currentSortState == SortState.NONE) {
                incomeCardContainer.sortByTime(false, true); // false = descending, true = enable sort
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

        // Save current list to cache when activity is closing
        saveCurrentListToCache();

        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
        waitingDialog = null;
    }
}