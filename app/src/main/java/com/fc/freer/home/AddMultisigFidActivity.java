package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.FieldNames.ID;

import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.MenuItem;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.ui.WaitingDialog;
import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AddMultisigFidActivity extends BaseCryptoActivity {
    private static final String TAG = "AddMultisigFidActivity";
    
    private TextView multisignResultsHint;
    private LinearLayout multisignResultsLayout;
    private ImageButton refreshButton;
//    private Button clearButton;
    private ImageButton confirmButton;
    private ImageButton moreButton;
    private TextView moreResultsHint;
    private CheckBox selectAllCheckbox;
    private TextView selectAllLabel;
    
    private List<KeyInfo> selectedMultisignFids = new ArrayList<>();
    private FapiClient fapiClient;
    private KeyCardContainer keyCardContainer;
    private WaitingDialog waitingDialog;
    private Setting currentSetting;
    private List<Multisig> currentMultisigResults = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_add_multisign_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.add_multisign_fid);
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
        multisignResultsHint = findViewById(R.id.multisigResultsHint);
        refreshButton = findViewById(R.id.refreshButton);
        multisignResultsLayout = findViewById(R.id.multisigResultsLayout);
//        clearButton = findViewById(R.id.clearButton);
        confirmButton = findViewById(R.id.addMultisigFidButton);
        moreButton = findViewById(R.id.moreButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox);
        selectAllLabel = findViewById(R.id.selectAllLabel);
        
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Initialize KeyCardContainer with checkbox selection (multiple choice)
        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(KeyCardContainer.createDeleteMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, multisignResultsLayout, ChooseMode.CHOOSE_MULTI, menuItems, true);

        keyCardContainer.setOnKeyListChangedListener(this::onMultisignSelectionChanged);
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
        refreshButton.setOnClickListener(v -> loadMyMultisigns());
//        clearButton.setOnClickListener(v -> clearAll());
        confirmButton.setOnClickListener(v -> confirmAddMultisignFids());
        ImageButton backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }
        moreButton.setOnClickListener(v -> loadMoreMultisigns());
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
        selectAllCheckbox.setVisibility(View.GONE);
        
        // Auto-load multisigns on activity start
        loadMyMultisigns();
    }
    
    private void loadMyMultisigns() {
        TimberLogger.d(TAG, "loadMyMultisigns() called");

        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.apip_client_not_available));
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.loading_multisigns));
        waitingDialog.show();
        
        // Fetch my multisigns from API
        fetchMyMultisignsFromApi();
    }
    
    private void fetchMyMultisignsFromApi() {
        fetchMyMultisignsFromApi(null);
    }
    
    private void fetchMyMultisignsFromApi(List<String> afterValue) {
        new Thread(() -> {
            try {
                FidManager fidManager = FidManager.getInstance();
                List<Multisig> results = fapiClient.myMultisigs(fidManager.getLiveFid());
                if(results==null || results.isEmpty()){
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        if (afterValue == null) {
                            ToastUtils.makeText(this, getString(R.string.fetch_multisigns_failed));
                            clearMultisignResults();
                        } else {
                            // No more results
                            moreButton.setVisibility(View.GONE);
                            ToastUtils.makeText(this, getString(R.string.no_more_results));
                        }
                    });
                    return;
                }
                
                // Store results for pagination
                if (afterValue == null) {
                    currentMultisigResults.clear();
                }
                currentMultisigResults.addAll(results);
                
                List<String> idList = new ArrayList<>();
                for(Multisig multisig : results){
                    idList.add(multisig.getId());
                }
                Map<String, Freer> cidInfoMap = fapiClient.freerByIds(idList);
                Map<String, Multisig> multisignMap = ObjectUtils.listToMap(results, ID);

                for(String id : multisignMap.keySet()){
                    Freer freer = cidInfoMap.get(id);
                    if(freer !=null){
                        freer.setMultisign(multisignMap.get(id));
                    }
                }

                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (cidInfoMap != null && !cidInfoMap.isEmpty()) {
                        if (afterValue == null) {
                            displayMultisignResults(new ArrayList<>(cidInfoMap.values()));
                        } else {
                            appendMultisigResults(new ArrayList<>(cidInfoMap.values()));
                        }
                        
                        // Show/hide More button based on result size
                        updateMoreButtonVisibility(results.size());
                    } else {
                        if (afterValue == null) {
                            TimberLogger.d(TAG, "No multisig results found, showing toast");
                            ToastUtils.makeText(this, getString(R.string.no_multisigns_found));
                            clearMultisignResults();
                        } else {
                            moreButton.setVisibility(View.GONE);
                            ToastUtils.makeText(this, getString(R.string.no_more_results));
                        }
                    }
                });
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching my multisigns: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.fetch_multisigns_failed));
                    if (afterValue == null) {
                        clearMultisignResults();
                    }
                });
            }
        }).start();
    }
    
    private void displayMultisignResults(List<Freer> results) {
        // Clear existing results and selection
        keyCardContainer.clearAll();
        selectedMultisignFids.clear();
        
        // Show multisig results hint
        multisignResultsHint.setVisibility(View.VISIBLE);
        
        // Hide more results hint when displaying new results
        moreResultsHint.setVisibility(View.GONE);
        
        // Reset select all checkbox
        selectAllCheckbox.setOnCheckedChangeListener(null);
        selectAllCheckbox.setChecked(false);
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
        
        // Filter out already added multisig FIDs
        List<KeyInfo> existingMultisigns = currentSetting.getMultisigKeyInfoList();
        Set<String> existingFids = new HashSet<>();
        if (existingMultisigns != null) {
            for (KeyInfo keyInfo : existingMultisigns) {
                existingFids.add(keyInfo.getId());
            }
        }
        
        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null && !existingFids.contains(keyInfo.getId())) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
        
        // Show/hide select all checkbox based on whether there are results
        if (keyCardContainer.getKeyInfoList().isEmpty()) {
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.all_multisigns_already_added));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            multisignResultsLayout.addView(emptyMessageView);
            selectAllCheckbox.setVisibility(View.GONE);
            selectAllLabel.setVisibility(View.GONE);
        } else {
            selectAllCheckbox.setVisibility(View.VISIBLE);
            selectAllLabel.setVisibility(View.VISIBLE);
        }
    }
    
    private void clearMultisignResults() {
        TimberLogger.d(TAG, "clearMultisignResults() called");
        keyCardContainer.clearAll();
        multisignResultsHint.setVisibility(View.GONE);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
        selectAllCheckbox.setVisibility(View.GONE);
        selectAllCheckbox.setOnCheckedChangeListener(null);
        selectAllCheckbox.setChecked(false);
        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> onSelectAllChanged(isChecked));
        selectedMultisignFids.clear();
        updateConfirmButton();
        currentMultisigResults.clear();
    }
    
    private void clearAll() {
        // Clear multisig results
        clearMultisignResults();
        
        ToastUtils.makeText(this, getString(R.string.cleared));
    }
    
    private void onMultisignSelectionChanged(List<KeyInfo> updatedKeyInfoList) {
        selectedMultisignFids = new ArrayList<>(keyCardContainer.getSelectedKeys());
        updateConfirmButton();
        updateSelectAllCheckbox();
        
        TimberLogger.d(TAG, "Selected %d multisig FIDs", selectedMultisignFids.size());
    }
    
    private void updateConfirmButton() {
        boolean hasSelection = !selectedMultisignFids.isEmpty();
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
            ToastUtils.makeText(this, getString(R.string.error_showing_detail));
        }
    }
    
    private void confirmAddMultisignFids() {
        if (selectedMultisignFids.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_multisign_fids_selected));
            return;
        }
        
        try {
            int addedCount = 0;
            
            for (KeyInfo keyInfoToAdd : selectedMultisignFids) {
                // Check if FID is already in multisig list (double-check)
                List<KeyInfo> multisignList = currentSetting.getMultisigKeyInfoList();
                boolean alreadyExists = false;
                if (multisignList != null) {
                    for (KeyInfo existingKeyInfo : multisignList) {
                        if (keyInfoToAdd.getId().equals(existingKeyInfo.getId())) {
                            alreadyExists = true;
                            break;
                        }
                    }
                }
                
                if (!alreadyExists) {
                    // Mark as multisig and set save time
                    keyInfoToAdd.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(), DateUtils.LONG_FORMAT));
                    
                    // Add to current setting's multisig list
                    currentSetting.addMultisigKeyInfo(keyInfoToAdd);
                    addedCount++;
                    
                    TimberLogger.d(TAG, "Added multisig FID: %s", keyInfoToAdd.getId());
                }
            }
            
            if (addedCount > 0) {
                // Save settings
                SettingManager settingManager = SettingManager.getInstance();
                settingManager.saveSettings(this, currentSetting);
                
                String message = addedCount == 1 ? 
                    getString(R.string.multisign_fid_added_successfully) :
                    getString(R.string.multisign_fids_added_successfully, addedCount);
                ToastUtils.makeText(this, message);
                
                TimberLogger.d(TAG, "Successfully added %d multisig FIDs", addedCount);
                
                setResult(RESULT_OK);
                finish();
            } else {
                ToastUtils.makeText(this, getString(R.string.all_selected_multisign_fids_already_added));
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding multisig FIDs: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.failed_to_add_multisign_fids) + ": " + e.getMessage());
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
    
    private void appendMultisigResults(List<Freer> newResults) {
        // Filter out already added multisig FIDs
        List<KeyInfo> existingMultisigs = currentSetting.getMultisigKeyInfoList();
        Set<String> existingFids = new HashSet<>();
        if (existingMultisigs != null) {
            for (KeyInfo keyInfo : existingMultisigs) {
                existingFids.add(keyInfo.getId());
            }
        }
        
        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null && !existingFids.contains(keyInfo.getId())) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
        
        // Show select all checkbox if there are results to display
        if (!keyCardContainer.getKeyInfoList().isEmpty()) {
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
    
    private void loadMoreMultisigns() {
        if (fapiClient == null) {
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();
        
        try {
            // Get the 'last' value from the last response
            List<String> afterValue = null;
            if (fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getLast() != null) {
                afterValue = fapiClient.getLastResponse().getLast();
            }
            
            if (afterValue != null) {
                fetchMyMultisignsFromApi(afterValue);
            } else {
                dismissWaitingDialog();
                ToastUtils.makeText(this, getString(R.string.no_more_results));
                moreButton.setVisibility(View.GONE);
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more multisigns: %s", e.getMessage());
            dismissWaitingDialog();
            ToastUtils.makeText(this, getString(R.string.failed_to_load_more));
        }
    }
    
    private void updateMoreResultsHint() {
        try {
            if (fapiClient != null && fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getTotal() != null) {
                
                long totalResults = fapiClient.getLastResponse().getTotal();
                int currentResultsCount = currentMultisigResults.size();
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
            keyCardContainer.selectAll();
        } else {
            keyCardContainer.unselectAll();
        }
        selectedMultisignFids = new ArrayList<>(keyCardContainer.getSelectedKeys());
        updateConfirmButton();
        
        TimberLogger.d(TAG, "Select all changed to %b, selected %d multisig FIDs", isChecked, selectedMultisignFids.size());
    }
    
    private void updateSelectAllCheckbox() {
        List<KeyInfo> allKeyInfos = keyCardContainer.getKeyInfoList();
        List<KeyInfo> selectedKeys = keyCardContainer.getSelectedKeys();
        
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