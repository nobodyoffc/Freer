package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.FieldNames.FREER;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.USED_CIDS;

import android.content.Intent;
import android.text.TextUtils;
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
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.feip.FeipHandler;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.freer.utils.SecurePrikeyManager;

import java.util.ArrayList;
import java.util.List;

public class SetMasterActivity extends BaseCryptoActivity {
    private static final String TAG = "SetMasterActivity";
    
    private LinearLayout mainFidLayout;
    private TextView searchResultsHint;
    private EditText masterSearchEditText;
    private ImageButton searchButton;
    private LinearLayout searchResultsLayout;
    private ImageButton clearButton;
    private ImageButton confirmButton;
    
    private String selectedMasterFid = null;
    private Freer selectedMasterFreer = null;
    private View selectedCardView = null;
    private String originalMasterInput = null; // Store original input (FID or pubkey)
    private FapiClient fapiClient;
    private FeipHandler feipHandler;
    private TxHandler txHandler;
    private CashManager cashManager;
    private KeyCardContainer keyCardContainer;
    private KeyCardContainer mainFidKeyCardContainer;
    private WaitingDialog waitingDialog;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_set_master;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.set_master);
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
        mainFidLayout = findViewById(R.id.mainFidLayout);
        searchResultsHint = findViewById(R.id.searchResultsHint);
        masterSearchEditText = findViewById(R.id.masterSearchEditText);
        searchButton = findViewById(R.id.searchButton);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        clearButton = findViewById(R.id.clearButton);
        confirmButton = findViewById(R.id.setMasterButton);
        
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Initialize main FID KeyCardContainer (no interactions needed)
        mainFidKeyCardContainer = new KeyCardContainer(this, mainFidLayout);
        
        // Initialize search KeyCardContainer with single choice and clickToReturn enabled

        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, ChooseMode.CHOOSE_ONE_RETURN);
        keyCardContainer.setOnKeyClickedListener(this::onMasterSelected);
        keyCardContainer.setOnMenuItemClickListener(this::onMenuItemClicked);
    }
    
    private void setupData() {
        try {
            // Get main FID from FidManager
            FidManager fidManager = FidManager.getInstance();
            if (fidManager != null) {
                String mainFid = fidManager.getMainFid();
                if (mainFid != null) {
                    // Clear existing cards and add main FID card
                    mainFidKeyCardContainer.clearAll();
                    
                    // Create KeyInfo for main FID
                    KeyInfo mainFidKeyInfo = new KeyInfo();
                    mainFidKeyInfo.setId(mainFid);
                    mainFidKeyInfo.setLabel("Main FID");
                    
                    mainFidKeyCardContainer.addKeyCard(mainFidKeyInfo);
                } else {
                    // Show placeholder if main FID not available
                    mainFidKeyCardContainer.clearAll();
                    KeyInfo placeholderKeyInfo = new KeyInfo();
                    placeholderKeyInfo.setId(getString(R.string.main_fid_not_available));
                    mainFidKeyCardContainer.addKeyCard(placeholderKeyInfo);
                }
            }
            
            // Get FAPI client
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter != null) {
                fapiClient = (FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
            }
            
            // Initialize FeipHandler, TxHandler, and CashManager
            feipHandler = new FeipHandler();
            txHandler = new TxHandler();
            cashManager = CashManager.getInstance();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up data: %s", e.getMessage());
        }
    }
    
    private void setupListeners() {
        searchButton.setOnClickListener(v -> performSearch());
        clearButton.setOnClickListener(v -> clearAll());
        confirmButton.setOnClickListener(v -> confirmSetMaster());
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        // Hide keyboard when masterSearchEditText loses focus
        masterSearchEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard();
            }
        });
    }
    
    private void performSearch() {
        TimberLogger.d(TAG, "performSearch() called - Stack trace: " + android.util.Log.getStackTraceString(new Exception()));

        String searchString = masterSearchEditText.getText().toString().trim();
        if (TextUtils.isEmpty(searchString)) {
            ToastUtils.makeText(this, getString(R.string.please_enter_search_term));
            return;
        }
        
        // Reset original input for new search
        originalMasterInput = null;
        
        // Check if input is a pubkey first - if so, create KeyInfo directly
        if (KeyTools.isPubkey(searchString)) {
            TimberLogger.d(TAG, "Detected pubkey input, creating KeyInfo directly");
            
            // Create KeyInfo from pubkey and display it directly
            KeyInfo keyInfo = new KeyInfo();
            keyInfo.setPubkey(searchString);
            keyInfo.setId(KeyTools.pubkeyToFchAddr(searchString));

            // Store original pubkey input
            originalMasterInput = searchString;
            
            // Display as single result and auto-select immediately
            List<KeyInfo> keyInfoList = new ArrayList<>();
            keyInfoList.add(keyInfo);
            displayKeyInfoResults(keyInfoList);
            
            // Use post to ensure UI is updated before auto-selecting
            selectedMasterFreer = new Freer();
            selectedMasterFreer.setId(KeyTools.pubkeyToFchAddr(searchString));
            selectedMasterFreer.setPubkey(searchString);
            selectedMasterFid = KeyTools.pubkeyToFchAddr(searchString);


            View cardView = searchResultsLayout.getChildAt(0);
            cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;

            // Show selected FID in the search EditText with middle truncation
            String displayText = selectedMasterFid;

            masterSearchEditText.setText(displayText);

            confirmButton.setEnabled(true);
        confirmButton.setAlpha(1.0f);

            TimberLogger.d(TAG, "Pubkey handling complete, returning early");
            return;
        }
        
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.apip_client_not_available));
            return;
        }
        
        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.searching));
        waitingDialog.show();
        
        // Check if it's a valid FID format (pubkey already handled above)
        if (isValidFidFormat(searchString)) {
            // Search for exact FID
            searchExactFid(searchString);
        } else {
            // Search for partial match in CID
            searchPartialCid(searchString);
        }
    }
    
    private boolean isValidFidFormat(String fid) {
        // Basic FID format validation - should start with F and be proper length
        return KeyTools.isGoodFid(fid);
    }
    
    private boolean isValidPubkeyFormat(String pubkey) {
        // Check if input is a valid public key format
        return KeyTools.isPubkey(pubkey);
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
                        displaySearchResults(results);
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
    
    private void searchPartialCid(String searchString) {
        new Thread(() -> {
            try {
                
                // Create Fcdsl for partial search in id or usedCids
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields(ID, USED_CIDS).addNewValue(searchString);
                fcdsl.setSize("20");

                List<Freer> results = fapiClient.entitySearch(FREER,fcdsl,Freer.class);
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (results != null && !results.isEmpty()) {
                        displaySearchResults(results);
                    } else {
                        TimberLogger.d(TAG, "No CID results found, showing toast");
                        ToastUtils.makeText(this, getString(R.string.no_matching_cids_found));
                        clearSearchResults();
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
        
        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                // Don't set label - let CID value show in the CID field instead
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
    }
    
    private void displayKeyInfoResults(List<KeyInfo> keyInfoList) {
        // Clear existing results and selection
        keyCardContainer.clearAll();
        selectedCardView = null;
        
        // Add KeyInfo objects directly to KeyCardContainer
        for (KeyInfo keyInfo : keyInfoList) {
            keyCardContainer.addKeyCard(keyInfo);
        }
    }
    
    private void clearSearchResults() {
        TimberLogger.d(TAG, "clearSearchResults() called");
        keyCardContainer.clearAll();
        searchResultsHint.setVisibility(View.GONE);
    }
    
    private void clearAll() {
        // Clear the search input
        masterSearchEditText.setText("");
        
        // Clear search results
        clearSearchResults();
        
        // Reset selection state
        selectedMasterFid = null;
        selectedMasterFreer = null;
        selectedCardView = null;
        originalMasterInput = null;
        
        // Disable set master button
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        ToastUtils.makeText(this, getString(R.string.cleared));
    }
    
    private void onMasterSelected(KeyInfo keyInfo) {
        selectedMasterFid = keyInfo.getId();
        
        // Convert KeyInfo back to Freer for compatibility with existing logic
        selectedMasterFreer = new Freer();
        selectedMasterFreer.setId(keyInfo.getId());
        selectedMasterFreer.setCid(keyInfo.getCid());
        selectedMasterFreer.setPubkey(keyInfo.getPubkey());
        
        // If we don't have original input stored (direct FID search), use the FID
        if (originalMasterInput == null) {
            originalMasterInput = selectedMasterFid;
        }
        
        // Update card selection highlighting
        updateCardSelection(keyInfo);
        
        // Show selected FID in the search EditText with middle truncation
        String displayText = StringUtils.omitMiddle(selectedMasterFid, 20);

        if (keyInfo.getLabel() != null && !keyInfo.getLabel().trim().isEmpty()) {
            displayText = keyInfo.getLabel() + ": "+displayText;
        } else if (keyInfo.getCid() != null && !keyInfo.getCid().trim().isEmpty()) {
            displayText = keyInfo.getCid() + ": "+displayText;
        }
        masterSearchEditText.setText(displayText);
        
        confirmButton.setEnabled(true);
        confirmButton.setAlpha(1.0f);
        
        ToastUtils.makeText(this, getString(R.string.master_selected));
    }
    
    private void onMenuItemClicked(String menuItem, KeyInfo keyInfo) {
        if (getString(R.string.detail).equals(menuItem)) {
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
    
    
    private void confirmSetMaster() {
        if (selectedMasterFid == null || selectedMasterFreer == null) {
            ToastUtils.makeText(this, getString(R.string.no_master_selected));
            return;
        }
        
        if (feipHandler == null) {
            ToastUtils.makeText(this, getString(R.string.feip_handler_not_available));
            return;
        }
        
        // Get main FID from FidManager
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) {
            ToastUtils.makeText(this, getString(R.string.fid_manager_not_available));
            return;
        }
        
        String mainFid = fidManager.getMainFid();
        if (mainFid == null) {
            ToastUtils.makeText(this, getString(R.string.main_fid_not_available));
            return;
        }
        
        // Disable button to prevent double submission
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        // Show confirmation dialog
        String title = getString(R.string.confirm_set_master);
        String message = getString(R.string.confirm_set_master_message, selectedMasterFid, mainFid);


        UserConfirmDialog dialog = new UserConfirmDialog(this, title, message, choice -> {
            if (choice == UserConfirmDialog.Choice.YES) {
                // User confirmed, proceed with transaction
                try {
                    // Step 1: Create FEIP JSON using FeipHandler.setMaster
                    String prikeyCipher = fidManager != null ? fidManager.getLiveKeyInfo().getPrikeyCipher() : null;
                    if (prikeyCipher == null) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(SetMasterActivity.this,
                                getString(R.string.failed_to_get_prikey_cipher)
                            );
                            confirmButton.setEnabled(true);
                            confirmButton.setAlpha(1.0f);
                        });
                        return;
                    }
                    byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(prikeyCipher);
                    String feipJson = feipHandler.masterSet(selectedMasterFreer.getPubkey(), prikey);
                    if (feipJson == null || feipJson.isEmpty()) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(SetMasterActivity.this,
                                getString(R.string.failed_to_set_master)
                            );
                            // Re-enable button
                            confirmButton.setEnabled(true);
                            confirmButton.setAlpha(1.0f);
                        });
                        return;
                    }

                    // Step 2: Use TxSender to create, sign, and broadcast FEIP transaction
                    TxSender txSender = new TxSender();
                    txSender.carveSimpleFeip(
                            SetMasterActivity.this,
                            mainFid,
                            feipJson,
                            prikey,
                            cashManager,
                            txHandler,
                            fapiClient,
                            new TxSender.TxCallback() {
                                @Override
                                public void onSuccess(String txId) {
                                    runOnUiThread(() -> {
                                        try {
                                            // Update mainKeyInfo.master and save current setting
                                            SettingManager settingManager = SettingManager.getInstance();
                                            Setting currentSetting = settingManager.getCurrentSetting();
                                            if (currentSetting != null && currentSetting.getMainKeyInfo() != null) {
                                                // Update the master field in mainKeyInfo
                                                currentSetting.getMainKeyInfo().setMaster(selectedMasterFid);
                                                currentSetting.getKeyInfoMap().get(currentSetting.getMainKeyInfo().getId()).setMaster(selectedMasterFid);
                                                
                                                settingManager.saveSettings(SetMasterActivity.this, currentSetting);
                                                TimberLogger.i(TAG, "Updated mainKeyInfo.master to: %s and saved setting", selectedMasterFid);
                                            }
                                        } catch (Exception e) {
                                            TimberLogger.e(TAG, "Error updating mainKeyInfo.master: %s", e.getMessage());
                                        }
                                        
                                        ToastUtils.makeText(SetMasterActivity.this,
                                            getString(R.string.master_set_successfully, txId)
                                        );
                                        setResult(RESULT_OK);
                                        SecurePrikeyManager.erasePrikey(prikey);
                                        
                                        finish();
                                    });
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    runOnUiThread(() -> {
                                        ToastUtils.makeText(SetMasterActivity.this,
                                            getString(R.string.failed_to_set_master, errorMessage)
                                        );
                                        // Re-enable button
                                        confirmButton.setEnabled(true);
                                        confirmButton.setAlpha(1.0f);
                                        SecurePrikeyManager.erasePrikey(prikey);

                                    });
                                }

                                @Override
                                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                                    runOnUiThread(() -> {
                                        // Show unsigned transaction as QR codes
                                        txSender.showUnsignedTxAsQR(SetMasterActivity.this, rawTxInfo);
                                        // Re-enable button since we're not finishing
                                        confirmButton.setEnabled(true);
                                        confirmButton.setAlpha(1.0f);
                                        SecurePrikeyManager.erasePrikey(prikey);
                                    });
                                }

                                @Override
                                public void onUnbroadcasted(String signedTxHex) {
                                    runOnUiThread(() -> {
                                        // Show signed transaction as QR code for manual broadcasting
                                        txSender.showSignedTxAsQR(SetMasterActivity.this, signedTxHex);
                                        // Re-enable button since we're not finishing
                                        confirmButton.setEnabled(true);
                                        confirmButton.setAlpha(1.0f);
                                        SecurePrikeyManager.erasePrikey(prikey);
                                    });
                                }
                            });

                } catch (Exception e) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(SetMasterActivity.this,
                            getString(R.string.failed_to_set_master, e.getMessage())
                        );
                        // Re-enable button
                        confirmButton.setEnabled(true);
                        confirmButton.setAlpha(1.0f);

                    });
                }
            } else {
                // User cancelled, re-enable button
                confirmButton.setEnabled(true);
                confirmButton.setAlpha(1.0f);

            }
        });
        
        dialog.show();
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
}