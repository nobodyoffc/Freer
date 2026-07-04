package com.fc.freer.mail;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.MailCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.MailManager;
import com.fc.freer.manager.FidManager;

import static com.fc.fc_ajdk.constants.IndicesNames.MAIL;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.MailOpData;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;

import java.util.ArrayList;
import java.util.List;

public class MailDeletedActivity extends BaseCryptoActivity {
    private static final String TAG = "MailDeleted";

    private MailCardContainer mailCardContainer;
    private LinearLayout mailListContainer;
    protected MailManager mailManager;

    private Button recoverButton;
    private Button onChainButton;
    
    private ImageButton mailMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView mailScrollView;

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
    private enum SortType { SENDER, RECIPIENT, UPDATE_HEIGHT }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.UPDATE_HEIGHT;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Mail> mailList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean hasMoreData = true;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;

    // Statistics TextView
    private TextView mailStatisticsTextView;

    // Mode tracking
    private boolean isOnChainMode = true;

    private WaitingDialog waitingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mailManager = MailManager.getInstance();

        if (mailManager == null) {
            ToastUtils.makeText(this, getString(R.string.mail_not_ready_try_later));
            finish();
            return;
        }

        // Load initial data
        loadInitialData(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_mail_deleted;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.deleted_mails);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        recoverButton = findViewById(R.id.recover_button);
        onChainButton = findViewById(R.id.on_chain_button);
        mailMoreButton = findViewById(R.id.load_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        mailScrollView = findViewById(R.id.mail_scroll_view);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        typeSortButton = findViewById(R.id.type_sort_button);
        typeSortIcon = findViewById(R.id.type_sort_icon);
        typeSortLabel = findViewById(R.id.type_sort_label);

        // Initialize statistics TextView
        mailStatisticsTextView = findViewById(R.id.mail_statistics);

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
        // MailDeletedActivity doesn't use QR scanning functionality
    }

    /**
     * Loads the initial page of deleted Mail objects
     */
    private void loadInitialData(Context context) {
        // Show loading indicator
        showWaitingDialog("Loading deleted mails...");

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch deleted mails from API (onlyActive = false, timeAsc = false)
                Fcdsl fcdsl = mailManager.makeFcdsl(pageSize, null, false, false);

                List<Mail> deletedMails = mailManager.fetchPageMails(fcdsl);

                if (deletedMails == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        showToast("Failed to load deleted mails");
                        updateUI();
                    });
                    return;
                }

                totalDeleted = mailManager.getFapiClient().getLastResponse().getTotal();

                // Filter only deleted mails
                List<Mail> filteredDeletedMails = new ArrayList<>();
                for (Mail mail : deletedMails) {
                    if (Boolean.FALSE.equals(mail.getActive())) {
                        mail.setOnChain(true);
                        filteredDeletedMails.add(mail);
                    }
                }

                // Convert to Mail objects without saving to DB
                List<Mail> madeMailList = mailManager.makeEntityDetails(filteredDeletedMails, true, context);

                runOnUiThread(() -> {
                    this.mailList.clear();
                    this.mailList.addAll(madeMailList);

                    if (!this.mailList.isEmpty()) {
                        // Check if there might be more data
                        hasMoreData = madeMailList.size() == pageSize;
                        hasMoreEarlierData = hasMoreData;
                        hasMoreNewerData = false; // We start from the latest, so no newer data initially
                    } else {
                        // No data available
                        hasMoreData = false;
                        hasMoreEarlierData = false;
                        hasMoreNewerData = false;
                    }

                    updateUI();
                    loadMailCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast("Error loading deleted mails: " + e.getMessage());
                    updateUI();
                });
            }
        }).start();
    }

    private void updateButtonStates() {
        boolean hasContacts = !(mailList == null || mailList.isEmpty());
        boolean hasSelected = mailCardContainer != null && !mailCardContainer.getSelectedMails().isEmpty();

        recoverButton.setEnabled(hasSelected);
        recoverButton.setAlpha(hasSelected ? 1.0f : 0.5f);

    }

    private void loadMailCardList() {
        // Initialize the LinearLayout container for mail cards
        mailListContainer = findViewById(R.id.fragment_container);

        // Create MailCardContainer with selection enabled (checkbox mode)
        String liveFid = FidManager.getInstance().getLiveKeyInfo().getId();
        mailCardContainer = new MailCardContainer(this, mailListContainer, ChooseMode.CHOOSE_MULTI, liveFid);
        mailCardContainer.setScrollView(mailScrollView);

        // Set up selection change listener
        mailCardContainer.setOnMailListChangedListener(updatedMailList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

        // Add all mail cards to the manager
        for (Mail mail : mailList) {
            mailCardContainer.addMailCard(mail);
        }

        mailCardContainer.clearAllBoundaryIndicators();

        // Set up scroll listener for pagination
        setupScrollListener();

        // Update UI after cards are loaded
        updateUI();
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
                // Reset other sorts to NONE if save time sort was not active
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
     * Sets up the type sort button listener
     */
    private void setupTypeSortButton() {
        if (typeSortButton != null) {
            typeSortButton.setOnClickListener(v -> {
                // Reset other sorts to NONE if type sort was not active
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
     * Sets up swipe refresh functionality for loading newer mails
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer deleted mails");
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

    private long lastScrollTime = 0;
    private static final int MIN_SCROLL_DISTANCE = 10; // Minimum scroll distance to trigger loading
    private static final long SCROLL_DELAY_MS = 300; // Delay before triggering load (0.3 seconds)

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

                // Check if we've reached the top and user is scrolling down to load newer mails
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerMails();
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
     * Loads newer deleted mails (swipe down from top)
     */
    private void loadNewerMails() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch newer deleted mails (timeAsc = true)
                List<String> latestMailSortKeys = getLatestMailSortKeys();
                Fcdsl fcdsl = mailManager.makeFcdsl(pageSize, latestMailSortKeys, false, true);

                List<Mail> newerMails = mailManager.fetchPageMails(fcdsl);

                if (newerMails == null) {
                    runOnUiThread(() -> {
                        isLoadingNewer = false;
                        swipeRefreshLayout.setRefreshing(false);
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_newer_deleted_mails));
                    });
                    return;
                }

                // Filter only deleted mails
                List<Mail> filteredMails = new ArrayList<>();
                for (Mail mail : newerMails) {
                    if (Boolean.FALSE.equals(mail.getActive())) {
                        filteredMails.add(mail);
                    }
                }

                // Convert to Mail objects
                List<Mail> newerMailDetails = mailManager.makeEntityDetails(filteredMails, true, this);
                runOnUiThread(() -> {
                    if (!newerMailDetails.isEmpty()) {
                        int currentSize = mailList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + newerMailDetails.size();
                        int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                        java.util.Collections.reverse(newerMailDetails);

                        if (mailCardContainer != null) {
                            mailCardContainer.addMailCardsToBeginningWithBottomRemoval(
                                newerMailDetails,
                                itemsToRemoveFromEnd,
                                maxWindowSize
                            );

                            if (mailCardContainer.getMailList() != null) {
                                mailList.clear();
                                mailList.addAll(mailCardContainer.getMailList());
                            }
                        } else {
                            mailList.addAll(0, newerMailDetails);
                            int removal = itemsToRemoveFromEnd;
                            while (removal-- > 0 && !mailList.isEmpty()) {
                                mailList.remove(mailList.size() - 1);
                            }
                        }

                        if (itemsToRemoveFromEnd > 0) {
                            hasMoreEarlierData = true;
                        }

                        hasMoreNewerData = newerMailDetails.size() >= pageSize;

                        ToastUtils.makeText(this, String.format(getString(R.string.loaded_newer_deleted_mails), newerMailDetails.size()));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.no_newer_deleted_mails_found));
                    }

                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer deleted mails: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                    ToastUtils.makeText(this, String.format(getString(R.string.error_loading_newer_deleted_mails), e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Loads earlier (older) deleted mails (scroll to bottom)
     */
    private void loadEarlierMails() {
        if (isLoadingNewer || isLoadingEarlier || !hasMoreEarlierData) {
            return;
        }

        isLoadingEarlier = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch earlier deleted mails (timeAsc = false)
                List<String> earliestMailSortKeys = getEarliestMailSortKeys();
                Fcdsl fcdsl = mailManager.makeFcdsl(pageSize, earliestMailSortKeys, false, false);

                List<Mail> earlierMails = mailManager.fetchPageMails(fcdsl);

                if (earlierMails == null) {
                    runOnUiThread(() -> {
                        isLoadingEarlier = false;
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_earlier_deleted_mails));
                    });
                    return;
                }

                // Filter only deleted mails
                List<Mail> filteredMails = new ArrayList<>();
                for (Mail mail : earlierMails) {
                    if (Boolean.FALSE.equals(mail.getActive())) {
                        filteredMails.add(mail);
                    }
                }

                // Convert to Mail objects
                List<Mail> earlierMailDetails = mailManager.makeEntityDetails(filteredMails, true, this);

                runOnUiThread(() -> {
                    if (!earlierMailDetails.isEmpty()) {
                        int currentSize = mailList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + earlierMailDetails.size();
                        int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                        if (mailCardContainer != null) {
                            mailCardContainer.addMailCardsToEndWithTopRemoval(
                                earlierMailDetails,
                                itemsToRemoveFromBeginning,
                                maxWindowSize
                            );

                            if (mailCardContainer.getMailList() != null) {
                                mailList.clear();
                                mailList.addAll(mailCardContainer.getMailList());
                            }
                        } else {
                            int removal = itemsToRemoveFromBeginning;
                            while (removal-- > 0 && !mailList.isEmpty()) {
                                mailList.remove(0);
                            }
                            mailList.addAll(earlierMailDetails);
                        }

                        if (itemsToRemoveFromBeginning > 0) {
                            hasMoreNewerData = true;
                        }

                        hasMoreEarlierData = earlierMailDetails.size() >= pageSize;

                        ToastUtils.makeText(this, String.format(getString(R.string.loaded_earlier_deleted_mails), earlierMailDetails.size()));
                    } else {
                        hasMoreEarlierData = false;
                        ToastUtils.makeText(this, getString(R.string.no_more_earlier_deleted_mails_found));
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier deleted mails: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                    ToastUtils.makeText(this, String.format(getString(R.string.error_loading_earlier_deleted_mails), e.getMessage()));
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
        if (mailMoreButton != null) {
            mailMoreButton.setOnClickListener(v -> {
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    mailMoreButton.setEnabled(false);
                    mailMoreButton.setAlpha(0.5f);
                    loadEarlierMails();
                    new android.os.Handler().postDelayed(() -> {
                        if (mailMoreButton != null) {
                            mailMoreButton.setEnabled(true);
                            mailMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }

        // Set click listener for Recover button
        recoverButton.setOnClickListener(v -> {
            if (mailCardContainer == null) return;
            List<Mail> selectedMails = mailCardContainer.getSelectedMails();
            if (selectedMails.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_mails_selected_for_recovery));
                return;
            }
            performRecoverOperation(selectedMails);
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
     * Sets default colors for sort labels and icons
     */
    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);
        if (sortLabel != null) sortLabel.setTextColor(hintColor);
        if (typeSortLabel != null) typeSortLabel.setTextColor(hintColor);
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);
        if (currentSortType == SortType.SENDER) {
            // Reset type sort icon and label
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.RECIPIENT) {
            // Reset save time sort icon and label
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
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
        int labelColor;
        switch (currentSortState) {
            case NONE:
                iconResource = R.drawable.ic_sort_none;
                labelColor = getResources().getColor(R.color.hint, null);
                break;
            case ASCENDING:
                iconResource = R.drawable.ic_sort_asc;
                labelColor = getResources().getColor(R.color.pink, null);
                break;
            case DESCENDING:
                iconResource = R.drawable.ic_sort_desc;
                labelColor = getResources().getColor(R.color.accent, null);
                break;
            default:
                iconResource = R.drawable.ic_sort_none;
                labelColor = getResources().getColor(R.color.hint, null);
        }

        if (currentSortType == SortType.SENDER && sortIcon != null) {
            sortIcon.setImageResource(iconResource);
            if (sortLabel != null) sortLabel.setTextColor(labelColor);
        } else if (currentSortType == SortType.RECIPIENT && typeSortIcon != null) {
            typeSortIcon.setImageResource(iconResource);
            if (typeSortLabel != null) typeSortLabel.setTextColor(labelColor);
        }
    }

    /**
     * Sorts the mail list according to current sort state and type
     */
    private void sortMailList() {
        if (mailCardContainer != null) {
            if (currentSortType == SortType.SENDER) {
                mailCardContainer.sortBySender(currentSortState == SortState.ASCENDING,
                                            currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.RECIPIENT) {
                mailCardContainer.sortByRecipient(currentSortState == SortState.ASCENDING,
                                         currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.UPDATE_HEIGHT) {
                mailCardContainer.sortByBirthTime(currentSortState == SortState.ASCENDING,
                                                 currentSortState != SortState.NONE);
            }

            // If all sort buttons are in NONE state, restore default order (desc by birthTime)
            if (currentSortState == SortState.NONE) {
                mailCardContainer.sortByBirthTime(false, true); // false = descending, true = enable sort
            }
        }
    }

    /**
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (mailStatisticsTextView == null) {
            return;
        }

        // Get container size
        int containerSize = mailList != null ? mailList.size() : 0;

        StringBuilder statsText = new StringBuilder();
        statsText.append("Above: ").append(containerSize);
        if (totalDeleted != null)
            statsText.append("      Total: ").append(totalDeleted);

        mailStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.mail_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        // Update More button visibility based on hasMoreEarlierData
        if (mailMoreButton != null) {
            mailMoreButton.setVisibility(hasMoreEarlierData ? View.VISIBLE : View.GONE);
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

    private void performRecoverOperation(List<Mail> mailsToRecover) {
        // Check if we're in off-chain mode
        if (!isOnChainMode) {
            performOffChainRecovery(mailsToRecover);
            return;
        }

        // On-chain recovery logic
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        // Extract IDs for the recover operation
        List<String> mailIds = new ArrayList<>();
        for (Mail mail : mailsToRecover) {
            if (mail.getId() != null) {
                mailIds.add(mail.getId());
            }
        }

        if (mailIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_mail_ids_for_recovery));
            return;
        }

        // Create FEIP for recover operation
        String feipJson = makeRecoverMailFeip(mailIds);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            // Send transaction on blockchain
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            // Add recovered mails to database
                            for (Mail mail : mailsToRecover) {
                                mail.setActive(false);
                                mail.setOnChain(null);
                                mailManager.addMail(mail);
                            }
                            mailManager.commit();

                            // Remove recovered mails from card container and main list
                            mailCardContainer.removeSelectedMails();
                            mailList.removeAll(mailsToRecover);

                            updateUI();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(MailDeletedActivity.this, String.format(getString(R.string.failed_to_recover_mails_on_chain), errorMessage));
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(MailDeletedActivity.this, getString(R.string.cannot_sign_transaction_showing_unsigned_tx));
                            txSender.showUnsignedTxAsQR(MailDeletedActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(MailDeletedActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            // Recover locally without blockchain transaction
            for (Mail mail : mailsToRecover) {
                mail.setActive(false);
                mailManager.addMail(mail);
            }
            mailManager.commit();

            // Remove recovered mails from card container and main list
            mailCardContainer.removeSelectedMails();
            mailList.removeAll(mailsToRecover);

            updateUI();
            ToastUtils.makeText(this, getString(R.string.mails_recovered_locally));
        }
    }

    /**
     * Performs off-chain recovery by simply saving locally deleted mails back to the database
     */
    private void performOffChainRecovery(List<Mail> mailsToRecover) {
        showWaitingDialog("Recovering mails locally...");

        // Perform recovery on background thread
        new Thread(() -> {
            try {
                // For off-chain recovery, simply save the mails back to the database
                for (Mail mail : mailsToRecover) {
                    // Set the mail as active (recovered)
                    mail.setActive(true);

                    // Add the mail back to the database
                    mailManager.addMail(mail);
                }
                // Commit the changes to the database
                mailManager.commit();

                runOnUiThread(() -> {
                    // Remove recovered mails from the local deleted list
                    for (Mail mail : mailsToRecover) {
                        mailManager.removeFromLocalDeletedList(mail);
                    }

                    // Remove recovered mails from card container and main list
                    mailCardContainer.removeSelectedMails();
                    mailList.removeAll(mailsToRecover);

                    // Update the total count
                    totalDeleted = mailManager.getLocalDeletedListSize();

                    updateUI();
                    dismissWaitingDialog();
                    ToastUtils.makeText(MailDeletedActivity.this, getString(R.string.mails_recovered_successfully_off_chain));
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error during off-chain recovery: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(MailDeletedActivity.this, String.format(getString(R.string.failed_to_recover_mails), e.getMessage()));
                });
            }
        }).start();
    }

    private static String makeRecoverMailFeip(List<String> mailIds) {
        Feip feip = Feip.fromName(MAIL);
        MailOpData mailOpData = MailOpData.makeRecover(mailIds);
        feip.setData(mailOpData);
        return feip.toJson();
    }

    /**
     * Helper method to create sort list from Mail object
     */
    private List<String> makeSortList(Mail mail) {
        return java.util.Arrays.asList(String.valueOf(mail.getLastHeight()), mail.getId());
    }

    private List<String> getLatestMailSortKeys() {
        if (mailList.isEmpty()) {
            return null;
        }
        return makeSortList(mailList.get(0));
    }

    private List<String> getEarliestMailSortKeys() {
        if (mailList.isEmpty()) {
            return null;
        }
        return makeSortList(mailList.get(mailList.size() - 1));
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
        clearMailList();

        // Load appropriate data
        if (isOnChainMode) {
            loadInitialData(this);
        } else {
            loadLocalDeletedMails();
        }
    }

    /**
     * Clears the mail list and card container
     */
    private void clearMailList() {
        if (mailCardContainer != null) {
            mailCardContainer.clearAll();
        }

        mailList.clear();

        // Reset pagination state
        hasMoreData = true;
        hasMoreEarlierData = true;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingEarlier = false;
        totalDeleted = null;

        updateUI();
    }

    /**
     * Loads deleted mails from local deleted list
     */
    private void loadLocalDeletedMails() {
        // Show loading indicator
        showWaitingDialog("Loading local deleted mails...");

        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Get deleted mails from localDeletedList
                List<Mail> localDeletedMails = mailManager.getLocalDeletedList();

                runOnUiThread(() -> {
                    this.mailList.clear();
                    this.mailList.addAll(localDeletedMails);

                    // Set total count to local deleted list size
                    totalDeleted = mailManager.getLocalDeletedListSize();

                    updateUI();
                    loadMailCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading local deleted mails: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast("Error loading local deleted mails: " + e.getMessage());
                    updateUI();
                });
            }
        }).start();
    }
}