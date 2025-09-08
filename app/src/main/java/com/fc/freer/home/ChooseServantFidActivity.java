package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
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

public class ChooseServantFidActivity extends BaseCryptoActivity {
    private static final String TAG = "ChooseServantFidActivity";
    public static final int REQUEST_CODE_ADD_SERVANT_FID = 3002;
    
    private LinearLayout servantFidListContainer;
    private Button cancelButton;
    private Button addButton;
    private Setting currentSetting;
    private KeyInfo selectedKeyInfo;
    private List<KeyInfo> servantFidList;
    private KeyCardManager keyCardManager;
    private View selectedCardView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get current setting
        currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null) {
            Toast.makeText(this, "Current setting not available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Load servant FID list
        loadServantFidList();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_choose_servant_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.choose_servant_fid);
    }

    @Override
    protected void initializeViews() {
        servantFidListContainer = findViewById(R.id.servant_fid_list_container);
        cancelButton = findViewById(R.id.cancel_button);
        addButton = findViewById(R.id.add_button);
        
        // Initialize KeyCardManager with clickToReturn mode and delete menu
        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(MenuItem.createDeleteMenuItem(this));
        keyCardManager = new KeyCardManager(this, servantFidListContainer, null, true, menuItems);
        keyCardManager.setShowDefaultMenuItems(true); // Show "Add to FID list" and "Clear FID list"
        keyCardManager.setOnKeyClickedListener(this::onServantFidSelected);
        keyCardManager.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    @Override
    protected void setupButtons() {
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        addButton.setOnClickListener(v -> {
            // Launch AddServantFidActivity
            Intent intent = new Intent(this, AddServantFidActivity.class);
            startActivityForResult(intent, REQUEST_CODE_ADD_SERVANT_FID);
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // This activity doesn't use QR scanning
    }

    private void loadServantFidList() {
        List<KeyInfo> servantList = currentSetting.getServantKeyInfoList();
        if (servantList == null || servantList.isEmpty()) {
            // No servant FIDs, show create button only
            addButton.setEnabled(true);
            servantFidList = new ArrayList<>();
            displayServantFidList();
            return;
        }

        servantFidList = new ArrayList<>(servantList);
        
        // Preload avatars for all keys in background
        preloadAvatars();
        
        displayServantFidList();
    }
    
    /**
     * Preload avatars for all keys in background to improve performance
     */
    private void preloadAvatars() {
        if (servantFidList != null && !servantFidList.isEmpty()) {
            String[] fids = servantFidList.stream()
                .map(KeyInfo::getId)
                .toArray(String[]::new);
            
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            avatarManager.preloadAvatars(fids);
            
            TimberLogger.d(TAG, "Preloading avatars for %d servant FIDs", fids.length);
        }
    }

    private void displayServantFidList() {
        keyCardManager.clearAll();
        selectedCardView = null;
        
        if (servantFidList.isEmpty()) {
            // Show "No servant FIDs available" message
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.no_servant_fids_available));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            servantFidListContainer.addView(emptyMessageView);
            return;
        }
        
        for (KeyInfo keyInfo : servantFidList) {
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    private void onServantFidSelected(KeyInfo keyInfo) {
        selectedKeyInfo = keyInfo;
        
        // Update card selection highlighting
        updateCardSelection(keyInfo);
        
        // Switch to the selected servant FID as liveFid using the same pattern as PersonPopupMenuHelper
        switchToServantFidAsync(keyInfo.getId(), keyInfo);
    }
    
    private void onMenuItemClicked(String menuItemId, KeyInfo keyInfo) {
        if ("delete".equals(menuItemId)) {
            // Remove the servant FID from the current setting
            if (currentSetting != null) {
                boolean removed = currentSetting.removeServantKeyInfo(keyInfo.getId());
                if (removed) {
                    // Save the updated setting
                    SettingManager.getInstance().saveSettings(this,currentSetting);
                    
                    // Also update the current setting in SettingManager to ensure it stays in sync
                    SettingManager.getInstance().setCurrentSetting(this,currentSetting);
                    
                    // Reload the list to reflect the changes
                    loadServantFidList();
                    
                    Toast.makeText(this, "Servant FID deleted", Toast.LENGTH_SHORT).show();
                    TimberLogger.d(TAG, "Successfully removed servant FID: %s", keyInfo.getId());
                } else {
                    Toast.makeText(this, "Failed to remove servant FID", Toast.LENGTH_SHORT).show();
                    TimberLogger.w(TAG, "Failed to remove servant FID: %s", keyInfo.getId());
                }
            }
        }
    }
    
    /**
     * Switch to servant FID using proper FidManager.switchLiveFid() method
     */
    private void switchToServantFidAsync(String servantFid, KeyInfo keyInfo) {
        TimberLogger.d(TAG, "=== SERVANT FID SWITCH START ===");
        TimberLogger.d(TAG, "Target servant FID: %s", servantFid);
        TimberLogger.d(TAG, "Target KeyInfo - ID: %s, CID: %s, Label: %s", 
            keyInfo != null ? keyInfo.getId() : "null",
            keyInfo != null ? keyInfo.getCid() : "null", 
            keyInfo != null ? keyInfo.getLabel() : "null");
        
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
                TimberLogger.d(TAG, "BEFORE switchLiveFid:");
                TimberLogger.d(TAG, "  - Previous liveFid: %s", previousLiveFid);
                TimberLogger.d(TAG, "  - Target servantFid: %s", servantFid);

                // Use the proper FidManager.switchLiveFid() method
                boolean success = fidManager.switchLiveFid(this, servantFid, keyInfo);
                
                String currentLiveFid = fidManager.getLiveFid();
                KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();
                TimberLogger.d(TAG, "AFTER switchLiveFid:");
                TimberLogger.d(TAG, "  - Switch success: %s", success);
                TimberLogger.d(TAG, "  - Current liveFid: %s", currentLiveFid);
                TimberLogger.d(TAG, "  - Current liveKeyInfo ID: %s", currentLiveKeyInfo != null ? currentLiveKeyInfo.getId() : "null");
                TimberLogger.d(TAG, "  - Current liveKeyInfo CID: %s", currentLiveKeyInfo != null ? currentLiveKeyInfo.getCid() : "null");

                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, "Switched to servant FID: " + servantFid, Toast.LENGTH_SHORT).show();
                        TimberLogger.d(TAG, "Switch successful, finishing activity");
                    } else if (servantFid.equals(previousLiveFid)) {
                        Toast.makeText(this, "Already viewing FID: " + servantFid, Toast.LENGTH_SHORT).show();
                        TimberLogger.d(TAG, "Already viewing this FID, treating as success");
                    } else {
                        Toast.makeText(this, "Failed to switch to servant FID", Toast.LENGTH_SHORT).show();
                        TimberLogger.w(TAG, "switchLiveFid returned false for unknown reason");
                        return; // Don't finish activity on failure
                    }

                    // Return success result to HomeActivity to refresh UI
                    setResult(RESULT_OK);
                    finish();
                    
                    // Refresh cidInfo from API in background
                    refreshCidInfoAsync(servantFid, fidManager);
                });
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error switching to servant FID %s: %s", servantFid, e.getMessage(), e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to switch to servant FID: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                        KeyInfo updatedKeyInfo = KeyInfo.fromCid(cidInfo);
                        updatedKeyInfo.setLabel(currentKeyInfo.getLabel());
                        updatedKeyInfo.setPrikeyCipher(currentKeyInfo.getPrikeyCipher());
                        updatedKeyInfo.setWatchOnly(currentKeyInfo.getWatchOnly());
                        updatedKeyInfo.setSaveTime(currentKeyInfo.getSaveTime());
                        
                        // Update in FidManager and save
                        if (fidManager.updateKeyInfo(this, fid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Updated cidInfo for servant FID: %s - Cash: %s, Balance: %s, CD: %s", 
                                fid, cidInfo.getCash(), cidInfo.getBalance(), cidInfo.getCd());
                        }
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch cidInfo for servant FID: %s", fid);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for servant FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_ADD_SERVANT_FID && resultCode == RESULT_OK) {
            // Reload servant FID list after adding new one
            loadServantFidList();
            Toast.makeText(this, "Servant FID added successfully", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateCardSelection(KeyInfo selectedKeyInfo) {
        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.key_card_background);
        }
        
        // Find and highlight the selected card
        for (int i = 0; i < servantFidListContainer.getChildCount(); i++) {
            View cardView = servantFidListContainer.getChildAt(i);
            TextView keyIdView = cardView.findViewById(R.id.key_id);
            if (keyIdView != null && selectedKeyInfo.getId().equals(keyIdView.getText().toString())) {
                cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;
                break;
            }
        }
    }
}