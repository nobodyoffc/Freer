package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.FREER;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.USED_CIDS;

import android.content.Intent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.ui.WaitingDialog;
import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.util.ArrayList;
import java.util.List;

public class AddWatchedFidActivity extends BaseCryptoActivity {
    private static final String TAG = "AddWatchedFidActivity";
    
    private TextView searchResultsHint;
    private EditText fidSearchEditText;
    private View searchIcon;
    private View clearSearchIcon;
    private LinearLayout searchResultsLayout;
    private ImageButton confirmButton;
    private ImageButton moreButton;
    private TextView moreResultsHint;
    
    private String selectedFid = null;
    private Freer selectedFreer = null;
    private View selectedCardView = null;
    private FapiClient fapiClient;
    private KeyCardContainer keyCardContainer;
    private WaitingDialog waitingDialog;
    private Setting currentSetting;
    private List<Freer> currentSearchResults = new ArrayList<>();
    private String currentSearchTerm = null;
    private boolean isExactFidSearch = false;
    private List<String> lastValue;

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
    protected void setupBackButton() {
        backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan results if needed
    }
    
    private void initViews() {
        searchResultsHint = findViewById(R.id.searchResultsHint);
        fidSearchEditText = findViewById(R.id.fidSearchEditText);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        confirmButton = findViewById(R.id.addWatchedFidButton);
        moreButton = findViewById(R.id.moreButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);
        
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        List<com.fc.freer.ui.MenuItem> menuItems = new ArrayList<>();
        menuItems.add(com.fc.freer.ui.MenuItem.createDetailMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, ChooseMode.CHOOSE_ONE_RETURN, menuItems, true);
        keyCardContainer.setOnKeyClickedListener(this::onFidSelected);
        keyCardContainer.setOnMenuItemClickListener(this::onMenuItemClicked);
    }
    
    private void setupData() {
        try {
            // Get current setting
            currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                ToastUtils.makeText(this, getString(R.string.current_setting_not_available));
                finish();
                return;
            }
            
            // Get FAPI client
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter != null) {
                fapiClient = (FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up data: %s", e.getMessage());
        }
    }
    
    private void setupListeners() {
        searchIcon.setOnClickListener(v -> {
            hideKeyboard();
            performSearch();
        });
        clearSearchIcon.setOnClickListener(v -> {
            fidSearchEditText.setText("");
            hideKeyboard();
            clearAll();
        });
        confirmButton.setOnClickListener(v -> {
            hideKeyboard();
            confirmAddWatchedFid();
        });
        moreButton.setOnClickListener(v -> loadMoreResults());
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);

        fidSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

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
            ToastUtils.makeText(this, getString(R.string.please_enter_search_term));
            return;
        }
        
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.apip_client_not_available));
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
                Freer freerInfo = fapiClient.getFreer(fid);
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (freerInfo != null) {
                        List<Freer> results = new ArrayList<>();
                        results.add(freerInfo);
                        currentSearchResults.clear();
                        currentSearchResults.addAll(results);
                        displaySearchResults(results);
                        moreButton.setVisibility(View.GONE); // No pagination for exact FID search
                    } else {
                        ToastUtils.makeText(this, getString(R.string.fid_not_found));
                        clearSearchResults();
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching exact FID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed));
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
                fcdsl.addSort(LAST_HEIGHT,DESC).addSort(ID,DESC);
                // Add 'after' parameter for pagination
                if (afterValue != null) {
                    fcdsl.setAfter(afterValue);
                }


                List<Freer> results = fapiClient.entitySearch(FREER,fcdsl,Freer.class);


                if (fapiClient.getLastResponse() != null &&
                        fapiClient.getLastResponse().getLast() != null) {
                    lastValue = fapiClient.getLastResponse().getLast();
                }

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
                            ToastUtils.makeText(this, getString(R.string.no_matching_cids_found));
                            clearSearchResults();
                        } else {
                            // No more results
                            moreButton.setVisibility(View.GONE);
                            ToastUtils.makeText(this, getString(R.string.no_more_results));
                        }
                    }
                });
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching partial CID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    clearSearchResults();
                });
            }
        }).start();
    }
    
    private void displaySearchResults(List<Freer> results) {
        // Clear existing results and selection
        keyCardContainer.clearAll();
        selectedCardView = null;
        
        // Show search results hint
        searchResultsHint.setVisibility(View.VISIBLE);
        
        // Hide more results hint when displaying new results
        moreResultsHint.setVisibility(View.GONE);
        
        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                // Don't set label - let CID value show in the CID field instead
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
    }
    
    private void clearSearchResults() {
        TimberLogger.d(TAG, "clearSearchResults() called");
        keyCardContainer.clearAll();
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
        selectedFreer = null;
        selectedCardView = null;
        
        // Disable add button
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Clear search parameters
        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;
        
        ToastUtils.makeText(this, getString(R.string.cleared));
    }
    
    private void appendSearchResults(List<Freer> newResults) {
        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                keyCardContainer.addKeyCard(keyInfo);
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
        if (currentSearchTerm == null || fapiClient == null) {
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();
        
        try {
            
            if (lastValue != null) {
                searchPartialCid(currentSearchTerm, lastValue);
            } else {
                dismissWaitingDialog();
                ToastUtils.makeText(this, getString(R.string.no_more_results));
                moreButton.setVisibility(View.GONE);
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more results: %s", e.getMessage());
            dismissWaitingDialog();
            ToastUtils.makeText(this, getString(R.string.failed_to_load_more));
        }
    }
    
    private void onFidSelected(KeyInfo keyInfo) {
        selectedFid = keyInfo.getId();
        
        // Convert KeyInfo back to Freer for compatibility with existing logic
        selectedFreer = new Freer();
        selectedFreer.setId(keyInfo.getId());
        selectedFreer.setCid(keyInfo.getCid());
        selectedFreer.setPubkey(keyInfo.getPubkey());

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
        ToastUtils.makeText(this, getString(R.string.fid_selected));
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
            ToastUtils.makeText(this, getString(R.string.error_showing_detail));
        }
    }
    
    private void confirmAddWatchedFid() {
        if (selectedFid == null || selectedFreer == null) {
            ToastUtils.makeText(this, getString(R.string.no_fid_selected));
            return;
        }
        
        try {
            // Check if FID is already watched
            List<KeyInfo> watchedList = currentSetting.getWatchedKeyInfoList();
            if (watchedList != null) {
                for (KeyInfo keyInfo : watchedList) {
                    if (selectedFid.equals(keyInfo.getId())) {
                        ToastUtils.makeText(this, getString(R.string.fid_already_watched));
                        return;
                    }
                }
            }
            
            // Create KeyInfo from selected Freer
            KeyInfo keyInfoToAdd = KeyInfo.fromCid(selectedFreer);
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
                ToastUtils.makeText(this, getString(R.string.watched_fid_added_successfully));
                
                setResult(RESULT_OK);
                finish();
            } else {
                ToastUtils.makeText(this, getString(R.string.failed_to_create_keyinfo_from_fid));
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding watched FID: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.failed_to_add_watched_fid) + ": " + e.getMessage());
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
            if (fapiClient != null && 
                fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getTotal() != null) {
                
                long totalResults = fapiClient.getLastResponse().getTotal();
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