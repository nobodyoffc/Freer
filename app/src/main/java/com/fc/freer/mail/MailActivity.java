package com.fc.freer.mail;

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
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.MailCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.TimberLogger;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;

import static com.fc.fc_ajdk.constants.IndicesNames.MAIL;
import static com.fc.freer.mail.CreateMailActivity.makeSendMailFeip;
import static com.fc.freer.manager.MailManager.MAX_PAYING_NOTICE_FEE;

import com.fc.freer.R;
import com.fc.freer.manager.MailManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.SendTxActivity;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MailActivity extends BaseCryptoActivity {
    // Constants
    private static final String TAG = "Mails";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;
    public static final int CONTENT_BRIEF_LENGTH = 48;

    // Core components
    private MailCardContainer mailCardContainer;
    protected MailManager mailManager;
    private WaitingDialog waitingDialog;

    // UI Elements - Buttons
    private ImageButton loadMoreButton;

    private ImageButton moreButton;
    private ImageButton deleteButton;
    private ImageButton toListButton;
    private ImageButton createNewButton;
    private CheckBox selectAllCheckBox;

    // UI Elements - Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView mailScrollView;
    private TextView mailStatisticsTextView;

    // UI Elements - Search
    private EditText searchEditText;
    private ImageView searchIcon;
    private ImageView clearSearchIcon;

    // UI Elements - Sort
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout senderSortButton;
    private ImageView senderSortIcon;
    private TextView senderSortLabel;
    private LinearLayout recipientSortButton;
    private ImageView recipientSortIcon;
    private TextView recipientSortLabel;

    @NonNull
    public static String makeReplyHead(Mail mail) {
        String contentBrief = mail.getContent() != null && mail.getContent().length() > ReadMailActivity.CONTENT_BRIEF_LENGTH
            ? mail.getContent().substring(0, ReadMailActivity.CONTENT_BRIEF_LENGTH) + "..."
            : mail.getContent();
        String replyContent = "Re: " +
                contentBrief + "\n" +
                "ID:" + mail.getId() +
                "\n----\n";
        return replyContent;
    }

    // Enums
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { UPDATE_HEIGHT, SENDER, RECIPIENT }

    // State management
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.UPDATE_HEIGHT;
    private boolean isSearchMode = false;
    private String currentSearchQuery = "";
    private boolean scrollListenerSetup = false;

    // Data and pagination
    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Mail> mailList = new ArrayList<>();
    private String earliestId = null;
    private String latestId = null;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreData = true;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    // Activity result launchers
    private ActivityResultLauncher<Intent> importMailLauncher;
    private ActivityResultLauncher<Intent> createMailLauncher;
    private ActivityResultLauncher<Intent> updateMailLauncher;
    private ActivityResultLauncher<Intent> deleteMailLauncher;

    // ================================
    // LIFECYCLE METHODS
    // ================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        // Initialize MailManager with current live FID
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                mailManager = MailManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize MailManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing MailManager with liveFid: " + e.getMessage());
        }

        if (mailManager == null) {
            ToastUtils.makeText(this, getString(R.string.mail_not_ready_try_later));
            finish();
            return;
        }

        // Load initial page of Mail objects
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
                    showErrorMessage(getString(R.string.error)+":" + e.getMessage());
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
        // Initialize import mail launcher
        importMailLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize create mail launcher
        createMailLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        // Initialize update mail launcher
        updateMailLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK || result.getResultCode() == ReadMailActivity.RESULT_REPLIED) {
                    // Get the mail ID that was read
                    if (result.getData() != null) {
                        String readMailId = result.getData().getStringExtra(ReadMailActivity.EXTRA_READ_MAIL_ID);
                        if (readMailId != null) {
                            // Mark the mail as read in the database
                            markMailAsRead(readMailId);
                        }
                    }
                    refreshList();
                }
            }
        );

        // Initialize delete mail launcher
        deleteMailLauncher = registerForActivityResult(
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
        return R.layout.activity_mail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.my_mails);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        loadMoreButton = findViewById(R.id.load_more_button);
        moreButton = findViewById(R.id.more_button);
        deleteButton = findViewById(R.id.delete_button);
        toListButton = findViewById(R.id.to_list_button);
        createNewButton = findViewById(R.id.create_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        mailScrollView = findViewById(R.id.mail_scroll_view);

        // Initialize search elements
        searchEditText = findViewById(R.id.search_edit_text);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        senderSortButton = findViewById(R.id.sender_sort_button);
        senderSortIcon = findViewById(R.id.sender_sort_icon);
        senderSortLabel = findViewById(R.id.sender_sort_label);
        recipientSortButton = findViewById(R.id.recipient_sort_button);
        recipientSortIcon = findViewById(R.id.recipient_sort_icon);
        recipientSortLabel = findViewById(R.id.recipient_sort_label);

        // Initialize statistics TextView
        mailStatisticsTextView = findViewById(R.id.mail_statistics);

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupSortButton();
        setupSenderSortButton();
        setupRecipientSortButton();

        // Set up swipe refresh
        setupSwipeRefresh();

        // Set up search functionality
        setupSearch();

        // Set default sort colors
        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Forward QR scan results if needed
    }

    // ================================
    // DATA LOADING METHODS
    // ================================

    /**
     * Loads the initial page of Mail objects
     */
    private void loadInitialData() {
        executeInBackground(getString(R.string.loading_mails), () -> {
            mailManager.refreshMailsFromAPI(this);
            return mailManager.getPaginatedMails(pageSize, null, true);
        }, this::showItemList);
    }

    private void loadFirstPage() {
        List<Mail> firstPageList = mailManager.getPaginatedMails(pageSize, null, true);
        showItemList(firstPageList);
    }

    private void showItemList(List<Mail> newList) {
        if (newList == null || newList.isEmpty()) {
            dismissWaitingDialog();
            showInfoMessage(getString(R.string.no_mails_found));
            updateUI();
            return;
        }

        mailList.clear();
        mailList.addAll(newList);
        updatePaginationState(newList);
        updateUI();
        loadMailCardList();
        dismissWaitingDialog();
    }

    private void updatePaginationState(List<Mail> newList) {
        if (!mailList.isEmpty()) {
            latestId = mailList.get(0).getId();
            earliestId = mailList.get(mailList.size() - 1).getId();
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
        boolean hasSelected = mailCardContainer != null && !mailCardContainer.getSelectedMails().isEmpty();

        // Delete button is enabled when some mails are selected
        deleteButton.setEnabled(hasSelected);
        deleteButton.setAlpha(hasSelected ? 1.0f : 0.5f);

        toListButton.setEnabled(hasSelected);
        toListButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void loadMailCardList() {
        // Initialize the LinearLayout container for mail cards
        LinearLayout mailListContainer = findViewById(R.id.fragment_container);

        // Drop the cards of the previous rendering: a new MailCardContainer starts with an
        // empty mail list, so leftover views would both duplicate the list and shift every
        // card out of sync with mailList (which is what setBoundaryIndicator indexes into).
        if (mailListContainer != null) {
            mailListContainer.removeAllViews();
        }

        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

        // Create MailCardContainer with selection enabled (checkbox mode)
        mailCardContainer = new MailCardContainer(this, mailListContainer, ChooseMode.CHOOSE_MULTI, liveFid);

        // Set up selection change listener
        mailCardContainer.setOnMailListChangedListener(updatedMailList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Set up off-chain icon click listener
        mailCardContainer.setOnOffChainIconClickListener(this::sendMailOnChain);

        // Set up reply click listener
        mailCardContainer.setOnReplyClickListener(this::replyToMail);

        // Set up mail click listener to use ReadMailActivity instead of DetailActivity
        mailCardContainer.setOnMailClickListener(this::openReadMailActivity);

        // Set up update mail launcher
        mailCardContainer.setUpdateMailLauncher(updateMailLauncher);
        mailCardContainer.setScrollView(mailScrollView);

        // Add all mail cards to the manager
        for (Mail mail : mailList) {
            mailCardContainer.addMailCard(mail);
        }

        mailCardContainer.clearAllBoundaryIndicators();

        // Update UI after cards are loaded
        updateUI();

        // Set up scroll listener for pagination
        setupScrollListener();
    }

    /**
     * Sets up the select all checkbox listener
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && mailCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Only respond to user clicks, not programmatic changes
                if (buttonView.isPressed()) {
                    mailCardContainer.selectAll(isChecked);
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
                sortMailList();
            });
        }
    }

    /**
     * Sets up the sender sort button listener
     */
    private void setupSenderSortButton() {
        if (senderSortButton != null) {
            senderSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if sender sort was not active
                if (currentSortType != SortType.SENDER) {
                    currentSortType = SortType.SENDER;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortMailList();
            });
        }
    }

    /**
     * Sets up the recipient sort button listener
     */
    private void setupRecipientSortButton() {
        if (recipientSortButton != null) {
            recipientSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if recipient sort was not active
                if (currentSortType != SortType.RECIPIENT) {
                    currentSortType = SortType.RECIPIENT;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortMailList();
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current mail selection
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && mailCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (mailCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (mailCardContainer.areNoneSelected()) {
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
        if (mailManager == null) {
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
                List<Mail> searchResults = mailManager.searchMails(query);
                if (searchResults == null) {
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    return;
                }
                runOnUiThread(() -> {
                    // Clear existing mail card manager
                    if (mailCardContainer != null) {
                        mailCardContainer.clearAll();
                    }

                    // Clear existing list
                    mailList.clear();

                    // Add search results
                    mailList.addAll(searchResults);

                    // Reload the card list with search results
                    loadMailCardList();

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
     * Sets up swipe refresh functionality for loading newer mails
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer mails");
                loadNewerMails();
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
        if (mailScrollView != null) {
            // Add scroll listener
            mailScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = isAtTop(mailScrollView);
                boolean scrollingDown = scrollY > oldScrollY;

                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                // Only trigger if user scrolled a significant distance
                if (scrollDistance < MIN_SCROLL_DISTANCE) {
                    return;
                }

                // Check if we've reached the top and user is scrolling down to load newer mails from container (only if not in search mode)
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData && !isSearchMode) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerMailToContainer();
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
     * Loads newer mails from local database first (for swipe refresh)
     */
    private void loadMoreNewerFromDatabase() {
        TimberLogger.i(TAG, "Trying to load newer mails from local database");

        if (mailList.isEmpty() || mailList.get(0).getId() == null) {
            loadNewerFromAPI();
            return;
        }

        try {
            String newestId = mailList.get(0).getId();
            List<Mail> newerItems = mailManager.getPaginatedMails(pageSize, newestId, false);

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

    private void handleNewerItemsFromDatabase(List<Mail> newerItems) {
        TimberLogger.i(TAG, "Found %d newer items in database, inserting at top", newerItems.size());

        Collections.reverse(newerItems);
        int itemsToRemoveFromEnd = calculateItemsToRemove(newerItems.size());
        int removalCount = itemsToRemoveFromEnd;

        if (mailCardContainer != null) {
            mailCardContainer.addMailCardsToBeginningWithBottomRemoval(
                newerItems,
                itemsToRemoveFromEnd,
                FreerApplication.MAX_CONTAINER_SIZE
            );

            if (mailCardContainer.getMailList() != null) {
                mailList.clear();
                mailList.addAll(mailCardContainer.getMailList());
            }
        } else {
            mailList.addAll(0, newerItems);
            int fallbackRemoval = removalCount;
            while (fallbackRemoval-- > 0 && !mailList.isEmpty()) {
                mailList.remove(mailList.size() - 1);
            }
        }

        if (!mailList.isEmpty()) {
            latestId = mailList.get(0).getId();
            earliestId = mailList.get(mailList.size() - 1).getId();
        }

        if (removalCount > 0) {
            hasMoreEarlierData = true;
        }

        hasMoreNewerData = newerItems.size() >= pageSize;

        updateStatistics();
        updateButtonStates();
        updateSelectAllCheckBoxState();
        showSuccessMessage("Loaded " + newerItems.size() + " newer mails from database");
        stopSwipeRefresh();
    }

    private int calculateItemsToRemove(int newItemsSize) {
        int currentSize = mailList.size();
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
     * Loads newer mails from API (get all changes including inactive mails for cleanup)
     */
    private void loadNewerFromAPI() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;
        executeInBackground(null, () -> {
            int added = mailManager.loadNewerMailsFromAPI(this);
            mailManager.commit();
            return added;
        }, this::handleNewerFromAPIResult);
    }

    private void handleNewerFromAPIResult(Integer added) {
        try {
            if (added == -1) {
                showErrorMessage(getString(R.string.failed_to_update_mails_db));
            } else if (added > 0) {
                mailManager.updateOnChainTotal(this);
                loadFirstPage();
                showSuccessMessage(getString(R.string.added_new_entities, added, MAIL));
            } else {
                showInfoMessage(getString(R.string.no_new_mails_found));
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
     * Loads more mails from local database first, then from API if needed
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadMoreEarlierFromDatabase() {
        TimberLogger.i(TAG, "Trying to load more mails from local database");
        if(!hasMoreEarlierData){
            TimberLogger.i(TAG, "No more earlier data available");
            return;
        }
        try {
            int currentSize = mailList.size();
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            List<Mail> moreItems = mailManager.getPaginatedMails(pageSize, earliestId, true);

            if (moreItems != null && !moreItems.isEmpty()) {
                int newTotalSize = currentSize + moreItems.size();
                int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                if (mailCardContainer != null) {
                    mailCardContainer.addMailCardsToEndWithTopRemoval(
                        moreItems,
                        itemsToRemoveFromBeginning,
                        FreerApplication.MAX_CONTAINER_SIZE
                    );

                    if (mailCardContainer.getMailList() != null) {
                        mailList.clear();
                        mailList.addAll(mailCardContainer.getMailList());
                    }
                } else {
                    int removalCount = itemsToRemoveFromBeginning;
                    while (removalCount-- > 0 && !mailList.isEmpty()) {
                        mailList.remove(0);
                    }
                    mailList.addAll(moreItems);
                }

                if (!mailList.isEmpty()) {
                    latestId = mailList.get(0).getId();
                    earliestId = mailList.get(mailList.size() - 1).getId();
                }

                if (itemsToRemoveFromBeginning > 0) {
                    hasMoreNewerData = true;
                }

                hasMoreEarlierData = moreItems.size() >= pageSize;

                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();

                TimberLogger.i(TAG, "Loaded %d mail items, window size: %d/%d",
                    moreItems.size(), mailList.size(), maxWindowSize);
                ToastUtils.makeText(this, String.format(getString(R.string.loaded_mails), moreItems.size()));
            } else {
                ToastUtils.makeText(this, getString(R.string.loading_more_from_apip));
                loadEarlierMails();
            }

        } catch (Exception e) {
            loadEarlierMails();
        }
    }

    /**
     * Loads newer mails into container when scrolling up
     * Implements sliding window to maintain MAX_CONTAINER_SIZE limit
     */
    private void loadNewerMailToContainer() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if(!hasMoreNewerData){
            ToastUtils.makeText(this, getString(R.string.no_more_cash));
            return;
        }

        TimberLogger.i(TAG, "Loading newer mails to container from local database");

        try {
            // Calculate how many items we can load without exceeding the window limit
            int currentSize = mailList.size();
            int pageSize = 50; // Load 50 more items
            int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;

            // Get newer items from database using pagination from the latest item
            String latestIdValue = latestId;
            if (latestIdValue == null) {
                TimberLogger.w(TAG, "Latest ID is null, cannot load newer mails");
                return;
            }

            // Get newer items (items with IDs higher than the current latest item)
            List<Mail> newerItems = mailManager.getPaginatedMails(pageSize, latestIdValue, true); // true = get items after this ID

            if (newerItems != null && !newerItems.isEmpty()) {
                int newTotalSize = currentSize + newerItems.size();
                int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);
                int removalCount = itemsToRemoveFromEnd;

                if (mailCardContainer != null) {
                    mailCardContainer.addMailCardsToBeginningWithBottomRemoval(
                        newerItems,
                        itemsToRemoveFromEnd,
                        FreerApplication.MAX_CONTAINER_SIZE
                    );

                    if (mailCardContainer.getMailList() != null) {
                        mailList.clear();
                        mailList.addAll(mailCardContainer.getMailList());
                    }
                } else {
                    while (removalCount-- > 0 && !mailList.isEmpty()) {
                        mailList.remove(mailList.size() - 1);
                    }
                    for (int i = newerItems.size() - 1; i >= 0; i--) {
                        mailList.add(0, newerItems.get(i));
                    }
                }

                if (!mailList.isEmpty()) {
                    latestId = mailList.get(0).getId();
                    earliestId = mailList.get(mailList.size() - 1).getId();
                }

                if (itemsToRemoveFromEnd > 0) {
                    hasMoreEarlierData = true;
                }

                hasMoreNewerData = newerItems.size() >= pageSize;

                updateStatistics();
                updateButtonStates();
                updateSelectAllCheckBoxState();

                TimberLogger.i(TAG, "Loaded %d newer mail items to container, window size: %d/%d",
                    newerItems.size(), mailList.size(), maxWindowSize);
                ToastUtils.makeText(this, String.format(getString(R.string.loaded_newer_mails), newerItems.size()));
            } else {
                // No more newer items available
                hasMoreNewerData = false;
                TimberLogger.i(TAG, "No more newer mail items available");
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer mails to container: %s", e.getMessage());
        }
    }

    /**
     * Loads newer mails when user swipes down - tries database first, then API
     */
    private void loadNewerMails() {
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
     * Loads earlier (older) mails when user scrolls to bottom
     */
    private void loadEarlierMails() {
        if (isLoadingNewer || isLoadingEarlier) {
            return;
        }

        if (!hasMoreEarlierData) {
            showInfoMessage(getString(R.string.no_more_mails));
            return;
        }

        isLoadingEarlier = true;
        executeInBackground(getString(R.string.loading_earlier_mails), () -> {
            int added = mailManager.loadEarlierMailsFromAPI(this);
            mailManager.commit();
            return added;
        }, this::handleEarlierMailsResult);
    }

    private void handleEarlierMailsResult(Integer added) {
        try {
            if (added > 0) {
                refreshList();
                showSuccessMessage(getString(R.string.added_new_entities, added, MAIL));
            } else if (added == 0) {
                hasMoreEarlierData = false;
                updateStatistics();
                showInfoMessage(getString(R.string.no_more_earlier_mails_found));
            } else {
                showErrorMessage(getString(R.string.failed_to_update_mails_db));
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

        // Set click listener for Delete button
        deleteButton.setOnClickListener(v -> {
            if (mailCardContainer == null) return;
            List<Mail> selectedMails = mailCardContainer.getSelectedMails();
            if (selectedMails.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }

            // Convert selected mails to JSON and launch DeleteMailActivity
            try {
                String mailListJson = JsonUtils.toJson(selectedMails);
                Intent intent = new Intent(MailActivity.this, DeleteMailActivity.class);
                intent.putExtra("mailList", mailListJson);
                deleteMailLauncher.launch(intent);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error converting mail list to JSON: %s", e.getMessage());
                ToastUtils.makeText(this, getString(R.string.error_preparing_mail_data_for_deletion));
            }
        });

        toListButton.setOnClickListener(v -> {
            if (mailCardContainer == null) return;
            List<Mail> chosenObjects = mailCardContainer.getSelectedMails();
            if (chosenObjects.isEmpty()) {
                ToastUtils.makeText(this, R.string.no_items_selected);
                return;
            }

            String me = FidManager.getInstance().getLiveFid();

            // Add selected mail IDs to global mailList
            int addedCount = 0;
            for (Mail mail : chosenObjects) {
                String id = mail.getId();
                if (id != null && !id.isEmpty()) {
                    // For mails, we could add sender/recipient FIDs to global list
                    String sender = mail.getFrom();
                    String recipient = mail.getTo();
                    if (sender != null && !sender.isEmpty() && !sender.equals(me)) {
                        FreerApplication.addFid(sender);
                        addedCount++;
                    }
                    if (recipient != null && !recipient.isEmpty() && !recipient.equals(sender) && !recipient.equals(me)) {
                        FreerApplication.addFid(recipient);
                        addedCount++;
                    }
                }
            }

            if (addedCount > 0) {
                ToastUtils.makeText(this, String.format(getString(R.string.added_mail_participants_to_list), addedCount));
                // Clear selections after adding to list
                mailCardContainer.selectAll(false);
            } else {
                ToastUtils.makeText(this, getString(R.string.no_valid_participants_found_in_selected_mails));
            }
        });

        createNewButton.setOnClickListener(v -> {
            Intent intent = new Intent(MailActivity.this, CreateMailActivity.class);
            createMailLauncher.launch(intent);
        });

        moreButton.setOnClickListener(v -> showMoreMenu(v));
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

        // Clear existing mail card manager
        if (mailCardContainer != null) {
            mailCardContainer.clearAll();
        }

        // Clear existing list
        mailList.clear();

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
        loadInitialData();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set up scroll listener only once
        if (!scrollListenerSetup) {
            // Wait for mail scroll view to be ready before setting up scroll listener
            if (mailScrollView != null) {
                setupScrollListener();
                scrollListenerSetup = true;
            } else {
                // Try again after a short delay to ensure scroll view is ready
                new android.os.Handler().postDelayed(() -> {
                    if (mailScrollView != null && !scrollListenerSetup) {
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

        if (senderSortLabel != null) {
            senderSortLabel.setTextColor(hintColor);
        }
        if (senderSortIcon != null) {
            senderSortIcon.setColorFilter(hintColor);
        }

        if (recipientSortLabel != null) {
            recipientSortLabel.setTextColor(hintColor);
        }
        if (recipientSortIcon != null) {
            recipientSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.UPDATE_HEIGHT) {
            // Reset sender and recipient sort icons and labels
            if (senderSortIcon != null) {
                senderSortIcon.setImageResource(R.drawable.ic_sort_none);
                senderSortIcon.setColorFilter(hintColor);
            }
            if (senderSortLabel != null) {
                senderSortLabel.setTextColor(hintColor);
            }
            if (recipientSortIcon != null) {
                recipientSortIcon.setImageResource(R.drawable.ic_sort_none);
                recipientSortIcon.setColorFilter(hintColor);
            }
            if (recipientSortLabel != null) {
                recipientSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.SENDER) {
            // Reset update height and recipient sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (recipientSortIcon != null) {
                recipientSortIcon.setImageResource(R.drawable.ic_sort_none);
                recipientSortIcon.setColorFilter(hintColor);
            }
            if (recipientSortLabel != null) {
                recipientSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.RECIPIENT) {
            // Reset update height and sender sort icons and labels
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (senderSortIcon != null) {
                senderSortIcon.setImageResource(R.drawable.ic_sort_none);
                senderSortIcon.setColorFilter(hintColor);
            }
            if (senderSortLabel != null) {
                senderSortLabel.setTextColor(hintColor);
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

        int color = switch (currentSortState) {
            case DESCENDING -> getResources().getColor(R.color.accent, null);
            case ASCENDING -> getResources().getColor(R.color.pink, null);
            default -> getResources().getColor(R.color.hint, null);
        };

        if (currentSortType == SortType.UPDATE_HEIGHT && sortIcon != null && sortLabel != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.SENDER && senderSortIcon != null && senderSortLabel != null) {
            senderSortIcon.setImageResource(iconResource);
            senderSortIcon.setColorFilter(color);
            senderSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.RECIPIENT && recipientSortIcon != null && recipientSortLabel != null) {
            recipientSortIcon.setImageResource(iconResource);
            recipientSortIcon.setColorFilter(color);
            recipientSortLabel.setTextColor(color);
        }
    }

    /**
     * Sorts the mail list according to current sort state and type
     */
    private void sortMailList() {
        if (mailCardContainer != null) {
            if (currentSortType == SortType.UPDATE_HEIGHT) {
                mailCardContainer.sortByBirthTime(currentSortState == SortState.ASCENDING,
                                                currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.SENDER) {
                mailCardContainer.sortBySender(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.RECIPIENT) {
                mailCardContainer.sortByRecipient(currentSortState == SortState.ASCENDING,
                                          currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by updateHeight)
            if (currentSortState == SortState.NONE) {
                mailCardContainer.sortByBirthTime(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size, database size, and total on-chain
     */
    private void updateStatistics() {

        if (mailStatisticsTextView == null || mailManager == null) {
            return;
        }

        // Get container size
        int containerSize = mailList != null ?mailList.size() : 0;

        // Get database size
        long dbSize = mailManager.getMailDBSize();
        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        statsText.append(getString(R.string.local_in_statistics)).append(dbSize);
        // Get total on-chain
        Long totalOnChain = mailManager.getTotalOnChain();
        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        mailStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.mail_statistics_container);
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
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_mail_more, (ViewGroup) anchorView.getParent(), false);
        PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        // Set up menu item click listeners
        TextView setNoticeFeeItem = popupView.findViewById(R.id.set_notice_fee_item);
        TextView deletedItem = popupView.findViewById(R.id.deleted_item);
        TextView markAllReadItem = popupView.findViewById(R.id.mark_all_read_item);

        setNoticeFeeItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(MailActivity.this, SetNoticeFeeActivity.class);
            startActivity(intent);
        });

        deletedItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(MailActivity.this, MailDeletedActivity.class);
            startActivity(intent);
        });

        markAllReadItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            handleMarkAllRead();
        });

        popupView.findViewById(R.id.about_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            com.fc.freer.utils.AboutDialog.show(this, R.string.about_mail_title, R.string.about_mail_message);
        });

        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        // Show the popup above the anchor view
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    /**
     * Replies to a mail by launching CreateMailActivity with pre-filled data
     */
    private void replyToMail(Mail mail) {
        if (mail == null) {
            ToastUtils.makeText(this, getString(R.string.invalid_mail));
            return;
        }

        // Get current live FID from FidManager
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

        if (liveFid == null) {
            ToastUtils.makeText(this, getString(R.string.no_live_fid_available));
            return;
        }

        // Determine recipient FID (the FID different from live FID)
        String recipientFid;
        if (liveFid.equals(mail.getFrom())) {
            // If current user is sender, reply to the original recipient
            recipientFid = mail.getTo();
        } else {
            // If current user is recipient, reply to the original sender
            recipientFid = mail.getFrom();
        }

        if (recipientFid == null || recipientFid.trim().isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.cannot_determine_reply_recipient));
            return;
        }

        // Create reply content with mail ID reference
        String replyContent = makeReplyHead(mail);
        // Launch CreateMailActivity with pre-filled data
        Intent intent = new Intent(this, CreateMailActivity.class);
        intent.putExtra("recipientFid", recipientFid);
        intent.putExtra("replyContent", replyContent);
        intent.putExtra("gotNoticeFee", String.valueOf(mail.getNoticeFee()));
        createMailLauncher.launch(intent);
    }

    /**
     * Opens ReadMailActivity to display mail details
     */
    private void openReadMailActivity(Mail mail) {
        if (mail == null) {
            ToastUtils.makeText(this, getString(R.string.invalid_mail));
            return;
        }

        try {
            Intent intent = new Intent(this, ReadMailActivity.class);
            intent.putExtra(ReadMailActivity.EXTRA_MAIL_JSON, mail.toJson());

            // Use updateMailLauncher to handle potential replies
            updateMailLauncher.launch(intent);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error opening ReadMailActivity: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.error_opening_mail_details));
        }
    }

    /**
     * Sends an off-chain mail to the blockchain
     */
    private void sendMailOnChain(Mail mail) {
        if (mail == null) {
            ToastUtils.makeText(this, getString(R.string.invalid_mail));
            return;
        }

        // Check if mail is already on-chain
        if (Boolean.TRUE.equals(mail.getOnChain())) {
            ToastUtils.makeText(this, getString(R.string.mail_is_already_on_chain));
            return;
        }

        // Get current KeyInfo from FidManager
        FidManager fidManager = FidManager.getInstance();
        KeyInfo liveKeyInfo = fidManager != null ? fidManager.getLiveKeyInfo() : null;

        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_live_key_info));
            return;
        }

        // Get recipient contact information for notice fee calculation
        ContactManager contactManager = ContactManager.getInstance();
        Contact recipientContact = contactManager.getContactByFid(mail.getTo());

        if (recipientContact == null) {
            ToastUtils.makeText(this, getString(R.string.recipient_contact_not_found));
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        String pubkey = ContactManager.getPubkeyGlobal(mail.getTo(), this);
        if(pubkey==null)return;

        mail.encryptContent(prikey,pubkey);

        String feipJson = makeSendMailFeip(mail);

        // Execute the transaction in background thread
        executeInBackground(getString(R.string.sending_mail_on_chain), () -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();

            // Calculate amount to send to recipient using MailManager
            double amountFch;

            amountFch = mailManager.calculateMailFee(recipientContact.getFid(), this);

            if(amountFch==-1){
                ToastUtils.makeText(this, getString(R.string.the_notice_fee_of_s_is_more_than_your_limit_d,recipientContact.getFid(),MailManager.getInstance().getSetting(MAX_PAYING_NOTICE_FEE)));
                return null;
            }

            txSender.carveFeipWithRecipient(this, liveKeyInfo.getId(), mail.getTo(), amountFch, feipJson, prikey, cashManager,
                new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            // Update mail status
                            mailManager.removeMail(mail);

                            mail.setId(txId);
                            mail.setOnChain(null);
                            mail.setBirthTime(System.currentTimeMillis()/1000);
                            mail.setLastHeight(Constants.MaX_HEIGHT);

                            // Save updated mail
                            if (mailManager != null) {
                                mailManager.updateMail(mail);
                                mailManager.commit();
                            }

                            // Refresh the mail list
                            refreshList();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            showErrorMessage("Failed to send mail on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            // Launch SendTxActivity to handle the unsigned transaction
                            Intent intent = new Intent(MailActivity.this, SendTxActivity.class);
                            intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toJsonWithSenderInfo());
                            startActivity(intent);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Launch SendTxActivity to handle the signed transaction for broadcasting
                            TxSender txSender = new TxSender();
                            txSender.showSignedTxAsQR(MailActivity.this, signedTxHex);
                        });
                    }
                });
            return null;
        }, result -> {
            // Operation completed in background thread
        });
    }

    /**
     * Handles the "Mark All Read" menu action.
     * If some mails are checked, marks only those as read.
     * If none are checked, shows a confirmation dialog then marks all mails as read.
     */
    private void handleMarkAllRead() {
        if (mailManager == null) return;

        List<Mail> selectedMails = mailCardContainer != null ? mailCardContainer.getSelectedMails() : new ArrayList<>();

        if (!selectedMails.isEmpty()) {
            markSelectedMailsAsRead(selectedMails);
        } else {
            new AlertDialog.Builder(this)
                .setMessage(R.string.confirm_mark_all_read)
                .setPositiveButton(R.string.confirm, (dialog, which) -> markAllMailsRead())
                .setNegativeButton(R.string.cancel, null)
                .show();
        }
    }

    private void markSelectedMailsAsRead(List<Mail> selectedMails) {
        List<String> unreadIds = new ArrayList<>();
        for (Mail mail : selectedMails) {
            if (Boolean.TRUE.equals(mail.getUnread())) {
                unreadIds.add(mail.getId());
                mail.setUnread(false);
            }
        }
        if (!unreadIds.isEmpty()) {
            int count = mailManager.markMailsAsRead(unreadIds);
            refreshList();
            showSuccessMessage(getString(R.string.marked_mails_read, count));
        } else {
            showInfoMessage(getString(R.string.no_unread_mails));
        }
    }

    private void markAllMailsRead() {
        int count = mailManager.markAllMailsAsRead();
        if (count > 0) {
            refreshList();
            showSuccessMessage(getString(R.string.marked_mails_read, count));
        } else {
            showInfoMessage(getString(R.string.no_unread_mails));
        }
    }

    private void markMailAsRead(String mailId) {
        if (mailId == null || mailManager == null) return;
        mailManager.markMailAsRead(mailId);
    }

}