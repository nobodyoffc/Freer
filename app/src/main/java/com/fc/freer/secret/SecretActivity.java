package com.fc.freer.secret;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.SecretCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.SecretOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;
import com.fc.freer.R;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.manager.FidManager;
import android.widget.ImageButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SecretActivity extends BaseCryptoActivity {
    // Constants
    private static final String TAG = "Secrets";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    // Core components
    private SecretCardContainer secretCardContainer;
    protected SecretManager secretManager;
    private WaitingDialog waitingDialog;

    // UI Elements - Buttons
    private ImageButton moreButton;
    private ImageButton deleteButton;
    private ImageButton createNewButton;
    
    private CheckBox selectAllCheckBox;

    private ImageButton loadMoreButton;
    // UI Elements - Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView secretScrollView;
    private TextView secretStatisticsTextView;

    // UI Elements - Search
    private EditText searchEditText;
    private ImageView searchIcon;
    private ImageView clearSearchIcon;

    // UI Elements - Sort
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout typeSortButton;
    private ImageView typeSortIcon;
    private TextView typeSortLabel;
    private LinearLayout titleSortButton;
    private ImageView titleSortIcon;
    private TextView titleSortLabel;

    // Enums
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { SAVE_TIME, TYPE, TITLE, UPDATE_HEIGHT }

    // State management
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.UPDATE_HEIGHT;
    private boolean isSearchMode = false;
    private String currentSearchQuery = "";
    private boolean scrollListenerSetup = false;

    // Data and pagination
    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Secret> secretList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    // Activity result launchers
    private ActivityResultLauncher<Intent> deleteSecretLauncher;
    private ActivityResultLauncher<Intent> importSecretLauncher;
    private ActivityResultLauncher<Intent> updateSecretLauncher;

    // ================================
    // LIFECYCLE METHODS
    // ================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        // Initialize SecretManager with current live FID
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                secretManager = SecretManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize SecretManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing SecretManager with liveFid: " + e.getMessage());
        }

        if (secretManager == null) {
            ToastUtils.makeText(this, getString(R.string.secret_not_ready_try_later));
            finish();
            return;
        }

        // Load initial page of Secret objects
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
        // Initialize delete secret launcher
        deleteSecretLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize import secret launcher
        importSecretLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize update secret launcher
        updateSecretLauncher = registerForActivityResult(
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
        return R.layout.activity_secret;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.my_secrets);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        moreButton = findViewById(R.id.more_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        deleteButton = findViewById(R.id.delete_button);
        createNewButton = findViewById(R.id.create_button);
        
        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        
        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        secretScrollView = findViewById(R.id.secret_scroll_view);

        // Initialize search elements
        searchEditText = findViewById(R.id.search_edit_text);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        
        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        typeSortButton = findViewById(R.id.type_sort_button);
        typeSortIcon = findViewById(R.id.type_sort_icon);
        typeSortLabel = findViewById(R.id.type_sort_label);
        titleSortButton = findViewById(R.id.title_sort_button);
        titleSortIcon = findViewById(R.id.title_sort_icon);
        titleSortLabel = findViewById(R.id.title_sort_label);

        // Set default sort colors
        setDefaultSortColors();

        // Initialize statistics TextView
        secretStatisticsTextView = findViewById(R.id.secret_statistics);

        // Set up select all checkbox listener
        setupSelectAllCheckBox();
        
        // Set up sort button listeners
        setupSortButton();
        setupTypeSortButton();
        setupTitleSortButton();
        
        // Set up swipe refresh
        setupSwipeRefresh();

        // Set up search functionality
        setupSearch();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // SecretActivity doesn't use QR scanning functionality (similar to MyKeysActivity)
    }

    // ================================
    // DATA LOADING METHODS
    // ================================

    /**
     * Loads the initial page of Secret objects
     */
    private void loadInitialData() {
        executeInBackground(getString(R.string.loading_secrets), () -> {
            secretManager.refreshSecretsFromAPI(this);
            return secretManager.getPaginatedSecrets(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Secret> firstPageList = secretManager.getPaginatedSecrets(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<Secret> newList) {
        if (newList == null || newList.isEmpty()) {
            showInfoMessage(getString(R.string.no_secrets_found));
            updateUI();
            return;
        }

        secretList.clear();
        secretList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadSecretCardList();
    }

    private void updatePaginationState(List<Secret> newList) {
        if (!secretList.isEmpty()) {
            latestId = secretList.get(0).getId();
            earliestId = secretList.get(secretList.size() - 1).getId();
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
        boolean hasSecrets = !(secretList ==null || secretList.isEmpty());

        deleteButton.setEnabled(hasSecrets);
        deleteButton.setAlpha(hasSecrets ? 1.0f : 0.5f);
    }

    private void loadSecretCardList() {
        // Initialize the LinearLayout container for secret cards
        LinearLayout secretListContainer = findViewById(R.id.fragment_container);

        // Create SecretCardContainer with selection enabled (checkbox mode) and delete button hidden
        secretCardContainer = new SecretCardContainer(this, secretListContainer, ChooseMode.CHOOSE_MULTI); // CHOOSE_MULTI = multiple selection
        secretCardContainer.setScrollView(secretScrollView);

        // Set up selection change listener
        secretCardContainer.setOnSecretListChangedListener(updatedSecretList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Set up off-chain icon click listener
        secretCardContainer.setOnOffChainIconClickListener(this::sendSecretOnChain);

        // Set up update secret launcher
        secretCardContainer.setUpdateSecretLauncher(updateSecretLauncher);

        // Add all secret cards to the manager
        for (Secret secret : secretList) {
            secretCardContainer.addSecretCard(secret);
        }

        secretCardContainer.clearAllBoundaryIndicators();

        // Set up scroll listener for pagination
        setupScrollListener();

        // Update UI after cards are loaded
        updateUI();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && secretCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    secretCardContainer.selectAll(isChecked);
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
                // Reset other sorts to NONE if save time sort was not active
                if (currentSortType != SortType.SAVE_TIME) {
                    currentSortType = SortType.SAVE_TIME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortSecretList();
            });
        }
    }
    
    /**
     * Sets up the type sort button listener
     */
    private void setupTypeSortButton() {
        if (typeSortButton != null) {
            typeSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if type sort was not active
                if (currentSortType != SortType.TYPE) {
                    currentSortType = SortType.TYPE;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortSecretList();
            });
        }
    }

    /**
     * Sets up the title sort button listener
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
                sortSecretList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current secret selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && secretCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);
            
            if (secretCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (secretCardContainer.areNoneSelected()) {
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
     * Sets up the search functionality
     */
    private void setupSearch() {
        if (searchEditText != null && searchIcon != null && clearSearchIcon != null) {
            // Set up search icon click listener
            searchIcon.setOnClickListener(v -> performSearch());

            // Set up clear icon click listener
            clearSearchIcon.setOnClickListener(v -> {
                searchEditText.setText("");
                clearSearchIcon.setVisibility(View.GONE);
            });

            // Set up text change listener to show/hide clear icon
            searchEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() > 0) {
                        clearSearchIcon.setVisibility(View.VISIBLE);
                    } else {
                        clearSearchIcon.setVisibility(View.GONE);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            // Set up search EditText listeners
            searchEditText.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    performSearch();
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Performs the search operation
     */
    private void performSearch() {
        if (secretManager == null) {
            return;
        }

        String query = searchEditText.getText().toString().trim();

        // If search is empty, refresh the list to show all items
        if (query.isEmpty()) {
            isSearchMode = false;
            currentSearchQuery = "";
            refreshList();
            return;
        }

        // Perform search
        isSearchMode = true;
        currentSearchQuery = query;

        // Hide keyboard
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchEditText != null) {
            imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
        }

        // Perform search in background thread
        new Thread(() -> {
            try {
                List<Secret> searchResults = secretManager.searchSecrets(query);
                if (searchResults == null) {
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    return;
                }
                runOnUiThread(() -> {
                    // Clear existing secret card manager
                    if (secretCardContainer != null) {
                        secretCardContainer.clearAll();
                    }

                    // Clear existing list
                    secretList.clear();

                    // Add search results
                    secretList.addAll(searchResults);

                    // Reload the card list with search results
                    loadSecretCardList();

                    // Show search results count
                    ToastUtils.makeText(this, getString(R.string.search_results) + ": " + searchResults.size());
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error performing search: %s", e.getMessage());
                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.search_failed)));
            }
        }).start();
    }

    /**
     * Sets up swipe refresh functionality for loading newer secrets
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer secrets");
                loadNewerSecrets();
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
     * Sets up the scroll listener to detect when the user reaches the top for loading newer items
     */
    private void setupScrollListener() {
        if (secretScrollView != null) {
            // Add scroll listener
            secretScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(secretScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                // Only trigger if user scrolled a significant distance
                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                // Check if we've reached the top and user is scrolling down to load newer secrets from container (only if not in search mode)
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerSecretToContainer();
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
     * Loads newer secrets from local database first (for swipe refresh)
     */
    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer secrets from local database");

        if (secretList.isEmpty() || secretList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = secretList.get(0).getId();
            List<Secret> newerItems = secretManager.getPaginatedSecrets(pageSize, newestId, false);

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

    private void handleNewerItemsFromDatabase(List<Secret> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        Collections.reverse(newerItems);

        if (secretCardContainer != null) {
            secretCardContainer.addSecretCardsToBeginningWithBottomRemoval(
                newerItems,
                itemsToRemoveFromEnd,
                FreerApplication.MAX_CONTAINER_SIZE
            );

            if (secretCardContainer.getSecretList() != null) {
                secretList.clear();
                secretList.addAll(secretCardContainer.getSecretList());
            }
        } else {
            secretList.addAll(0, newerItems);
        }

        if (!secretList.isEmpty()) {
            latestId = secretList.get(0).getId();
            earliestId = secretList.get(secretList.size() - 1).getId();
        }

        if (itemsToRemoveFromEnd > 0) {
            hasMoreEarlierData = true;
        }

        hasMoreNewerData = newerItems.size() >= pageSize;

        updateStatistics();
        updateButtonStates();
        updateSelectAllCheckBoxState();
        showSuccessMessage("Loaded " + newerItems.size() + " newer secrets from database");
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = secretList.size();
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
        currentSortType = SortType.SAVE_TIME;
        setDefaultSortColors();
    }

    /**
     * Loads newer secrets from API (get all changes including inactive secrets for cleanup)
     */
    private void loadNewerFromAPI() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;
        executeInBackground(null, () -> {
            int added = secretManager.loadNewerSecretsFromAPI(this);
            secretManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_secrets_db));
            } else if (added > 0) {
                secretManager.updateOnChainTotal(this);
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, SECRET));
            } else {
                showInfoMessage(getString(R.string.no_new_secrets_found));
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
     * Loads more secrets from local database first, then from API if needed
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more secrets from local database");

        if(!hasMoreEarlierData){
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = secretList.size();
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get next page using the last item's ID for pagination
            List<Secret> moreItems = secretManager.getPaginatedSecrets(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (secretCardContainer != null) {
                    secretCardContainer.addSecretCardsToEndWithTopRemoval(
                        moreItems,
                        itemsToRemoveFromBeginning,
                        FreerApplication.MAX_CONTAINER_SIZE
                    );

                    if (secretCardContainer.getSecretList() != null) {
                        secretList.clear();
                        secretList.addAll(secretCardContainer.getSecretList());
                    }
                } else {
                    secretList.addAll(moreItems);
                }

                if (!secretList.isEmpty()) {
                    latestId = secretList.get(0).getId();
                    earliestId = secretList.get(secretList.size() - 1).getId();
                }

                if (itemsToRemoveFromBeginning > 0) {
                    hasMoreNewerData = true;
                }

                hasMoreEarlierData = moreItems.size() >= pageSize;

                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();

                TimberLogger.i(TAG, "Loaded %d secret items, window size: %d/%d",
                    moreItems.size(), secretList.size(), maxWindowSize);
                ToastUtils.makeText(this, "Loaded " + moreItems.size() + " secrets");
            } else {
                // No more items in local database - try to fetch from API
                ToastUtils.makeText(this, getString(R.string.loading_more_from_apip));
                loadEarlierSecrets();
            }

        } catch (Exception e) {
            // Fallback to API call
            loadEarlierSecrets();
        }
    }

    /**
     * Loads newer secrets into container when scrolling up
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadNewerSecretToContainer() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if(!hasMoreNewerData){
            ToastUtils.makeText(this, getString(R.string.no_more_cash));
            return;
        }

        TimberLogger.i(TAG, "Loading newer secrets to container from local database");

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = secretList.size();
            int pageSize = 50; // Load 50 more items
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get newer items from database using pagination from the latest item
            String latestIdValue = latestId;
            if (latestIdValue == null) {
                TimberLogger.w(TAG, "Latest ID is null, cannot load newer secrets");
                return;
            }

            // Get newer items (items with IDs higher than the current latest item)
            List<Secret> newerItems = secretManager.getPaginatedSecrets(pageSize, latestIdValue, true); // true = get items after this ID

            if (newerItems != null && !newerItems.isEmpty()) {
                int newTotalSize = currentSize + newerItems.size();
                int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                Collections.reverse(newerItems);

                if (secretCardContainer != null) {
                    secretCardContainer.addSecretCardsToBeginningWithBottomRemoval(
                        newerItems,
                        itemsToRemoveFromEnd,
                        FreerApplication.MAX_CONTAINER_SIZE
                    );

                    if (secretCardContainer.getSecretList() != null) {
                        secretList.clear();
                        secretList.addAll(secretCardContainer.getSecretList());
                    }
                } else {
                    secretList.addAll(0, newerItems);
                }

                if (!secretList.isEmpty()) {
                    latestId = secretList.get(0).getId();
                    earliestId = secretList.get(secretList.size() - 1).getId();
                }

                if (itemsToRemoveFromEnd > 0) {
                    hasMoreEarlierData = true;
                }

                hasMoreNewerData = newerItems.size() >= pageSize;

                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();

                TimberLogger.i(TAG, "Loaded %d newer secret items to container, window size: %d/%d",
                    newerItems.size(), secretList.size(), maxWindowSize);
                ToastUtils.makeText(this, "Loaded " + newerItems.size() + " newer secrets");
            } else {
                // No more newer items available
                hasMoreNewerData = false;
                TimberLogger.i(TAG, "No more newer secret items available");
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer secrets to container: %s", e.getMessage());
        }
    }

    /**
     * Loads newer secrets when user swipes down - tries database first, then API
     */
    private void loadNewerSecrets() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // If in search mode, just refresh the search results instead of loading from API
        if (isSearchMode) {
            performSearch();
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        loadMoreNewerFromDatabase();
    }
    
    /**
     * Loads earlier (older) secrets when user scrolls to bottom
     */
    private void loadEarlierSecrets() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if (!hasMoreEarlierData) {
            showInfoMessage(getString(R.string.no_more_secrets));
            return;
        }

        isLoadingEarlier = true;
        executeInBackground(getString(R.string.loading_earlier_secrets), () -> {
            int added = secretManager.loadEarlierSecretsFromAPI(this);
            secretManager.commit();
            return added;
        }, this::handleEarlierSecretsResult);
    }

    private void handleEarlierSecretsResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, SECRET));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                showInfoMessage(getString(R.string.no_more_earlier_secrets_found));
                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();
            } else {
                showErrorMessage(getString(R.string.failed_to_update_secrets_db));
                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();
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
        // Set click listener for More button to load earlier data
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                hideKeyboard();
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    // Disable button and change appearance while loading
                    loadMoreButton.setEnabled(false);
                    loadMoreButton.setAlpha(0.5f);

                    // Load more data
                    loadMoreEarlierFromDatabase();

                    // Re-enable button after loading completes (handled in loadMoreEarlierFromDatabase)
                    // The button will be re-enabled automatically when updateStatistics is called
                    new android.os.Handler().postDelayed(() -> {
                        if (loadMoreButton != null) {
                            loadMoreButton.setEnabled(true);
                            loadMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }

        deleteButton.setOnClickListener(v -> {
            hideKeyboard();
            if (secretCardContainer == null) return;
            List<Secret> chosenObjects = secretCardContainer.getSelectedSecrets();
            if (chosenObjects.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }

            // Pass all selected secrets to DeleteSecretActivity for user confirmation
            String secretListJson = JsonUtils.toJson(chosenObjects);
            Intent intent = new Intent(SecretActivity.this, DeleteSecretActivity.class);
            intent.putExtra("secretList", secretListJson);
            deleteSecretLauncher.launch(intent);
        });

        createNewButton.setOnClickListener(v -> {
            hideKeyboard();
            getIntent().putExtra("fromCreateSecret", true);
            Intent intent = new Intent(SecretActivity.this, CreateSecretActivity.class);
            startActivity(intent);
        });

        if (moreButton != null) {
            moreButton.setOnClickListener(v -> {
                hideKeyboard();
                showMoreMenu(v);
            });
        }
    }

    // ================================
    // UTILITY METHODS
    // ================================

    private void refreshList() {
        // Clear search mode when refreshing
        isSearchMode = false;
        currentSearchQuery = "";
        if (searchEditText != null) {
            searchEditText.setText("");
        }

        // Clear existing secret card manager
        if (secretCardContainer != null) {
            secretCardContainer.clearAll();
        }

        // Clear existing list
        secretList.clear();

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
//        sortSecretList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // Only refresh if we're returning from creating a new secret
        if (getIntent().getBooleanExtra("fromCreateSecret", false)) {
            refreshList();
            // Clear the flag
            getIntent().removeExtra("fromCreateSecret");
        }
        
        // Set up scroll listener only once
        if (!scrollListenerSetup) {
            // Wait for secret scroll view to be ready before setting up scroll listener
            if (secretScrollView != null) {
                setupScrollListener();
                scrollListenerSetup = true;
            } else {
                // Try again after a short delay to ensure scroll view is ready
                new android.os.Handler().postDelayed(() -> {
                    if (secretScrollView != null && !scrollListenerSetup) {
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

        if (typeSortLabel != null) {
            typeSortLabel.setTextColor(hintColor);
        }
        if (typeSortIcon != null) {
            typeSortIcon.setColorFilter(hintColor);
        }

        if (titleSortLabel != null) {
            titleSortLabel.setTextColor(hintColor);
        }
        if (titleSortIcon != null) {
            titleSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons and colors to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);

        if (currentSortType == SortType.SAVE_TIME) {
            // Reset type and title sort icons and labels
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
                typeSortIcon.setColorFilter(hintColor);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
            if (titleSortIcon != null) {
                titleSortIcon.setImageResource(R.drawable.ic_sort_none);
                titleSortIcon.setColorFilter(hintColor);
            }
            if (titleSortLabel != null) {
                titleSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.TYPE) {
            // Reset save time and title sort icons and labels
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
            // Reset save time and type sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
                typeSortIcon.setColorFilter(hintColor);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
        }
        // Note: UPDATE_HEIGHT doesn't have a dedicated UI button, it's the default sort
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

        if (currentSortType == SortType.SAVE_TIME && sortIcon != null && sortLabel != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TYPE && typeSortIcon != null && typeSortLabel != null) {
            typeSortIcon.setImageResource(iconResource);
            typeSortIcon.setColorFilter(color);
            typeSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TITLE && titleSortIcon != null && titleSortLabel != null) {
            titleSortIcon.setImageResource(iconResource);
            titleSortIcon.setColorFilter(color);
            titleSortLabel.setTextColor(color);
        }
    }
    
    /**
     * Sorts the secret list according to current sort state and type
     */
    private void sortSecretList() {
        if (secretCardContainer != null) {
            if (currentSortType == SortType.SAVE_TIME) {
                secretCardContainer.sortBySaveTime(currentSortState == SortState.ASCENDING,
                                            currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TYPE) {
                secretCardContainer.sortByType(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TITLE) {
                secretCardContainer.sortByTitle(currentSortState == SortState.ASCENDING,
                                          currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.UPDATE_HEIGHT) {
                secretCardContainer.sortByUpdateHeight(currentSortState == SortState.ASCENDING,
                                                 currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by updateHeight)
            if (currentSortState == SortState.NONE) {
                secretCardContainer.sortByUpdateHeight(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size, database size, and total on-chain
     */
    private void updateStatistics() {
        if (secretStatisticsTextView == null || secretManager == null) {
            return;
        }

        // Get container size
        int containerSize = secretList != null ? secretList.size() : 0;

        // Get database size
        long dbSize = secretManager.getSecretDBSize();

        // Get total on-chain
        Long totalOnChain = secretManager.getTotalOnChain();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        statsText.append(getString(R.string.local_in_statistics)).append(dbSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        secretStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.secret_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        // Update More button visibility based on hasMoreEarlierData
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
     * Shows the More popup menu
     */
    private void showMoreMenu(View anchorView) {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_secret_more, (ViewGroup) anchorView.getParent(), false);
        PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        // Set up menu item click listeners
        TextView deletedItem = popupView.findViewById(R.id.deleted_item);
        TextView importItem = popupView.findViewById(R.id.import_item);
        TextView exportItem = popupView.findViewById(R.id.export_item);
        TextView backupOffChainItem = popupView.findViewById(R.id.backup_off_chain_item);

        deletedItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(SecretActivity.this, SecretDeletedActivity.class);
            startActivity(intent);
        });

        importItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(SecretActivity.this, ImportSecretActivity.class);
            importSecretLauncher.launch(intent);
        });

        exportItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (secretCardContainer == null) return;
            List<Secret> chosenObjects = secretCardContainer.getSelectedSecrets();
            if (chosenObjects.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }
            String secretListJson = JsonUtils.toJson(chosenObjects);
            Intent intent = new Intent(SecretActivity.this, ExportSecretActivity.class);
            intent.putExtra("secretList", secretListJson);
            startActivity(intent);
        });

        backupOffChainItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(SecretActivity.this, BackupSecretsActivity.class);
            startActivity(intent);
        });

        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        // Show the popup above the anchor view
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    /**
     * Sends an off-chain secret to the blockchain with 'add' operation
     * This method carves the secret on-chain similar to CreateSecretActivity's carve button
     */
    private void sendSecretOnChain(Secret secret) {
        if (secret == null) {
            ToastUtils.makeText(this, "Invalid secret");
            return;
        }

        // Check if secret is already on-chain
        Boolean onChain = secret.getOnChain();
        if (onChain != null && onChain) {
            ToastUtils.makeText(this, "Secret is already on-chain");
            return;
        }

        // Only allow carving if secret is explicitly marked as off-chain (false)
        if (onChain == null || onChain) {
            ToastUtils.makeText(this, "This secret is not an off-chain secret");
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, "No live key available");
            return;
        }

        String pubkey = liveKeyInfo.getPubkey();
        if (pubkey == null) {
            ToastUtils.makeText(this, "Invalid public key");
            return;
        }

        // Decrypt the content to prepare for on-chain carving
        String content = null;
        if (secret.getContentCipher() != null && !secret.getContentCipher().isEmpty()) {
            // Get private key for decryption
            byte[] prikeyForDecrypt = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
            if (prikeyForDecrypt != null) {
                try {
                    content = secret.decryptContent(prikeyForDecrypt);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error decrypting content for on-chain submission: %s", e.getMessage());
                } finally {
                    com.fc.fc_ajdk.utils.BytesUtils.clearByteArray(prikeyForDecrypt);
                }
            }
        }

        if (content == null || content.isEmpty()) {
            ToastUtils.makeText(this, "No content available to send on-chain");
            return;
        }

        // Create a copy of the secret for on-chain submission (same as CreateSecretActivity)
        Secret secretForChain = new Secret();
        if (secret.getTitle() != null && !secret.getTitle().isEmpty()) {
            secretForChain.setTitle(secret.getTitle());
        }
        if (secret.getMemo() != null && !secret.getMemo().isEmpty()) {
            secretForChain.setMemo(secret.getMemo());
        }
        if (secret.getType() != null && !secret.getType().isEmpty()) {
            secretForChain.setType(secret.getType());
        }
        secretForChain.setContent(content);

        // Create FEIP for on-chain carving
        String feipJson = makeAddSecretFeip(secretForChain, pubkey);

        // Show waiting dialog before fetching private key
        showWaitingDialog(getString(R.string.carving_secret_on_chain));

        // Get private key for transaction signing
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            // User cancelled or failed to get private key - dismiss dialog
            dismissWaitingDialog();
            ToastUtils.makeText(this, "Failed to get private key for transaction signing");
            return;
        }

        // Private key obtained, proceed with transaction
        new Thread(() -> {
            try {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();

                            // Remove the original off-chain secret from database
                            SecretManager.getInstance().removeSecretDetail(secret);

                            // Update the secret with transaction details (same as CreateSecretActivity)
                            secret.setId(txId);
                            secret.setOnChain(null); // null indicates pending/unknown status
                            secret.setLastHeight(Constants.MaX_HEIGHT);

                            // Save the updated secret to database
                            SecretManager.getInstance().addSecretDetail(secret);

                            // Commit changes to database
                            SecretManager.getInstance().commit();

                            // Refresh the UI to show updated status
                            refreshList();

                            ToastUtils.makeText(SecretActivity.this, "Secret carved on-chain successfully! TxID: " + txId);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(SecretActivity.this, "Failed to carve secret on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(SecretActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(SecretActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(SecretActivity.this, signedTxHex);
                        });
                    }
                });
            } catch (Exception e) {
                // Handle any unexpected errors during transaction creation
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(SecretActivity.this, "Error creating transaction: " + e.getMessage());
                });
            } finally {
                // Clear the private key from memory
                dismissWaitingDialog();
                com.fc.fc_ajdk.utils.BytesUtils.clearByteArray(prikey);
            }
        }).start();
    }

    /**
     * Creates a FEIP for adding a secret on-chain (based on CreateSecretActivity logic)
     */
    private static String makeAddSecretFeip(Secret secret, String pubkey) {
        String secretDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(secret.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(SECRET);
        SecretOpData secretOpData = SecretOpData.makeAdd(null, secretDetailCipher);
        feip.setData(secretOpData);
        return feip.toJson();
    }

} 