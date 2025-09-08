package com.fc.freer.home;

import static com.fc.fc_ajdk.data.fcData.KeyInfo.updateFromCid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cid;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.KeyCardManager;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.freer.ui.MenuItem;

import java.util.ArrayList;
import java.util.List;

public class ChooseMultisignFidActivity extends BaseCryptoActivity {
    private static final String TAG = "ChooseMultisignFidActivity";
    public static final int REQUEST_CODE_ADD_MULTISIGN_FID = 3001;
    
    private LinearLayout multisignFidListContainer;
    private Button cancelButton;
    private Button addButton;
    private Setting currentSetting;
    private KeyInfo selectedKeyInfo;
    private List<KeyInfo> multisignFidList;
    private KeyCardManager keyCardManager;
    private View selectedCardView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get current setting (always reload to get latest data)
        currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null) {
            Toast.makeText(this, "Current setting not available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Load multisign FID list
        loadMultisignFidList();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Reload the current setting and multisign list when activity resumes
        // This ensures we have the latest data if user navigated back from another activity
        currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting != null) {
            loadMultisignFidList();
            TimberLogger.d(TAG, "Reloaded multisign FID list on resume");
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
        cancelButton = findViewById(R.id.cancel_button);
        addButton = findViewById(R.id.add_button);
        
        // Initialize KeyCardManager with clickToReturn mode and delete menu
        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(MenuItem.createDeleteMenuItem(this));
        keyCardManager = new KeyCardManager(this, multisignFidListContainer, null, true, menuItems);
        keyCardManager.setShowDefaultMenuItems(true); // Show "Add to FID list" and "Clear FID list"
        keyCardManager.setOnKeyClickedListener(this::onMultisignFidSelected);
        keyCardManager.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    @Override
    protected void setupButtons() {
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        addButton.setOnClickListener(v -> {
            // Launch AddMultisignFidActivity
            Intent intent = new Intent(this, AddMultisignFidActivity.class);
            startActivityForResult(intent, REQUEST_CODE_ADD_MULTISIGN_FID);
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // This activity doesn't use QR scanning
    }

    private void loadMultisignFidList() {
        List<KeyInfo> multisignList = currentSetting.getMultisignKeyInfoList();
        if (multisignList == null || multisignList.isEmpty()) {
            // No multisign FIDs, show create button only
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
            
            TimberLogger.d(TAG, "Preloading avatars for %d multisign FIDs", fids.length);
        }
    }

    private void displayMultisignFidList() {
        keyCardManager.clearAll();
        selectedCardView = null;
        
        if (multisignFidList.isEmpty()) {
            // Show "No multisign FIDs available" message
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.no_multisign_fids_available));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            multisignFidListContainer.addView(emptyMessageView);
            return;
        }
        
        for (KeyInfo keyInfo : multisignFidList) {
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    private void onMultisignFidSelected(KeyInfo keyInfo) {
        selectedKeyInfo = keyInfo;
        
        // Update card selection highlighting
        updateCardSelection(keyInfo);
        
        // Switch to the selected multisign FID as liveFid using the same pattern as PersonPopupMenuHelper
        switchToMultisignFidAsync(keyInfo.getId(), keyInfo);
    }
    
    private void onMenuItemClicked(String menuItemId, KeyInfo keyInfo) {
        if ("delete".equals(menuItemId)) {
            // Remove the multisign FID from the current setting
            if (currentSetting != null) {
                boolean removed = currentSetting.removeMultisignKeyInfo(keyInfo.getId());
                if (removed) {
                    // Save the updated setting
                    SettingManager.getInstance().saveSettings(this,currentSetting);
                    
                    // Also update the current setting in SettingManager to ensure it stays in sync
                    SettingManager.getInstance().setCurrentSetting(this,currentSetting);
                    
                    // Reload the list to reflect the changes
                    loadMultisignFidList();
                    
                    Toast.makeText(this, "Multisign FID deleted", Toast.LENGTH_SHORT).show();
                    TimberLogger.d(TAG, "Successfully removed multisign FID: %s", keyInfo.getId());
                } else {
                    Toast.makeText(this, "Failed to remove multisign FID", Toast.LENGTH_SHORT).show();
                    TimberLogger.w(TAG, "Failed to remove multisign FID: %s", keyInfo.getId());
                }
            }
        }
    }
    
    /**
     * Switch to multisign FID using proper FidManager.switchLiveFid() method
     */
    private void switchToMultisignFidAsync(String multisignFid, KeyInfo keyInfo) {
        
        // Execute switching operation on background thread
        new Thread(() -> {
            try {
                FidManager fidManager = FidManager.getInstance();
                if (fidManager == null) {
                    TimberLogger.e(TAG, "FidManager is null!");
                    runOnUiThread(() -> {
                        Toast.makeText(this, "FidManager not available", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                String previousLiveFid = fidManager.getLiveFid();

                // Use the proper FidManager.switchLiveFid() method
                boolean success = fidManager.switchLiveFid(this, multisignFid, keyInfo);
                
                String currentLiveFid = fidManager.getLiveFid();
                KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();

                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, "Switched to multisign FID: " + multisignFid, Toast.LENGTH_SHORT).show();
                        TimberLogger.d(TAG, "Switch successful, finishing activity");
                    } else if (multisignFid.equals(previousLiveFid)) {
                        Toast.makeText(this, "Already viewing FID: " + multisignFid, Toast.LENGTH_SHORT).show();
                        TimberLogger.d(TAG, "Already viewing this FID, treating as success");
                    } else {
                        Toast.makeText(this, "Failed to switch to multisign FID", Toast.LENGTH_SHORT).show();
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
                TimberLogger.e(TAG, "Error switching to multisign FID %s: %s", multisignFid, e.getMessage(), e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to switch to multisign FID: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                com.fc.freer.network.ApipClient apipClient = (com.fc.freer.network.ApipClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.APIP);
                if (apipClient == null || !apipClient.getConnected()) {
                    TimberLogger.w(TAG, "APIP client not available for cidInfo refresh");
                    return;
                }
                
                // Fetch fresh cidInfo from API
                com.fc.fc_ajdk.data.fchData.Cid cidInfo = apipClient.cidInfoById(fid, 
                    com.fc.fc_ajdk.utils.http.RequestMethod.POST, 
                    com.fc.fc_ajdk.utils.http.AuthType.FC_SIGN_BODY, 
                    this);
                    
                if (cidInfo != null) {
                    // Update KeyInfo with fresh data
                    KeyInfo currentKeyInfo = fidManager.getLiveKeyInfo();
                    if (currentKeyInfo != null) {
                        // Preserve user-specific data while updating API data
                        KeyInfo updatedKeyInfo = KeyInfo.updateFromCid(cidInfo, currentKeyInfo);
                        // Update in FidManager and save
                        if (fidManager.updateKeyInfo(this, fid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Updated cidInfo for multisign FID: %s - Cash: %s, Balance: %s, CD: %s", 
                                fid, cidInfo.getCash(), cidInfo.getBalance(), cidInfo.getCd());
                        }
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch cidInfo for multisign FID: %s", fid);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for multisign FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_ADD_MULTISIGN_FID && resultCode == RESULT_OK) {
            // Reload multisign FID list after adding new one
            loadMultisignFidList();
            Toast.makeText(this, "Multisign FID added successfully", Toast.LENGTH_SHORT).show();
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