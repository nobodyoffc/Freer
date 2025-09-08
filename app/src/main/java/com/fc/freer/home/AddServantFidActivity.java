package com.fc.freer.home;

import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.data.fchData.Cid;
import com.fc.fc_ajdk.data.feipData.CidHist;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.fc_ajdk.utils.http.RequestMethod;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.network.ApipClient;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.MenuItem;
import com.fc.freer.ui.MenuItemType;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.KeyCardManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AddServantFidActivity extends BaseCryptoActivity {
    private static final String TAG = "AddServantFidActivity";
    
    private TextView servantResultsHint;
    private LinearLayout servantResultsLayout;
    private Button refreshButton;
    private Button cancelButton;
    private Button confirmButton;
    private Button moreButton;
    private TextView moreResultsHint;
    private CheckBox selectAllCheckbox;
    private TextView selectAllLabel;
    private List<KeyInfo> selectedServantFids = new ArrayList<>();
    private ApipClient apipClient;
    private KeyCardManager keyCardManager;
    private WaitingDialog waitingDialog;
    private Setting currentSetting;
    private List<CidHist> currentServantResults = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_add_servant_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.add_servant_fid);
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
        servantResultsHint = findViewById(R.id.servantResultsHint);
        refreshButton = findViewById(R.id.refreshButton);
        servantResultsLayout = findViewById(R.id.servantResultsLayout);
        cancelButton = findViewById(R.id.cancelButton);
        confirmButton = findViewById(R.id.addServantFidButton);
        moreButton = findViewById(R.id.moreButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox);
        selectAllLabel = findViewById(R.id.selectAllLabel);

        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Initialize KeyCardManager with checkbox selection (multiple choice)
        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(new MenuItem("detail", getString(R.string.detail), MenuItemType.DETAIL));
        keyCardManager = new KeyCardManager(this, servantResultsLayout, false, false, menuItems);
        keyCardManager.setOnKeyListChangedListener(this::onServantSelectionChanged);
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
        refreshButton.setOnClickListener(v -> loadMyServants());
        cancelButton.setOnClickListener(v -> finish());
        confirmButton.setOnClickListener(v -> confirmAddServantFids());
        moreButton.setOnClickListener(v -> loadMoreServants());
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
        selectAllCheckbox.setVisibility(View.GONE);
        
        // Auto-load servants on activity start
        loadMyServants();
    }
    
    private void loadMyServants() {
        TimberLogger.d(TAG, "loadMyServants() called");

        if (apipClient == null) {
            Toast.makeText(this, getString(R.string.apip_client_not_available), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.loading_servants));
        waitingDialog.show();
        
        // Fetch my servants from API
        fetchMyServantsFromApi();
    }
    
    private void fetchMyServantsFromApi() {
        fetchMyServantsFromApi(null);
    }
    
    private void fetchMyServantsFromApi(List<String> afterValue) {
        new Thread(() -> {
            try {
                FidManager fidManager = FidManager.getInstance();
                // Use myServants API to get CidHist list
                List<CidHist> results = apipClient.myServants(fidManager.getLiveFid(), RequestMethod.POST, AuthType.FC_SIGN_BODY, this);
                
                if (results == null || results.isEmpty()) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        if (afterValue == null) {
                            Toast.makeText(this, getString(R.string.fetch_servants_failed), Toast.LENGTH_SHORT).show();
                            clearServantResults();
                        } else {
                            // No more results
                            moreButton.setVisibility(View.GONE);
                            Toast.makeText(this, getString(R.string.no_more_results), Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                
                // Store results for pagination
                if (afterValue == null) {
                    currentServantResults.clear();
                }
                currentServantResults.addAll(results);
                
                // Extract signer FIDs from CidHist results
                List<String> signerFids = new ArrayList<>();
                for (CidHist cidHist : results) {
                    if (cidHist.getSigner() != null && !cidHist.getSigner().trim().isEmpty()) {
                        signerFids.add(cidHist.getSigner());
                    }
                }
                
                if (signerFids.isEmpty()) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        if (afterValue == null) {
                            Toast.makeText(this, getString(R.string.fetch_servants_failed), Toast.LENGTH_SHORT).show();
                            clearServantResults();
                        } else {
                            moreButton.setVisibility(View.GONE);
                            Toast.makeText(this, getString(R.string.no_more_results), Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                
                // Get CID info for all signer FIDs
                Map<String, Cid> cidInfoMap = apipClient.cidInfoByIds(RequestMethod.POST, AuthType.FC_SIGN_BODY, this, signerFids.toArray(new String[0]));

                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (cidInfoMap != null && !cidInfoMap.isEmpty()) {
                        if (afterValue == null) {
                            displayServantResults(new ArrayList<>(cidInfoMap.values()));
                        } else {
                            appendServantResults(new ArrayList<>(cidInfoMap.values()));
                        }
                        
                        // Show/hide More button based on result size
                        updateMoreButtonVisibility(results.size());
                    } else {
                        if (afterValue == null) {
                            TimberLogger.d(TAG, "No servant results found, showing toast");
                            Toast.makeText(this, getString(R.string.fetch_servants_failed), Toast.LENGTH_SHORT).show();
                            clearServantResults();
                        } else {
                            moreButton.setVisibility(View.GONE);
                            Toast.makeText(this, getString(R.string.no_more_results), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching my servants: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    Toast.makeText(this, getString(R.string.fetch_servants_failed), Toast.LENGTH_SHORT).show();
                    if (afterValue == null) {
                        clearServantResults();
                    }
                });
            }
        }).start();
    }
    
    private void displayServantResults(List<Cid> results) {
        // Clear existing results and selection
        keyCardManager.clearAll();
        selectedServantFids.clear();
        
        // Show servant results hint
        servantResultsHint.setVisibility(View.VISIBLE);
        
        // Hide more results hint when displaying new results
        moreResultsHint.setVisibility(View.GONE);
        
        // Reset select all checkbox
        selectAllCheckbox.setOnCheckedChangeListener(null);
        selectAllCheckbox.setChecked(false);
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
        
        // Filter out already added servant FIDs
        List<KeyInfo> existingServants = currentSetting.getServantKeyInfoList();
        Set<String> existingFids = new HashSet<>();
        if (existingServants != null) {
            for (KeyInfo keyInfo : existingServants) {
                existingFids.add(keyInfo.getId());
            }
        }
        
        // Convert Cid objects to KeyInfo objects and add them to KeyCardManager
        for (Cid cid : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(cid);
            if (keyInfo != null && !existingFids.contains(keyInfo.getId())) {
                keyCardManager.addKeyCard(keyInfo);
            }
        }
        
        // Show/hide select all checkbox based on whether there are results
        if (keyCardManager.getKeyInfoList().isEmpty()) {
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.all_servants_already_added));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            servantResultsLayout.addView(emptyMessageView);
            selectAllCheckbox.setVisibility(View.GONE);
            selectAllLabel.setVisibility(View.GONE);
        } else {
            selectAllCheckbox.setVisibility(View.VISIBLE);
            selectAllLabel.setVisibility(View.VISIBLE);
        }
    }
    
    private void clearServantResults() {
        TimberLogger.d(TAG, "clearServantResults() called");
        keyCardManager.clearAll();
        servantResultsHint.setVisibility(View.GONE);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
        selectAllCheckbox.setVisibility(View.GONE);
        selectAllCheckbox.setOnCheckedChangeListener(null);
        selectAllCheckbox.setChecked(false);
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
        selectedServantFids.clear();
        updateConfirmButton();
        currentServantResults.clear();
    }
    
    private void onServantSelectionChanged(List<KeyInfo> updatedKeyInfoList) {
        selectedServantFids = new ArrayList<>(keyCardManager.getSelectedKeys());
        updateConfirmButton();
        updateSelectAllCheckbox();
        
        TimberLogger.d(TAG, "Selected %d servant FIDs", selectedServantFids.size());
    }
    
    private void updateConfirmButton() {
        boolean hasSelection = !selectedServantFids.isEmpty();
        confirmButton.setEnabled(hasSelection);
        confirmButton.setAlpha(hasSelection ? 1.0f : 0.5f);
    }
    
    private void onMenuItemClicked(String menuItem, KeyInfo keyInfo) {
        if (getString(R.string.detail).equals(menuItem)) {
            showKeyInfoDetail(keyInfo);
        }
    }
    
    private void showKeyInfoDetail(KeyInfo keyInfo) {
        try {
            android.content.Intent intent = new android.content.Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, keyInfo.toJson());
            intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, KeyInfo.class.getName());
            startActivity(intent);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing KeyInfo detail: %s", e.getMessage());
            Toast.makeText(this, getString(R.string.error_showing_detail), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void confirmAddServantFids() {
        if (selectedServantFids.isEmpty()) {
            Toast.makeText(this, "No servant FIDs selected", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            int addedCount = 0;
            
            for (KeyInfo keyInfoToAdd : selectedServantFids) {
                // Check if FID is already in servant list (double-check)
                List<KeyInfo> servantList = currentSetting.getServantKeyInfoList();
                boolean alreadyExists = false;
                if (servantList != null) {
                    for (KeyInfo existingKeyInfo : servantList) {
                        if (keyInfoToAdd.getId().equals(existingKeyInfo.getId())) {
                            alreadyExists = true;
                            break;
                        }
                    }
                }
                
                if (!alreadyExists) {
                    // Mark as servant and set save time
                    keyInfoToAdd.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(), DateUtils.LONG_FORMAT));
                    
                    // Add to current setting's servant list
                    currentSetting.addServantKeyInfo(keyInfoToAdd);
                    addedCount++;
                    
                    TimberLogger.d(TAG, "Added servant FID: %s", keyInfoToAdd.getId());
                }
            }
            
            if (addedCount > 0) {
                // Save settings
                SettingManager settingManager = SettingManager.getInstance();
                settingManager.saveSettings(this, currentSetting);
                
                // Update the SettingManager's current setting reference to ensure other activities see the changes
                settingManager.setCurrentSetting(this, currentSetting);
                
                String message = addedCount == 1 ? 
                    getString(R.string.servant_fid_added_successfully) :
                    getString(R.string.servant_fids_added_successfully, addedCount);
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                
                TimberLogger.d(TAG, "Successfully added %d servant FIDs", addedCount);
                
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "All selected FIDs were already added", Toast.LENGTH_SHORT).show();
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding servant FIDs: %s", e.getMessage());
            Toast.makeText(this, getString(R.string.failed_to_add_servant_fids) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
    
    private void appendServantResults(List<Cid> newResults) {
        // Filter out already added servant FIDs
        List<KeyInfo> existingServants = currentSetting.getServantKeyInfoList();
        Set<String> existingFids = new HashSet<>();
        if (existingServants != null) {
            for (KeyInfo keyInfo : existingServants) {
                existingFids.add(keyInfo.getId());
            }
        }
        
        // Convert Cid objects to KeyInfo objects and add them to KeyCardManager
        for (Cid cid : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(cid);
            if (keyInfo != null && !existingFids.contains(keyInfo.getId())) {
                keyCardManager.addKeyCard(keyInfo);
            }
        }
        
        // Show select all checkbox if there are results to display
        if (!keyCardManager.getKeyInfoList().isEmpty()) {
            selectAllCheckbox.setVisibility(View.VISIBLE);
        }
    }
    
    private void updateMoreButtonVisibility(int resultsSize) {
        // Show More button if we got the full page size, indicating there might be more results
        if (resultsSize >= FreerApplication.DEFAULT_PAGE_SIZE) {
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
    
    private void loadMoreServants() {
        if (apipClient == null) {
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
                fetchMyServantsFromApi(afterValue);
            } else {
                dismissWaitingDialog();
                Toast.makeText(this, getString(R.string.no_more_results), Toast.LENGTH_SHORT).show();
                moreButton.setVisibility(View.GONE);
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more servants: %s", e.getMessage());
            dismissWaitingDialog();
            Toast.makeText(this, getString(R.string.failed_to_load_more), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateMoreResultsHint() {
        try {
            if (apipClient != null && 
                apipClient.getApiEvent() != null && 
                apipClient.getApiEvent().getResponseBody() != null && 
                apipClient.getApiEvent().getResponseBody().getTotal() != null) {
                
                long totalResults = apipClient.getApiEvent().getResponseBody().getTotal();
                int currentResultsCount = currentServantResults.size();
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
    
    private void onSelectAllChanged(boolean isChecked) {
        if (isChecked) {
            keyCardManager.selectAll();
        } else {
            keyCardManager.unselectAll();
        }
        selectedServantFids = new ArrayList<>(keyCardManager.getSelectedKeys());
        updateConfirmButton();
        
        TimberLogger.d(TAG, "Select all changed to %b, selected %d servant FIDs", isChecked, selectedServantFids.size());
    }
    
    private void updateSelectAllCheckbox() {
        List<KeyInfo> allKeyInfos = keyCardManager.getKeyInfoList();
        List<KeyInfo> selectedKeys = keyCardManager.getSelectedKeys();
        
        // Temporarily remove listener to prevent recursive calls
        selectAllCheckbox.setOnCheckedChangeListener(null);
        
        if (allKeyInfos.isEmpty()) {
            selectAllCheckbox.setChecked(false);
        } else if (selectedKeys.size() == allKeyInfos.size()) {
            selectAllCheckbox.setChecked(true);
        } else {
            selectAllCheckbox.setChecked(false);
        }
        
        // Re-add listener
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
    }
}