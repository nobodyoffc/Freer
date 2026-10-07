package com.fc.freer.secret;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.SecretCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.FreerApplication;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.manager.FidManager;

import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
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
import android.widget.ImageButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SecretDeletedActivity extends BaseCryptoActivity {
    private static final String TAG = "SecretDeleted";

    private SecretCardContainer secretCardContainer;
    private LinearLayout secretListContainer;
    protected SecretManager secretManager;

    private Button recoverButton;
    private Button onChainButton;
    private ImageButton okButton;

    private ImageButton secretMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView secretScrollView;

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
    private enum SortType { SAVE_TIME, TYPE, UPDATE_HEIGHT }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.UPDATE_HEIGHT;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Secret> secretList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean hasMoreData = true;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;

    // Statistics TextView
    private TextView secretStatisticsTextView;

    // Mode tracking
    private boolean isOnChainMode = true;

    private WaitingDialog waitingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        secretManager = SecretManager.getInstance();

        if (secretManager == null) {
            ToastUtils.makeText(this, getString(R.string.secret_not_ready_try_later));
            finish();
            return;
        }

        // Load initial data
        loadInitialData(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_secret_deleted;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.deleted_secrets);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        recoverButton = findViewById(R.id.recover_button);
        onChainButton = findViewById(R.id.on_chain_button);
        okButton = findViewById(R.id.ok_button);
        secretMoreButton = findViewById(R.id.load_more_button);

        // Initialize select all checkbox
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize swipe refresh layout and scroll view
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        secretScrollView = findViewById(R.id.secret_scroll_view);

        // Initialize sort button elements
        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        typeSortButton = findViewById(R.id.type_sort_button);
        typeSortIcon = findViewById(R.id.type_sort_icon);
        typeSortLabel = findViewById(R.id.type_sort_label);

        // Initialize statistics TextView
        secretStatisticsTextView = findViewById(R.id.secret_statistics);

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
        // SecretDeletedActivity doesn't use QR scanning functionality
    }

    /**
     * Loads the initial page of deleted Secret objects
     */
    private void loadInitialData(Context context) {
        // Show loading indicator
        showWaitingDialog(getString(R.string.loading_deleted_secrets));

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch deleted secrets from API (onlyActive = false, timeAsc = false)
                Fcdsl fcdsl = secretManager.makeFcdsl(pageSize, null, false, false);

                List<Secret> deletedSecrets = secretManager.fetchPageSecrets(fcdsl);

                if (deletedSecrets == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        showToast(getString(R.string.failed_to_load_deleted_secrets));
                        updateUI();
                    });
                    return;
                }

                totalDeleted = secretManager.getFapiClient().getLastResponse().getTotal();

                // Filter only deleted secrets
                List<Secret> filteredDeletedSecrets = new ArrayList<>();
                for (Secret secret : deletedSecrets) {
                    if (Boolean.FALSE.equals(secret.getActive())) {
                        filteredDeletedSecrets.add(secret);
                    }
                }

                // Convert to Secret objects without saving to DB
                List<Secret> secretList = secretManager.makeEntityDetails(filteredDeletedSecrets,true,context );

                runOnUiThread(() -> {
                    this.secretList.clear();
                    this.secretList.addAll(secretList);

                    if (!this.secretList.isEmpty()) {
                        // Check if there might be more data
                        hasMoreData = deletedSecrets.size() == pageSize;
                        hasMoreEarlierData = hasMoreData;
                        hasMoreNewerData = false; // We start from the latest, so no newer data initially
                    } else {
                        // No data available
                        hasMoreData = false;
                        hasMoreEarlierData = false;
                        hasMoreNewerData = false;
                    }

                    updateUI();
                    loadSecretCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast(getString(R.string.error_loading_deleted_secrets, e.getMessage()));
                    updateUI();
                });
            }
        }).start();
    }

    private void updateButtonStates() {
        boolean hasSecrets = !(secretList ==null || secretList.isEmpty());
        boolean hasSelected = secretCardContainer != null && !secretCardContainer.getSelectedSecrets().isEmpty();

        recoverButton.setEnabled(hasSelected);
        recoverButton.setAlpha(hasSelected ? 1.0f : 0.5f);
        okButton.setEnabled(true);
        okButton.setAlpha(1.0f);
    }

    private void loadSecretCardList() {
        // Initialize the LinearLayout container for secret cards
        secretListContainer = findViewById(R.id.fragment_container);

        // Create SecretCardContainer with selection enabled (checkbox mode)
        secretCardContainer = new SecretCardContainer(this, secretListContainer, ChooseMode.CHOOSE_MULTI); // CHOOSE_MULTI = multiple selection
        secretCardContainer.setScrollView(secretScrollView);

        // Set up selection change listener
        secretCardContainer.setOnSecretListChangedListener(updatedSecretList -> {
            updateUI(); // This calls updateButtonStates, updateSelectAllCheckBoxState, and updateStatistics
        });

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
     * Sets up swipe refresh functionality for loading newer secrets
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer deleted secrets");
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

    private long lastScrollTime = 0;
    private static final int MIN_SCROLL_DISTANCE = 10; // Minimum scroll distance to trigger loading
    private static final long SCROLL_DELAY_MS = 300; // Delay before triggering load (0.3 seconds)

    /**
     * Sets up the scroll listener to detect when the user reaches the end of the list
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

                // Check if we've reached the top and user is scrolling down to load newer secrets
                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData) {
                    // Add delay to prevent accidental triggers
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerSecrets();
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
     * Loads newer deleted secrets (swipe down from top)
     */
    private void loadNewerSecrets() {
        if (isLoadingNewer || isLoadingEarlier) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch newer deleted secrets (timeAsc = true)
                List<String> latestSecretSortKeys = getLatestSecretSortKeys();
                Fcdsl fcdsl = secretManager.makeFcdsl(pageSize, latestSecretSortKeys, false, true);

                List<Secret> newerSecrets = secretManager.fetchPageSecrets(fcdsl);

                if (newerSecrets == null) {
                    runOnUiThread(() -> {
                        isLoadingNewer = false;
                        swipeRefreshLayout.setRefreshing(false);
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_newer_deleted_secrets));
                    });
                    return;
                }

                // Filter only deleted secrets
                List<Secret> filteredSecrets = new ArrayList<>();
                for (Secret secret : newerSecrets) {
                    if (Boolean.FALSE.equals(secret.getActive())) {
                        filteredSecrets.add(secret);
                    }
                }

                // Convert to Secret objects
                List<Secret> newerSecretDetails = secretManager.makeEntityDetails(filteredSecrets,true,this );
                runOnUiThread(() -> {
                    if (!newerSecretDetails.isEmpty()) {
                        int currentSize = secretList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newItemsCount = newerSecretDetails.size();
                        int newTotalSize = currentSize + newItemsCount;
                        int itemsToRemoveFromEnd = Math.max(0, newTotalSize - maxWindowSize);

                        Collections.reverse(newerSecretDetails);

                        if (secretCardContainer != null) {
                            secretCardContainer.addSecretCardsToBeginningWithBottomRemoval(
                                newerSecretDetails,
                                itemsToRemoveFromEnd,
                                FreerApplication.MAX_CONTAINER_SIZE
                            );

                            List<Secret> updatedSecretList = secretCardContainer.getSecretList();
                            if (updatedSecretList != null) {
                                secretList.clear();
                                secretList.addAll(updatedSecretList);
                            }
                        } else {
                            if (itemsToRemoveFromEnd > 0) {
                                for (int i = 0; i < itemsToRemoveFromEnd && !secretList.isEmpty(); i++) {
                                    secretList.remove(secretList.size() - 1);
                                }
                                hasMoreEarlierData = true;
                            }
                            secretList.addAll(0, newerSecretDetails);
                        }

                        if (itemsToRemoveFromEnd > 0) {
                            hasMoreEarlierData = true;
                        }

                        hasMoreNewerData = newerSecrets.size() >= pageSize;

                        ToastUtils.makeText(this, getString(R.string.loaded_newer_deleted_secrets, newerSecretDetails.size()));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.no_newer_deleted_secrets_found));
                    }

                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer deleted secrets: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                    ToastUtils.makeText(this, getString(R.string.error_loading_newer_deleted_secrets, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Loads earlier (older) deleted secrets (scroll to bottom)
     */
    private void loadEarlierSecrets() {
        if (isLoadingNewer || isLoadingEarlier || !hasMoreEarlierData) {
            return;
        }

        isLoadingEarlier = true;

        // Perform API operations on background thread
        new Thread(() -> {
            try {
                // Fetch earlier deleted secrets (timeAsc = false)
                List<String> earliestSecretSortKeys = getEarliestSecretSortKeys();
                Fcdsl fcdsl = secretManager.makeFcdsl(pageSize, earliestSecretSortKeys, false, false);

                List<Secret> earlierSecrets = secretManager.fetchPageSecrets(fcdsl);

                if (earlierSecrets == null) {
                    runOnUiThread(() -> {
                        isLoadingEarlier = false;
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_earlier_deleted_secrets));
                    });
                    return;
                }

                // Filter only deleted secrets
                List<Secret> filteredSecrets = new ArrayList<>();
                for (Secret secret : earlierSecrets) {
                    if (Boolean.FALSE.equals(secret.getActive())) {
                        filteredSecrets.add(secret);
                    }
                }

                // Convert to Secret objects
                List<Secret> earlierSecretDetails = secretManager.makeEntityDetails(filteredSecrets,true, this);

                runOnUiThread(() -> {
                    if (!earlierSecretDetails.isEmpty()) {
                        int currentSize = secretList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newItemsCount = earlierSecretDetails.size();
                        int newTotalSize = currentSize + newItemsCount;
                        int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - maxWindowSize);

                        if (secretCardContainer != null) {
                            secretCardContainer.addSecretCardsToEndWithTopRemoval(
                                earlierSecretDetails,
                                itemsToRemoveFromBeginning,
                                FreerApplication.MAX_CONTAINER_SIZE
                            );

                            List<Secret> updatedSecretList = secretCardContainer.getSecretList();
                            if (updatedSecretList != null) {
                                secretList.clear();
                                secretList.addAll(updatedSecretList);
                            }
                        } else {
                            if (itemsToRemoveFromBeginning > 0) {
                                for (int i = 0; i < itemsToRemoveFromBeginning && !secretList.isEmpty(); i++) {
                                    secretList.remove(0);
                                }
                                hasMoreNewerData = true;
                            }
                            secretList.addAll(earlierSecretDetails);
                        }

                        if (itemsToRemoveFromBeginning > 0) {
                            hasMoreNewerData = true;
                        }

                        if (earlierSecrets.size() < pageSize) {
                            hasMoreEarlierData = false;
                        }

                        ToastUtils.makeText(this, getString(R.string.loaded_earlier_deleted_secrets, earlierSecretDetails.size()));
                    } else {
                        hasMoreEarlierData = false;
                        ToastUtils.makeText(this, getString(R.string.no_more_earlier_deleted_secrets_found));
                    }

                    if (!hasMoreEarlierData && secretMoreButton != null) {
                        secretMoreButton.setVisibility(View.GONE);
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier deleted secrets: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                    ToastUtils.makeText(this, getString(R.string.error_loading_earlier_deleted_secrets, e.getMessage()));
                });
            }
        }).start();
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
        currentSortState = SortState.NONE;
        currentSortType = SortType.SAVE_TIME;
        setDefaultSortColors();
    }

    @Override
    protected void setupButtons() {
        // Set click listener for More button to load earlier data
        if (secretMoreButton != null) {
            secretMoreButton.setOnClickListener(v -> {
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    secretMoreButton.setEnabled(false);
                    secretMoreButton.setAlpha(0.5f);
                    loadEarlierSecrets();
                    new android.os.Handler().postDelayed(() -> {
                        if (secretMoreButton != null) {
                            secretMoreButton.setEnabled(true);
                            secretMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }

        // Set click listener for Recover button
        recoverButton.setOnClickListener(v -> {
            if (secretCardContainer == null) return;
            List<Secret> selectedSecrets = secretCardContainer.getSelectedSecrets();
            if (selectedSecrets.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_secrets_selected_for_recovery));
                return;
            }
            performRecoverOperation(selectedSecrets);
        });

        // Set click listener for On Chain button
        onChainButton.setOnClickListener(v -> {
            toggleMode();
        });

        // Set click listener for OK button
        okButton.setOnClickListener(v -> {
            finish(); // Just close the activity
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
        if (currentSortType == SortType.SAVE_TIME) {
            // Reset type sort icon and label
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
                typeSortIcon.setColorFilter(hintColor);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.TYPE) {
            // Reset save time sort icon and label
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

        if (currentSortType == SortType.SAVE_TIME && sortIcon != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            if (sortLabel != null) sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TYPE && typeSortIcon != null) {
            typeSortIcon.setImageResource(iconResource);
            typeSortIcon.setColorFilter(color);
            if (typeSortLabel != null) typeSortLabel.setTextColor(color);
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
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (secretStatisticsTextView == null) {
            return;
        }

        // Get container size
        int containerSize = secretList != null ? secretList.size() : 0;

        StringBuilder statsText = new StringBuilder();
        statsText.append("Above: ").append(containerSize);
        if(totalDeleted!=null)
            statsText.append("      Total: ").append(totalDeleted);

        secretStatisticsTextView.setText(statsText.toString());

        // Find statistics container and show/hide it
        LinearLayout statisticsContainer = findViewById(R.id.secret_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        // Update More button visibility based on hasMoreEarlierData
        if (secretMoreButton != null) {
            secretMoreButton.setVisibility(hasMoreEarlierData ? View.VISIBLE : View.GONE);
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

    private void performRecoverOperation(List<Secret> secretsToRecover) {
        // Check if we're in off-chain mode
        if (!isOnChainMode) {
            performOffChainRecovery(secretsToRecover);
            return;
        }

        // On-chain recovery logic
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        // Extract IDs for the recover operation
        List<String> secretIds = new ArrayList<>();
        for (Secret secret : secretsToRecover) {
            if (secret.getId() != null) {
                secretIds.add(secret.getId());
            }
        }

        if (secretIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_secret_ids_for_recovery));
            return;
        }

        // Create FEIP for recover operation
        String feipJson = makeRecoverSecretFeip(secretIds);

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
                            // Add recovered secrets to database
                            for (Secret secret : secretsToRecover) {
                                secret.setActive(false);
                                secret.markCarvePending();
                                secretManager.addSecretDetail(secret);
                            }
                            secretManager.commit();

                            // Remove recovered secrets from card container and main list
                            secretCardContainer.removeSelectedSecrets();
                            secretList.removeAll(secretsToRecover);

                            updateUI();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(SecretDeletedActivity.this, getString(R.string.failed_to_recover_secrets_on_chain, errorMessage));
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(SecretDeletedActivity.this, getString(R.string.cannot_sign_transaction_showing_unsigned_tx));
                            txSender.showUnsignedTxAsQR(SecretDeletedActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(SecretDeletedActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            // Recover locally without blockchain transaction
            for (Secret secret : secretsToRecover) {
                secret.setActive(false);
                secretManager.addSecretDetail(secret);
            }
            secretManager.commit();

            // Remove recovered secrets from card container and main list
            secretCardContainer.removeSelectedSecrets();
            secretList.removeAll(secretsToRecover);

            updateUI();
            ToastUtils.makeText(this, getString(R.string.secrets_recovered_locally));
        }
    }

    /**
     * Performs off-chain recovery by simply saving locally deleted secrets back to the database
     */
    private void performOffChainRecovery(List<Secret> secretsToRecover) {
        showWaitingDialog(getString(R.string.recovering_secrets_locally));

        // Perform recovery on background thread
        new Thread(() -> {
            try {
                // For off-chain recovery, simply save the secrets back to the database
                for (Secret secret : secretsToRecover) {
                    // Set the secret as active (recovered)
                    secret.setActive(true);

                    // Add the secret back to the database
                    secretManager.addSecretDetail(secret);
                }
                // Commit the changes to the database
                secretManager.commit();

                runOnUiThread(() -> {
                    // Remove recovered secrets from the local deleted list
                    for (Secret secret : secretsToRecover) {
                        secretManager.removeFromLocalDeletedList(secret);
                    }

                    // Remove recovered secrets from card container and main list
                    secretCardContainer.removeSelectedSecrets();
                    secretList.removeAll(secretsToRecover);

                    // Update the total count
                    totalDeleted = secretManager.getLocalDeletedListSize();

                    updateUI();
                    dismissWaitingDialog();
                    ToastUtils.makeText(SecretDeletedActivity.this, getString(R.string.secrets_recovered_successfully_off_chain));
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error during off-chain recovery: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(SecretDeletedActivity.this, getString(R.string.failed_to_recover_secrets, e.getMessage()));
                });
            }
        }).start();
    }

    private static String makeRecoverSecretFeip(List<String> secretIds) {
        Feip feip = Feip.fromName(SECRET);
        SecretOpData secretOpData = SecretOpData.makeRecover(secretIds);
        feip.setData(secretOpData);
        return feip.toJson();
    }

    /**
     * Helper method to create sort list from Secret object
     */
    private List<String> makeSortList(Secret secret) {
        return java.util.Arrays.asList(String.valueOf(secret.getLastHeight()), secret.getId());
    }

    private List<String> getLatestSecretSortKeys() {
        if (secretList.isEmpty()) {
            return null;
        }
        return makeSortList(secretList.get(0));
    }

    private List<String> getEarliestSecretSortKeys() {
        if (secretList.isEmpty()) {
            return null;
        }
        return makeSortList(secretList.get(secretList.size() - 1));
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
        clearSecretList();

        // Load appropriate data
        if (isOnChainMode) {
            loadInitialData(this);
        } else {
            loadLocalDeletedSecrets();
        }
    }

    /**
     * Clears the secret list and card container
     */
    private void clearSecretList() {
        if (secretCardContainer != null) {
            secretCardContainer.clearAll();
        }

        secretList.clear();

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
     * Loads deleted secrets from local deleted list
     */
    private void loadLocalDeletedSecrets() {
        // Show loading indicator
        showWaitingDialog(getString(R.string.loading_local_deleted_secrets));

        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Get deleted secrets from localDeletedList
                List<Secret> localDeletedSecrets = secretManager.getLocalDeletedList();

                runOnUiThread(() -> {
                    this.secretList.clear();
                    this.secretList.addAll(localDeletedSecrets);

                    // Set total count to local deleted list size
                    totalDeleted = secretManager.getLocalDeletedListSize();

                    updateUI();
                    loadSecretCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading local deleted secrets: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast(getString(R.string.error_loading_local_deleted_secrets, e.getMessage()));
                    updateUI();
                });
            }
        }).start();
    }
}