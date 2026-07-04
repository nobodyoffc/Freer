package com.fc.freer.contact;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
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
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ContactCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.ContactOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.fc_ajdk.core.crypto.Encryptor;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;
import com.fc.freer.R;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ContactActivity extends BaseCryptoActivity {
    // Constants
    private static final String TAG = "Contacts";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    // Core components
    private ContactCardContainer contactCardContainer;
    protected ContactManager contactManager;
    private WaitingDialog waitingDialog;

    // UI Elements - Buttons
    private ImageButton loadMoreButton;
    private ImageButton moreButton;
    private ImageButton toListButton;
    private ImageButton createNewButton;
    private CheckBox selectAllCheckBox;

    // UI Elements - Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView contactScrollView;
    private TextView contactStatisticsTextView;

    // UI Elements - Search
    private EditText searchEditText;
    private ImageView searchIcon;
    private ImageView clearSearchIcon;

    // UI Elements - Sort
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout fidSortButton;
    private ImageView fidSortIcon;
    private TextView fidSortLabel;
    private LinearLayout nameSortButton;
    private ImageView nameSortIcon;
    private TextView nameSortLabel;

    // Enums
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { UPDATE_HEIGHT, FID, NAME }

    // State management
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.UPDATE_HEIGHT;
    private boolean isSearchMode = false;
    private String currentSearchQuery = "";
    private boolean scrollListenerSetup = false;

    // Data and pagination
    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Contact> contactList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    // Activity result launchers
    private ActivityResultLauncher<Intent> importContactLauncher;
    private ActivityResultLauncher<Intent> createContactLauncher;
    private ActivityResultLauncher<Intent> updateContactLauncher;
    private ActivityResultLauncher<Intent> deleteContactLauncher;

    // Add from list dialog state
    private List<Contact> contactListForDialog = new ArrayList<>();
    private int currentFidIndex = 0;
    private AddContactDialog currentAddContactDialog;

    // ================================
    // LIFECYCLE METHODS
    // ================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        // Initialize ContactManager with current live FID
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                contactManager = ContactManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ContactManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ContactManager with liveFid: " + e.getMessage());
        }

        if (contactManager == null) {
            ToastUtils.makeText(this, getString(R.string.contact_not_ready_try_later));
            finish();
            return;
        }

        new Thread(() -> contactManager.checkGuideAndMaster()).start();

        // Load initial page of Contact objects
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
        // Initialize import contact launcher
        importContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize create contact launcher
        createContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize update contact launcher
        updateContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize delete contact launcher
        deleteContactLauncher = registerForActivityResult(
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
        return R.layout.activity_contact;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.my_contacts);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        loadMoreButton = findViewById(R.id.load_more_button);
        moreButton = findViewById(R.id.more_button);
        toListButton = findViewById(R.id.to_list_button); // Reusing the same button ID but changing functionality
        createNewButton = findViewById(R.id.create_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        contactScrollView = findViewById(R.id.contact_scroll_view);

        // Initialize search elements
        searchEditText = findViewById(R.id.search_edit_text);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        fidSortButton = findViewById(R.id.fid_sort_button);
        fidSortIcon = findViewById(R.id.fid_sort_icon);
        fidSortLabel = findViewById(R.id.fid_sort_label);
        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        nameSortLabel = findViewById(R.id.name_sort_label);

        // Set default sort colors
        setDefaultSortColors();

        // Initialize statistics TextView
        contactStatisticsTextView = findViewById(R.id.contact_statistics);

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupSortButton();
        setupFidSortButton();
        setupNameSortButton();

        // Set up swipe refresh
        setupSwipeRefresh();

        // Set up search functionality
        setupSearch();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Forward QR scan results to the current AddContactDialog if it exists
    }

    // ================================
    // DATA LOADING METHODS
    // ================================

    /**
     * Loads the initial page of Contact objects
     */
    private void loadInitialData() {
        executeInBackground(getString(R.string.loading_contacts), () -> {
            contactManager.refreshContactsFromAPI(this);
            return contactManager.getPaginatedContacts(pageSize, null, true, this);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Contact> firstPageList = contactManager.getPaginatedContacts(pageSize, null, true, this);
        showItemList(firstPageList);
    }

    private void showItemList(List<Contact> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_contacts_found));
            updateUI();
            return;
        }

        contactList.clear();
        contactList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadContactCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<Contact> newList) {
        if (!contactList.isEmpty()) {
            latestId = contactList.get(0).getId();
            earliestId = contactList.get(contactList.size() - 1).getId();
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

        boolean hasSelected = contactCardContainer != null && !contactCardContainer.getSelectedContacts().isEmpty();


        toListButton.setEnabled(hasSelected);
        toListButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void loadContactCardList() {
        // Initialize the LinearLayout container for contact cards
        LinearLayout contactListContainer = findViewById(R.id.fragment_container);

        // Create ContactCardContainer with selection enabled (checkbox mode)
        contactCardContainer = new ContactCardContainer(this, contactListContainer, ChooseMode.CHOOSE_MULTI);
        contactCardContainer.setScrollView(contactScrollView);

        // Set up selection change listener
        contactCardContainer.setOnContactListChangedListener(updatedContactList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Set up off-chain icon click listener
        contactCardContainer.setOnOffChainIconClickListener(this::sendContactOnChain);

        // Set up update contact launcher
        contactCardContainer.setUpdateContactLauncher(updateContactLauncher);

        // Add all contact cards to the manager
        for (Contact contact : contactList) {
            contactCardContainer.addContactCard(contact);
        }

        contactCardContainer.clearAllBoundaryIndicators();

        // Update UI after cards are loaded
        updateUI();

        // Set up scroll listener for pagination
        setupScrollListener();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && contactCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    contactCardContainer.selectAll(isChecked);
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
                // Reset other sorts to NONE if update height sort was not active
                if (currentSortType != SortType.UPDATE_HEIGHT) {
                    currentSortType = SortType.UPDATE_HEIGHT;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortContactList();
            });
        }
    }

    /**
     * Sets up the FID sort button listener
     */
    private void setupFidSortButton() {
        if (fidSortButton != null) {
            fidSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if FID sort was not active
                if (currentSortType != SortType.FID) {
                    currentSortType = SortType.FID;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortContactList();
            });
        }
    }

    /**
     * Sets up the name sort button listener
     */
    private void setupNameSortButton() {
        if (nameSortButton != null) {
            nameSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if name sort was not active
                if (currentSortType != SortType.NAME) {
                    currentSortType = SortType.NAME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortContactList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current contact selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && contactCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (contactCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (contactCardContainer.areNoneSelected()) {
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
        if (contactManager == null) {
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
                List<Contact> searchResults = contactManager.searchContacts(query);
                if (searchResults == null) {
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    return;
                }
                runOnUiThread(() -> {
                    // Clear existing contact card manager
                    if (contactCardContainer != null) {
                        contactCardContainer.clearAll();
                    }

                    // Clear existing list
                    contactList.clear();

                    // Add search results
                    contactList.addAll(searchResults);

                    // Reload the card list with search results
                    loadContactCardList();

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
     * Sets up swipe refresh functionality for loading newer contacts
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer contacts");
                loadNewerContacts();
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
        if (contactScrollView != null) {
            // Add scroll listener
            contactScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(contactScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                // Only trigger if user scrolled a significant distance
                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                // Check if we've reached the top and user is scrolling down to load newer contacts from container (only if not in search mode)
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerContactToContainer();
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
     * Loads newer contacts from local database first (for swipe refresh)
     */
    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer contacts from local database");

        if (contactList.isEmpty() || contactList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = contactList.get(0).getId();
            List<Contact> newerItems = contactManager.getPaginatedContacts(pageSize, newestId, false, this);

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

    private void handleNewerItemsFromDatabase(List<Contact> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        java.util.Collections.reverse(newerItems);
        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());

        if (contactCardContainer != null) {
            contactCardContainer.addContactCardsToBeginningWithBottomRemoval(
                newerItems,
                itemsToRemoveFromEnd,
                FreerApplication.MAX_CONTAINER_SIZE
            );

            if (contactCardContainer.getContactList() != null) {
                contactList.clear();
                contactList.addAll(contactCardContainer.getContactList());
            }
        }

        if (!contactList.isEmpty()) {
            latestId = contactList.get(0).getId();
            earliestId = contactList.get(contactList.size() - 1).getId();
        }

        if (itemsToRemoveFromEnd > 0) {
            hasMoreEarlierData = true;
        }

        hasMoreNewerData = newerItems.size() >= pageSize;

        updateStatistics();
        updateButtonStates();
        updateSelectAllCheckBoxState();
        showSuccessMessage("Loaded " + newerItems.size() + " newer contacts from database");
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = contactList.size();
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
        currentSortType = SortType.UPDATE_HEIGHT;
        setDefaultSortColors();
    }

    /**
     * Loads newer contacts from API (get all changes including inactive contacts for cleanup)
     */
    private void loadNewerFromAPI() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;
        executeInBackground(null, () -> {
            int added = contactManager.loadNewerContactsFromAPI(this);
            contactManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_contacts_db));
            } else if (added > 0) {
                contactManager.updateOnChainTotal(this);
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, CONTACT));
            } else {
                showInfoMessage(getString(R.string.no_new_contacts_found));
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
     * Loads more contacts from local database first, then from API if needed
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more contacts from local database");

        // Check if we already know there's no more data
        if (!hasMoreEarlierData) {
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = contactList.size();
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get next page using the last item's ID for pagination
            List<Contact> moreItems = contactManager.getPaginatedContacts(pageSize, earliestId, true, this);

            if (moreItems != null && !moreItems.isEmpty()) {
                // Calculate how many items to remove from the beginning if we exceed window size
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (contactCardContainer != null) {
                    contactCardContainer.addContactCardsToEndWithTopRemoval(
                        moreItems,
                        itemsToRemoveFromBeginning,
                        FreerApplication.MAX_CONTAINER_SIZE
                    );

                    if (contactCardContainer.getContactList() != null) {
                        contactList.clear();
                        contactList.addAll(contactCardContainer.getContactList());
                    }
                }

                if (!contactList.isEmpty()) {
                    latestId = contactList.get(0).getId();
                    earliestId = contactList.get(contactList.size() - 1).getId();
                }

                if (itemsToRemoveFromBeginning > 0) {
                    hasMoreNewerData = true;
                }

                hasMoreEarlierData = moreItems.size() >= pageSize;

                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();

                TimberLogger.i(TAG, "Loaded %d contact items, window size: %d/%d",
                    moreItems.size(), contactList.size(), maxWindowSize);
                ToastUtils.makeText(this, getString(R.string.toast_loaded_contacts, moreItems.size()));
            } else {
                // No more items in local database - try to fetch from API
                loadEarlierContacts();
            }

        } catch (Exception e) {
            // Fallback to API call
            loadEarlierContacts();
        }
    }

    /**
     * Loads newer contacts into container when scrolling up
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadNewerContactToContainer() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if(!hasMoreNewerData){
            ToastUtils.makeText(this, getString(R.string.no_more_cash));
            return;
        }

        TimberLogger.i(TAG, "Loading newer contacts to container from local database");

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = contactList.size();
            int pageSize = 50; // Load 50 more items
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get newer items from database using pagination from the latest item
            String latestIdValue = latestId;
            if (latestIdValue == null) {
                TimberLogger.w(TAG, "Latest ID is null, cannot load newer contacts");
                return;
            }

            // Get newer items (items with IDs higher than the current latest item)
            List<Contact> newerItems = contactManager.getPaginatedContacts(pageSize, latestIdValue, true, this); // true = get items after this ID

            if (newerItems != null && !newerItems.isEmpty()) {
                // Calculate how many items to remove from the end if we exceed window size
                int newTotalSize = currentSize + newerItems.size();
                int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                if (contactCardContainer != null) {
                    contactCardContainer.addContactCardsToBeginningWithBottomRemoval(
                        newerItems,
                        itemsToRemoveFromEnd,
                        FreerApplication.MAX_CONTAINER_SIZE
                    );

                    if (contactCardContainer.getContactList() != null) {
                        contactList.clear();
                        contactList.addAll(contactCardContainer.getContactList());
                    }
                }

                if (!contactList.isEmpty()) {
                    latestId = contactList.get(0).getId();
                    earliestId = contactList.get(contactList.size() - 1).getId();
                }

                if (itemsToRemoveFromEnd > 0) {
                    hasMoreEarlierData = true;
                }

                // Check if there are more newer items available
                if (newerItems.size() < pageSize) {
                    hasMoreNewerData = false;
                }

                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();

                TimberLogger.i(TAG, "Loaded %d newer contact items to container, window size: %d/%d",
                    newerItems.size(), contactList.size(), maxWindowSize);
                ToastUtils.makeText(this, getString(R.string.toast_loaded_newer_contacts, newerItems.size()));
            } else {
                // No more newer items available
                hasMoreNewerData = false;
                TimberLogger.i(TAG, "No more newer contact items available");
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer contacts to container: %s", e.getMessage());
        }
    }

    /**
     * Loads newer contacts when user swipes down - tries database first, then API
     */
    private void loadNewerContacts() {
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
     * Loads earlier (older) contacts when user scrolls to bottom
     */
    private void loadEarlierContacts() {
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
            int added = contactManager.loadEarlierContactsFromAPI(this);
            contactManager.commit();
            return added;
        }, this::handleEarlierContactsResult);
    }

    private void handleEarlierContactsResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, CONTACT));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                showInfoMessage(getString(R.string.no_more_earlier_contacts_found));
                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();
            } else {
                showErrorMessage(getString(R.string.failed_to_update_contacts_db));
                updateStatistics();
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
        // Set click listener for More button to show more menu
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

        toListButton.setOnClickListener(v -> {
            if (contactCardContainer == null) return;
            List<Contact> chosenObjects = contactCardContainer.getSelectedContacts();
            if (chosenObjects.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }

            // Add selected contact FIDs to global fidList
            int addedCount = 0;
            for (Contact contact : chosenObjects) {
                String fid = contact.getFid();
                if (fid != null && !fid.isEmpty()) {
                    FreerApplication.addFid(fid);
                    addedCount++;
                }
            }

            if (addedCount > 0) {
                ToastUtils.makeText(this, getString(R.string.toast_added_contact_fids, addedCount));
                // Clear selections after adding to list
                contactCardContainer.selectAll(false);
            } else {
                ToastUtils.makeText(this, getString(R.string.toast_no_valid_fids_in_contacts));
            }
        });

        createNewButton.setOnClickListener(v -> {
            Intent intent = new Intent(ContactActivity.this, CreateContactActivity.class);
            createContactLauncher.launch(intent);
        });
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

        // Clear existing contact card manager
        if (contactCardContainer != null) {
            contactCardContainer.clearAll();
        }

        // Clear existing list
        contactList.clear();

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

    @Override
    protected void onResume() {
        super.onResume();

        // Set up scroll listener only once
        if (!scrollListenerSetup) {
            // Wait for contact scroll view to be ready before setting up scroll listener
            if (contactScrollView != null) {
                setupScrollListener();
                scrollListenerSetup = true;
            } else {
                // Try again after a short delay to ensure scroll view is ready
                new android.os.Handler().postDelayed(() -> {
                    if (contactScrollView != null && !scrollListenerSetup) {
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
        if (fidSortLabel != null) {
            fidSortLabel.setTextColor(hintColor);
        }
        if (fidSortIcon != null) {
            fidSortIcon.setColorFilter(hintColor);
        }
        if (nameSortLabel != null) {
            nameSortLabel.setTextColor(hintColor);
        }
        if (nameSortIcon != null) {
            nameSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons and colors to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);

        if (currentSortType == SortType.UPDATE_HEIGHT) {
            // Reset FID and name sort icons and labels
            if (fidSortIcon != null) {
                fidSortIcon.setImageResource(R.drawable.ic_sort_none);
                fidSortIcon.setColorFilter(hintColor);
            }
            if (fidSortLabel != null) {
                fidSortLabel.setTextColor(hintColor);
            }
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.FID) {
            // Reset update height and name sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.NAME) {
            // Reset update height and FID sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (fidSortIcon != null) {
                fidSortIcon.setImageResource(R.drawable.ic_sort_none);
                fidSortIcon.setColorFilter(hintColor);
            }
            if (fidSortLabel != null) {
                fidSortLabel.setTextColor(hintColor);
            }
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

        if (currentSortType == SortType.UPDATE_HEIGHT) {
            if (sortIcon != null) {
                sortIcon.setImageResource(iconResource);
                sortIcon.setColorFilter(color);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(color);
            }
        } else if (currentSortType == SortType.FID) {
            if (fidSortIcon != null) {
                fidSortIcon.setImageResource(iconResource);
                fidSortIcon.setColorFilter(color);
            }
            if (fidSortLabel != null) {
                fidSortLabel.setTextColor(color);
            }
        } else if (currentSortType == SortType.NAME) {
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(iconResource);
                nameSortIcon.setColorFilter(color);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(color);
            }
        }
    }

    /**
     * Sorts the contact list according to current sort state and type
     */
    private void sortContactList() {
        if (contactCardContainer != null) {
            if (currentSortType == SortType.UPDATE_HEIGHT) {
                contactCardContainer.sortByUpdateHeight(currentSortState == SortState.ASCENDING,
                                                currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.FID) {
                contactCardContainer.sortByFid(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.NAME) {
                contactCardContainer.sortByName(currentSortState == SortState.ASCENDING,
                                          currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by updateHeight)
            if (currentSortState == SortState.NONE) {
                contactCardContainer.sortByUpdateHeight(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size, database size, and total on-chain
     */
    private void updateStatistics() {
        if (contactStatisticsTextView == null || contactManager == null) {
            return;
        }

        // Get container size
        int containerSize = contactList != null ? contactList.size() : 0;

        // Get database size
        long dbSize = contactManager.getContactDBSize();

        // Get total on-chain
        Long totalOnChain = contactManager.getTotalOnChain();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        statsText.append(getString(R.string.local_in_statistics)).append(dbSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        contactStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.contact_statistics_container);
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
        cleanupDialogState();
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
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_contact_more, (ViewGroup) anchorView.getParent(), false);
        PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        // Set up menu item click listeners
        TextView addFromListItem = popupView.findViewById(R.id.add_from_list_item);
        TextView deletedItem = popupView.findViewById(R.id.deleted_item);
        TextView deleteItem = popupView.findViewById(R.id.delete_item);

        addFromListItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            showAddFromListDialogs();
        });

        deletedItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(ContactActivity.this, ContactDeletedActivity.class);
            startActivity(intent);
        });


        deleteItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (contactCardContainer == null) return;
            List<Contact> chosenObjects = contactCardContainer.getSelectedContacts();
            if (chosenObjects.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }

            // Launch DeleteContactActivity with all selected contacts
            // DeleteContactActivity will handle off-chain/on-chain separation internally
            String contactListJson = JsonUtils.toJson(chosenObjects);
            Intent intent = new Intent(ContactActivity.this, DeleteContactActivity.class);
            intent.putExtra("contactList", contactListJson);
            deleteContactLauncher.launch(intent);
        });

        popupView.findViewById(R.id.about_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            com.fc.freer.utils.AboutDialog.show(this, R.string.about_contact_title, R.string.about_contact_message);
        });

        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        // Show the popup above the anchor view
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    /**
     * Sends an off-chain contact to the blockchain
     */
    private void sendContactOnChain(Contact contact) {
        if (contact == null) {
            ToastUtils.makeText(this, getString(R.string.toast_invalid_contact));
            return;
        }

        // Check if contact is already on-chain
        if (Boolean.TRUE.equals(contact.getOnChain())) {
            ToastUtils.makeText(this, getString(R.string.toast_contact_already_onchain));
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.toast_no_live_key));
            return;
        }

        String pubkey = liveKeyInfo.getPubkey();
        if (pubkey == null) {
            ToastUtils.makeText(this, getString(R.string.toast_invalid_public_key));
            return;
        }

        // Create a copy of the contact for on-chain submission
        Contact contactForChain = new Contact();
        contactForChain.setFid(contact.getFid());
        contactForChain.setTitles(contact.getTitles());
        contactForChain.setMemo(contact.getMemo());
        contactForChain.setSeeStatement(contact.getSeeStatement());
        contactForChain.setSeeWritings(contact.getSeeWritings());

        String feipJson = makeAddContactFeip(contactForChain, pubkey);

        // Get private key for transaction signing
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {

            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            // Remove the original off-chain contact from database
                            ContactManager.getInstance().removeContact(contact);

                            // Create a new contact with the transaction ID as the contact ID
                            makePendingContact(txId, contact);

                            // Save the new on-chain contact to database
                            ContactManager.getInstance().addContact(contact);

                            // Commit changes to database
                            ContactManager.getInstance().commit();

                            // Refresh the UI to show updated status
                            refreshList();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(ContactActivity.this, getString(R.string.failed_to_send_contact_on_chain) + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(ContactActivity.this, getString(R.string.no_prikey_copy_tx_sign_it_and_broadcast_it));
                            txSender.showUnsignedTxAsQR(ContactActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(ContactActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            ToastUtils.makeText(this, getString(R.string.toast_failed_get_prikey_signing));
        }
    }

    private void makePendingContact(String txId, Contact contact) {
        contact.setId(txId); // Use transaction ID as contact ID
        contact.setOnChain(null); // null indicates pending/unknown status
        contact.setLastHeight(Constants.MaX_HEIGHT);
    }

    /**
     * Shows sequential dialogs for each FID in the global fidList
     */
    private void showAddFromListDialogs() {
        List<String> fidList =  FreerApplication.getFidList();
        if(fidList.isEmpty())return;

        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        Map<String, Freer> result = null;
        if(fapiClient!=null)
            result = fapiClient.freerByIds(fidList);
        CidFidManager cidFidManager = CidFidManager.getInstance();
        for(String fid:fidList){
            Contact contact = new Contact();
            contact.setFid(fid);
            if(result!=null){
                Freer freer = result.get(fid);
                if(freer !=null) {
                    if(freer.getCid()!=null) {
                        cidFidManager.add(fid, freer.getCid());
                        contact.setCid(freer.getCid());
                    }
                    contact.setPubkey(freer.getPubkey());
                }
            }
            contactListForDialog.add(contact);
        }

        if (contactListForDialog.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.toast_no_fids_in_list));
            return;
        }

        currentFidIndex = 0;
        showNextAddContactDialog();
    }

    /**
     * Shows the next dialog in the sequence
     */
    private void showNextAddContactDialog() {
        if (currentFidIndex >= contactListForDialog.size()) {
            // All dialogs completed
            ToastUtils.makeText(this, getString(R.string.toast_completed_processing_fids, contactListForDialog.size()));
            cleanupDialogState();
            refreshList(); // Refresh the contact list to show any new contacts
            return;
        }

        Contact currentFid = contactListForDialog.get(currentFidIndex);

        currentAddContactDialog = new AddContactDialog(this, currentFid, new AddContactDialog.AddContactDialogCallback() {
            @Override
            public void onStop() {
                ToastUtils.makeText(ContactActivity.this, getString(R.string.toast_stopped_at_fid, currentFidIndex + 1, contactListForDialog.size()));
                cleanupDialogState();
            }

            @Override
            public void onNext() {
                ToastUtils.makeText(ContactActivity.this, getString(R.string.toast_skipped_fid, currentFidIndex + 1, contactListForDialog.size()));
                currentFidIndex++;
                showNextAddContactDialog();
            }

            @Override
            public void onCarveSuccess(Contact contact) {
                ToastUtils.makeText(ContactActivity.this, getString(R.string.toast_contact_carved_fid, currentFidIndex + 1, contactListForDialog.size()));
                currentFidIndex++;
                showNextAddContactDialog();
            }

            @Override
            public void onCarveError(String errorMessage) {
                ToastUtils.makeText(ContactActivity.this, getString(R.string.toast_error_carving_contact, errorMessage));
                // Continue to next FID on error
                currentFidIndex++;
                showNextAddContactDialog();
            }
        });

        currentAddContactDialog.show();
    }

    /**
     * Cleans up dialog state
     */
    private void cleanupDialogState() {
        if (currentAddContactDialog != null) {
            currentAddContactDialog.dismiss();
            currentAddContactDialog = null;
        }
        contactListForDialog = null;
        currentFidIndex = 0;
    }

    /**
     * Creates a FEIP for adding a contact on-chain (based on CreateContactActivity logic)
     */
    private static String makeAddContactFeip(Contact contact, String pubkey) {
        String contactDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(contact.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(CONTACT);
        ContactOpData contactOpData = ContactOpData.makeAdd(null, contactDetailCipher);
        feip.setData(contactOpData);
        return feip.toJson();
    }
}