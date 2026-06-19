package com.fc.freer.contact;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ContactCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;
import com.fc.freer.R;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.List;

public class ChooseContactActivity extends BaseCryptoActivity {
    // Constants
    private static final String TAG = "ChooseContact";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    // Selection mode constants
    public static final String EXTRA_CHOOSE_MODE = "choose_mode";
    public static final String EXTRA_SELECTED_CONTACTS = "selected_contacts";
    public static final String EXTRA_SELECTED_CONTACT = "selected_contact";

    // Core components
    private ContactCardContainer contactCardContainer;
    protected ContactManager contactManager;
    private WaitingDialog waitingDialog;

    // Selection mode
    private ChooseMode chooseMode = ChooseMode.CHOOSE_MULTI;

    // UI Elements - Buttons
    private ImageButton confirmButton;
    private ImageButton createNewButton;
    private CheckBox selectAllCheckBox;

    // UI Elements - Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView contactScrollView;
    private TextView contactStatisticsTextView;

    // UI Elements - Search
    private EditText searchEditText;
    private ImageView searchIcon;

    // UI Elements - Sort
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private LinearLayout fidSortButton;
    private ImageView fidSortIcon;
    private LinearLayout nameSortButton;
    private ImageView nameSortIcon;

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

    // Activity result launcher
    private ActivityResultLauncher<Intent> createContactLauncher;

    // ================================
    // LIFECYCLE METHODS
    // ================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launcher
        initializeActivityResultLauncher();

        // Get selection mode from intent
        String chooseModeStr = getIntent().getStringExtra(EXTRA_CHOOSE_MODE);
        if (chooseModeStr != null) {
            try {
                chooseMode = ChooseMode.valueOf(chooseModeStr);
            } catch (IllegalArgumentException e) {
                TimberLogger.e(TAG, "Invalid ChooseMode: %s, using default CHOOSE_MULTI", chooseModeStr);
                chooseMode = ChooseMode.CHOOSE_MULTI;
            }
        }

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

        // Load initial page of Contact objects
        loadFirstPage();
    }

    private void initializeActivityResultLauncher() {
        // Initialize create contact launcher
        createContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );
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

    @Override
    protected int getLayoutId() {
        return R.layout.activity_choose_contact;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.choose_contacts);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        confirmButton = findViewById(R.id.confirm_button);
        createNewButton = findViewById(R.id.create_button);

        // Initialize select all checkbox (only for checkbox mode)
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        contactScrollView = findViewById(R.id.contact_scroll_view);

        // Initialize search elements
        searchEditText = findViewById(R.id.search_edit_text);
        searchIcon = findViewById(R.id.search_icon);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        fidSortButton = findViewById(R.id.fid_sort_button);
        fidSortIcon = findViewById(R.id.fid_sort_icon);
        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortIcon = findViewById(R.id.name_sort_icon);

        // Initialize statistics TextView
        contactStatisticsTextView = findViewById(R.id.contact_statistics);

        // Configure UI based on selection mode
        configureUIForSelectionMode();

        // Set up listeners
        setupSelectAllCheckBox();
        setupSortButton();
        setupFidSortButton();
        setupNameSortButton();
        setupSwipeRefresh();
        setupSearch();
    }

    /**
     * Configures the UI based on the selection mode
     */
    private void configureUIForSelectionMode() {
        // Show select all checkbox and label only for multi-select mode
        if (selectAllCheckBox != null) {
            selectAllCheckBox.setVisibility(chooseMode == ChooseMode.CHOOSE_MULTI ? View.VISIBLE : View.GONE);
        }

        TextView selectAllLabel = findViewById(R.id.selectAllLabel);
        if (selectAllLabel != null) {
            selectAllLabel.setVisibility(chooseMode == ChooseMode.CHOOSE_MULTI ? View.VISIBLE : View.GONE);
        }

        if (confirmButton != null) {
            // Show OK button for all modes except direct return mode
            confirmButton.setVisibility(chooseMode == ChooseMode.CHOOSE_ONE_RETURN ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // No QR scan handling needed for contact selection
    }

    // ================================
    // DATA LOADING METHODS
    // ================================

    /**
     * Loads the initial page of Contact objects
     */
    private void loadInitialData() {
        executeInBackground("Loading contacts...", () -> {
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
            showInfoMessage(getString(R.string.no_contacts_found));
            updateUI();
            return;
        }

        contactList.clear();
        contactList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadContactCardList();
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
        boolean hasContacts = !(contactList == null || contactList.isEmpty());

        if (confirmButton != null && confirmButton.getVisibility() == View.VISIBLE) {
            // Enable OK button based on selection mode
            boolean enableOk = false;
            if (contactCardContainer != null) {
                if (chooseMode == ChooseMode.CHOOSE_ONE || chooseMode == ChooseMode.CHOOSE_MULTI) {
                    enableOk = !contactCardContainer.getSelectedContacts().isEmpty();
                }
            }
            confirmButton.setEnabled(enableOk);
            confirmButton.setAlpha(enableOk ? 1.0f : 0.5f);
        }
    }

    private void loadContactCardList() {
        // Initialize the LinearLayout container for contact cards
        LinearLayout contactListContainer = findViewById(R.id.fragment_container);

        // Create ContactCardContainer with appropriate selection mode
        contactCardContainer = new ContactCardContainer(this, contactListContainer, chooseMode);

        // Set up selection change listener
        contactCardContainer.setOnContactListChangedListener(updatedContactList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Set up contact click listener for direct return mode
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN) {
            contactCardContainer.setOnContactClickListener(this::onContactDirectClick);
        }

        // Add all contact cards to the manager
        for (Contact contact : contactList) {
            contactCardContainer.addContactCard(contact);
        }

        // Update UI after cards are loaded
        updateUI();
    }

    /**
     * Handles direct click on contact (CHOOSE_ONE_RETURN mode)
     */
    private void onContactDirectClick(Contact contact) {
        returnSelectedContact(contact);
    }

    /**
     * Returns the selected contact and finishes the activity
     */
    private void returnSelectedContact(Contact contact) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_SELECTED_CONTACT, contact.toJson());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    /**
     * Returns the selected contacts and finishes the activity
     */
    private void returnSelectedContacts(List<Contact> contacts) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_SELECTED_CONTACTS, JsonUtils.toJson(contacts));
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && contactCardContainer != null && chooseMode == ChooseMode.CHOOSE_MULTI) {
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
        if (selectAllCheckBox != null && contactCardContainer != null && chooseMode == ChooseMode.CHOOSE_MULTI) {
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
        if (searchEditText != null && searchIcon != null) {
            // Set up search icon click listener
            searchIcon.setOnClickListener(v -> performSearch());

            // Set up search EditText listeners
            searchEditText.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    performSearch();
                    return true;
                }
                return false;
            });

            // Optional: Add real-time search as user types (with debouncing)
            searchEditText.addTextChangedListener(new TextWatcher() {
                private final android.os.Handler handler = new android.os.Handler();
                private Runnable searchRunnable;

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (searchRunnable != null) {
                        handler.removeCallbacks(searchRunnable);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                    // Debounce search for 300ms to avoid too many searches while typing
                    searchRunnable = () -> {
                        String query = s.toString().trim();
                        if (query.equals(currentSearchQuery)) {
                            return; // No change, skip search
                        }
                        currentSearchQuery = query;
                        performSearch();
                    };
                    handler.postDelayed(searchRunnable, 300);
                }
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
                boolean atBottom = isAtBottom(contactScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                // Only trigger if user scrolled a significant distance
                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                // Check if we've reached the bottom to load more contacts (only if not in search mode)
                if (atBottom && scrollingDown && !isSearchMode) {
                    if (!isLoadingNewer && !isLoadingEarlier) {
                        // Add delay to prevent accidental triggers
                        if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                            lastScrollTime = currentTime;

                            // Try to load more from local database
                            loadMoreEarlierFromDatabase();
                        }
                    }
                }
            });
        }
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
     * Loads more contacts from local database first, then from API if needed
     */
    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more contacts from local database");

        try {
            // Get next page using the last item's ID for pagination
            List<Contact> moreItems = contactManager.getPaginatedContacts(pageSize, earliestId, true, this);

            if (moreItems != null && !moreItems.isEmpty()) {
                // Add new items to the end of the list
                contactList.addAll(moreItems);
                for (Contact newContact : moreItems) {
                    contactCardContainer.addContactCard(newContact);
                }

                // Update earliestId for next pagination
                earliestId = contactList.get(contactList.size() - 1).getId();

                TimberLogger.i(TAG, "Loaded %d contact items", moreItems.size());
                ToastUtils.makeText(this, "Loaded " + moreItems.size() + " contacts");
            } else {
                // No more items in local database - try to fetch from API
                ToastUtils.makeText(this, getString(R.string.loading_more_from_apip));
                loadEarlierContacts();
            }

        } catch (Exception e) {
            // Fallback to API call
            loadEarlierContacts();
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
        contactList.addAll(0, newerItems);
        contactCardContainer.addContactCardsToBeginning(newerItems);

        latestId = contactList.get(0).getId();
        hasMoreNewerData = newerItems.size() >= pageSize;

        updateUI();
        showSuccessMessage("Loaded " + newerItems.size() + " newer contacts from database");
        stopSwipeRefresh();
    }

    private void stopSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    /**
     * Loads newer contacts from API
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
                showInfoMessage("No new contacts found");
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
     * Loads earlier (older) contacts when user scrolls to bottom
     */
    private void loadEarlierContacts() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if (!hasMoreEarlierData) {
            showInfoMessage(getString(R.string.no_more_contacts));
            return;
        }

        isLoadingEarlier = true;
        executeInBackground("Loading earlier contacts...", () -> {
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
                showInfoMessage("No more earlier contacts found");
            } else {
                showErrorMessage(getString(R.string.failed_to_update_contacts_db));
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
        // Set click listener for OK button
        if (confirmButton != null) {
            confirmButton.setOnClickListener(v -> {
                if (contactCardContainer == null) return;

                List<Contact> selectedContacts = contactCardContainer.getSelectedContacts();
                if (selectedContacts.isEmpty()) {
                    ToastUtils.makeText(this, R.string.no_items_selected);
                    return;
                }

                if (chooseMode == ChooseMode.CHOOSE_ONE) {
                    // Return single selected contact
                    returnSelectedContact(selectedContacts.get(0));
                } else if (chooseMode == ChooseMode.CHOOSE_MULTI) {
                    // Return all selected contacts
                    returnSelectedContacts(selectedContacts);
                }
            });
        }

        // Set click listener for Create button
        if (createNewButton != null) {
            createNewButton.setOnClickListener(v -> {
                Intent intent = new Intent(ChooseContactActivity.this, CreateContactActivity.class);
                createContactLauncher.launch(intent);
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
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.UPDATE_HEIGHT) {
            // Reset FID and name sort icons
            if (fidSortIcon != null) {
                fidSortIcon.setImageResource(R.drawable.ic_sort_none);
            }
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
            }
        } else if (currentSortType == SortType.FID) {
            // Reset update height and name sort icons
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
            }
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
            }
        } else if (currentSortType == SortType.NAME) {
            // Reset update height and FID sort icons
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
            }
            if (fidSortIcon != null) {
                fidSortIcon.setImageResource(R.drawable.ic_sort_none);
            }
        }
    }

    /**
     * Updates the sort icons based on current sort state and type
     */
    private void updateSortIcons() {
        int iconResource = switch (currentSortState) {
            case ASCENDING -> R.drawable.ic_sort_asc;
            case DESCENDING -> R.drawable.ic_sort_desc;
            default -> R.drawable.ic_sort_none;
        };

        if (currentSortType == SortType.UPDATE_HEIGHT && sortIcon != null) {
            sortIcon.setImageResource(iconResource);
        } else if (currentSortType == SortType.FID && fidSortIcon != null) {
            fidSortIcon.setImageResource(iconResource);
        } else if (currentSortType == SortType.NAME && nameSortIcon != null) {
            nameSortIcon.setImageResource(iconResource);
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
        int containerSize = contactCardContainer != null ? contactCardContainer.getContactList().size() : 0;

        // Get database size
        long dbSize = contactManager.getContactDBSize();

        // Get total on-chain
        Long totalOnChain = contactManager.getTotalOnChain();

        StringBuilder statsText = new StringBuilder();
        statsText.append("Above: ").append(containerSize);
        statsText.append("      Local: ").append(dbSize);

        if (totalOnChain != null) {
            statsText.append("      Total: ").append(totalOnChain);
        }

        contactStatisticsTextView.setText(statsText.toString());
        contactStatisticsTextView.setVisibility(View.VISIBLE);
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
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
}