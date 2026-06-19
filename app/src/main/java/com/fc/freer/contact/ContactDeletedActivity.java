package com.fc.freer.contact;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageButton;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ContactCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
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

import java.util.ArrayList;
import java.util.List;

public class ContactDeletedActivity extends BaseCryptoActivity {
    private static final String TAG = "ContactDeleted";

    private ContactCardContainer contactCardContainer;
    private LinearLayout contactListContainer;
    protected ContactManager contactManager;

    private Button recoverButton;
    private Button onChainButton;
    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView contactScrollView;

    // Sort-related UI elements
    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout typeSortButton;
    private ImageView typeSortIcon;
    private TextView typeSortLabel;
    private Long totalDeleted;

    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { FID, NAME, UPDATE_HEIGHT }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.UPDATE_HEIGHT;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Contact> contactList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean hasMoreData = true;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;

    // Statistics TextView
    private TextView contactStatisticsTextView;

    // Mode tracking
    private boolean isOnChainMode = true;

    private WaitingDialog waitingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        contactManager = ContactManager.getInstance();

        if (contactManager == null) {
            ToastUtils.makeText(this, getString(R.string.contact_not_ready_try_later));
            finish();
            return;
        }

        // Load initial data
        loadInitialData(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_contact_deleted;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.deleted_contacts);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        recoverButton = findViewById(R.id.recover_button);
        onChainButton = findViewById(R.id.on_chain_button);
        loadMoreButton = findViewById(R.id.load_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        contactScrollView = findViewById(R.id.contact_scroll_view);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        typeSortButton = findViewById(R.id.type_sort_button);
        typeSortIcon = findViewById(R.id.type_sort_icon);
        typeSortLabel = findViewById(R.id.type_sort_label);

        // Initialize statistics TextView
        contactStatisticsTextView = findViewById(R.id.contact_statistics);

        // Set default sort colors
        setDefaultSortColors();

        // Set up select all checkbox listener
        setupSelectAllCheckBox();

        // Set up sort button listeners
        setupSortButton();
        setupTypeSortButton();

        // Set up swipe refresh
        setupSwipeRefresh();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // ContactDeletedActivity doesn't use QR scanning functionality
    }

    /**
     * Loads the initial page of deleted Contact objects
     */
    private void loadInitialData(Context context) {
        // Show loading indicator
        showWaitingDialog("Loading deleted contacts...");

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch deleted contacts from API (onlyActive = false, timeAsc = false)
                Fcdsl fcdsl = contactManager.makeFcdsl(pageSize, null, false, false);

                List<Contact> deletedContacts = contactManager.fetchPageContacts(fcdsl);

                if (deletedContacts == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        showToast("Failed to load deleted contacts");
                        updateUI();
                    });
                    return;
                }

                totalDeleted = contactManager.getFapiClient().getLastResponse().getTotal();

                // Filter only deleted contacts
                List<Contact> filteredDeletedContacts = new ArrayList<>();
                for (Contact contact : deletedContacts) {
                    if (Boolean.FALSE.equals(contact.getActive())) {
                        contact.setOnChain(true);
                        filteredDeletedContacts.add(contact);
                    }
                }

                // Convert to Contact objects without saving to DB
                List<Contact> contactList = contactManager.makeEntityDetails(filteredDeletedContacts, true, context);

                runOnUiThread(() -> {
                    this.contactList.clear();
                    this.contactList.addAll(contactList);

                    // Set pagination info
                    if (!this.contactList.isEmpty()) {
                        // Check if there might be more data
                        hasMoreData = deletedContacts.size() == pageSize;
                        hasMoreEarlierData = hasMoreData;
                        hasMoreNewerData = false; // We start from the latest, so no newer data initially
                    } else {
                        // No data available
                        hasMoreData = false;
                        hasMoreEarlierData = false;
                        hasMoreNewerData = false;
                    }

                    updateUI();
                    loadContactCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast("Error loading deleted contacts: " + e.getMessage());
                    updateUI();
                });
            }
        }).start();
    }

    private void updateButtonStates() {
        boolean hasContacts = !(contactList == null || contactList.isEmpty());
        boolean hasSelected = contactCardContainer != null && !contactCardContainer.getSelectedContacts().isEmpty();

        recoverButton.setEnabled(hasSelected);
        recoverButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void loadContactCardList() {
        // Initialize the LinearLayout container for contact cards
        contactListContainer = findViewById(R.id.fragment_container);

        // Create ContactCardContainer with selection enabled (checkbox mode)
        contactCardContainer = new ContactCardContainer(this, contactListContainer, ChooseMode.CHOOSE_MULTI);
        contactCardContainer.setScrollView(contactScrollView);

        // Set up selection change listener
        contactCardContainer.setOnContactListChangedListener(updatedContactList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Add all contact cards to the manager
        for (Contact contact : contactList) {
            contactCardContainer.addContactCard(contact);
        }

        contactCardContainer.clearAllBoundaryIndicators();

        // Set up scroll listener for pagination
        setupScrollListener();

        // Update UI after cards are loaded
        updateUI();
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
                // Reset other sorts to NONE if save time sort was not active
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
     * Sets up the type sort button listener
     */
    private void setupTypeSortButton() {
        if (typeSortButton != null) {
            typeSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if type sort was not active
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
     * Sets up swipe refresh functionality for loading newer contacts
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer deleted contacts");
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

    private long lastScrollTime = 0;
    private static final int MIN_SCROLL_DISTANCE = 10; // Minimum scroll distance to trigger loading
    private static final long SCROLL_DELAY_MS = 300; // Delay before triggering load (0.3 seconds)

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

                // Check if we've reached the top and user is scrolling down to load newer contacts
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerContacts();
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
     * Loads newer deleted contacts (swipe down from top)
     */
    private void loadNewerContacts() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch newer deleted contacts (timeAsc = true)
                List<String> latestContactSortKeys = getLatestContactSortKeys();
                Fcdsl fcdsl = contactManager.makeFcdsl(pageSize, latestContactSortKeys, false, true);

                List<Contact> newerContacts = contactManager.fetchPageContacts(fcdsl);

                if (newerContacts == null) {
                    runOnUiThread(() -> {
                        isLoadingNewer = false;
                        swipeRefreshLayout.setRefreshing(false);
                        ToastUtils.makeText(this, "Failed to load newer deleted contacts");
                    });
                    return;
                }

                // Filter only deleted contacts
                List<Contact> filteredContacts = new ArrayList<>();
                for (Contact contact : newerContacts) {
                    if (Boolean.FALSE.equals(contact.getActive())) {
                        filteredContacts.add(contact);
                    }
                }

                // Convert to Contact objects
                List<Contact> newerContactDetails = contactManager.makeEntityDetails(filteredContacts, true, this);
                runOnUiThread(() -> {
                    if (!newerContactDetails.isEmpty()) {
                        // Calculate how many items we can add without exceeding the window limit
                        int currentSize = contactList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + newerContactDetails.size();
                        int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                        // Remove items from end if necessary (sliding window)
                        java.util.Collections.reverse(newerContactDetails);

                        contactCardContainer.addContactCardsToBeginningWithBottomRemoval(
                            newerContactDetails,
                            itemsToRemoveFromEnd,
                            FreerApplication.MAX_CONTAINER_SIZE
                        );

                        if (itemsToRemoveFromEnd > 0) {
                            hasMoreEarlierData = true; // Now we have earlier data available
                        }

                        if (contactCardContainer.getContactList() != null) {
                            contactList.clear();
                            contactList.addAll(contactCardContainer.getContactList());
                        }

                        // Check if there are more newer items available
                        hasMoreNewerData = newerContacts.size() >= pageSize;

                        ToastUtils.makeText(this, "Loaded " + newerContactDetails.size() + " newer deleted contacts");
                    } else {
                        ToastUtils.makeText(this, "No newer deleted contacts found");
                    }

                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer deleted contacts: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                    ToastUtils.makeText(this, "Error loading newer deleted contacts: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Loads earlier (older) deleted contacts (scroll to bottom)
     */
    private void loadEarlierContacts() {
        if (isLoadingNewer || isLoadingEarlier || !hasMoreEarlierData) {
            return;
        }

        isLoadingEarlier = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch earlier deleted contacts (timeAsc = false)
                List<String> earliestContactSortKeys = getEarliestContactSortKeys();
                Fcdsl fcdsl = contactManager.makeFcdsl(pageSize, earliestContactSortKeys, false, false);

                List<Contact> earlierContacts = contactManager.fetchPageContacts(fcdsl);

                if (earlierContacts == null) {
                    runOnUiThread(() -> {
                        isLoadingEarlier = false;
                        ToastUtils.makeText(this, "Failed to load earlier deleted contacts");
                    });
                    return;
                }

                // Filter only deleted contacts
                List<Contact> filteredContacts = new ArrayList<>();
                for (Contact contact : earlierContacts) {
                    if (Boolean.FALSE.equals(contact.getActive())) {
                        filteredContacts.add(contact);
                    }
                }

                // Convert to Contact objects
                List<Contact> earlierContactDetails = contactManager.makeEntityDetails(filteredContacts, true, this);

                runOnUiThread(() -> {
                    if (!earlierContactDetails.isEmpty()) {
                        // Calculate how many items we can add without exceeding the window limit
                        int currentSize = contactList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + earlierContactDetails.size();
                        int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                        // Remove items from beginning if necessary (sliding window)
                        contactCardContainer.addContactCardsToEndWithTopRemoval(
                            earlierContactDetails,
                            itemsToRemoveFromBeginning,
                            FreerApplication.MAX_CONTAINER_SIZE
                        );

                        if (itemsToRemoveFromBeginning > 0) {
                            hasMoreNewerData = true; // Now we have newer data available
                        }

                        if (contactCardContainer.getContactList() != null) {
                            contactList.clear();
                            contactList.addAll(contactCardContainer.getContactList());
                        }

                        // Check if there are more earlier items available
                        if (earlierContacts.size() < pageSize) {
                            hasMoreEarlierData = false;
                        }

                        ToastUtils.makeText(this, "Loaded " + earlierContactDetails.size() + " earlier deleted contacts");
                    } else {
                        hasMoreEarlierData = false;
                        ToastUtils.makeText(this, "No more earlier deleted contacts found");
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier deleted contacts: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                    ToastUtils.makeText(this, "Error loading earlier deleted contacts: " + e.getMessage());
                });
            }
        }).start();
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
        currentSortState = SortState.NONE;
        currentSortType = SortType.UPDATE_HEIGHT;
        setDefaultSortColors();
    }

    @Override
    protected void setupButtons() {
        // Set click listener for More button to load earlier data
        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    loadMoreButton.setEnabled(false);
                    loadMoreButton.setAlpha(0.5f);
                    loadEarlierContacts();
                    new android.os.Handler().postDelayed(() -> {
                        if (loadMoreButton != null) {
                            loadMoreButton.setEnabled(true);
                            loadMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }

        // Set click listener for Recover button
        recoverButton.setOnClickListener(v -> {
            if (contactCardContainer == null) return;
            List<Contact> selectedContacts = contactCardContainer.getSelectedContacts();
            if (selectedContacts.isEmpty()) {
                ToastUtils.makeText(this, "No contacts selected for recovery");
                return;
            }
            performRecoverOperation(selectedContacts);
        });

        // Set click listener for On Chain button
        onChainButton.setOnClickListener(v -> {
            toggleMode();
        });
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
    }

    /**
     * Resets other sort icons and colors to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);
        if (currentSortType == SortType.FID) {
            // Reset name sort icon and label
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
                typeSortIcon.setColorFilter(hintColor);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.NAME) {
            // Reset FID sort icon and label
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

        if (currentSortType == SortType.FID && sortIcon != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            if (sortLabel != null) sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.NAME && typeSortIcon != null) {
            typeSortIcon.setImageResource(iconResource);
            typeSortIcon.setColorFilter(color);
            if (typeSortLabel != null) typeSortLabel.setTextColor(color);
        }
    }

    /**
     * Sorts the contact list according to current sort state and type
     */
    private void sortContactList() {
        if (contactCardContainer != null) {
            if (currentSortType == SortType.FID) {
                contactCardContainer.sortByFid(currentSortState == SortState.ASCENDING,
                                            currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.NAME) {
                contactCardContainer.sortByName(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.UPDATE_HEIGHT) {
                contactCardContainer.sortByUpdateHeight(currentSortState == SortState.ASCENDING,
                                                 currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by updateHeight)
            if (currentSortState == SortState.NONE) {
                contactCardContainer.sortByUpdateHeight(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (contactStatisticsTextView == null) {
            return;
        }

        // Get container size
        int containerSize = contactListContainer != null ? contactList.size() : 0;

        StringBuilder statsText = new StringBuilder();
        statsText.append("Above: ").append(containerSize);
        if (totalDeleted != null)
            statsText.append("      Total: ").append(totalDeleted);

        contactStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.contact_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        // Update More button visibility based on hasMoreEarlierData
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
        waitingDialog = null;
    }

    private void performRecoverOperation(List<Contact> contactsToRecover) {
        // Check if we're in off-chain mode
        if (!isOnChainMode) {
            performOffChainRecovery(contactsToRecover);
            return;
        }

        // On-chain recovery logic
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, "No active key available");
            return;
        }

        // Extract IDs for the recover operation
        List<String> contactIds = new ArrayList<>();
        for (Contact contact : contactsToRecover) {
            if (contact.getId() != null) {
                contactIds.add(contact.getId());
            }
        }

        if (contactIds.isEmpty()) {
            ToastUtils.makeText(this, "No valid contact IDs for recovery");
            return;
        }

        // Create FEIP for recover operation
        String feipJson = makeRecoverContactFeip(contactIds);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            // Send transaction on blockchain
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            // Add recovered contacts to database
                            for (Contact contact : contactsToRecover) {
                                contact.setActive(false);
                                contact.setOnChain(null);
                                contactManager.addContact(contact);
                            }
                            contactManager.commit();

                            // Remove recovered contacts from card container and main list
                            contactCardContainer.removeSelectedContacts();
                            contactList.removeAll(contactsToRecover);

                            updateUI();
                            ToastUtils.makeText(ContactDeletedActivity.this, "Contacts recovered successfully on-chain");
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(ContactDeletedActivity.this, "Failed to recover contacts on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(ContactDeletedActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(ContactDeletedActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(ContactDeletedActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            // Recover locally without blockchain transaction
            for (Contact contact : contactsToRecover) {
                contact.setActive(false);
                contactManager.addContact(contact);
            }
            contactManager.commit();

            // Remove recovered contacts from card container and main list
            contactCardContainer.removeSelectedContacts();
            contactList.removeAll(contactsToRecover);

            updateUI();
            ToastUtils.makeText(this, "Contacts recovered locally");

            // Set up scroll listener for pagination
            setupScrollListener();
        }
    }

    /**
     * Performs off-chain recovery by simply saving locally deleted contacts back to the database
     */
    private void performOffChainRecovery(List<Contact> contactsToRecover) {
        showWaitingDialog("Recovering contacts locally...");

        // Perform recovery on background thread
        new Thread(() -> {
            try {
                // For off-chain recovery, simply save the contacts back to the database
                for (Contact contact : contactsToRecover) {
                    // Set the contact as active (recovered)
                    contact.setActive(true);

                    // Add the contact back to the database
                    contactManager.addContact(contact);
                }
                // Commit the changes to the database
                contactManager.commit();

                runOnUiThread(() -> {
                    // Remove recovered contacts from the local deleted list
                    for (Contact contact : contactsToRecover) {
                        contactManager.removeFromLocalDeletedList(contact);
                    }

                    // Remove recovered contacts from card container and main list
                    contactCardContainer.removeSelectedContacts();
                    contactList.removeAll(contactsToRecover);

                    // Update the total count
                    totalDeleted = contactManager.getLocalDeletedListSize();

                    updateUI();
                    dismissWaitingDialog();
                    ToastUtils.makeText(ContactDeletedActivity.this, "Contacts recovered successfully (off-chain)");
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error during off-chain recovery: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(ContactDeletedActivity.this, "Failed to recover contacts: " + e.getMessage());
                });
            }
        }).start();
    }

    private static String makeRecoverContactFeip(List<String> contactIds) {
        Feip feip = Feip.fromName(CONTACT);
        ContactOpData contactOpData = ContactOpData.makeRecover(contactIds);
        feip.setData(contactOpData);
        return feip.toJson();
    }

    /**
     * Helper method to create sort list from Contact object
     */
    private List<String> makeSortList(Contact contact) {
        return java.util.Arrays.asList(String.valueOf(contact.getLastHeight()), contact.getId());
    }

    private List<String> getLatestContactSortKeys() {
        if (contactList.isEmpty()) {
            return null;
        }
        return makeSortList(contactList.get(0));
    }

    private List<String> getEarliestContactSortKeys() {
        if (contactList.isEmpty()) {
            return null;
        }
        return makeSortList(contactList.get(contactList.size() - 1));
    }

    /**
     * Toggles between on-chain and off-chain mode
     */
    private void toggleMode() {
        isOnChainMode = !isOnChainMode;

        // Update button text
        if (isOnChainMode) {
            onChainButton.setText(getString(R.string.off_chain));
        } else {
            onChainButton.setText(getString(R.string.on_chain));
        }

        // Clear current list
        clearContactList();

        // Load appropriate data
        if (isOnChainMode) {
            loadInitialData(this);
        } else {
            loadLocalDeletedContacts();
        }
    }

    /**
     * Clears the contact list and card container
     */
    private void clearContactList() {
        if (contactCardContainer != null) {
            contactCardContainer.clearAll();
        }

        contactList.clear();

        // Reset pagination state
        hasMoreData = false;
        hasMoreEarlierData = false;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingEarlier = false;
        totalDeleted = null;

        updateUI();
    }

    /**
     * Loads deleted contacts from local deleted list
     */
    private void loadLocalDeletedContacts() {
        // Show loading indicator
        showWaitingDialog("Loading local deleted contacts...");

        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Get deleted contacts from localDeletedList
                List<Contact> localDeletedContacts = contactManager.getLocalDeletedList();

                runOnUiThread(() -> {
                    this.contactList.clear();
                    this.contactList.addAll(localDeletedContacts);

                    // Set total count to local deleted list size
                    totalDeleted = contactManager.getLocalDeletedListSize();

                    hasMoreEarlierData = false;
                    hasMoreNewerData = false;
                    updateUI();
                    loadContactCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading local deleted contacts: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast("Error loading local deleted contacts: " + e.getMessage());
                    updateUI();
                });
            }
        }).start();
    }
}