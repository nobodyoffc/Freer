package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.USED_CIDS;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fchData.Cid;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.fc_ajdk.utils.http.RequestMethod;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.network.ApipClient;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.KeyCardManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.util.ArrayList;
import java.util.List;

public class AddWatchedFidActivity extends BaseCryptoActivity {
    private static final String TAG = "AddWatchedFidActivity";
    
    private TextView searchResultsHint;
    private EditText fidSearchEditText;
    private Button searchButton;
    private LinearLayout searchResultsLayout;
    private Button clearButton;
    private Button cancelButton;
    private Button confirmButton;
    private Button moreButton;
    private TextView moreResultsHint;
    
    private String selectedFid = null;
    private Cid selectedCid = null;
    private View selectedCardView = null;
    private ApipClient apipClient;
    private KeyCardManager keyCardManager;
    private WaitingDialog waitingDialog;
    private Setting currentSetting;
    private List<Cid> currentSearchResults = new ArrayList<>();
    private String currentSearchTerm = null;
    private boolean isExactFidSearch = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_add_watched_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.add_watched_fid);
    }

    @Override
    protected void initializeViews() {
        initViews();
        setupData();
    }

    @Override
    protected void setupButtons() {
        setupListeners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }
    
    private void initViews() {
        searchResultsHint = findViewById(R.id.searchResultsHint);
        fidSearchEditText = findViewById(R.id.fidSearchEditText);
        searchButton = findViewById(R.id.searchButton);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        clearButton = findViewById(R.id.clearButton);
        cancelButton = findViewById(R.id.cancelButton);
        confirmButton = findViewById(R.id.addWatchedFidButton);
        moreButton = findViewById(R.id.moreButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);
        
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Initialize search KeyCardManager with single choice and clickToReturn enabled
        List<com.fc.freer.ui.MenuItem> menuItems = new ArrayList<>();
        menuItems.add(com.fc.freer.ui.MenuItem.createDetailMenuItem(this));
        keyCardManager = new KeyCardManager(this, searchResultsLayout, true, true, menuItems);
        keyCardManager.setOnKeyClickedListener(this::onFidSelected);
        keyCardManager.setOnMenuItemClickListener(this::onMenuItemClicked);
    }
    
    private void setupData() {
        try {
            // Get current setting
            currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                Toast.makeText(this, "Current setting not available", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            
            // Get APIP client
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter != null) {
                apipClient = (ApipClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.APIP);
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up data: %s", e.getMessage());
        }
    }
    
    private void setupListeners() {
        searchButton.setOnClickListener(v -> performSearch());
        clearButton.setOnClickListener(v -> clearAll());
        cancelButton.setOnClickListener(v -> finish());
        confirmButton.setOnClickListener(v -> confirmAddWatchedFid());
        moreButton.setOnClickListener(v -> loadMoreResults());
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
        
        // Hide keyboard when fidSearchEditText loses focus
        fidSearchEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard();
            }
        });
    }
    
    private void performSearch() {
        TimberLogger.d(TAG, "performSearch() called");

        String searchString = fidSearchEditText.getText().toString().trim();
        if (TextUtils.isEmpty(searchString)) {
            Toast.makeText(this, getString(R.string.please_enter_search_term), Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (apipClient == null) {
            Toast.makeText(this, getString(R.string.apip_client_not_available), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.searching));
        waitingDialog.show();
        
        // Store current search parameters
        currentSearchTerm = searchString;
        isExactFidSearch = isValidFidFormat(searchString);
        
        // Clear previous results
        currentSearchResults.clear();
        
        // Check if it's a valid FID format
        if (isExactFidSearch) {
            // Search for exact FID
            searchExactFid(searchString);
        } else {
            // Search for partial match in CID
            searchPartialCid(searchString, null);
        }
    }
    
    private boolean isValidFidFormat(String fid) {
        // Basic FID format validation - should start with F and be proper length
        return KeyTools.isGoodFid(fid);
    }
    
    private void searchExactFid(String fid) {
        new Thread(() -> {
            try {
                Cid cidInfo = apipClient.cidInfoById(fid, RequestMethod.POST, AuthType.FREE, this);
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (cidInfo != null) {
                        List<Cid> results = new ArrayList<>();
                        results.add(cidInfo);
                        currentSearchResults.clear();
                        currentSearchResults.addAll(results);
                        displaySearchResults(results);
                        moreButton.setVisibility(View.GONE); // No pagination for exact FID search
                    } else {
                        Toast.makeText(this, getString(R.string.fid_not_found), Toast.LENGTH_SHORT).show();
                        clearSearchResults();
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching exact FID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    Toast.makeText(this, getString(R.string.search_failed), Toast.LENGTH_SHORT).show();
                    clearSearchResults();
                });
            }
        }).start();
    }
    
    private void searchPartialCid(String searchString, List<String> afterValue) {
        new Thread(() -> {
            try {
                // Create Fcdsl for partial search in id or usedCids
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields(ID, USED_CIDS).addNewValue(searchString);
                fcdsl.setSize(String.valueOf(FreerApplication.DEFAULT_PAGE_SIZE));
                
                // Add 'after' parameter for pagination
                if (afterValue != null) {
                    fcdsl.setAfter(afterValue);
                }

                List<Cid> results = apipClient.cidSearch(fcdsl, RequestMethod.POST, AuthType.FREE, this);
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (results != null && !results.isEmpty()) {
                        if (afterValue == null) {
                            // New search - replace results
                            currentSearchResults.clear();
                            currentSearchResults.addAll(results);
                            displaySearchResults(currentSearchResults);
                        } else {
                            // Load more - append results
                            currentSearchResults.addAll(results);
                            appendSearchResults(results);
                        }
                        
                        // Show/hide More button based on result size
                        updateMoreButtonVisibility(results.size());
                    } else {
                        if (afterValue == null) {
                            TimberLogger.d(TAG, "No CID results found, showing toast");
                            Toast.makeText(this, getString(R.string.no_matching_cids_found), Toast.LENGTH_SHORT).show();
                            clearSearchResults();
                        } else {
                            // No more results
                            moreButton.setVisibility(View.GONE);
                            Toast.makeText(this, getString(R.string.no_more_results), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching partial CID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    Toast.makeText(this, getString(R.string.search_failed), Toast.LENGTH_SHORT).show();
                    clearSearchResults();
                });
            }
        }).start();
    }
    
    private void displaySearchResults(List<Cid> results) {
        // Clear existing results and selection
        keyCardManager.clearAll();
        selectedCardView = null;
        
        // Show search results hint
        searchResultsHint.setVisibility(View.VISIBLE);
        
        // Hide more results hint when displaying new results
        moreResultsHint.setVisibility(View.GONE);
        
        // Convert Cid objects to KeyInfo objects and add them to KeyCardManager
        for (Cid cid : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(cid);
            if (keyInfo != null) {
                // Don't set label - let CID value show in the CID field instead
                keyCardManager.addKeyCard(keyInfo);
            }
        }
    }
    
    private void clearSearchResults() {
        TimberLogger.d(TAG, "clearSearchResults() called");
        keyCardManager.clearAll();
        searchResultsHint.setVisibility(View.GONE);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
    }
    
    private void clearAll() {
        // Clear the search input
        fidSearchEditText.setText("");
        
        // Clear search results
        clearSearchResults();
        
        // Reset selection state
        selectedFid = null;
        selectedCid = null;
        selectedCardView = null;
        
        // Disable add button
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Clear search parameters
        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;
        
        Toast.makeText(this, getString(R.string.cleared), Toast.LENGTH_SHORT).show();
    }
    
    private void appendSearchResults(List<Cid> newResults) {
        // Convert Cid objects to KeyInfo objects and add them to KeyCardManager
        for (Cid cid : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(cid);
            if (keyInfo != null) {
                keyCardManager.addKeyCard(keyInfo);
            }
        }
    }
    
    private void updateMoreButtonVisibility(int resultsSize) {
        // Show More button if we got the full page size (20), indicating there might be more results
        if (resultsSize >= FreerApplication.DEFAULT_PAGE_SIZE && !isExactFidSearch) {
            moreButton.setVisibility(View.VISIBLE);
            moreButton.setEnabled(true);
            moreButton.setAlpha(1.0f);
            
            // Show hint text with remaining results count
            updateMoreResultsHint();
        } else {
            moreButton.setVisibility(View.GONE);
            moreResultsHint.setVisibility(View.GONE);
        }
    }
    
    private void loadMoreResults() {
        if (currentSearchTerm == null || apipClient == null) {
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();
        
        try {
            // Get the 'last' value from the last response
            List<String> afterValue = null;
            if (apipClient.getApiEvent() != null && 
                apipClient.getApiEvent().getResponseBody() != null && 
                apipClient.getApiEvent().getResponseBody().getLast() != null) {
                afterValue = apipClient.getApiEvent().getResponseBody().getLast();
            }
            
            if (afterValue != null) {
                searchPartialCid(currentSearchTerm, afterValue);
            } else {
                dismissWaitingDialog();
                Toast.makeText(this, getString(R.string.no_more_results), Toast.LENGTH_SHORT).show();
                moreButton.setVisibility(View.GONE);
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more results: %s", e.getMessage());
            dismissWaitingDialog();
            Toast.makeText(this, getString(R.string.failed_to_load_more), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void onFidSelected(KeyInfo keyInfo) {
        selectedFid = keyInfo.getId();
        
        // Convert KeyInfo back to Cid for compatibility with existing logic
        selectedCid = new Cid();
        selectedCid.setId(keyInfo.getId());
        selectedCid.setCid(keyInfo.getCid());
        selectedCid.setPubkey(keyInfo.getPubkey());

    // Update card selection highlighting
        updateCardSelection(keyInfo);
   // Show selected FID in the search EditText with middle truncation

        String displayText = StringUtils.omitMiddle(selectedFid, 20);
        if (keyInfo.getCid() != null && !keyInfo.getCid().trim().isEmpty()) {
            displayText = keyInfo.getCid() + ": " + displayText;
        }
        fidSearchEditText.setText(displayText);
        confirmButton.setEnabled(true);
        confirmButton.setAlpha(1.0f);
        Toast.makeText(this, "FID selected", Toast.LENGTH_SHORT).show();
    }
    
    private void onMenuItemClicked(String menuItemId, KeyInfo keyInfo) {
        if ("detail".equals(menuItemId)) {
            showKeyInfoDetail(keyInfo);
        }
    }
    
    private void showKeyInfoDetail(KeyInfo keyInfo) {
        try {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, keyInfo.toJson());
            intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, KeyInfo.class.getName());
            startActivity(intent);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing KeyInfo detail: %s", e.getMessage());
            Toast.makeText(this, getString(R.string.error_showing_detail), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void confirmAddWatchedFid() {
        if (selectedFid == null || selectedCid == null) {
            Toast.makeText(this, "No FID selected", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // Check if FID is already watched
            List<KeyInfo> watchedList = currentSetting.getWatchedKeyInfoList();
            if (watchedList != null) {
                for (KeyInfo keyInfo : watchedList) {
                    if (selectedFid.equals(keyInfo.getId())) {
                        Toast.makeText(this, getString(R.string.fid_already_watched), Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }
            
            // Create KeyInfo from selected Cid
            KeyInfo keyInfoToAdd = KeyInfo.fromCid(selectedCid);
            if (keyInfoToAdd != null) {
                // Mark as watch-only
                keyInfoToAdd.setWatchOnly(true);
                keyInfoToAdd.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(),DateUtils.LONG_FORMAT));
                
                // Add to current setting's watched list
                currentSetting.addWatchedKeyInfo(keyInfoToAdd);
                
                // Save settings
                SettingManager settingManager = SettingManager.getInstance();
                settingManager.saveSettings(this, currentSetting);
                
                TimberLogger.d(TAG, "Successfully added watched FID: %s", selectedFid);
                Toast.makeText(this, getString(R.string.watched_fid_added_successfully), Toast.LENGTH_SHORT).show();
                
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "Failed to create KeyInfo from selected FID", Toast.LENGTH_SHORT).show();
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding watched FID: %s", e.getMessage());
            Toast.makeText(this, getString(R.string.failed_to_add_watched_fid) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onDestroy() {
        dismissWaitingDialog();
        super.onDestroy();
    }
    
    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
            waitingDialog = null;
        }
    }
    
    private void updateCardSelection(KeyInfo selectedKeyInfo) {
        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.key_card_background);
        }
        
        // Find and highlight the selected card
        for (int i = 0; i < searchResultsLayout.getChildCount(); i++) {
            View cardView = searchResultsLayout.getChildAt(i);
            TextView keyIdView = cardView.findViewById(R.id.key_id);
            if (keyIdView != null && selectedKeyInfo.getId().equals(keyIdView.getText().toString())) {
                cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;
                break;
            }
        }
        confirmButton.setEnabled(true);
        confirmButton.setAlpha(1.0f);
    }
    
    private void updateMoreResultsHint() {
        try {
            if (apipClient != null && 
                apipClient.getApiEvent() != null && 
                apipClient.getApiEvent().getResponseBody() != null && 
                apipClient.getApiEvent().getResponseBody().getTotal() != null) {
                
                long totalResults = apipClient.getApiEvent().getResponseBody().getTotal();
                int currentResultsCount = currentSearchResults.size();
                long remainingResults = totalResults - currentResultsCount;
                
                if (remainingResults > 0) {
                    String hintText = String.format("%d left", remainingResults);
                    moreResultsHint.setText(hintText);
                    moreResultsHint.setVisibility(View.VISIBLE);
                } else {
                    moreResultsHint.setVisibility(View.GONE);
                }
            } else {
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating more results hint: %s", e.getMessage());
            moreResultsHint.setVisibility(View.GONE);
        }
    }
}