package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ProofCardContainer;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.TimberLogger;

import static com.fc.fc_ajdk.constants.IndicesNames.PROOF;
import com.fc.freer.R;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProofActivity extends BaseCryptoActivity {
    // Constants
    private static final String TAG = "Proofs";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    // Core components
    private ProofCardContainer proofCardContainer;
    protected ProofManager proofManager;
    private WaitingDialog waitingDialog;

    // UI Elements - Buttons
    private ImageButton moreButton;
    private ImageButton hideButton;
    private ImageButton issueButton;
    private ImageButton clearButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;


    // UI Elements - Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView proofScrollView;
    private TextView proofStatisticsTextView;

    // UI Elements - Sort
    private LinearLayout sortButton;
    private TextView sortLabel;
    private ImageView sortIcon;
    private LinearLayout issuerSortButton;
    private TextView issuerSortLabel;
    private ImageView issuerSortIcon;
    private LinearLayout titleSortButton;
    private TextView titleSortLabel;
    private ImageView titleSortIcon;

    // Enums
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { LAST_TIME, ISSUER, TITLE }

    // State management
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.LAST_TIME;
    private boolean scrollListenerSetup = false;

    // Data and pagination
    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Proof> proofList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    // Activity result launchers
    private ActivityResultLauncher<Intent> updateProofLauncher;
    private ActivityResultLauncher<Intent> destroyProofLauncher;
    private ActivityResultLauncher<Intent> chooseContactLauncher;
    private ActivityResultLauncher<Intent> issueProofLauncher;

    // Temporary storage for proof being transferred
    private Proof proofBeingTransferred = null;

    // ================================
    // LIFECYCLE METHODS
    // ================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        // Initialize ProofManager with current live FID
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                proofManager = ProofManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ProofManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ProofManager with liveFid: " + e.getMessage());
        }

        if (proofManager == null) {
            ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later));
            finish();
            return;
        }

        // Load initial page of Proof objects
        loadInitialData();
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
        // Initialize update proof launcher
        updateProofLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize destroy proof launcher
        destroyProofLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize issue proof launcher
        issueProofLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize choose contact launcher for transferring proofs
        chooseContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String contactJson = result.getData().getStringExtra(com.fc.freer.contact.ChooseContactActivity.EXTRA_SELECTED_CONTACT);
                    if (contactJson != null ) {
                        try {
                            com.fc.fc_ajdk.data.feipData.Contact contact = com.fc.fc_ajdk.utils.JsonUtils.fromJson(contactJson, com.fc.fc_ajdk.data.feipData.Contact.class);

                            transferProofToContact(proofBeingTransferred, contact);
                            proofBeingTransferred = null;
                        } catch (Exception e) {
                            TimberLogger.e(TAG, "Failed to parse contact or proof: %s", e.getMessage());
                            showErrorMessage("Failed to transfer proof");
                            proofBeingTransferred = null;
                        }
                    }
                }else proofBeingTransferred = null;
            }
        );
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_proof;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.my_proofs);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        moreButton = findViewById(R.id.load_more_button);
        loadMoreButton = findViewById(R.id.proof_more_button);
        hideButton = findViewById(R.id.hide_button);
        issueButton = findViewById(R.id.issue_button);
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        proofScrollView = findViewById(R.id.proof_scroll_view);

        // Initialize search controls from base class
        setupSearchControls();

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortLabel = findViewById(R.id.sort_label);
        sortIcon = findViewById(R.id.sort_icon);
        issuerSortButton = findViewById(R.id.issuer_sort_button);
        issuerSortLabel = findViewById(R.id.issuer_sort_label);
        issuerSortIcon = findViewById(R.id.issuer_sort_icon);
        titleSortButton = findViewById(R.id.title_sort_button);
        titleSortLabel = findViewById(R.id.title_sort_label);
        titleSortIcon = findViewById(R.id.title_sort_icon);

        // Set default colors for sort labels and icons to hint
        setDefaultSortColors();

        // Initialize statistics TextView
        proofStatisticsTextView = findViewById(R.id.proof_statistics);

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupSortButton();
        setupIssuerSortButton();
        setupTitleSortButton();

        // Set up swipe refresh
        setupSwipeRefresh();

        // Set up spinners
        setupProofSpinners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }

    // ================================
    // DATA LOADING METHODS
    // ================================

    /**
     * Loads the initial page of Proof objects
     */
    private void loadInitialData() {
        executeInBackground(getString(R.string.loading_proofs), () -> {
            proofManager.refreshProofsFromAPI(this);
            return proofManager.getPaginatedProofs(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Proof> firstPageList = proofManager.getPaginatedProofs(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<Proof> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_proofs_found));
            updateUI();
            return;
        }

        proofList.clear();
        proofList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadProofCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<Proof> newList) {
        if (!proofList.isEmpty()) {
            latestId = proofList.get(0).getId();
            earliestId = proofList.get(proofList.size() - 1).getId();
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
        boolean hasSelected = proofCardContainer != null && !proofCardContainer.getSelectedProofs().isEmpty();

        hideButton.setEnabled(hasSelected);
        hideButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void loadProofCardList() {
        // Initialize the LinearLayout container for proof cards
        LinearLayout proofListContainer = findViewById(R.id.fragment_container);

        // Create ProofCardContainer with selection enabled (checkbox mode)
        proofCardContainer = new ProofCardContainer(this, proofListContainer, ChooseMode.CHOOSE_MULTI);

        // Set up selection change listener
        proofCardContainer.setOnProofListChangedListener(updatedProofList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Set up update proof launcher
        proofCardContainer.setUpdateProofLauncher(updateProofLauncher);

        // Set up onchain icon click listener for carving off-chain proofs
        proofCardContainer.setOnOffChainIconClickListener(this::handleOffChainIconClick);

        // Set up pay icon click listener for transferring proofs
        proofCardContainer.setOnPayIconClickListener(this::handlePayIconClick);

        // Set up sign icon click listener for signing proofs
        proofCardContainer.setOnSignIconClickListener(this::handleSignIconClick);

        Map<String, String> cidMap = proofCardContainer.getCidMap(proofList,this);

        // Add all proof cards to the manager
        for (Proof proof : proofList) {
            proofCardContainer.addProofCard(proof,cidMap);
        }

        // Update UI after cards are loaded
        updateUI();

        // Set up scroll listener for pagination
        setupScrollListener();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && proofCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    proofCardContainer.selectAll(isChecked);
                }
            });
        }
    }

    /**
     * Sets up the sort button listener (Time)
     */
    private void setupSortButton() {
        if (sortButton != null) {
            sortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if time sort was not active
                if (currentSortType != SortType.LAST_TIME) {
                    currentSortType = SortType.LAST_TIME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortProofList();
            });
        }
    }

    /**
     * Sets up the Issuer sort button listener
     */
    private void setupIssuerSortButton() {
        if (issuerSortButton != null) {
            issuerSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if issuer sort was not active
                if (currentSortType != SortType.ISSUER) {
                    currentSortType = SortType.ISSUER;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortProofList();
            });
        }
    }

    /**
     * Sets up the Title sort button listener
     */
    private void setupTitleSortButton() {
        if (titleSortButton != null) {
            titleSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if title sort was not active
                if (currentSortType != SortType.TITLE) {
                    currentSortType = SortType.TITLE;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortProofList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current proof selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && proofCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (proofCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (proofCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                // Some are selected - show intermediate state (unchecked)
                selectAllCheckBox.setChecked(false);
            }

            // Re-attach listener
            setupSelectAllCheckBox();
        }
    }

    @Override
    protected void onExitSearchMode() {
        // Clear existing proof card manager
        if (proofCardContainer != null) {
            proofCardContainer.clearAll();
        }

        // Reload initial data
        loadFirstPage();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        // Perform search from API
        performSearchFromAPI(null);
    }

    /**
     * Performs the search from API with optional reference proof for pagination
     */
    private void performSearchFromAPI(Proof referenceProof) {
        if (proofManager == null) {
            return;
        }

        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<Proof> searchResults = proofManager.searchProofsFromApi(
                    this,
                        currentSearchQuery,
                    currentSearchField,
                    currentSortField,
                    currentSortOrder,
                    referenceProof
                );

                runOnUiThread(() -> {
                    if (searchResults != null && !searchResults.isEmpty()) {
                        if (referenceProof == null) {
                            // Initial search - replace all results
                            proofList.clear();
                            proofList.addAll(searchResults);
                            hasMoreEarlierData = searchResults.size() >= pageSize;
                            hasMoreNewerData = false;
                        }

                        updatePaginationState(searchResults);

                        if (proofCardContainer != null) {
                            proofCardContainer.clearAll();
                        }
                        loadProofCardList();

                        dismissWaitingDialog();
                        showSuccessMessage(getString(R.string.found_proofs_count, searchResults.size()));
                    } else {
                        dismissWaitingDialog();
                        showInfoMessage(getString(R.string.no_proofs_found));
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

    /**
     * Sets up the spinner dropdowns specific to Proof
     */
    private void setupProofSpinners() {
        // Determine language for display names
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        // Set up 'Search in' spinner with Proof searchable fields
        if (searchFieldSpinner != null) {
            // Get searchable fields from Proof class
            LinkedHashMap<String, Map<String, String>> searchableFields = Proof.getSearchableFields();

            // Build options array starting with the placeholder
            List<String> fieldOptionsList = new ArrayList<>();
            fieldOptionsList.add(getString(R.string.search_in)); // First item is placeholder

            // Add searchable field display names
            for (Map.Entry<String, Map<String, String>> entry : searchableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                fieldOptionsList.add(displayName);
            }

            String[] fieldOptions = fieldOptionsList.toArray(new String[0]);
            setupSpinner(searchFieldSpinner, fieldOptions, R.string.field);
        }

        // Set up sort spinner with Proof sortable fields
        if (sortFieldSpinner != null) {
            // Get sortable fields from Proof class
            LinkedHashMap<String, Map<String, String>> sortableFields = Proof.getSortableFields();

            // Build options array starting with the placeholder
            List<String> sortOptionsList = new ArrayList<>();
            sortOptionsList.add(getString(R.string.sort_by)); // First item is placeholder

            // Add sortable field display names
            for (Map.Entry<String, Map<String, String>> entry : sortableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                sortOptionsList.add(displayName);
            }

            String[] sortOptions = sortOptionsList.toArray(new String[0]);
            setupSpinner(sortFieldSpinner, sortOptions, R.string.field);
        }

        // Set up Order spinner
        if (sortOrderSpinner != null) {
            String[] orderOptions = new String[]{"Order", "DESC", "ASC"};
            setupSpinner(sortOrderSpinner, orderOptions, R.string.order);
        }
    }

    /**
     * Sets up swipe refresh functionality for loading newer proofs
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer proofs");
                loadNewerProofs();
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
     * Sets up the scroll listener to detect when the user reaches the end of the list
     */
    private void setupScrollListener() {
        if (proofScrollView != null) {
            // Add scroll listener
            proofScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(proofScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                // Only trigger if user scrolled a significant distance
                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                // Check if we've reached the top and user is scrolling down to load newer proofs from container (only if not in search mode)
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerProofToContainer();
                    }
                }
            });
        }
    }

    /**
     * Checks if the scroll view is at the top
     */
    private boolean isAtTop(View scrollView) {
        if (scrollView instanceof ScrollView sv) {
            int scrollY = sv.getScrollY();

            // Must be very close to the top (within 10 pixels)
            return scrollY <= 10;
        }
        return false;
    }

    /**
     * Checks if the scroll view is at or near the bottom
     */
    private boolean isAtBottom(View scrollView) {
        if (scrollView instanceof ScrollView sv) {
            int scrollY = sv.getScrollY();
            int height = sv.getHeight();
            int viewHeight = sv.getChildAt(0) != null ? sv.getChildAt(0).getHeight() : 0;

            // Must be very close to the bottom (within 100 pixels)
            return viewHeight > 0 && (viewHeight - scrollY - height) <= 100;
        }
        return false;
    }

    /**
     * Loads newer proofs from local database first (for swipe refresh)
     */
    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer proofs from local database");

        if (proofList.isEmpty() || proofList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = proofList.get(0).getId();
            List<Proof> newerItems = proofManager.getPaginatedProofs(pageSize, newestId, false);

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

    private void handleNewerItemsFromDatabase(List<Proof> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        removeEarlier(itemsToRemoveFromEnd);

        java.util.Collections.reverse(newerItems);
        proofList.addAll(0, newerItems);
        proofCardContainer.addProofCardsToBeginning(newerItems);

        latestId = proofList.get(0).getId();
        hasMoreNewerData = newerItems.size() >= pageSize;

        updateUI();
        showSuccessMessage("Loaded " + newerItems.size() + " newer proofs from database");
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = proofList.size();
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
        currentSortType =  SortType.LAST_TIME;
        setDefaultSortColors();
    }

    private void removeEarlier(int itemsToRemoveFromEnd) {
        // Remove items from end if necessary (sliding window)
        if (itemsToRemoveFromEnd > 0) {
            TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
            proofCardContainer.removeFromEnd(itemsToRemoveFromEnd);

            // Update proofList to reflect the removal
            if (proofCardContainer.getProofList() != null) {
                proofList.clear();
                proofList.addAll(proofCardContainer.getProofList());
            }

            // Update earliestId to the new last item
            if (!proofList.isEmpty()) {
                earliestId = proofList.get(proofList.size() - 1).getId();
                hasMoreEarlierData = true; // Now we have earlier data available
            }

            // Update statistics to show the load more button
            updateStatistics();
        }
    }

    /**
     * Loads newer proofs from API (get all changes including inactive proofs for cleanup)
     */
    private void loadNewerFromAPI() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;
        executeInBackground(null, () -> {
            int added = proofManager.loadNewerProofsFromAPI(this);
            proofManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_proofs_db));
            } else if (added > 0) {
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, PROOF));
            } else {
                showInfoMessage(getString(R.string.no_new_proofs_found));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reloading from database after API sync: %s", e.getMessage());
            refreshList();
        } finally {
            isLoadingNewer = false;
            stopSwipeRefresh();
        }
    }

    /**
     * Loads more proofs from local database first, then from API if needed
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more proofs from local database");

        // Check if we already know there's no more data
        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = proofList.size();
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get next page using the last item's ID for pagination
            List<Proof> moreItems = proofManager.getPaginatedProofs(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                // Calculate how many items to remove from the beginning if we exceed window size
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                // Remove items from beginning if necessary (sliding window)
                if (itemsToRemoveFromBeginning > 0) {
                    TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", itemsToRemoveFromBeginning);
                    proofCardContainer.removeFromBeginning(itemsToRemoveFromBeginning);

                    // Update latestId to the new first item
                    if(proofCardContainer.getProofList() != null){
                        proofList.clear();
                        proofList.addAll(proofCardContainer.getProofList());
                    }

                    if (!proofList.isEmpty()) {
                        latestId = proofList.get(0).getId();
                        hasMoreNewerData = true; // Now we have newer data available
                    }
                }

                // Add new items to the end of the list
                proofList.addAll(moreItems);
                updateStatistics();

                Map<String, String> cidMap = proofCardContainer.getCidMap(proofList,this);
                for (Proof newProof : moreItems) {
                    proofCardContainer.addProofCard(newProof, cidMap);
                }

                // Update earliestId for next pagination
                earliestId = proofList.get(proofList.size() - 1).getId();

                TimberLogger.i(TAG, "Loaded %d proof items, window size: %d/%d",
                    moreItems.size(), proofList.size(), maxWindowSize);
                ToastUtils.makeText(this, "Loaded " + moreItems.size() + " proofs");
            } else {
                // No more items in local database - try to fetch from API
                loadEarlierProofs();
            }

        } catch (Exception e) {
            // Fallback to API call
            loadEarlierProofs();
        }
    }

    /**
     * Loads newer proofs into container when scrolling up
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadNewerProofToContainer() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if(!hasMoreNewerData){
            ToastUtils.makeText(this, getString(R.string.no_more_cash));
            return;
        }

        TimberLogger.i(TAG, "Loading newer proofs to container from local database");

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = proofList.size();
            int pageSize = 50; // Load 50 more items
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get newer items from database using pagination from the latest item
            String latestIdValue = latestId;
            if (latestIdValue == null) {
                TimberLogger.w(TAG, "Latest ID is null, cannot load newer proofs");
                return;
            }

            // Get newer items (items with IDs higher than the current latest item)
            List<Proof> newerItems = proofManager.getPaginatedProofs(pageSize, latestIdValue, true); // true = get items after this ID

            if (newerItems != null && !newerItems.isEmpty()) {
                // Calculate how many items to remove from the end if we exceed window size
                int newTotalSize = currentSize + newerItems.size();
                int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                // Remove items from end if necessary (sliding window)
                if (itemsToRemoveFromEnd > 0) {
                    TimberLogger.i(TAG, "Removing %d items from end to maintain window size", itemsToRemoveFromEnd);
                    proofCardContainer.removeFromEnd(itemsToRemoveFromEnd);

                    // Update earliestId to the new last item
                    if (!proofList.isEmpty()) {
                        earliestId = proofList.get(proofList.size() - 1).getId();
                        hasMoreEarlierData = true; // Now we have earlier data available
                    }

                    // Update statistics to show the load more button
                    updateStatistics();
                }

                // Add newer items to the beginning of the list
                proofCardContainer.addProofCardsToBeginning(newerItems);

                // Update latestId to the new first item
                latestId = proofList.get(0).getId();

                // Check if there are more newer items available
                if (newerItems.size() < pageSize) {
                    hasMoreNewerData = false;
                }

                TimberLogger.i(TAG, "Loaded %d newer proof items to container, window size: %d/%d",
                    newerItems.size(), proofList.size(), maxWindowSize);
                ToastUtils.makeText(this, "Loaded " + newerItems.size() + " newer proofs");
            } else {
                // No more newer items available
                hasMoreNewerData = false;
                TimberLogger.i(TAG, "No more newer proof items available");
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer proofs to container: %s", e.getMessage());
        }
    }

    /**
     * Loads newer proofs when user swipes down - tries database first, then API
     */
    private void loadNewerProofs() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // If in search mode, just refresh the search results instead of loading from API
//        if (isSearchMode) {
//            performSearch();
//            swipeRefreshLayout.setRefreshing(false);
//            return;
//        }

        loadMoreNewerFromDatabase();
    }

    /**
     * Loads earlier (older) proofs when user scrolls to bottom
     */
    private void loadEarlierProofs() {
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
            int added = proofManager.loadEarlierProofsFromAPI(this);
            proofManager.commit();
            return added;
        }, this::handleEarlierProofsResult);
    }

    private void handleEarlierProofsResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, PROOF));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                updateStatistics();
                showInfoMessage(getString(R.string.no_more_earlier_proofs_found));
            } else {
                showErrorMessage(getString(R.string.failed_to_update_proofs_db));
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

        // Set click listener for Load More button to load earlier data
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

        hideButton.setOnClickListener(v -> {
            if (proofCardContainer == null) return;
            List<Proof> chosenObjects = proofCardContainer.getSelectedProofs();
            if (chosenObjects.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }

            proofManager.addToLocalDeletedList(chosenObjects);

            // Remove selected proofs from local database
            proofManager.removeProofs(chosenObjects);
            proofManager.commit();

            // Refresh the list
            refreshList();
            showSuccessMessage("Hidden " + chosenObjects.size() + " proof(s)");
        });

        // Set click listener for Issue button
        if (issueButton != null) {
            issueButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, IssueProofActivity.class);
                issueProofLauncher.launch(intent);
            });
        }

        // Set click listener for Clear button
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                // Clear search controls using base class method
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

    // ================================
    // UTILITY METHODS
    // ================================

    private void refreshList() {
        // Clear search controls using base class method
        clearSearchControls();

        // Clear existing proof card manager
        if (proofCardContainer != null) {
            proofCardContainer.clearAll();
        }

        // Clear existing list
        proofList.clear();

        // Reset pagination variables
        earliestId = null;
        latestId = null;
        hasMoreData = true;
        hasMoreEarlierData = true;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingEarlier = false;
        scrollListenerSetup = false; // Reset scroll listener flag

        // Update UI
        updateUI();

        // Load initial data again
        loadFirstPage();
    }

    /**
     * Clears the local database and deleted list, then reloads initial data from API
     */
    private void clearAndReload() {
        if (proofManager == null) {
            ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later));
            return;
        }

        showWaitingDialog(getString(R.string.clearing_and_reloading));

        // Execute database clear operations in background thread
        executeInBackground(null, () -> {
            // Clear the local deleted list
            proofManager.clearLocalDeletedList();
            TimberLogger.i(TAG, "Cleared local deleted list");

            // Refresh entire database from API (clears DB and pagination states, then refetches)
            int count = proofManager.freshProofDB(this);
            TimberLogger.i(TAG, "Refreshed proof database, loaded %d proofs", count);

            // Return first page of proofs
            return proofManager.getPaginatedProofs(pageSize, null, true);
        }, result -> {
            // Clear UI state
            if (proofCardContainer != null) {
                proofCardContainer.clearAll();
            }
            proofList.clear();

            // Clear search controls using base class method
            clearSearchControls();

            // Reset pagination variables
            earliestId = null;
            latestId = null;
            hasMoreData = true;
            hasMoreEarlierData = true;
            hasMoreNewerData = false;
            isLoadingNewer = false;
            isLoadingEarlier = false;
            scrollListenerSetup = false;

            // Show the reloaded data
            showItemList(result);
            showSuccessMessage(getString(R.string.database_cleared_and_reloaded));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Dismiss waiting dialog if user returns without completing transaction
        // This handles the case where SendTxActivity was cancelled/backed out
        dismissWaitingDialog();

        // Set up scroll listener only once
        if (!scrollListenerSetup) {
            // Wait for proof scroll view to be ready before setting up scroll listener
            if (proofScrollView != null) {
                setupScrollListener();
                scrollListenerSetup = true;
            } else {
                // Try again after a short delay to ensure scroll view is ready
                new android.os.Handler().postDelayed(() -> {
                    if (proofScrollView != null && !scrollListenerSetup) {
                        setupScrollListener();
                        scrollListenerSetup = true;
                    }
                }, 100);
            }
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
     * Resets other sort icons and colors to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);

        if (currentSortType == SortType.LAST_TIME) {
            // Reset issuer and title sort icons and colors
            if (issuerSortIcon != null) {
                issuerSortIcon.setImageResource(R.drawable.ic_sort_none);
                issuerSortIcon.setColorFilter(hintColor);
            }
            if (issuerSortLabel != null) {
                issuerSortLabel.setTextColor(hintColor);
            }
            if (titleSortIcon != null) {
                titleSortIcon.setImageResource(R.drawable.ic_sort_none);
                titleSortIcon.setColorFilter(hintColor);
            }
            if (titleSortLabel != null) {
                titleSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.ISSUER) {
            // Reset time and title sort icons and colors
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (titleSortIcon != null) {
                titleSortIcon.setImageResource(R.drawable.ic_sort_none);
                titleSortIcon.setColorFilter(hintColor);
            }
            if (titleSortLabel != null) {
                titleSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.TITLE) {
            // Reset time and issuer sort icons and colors
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (issuerSortIcon != null) {
                issuerSortIcon.setImageResource(R.drawable.ic_sort_none);
                issuerSortIcon.setColorFilter(hintColor);
            }
            if (issuerSortLabel != null) {
                issuerSortLabel.setTextColor(hintColor);
            }
        }
    }

    /**
     * Sets default colors for all sort labels and icons to hint color
     */
    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);

        if (sortLabel != null) {
            sortLabel.setTextColor(hintColor);
        }
        if (sortIcon != null) {
            sortIcon.setColorFilter(hintColor);
        }
        if (issuerSortLabel != null) {
            issuerSortLabel.setTextColor(hintColor);
        }
        if (issuerSortIcon != null) {
            issuerSortIcon.setColorFilter(hintColor);
        }
        if (titleSortLabel != null) {
            titleSortLabel.setTextColor(hintColor);
        }
        if (titleSortIcon != null) {
            titleSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Updates the sort icons and colors based on current sort state and type
     */
    private void updateSortIcons() {
        int iconResource = switch (currentSortState) {
            case ASCENDING -> R.drawable.ic_sort_asc;
            case DESCENDING -> R.drawable.ic_sort_desc;
            default -> R.drawable.ic_sort_none;
        };

        // Determine color based on sort state
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

        if (currentSortType == SortType.LAST_TIME && sortIcon != null && sortLabel != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.ISSUER && issuerSortIcon != null && issuerSortLabel != null) {
            issuerSortIcon.setImageResource(iconResource);
            issuerSortIcon.setColorFilter(color);
            issuerSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TITLE && titleSortIcon != null && titleSortLabel != null) {
            titleSortIcon.setImageResource(iconResource);
            titleSortIcon.setColorFilter(color);
            titleSortLabel.setTextColor(color);
        }
    }

    /**
     * Sorts the proof list according to current sort state and type
     */
    private void sortProofList() {
        if (proofCardContainer != null) {
            if (currentSortType == SortType.LAST_TIME) {
                proofCardContainer.sortByLastTime(currentSortState == SortState.ASCENDING,
                                                currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.ISSUER) {
                proofCardContainer.sortByIssuer(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TITLE) {
                proofCardContainer.sortByTitle(currentSortState == SortState.ASCENDING,
                                          currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by lastTime)
            if (currentSortState == SortState.NONE) {
                proofCardContainer.sortByLastTime(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size, database size, and total on-chain
     */
    private void updateStatistics() {
        if (proofStatisticsTextView == null || proofManager == null) {
            return;
        }

        // Get container size
        int containerSize = proofList != null ? proofList.size() : 0;

        // Get database size
        long dbSize = proofManager.getProofDBSize();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        statsText.append(getString(R.string.local_in_statistics)).append(dbSize);

        proofStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.proof_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        // Update Load More button visibility based on hasMoreEarlierData
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

    /**
     * Handles off-chain icon click to carve proofs on-chain
     */
    private void handleOffChainIconClick(Proof proof) {
        // Check if proof.onChain is false and issuer is living FID
        if (proof.getOnChain() == null || proof.getOnChain()) {
            return; // Only handle off-chain proofs
        }

        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            showErrorMessage("No active FID");
            return;
        }

        String liveFid = fidManager.getLiveFid();
        if (!liveFid.equals(proof.getIssuer())) {
            showErrorMessage("You can only carve proofs you issued");
            return;
        }

        // Carve the proof on-chain
        carveProofOnChain(proof);
    }

    /**
     * Handles pay icon click to transfer proofs to contacts
     */
    private void handlePayIconClick(Proof proof) {
        // Launch ChooseContactActivity with CHOOSE_ONE_RETURN mode
        Intent intent = new Intent(this, com.fc.freer.contact.ChooseContactActivity.class);
        intent.putExtra(com.fc.freer.contact.ChooseContactActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
        proofBeingTransferred = proof;
        chooseContactLauncher.launch(intent);
    }

    /**
     * Handles sign icon click to sign proofs
     */
    private void handleSignIconClick(Proof proof) {
        signProof(proof);
    }

    /**
     * Carves an off-chain proof on-chain
     */
    private void carveProofOnChain(Proof proof) {
        String title = proof.getTitle();
        String content = proof.getContent();

        // Get cosigners array
        String[] cosignersArray;
        if (proof.getCosignersInvited() != null && !proof.getCosignersInvited().isEmpty()) {
            cosignersArray = proof.getCosignersInvited().toArray(new String[0]);
        } else {
            cosignersArray = null;
        }

        Boolean transferable = proof.isTransferable();
        // allSignsRequired is not stored in Proof, so we'll use false as default
        Boolean allSignsRequired = false;

        FidManager fidManager = FidManager.getInstance();
        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();

        String feipJson = makeIssueProofFeip(title, content, cosignersArray, transferable, allSignsRequired);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            showWaitingDialog(getString(R.string.carving_proof_on_chain));
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();

                            // Create new proof with onChain = null and the new txId
                            Proof newProof = new Proof();
                            newProof.setId(txId);
                            newProof.setTitle(title);
                            newProof.setContent(content);
                            if (cosignersArray != null && cosignersArray.length > 0) {
                                newProof.setCosignersInvited(java.util.Arrays.asList(cosignersArray));
                            }
                            newProof.setTransferable(transferable);
                            newProof.setActive(true);
                            newProof.setDestroyed(false);
                            newProof.setIssuer(proof.getIssuer());
                            newProof.setOwner(proof.getIssuer());
                            newProof.setOnChain(null);
                            newProof.setLastHeight(com.fc.fc_ajdk.constants.Constants.MaX_HEIGHT);

                            // Remove old off-chain proof from database
                            String oldProofId = proof.getId();
                            if (oldProofId != null) {
                                proofManager.removeProof(proof);
                            }

                            // Add new proof to database
                            proofManager.addProof(newProof);
                            proofManager.commit();

                            // Remove old proof card from container
                            if (proofCardContainer != null) {
                                proofCardContainer.removeProofById(oldProofId);

                                // Add new proof card to the beginning
                                Map<String, String> cidMap = proofCardContainer.getCidMap(java.util.Collections.singletonList(newProof), ProofActivity.this);
                                proofCardContainer.addProofCardsToBeginning(java.util.Collections.singletonList(newProof));
                            }

                            showSuccessMessage("Proof carved on-chain successfully with txId: " + txId);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            showErrorMessage("Failed to carve proof on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(ProofActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(ProofActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(ProofActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();
        } else {
            showErrorMessage("Failed to get private key");
        }
    }

    /**
     * Creates the FEIP JSON for issuing a proof
     */
    private static String makeIssueProofFeip(String title, String content, String[] cosigners, Boolean transferable, Boolean allSignsRequired) {
        Feip feip = Feip.fromName(PROOF);
        com.fc.fc_ajdk.data.feipData.ProofOpData proofOpData = com.fc.fc_ajdk.data.feipData.ProofOpData.makeIssue(title, content, cosigners, transferable, allSignsRequired);
        feip.setData(proofOpData);
        return feip.toJson();
    }

    /**
     * Transfers a proof to a contact
     */
    private void transferProofToContact(Proof proof, com.fc.fc_ajdk.data.feipData.Contact contact) {
        if (contact == null || contact.getFid() == null) {
            showErrorMessage("Invalid contact");
            return;
        }

        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
            showErrorMessage("No active key");
            return;
        }

        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();

        // Create transfer FEIP
        Feip feip = Feip.fromName(PROOF);
        com.fc.fc_ajdk.data.feipData.ProofOpData proofOpData = com.fc.fc_ajdk.data.feipData.ProofOpData.makeTransfer(proof.getId());
        feip.setData(proofOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            showWaitingDialog("Transferring proof...");
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveFeipWithRecipient(this, liveKeyInfo.getId(), contact.getFid(), null, feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            proof.setOnChain(null);
                            proofCardContainer.removeProofById(proof.getId());
                            proofManager.removeProof(proof);
                            showSuccessMessage("Proof transferred successfully to " + contact.getFid());
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            showErrorMessage("Failed to transfer proof: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(ProofActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(ProofActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            txSender.showSignedTxAsQR(ProofActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();
        } else {
            showErrorMessage("Failed to get private key");
        }
    }

    /**
     * Signs a proof
     */
    private void signProof(Proof proof) {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
            showErrorMessage("No active key");
            return;
        }

        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();

        // Create sign FEIP
        Feip feip = Feip.fromName(PROOF);
        com.fc.fc_ajdk.data.feipData.ProofOpData proofOpData = com.fc.fc_ajdk.data.feipData.ProofOpData.makeSign(proof.getId());
        feip.setData(proofOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            showWaitingDialog("Signing proof...");
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();

                            // Update proof onChain status to null (indicating it's on-chain but not yet confirmed)
                            proof.setOnChain(null);
                            proof.setLastHeight(com.fc.fc_ajdk.constants.Constants.MaX_HEIGHT);

                            // Update in database
                            proofManager.updateProof(proof);
                            proofManager.commit();

                            // Update the card in the container
                            if (proofCardContainer != null) {
                                proofCardContainer.updateProofCard(proof);
                            }

                            showSuccessMessage("Proof signed successfully");
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            showErrorMessage("Failed to sign proof: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(ProofActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(ProofActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            txSender.showSignedTxAsQR(ProofActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();
        } else {
            showErrorMessage("Failed to get private key");
        }
    }

    /**
     * Shows the More popup menu
     */
    private void showMoreMenu(View anchorView) {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_proof_more, (ViewGroup) anchorView.getParent(), false);
        PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        // Set up menu item click listeners
        TextView recordItem = popupView.findViewById(R.id.record_item);
        TextView destroyItem = popupView.findViewById(R.id.destroy_item);
        TextView refreshItem = popupView.findViewById(R.id.refresh_item);
        TextView destroyedListItem = popupView.findViewById(R.id.destroyed_item);

        recordItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            // TODO: Implement record functionality
            ToastUtils.makeText(this, "Record functionality to be implemented");
        });

        destroyItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (proofCardContainer == null) return;
            List<Proof> selectedProofs = proofCardContainer.getSelectedProofs();
            if (selectedProofs.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_items_selected));
                return;
            }
            String liveFid = FidManager.getInstance().getLiveFid();
            int removed = 0;
            for(Proof proof:selectedProofs){
                if(!proof.getOwner().equals(liveFid)) {
                    selectedProofs.remove(proof);
                    removed ++;
                }
            }
            if(removed>0)
                ToastUtils.makeText(this, getString(R.string.removed_the_proofs_which_you_don_t_own,removed));
            // Pass selected proofs to DestroyProofActivity
            Intent intent = new Intent(this, DestroyProofActivity.class);
            intent.putExtra("proofList", com.fc.fc_ajdk.utils.JsonUtils.toJson(selectedProofs));
            destroyProofLauncher.launch(intent);
        });

        destroyedListItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(this, ProofDestroyedActivity.class);
            startActivity(intent);
        });

        refreshItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        // Show the popup above the anchor view
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }
}
