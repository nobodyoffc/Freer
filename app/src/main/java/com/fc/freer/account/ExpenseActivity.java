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
import android.widget.ImageButton;

import com.fc.freer.FreerApplication;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ExpenseCardContainer;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.network.NetworkUtils;
import android.widget.ImageView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.ui.PopupMenuHelper;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.manager.CashManager;
import com.fc.freer.model.Expense;

import java.util.ArrayList;
import java.util.List;

public class ExpenseActivity extends BaseCryptoActivity {
    private static final String TAG = "Expense";
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_REQUEST_SIZE, FreerApplication.MAX_CONTAINER_SIZE);

    private ExpenseCardContainer expenseCardContainer;
    private LinearLayout expenseListContainer;
    protected CashManager cashManager;

    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;
    private PopupMenuHelper popupMenuHelper;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView expenseScrollView;
    
    // Sort-related UI elements
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout toSortButton;
    private ImageView toSortIcon;
    private TextView toSortLabel;
    
    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { FCH, TO }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.FCH;
    
    // Summary card UI elements
    private TextView selectedExpenseCountTextView;
    private TextView selectedExpenseAmountTextView;

    // Statistics TextView
    private TextView expenseStatisticsTextView;
    
    private final List<Expense> expenseList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean isLoadingOlder = false;
    private boolean hasMoreOlderData = true;
    private boolean hasMoreNewerData = false;
    private boolean scrollListenerSetup = false;
    
    // Track latest and earliest expenses for pagination
    private Expense latestExpense = null;  // For fetching newer expenses
    private Expense earliestExpense = null; // For fetching older expenses

    private WaitingDialog waitingDialog;

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

        // Initialize PopupMenuHelper
        popupMenuHelper = new PopupMenuHelper(this);

        // Load initial page of Expense objects
        loadInitialData();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_expense;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.expense);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        loadMoreButton = findViewById(R.id.load_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        
        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        expenseScrollView = findViewById(R.id.expense_scroll_view);
        
        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        toSortButton = findViewById(R.id.to_sort_button);
        toSortIcon = findViewById(R.id.to_sort_icon);
        toSortLabel = findViewById(R.id.to_sort_label);

        // Initialize summary card UI elements
        selectedExpenseCountTextView = findViewById(R.id.selectedExpenseCount);
        selectedExpenseAmountTextView = findViewById(R.id.selectedExpenseAmount);

        // Initialize statistics TextView
        expenseStatisticsTextView = findViewById(R.id.expense_statistics);

        // Initialize summary card with zeros
        updateSummaryCard();
        
        // Set up select all checkbox listener
        setupSelectAllCheckBox();
        
        // Set up sort button listeners
        setupSortButton();
        setupToSortButton();

        // Set up swipe refresh
        setupSwipeUpdateExpense();

        // Set default sort colors
        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // ExpenseActivity doesn't use QR scanning functionality for main activity
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
                boolean networkAvailable = NetworkUtils.isNetworkAvailable(this);

                if (networkAvailable) {
                    // Network is available, load directly from API
                    loadFromAPI(REFRESH);
                } else {
                    // No network, try to load from cached expense list
                    List<Expense> cachedExpenseList = cashManager.getCachedExpenseList();

                    if (!cachedExpenseList.isEmpty()) {
                        // Cached data found, display it
                        runOnUiThread(() -> {
                            expenseList.clear();
                            expenseList.addAll(cachedExpenseList);

                            // Update latest and earliest expense tracking
                            updateLatestAndEarliestExpense();

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
                    showToast(getString(R.string.error_loading_expense, e.getMessage()));
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
            List<Expense> apiExpenses = cashManager.fetchExpenseFromAPI(this, operationType, null);

            runOnUiThread(() -> {
                if (apiExpenses != null && !apiExpenses.isEmpty()) {
                    // Load the newly fetched data
                    expenseList.clear();

                    updateExpenseListAndFlags(operationType, apiExpenses);

                    // Update latest and earliest expense tracking
                    updateLatestAndEarliestExpense();

                    loadListContainer();
                    dismissWaitingDialog();
                    showToast(getString(R.string.added_new_expense, apiExpenses.size()));
                } else {
                    dismissWaitingDialog();
                    showToast(getString(R.string.no_expense_found));
                }

                updateButtonStates();
            });
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading from API: %s", e.getMessage());
            runOnUiThread(() -> {
                dismissWaitingDialog();
                showToast(getString(R.string.error_loading_expense, e.getMessage()));
            });
        }
    }

    private void updateExpenseListAndFlags(String operationType, List<Expense> apiExpenses) {
        switch (operationType){
            case REFRESH -> {
                expenseList.addAll(apiExpenses);
                hasMoreOlderData = apiExpenses.size() >= pageSize;
                hasMoreNewerData = false; // We start from latest, so no newer data initially
            }
            case OLDER -> {
                expenseList.addAll(apiExpenses);
                hasMoreOlderData = apiExpenses.size() >= pageSize;
            }
            case NEWER -> {
                java.util.Collections.reverse(apiExpenses);
                expenseList.addAll(0, apiExpenses);
                hasMoreNewerData = apiExpenses.size() >= pageSize;
            }
        }
    }

    /**
     * Saves the current expense list to cache for next loadInitialData
     */
    private void saveCurrentListToCache() {
        new Thread(() -> {
            try {
                int savedCount = cashManager.saveCurrentExpenseList(expenseList);
                if (savedCount > 0) {
                    cashManager.commit();
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error saving current list to cache: %s", e.getMessage());
            }
        }).start();
    }

    /**
     * Updates the latest and earliest expense tracking based on current list.
     * Latest expense is the one with highest time, earliest is the one with lowest time.
     */
    private void updateLatestAndEarliestExpense() {
        if (expenseList.isEmpty()) {
            latestExpense = null;
            earliestExpense = null;
            return;
        }
        
        // Since the list is displayed in time DESC order, first item is latest, last item is earliest
        latestExpense = expenseList.get(0);
        earliestExpense = expenseList.get(expenseList.size() - 1);
        
    }

    private void updateButtonStates() {
        boolean hasExpense = !(expenseList == null || expenseList.isEmpty());

        // Show/hide load more button based on hasMoreOlderData
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreOlderData ? View.VISIBLE : View.GONE);
        }
    }

    private void loadListContainer() {
        // Initialize the LinearLayout container for expense cards
        expenseListContainer = findViewById(R.id.fragment_container);
        
        // Create ExpenseCardContainer with selection enabled (checkbox mode)
        expenseCardContainer = new ExpenseCardContainer(this, expenseListContainer, false); // false = multiple selection
        expenseCardContainer.setScrollView(expenseScrollView);
        
        // Set up selection change listener
        expenseCardContainer.setOnExpenseListChangedListener(updatedExpenseList -> {
            updateUI();
        });
        
        // Add all expense cards to the manager
        for (Expense expense : expenseList) {
            expenseCardContainer.addExpenseCard(expense);
        }

        if (expenseCardContainer != null) {
            expenseCardContainer.clearAllBoundaryIndicators();
        }

        // Update UI after cards are loaded
        updateUI();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && expenseCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    expenseCardContainer.selectAll(isChecked);
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
                // Reset TO sort to NONE if it was active
                if (currentSortType != SortType.FCH) {
                    currentSortType = SortType.FCH;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortExpenseList();
            });
        }
    }
    
    /**
     * Sets up the TO sort button listener
     */
    private void setupToSortButton() {
        if (toSortButton != null) {
            toSortButton.setOnClickListener(v -> {
                // Reset FCH sort to NONE if it was active
                if (currentSortType != SortType.TO) {
                    currentSortType = SortType.TO;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortExpenseList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current expense selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && expenseCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);
            
            if (expenseCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (expenseCardContainer.areNoneSelected()) {
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
     * Sets up swipe refresh functionality for loading newer expense
     */
    private void setupSwipeUpdateExpense() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer expense");
                loadNewerExpense();
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
     * Loads newer expenses when user swipes down from top
     */
    private void loadNewerExpense() {
        if (isLoadingNewer || isLoadingOlder) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch newer expenses using latest expense as reference (ASC order after latest)
                List<Expense> newerExpenses = cashManager.fetchExpenseFromAPI(this, "newer", latestExpense);

                runOnUiThread(() -> {
                    if (newerExpenses == null) {
                        ToastUtils.makeText(this, getString(R.string.failed_to_update_expense));
                    } else if (!newerExpenses.isEmpty()) {
                        // Add newer expenses to the beginning of the list (they are newer)
                        // Since API returns in ASC order but we display in DESC order, reverse them
                        java.util.Collections.reverse(newerExpenses);

                        // Remove items from end if necessary (sliding window)
                        int currentSize = expenseList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + newerExpenses.size();
                        int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemoveFromEnd > 0) {
                            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
                            for (int i = 0; i < itemsToRemoveFromEnd && !expenseList.isEmpty(); i++) {
                                expenseList.remove(expenseList.size() - 1);
                            }
                            hasMoreOlderData = true;
                        }

                        expenseList.addAll(0, newerExpenses);

                        // Update latest and earliest expense tracking
                        updateLatestAndEarliestExpense();

                        // Check if there are more newer items available
                        hasMoreNewerData = newerExpenses.size() >= pageSize;

                        // Update the UI container with scroll preservation
                        if (expenseCardContainer != null) {
                            expenseCardContainer.addExpenseCardsToBeginningWithBottomRemoval(newerExpenses, FreerApplication.MAX_CONTAINER_SIZE);
                        } else {
                            loadListContainer();
                        }

                        // Reset sort state to default when loading more items
                        currentSortState = SortState.NONE;
                        currentSortType = SortType.FCH;
                        setDefaultSortColors();

                        ToastUtils.makeText(this, getString(R.string.added_new_expense, newerExpenses.size()));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.no_new_expense_found));
                    }

                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer expense: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_loading_newer_expense, e.getMessage()));
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
                loadEarlierExpenses();
            });
        }
    }
    
    private void refreshList() {
        // Clear existing expense card manager
        if (expenseCardContainer != null) {
            expenseCardContainer.clearAll();
        }
        
        // Clear existing list
        expenseList.clear();
        
        // Reset pagination variables
        hasMoreOlderData = true;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingOlder = false;

        // Reset expense tracking
        latestExpense = null;
        earliestExpense = null;
        
        // Update button states
        updateButtonStates();
        
        // Load initial data again
        loadInitialData();
    }


    /**
     * Loads older expenses when user clicks Load More button
     */
    private void loadEarlierExpenses() {
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
                // Fetch older expenses using earliest expense as reference (DESC order after earliest)
                List<Expense> olderExpenses = cashManager.fetchExpenseFromAPI(this, "older", earliestExpense);

                runOnUiThread(() -> {
                    if (olderExpenses != null && !olderExpenses.isEmpty()) {
                        // Calculate how many items we can add without exceeding the window limit
                        int currentSize = expenseList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + olderExpenses.size();
                        int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                        // Remove items from beginning if necessary (sliding window)
                        if (itemsToRemoveFromBeginning > 0) {
                            TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", itemsToRemoveFromBeginning);
                            for (int i = 0; i < itemsToRemoveFromBeginning; i++) {
                                if (!expenseList.isEmpty()) {
                                    expenseList.remove(0);
                                }
                            }
                            hasMoreNewerData = true; // Now we have newer data available
                        }

                        // Add older expenses to the end of the list (they are older)
                        expenseList.addAll(olderExpenses);

                        // Update latest and earliest expense tracking
                        updateLatestAndEarliestExpense();

                        // Check if we got less than requested (no more data)
                        if (olderExpenses.size() < pageSize) {
                            hasMoreOlderData = false;
                        }

                        // Update the UI container with scroll preservation
                        if (expenseCardContainer != null) {
                            expenseCardContainer.addExpenseCardsToEndWithTopRemoval(olderExpenses, FreerApplication.MAX_CONTAINER_SIZE);
                        } else {
                            loadListContainer();
                        }

                        // Reset sort state to default when loading more items
                        currentSortState = SortState.NONE;
                        currentSortType = SortType.FCH;
                        setDefaultSortColors();

                        showToast(getString(R.string.loaded_more_expense, olderExpenses.size()));

                        TimberLogger.i(TAG, "Loaded %d older expense items, window size: %d/%d",
                            olderExpenses.size(), expenseList.size(), maxWindowSize);
                    } else {
                        // No more data available
                        hasMoreOlderData = false;
                        showToast(getString(R.string.no_more_expense_available));
                    }

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
                TimberLogger.e(TAG, "Error loading older expenses: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingOlder = false;
                    showToast(getString(R.string.error_loading_more_expense, e.getMessage()));

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
     * Updates the statistics TextView with container size and total on-chain
     */
    private void updateStatistics() {
        if (expenseStatisticsTextView == null || cashManager == null) {
            return;
        }

        // Get container size
        int containerSize = expenseList != null ? expenseList.size() : 0;

        // Get total on-chain from CashManager (this will be set when we fetch expense from API)
        Long totalOnChain = cashManager.getTotalExpense();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        expenseStatisticsTextView.setText(statsText.toString());
        expenseStatisticsTextView.setVisibility(android.view.View.VISIBLE);
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
     * Updates the summary card with totals from selected expense list
     */
    private void updateSummaryCard() {
        List<Expense> selectedExpenseList = expenseCardContainer != null ? expenseCardContainer.getSelectedExpenses() : new ArrayList<>();

        // Calculate totals
        int expenseCount = selectedExpenseList.size();
        long totalValue = 0;  // in satoshi

        for (Expense expense : selectedExpenseList) {
            // Sum values
            if (expense.getValue() != null) {
                totalValue += expense.getValue();
            }
        }

        // Update UI elements
        if (selectedExpenseCountTextView != null) {
            selectedExpenseCountTextView.setText(String.valueOf(expenseCount));
        }

        if (selectedExpenseAmountTextView != null) {
            // Convert satoshi to formatted string using FchUtils.formatSatoshiValue
            String formattedAmount = FchUtils.formatSatoshiToCoin(totalValue);
            selectedExpenseAmountTextView.setText(formattedAmount);
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

        if (toSortLabel != null) {
            toSortLabel.setTextColor(hintColor);
        }
        if (toSortIcon != null) {
            toSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.FCH) {
            // Reset TO sort icon and label
            if (toSortIcon != null) {
                toSortIcon.setImageResource(R.drawable.ic_sort_none);
                toSortIcon.setColorFilter(hintColor);
            }
            if (toSortLabel != null) {
                toSortLabel.setTextColor(hintColor);
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
        } else if (currentSortType == SortType.TO && toSortIcon != null && toSortLabel != null) {
            toSortIcon.setImageResource(iconResource);
            toSortIcon.setColorFilter(color);
            toSortLabel.setTextColor(color);
        }
    }
    
    /**
     * Sorts the expense list according to current sort state and type
     */
    private void sortExpenseList() {
        if (expenseCardContainer != null) {
            if (currentSortType == SortType.FCH) {
                expenseCardContainer.sortByValue(currentSortState == SortState.ASCENDING,
                                            currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TO) {
                expenseCardContainer.sortByTo(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            }
            
            // If all sort buttons are in NONE state, restore default order (desc by time)
            if (currentSortState == SortState.NONE) {
                expenseCardContainer.sortByTime(false, true); // false = descending, true = enable sort
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