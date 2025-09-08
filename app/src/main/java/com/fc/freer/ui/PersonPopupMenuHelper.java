package com.fc.freer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.MainActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.network.ApipClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.ui.WaitingDialog;

import java.util.List;

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
            Toast.makeText(activity, "Error showing menu", Toast.LENGTH_SHORT).show();
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
        
        // Check if it's in multisign list
        if (currentSetting.getMultisignKeyInfoList() != null) {
            for (KeyInfo keyInfo : currentSetting.getMultisignKeyInfoList()) {
                if (liveFid.equals(keyInfo.getId())) {
                    return "Multisign";
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
        String liveFid = fidManager.getLiveFid();
        boolean isMainFid = fidManager.isLiveFidMain();
        
        if (liveFid != null) {
            String fidType = determineFidType(liveFid);
            String displayText = "Living: " + fidType;
            currentFidText.setText(displayText);
        } else {
            currentFidText.setText(R.string.living_not_set);
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
            mainFidMenu.setTextColor(activity.getColor(R.color.text_color));
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
            myMasterMenu.setTextColor(activity.getColor(R.color.text_color));
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
            watchedFidsMenu.setTextColor(activity.getColor(R.color.text_color));
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
        
        // My Multisign FIDs
        TextView multisignFidsMenu = popupView.findViewById(R.id.menu_my_multisign_fids);
        if (MAIN_FID.equals(fidType)) {
            // Main FID - make it clickable
            multisignFidsMenu.setTextColor(activity.getColor(R.color.text_color));
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
            servantsMenu.setTextColor(activity.getColor(R.color.text_color));
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
        TextView quitMainFidMenu = popupView.findViewById(R.id.menu_quit_main_fid);
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
            Toast.makeText(activity, activity.getString(R.string.no_main_fid_available), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(activity, "No current setting available", Toast.LENGTH_SHORT).show();
            return;
        }
        
        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
        if (liveKeyInfo == null) {
            Toast.makeText(activity, "No live FID info available", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(activity, "No master yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            KeyInfo masterKeyInfo = currentSetting.getKeyInfoMap().get(masterFid);

            if (masterKeyInfo == null) {
                // Master KeyInfo not available locally, fetch it using ApipClient on background thread
                fetchMasterKeyInfoAsync(masterFid, currentSetting);
                return; // Will handle switching after async fetch completes
            }

            // Switch to master FID on background thread (even when KeyInfo exists locally)
            switchToMasterAsync(masterFid, masterKeyInfo);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error switching to master: %s", e.getMessage());
            Toast.makeText(activity, "Error switching to master FID", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Fetch master KeyInfo using ApipClient when not available locally
     */
    private KeyInfo fetchMasterKeyInfo(String masterFid, Setting currentSetting) {
        try {
            // Get ApipClient from current setting
            ApiCenter apiCenter = ApiCenter.getInstance();
            ApipClient apipClient = (ApipClient) apiCenter.getClient(Service.ServiceType.APIP);
            if (apipClient == null) {
                Toast.makeText(activity, "ApipClient not available", Toast.LENGTH_SHORT).show();
                return null;
            }
            
            // Fetch CID info using ApipClient
            com.fc.fc_ajdk.data.fchData.Cid cidInfo = apipClient.cidInfoById(masterFid, 
                com.fc.fc_ajdk.utils.http.RequestMethod.POST, 
                com.fc.fc_ajdk.utils.http.AuthType.FC_SIGN_BODY, 
                activity);
                
            if (cidInfo != null) {
                // Convert Cid to KeyInfo using existing fromCid method
                KeyInfo masterKeyInfo = KeyInfo.fromCid(cidInfo);
                
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
                Toast.makeText(activity, "Failed to fetch master FID info from network", Toast.LENGTH_SHORT).show();
                TimberLogger.w(TAG, "Failed to fetch CID info for master FID: %s", masterFid);
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching master KeyInfo: %s", e.getMessage());
            Toast.makeText(activity, "Error fetching master FID info", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(activity, "Switched to master FID successfully", Toast.LENGTH_SHORT).show();
                    
                    // Refresh cidInfo from API for the master FID
                    refreshCidInfoAsync(masterFid);
                } else {
                    refreshHomeActivityCard(); // Refresh to show current FID
                    Toast.makeText(activity, message != null ? message : "Failed to switch to master FID", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
    
    /**
     * Fetch master KeyInfo using ApipClient asynchronously
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
                                Toast.makeText(activity, "Fetched master info and switched successfully", Toast.LENGTH_SHORT).show();
                                
                                // Refresh cidInfo from API for the master FID
                                refreshCidInfoAsync(masterFid);
                            } else {
                                refreshHomeActivityCard(); // Refresh to show current FID
                                Toast.makeText(activity, message != null ? message : "Fetched master info but failed to switch", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } else {
                    // Failed to fetch master info
                    activity.runOnUiThread(() -> {
                        dismissWaitingDialog();
                        Toast.makeText(activity, "Failed to fetch master FID info", Toast.LENGTH_SHORT).show();
                    });
                }
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error in async fetch master KeyInfo: %s", e.getMessage());
                activity.runOnUiThread(() -> {
                    dismissWaitingDialog();
                    Toast.makeText(activity, "Error fetching master FID info: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
     * Show multisign FIDs options
     */
    private void showMultisignFidsOptions() {
        // Launch ChooseMultisignFidActivity with startActivityForResult to enable HomeActivity refresh
        Intent intent = new Intent(activity, com.fc.freer.home.ChooseMultisignFidActivity.class);
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
                    Toast.makeText(activity, activity.getString(R.string.fid_switched_successfully), Toast.LENGTH_SHORT).show();
                    
                    // Refresh cidInfo from API for the switched FID
                    refreshCidInfoAsync(fid);
                } else {
                    refreshHomeActivityCard(); // Refresh to show current FID
                    Toast.makeText(activity, message != null ? message : activity.getString(R.string.failed_to_switch_fid), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(activity, "Error logging out", Toast.LENGTH_SHORT).show();
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
                ApipClient apipClient = (ApipClient) apiCenter.getClient(Service.ServiceType.APIP);
                if (apipClient == null || !apipClient.getConnected()) {
                    TimberLogger.w(TAG, "APIP client not available for cidInfo refresh");
                    return;
                }
                
                // Fetch fresh cidInfo from API
                com.fc.fc_ajdk.data.fchData.Cid cidInfo = apipClient.cidInfoById(fid, 
                    com.fc.fc_ajdk.utils.http.RequestMethod.POST, 
                    com.fc.fc_ajdk.utils.http.AuthType.FC_SIGN_BODY, 
                    activity);
                    
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
                        if (fidManager.updateKeyInfo(activity, fid, updatedKeyInfo)) {
                            TimberLogger.d(TAG, "Updated cidInfo for FID: %s - Cash: %d, Balance: %d, CD: %d", 
                                fid, cidInfo.getCash(), cidInfo.getBalance(), cidInfo.getCd());
                            
                            // Refresh UI with updated data
                            activity.runOnUiThread(() -> {
                                refreshHomeActivityCard();
                            });
                        }
                    }
                } else {
                    TimberLogger.w(TAG, "Failed to fetch cidInfo for FID: %s", fid);
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