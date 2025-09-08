package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.fc_ajdk.utils.http.RequestMethod;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.ui.FcEntityListFragment;
import com.fc.freer.ui.PopupMenuHelper;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.manager.CashManager;
import com.fc.freer.multisign.CreateMultisignTxActivity;

import java.util.ArrayList;
import java.util.List;

public class CashActivity extends BaseCryptoActivity {
    private static final String TAG = "Cash";
    private static final int DEFAULT_PAGE_SIZE = 50; // Default page size for loading cash
    private static final int REQUEST_CREATE_TX = 1001; // Request code for CreateTxActivity
    
    public static final String EXTRA_SELECT_MODE = "select_mode";
    public static final String EXTRA_SELECTED_CASH = "selected_cash";
    
    private FcEntityListFragment<Cash> entityListFragment;
    protected CashManager cashManager;

    private Button updateButton;
    private Button reorganizeButton;
    private Button sendButton;
    private PopupMenuHelper popupMenuHelper;
    
    // Summary card UI elements
    private TextView selectedCashCountTextView;
    private TextView selectedCashAmountTextView;
    private TextView selectedCashCdTextView;
    
    private final List<Cash> cashList = new ArrayList<>();
    private Long lastIndex = null;
    private boolean isLoadingMore = false;
    private boolean hasMoreData = true;
    private LocalDB<Cash> cashDB;

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

        // Check if we're in selection mode
        boolean isSelectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
        if (isSelectMode) {
            // Change the send button to return button
            sendButton = findViewById(R.id.send_button);
            if (sendButton != null) {
                sendButton.setText(R.string.return_text);
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
        updateButton = findViewById(R.id.update_button);
        reorganizeButton = findViewById(R.id.reorganize_button);
        sendButton = findViewById(R.id.send_button);
        
        // Initialize summary card UI elements
        selectedCashCountTextView = findViewById(R.id.selectedCashCount);
        selectedCashAmountTextView = findViewById(R.id.selectedCashAmount);
        selectedCashCdTextView = findViewById(R.id.selectedCashCd);
        
        // Initialize summary card with zeros
        updateSummaryCard();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // CashActivity doesn't use QR scanning functionality for main activity
    }

    /**
     * Loads the initial page of Cash objects
     */
    private void loadInitialData() {
        // Show loading indicator
        try {
            // Get the first page of Cash objects from the latest
            List<Cash> newList = cashManager.getPaginatedCashes(DEFAULT_PAGE_SIZE, null, false);

            if(newList == null || newList.isEmpty()) {
                dismissWaitingDialog();
                showToast(getString(R.string.no_cash_found));
                updateButtonStates();
                return;
            }

            TimberLogger.i(TAG, "Initial load: %d cash loaded", newList.size());

            cashList.clear();
            cashList.addAll(newList);

            // If we have data, get the last index for pagination
            if (!cashList.isEmpty()) {
                // Get the last item's index
                lastIndex = cashManager.getIndexById(cashList.get(cashList.size() - 1).getId());

                // Check if there's more data to load
                hasMoreData = lastIndex > 1;
            } else {
                // No data available
                hasMoreData = false;
                lastIndex = null;
            }

            updateButtonStates();
            loadListFragment();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
            showToast(getString(R.string.error_loading_cash, e.getMessage()));
        }
    }

    private void updateButtonStates() {
        boolean hasCash = !(cashList==null || cashList.isEmpty());
        
        updateButton.setEnabled(true); // Update button is always enabled
        updateButton.setAlpha(1.0f);
        reorganizeButton.setEnabled(hasCash);
        reorganizeButton.setAlpha(hasCash ? 1.0f : 0.5f);
        sendButton.setEnabled(hasCash);
        sendButton.setAlpha(hasCash ? 1.0f : 0.5f);
    }

    private void loadListFragment() {
        // Create fragment with the initial Cash objects
        entityListFragment = FcEntityListFragment.newInstance(cashList, Cash.class, false);
        
        // Add fragment to container
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, entityListFragment)
                .commit();

        // Set up selection change listener for the fragment
        entityListFragment.setOnSelectionChangeListener(() -> {
            updateButtonStates();
            updateSummaryCard(); // Update summary when selection changes
        });

        // Update button states after fragment is loaded
        updateButtonStates();
        // Update summary card after fragment is loaded
        updateSummaryCard();
    }

    /**
     * Sets up the scroll listener to detect when the user reaches the end of the list
     */
    private void setupScrollListener() {
        // Get the ScrollView from the fragment
        View fragmentView = entityListFragment.getView();
        if (fragmentView != null) {
            // Find the ScrollView in the fragment's view hierarchy
            View scrollView = findScrollView(fragmentView);
            if (scrollView != null) {
                // Add scroll listener
                scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    boolean atBottom = isAtBottom(scrollView);
                    boolean scrollingUp = scrollY < oldScrollY;
                    
                    // Check if we've reached the bottom of the scroll view and user is scrolling up
                    if (atBottom && scrollingUp && !isLoadingMore && hasMoreData) {
                        TimberLogger.i(TAG, "Loading more data - reached bottom while scrolling up");
                        loadMoreData();
                    }
                });
            }
        }
    }
    
    /**
     * Recursively finds a ScrollView in the view hierarchy
     */
    private View findScrollView(View view) {
        if (view instanceof ScrollView) {
            return view;
        }
        
        if (view instanceof ViewGroup viewGroup) {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                View result = findScrollView(child);
                if (result != null) {
                    return result;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Checks if the scroll view is at the bottom
     */
    private boolean isAtBottom(View scrollView) {
        if (scrollView instanceof ScrollView sv) {
            int scrollY = sv.getScrollY();
            int height = sv.getHeight();
            int viewHeight = sv.getChildAt(0).getHeight();
            
            // If we're within 100 pixels of the bottom, consider it at the bottom
            return (viewHeight - scrollY - height) <= 100;
        }
        return false;
    }
    
    /**
     * Loads more data when the user scrolls to the end of the list
     */
    private void loadMoreData() {
        if (isLoadingMore || !hasMoreData) {
            return;
        }
        
        isLoadingMore = true;
        TimberLogger.i(TAG, "Loading more data, lastIndex: %d", lastIndex);
        
        // Show loading indicator
        showWaitingDialog("Loading more cash...");
        
        // Perform database operations on background thread
        new Thread(() -> {
            try {
                // Get the next page of Cash objects
                List<Cash> moreCash = cashManager.getPaginatedCashes(DEFAULT_PAGE_SIZE, lastIndex, false);
                
                runOnUiThread(() -> {
                    if (moreCash != null && !moreCash.isEmpty()) {
                        // Update the lastIndex for the next page
                        lastIndex = cashManager.getIndexById(moreCash.get(moreCash.size() - 1).getId());
                        
                        // Check if there's more data to load
                        hasMoreData = lastIndex > 1;
                        
                        // Add the new items to the list
                        cashList.addAll(moreCash);
                        
                        // Update the fragment's list
                        entityListFragment.updateList(cashList);
                        updateButtonStates();
                        updateSummaryCard();

                        Toast.makeText(this, getString(R.string.cash_loaded, moreCash.size()), Toast.LENGTH_SHORT).show();
                        
                        if (!hasMoreData) {
                            Toast.makeText(this, R.string.no_more_cash, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        // No more data to load
                        hasMoreData = false;
                        Toast.makeText(this, getString(R.string.no_more_cash), Toast.LENGTH_LONG).show();
                    }
                    
                    isLoadingMore = false;
                    dismissWaitingDialog();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading more data: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingMore = false;
                    dismissWaitingDialog();
                    Toast.makeText(this, getString(R.string.error_loading_more_cash) + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    protected void setupButtons() {
        // Set click listeners
        updateButton.setOnClickListener(v -> {
            // Show waiting dialog
            showWaitingDialog("Updating cash...");
            
            // Update valid cash on background thread
            new Thread(() -> {
                try {
                    // Update valid cash immediately without requiring selection
                    int added = cashManager.updateValidCash(this, AuthType.FC_SIGN_BODY, RequestMethod.POST, false);
                    
                    // Commit changes to disk
                    cashManager.commit();
                    
                    runOnUiThread(() -> {
                        // Refresh the entire list to reflect updates
                        refreshList();
                        
                        // Show success message
                        if(added==-1) Toast.makeText(this, getString(R.string.failed_to_update_cash_db),FreerApplication.TOAST_LASTING).show();
                        else Toast.makeText(this, getString(R.string.added_new_cash,added),FreerApplication.TOAST_LASTING).show();
                        dismissWaitingDialog();
                    });
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error updating cash: %s", e.getMessage());
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error updating cash: " + e.getMessage(), FreerApplication.TOAST_LASTING).show();
                        dismissWaitingDialog();
                    });
                }
            }).start();
        });

        reorganizeButton.setOnClickListener(v -> {
            // TODO: Implement reorganize functionality
            Toast.makeText(this, "Reorganize functionality will be added later", Toast.LENGTH_SHORT).show();
        });

        sendButton.setOnClickListener(v -> {
            List<Cash> chosenObjects = entityListFragment.getSelectedObjects();
            
            // Check if we're in selection mode
            boolean isSelectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
            if (isSelectMode) {
                if (chosenObjects.isEmpty()) {
                    Toast.makeText(this, R.string.no_items_selected , FreerApplication.TOAST_LASTING).show();
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

            // Normal send mode - validate that all cash have the same owner FID or null
            String ownerFid = null;
            for (Cash cash : chosenObjects) {
                String cashOwner = cash.getOwner();
                if (cashOwner != null && !cashOwner.isEmpty()) {
                    if (ownerFid == null) {
                        ownerFid = cashOwner;
                    } else if (!ownerFid.equals(cashOwner)) {
                        Toast.makeText(this, getString(R.string.all_cash_must_have_same_owner), FreerApplication.TOAST_LASTING).show();
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
            
            // Check if sender FID starts with '3' (multisign address)
            if (ownerFid != null && ownerFid.startsWith("3")) {
                // Start CreateMultisignTxActivity for multisign transaction
                Intent intent = new Intent(CashActivity.this, CreateMultisignTxActivity.class);
                intent.putExtra(CreateMultisignTxActivity.EXTRA_TX_INFO_JSON, rawTxInfoJson);
                startActivityForResult(intent, REQUEST_CREATE_TX);
            } else {
                // Start CreateTxActivity for normal transaction
                Intent intent = new Intent(CashActivity.this, CreateTxActivity.class);
                intent.putExtra(CreateTxActivity.EXTRA_TX_INFO_JSON, rawTxInfoJson);
                startActivityForResult(intent, REQUEST_CREATE_TX);
            }
        });

    }
    
    private void refreshList() {
        // Clear existing list
        cashList.clear();
        
        // Reset pagination variables
        lastIndex = null;
        hasMoreData = true;
        
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
        
        // Wait for fragment view to be ready before setting up scroll listener
        if (entityListFragment != null && entityListFragment.getView() != null) {
            setupScrollListener();
        } else {
            // Try again after a short delay to ensure fragment view is ready
            new android.os.Handler().postDelayed(() -> {
                if (entityListFragment != null && entityListFragment.getView() != null) {
                    setupScrollListener();
                }
            }, 100);
        }
    }

    /**
     * Updates the summary card with totals from selected cash list
     */
    private void updateSummaryCard() {
        List<Cash> selectedCashList = entityListFragment != null ? entityListFragment.getSelectedObjects() : new ArrayList<>();
        
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
            String formattedAmount = FchUtils.formatSatoshiValue(totalValue);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CREATE_TX) {
            // Refresh cash list when returning from CreateTxActivity
            // This is needed because cash may have been added or removed during transaction creation/signing
            TimberLogger.i(TAG, "Returned from CreateTxActivity, refreshing cash list");
            refreshList();
        }
    }
} 