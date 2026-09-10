package com.fc.freer.account;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.freer.FreerApplication;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.tx.CreateTxActivity;
import com.fc.freer.utils.ToastUtils;
import android.widget.ImageView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.utils.CashCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.manager.CashManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONException;

public class CashActivity extends BaseCryptoActivity {
    private static final String TAG = "Cash";
    private static final int REQUEST_CREATE_TX = 1001; // Request code for CreateTxActivity
    private static final int REQUEST_IMPORT_CASH = 1002; // Request code for ImportCashActivity
    private static final int REQUEST_REORG_CASH = 1003; // Request code for ReorgCashActivity
    
    public static final String EXTRA_SELECT_MODE = "select_mode";
    public static final String EXTRA_SELECTED_CASH = "selected_cash";
    // Cash ids to check on the first load in select mode, e.g. a TX's current inputs.
    public static final String EXTRA_PRESELECTED_CASH_IDS = "preselected_cash_ids";
    
    private CashCardContainer cashCardContainer;
    protected CashManager cashManager;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private ImageButton moreButton;
    private ImageButton loadMoreButton;
    private Button reorganizeButton;
    private ImageButton sendButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView cashScrollView;
    
    // Sort-related UI elements
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout cdSortButton;
    private ImageView cdSortIcon;
    private TextView cdSortLabel;
    
    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { FCH, CD }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.FCH;
    
    // Summary card UI elements
    private TextView selectedCashCountTextView;
    private TextView selectedCashAmountTextView;
    private TextView selectedCashCdTextView;

    // Statistics TextView
    private TextView cashStatisticsTextView;
    
    private final List<Cash> cashList = new ArrayList<>();
    private boolean preselectionApplied = false;
    private String earliestId = null;
    private boolean isLoadingNewer = false;
    private boolean hasMoreData = true;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;

    private WaitingDialog waitingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        // Initialize CashManager with current live FID instead of main FID
        com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
        cashManager = fidManager.getCashManager();

        if(cashManager==null){
            ToastUtils.makeText(this, getString(R.string.cash_not_ready_try_later));
            finish();
            return;
        }

        // Check if we're in selection mode
        boolean isSelectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
        if (isSelectMode) {
            // Change the send button to return button
            sendButton = findViewById(R.id.send_button);
            if (sendButton != null) {
                sendButton.setImageResource(R.drawable.ic_tick);
            }
        }

        // Load initial page of Cash objects
        loadInitialData();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_cash;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.cash);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        moreButton = findViewById(R.id.more_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        reorganizeButton = findViewById(R.id.reorganize_button);
        sendButton = findViewById(R.id.send_button);
        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        cashScrollView = findViewById(R.id.cash_scroll_view);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        cdSortButton = findViewById(R.id.cd_sort_button);
        cdSortIcon = findViewById(R.id.cd_sort_icon);
        cdSortLabel = findViewById(R.id.cd_sort_label);

        // Initialize summary card UI elements
        selectedCashCountTextView = findViewById(R.id.selectedCashCount);
        selectedCashAmountTextView = findViewById(R.id.selectedCashAmount);
        selectedCashCdTextView = findViewById(R.id.selectedCashCd);

        // Initialize statistics TextView
        cashStatisticsTextView = findViewById(R.id.cash_statistics);

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupSortButton();
        setupCdSortButton();

        // Set up swipe refresh
        setupSwipeRefresh();

        // Initialize summary card with zeros
        updateSummaryCard();

        // Set default sort colors
        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // CashActivity doesn't use QR scanning functionality for main activity
    }

    // ================================
    // HELPER METHODS - BACKGROUND OPERATIONS
    // ================================

    /**
     * Generic method to execute background operations with loading dialog
     */
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
                    showToast(getString(R.string.error) + ": " + e.getMessage());
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

    /**
     * Loads the initial page of Cash objects
     */
    private void loadInitialData() {
        executeInBackground(getString(R.string.loading), () -> {
            // Limit initial load to MAX_ITEM_SIZE_IN_A_WINDOW to prevent crashes
            cashManager.loadNewerCashFromAPI(this);
            return cashManager.getPaginatedCashes(pageSize, null, true);
        }, this::showItemList);
    }

    private void showItemList(List<Cash> newList) {
        if(newList == null || newList.isEmpty()) {
            showToast(getString(R.string.no_cash_found));
            updateButtonStates();
            return;
        }

        cashList.clear();
        cashList.addAll(newList);
        if(cashCardContainer!=null)
            cashCardContainer.clearAll();

        // If we have data, set both earliest and latest indexes for windowing
        if (!cashList.isEmpty()) {
            // Get the first item's index (latest/newest)
            // Get the last item's index (earliest/oldest)
            earliestId = cashList.get(cashList.size() - 1).getId();

            // Check if there might be more data
            hasMoreData = newList.size() == pageSize;
            hasMoreEarlierData = hasMoreData;

        } else {
            // No data available
            hasMoreData = false;
            hasMoreEarlierData = false;
            earliestId = null;
        }

        updateButtonStates();
        loadListContainer();
    }

    private void updateButtonStates() {
        boolean hasCash = !(cashList==null || cashList.isEmpty());
        
        moreButton.setEnabled(true); // More button is always enabled
        moreButton.setAlpha(1.0f);
        reorganizeButton.setEnabled(hasCash);
        reorganizeButton.setAlpha(hasCash ? 1.0f : 0.5f);
        sendButton.setEnabled(hasCash);
        sendButton.setAlpha(hasCash ? 1.0f : 0.5f);
    }

    private void loadListContainer() {
        // Initialize the LinearLayout container for cash cards
        LinearLayout cashListContainer = findViewById(R.id.fragment_container);

        // Create CashCardContainer with selection enabled (checkbox mode) and delete button hidden
        cashCardContainer = new CashCardContainer(this, cashListContainer, ChooseMode.CHOOSE_MULTI, false); // CHOOSE_MULTI = multiple selection, false = hide delete button

        // Set the ScrollView for scroll position management
        cashCardContainer.setScrollView(cashScrollView);

        // Set up selection change listener
        cashCardContainer.setOnCashListChangedListener(updatedCashList -> {
            updateUI();
        });

        // Set up cash viewed listener to update database when cash is marked as not new
        cashCardContainer.setOnCashViewedListener(cash -> {
            if (cashManager != null) {
                // Update cash in database on background thread
                new Thread(() -> {
                    cashManager.updateCash(cash);
                    cashManager.commit();
                    TimberLogger.i(TAG, "Marked cash as viewed: %s", cash.getId());
                }).start();
            }
        });

        // Add all cash cards to the manager
        for (Cash cash : cashList) {
            cashCardContainer.addCashCard(cash);
        }

        // Only on the first load, so a refresh doesn't bring back cash the user unchecked.
        if (!preselectionApplied) {
            preselectionApplied = true;
            ArrayList<String> preselectedIds = getIntent().getStringArrayListExtra(EXTRA_PRESELECTED_CASH_IDS);
            if (preselectedIds != null) {
                cashCardContainer.selectByIds(new HashSet<>(preselectedIds));
            }
        }

        // Update button states and statistics after cards are loaded
        updateUI();

        // Dismiss waiting dialog after UI is ready
        dismissWaitingDialog();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && cashCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    cashCardContainer.selectAll(isChecked);
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
                // Reset CD sort to NONE if it was active
                if (currentSortType != SortType.FCH) {
                    currentSortType = SortType.FCH;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortCashList();
            });
        }
    }
    
    /**
     * Sets up the CD sort button listener
     */
    private void setupCdSortButton() {
        if (cdSortButton != null) {
            cdSortButton.setOnClickListener(v -> {
                // Reset time sort to NONE if it was active
                if (currentSortType != SortType.CD) {
                    currentSortType = SortType.CD;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortCashList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current cash selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && cashCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);
            
            if (cashCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (cashCardContainer.areNoneSelected()) {
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
     * Sets up swipe refresh functionality for loading newer cash
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer cash");
                loadNewerCash();
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
     * Loads newer cash from local database first (for swipe refresh)
     */
    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer cash from local database");

        try {
            // Get the newest item's index for pagination (cashList.get(0) is newest due to desc ordering)
            if (cashList.isEmpty()) {
                TimberLogger.i(TAG, "No cash in current list, will try API directly");
                loadNewerFromAPI();
                return;
            }

            // Get the newest item's index (first item in descending-ordered cashList)
            String newestId = cashList.get(0).getId();
            if (newestId == null) {
                TimberLogger.w(TAG, "Cannot get ID for newest item, will try API");
                loadNewerFromAPI();
                return;
            }

            // Get newer items (items with indices higher than the current newest item)
            List<Cash> newerItems = cashManager.getPaginatedCashes(pageSize, newestId, false);

            if (newerItems != null && !newerItems.isEmpty()) {
                // Found newer items in local database - insert at top and refresh container
                TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

                // Reverse newerItems to maintain chronological order when inserting at beginning
                java.util.Collections.reverse(newerItems);

                // Add newer items to the beginning of the container with boundary indicator
                // This will handle removing old items from the end if needed
                cashCardContainer.addCashCardsToBeginningWithBottomRemoval(newerItems, FreerApplication.MAX_CONTAINER_SIZE);

                // Sync the activity's cashList with the container's list
                cashList.clear();
                cashList.addAll(cashCardContainer.getCashList());

                // Update earliestId to the new last item
                if (!cashList.isEmpty()) {
                    earliestId = cashList.get(cashList.size() - 1).getId();
                }

                // Reset sort state to default when loading more items
                currentSortState = SortState.NONE;
                currentSortType = SortType.FCH;
                setDefaultSortColors();

                // Update UI
                updateUI();

                ToastUtils.makeText(this,  getString(R.string.loaded_newer_cash_from_local_db,newerItems.size() ));
                // Stop the swipe refresh indicator
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            } else {
                // No newer items in local database - try to fetch from API
                TimberLogger.i(TAG, "No newer items in database, trying API");
                loadNewerFromAPI();
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer from database: %s", e.getMessage());
            // Stop the swipe refresh indicator
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
            // Fallback to API call
            loadNewerFromAPI();
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateSummaryCard();
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    /**
     * Loads more cash from local database first, then from API if needed
     * Implements sliding window to maintain MAX_ITEM_SIZE_IN_A_WINDOW limit
     */
    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more cash from local database");

        try {
            // Get next page using the last item's index for pagination
            List<Cash> moreItems = cashManager.getPaginatedCashes(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                // Add older items to the end of the container with boundary indicator
                // This will handle removing old items from the beginning if needed
                cashCardContainer.addCashCardsToEndWithTopRemoval(moreItems, FreerApplication.MAX_CONTAINER_SIZE);

                // Sync the activity's cashList with the container's list
                cashList.clear();
                cashList.addAll(cashCardContainer.getCashList());

                // Update earliestId for next pagination
                if (!cashList.isEmpty()) {
                    earliestId = cashList.get(cashList.size() - 1).getId();
                }

                TimberLogger.i(TAG, "Loaded %d cash items, window size: %d/%d",
                    moreItems.size(), cashList.size(), FreerApplication.MAX_CONTAINER_SIZE);
                ToastUtils.makeText(this, getString(R.string.loaded_newer_cash_from_local_db,moreItems.size() ));

                // Reset sort state to default when loading more items
                currentSortState = SortState.NONE;
                currentSortType = SortType.FCH;
                setDefaultSortColors();

                hasMoreEarlierData = moreItems.size() >= pageSize;

                // Update UI
                updateUI();

                // Reset loading flag after UI updates
                isLoadingEarlier = false;
            } else {
                // No more items in local database - try to fetch from API
                isLoadingEarlier = false;
                loadEarlierCashFromApi();
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more from database: %s", e.getMessage());
            // Reset loading flag on error
            isLoadingEarlier = false;
        }
    }

    /**
     * Refreshes the cash database by clearing and reloading all valid cash
     */
    private void refreshDatabase() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }
        
        isLoadingNewer = true;
        
        // Show loading indicator
        showWaitingDialog("Refreshing cash database...");
        
        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Clear and refresh the entire valid cash database
                int refreshed = cashManager.freshValidCashDB(this);
                
                runOnUiThread(() -> {
                    // Show success message
                    if(refreshed == 0) {
                        ToastUtils.makeText(this, getString(R.string.no_cash_found));
                    } else if (refreshed > 0) {
                        // Refresh the entire list since database was cleared and reloaded
                        refreshList();
                        ToastUtils.makeText(this, getString(R.string.refreshed_cash ,refreshed ));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.failed_to_update_cash_db));
                    }
                    
                    isLoadingNewer = false;
                    dismissWaitingDialog();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cash database: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_refreshing_database, e.getMessage()));
                    isLoadingNewer = false;
                    dismissWaitingDialog();
                });
            }
        }).start();
    }
    
    /**
     * Loads newer cash when user swipes down - tries database first, then API
     */
    private void loadNewerCash() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        TimberLogger.i(TAG, "Loading newer cash - trying database first");
        loadMoreNewerFromDatabase();
    }

    /**
     * Loads newer cash from API (get all changes including spent cash for cleanup)
     */
    private void loadNewerFromAPI() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }
        
        isLoadingNewer = true;
        
        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Load newer cash changes from API
                int added = cashManager.loadNewerCashFromAPI(this);
                
                // Commit changes to disk
                cashManager.commit();
                
                runOnUiThread(() -> {
                    // Show success message
                    if(added == -1) {
                        ToastUtils.makeText(this, getString(R.string.failed_to_update_cash_db));
                    } else if (added > 0) {
                        // Always reload from database to get consistent ordering
                        // Don't use API results directly as they may have different ordering
                        try {
                            loadInitialData(); // Reload from DB to maintain consistent ordering
                        } catch (Exception e) {
                            TimberLogger.e(TAG, "Error reloading from database after API sync: %s", e.getMessage());
                            // Fallback to full refresh
                            refreshList();
                        }

                        ToastUtils.makeText(this, getString(R.string.added_new_cash, added));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.no_cash_found));
                    }

                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer cash: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_loading_newer_cash , e.getMessage()));
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            }
        }).start();
    }
    
    /**
     * Loads earlier (older) cash when user scrolls up at the top
     */
    private void loadEarlierCashFromApi() {
        if (isLoadingNewer || isLoadingEarlier ) {
            ToastUtils.makeText(this, getString(R.string.system_is_busy_wait_and_try_again));
            return;
        }

        if(!hasMoreEarlierData){
            ToastUtils.makeText(this, getString(R.string.no_more_cash));
            return;
        }
        
        isLoadingEarlier = true;

        ToastUtils.makeText(this, getString(R.string.loading_more_cash_from_network));

        // Show loading indicator
        showWaitingDialog(getString(R.string.loading_earlier_cash));
        
        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Load earlier cash from API
                int added = cashManager.loadEarlierCashFromAPI(this);
                // Commit changes to disk
                cashManager.commit();
                
                runOnUiThread(() -> {
                    if (added > 0) {
                        ToastUtils.makeText(this, getString(R.string.added_new_cash, added));
                        loadMoreEarlierFromDatabase();
                    } else if (added == 0) {
                        // No more earlier data available
                        hasMoreEarlierData = false;
                        updateStatistics(); // Update button visibility
                        ToastUtils.makeText(this, getString(R.string.no_more_earlier_cash_found));
                    } else {
                        // Error occurred
                        ToastUtils.makeText(this, getString(R.string.failed_to_update_cash_db));
                    }

                    isLoadingEarlier = false;
                    dismissWaitingDialog();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier cash: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.error_loading_cash, e.getMessage()));
                });
            }
        }).start();
    }

    @Override
    protected void setupButtons() {
        // Set click listener for More button to show popup menu
        moreButton.setOnClickListener(this::showMoreMenu);

        // Set click listener for Load More button to load older cash
        loadMoreButton.setOnClickListener(v -> {
            loadMoreEarlierFromDatabase();
        });

        reorganizeButton.setOnClickListener(v -> {
            List<Cash> selectedCashes = cashCardContainer.getSelectedCashes();
            if (selectedCashes.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }
            
            if (selectedCashes.size() > 200) {
                ToastUtils.makeText(this, R.string.too_many_cash_selected);
                return;
            }
            
            Intent intent = new Intent(CashActivity.this, ReorgCashActivity.class);
            // Convert Cash objects to JSON strings
            List<String> cashJsonList = new ArrayList<>();
            for (Cash cash : selectedCashes) {
                cashJsonList.add(cash.toJson());
            }
            intent.putStringArrayListExtra(ReorgCashActivity.EXTRA_SELECTED_CASH, new ArrayList<>(cashJsonList));
            startActivityForResult(intent, REQUEST_REORG_CASH);
        });

        sendButton.setOnClickListener(v -> {
            List<Cash> chosenObjects = cashCardContainer.getSelectedCashes();
            
            // Check if we're in selection mode
            boolean isSelectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
            if (isSelectMode) {
                if (chosenObjects.isEmpty()) {
                    ToastUtils.makeText(this, R.string.no_items_selected);
                    return;
                }
                
                if (chosenObjects.size() > 200) {
                    ToastUtils.makeText(this, R.string.too_many_cash_selected);
                    return;
                }
                
                // Return selected cash to the calling activity
                Intent resultIntent = new Intent();
                // Convert Cash objects to JSON strings
                List<String> cashJsonList = new ArrayList<>();
                for (Cash cash : chosenObjects) {
                    cashJsonList.add(cash.toJson());
                }
                resultIntent.putStringArrayListExtra(EXTRA_SELECTED_CASH, new ArrayList<>(cashJsonList));
                setResult(RESULT_OK, resultIntent);
                finish();
                return;
            }

            // Normal send mode - validate selection
            if (chosenObjects.size() > 200) {
                ToastUtils.makeText(this, R.string.too_many_cash_selected);
                return;
            }
            
            // Validate that all cash have the same owner FID or null
            String ownerFid = null;
            for (Cash cash : chosenObjects) {
                String cashOwner = cash.getOwner();
                if (cashOwner != null && !cashOwner.isEmpty()) {
                    if (ownerFid == null) {
                        ownerFid = cashOwner;
                    } else if (!ownerFid.equals(cashOwner)) {
                        ToastUtils.makeText(this, getString(R.string.all_cash_must_have_same_owner));
                        return;
                    }
                }
            }

            // Create RawTxInfo with selected cash
            com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo = new com.fc.fc_ajdk.core.fch.RawTxInfo();
            rawTxInfo.setInputs(chosenObjects);
            
            // Set sender if we have a valid owner FID
            if (ownerFid != null) {
                rawTxInfo.setSender(ownerFid);
            }
            
            // Convert to JSON string
            String rawTxInfoJson = rawTxInfo.toJsonWithSenderInfo();
            
            // CreateTxActivity now handles both normal and multisig transactions
            Intent intent = new Intent(CashActivity.this, CreateTxActivity.class);
            intent.putExtra(CreateTxActivity.EXTRA_TX_INFO_JSON, rawTxInfoJson);
            startActivityForResult(intent, REQUEST_CREATE_TX);
        });

    }
    
    private void refreshList() {
        // Show waiting dialog during refresh
        showWaitingDialog(getString(R.string.loading));

        // Clear existing cash card manager
        if (cashCardContainer != null) {
            cashCardContainer.clearAll();
            cashCardContainer.clearAllBoundaryIndicators();
        }

        // Clear existing list
        cashList.clear();

        // Reset pagination variables
        earliestId = null;
        hasMoreData = true;
        hasMoreEarlierData = true;
        isLoadingNewer = false;
        isLoadingEarlier = false;

        // Update button states
        updateButtonStates();

        // Load initial data again
        loadInitialData();
    }
    
    @Override
    protected void onResume() {
        super.onResume();

        // Only refresh if we're returning from creating a new cash
        if (getIntent().getBooleanExtra("fromCreateCash", false)) {
            refreshList();
            // Clear the flag
            getIntent().removeExtra("fromCreateCash");
        }
    }

    /**
     * Updates the summary card with totals from selected cash list
     */
    private void updateSummaryCard() {
        List<Cash> selectedCashList = cashCardContainer != null ? cashCardContainer.getSelectedCashes() : new ArrayList<>();
        CashManager.refreshCd(selectedCashList);
        
        // Calculate totals
        int cashCount = selectedCashList.size();
        long totalValue = 0;  // in satoshi
        long totalCd = 0;
        
        for (Cash cash : selectedCashList) {
            // Sum values
            if (cash.getValue() != null) {
                totalValue += cash.getValue();
            }
            
            // Sum CD (Coin Days)
            if (cash.getCd() != null) {
                totalCd += cash.getCd();
            }
        }
        
        // Update UI elements
        if (selectedCashCountTextView != null) {
            selectedCashCountTextView.setText(String.valueOf(cashCount));
        }
        
        if (selectedCashAmountTextView != null) {
            // Convert satoshi to formatted string using FchUtils.formatSatoshiValue
            String formattedAmount = FchUtils.formatSatoshiToCoin(totalValue);
            selectedCashAmountTextView.setText(formattedAmount);
        }
        
        if (selectedCashCdTextView != null) {
            // Format large number for CD (using the same format from HomeActivity)
            String formattedCd = formatLargeNumber(totalCd);
            selectedCashCdTextView.setText(formattedCd);
        }
    }
    
    /**
     * Helper method to format large numbers (borrowed from HomeActivity pattern)
     */
    private String formatLargeNumber(long number) {
        if (number >= 1000000000) {
            return String.format("%.1fb", number / 1000000000.0);
        } else if (number >= 1000000) {
            return String.format("%.1fm", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fk", number / 1000.0);
        } else {
            return String.valueOf(number);
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

        if (cdSortLabel != null) {
            cdSortLabel.setTextColor(hintColor);
        }
        if (cdSortIcon != null) {
            cdSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.FCH) {
            // Reset CD sort icon and label
            if (cdSortIcon != null) {
                cdSortIcon.setImageResource(R.drawable.ic_sort_none);
                cdSortIcon.setColorFilter(hintColor);
            }
            if (cdSortLabel != null) {
                cdSortLabel.setTextColor(hintColor);
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
        } else if (currentSortType == SortType.CD && cdSortIcon != null && cdSortLabel != null) {
            cdSortIcon.setImageResource(iconResource);
            cdSortIcon.setColorFilter(color);
            cdSortLabel.setTextColor(color);
        }
    }
    
    /**
     * Sorts the cash list according to current sort state and type
     */
    private void sortCashList() {
        if (cashCardContainer != null) {
            if (currentSortType == SortType.FCH) {
                cashCardContainer.sortByValue(currentSortState == SortState.ASCENDING,
                                            currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.CD) {
                cashCardContainer.sortByCD(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by lastTime)
            if (currentSortState == SortState.NONE) {
                cashCardContainer.sortByLastTime(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size, database size, and total on-chain
     */
    private void updateStatistics() {
        if (cashStatisticsTextView == null || cashManager == null) {
            return;
        }

        // Get container size
        int containerSize = cashList != null ? cashList.size() : 0;

        // Get database size
        long dbSize = cashManager.getCashDBSize();

        // Get total on-chain
        Long totalOnChain = cashManager.getTotalCashOnChain();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        statsText.append(getString(R.string.local_in_statistics)).append(dbSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        cashStatisticsTextView.setText(statsText.toString());
        cashStatisticsTextView.setVisibility(View.VISIBLE);

        // Show or hide the loadMoreButton based on hasMoreEarlierData
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreEarlierData ? View.VISIBLE : View.GONE);
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
    
    /**
     * Shows the More popup menu
     */
    private void showMoreMenu(View anchorView) {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_cash_more, null);
        PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);
        
        // Set up menu item click listeners
        TextView refreshAllItem = popupView.findViewById(R.id.refresh_all_item);
        TextView importItem = popupView.findViewById(R.id.import_item);
        TextView exportItem = popupView.findViewById(R.id.export_item);
        
        refreshAllItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            refreshDatabase(); // Use the existing refresh functionality
        });
        
        importItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(CashActivity.this, ImportCashActivity.class);
            startActivityForResult(intent, REQUEST_IMPORT_CASH);
        });
        
        exportItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            exportSelectedCash();
        });
        
        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        
        // Show the popup above the anchor view
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    /**
     * Exports selected cash as simplified JSON objects
     */
    private void exportSelectedCash() {
        List<Cash> selectedCashes = cashCardContainer.getSelectedCashes();
        
        // Check if selected cash list is empty
        if (selectedCashes.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }
        
        try {
            // Create simplified cash objects and convert to JSON
            List<String> simplifiedCashJsonList = new ArrayList<>();
            
            for (Cash cash : selectedCashes) {
                // Create simplified cash object with only required fields
                JSONObject simplifiedCash = new JSONObject();
                simplifiedCash.put("owner", cash.getOwner() != null ? cash.getOwner() : "");
                simplifiedCash.put("birthTxId", cash.getBirthTxId() != null ? cash.getBirthTxId() : "");
                simplifiedCash.put("birthIndex", cash.getBirthIndex() != null ? cash.getBirthIndex() : 0);
                simplifiedCash.put("value", cash.getValue() != null ? cash.getValue() : 0);
                simplifiedCash.put("birthTime", cash.getBirthTime() != null ? cash.getBirthTime() : 0);
                
                simplifiedCashJsonList.add(simplifiedCash.toString());
            }
            
            // Merge all JSON together as exporting string
            StringBuilder exportingString = new StringBuilder();
            for (int i = 0; i < simplifiedCashJsonList.size(); i++) {
                exportingString.append(simplifiedCashJsonList.get(i));
                if (i < simplifiedCashJsonList.size() - 1) {
                    exportingString.append("\n");
                }
            }
            
            // Show QR codes for the exporting string
            QRCodeGenerator.generateAndShowQRCodeFromJsonList(this, exportingString.toString(), getString(R.string.export_cash));
            
        } catch (JSONException e) {
            TimberLogger.e(TAG, "Error creating export JSON: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.error_creating_export_data, e.getMessage()));
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CREATE_TX) {
            // Refresh cash list when returning from CreateTxActivity
            // This is needed because cash may have been added or removed during transaction creation/signing
            refreshList();
        } else if (requestCode == REQUEST_IMPORT_CASH && resultCode == RESULT_OK) {
            // Refresh cash list when returning successfully from ImportCashActivity
            // This is needed because new cash may have been imported
            refreshList();
        } else if (requestCode == REQUEST_REORG_CASH && resultCode == RESULT_OK) {
            // Refresh cash list only when returning successfully from ReorgCashActivity
            // This is needed because cash may have been reorganized (spent and new cash created)
            refreshList();
        }
    }
} 