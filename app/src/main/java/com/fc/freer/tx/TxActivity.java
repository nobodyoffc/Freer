package com.fc.freer.tx;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Tx;
import com.fc.fc_ajdk.utils.NumberUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.network.NetworkUtils;
import android.widget.ImageView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.fcData.FidTxMask;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.constants.Values;
import com.fc.freer.R;
import com.fc.freer.utils.TxCardContainer;
import com.fc.freer.ui.PopupMenuHelper;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;
import android.widget.ImageButton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TxActivity extends BaseCryptoActivity {
    private static final String TAG = "TxActivity";
    private static final int DEFAULT_PAGE_SIZE = 50;
    
    private TxCardContainer txCardContainer;
    private LinearLayout txListContainer;
    protected CashManager cashManager;
    private FapiClient fapiClient;

    private ImageButton createButton;
    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;
    private PopupMenuHelper popupMenuHelper;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView txScrollView;
    
    // Sort-related UI elements
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout payerSortButton;
    private ImageView payerSortIcon;
    private TextView payerSortLabel;
    private LinearLayout payeeSortButton;
    private ImageView payeeSortIcon;
    private TextView payeeSortLabel;
    
    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { FCH, PAYER, PAYEE }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.FCH;
    
    // Summary card UI elements
    private TextView selectedTxCountTextView;
    private TextView selectedTxAmountTextView;

    // Statistics TextView
    private TextView txStatisticsTextView;
    
    private final List<FidTxMask> txList = new ArrayList<>();
    private final int pageSize = Math.min(FreerApplication.DEFAULT_REQUEST_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private boolean isLoadingNewer = false;
    private boolean isLoadingFresh = false;
    private boolean isLoadingOlder = false;
    private boolean hasMoreOlderData = true;
    private boolean hasMoreNewerData = false;
    private boolean scrollListenerSetup = false;
    
    // Track latest and earliest transactions for pagination
    private FidTxMask latestTx = null;  // For fetching newer transactions
    private FidTxMask earliestTx = null; // For fetching older transactions

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

        // Initialize FapiClient
        fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "No FapiClient available from ApiCenter when initiating TxActivity.");
        }

        // Load initial page of FidTxMask objects
        loadInitialData();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.tx_transactions);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        createButton = findViewById(R.id.create_button);
        loadMoreButton = findViewById(R.id.load_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        
        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        txScrollView = findViewById(R.id.tx_scroll_view);
        
        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        payerSortButton = findViewById(R.id.payer_sort_button);
        payerSortIcon = findViewById(R.id.payer_sort_icon);
        payerSortLabel = findViewById(R.id.payer_sort_label);
        payeeSortButton = findViewById(R.id.payee_sort_button);
        payeeSortIcon = findViewById(R.id.payee_sort_icon);
        payeeSortLabel = findViewById(R.id.payee_sort_label);

        // Initialize summary card UI elements
        selectedTxCountTextView = findViewById(R.id.selectedTxCount);
        selectedTxAmountTextView = findViewById(R.id.selectedTxAmount);

        // Initialize statistics TextView
        txStatisticsTextView = findViewById(R.id.tx_statistics);

        // Initialize summary card with zeros
        updateSummaryCard();

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupSortButton();
        setupPayerSortButton();
        setupPayeeSortButton();

        // Set up swipe refresh
        setupSwipeUpdateTx();

        // Set default sort colors
        setDefaultSortColors();
    }


    /**
     * Helper method to collect payer/payee FIDs from tx list and fetch their CIDs
     * @param txs List of FidTxMask objects to process
     */
    private void fetchAndStoreCidsForTxs(List<FidTxMask> txs) {
        if (txs == null || txs.isEmpty()) {
            return;
        }

        // Collect unique payer and payee FIDs
        Set<String> fidSet = new HashSet<>();
        for (FidTxMask tx : txs) {
            if (tx.getFrom() != null && !tx.getFrom().isEmpty()) {
                fidSet.add(tx.getFrom());
            }
            if (tx.getTo() != null && !tx.getTo().isEmpty()) {
                fidSet.add(tx.getTo());
            }
        }

        if (fidSet.isEmpty()) {
            return;
        }

        // Convert set to list for API call
        List<String> fidList = new ArrayList<>(fidSet);

        // Perform API call on background thread
        new Thread(() -> {
            try {
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient != null) {
                    Map<String, String> fidCidMap = fapiClient.getFidCidMap(fidList);

                    if (!fidCidMap.isEmpty()) {
                        // Store the mappings in CidFidManager
                        CidFidManager cidFidManager = CidFidManager.getInstance(this);
                        cidFidManager.addAll(fidCidMap);
                        TimberLogger.d(TAG, "Stored %d FID-CID mappings for tx payers/payees", fidCidMap.size());
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching CIDs for tx payers/payees: %s", e.getMessage());
            }
        }).start();
    }

    /**
     * Updates the tx cards to display CIDs instead of FIDs when CID mappings are available
     */
    private void updateTxCardsWithCids() {
        if (txCardContainer != null) {
            txCardContainer.updatePayerPayeeFieldsWithCids();
        }
    }
    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // TxActivity doesn't use QR scanning functionality for main activity
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
                    loadFromAPI(Values.REFRESH);
                } else {
                    // No network, try to load from cached tx list
                    List<FidTxMask> cachedTxList = cashManager.getCachedTxList();

                    if (!cachedTxList.isEmpty()) {
                        // Cached data found, display it
                        runOnUiThread(() -> {
                            txList.clear();
                            txList.addAll(cachedTxList);

                            // Update latest and earliest tx tracking
                            updateLatestAndEarliestTx();

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
                    showToast(getString(R.string.error_loading_tx, e.getMessage()));
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
            // Fetch from API using operationType with page size
            List<FidTxMask> apiTxs = cashManager.fetchTxFromAPI(this, operationType, null);

            // Fetch and store CIDs for tx payers/payees
            fetchAndStoreCidsForTxs(apiTxs);

            runOnUiThread(() -> {
                if (apiTxs != null && !apiTxs.isEmpty()) {
                    // Load the newly fetched data
                    txList.clear();

                    updateTxListAndFlags(operationType, apiTxs);

                    // Update latest and earliest tx tracking
                    updateLatestAndEarliestTx();

                    loadListContainer();
                    dismissWaitingDialog();
                    showToast(getString(R.string.added_new_tx, apiTxs.size()));
                } else {
                    dismissWaitingDialog();
                    showToast(getString(R.string.no_tx_found));
                }
            });
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading from API: %s", e.getMessage());
            runOnUiThread(() -> {
                dismissWaitingDialog();
                showToast(getString(R.string.error_loading_tx, e.getMessage()));
            });
        }
    }

    private void updateTxListAndFlags(String operationType, List<FidTxMask> apiTxs) {
        switch (operationType) {
            case Values.REFRESH -> {
                txList.addAll(apiTxs);
                hasMoreOlderData = apiTxs.size() >= pageSize;
                hasMoreNewerData = false; // We start from latest, so no newer data initially
            }
            case Values.OLDER -> {
                txList.addAll(apiTxs);
                hasMoreOlderData = apiTxs.size() >= pageSize;
            }
            case Values.NEWER -> {
                java.util.Collections.reverse(apiTxs);
                txList.addAll(0, apiTxs);
                hasMoreNewerData = apiTxs.size() >= pageSize;
            }
        }
    }

    /**
     * Saves the current tx list to cache for next loadInitialData
     */
    private void saveCurrentListToCache() {
        new Thread(() -> {
            try {
                int savedCount = cashManager.saveCurrentTxList(txList);
                if (savedCount > 0) {
                    cashManager.commit();
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error saving current list to cache: %s", e.getMessage());
            }
        }).start();
    }

    /**
     * Updates the latest and earliest tx tracking based on current list.
     * Latest tx is the one with highest time, earliest is the one with lowest time.
     */
    private void updateLatestAndEarliestTx() {
        if (txList.isEmpty()) {
            latestTx = null;
            earliestTx = null;
            return;
        }
        
        // Since the list is displayed in time DESC order, first item is latest, last item is earliest
        latestTx = txList.get(0);
        earliestTx = txList.get(txList.size() - 1);
    }

    private void updateButtonStates() {
        boolean hasTx = !(txList == null || txList.isEmpty());

        createButton.setEnabled(true);
        createButton.setAlpha(1.0f);

        // Show/hide load more button based on hasMoreOlderData
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreOlderData ? View.VISIBLE : View.GONE);
        }
    }

    private void loadListContainer() {
        // Initialize the LinearLayout container for tx cards
        txListContainer = findViewById(R.id.fragment_container);

        // Create TxCardContainer with selection enabled (checkbox mode)
        txCardContainer = new TxCardContainer(this, txListContainer, false); // false = multiple selection
        txCardContainer.setScrollView(txScrollView);

        // Set up selection change listener
        txCardContainer.setOnTxListChangedListener(updatedTxList -> {
            updateUI();
        });
        
        // Set up custom click listener to fetch Tx and show details
        txCardContainer.setOnTxClickListener(this::fetchAndShowTxInfo);
        
        // Add all tx cards to the manager
        for (FidTxMask txInfo : txList) {
            txCardContainer.addTxCard(txInfo);
        }

        // Clear any boundary indicators when fully reloading the container
        txCardContainer.clearAllBoundaryIndicators();

        // Update UI after cards are loaded
        updateUI();

        // Update tx cards with CIDs if available
        updateTxCardsWithCids();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && txCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    txCardContainer.selectAll(isChecked);
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
                // Reset other sorts to NONE if they were active
                if (currentSortType != SortType.FCH) {
                    currentSortType = SortType.FCH;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortTxList();
            });
        }
    }
    
    /**
     * Sets up the PAYER sort button listener
     */
    private void setupPayerSortButton() {
        if (payerSortButton != null) {
            payerSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if they were active
                if (currentSortType != SortType.PAYER) {
                    currentSortType = SortType.PAYER;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortTxList();
            });
        }
    }
    
    /**
     * Sets up the PAYEE sort button listener
     */
    private void setupPayeeSortButton() {
        if (payeeSortButton != null) {
            payeeSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if they were active
                if (currentSortType != SortType.PAYEE) {
                    currentSortType = SortType.PAYEE;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortTxList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current tx selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && txCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);
            
            if (txCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (txCardContainer.areNoneSelected()) {
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
     * Sets up swipe refresh functionality for loading newer transactions
     */
    private void setupSwipeUpdateTx() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer transactions");
                loadMoreNewerTx();
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
     * Loads newer transactions when user swipes down from top
     */
    private void loadMoreNewerTx() {
        if (isLoadingNewer || isLoadingOlder) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch newer transactions using latest tx as reference (ASC order after latest)
                List<FidTxMask> newerTxs = cashManager.fetchTxFromAPI(this, "newer", latestTx);

                // Fetch and store CIDs for tx payers/payees
                fetchAndStoreCidsForTxs(newerTxs);

                runOnUiThread(() -> {
                    if (newerTxs == null) {
                        ToastUtils.makeText(this, getString(R.string.failed_to_update_tx));
                    } else if (!newerTxs.isEmpty()) {
                        int itemsToRemoveFromEnd = calculateItemsToRemove(newerTxs.size());

                        if (itemsToRemoveFromEnd > 0) {
                            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
                            removeItemsFromEnd(itemsToRemoveFromEnd);
                            hasMoreOlderData = true;
                        }

                        java.util.Collections.reverse(newerTxs);
                        txList.addAll(0, newerTxs);

                        updateLatestAndEarliestTx();
                        hasMoreNewerData = newerTxs.size() >= pageSize;

                        if (txCardContainer != null) {
                            txCardContainer.addTxCardsToBeginningWithBottomRemoval(newerTxs, FreerApplication.MAX_CONTAINER_SIZE);
                            txCardContainer.updatePayerPayeeFieldsWithCids();
                        } else {
                            loadListContainer();
                        }

                        updateStatistics();
                        ToastUtils.makeText(this, getString(R.string.added_new_tx, newerTxs.size()));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.no_new_tx_found));
                    }

                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer transactions: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_loading_newer_tx, e.getMessage()));
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
                loadMoreEarlierTxs();
            });
        }

        // Set click listener for Create button
        createButton.setOnClickListener(v -> {
            // Get current live FID to determine which activity to launch
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            
            if (liveFid == null) {
                showToast("No active FID available");
                return;
            }
            
            // CreateTxActivity now handles both normal and multisig transactions
            android.content.Intent intent = new android.content.Intent(this, CreateTxActivity.class);
            startActivity(intent);
        });
    }
    
    private void refreshList() {
        // Clear existing tx card manager
        if (txCardContainer != null) {
            txCardContainer.clearAll();
        }
        
        // Clear existing list
        txList.clear();
        
        // Reset pagination variables
        hasMoreOlderData = true;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingOlder = false;

        // Reset tx tracking
        latestTx = null;
        earliestTx = null;
        
        // Update button states
        updateButtonStates();
        
        // Load initial data again
        loadInitialData();
    }


    /**
     * Loads older transactions when user clicks Load More button
     */
    private void loadMoreEarlierTxs() {
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
                // Fetch older transactions using earliest tx as reference (DESC order after earliest)
                List<FidTxMask> olderTxs = cashManager.fetchTxFromAPI(this, "older", earliestTx);

                // Fetch and store CIDs for tx payers/payees
                fetchAndStoreCidsForTxs(olderTxs);

                runOnUiThread(() -> {
                    if (olderTxs != null && !olderTxs.isEmpty()) {
                        int itemsToRemoveFromBeginning = calculateItemsToRemove(olderTxs.size());

                        if (itemsToRemoveFromBeginning > 0) {
                            TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", itemsToRemoveFromBeginning);
                            removeItemsFromBeginning(itemsToRemoveFromBeginning);
                            hasMoreNewerData = true;
                        }

                        txList.addAll(olderTxs);

                        updateLatestAndEarliestTx();

                        if (olderTxs.size() < pageSize) {
                            hasMoreOlderData = false;
                        }

                        if (txCardContainer != null) {
                            txCardContainer.addTxCardsToEndWithTopRemoval(olderTxs, FreerApplication.MAX_CONTAINER_SIZE);
                            txCardContainer.updatePayerPayeeFieldsWithCids();
                        } else {
                            loadListContainer();
                        }

                        updateStatistics();
                        showToast(getString(R.string.loaded_more_tx, olderTxs.size()));

                        TimberLogger.i(TAG, "Loaded %d older tx items, window size: %d/%d",
                            olderTxs.size(), txList.size(), FreerApplication.MAX_CONTAINER_SIZE);
                    } else {
                        hasMoreOlderData = false;
                        showToast(getString(R.string.no_more_tx_available));
                    }

                    isLoadingOlder = false;

                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }

                    updateButtonStates();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading older transactions: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingOlder = false;
                    showToast(getString(R.string.error_loading_more_tx, e.getMessage()));

                    // Re-enable the load more button after error
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                });
            }
        }).start();
    }


    private int calculateItemsToRemove(int newItemsCount) {
        int currentSize = txList.size();
        int newTotalSize = currentSize + newItemsCount;
        return Math.max(0, newTotalSize - FreerApplication.MAX_CONTAINER_SIZE);
    }

    private void removeItemsFromEnd(int count) {
        if (count <= 0) {
            return;
        }

        TimberLogger.i(TAG, "Removing %d items from end to maintain window size", count);
        for (int i = 0; i < count && !txList.isEmpty(); i++) {
            txList.remove(txList.size() - 1);
        }
    }

    private void removeItemsFromBeginning(int count) {
        if (count <= 0) {
            return;
        }

        TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", count);
        for (int i = 0; i < count && !txList.isEmpty(); i++) {
            txList.remove(0);
        }
    }


    /**
     * Updates the statistics TextView with container size and total on-chain
     */
    private void updateStatistics() {
        if (txStatisticsTextView == null || cashManager == null) {
            return;
        }

        // Get container size
        int containerSize = txList != null ? txList.size() : 0;

        // Get total on-chain from CashManager (this will be set when we fetch tx from API)
        Long totalOnChain = cashManager.getTotalTx();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        txStatisticsTextView.setText(statsText.toString());
        txStatisticsTextView.setVisibility(View.VISIBLE);
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
     * Updates the summary card with totals from selected tx list
     */
    private void updateSummaryCard() {
        List<FidTxMask> selectedTxList = txCardContainer != null ? txCardContainer.getSelectedTxs() : new ArrayList<>();
        
        // Calculate totals
        int txCount = selectedTxList.size();
        double totalValue = 0;  // in satoshi
        
        for (FidTxMask txInfo : selectedTxList) {
            // Sum values
            if (txInfo.getBalance() != null) {
                totalValue += txInfo.getBalance();
            }
        }
        
        // Update UI elements
        if (selectedTxCountTextView != null) {
            selectedTxCountTextView.setText(String.valueOf(txCount));
        }
        
        if (selectedTxAmountTextView != null) {
            // Convert satoshi to formatted string using FchUtils.formatSatoshiValue
            String formattedAmount = NumberUtils.formatAmount(totalValue);
            selectedTxAmountTextView.setText(formattedAmount);
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

        if (payerSortLabel != null) {
            payerSortLabel.setTextColor(hintColor);
        }
        if (payerSortIcon != null) {
            payerSortIcon.setColorFilter(hintColor);
        }

        if (payeeSortLabel != null) {
            payeeSortLabel.setTextColor(hintColor);
        }
        if (payeeSortIcon != null) {
            payeeSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.FCH) {
            // Reset PAYER and PAYEE sort icons and labels
            if (payerSortIcon != null) {
                payerSortIcon.setImageResource(R.drawable.ic_sort_none);
                payerSortIcon.setColorFilter(hintColor);
            }
            if (payerSortLabel != null) {
                payerSortLabel.setTextColor(hintColor);
            }
            if (payeeSortIcon != null) {
                payeeSortIcon.setImageResource(R.drawable.ic_sort_none);
                payeeSortIcon.setColorFilter(hintColor);
            }
            if (payeeSortLabel != null) {
                payeeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.PAYER) {
            // Reset FCH and PAYEE sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (payeeSortIcon != null) {
                payeeSortIcon.setImageResource(R.drawable.ic_sort_none);
                payeeSortIcon.setColorFilter(hintColor);
            }
            if (payeeSortLabel != null) {
                payeeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.PAYEE) {
            // Reset FCH and PAYER sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (payerSortIcon != null) {
                payerSortIcon.setImageResource(R.drawable.ic_sort_none);
                payerSortIcon.setColorFilter(hintColor);
            }
            if (payerSortLabel != null) {
                payerSortLabel.setTextColor(hintColor);
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
        } else if (currentSortType == SortType.PAYER && payerSortIcon != null && payerSortLabel != null) {
            payerSortIcon.setImageResource(iconResource);
            payerSortIcon.setColorFilter(color);
            payerSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.PAYEE && payeeSortIcon != null && payeeSortLabel != null) {
            payeeSortIcon.setImageResource(iconResource);
            payeeSortIcon.setColorFilter(color);
            payeeSortLabel.setTextColor(color);
        }
    }
    
    /**
     * Sorts the tx list according to current sort state and type
     */
    private void sortTxList() {
        if (txCardContainer != null) {
            if (currentSortType == SortType.FCH) {
                txCardContainer.sortByValue(currentSortState == SortState.ASCENDING,
                                        currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.PAYER) {
                txCardContainer.sortByPayer(currentSortState == SortState.ASCENDING,
                                       currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.PAYEE) {
                txCardContainer.sortByPayee(currentSortState == SortState.ASCENDING,
                                        currentSortState != SortState.NONE);
            }
            
            // If all sort buttons are in NONE state, restore default order (desc by time)
            if (currentSortState == SortState.NONE) {
                txCardContainer.sortByTime(false, true); // false = descending, true = enable sort
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

    /**
     * Fetches Tx details using the fidTxMask ID and shows Tx detail activity
     * 
     * @param fidTxMask The FidTxMask object whose ID will be used to fetch Tx
     */
    public void fetchAndShowTxInfo(FidTxMask fidTxMask) {
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch Tx: FapiClient not available");
            showToast("FapiClient not available");
            return;
        }
        
        if (fidTxMask == null || fidTxMask.getId() == null) {
            TimberLogger.w(TAG, "Cannot fetch Tx: Invalid fidTxMask or ID");
            showToast("Invalid transaction ID");
            return;
        }
        
        // Show loading dialog
        showWaitingDialog(getString(R.string.loading));
        
        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch Tx using fapiClient.txByIds
                Map<String, Tx> txInfoMap = fapiClient.txByIds(Collections.singletonList(fidTxMask.getId()));
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    
                    if (txInfoMap != null && !txInfoMap.isEmpty()) {
                        // Get the first (and should be only) result
                        Tx tx = txInfoMap.values().iterator().next();
                        
                        if (tx != null) {
                            // Show Tx detail activity
                            android.content.Intent intent = new android.content.Intent(this, com.fc.freer.ui.DetailActivity.class);
                            intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, tx.toJson());
                            intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Tx.class.getName());
                            startActivity(intent);
                        } else {
                            showToast("Failed to load transaction details");
                        }
                    } else {
                        showToast("No transaction details found");
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching Tx: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast("Error loading transaction details: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Loads the FapiClient from ApiCenter
     * 
     * @return FapiClient instance, or null if not available
     */
    private FapiClient loadFapiClient() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            Object client = apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client instanceof FapiClient) {
                return (FapiClient) client;
            }
            TimberLogger.w(TAG, "No FapiClient available from ApiCenter");
            return null;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting FapiClient from ApiCenter: %s", e.getMessage());
            return null;
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