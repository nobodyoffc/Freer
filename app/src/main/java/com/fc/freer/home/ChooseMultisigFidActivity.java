package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.multisig.CreateMultisigIdActivity;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.ui.MenuItem;

import java.util.ArrayList;
import java.util.List;

public class ChooseMultisigFidActivity extends BaseCryptoActivity {
    private static final String TAG = "ChooseMultisigFidActivity";
    public static final int REQUEST_CODE_ADD_MULTISIGN_FID = 3001;
    
    private LinearLayout multisignFidListContainer;
    private ImageButton createButton;
    private ImageButton addButton;
    private Setting currentSetting;
    private KeyInfo selectedKeyInfo;
    private List<KeyInfo> multisignFidList;
    private KeyCardContainer keyCardContainer;
    private View selectedCardView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        
        // Get current setting (always reload to get latest data)
        currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null) {
            ToastUtils.makeText(this, getString(R.string.current_setting_not_available));
            finish();
            return;
        }
        
        // Load multisig FID list
        loadMultisignFidList();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Reload the current setting and multisig list when activity resumes
        // This ensures we have the latest data if user navigated back from another activity
        currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting != null) {
            loadMultisignFidList();
            TimberLogger.d(TAG, "Reloaded multisig FID list on resume");
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_choose_multisign_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.choose_multisign_fid);
    }

    @Override
    protected void initializeViews() {
        multisignFidListContainer = findViewById(R.id.multisign_fid_list_container);
        createButton = findViewById(R.id.create_button);
        addButton = findViewById(R.id.add_button);
        
        // Initialize KeyCardContainer with CHOOSE_ONE_RETURN mode and delete menu
        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(MenuItem.createDeleteMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, multisignFidListContainer, ChooseMode.CHOOSE_ONE_RETURN, menuItems, true);
        keyCardContainer.setShowDefaultMenuItems(true); // Show "Add to FID list" and "Clear FID list"
        keyCardContainer.setOnKeyClickedListener(this::onMultisignFidSelected);
        keyCardContainer.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    @Override
    protected void setupButtons() {
        createButton.setOnClickListener(v -> {
            // Launch AddMultisigFidActivity
            Intent intent = new Intent(this, AddMultisigFidActivity.class);
            startActivityForResult(intent, REQUEST_CODE_ADD_MULTISIGN_FID);
        });

        addButton.setOnClickListener(v -> {
            // Launch CreateMultisigIdActivity
            Intent intent = new Intent(this, CreateMultisigIdActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // This activity doesn't use QR scanning
    }

    private void loadMultisignFidList() {
        List<KeyInfo> multisignList = currentSetting.getMultisigKeyInfoList();
        if (multisignList == null || multisignList.isEmpty()) {
            // No multisig FIDs, show create button only
            addButton.setEnabled(true);
            multisignFidList = new ArrayList<>();
            displayMultisignFidList();
            return;
        }

        multisignFidList = new ArrayList<>(multisignList);
        
        // Preload avatars for all keys in background
        preloadAvatars();
        
        displayMultisignFidList();
    }
    
    /**
     * Preload avatars for all keys in background to improve performance
     */
    private void preloadAvatars() {
        if (multisignFidList != null && !multisignFidList.isEmpty()) {
            String[] fids = multisignFidList.stream()
                .map(KeyInfo::getId)
                .toArray(String[]::new);
            
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            avatarManager.preloadAvatars(fids);
            
            TimberLogger.d(TAG, "Preloading avatars for %d multisig FIDs", fids.length);
        }
    }

    private void displayMultisignFidList() {
        keyCardContainer.clearAll();
        selectedCardView = null;
        
        if (multisignFidList.isEmpty()) {
            // Show "No multisig FIDs available" message
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.no_multisign_fids_available));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            multisignFidListContainer.addView(emptyMessageView);
            return;
        }
        
        for (KeyInfo keyInfo : multisignFidList) {
            keyCardContainer.addKeyCard(keyInfo);
        }
    }

    private void onMultisignFidSelected(KeyInfo keyInfo) {
        selectedKeyInfo = keyInfo;
        
        // Update card selection highlighting
        updateCardSelection(keyInfo);
        
        // Switch to the selected multisig FID as liveFid using the same pattern as PersonPopupMenuHelper
        switchToMultisignFidAsync(keyInfo.getId(), keyInfo);
    }
    
    private void onMenuItemClicked(String menuItemId, KeyInfo keyInfo) {
        if ("delete".equals(menuItemId)) {
            // Remove the multisig FID from the current setting
            if (currentSetting != null) {
                boolean removed = currentSetting.removeMultisigKeyInfo(keyInfo.getId());
                if (removed) {
                    // Save the updated setting
                    SettingManager.getInstance().saveSettings(this,currentSetting);
                    
                    // Also update the current setting in SettingManager to ensure it stays in sync
                    SettingManager.getInstance().setCurrentSetting(currentSetting);
                    
                    // Reload the list to reflect the changes
                    loadMultisignFidList();

                    ToastUtils.makeText(this, getString(R.string.multisign_fid_deleted));
                    TimberLogger.d(TAG, "Successfully removed multisig FID: %s", keyInfo.getId());
                } else {
                    ToastUtils.makeText(this, getString(R.string.failed_to_remove_multisign_fid));
                    TimberLogger.w(TAG, "Failed to remove multisig FID: %s", keyInfo.getId());
                }
            }
        }
    }
    
    /**
     * Switch to multisig FID using proper FidManager.switchLiveFid() method
     */
    private void switchToMultisignFidAsync(String multisignFid, KeyInfo keyInfo) {
        // Show waiting dialog
        com.fc.freer.ui.WaitingDialog waitingDialog = new com.fc.freer.ui.WaitingDialog(this, getString(R.string.switching_fid));
        runOnUiThread(() -> waitingDialog.show());

        // Execute switching operation on background thread
        new Thread(() -> {
            try {
                FidManager fidManager = FidManager.getInstance();
                if (fidManager == null) {
                    TimberLogger.e(TAG, "FidManager is null!");
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        ToastUtils.makeText(this, getString(R.string.fid_manager_not_available));
                    });
                    return;
                }

                String previousLiveFid = fidManager.getLiveFid();

                // Use the proper FidManager.switchLiveFid() method
                boolean success = fidManager.switchLiveFid(this, multisignFid, keyInfo);

                String currentLiveFid = fidManager.getLiveFid();
                KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();

                runOnUiThread(() -> {
                    waitingDialog.dismiss();

                    if (success) {
                        ToastUtils.makeText(this, getString(R.string.fid_switched));
                        TimberLogger.d(TAG, "Switch successful, finishing activity");
                    } else if (multisignFid.equals(previousLiveFid)) {
                        ToastUtils.makeText(this, getString(R.string.already_viewing_multisign_fid, multisignFid));
                        TimberLogger.d(TAG, "Already viewing this FID, treating as success");
                    } else {
                        ToastUtils.makeText(this, getString(R.string.failed_to_switch_to_multisign_fid));
                        TimberLogger.w(TAG, "switchLiveFid returned false for unknown reason");
                        return; // Don't finish activity on failure
                    }

                    // Return success result to HomeActivity to refresh UI
                    setResult(RESULT_OK);
                    finish();

                    // Refresh cidInfo from API in background
                    refreshCidInfoAsync(multisignFid, fidManager);
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error switching to multisig FID %s: %s", multisignFid, e.getMessage(), e);
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    ToastUtils.makeText(this, getString(R.string.failed_to_switch_to_multisign_fid_error, e.getMessage()));
                });
            }
        }).start();
    }
    
    /**
     * Refresh cidInfo from API and update KeyInfo (similar to PersonPopupMenuHelper)
     */
    private void refreshCidInfoAsync(String fid, FidManager fidManager) {
        new Thread(() -> {
            try {
                com.fc.freer.utils.ApiCenter apiCenter = com.fc.freer.utils.ApiCenter.getInstance();
                com.fc.fc_ajdk.fapi.client.FapiClient fapiClient = (com.fc.fc_ajdk.fapi.client.FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null || !fapiClient.isConnected()) {
                    TimberLogger.w(TAG, "FAPI client not available for freerInfo refresh");
                    return;
                }
                
                // Fetch fresh freerInfo from API
                Freer freerInfo = fapiClient.freerById(fid);

                if (freerInfo != null) {
                    // Update KeyInfo with fresh data
                    KeyInfo currentKeyInfo = fidManager.getLiveKeyInfo();
                    if (currentKeyInfo != null) {
                        // Preserve user-specific data while updating API data
                        KeyInfo updatedKeyInfo = KeyInfo.updateFromCid(freerInfo, currentKeyInfo);
                        // Update in FidManager and save
                        if (fidManager.updateKeyInfo(this, fid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Updated freerInfo for multisig FID: %s - Cash: %s, Balance: %s, CD: %s",
                                fid, freerInfo.getCash(), freerInfo.getBalance(), freerInfo.getCd());
                        }
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch freerInfo for multisig FID: %s", fid);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for multisig FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_ADD_MULTISIGN_FID && resultCode == RESULT_OK) {
            // Reload multisig FID list after adding new one
            loadMultisignFidList();
            ToastUtils.makeText(this, getString(R.string.multisign_fid_added_successfully));
        }
    }
    
    private void updateCardSelection(KeyInfo selectedKeyInfo) {
        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.key_card_background);
        }
        
        // Find and highlight the selected card
        for (int i = 0; i < multisignFidListContainer.getChildCount(); i++) {
            View cardView = multisignFidListContainer.getChildAt(i);
            TextView keyIdView = cardView.findViewById(R.id.key_id);
            if (keyIdView != null && selectedKeyInfo.getId().equals(keyIdView.getText().toString())) {
                cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;
                break;
            }
        }
    }
}