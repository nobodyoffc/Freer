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

import java.util.ArrayList;
import java.util.List;

public class ChooseWatchedFidActivity extends BaseCryptoActivity {
    private static final String TAG = "ChooseWatchedFidActivity";
    public static final int REQUEST_CODE_ADD_WATCHED_FID = 2001;
    
    private LinearLayout watchedFidListContainer;
    private Button cancelButton;
    private Button addButton;
    private Setting currentSetting;
    private KeyInfo selectedKeyInfo;
    private List<KeyInfo> watchedFidList;
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
        
        // Load watched FID list
        loadWatchedFidList();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_choose_watched_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.choose_watched_fid);
    }

    @Override
    protected void initializeViews() {
        watchedFidListContainer = findViewById(R.id.watched_fid_list_container);
        cancelButton = findViewById(R.id.cancel_button);
        addButton = findViewById(R.id.add_button);
        
        // Initialize KeyCardManager with clickToReturn mode
        keyCardManager = new KeyCardManager(this, watchedFidListContainer, null, true, null);
        keyCardManager.setOnKeyClickedListener(this::onWatchedFidSelected);
    }

    @Override
    protected void setupButtons() {
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        addButton.setOnClickListener(v -> {
            // Launch AddWatchedFidActivity
            Intent intent = new Intent(this, AddWatchedFidActivity.class);
            startActivityForResult(intent, REQUEST_CODE_ADD_WATCHED_FID);
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // This activity doesn't use QR scanning
    }

    private void loadWatchedFidList() {
        List<KeyInfo> watchedList = currentSetting.getWatchedKeyInfoList();
        if (watchedList == null || watchedList.isEmpty()) {
            // No watched FIDs, show create button only
            addButton.setEnabled(true);
            watchedFidList = new ArrayList<>();
            displayWatchedFidList();
            return;
        }

        watchedFidList = new ArrayList<>(watchedList);
        
        // Preload avatars for all keys in background
        preloadAvatars();
        
        displayWatchedFidList();
    }
    
    /**
     * Preload avatars for all keys in background to improve performance
     */
    private void preloadAvatars() {
        if (watchedFidList != null && !watchedFidList.isEmpty()) {
            String[] fids = watchedFidList.stream()
                .map(KeyInfo::getId)
                .toArray(String[]::new);
            
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            avatarManager.preloadAvatars(fids);
            
            TimberLogger.d(TAG, "Preloading avatars for %d watched FIDs", fids.length);
        }
    }

    private void displayWatchedFidList() {
        keyCardManager.clearAll();
        selectedCardView = null;
        
        if (watchedFidList.isEmpty()) {
            // Show "No watched FIDs available" message
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.no_watched_fids_available));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            watchedFidListContainer.addView(emptyMessageView);
            return;
        }
        
        for (KeyInfo keyInfo : watchedFidList) {
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    private void onWatchedFidSelected(KeyInfo keyInfo) {
        selectedKeyInfo = keyInfo;
        
        // Update card selection highlighting
        updateCardSelection(keyInfo);
        
        // Switch to the selected watched FID as liveFid using the same pattern as PersonPopupMenuHelper
        switchToWatchedFidAsync(keyInfo.getId(), keyInfo);
    }
    
    /**
     * Switch to watched FID using proper FidManager.switchLiveFid() method
     */
    private void switchToWatchedFidAsync(String watchedFid, KeyInfo keyInfo) {
        TimberLogger.d(TAG, "=== WATCHED FID SWITCH START ===");
        TimberLogger.d(TAG, "Target watched FID: %s", watchedFid);
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
                TimberLogger.d(TAG, "  - Target watchedFid: %s", watchedFid);

                // Use the proper FidManager.switchLiveFid() method
                boolean success = fidManager.switchLiveFid(this, watchedFid, keyInfo);
                
                String currentLiveFid = fidManager.getLiveFid();
                KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();
                TimberLogger.d(TAG, "AFTER switchLiveFid:");
                TimberLogger.d(TAG, "  - Switch success: %s", success);
                TimberLogger.d(TAG, "  - Current liveFid: %s", currentLiveFid);
                TimberLogger.d(TAG, "  - Current liveKeyInfo ID: %s", currentLiveKeyInfo != null ? currentLiveKeyInfo.getId() : "null");
                TimberLogger.d(TAG, "  - Current liveKeyInfo CID: %s", currentLiveKeyInfo != null ? currentLiveKeyInfo.getCid() : "null");

                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, "Switched to watched FID: " + watchedFid, Toast.LENGTH_SHORT).show();
                        TimberLogger.d(TAG, "Switch successful, finishing activity");
                    } else if (watchedFid.equals(previousLiveFid)) {
                        Toast.makeText(this, "Already viewing FID: " + watchedFid, Toast.LENGTH_SHORT).show();
                        TimberLogger.d(TAG, "Already viewing this FID, treating as success");
                    } else {
                        Toast.makeText(this, "Failed to switch to watched FID", Toast.LENGTH_SHORT).show();
                        TimberLogger.w(TAG, "switchLiveFid returned false for unknown reason");
                        return; // Don't finish activity on failure
                    }

                    // Return success result to HomeActivity to refresh UI
                    setResult(RESULT_OK);
                    finish();
                    
                    // Refresh cidInfo from API in background
                    refreshCidInfoAsync(watchedFid, fidManager);
                });
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error switching to watched FID %s: %s", watchedFid, e.getMessage(), e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to switch to watched FID: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                            TimberLogger.d(TAG, "Updated cidInfo for watched FID: %s - Cash: %s, Balance: %s, CD: %s", 
                                fid, cidInfo.getCash(), cidInfo.getBalance(), cidInfo.getCd());
                        }
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch cidInfo for watched FID: %s", fid);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for watched FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_ADD_WATCHED_FID && resultCode == RESULT_OK) {
            // Reload watched FID list after adding new one
            loadWatchedFidList();
            Toast.makeText(this, "Watched FID added successfully", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateCardSelection(KeyInfo selectedKeyInfo) {
        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.key_card_background);
        }
        
        // Find and highlight the selected card
        for (int i = 0; i < watchedFidListContainer.getChildCount(); i++) {
            View cardView = watchedFidListContainer.getChildAt(i);
            TextView keyIdView = cardView.findViewById(R.id.key_id);
            if (keyIdView != null && selectedKeyInfo.getId().equals(keyIdView.getText().toString())) {
                cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;
                break;
            }
        }
    }
}