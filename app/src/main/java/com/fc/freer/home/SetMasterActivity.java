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
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.fc_ajdk.utils.http.RequestMethod;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.network.ApipClient;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.KeyCardManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.feip.FeipHandler;
import com.fc.freer.tx.TxHandler;
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
    private Button searchButton;
    private LinearLayout searchResultsLayout;
    private Button clearButton;
    private Button cancelButton;
    private Button confirmButton;
    
    private String selectedMasterFid = null;
    private Cid selectedMasterCid = null;
    private View selectedCardView = null;
    private String originalMasterInput = null; // Store original input (FID or pubkey)
    private ApipClient apipClient;
    private FeipHandler feipHandler;
    private TxHandler txHandler;
    private CashManager cashManager;
    private KeyCardManager keyCardManager;
    private KeyCardManager mainFidKeyCardManager;
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
        cancelButton = findViewById(R.id.cancelButton);
        confirmButton = findViewById(R.id.setMasterButton);
        
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        // Initialize main FID KeyCardManager (no interactions needed)
        mainFidKeyCardManager = new KeyCardManager(this, mainFidLayout);
        
        // Initialize search KeyCardManager with single choice and clickToReturn enabled

        keyCardManager = new KeyCardManager(this, searchResultsLayout, true, true, null);
        keyCardManager.setOnKeyClickedListener(this::onMasterSelected);
        keyCardManager.setOnMenuItemClickListener(this::onMenuItemClicked);
    }
    
    private void setupData() {
        try {
            // Get main FID from FidManager
            FidManager fidManager = FidManager.getInstance();
            if (fidManager != null) {
                String mainFid = fidManager.getMainFid();
                if (mainFid != null) {
                    // Clear existing cards and add main FID card
                    mainFidKeyCardManager.clearAll();
                    
                    // Create KeyInfo for main FID
                    KeyInfo mainFidKeyInfo = new KeyInfo();
                    mainFidKeyInfo.setId(mainFid);
                    mainFidKeyInfo.setLabel("Main FID");
                    
                    mainFidKeyCardManager.addKeyCard(mainFidKeyInfo);
                } else {
                    // Show placeholder if main FID not available
                    mainFidKeyCardManager.clearAll();
                    KeyInfo placeholderKeyInfo = new KeyInfo();
                    placeholderKeyInfo.setId(getString(R.string.main_fid_not_available));
                    mainFidKeyCardManager.addKeyCard(placeholderKeyInfo);
                }
            }
            
            // Get APIP client
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter != null) {
                apipClient = (ApipClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.APIP);
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
        cancelButton.setOnClickListener(v -> finish());
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
            Toast.makeText(this, getString(R.string.please_enter_search_term), Toast.LENGTH_SHORT).show();
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
            selectedMasterCid = new Cid();
            selectedMasterCid.setId(KeyTools.pubkeyToFchAddr(searchString));
            selectedMasterCid.setPubkey(searchString);
            selectedMasterFid = KeyTools.pubkeyToFchAddr(searchString);


            View cardView = searchResultsLayout.getChildAt(0);
            cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;

            // Show selected FID in the search EditText with middle truncation
            String displayText = StringUtils.omitMiddle(selectedMasterFid, 20);

            masterSearchEditText.setText(displayText);

            confirmButton.setEnabled(true);
        confirmButton.setAlpha(1.0f);

            TimberLogger.d(TAG, "Pubkey handling complete, returning early");
            return;
        }
        
        if (apipClient == null) {
            Toast.makeText(this, getString(R.string.apip_client_not_available), Toast.LENGTH_SHORT).show();
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

                Cid cidInfo = apipClient.cidInfoById(fid, RequestMethod.POST, AuthType.FREE, this);
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (cidInfo != null) {
                        List<Cid> results = new ArrayList<>();
                        results.add(cidInfo);
                        displaySearchResults(results);
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
    
    private void searchPartialCid(String searchString) {
        new Thread(() -> {
            try {
                
                // Create Fcdsl for partial search in id or usedCids
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields(ID, USED_CIDS).addNewValue(searchString);
                fcdsl.setSize("20");

                List<Cid> results = apipClient.cidSearch(fcdsl, RequestMethod.POST, AuthType.FREE, this);
                
                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (results != null && !results.isEmpty()) {
                        displaySearchResults(results);
                    } else {
                        TimberLogger.d(TAG, "No CID results found, showing toast");
                        Toast.makeText(this, getString(R.string.no_matching_cids_found), Toast.LENGTH_SHORT).show();
                        clearSearchResults();
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
        
        // Convert Cid objects to KeyInfo objects and add them to KeyCardManager
        for (Cid cid : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(cid);
            if (keyInfo != null) {
                // Don't set label - let CID value show in the CID field instead
                keyCardManager.addKeyCard(keyInfo);
            }
        }
    }
    
    private void displayKeyInfoResults(List<KeyInfo> keyInfoList) {
        // Clear existing results and selection
        keyCardManager.clearAll();
        selectedCardView = null;
        
        // Add KeyInfo objects directly to KeyCardManager
        for (KeyInfo keyInfo : keyInfoList) {
            keyCardManager.addKeyCard(keyInfo);
        }
    }
    
    private void clearSearchResults() {
        TimberLogger.d(TAG, "clearSearchResults() called");
        keyCardManager.clearAll();
        searchResultsHint.setVisibility(View.GONE);
    }
    
    private void clearAll() {
        // Clear the search input
        masterSearchEditText.setText("");
        
        // Clear search results
        clearSearchResults();
        
        // Reset selection state
        selectedMasterFid = null;
        selectedMasterCid = null;
        selectedCardView = null;
        originalMasterInput = null;
        
        // Disable set master button
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        
        Toast.makeText(this, getString(R.string.cleared), Toast.LENGTH_SHORT).show();
    }
    
    private void onMasterSelected(KeyInfo keyInfo) {
        selectedMasterFid = keyInfo.getId();
        
        // Convert KeyInfo back to Cid for compatibility with existing logic
        selectedMasterCid = new Cid();
        selectedMasterCid.setId(keyInfo.getId());
        selectedMasterCid.setCid(keyInfo.getCid());
        selectedMasterCid.setPubkey(keyInfo.getPubkey());
        
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
        
        Toast.makeText(this, getString(R.string.master_selected), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.error_showing_detail), Toast.LENGTH_SHORT).show();
        }
    }
    
    
    private void confirmSetMaster() {
        if (selectedMasterFid == null || selectedMasterCid == null) {
            Toast.makeText(this, getString(R.string.no_master_selected), Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (feipHandler == null) {
            Toast.makeText(this, getString(R.string.feip_handler_not_available), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Get main FID from FidManager
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) {
            Toast.makeText(this, getString(R.string.fid_manager_not_available), Toast.LENGTH_SHORT).show();
            return;
        }
        
        String mainFid = fidManager.getMainFid();
        if (mainFid == null) {
            Toast.makeText(this, getString(R.string.main_fid_not_available), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Disable button to prevent double submission
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);
        confirmButton.setText(getString(R.string.setting_master));
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
                            Toast.makeText(SetMasterActivity.this,
                                getString(R.string.failed_to_get_private_key_cipher),
                                Toast.LENGTH_LONG).show();
                            confirmButton.setEnabled(true);
                            confirmButton.setAlpha(1.0f);
                        });
                        return;
                    }
                    byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(prikeyCipher);
                    String feipJson = feipHandler.masterSet(selectedMasterCid.getPubkey(), prikey);
                    if (feipJson == null || feipJson.isEmpty()) {
                        runOnUiThread(() -> {
                            Toast.makeText(SetMasterActivity.this,
                                getString(R.string.failed_to_set_master),
                                Toast.LENGTH_LONG).show();
                            // Re-enable button
                            confirmButton.setEnabled(true);
                            confirmButton.setAlpha(1.0f);
                        });
                        return;
                    }

                    // Step 2: Use TxSender to create, sign, and broadcast FEIP transaction
                    TxSender.carveSimpleFeip(
                            SetMasterActivity.this,
                            mainFid,
                            feipJson,
                            prikey,
                            cashManager,
                            txHandler,
                            apipClient,
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
                                        
                                        Toast.makeText(SetMasterActivity.this,
                                            getString(R.string.master_set_successfully, txId),
                                            Toast.LENGTH_LONG).show();
                                        setResult(RESULT_OK);
                                        SecurePrikeyManager.erasePrikey(prikey);
                                        
                                        finish();
                                    });
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(SetMasterActivity.this,
                                            getString(R.string.failed_to_set_master, errorMessage),
                                            Toast.LENGTH_LONG).show();
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
                                        TxSender.showUnsignedTxAsQR(SetMasterActivity.this, rawTxInfo);
                                        // Re-enable button since we're not finishing
                                        confirmButton.setEnabled(true);
                                        confirmButton.setAlpha(1.0f);
                                        SecurePrikeyManager.erasePrikey(prikey);
                                    });
                                }
                            });

                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(SetMasterActivity.this,
                            getString(R.string.failed_to_set_master, e.getMessage()),
                            Toast.LENGTH_LONG).show();
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