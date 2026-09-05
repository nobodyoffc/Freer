package com.fc.freer.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.freer.home.ChooseMultisigFidActivity;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.MainActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.initiate.SettingManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;

/**
 * PopupMenuHelper for person/FID switching menu
 */
public class PersonPopupMenuHelper {
    private static final String TAG = "PersonPopupMenuHelper";
    public static final String MAIN_FID = "Main FID";

    private Activity activity;
    private PopupWindow popupWindow;
    private FidManager fidManager;
    private WaitingDialog waitingDialog;
    
    public PersonPopupMenuHelper(Activity activity) {
        this.activity = activity;
        this.fidManager = FidManager.getInstance();
    }
    
    /**
     * Show the person popup menu
     */
    public void showPersonMenu(View anchorView) {
        try {
            // Create popup window content
            View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_menu_person, null);
            
            // Configure popup window
            popupWindow = new PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            );
            
            // Set up the popup content
            setupPopupContent(popupView);
            
            // Show popup
            popupWindow.showAsDropDown(anchorView, 0, 10);
            
            TimberLogger.d(TAG, "Person popup menu shown");
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing person popup menu: %s", e.getMessage());
            ToastUtils.showError(activity, activity.getString(R.string.error_showing_menu));
        }
    }
    
    /**
     * Determine the type of the current live FID
     */
    private String determineFidType(String liveFid) {
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null) {
            return "Unknown";
        }
        
        // Check if it's the main FID
        if (fidManager.isLiveFidMain()) {
            return MAIN_FID;
        }
        
        // Check if it's the master FID
        String master = currentSetting.getMainKeyInfo().getMaster();
        if (liveFid.equals(master)) {
            return "Master";
        }
        
        // Check if it's in watched list
        if (currentSetting.getWatchedKeyInfoList() != null) {
            for (KeyInfo keyInfo : currentSetting.getWatchedKeyInfoList()) {
                if (liveFid.equals(keyInfo.getId())) {
                    return "Watched";
                }
            }
        }
        
        // Check if it's in multisig list
        if (currentSetting.getMultisigKeyInfoList() != null) {
            for (KeyInfo keyInfo : currentSetting.getMultisigKeyInfoList()) {
                if (liveFid.equals(keyInfo.getId())) {
                    return "Multisig";
                }
            }
        }
        
        // Check if it's in servant list
        if (currentSetting.getServantKeyInfoList() != null) {
            for (KeyInfo keyInfo : currentSetting.getServantKeyInfoList()) {
                if (liveFid.equals(keyInfo.getId())) {
                    return "Servant";
                }
            }
        }
        
        return "Unknown";
    }
    
    /**
     * Set up the popup content with current FID info and menu actions
     */
    private void setupPopupContent(View popupView) {
        // Current FID display
        TextView currentFidText = popupView.findViewById(R.id.current_fid_text);
        ImageView currentFidAvatar = popupView.findViewById(R.id.current_fid_avatar);
        String liveFid = fidManager.getLiveFid();
        boolean isMainFid = fidManager.isLiveFidMain();

        if (liveFid != null) {
            String fidType = determineFidType(liveFid);
            String displayText = "Living: " + fidType;
            currentFidText.setText(displayText);

            // Set the living FID avatar
            AvatarManager avatarManager = AvatarManager.getInstance(activity);
            Bitmap avatarBitmap = avatarManager.getAvatarBitmap(liveFid);
            if (avatarBitmap != null) {
                currentFidAvatar.setImageBitmap(avatarBitmap);
            } else {
                currentFidAvatar.setImageResource(R.drawable.ic_person);
            }
        } else {
            currentFidText.setText(R.string.living_not_set);
            currentFidAvatar.setImageResource(R.drawable.ic_person);
        }
        
        // Switch to Main FID menu item removed - redundant with Main FID option
        
        // Main FID
        TextView mainFidMenu = popupView.findViewById(R.id.menu_main_fid);
        String fidType = determineFidType(liveFid);
        if (MAIN_FID.equals(fidType)) {
            // Current FID is main FID - make it hint color and unclickable
            mainFidMenu.setTextColor(activity.getColor(R.color.hint));
            mainFidMenu.setClickable(false);
            mainFidMenu.setOnClickListener(null);
        } else {
            // Not current FID - make it clickable
            mainFidMenu.setTextColor(activity.getColor(R.color.text));
            mainFidMenu.setClickable(true);
            mainFidMenu.setOnClickListener(v -> {
                dismissPopup();
                showMainFidOptions();
            });
        }
        
        // My Master
        TextView myMasterMenu = popupView.findViewById(R.id.menu_my_master);
        if (MAIN_FID.equals(fidType)) {
            // Main FID - make it clickable
            myMasterMenu.setTextColor(activity.getColor(R.color.text));
            myMasterMenu.setClickable(true);
            myMasterMenu.setOnClickListener(v -> {
                dismissPopup();
                showMasterOptions();
            });

        } else {
            // Current FID is not main FID - make it hint color and unclickable
            myMasterMenu.setTextColor(activity.getColor(R.color.hint));
            myMasterMenu.setClickable(false);
            myMasterMenu.setOnClickListener(null);
        }
        
        // My Watched FIDs
        TextView watchedFidsMenu = popupView.findViewById(R.id.menu_my_watched_fids);
        if (MAIN_FID.equals(fidType)) {
            // Main FID - make it clickable
            watchedFidsMenu.setTextColor(activity.getColor(R.color.text));
            watchedFidsMenu.setClickable(true);
            watchedFidsMenu.setOnClickListener(v -> {
                dismissPopup();
                showWatchedFidsOptions();
            });
        } else {
            // Current FID is not main FID - make it hint color and unclickable
            watchedFidsMenu.setTextColor(activity.getColor(R.color.hint));
            watchedFidsMenu.setClickable(false);
            watchedFidsMenu.setOnClickListener(null);
        }
        
        // My Multisig FIDs
        TextView multisignFidsMenu = popupView.findViewById(R.id.menu_my_multisign_fids);
        if (MAIN_FID.equals(fidType)) {
            // Main FID - make it clickable
            multisignFidsMenu.setTextColor(activity.getColor(R.color.text));
            multisignFidsMenu.setClickable(true);
            multisignFidsMenu.setOnClickListener(v -> {
                dismissPopup();
                showMultisignFidsOptions();
            });
        } else {
            // Current FID is not main FID - make it hint color and unclickable
            multisignFidsMenu.setTextColor(activity.getColor(R.color.hint));
            multisignFidsMenu.setClickable(false);
            multisignFidsMenu.setOnClickListener(null);
        }
        
        // My Servants
        TextView servantsMenu = popupView.findViewById(R.id.menu_my_servants);
        if (MAIN_FID.equals(fidType)) {
            // Main FID - make it clickable
            servantsMenu.setTextColor(activity.getColor(R.color.text));
            servantsMenu.setClickable(true);
            servantsMenu.setOnClickListener(v -> {
                dismissPopup();
                showServantsOptions();
            });
        } else {
            // Current FID is not main FID - make it hint color and unclickable
            servantsMenu.setTextColor(activity.getColor(R.color.hint));
            servantsMenu.setClickable(false);
            servantsMenu.setOnClickListener(null);
        }
        
        // Quit Main FID
        View quitMainFidMenu = popupView.findViewById(R.id.menu_quit_main_fid);
        ImageView quitMainFidAvatar = popupView.findViewById(R.id.menu_quit_main_fid_avatar);

        // Set the main FID avatar
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting != null && currentSetting.getMainKeyInfo() != null) {
            String mainFid = currentSetting.getMainKeyInfo().getId();
            if (mainFid != null) {
                AvatarManager avatarManager = AvatarManager.getInstance(activity);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(mainFid);
                if (avatarBitmap != null) {
                    quitMainFidAvatar.setImageBitmap(avatarBitmap);
                } else {
                    quitMainFidAvatar.setImageResource(R.drawable.ic_person);
                }
            }
        }

        quitMainFidMenu.setOnClickListener(v -> {
            dismissPopup();
            quitMainFid();
        });
    }
    
    
    /**
     * Show main FID options
     */
    private void showMainFidOptions() {
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null || currentSetting.getMainKeyInfo() == null) {
            ToastUtils.showWarning(activity, activity.getString(R.string.no_main_fid_available));
            return;
        }
        
        KeyInfo mainKeyInfo = currentSetting.getMainKeyInfo();
        
        // Switch to main FID directly without confirmation dialog
        switchToFid(mainKeyInfo.getId(), mainKeyInfo);
    }
    
    /**
     * Show master FID options - check if master exists, if not prompt to set one
     */
    private void showMasterOptions() {
        Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting == null) {
            ToastUtils.showWarning(activity, activity.getString(R.string.no_current_setting_available));
            return;
        }

        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.showWarning(activity, activity.getString(R.string.no_live_fid_info_available));
            return;
        }
        
        // Check if master is set for the live FID
        String masterFid = liveKeyInfo.getMaster();
        if (masterFid != null && !masterFid.trim().isEmpty()) {
            // Master exists, try to switch to it
            switchToMaster();
        } else {
            // No master set, ask if user wants to set one
            promptSetMaster();
        }
    }
    
    /**
     * Switch to the master FID
     */
    private void switchToMaster() {
        try {
            // Try to get master KeyInfo from current setting first
            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            String masterFid = currentSetting.getMainKeyInfo().getMaster();

            if(masterFid == null){
                ToastUtils.showWarning(activity, activity.getString(R.string.no_master_yet));
                return;
            }
            KeyInfo masterKeyInfo = currentSetting.getKeyInfoMap().get(masterFid);

            if (masterKeyInfo == null) {
                // Master KeyInfo not available locally, fetch it using FapiClient on background thread
                fetchMasterKeyInfoAsync(masterFid, currentSetting);
                return; // Will handle switching after async fetch completes
            }

            // Switch to master FID on background thread (even when KeyInfo exists locally)
            switchToMasterAsync(masterFid, masterKeyInfo);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error switching to master: %s", e.getMessage());
            ToastUtils.showError(activity, activity.getString(R.string.error_switching_to_master_fid));
        }
    }
    
    /**
     * Fetch master KeyInfo using FapiClient when not available locally
     */
    private KeyInfo fetchMasterKeyInfo(String masterFid, Setting currentSetting) {
        try {
            // Get FapiClient from current setting
            ApiCenter apiCenter = ApiCenter.getInstance();
            FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient == null) {
                ToastUtils.showError(activity, activity.getString(R.string.apip_client_not_available));
                return null;
            }
            
            // Fetch CID info using FapiClient
            Freer freerInfo = fapiClient.getFreer(masterFid);

            if (freerInfo != null) {
                // Convert Freer to KeyInfo using existing fromCid method
                KeyInfo masterKeyInfo = KeyInfo.fromCid(freerInfo);
                
                // Save to currentSetting.keyInfoMap
                if (currentSetting.getKeyInfoMap() == null) {
                    currentSetting.setKeyInfoMap(new java.util.HashMap<>());
                }
                currentSetting.getKeyInfoMap().put(masterFid, masterKeyInfo);
                
                // Save the setting
                SettingManager.getInstance().saveSettings(activity, currentSetting);
                
                TimberLogger.d(TAG, "Successfully fetched and saved master KeyInfo for: %s", masterFid);
                return masterKeyInfo;
            } else {
                ToastUtils.showError(activity, activity.getString(R.string.failed_to_fetch_master_fid_info_from_network));
                TimberLogger.w(TAG, "Failed to fetch CID info for master FID: %s", masterFid);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching master KeyInfo: %s", e.getMessage());
            ToastUtils.showError(activity, activity.getString(R.string.error_fetching_master_fid_info));
        }
        return null;
    }
    
    /**
     * Switch to master FID asynchronously (for when KeyInfo already exists locally)
     */
    private void switchToMasterAsync(String masterFid, KeyInfo masterKeyInfo) {
        // Show waiting dialog
        showWaitingDialog("Switching to master FID...");
        
        // Use FidManager's async method with callback
        fidManager.switchLiveFidAsync(activity, masterFid, masterKeyInfo, new FidManager.FidSwitchCallback() {
            @Override
            public void onSwitchComplete(boolean success, String message) {
                // Dismiss waiting dialog
                dismissWaitingDialog();
                
                if (success) {
                    refreshHomeActivityCard();
                    ToastUtils.makeText(activity, activity.getString(R.string.fid_switched));

                    // Refresh cidInfo from API for the master FID
                    refreshCidInfoAsync(masterFid);
                } else {
                    refreshHomeActivityCard(); // Refresh to show current FID
                    ToastUtils.showError(activity, message != null ? message : activity.getString(R.string.failed_to_switch_fid,""));
                }
            }
        });
    }
    
    /**
     * Fetch master KeyInfo using FapiClient asynchronously
     */
    private void fetchMasterKeyInfoAsync(String masterFid, Setting currentSetting) {
        // Show waiting dialog
        showWaitingDialog("Fetching master FID info...");
        
        // Execute network operation on background thread
        new Thread(() -> {
            try {
                KeyInfo masterKeyInfo = fetchMasterKeyInfo(masterFid, currentSetting);
                
                if (masterKeyInfo != null) {
                    // Successfully fetched, now switch using FidManager's async method
                    activity.runOnUiThread(() -> {
                        // Update waiting dialog message
                        updateWaitingDialogMessage("Switching to master FID...");
                    });
                    
                    // Use FidManager's async method with callback
                    fidManager.switchLiveFidAsync(activity, masterFid, masterKeyInfo, new FidManager.FidSwitchCallback() {
                        @Override
                        public void onSwitchComplete(boolean success, String message) {
                            // Dismiss waiting dialog
                            dismissWaitingDialog();

                            if (success) {
                                refreshHomeActivityCard();
                                ToastUtils.makeText(activity, activity.getString(R.string.got_master_info_and_switched));

                                // Refresh cidInfo from API for the master FID
                                refreshCidInfoAsync(masterFid);
                            } else {
                                refreshHomeActivityCard(); // Refresh to show current FID
                                ToastUtils.makeText(activity, message != null ? message : activity.getString(R.string.got_master_info));
                                ToastUtils.showError(activity, message != null ? message : activity.getString(R.string.failed_to_switch));
                            }
                        }
                    });
                } else {
                    // Failed to fetch master info
                    activity.runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.showError(activity, activity.getString(R.string.failed_to_fetch_master_fid_info));
                    });
                }

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error in async fetch master KeyInfo: %s", e.getMessage());
                activity.runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.showError(activity, activity.getString(R.string.error_fetching_master_fid_info_with_message, e.getMessage()));
                });
            }
        }).start();
    }
    
    /**
     * Prompt user to set a master for the main FID
     */
    private void promptSetMaster() {
        UserConfirmDialog confirmDialog = new UserConfirmDialog(
            activity,
            "Set Master",
            "No master is set for this FID. Would you like to set a master?",
            choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    // Launch SetMasterActivity
                    Intent intent = new Intent(activity, com.fc.freer.home.SetMasterActivity.class);
                    activity.startActivity(intent);
                }
            }
        );
        confirmDialog.show();
    }
    
    /**
     * Show watched FIDs options
     */
    private void showWatchedFidsOptions() {
        // Launch ChooseWatchedFidActivity with startActivityForResult to enable HomeActivity refresh
        Intent intent = new Intent(activity, com.fc.freer.home.ChooseWatchedFidActivity.class);
        activity.startActivityForResult(intent, 1001);
    }
    
    /**
     * Show multisig FIDs options
     */
    private void showMultisignFidsOptions() {
        // Launch ChooseMultisigFidActivity with startActivityForResult to enable HomeActivity refresh
        Intent intent = new Intent(activity, ChooseMultisigFidActivity.class);
        activity.startActivityForResult(intent, 1002);
    }
    
    /**
     * Show servant FIDs options
     */
    private void showServantsOptions() {
        // Launch ChooseServantFidActivity with startActivityForResult to enable HomeActivity refresh
        Intent intent = new Intent(activity, com.fc.freer.home.ChooseServantFidActivity.class);
        activity.startActivityForResult(intent, 1003);
    }
    
    /**
     * Switch to a specific FID
     */
    private void switchToFid(String fid, KeyInfo keyInfo) {
        // Show waiting dialog
        showWaitingDialog("Switching FID...");
        
        // Use FidManager's async method with callback
        fidManager.switchLiveFidAsync(activity, fid, keyInfo, new FidManager.FidSwitchCallback() {
            @Override
            public void onSwitchComplete(boolean success, String message) {
                // Dismiss waiting dialog
                dismissWaitingDialog();
                
                if (success) {
                    refreshHomeActivityCard();
                    ToastUtils.makeText(activity, activity.getString(R.string.fid_switched));
                    
                    // Refresh cidInfo from API for the switched FID
                    refreshCidInfoAsync(fid);
                } else {
                    refreshHomeActivityCard(); // Refresh to show current FID
                    ToastUtils.showError(activity, message != null ? message : activity.getString(R.string.failed_to_switch_fid,""));
                }
            }
        });
    }
    
    /**
     * Quit main FID (logout)
     */
    private void quitMainFid() {
        try {
            // Reset FidManager
            fidManager.reset();

            // Close this identity's API clients and stop its FUDP node before the
            // setting is dropped. Leaving it to the next HomeActivity is not enough:
            // that only tears down when it observes a *different* FID, so selecting
            // the same identity again would start a second node under one FID.
            ApiCenter.getInstance().closeAllClientsAsync();

            // Reset SettingManager
            SettingManager.getInstance().clearCurrentSetting();

            // Return to MainActivity
            Intent intent = new Intent(activity, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            activity.finish();
            
            TimberLogger.d(TAG, "Successfully quit main FID");
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error quitting main FID: %s", e.getMessage());
            ToastUtils.showError(activity, activity.getString(R.string.error_logging_out));
        }
    }
    
    /**
     * Refresh the HomeActivity's live FID card if the current activity is HomeActivity
     */
    private void refreshHomeActivityCard() {
        if (activity instanceof com.fc.freer.home.HomeActivity) {
            ((com.fc.freer.home.HomeActivity) activity).refreshLiveFidCard();
        }
    }
    
    /**
     * Refresh cidInfo from API and update UI
     */
    private void refreshCidInfoAsync(String fid) {
        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null || !fapiClient.isConnected()) {
                    TimberLogger.w(TAG, "FAPI client not available for freerInfo refresh");
                    return;
                }
                
                // Fetch fresh freerInfo from API
                // Fetch CID info using FapiClient
                Freer freerInfo = fapiClient.getFreer(fid);

                if (freerInfo != null) {
                    // Update KeyInfo with fresh data
                    KeyInfo currentKeyInfo = fidManager.getLiveKeyInfo();
                    if (currentKeyInfo != null) {
                        // Preserve user-specific data while updating API data
                        KeyInfo updatedKeyInfo = KeyInfo.fromCid(freerInfo);
                        updatedKeyInfo.setLabel(currentKeyInfo.getLabel());
                        updatedKeyInfo.setPrikeyCipher(currentKeyInfo.getPrikeyCipher());
                        updatedKeyInfo.setWatchOnly(currentKeyInfo.getWatchOnly());
                        updatedKeyInfo.setSaveTime(currentKeyInfo.getSaveTime());
                        
                        // Update in FidManager and save
                        if (fidManager.updateKeyInfo(activity, fid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Updated freerInfo for FID: %s - Cash: %d, Balance: %d, CD: %d",
                                fid, freerInfo.getCash(), freerInfo.getBalance(), freerInfo.getCd());
                            
                            // Refresh UI with updated data
                            activity.runOnUiThread(() -> {
                                refreshHomeActivityCard();
                            });
                        }
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch freerInfo for FID: %s", fid);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error refreshing cidInfo for FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }


    /**
     * Show waiting dialog with message
     */
    private void showWaitingDialog(String message) {
        try {
            if (waitingDialog != null && waitingDialog.isShowing()) {
                waitingDialog.dismiss();
            }
            waitingDialog = new WaitingDialog(activity, message);
            waitingDialog.show();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing waiting dialog: %s", e.getMessage());
        }
    }
    
    /**
     * Update waiting dialog message
     */
    private void updateWaitingDialogMessage(String message) {
        try {
            if (waitingDialog != null && waitingDialog.isShowing()) {
                waitingDialog.setHint(message);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating waiting dialog message: %s", e.getMessage());
        }
    }
    
    /**
     * Dismiss waiting dialog
     */
    private void dismissWaitingDialog() {
        try {
            if (waitingDialog != null && waitingDialog.isShowing()) {
                waitingDialog.dismiss();
                waitingDialog = null;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error dismissing waiting dialog: %s", e.getMessage());
        }
    }
    
    /**
     * Cleanup method to be called when the activity is being destroyed
     */
    public void cleanup() {
        dismissWaitingDialog();
        dismissPopup();
    }
    
    /**
     * Dismiss the popup window
     */
    private void dismissPopup() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }
}