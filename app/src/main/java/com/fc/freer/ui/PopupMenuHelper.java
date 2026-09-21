package com.fc.freer.ui;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.freer.home.SetMasterActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.onboarding.LiveFidRecord;
import com.fc.freer.onboarding.PendingIdentityCarve;
import com.fc.freer.onboarding.PendingIdentityCarves;
import com.fc.freer.model.Configure;
import com.fc.freer.convert.BroadcastActivity;
import com.fc.freer.convert.StringConvertActivity;
import com.fc.freer.tools.RandomBytesGeneratorActivity;
import com.fc.freer.tools.TotpActivity;
import com.fc.freer.utils.ToastUtils;

import com.fc.freer.R;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.home.ArticleDisplayActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.convert.PrikeyConverterActivity;
import com.fc.freer.convert.PubkeyConverterActivity;
import com.fc.freer.convert.AddressConverterActivity;
import com.fc.freer.convert.JsonConvertActivity;
import com.fc.freer.convert.ScriptConverterActivity;
import com.fc.freer.convert.TimeConvertActivity;
import com.fc.freer.tools.HashActivity;
import com.fc.freer.tools.EncryptActivity;
import com.fc.freer.tools.DecryptActivity;
import com.fc.freer.tools.SignMsgActivity;
import com.fc.freer.tools.VerifyActivity;
import com.fc.freer.account.CashActivity;
import com.fc.freer.account.IncomeActivity;
import com.fc.freer.account.ExpenseActivity;


public class PopupMenuHelper {
    private static final String TAG = "PopupMenu";

    private final Context context;
    private PopupWindow popupWindow;

    public PopupMenuHelper(Context context) {
        this.context = context;
    }

    public void showConvertMenu(View anchorView) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_menu_converter, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupConvertMenuItems(popupView);
        showPopup(anchorView);
    }

    public void showSettingsMenu(View anchorView) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_menu_settings, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupSettingsMenuItems(popupView);
        showPopup(anchorView);
    }

    public void showInputOptionsMenu(View anchorView, OnInputOptionSelectedListener listener) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_menu_input_options, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupInputOptionsMenuItems(popupView, listener);
        showPopup(anchorView);
    }

    public void showToolsMenu(View anchorView) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_menu_tools, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupToolsMenuItems(popupView);
        showPopup(anchorView);
    }

    public void showAccountMenu(View anchorView) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_menu_account, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupAccountMenuItems(popupView);
        showPopup(anchorView);
    }

    private void setupConvertMenuItems(View popupView) {
        TextView privateKeyConverter = popupView.findViewById(R.id.private_key_converter);
        TextView publicKeyConverter = popupView.findViewById(R.id.public_key_converter);
        TextView addressConverter = popupView.findViewById(R.id.address_converter);
        TextView jsonConverter = popupView.findViewById(R.id.json_converter);
        TextView stringConverter = popupView.findViewById(R.id.string_converter);
        TextView scriptConverter = popupView.findViewById(R.id.script_converter);
        TextView timeConverter = popupView.findViewById(R.id.time_converter);

        privateKeyConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, PrikeyConverterActivity.class));
        });

        publicKeyConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, PubkeyConverterActivity.class));
        });

        addressConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, AddressConverterActivity.class));
        });

        jsonConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, JsonConvertActivity.class));
        });

        stringConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, StringConvertActivity.class));
        });

        scriptConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, ScriptConverterActivity.class));
        });

        timeConverter.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, TimeConvertActivity.class));
        });
    }

    /**
     * Whether this FID could still name a master: the main FID, with none on the chain and none
     * carved in the last day.
     * <p>
     * FEIP6 is write-once — the parser ignores a master carve from a FID that already has one,
     * after the fee is paid — so offering it again would only cost money. A master belongs to
     * the main FID, so a sub-identity is not asked either. Only what the chain said in this
     * process counts: the cached KeyInfo is written on broadcast, and hiding the item off that
     * would leave no way back after a carve that never landed.
     */
    private boolean hasNoMasterYet() {
        FidManager fidManager = FidManager.getInstance();
        String mainFid = fidManager != null ? fidManager.getMainFid() : null;
        if (mainFid == null || !mainFid.equals(fidManager.getLiveFid())) return false;
        if (fidManager.isLiveFidMultisig()) return false;
        LiveFidRecord record = LiveFidRecord.confirmed(mainFid);
        if (record != null && record.master != null && !record.master.trim().isEmpty()) return false;
        return PendingIdentityCarves.of(context).getInFlight(
                mainFid, PendingIdentityCarve.Kind.MASTER, System.currentTimeMillis()) == null;
    }

    private void setupSettingsMenuItems(View popupView) {
        TextView changePassword = popupView.findViewById(R.id.change_password);
        TextView setCid = popupView.findViewById(R.id.set_cid);
        TextView setApis = popupView.findViewById(R.id.set_apis);
        TextView setDockDisk = popupView.findViewById(R.id.set_dock_disk);
        TextView setMaster = popupView.findViewById(R.id.set_master);
        TextView backupPrikey = popupView.findViewById(R.id.backup_prikey);
        TextView helpBeginners = popupView.findViewById(R.id.help_beginners);
        TextView security_guidelines = popupView.findViewById(R.id.security_guidelines);
        TextView toastMessages = popupView.findViewById(R.id.toast_messages);
        TextView removeMe = popupView.findViewById(R.id.remove_me);

        // Set click listeners for each menu item
        changePassword.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, ChangePasswordActivity.class);
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 9999);
            } else {
                context.startActivity(intent);
            }
        });

        setCid.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, com.fc.freer.home.SetCidActivity.class));
        });

        setApis.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, com.fc.freer.home.ApiServicesActivity.class);
            intent.putExtra("FROM_SETTINGS", true);
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 9998);
            } else {
                context.startActivity(intent);
            }
        });

        // A multisig FID has no key pair, so it can neither encrypt nor decrypt messages:
        // DOCK (messaging) and DISK (private storage) registration would be useless for it.
        if (FidManager.getInstance() != null && FidManager.getInstance().isLiveFidMultisig()) {
            setDockDisk.setVisibility(View.GONE);
            View dockDiskDivider = popupView.findViewById(R.id.set_dock_disk_divider);
            if (dockDiskDivider != null) dockDiskDivider.setVisibility(View.GONE);
        }

        setDockDisk.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, com.fc.freer.data.ServerSetupActivity.class));
        });

        // A master can be named once and never again, so the item is only there while this FID
        // has none. Once the chain holds one, the way to it is the person menu, which switches
        // to the master instead of offering a carve the parser would ignore.
        if (hasNoMasterYet()) {
            setMaster.setOnClickListener(v -> {
                popupWindow.dismiss();
                context.startActivity(new Intent(context, SetMasterActivity.class));
            });
        } else {
            setMaster.setVisibility(View.GONE);
            View setMasterDivider = popupView.findViewById(R.id.set_master_divider);
            if (setMasterDivider != null) setMasterDivider.setVisibility(View.GONE);
        }

        helpBeginners.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, com.fc.freer.im.NewcomerRequestsActivity.class));
        });

        backupPrikey.setOnClickListener(v -> {
            popupWindow.dismiss();
            BackupPrikeyDialog dialog = new BackupPrikeyDialog(context, new BackupPrikeyDialog.BackupPrikeyListener() {
                @Override
                public void onDone() {
                    ToastUtils.makeText(context, R.string.prikey_backup_completed);
                }

                @Override
                public void onLater() {
                    // User chose to backup later, do nothing
                }
            });
            dialog.show();
        });

        security_guidelines.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = ArticleDisplayActivity.newIntent(context,
                context.getString(R.string.instruction),
                context.getString(R.string.security_guidelines_text));
            context.startActivity(intent);
        });

        toastMessages.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, com.fc.freer.home.ToastActivity.class));
        });


        removeMe.setOnClickListener(v -> {
            popupWindow.dismiss();
            showRemoveMeConfirmation();
        });
    }

    private void setupInputOptionsMenuItems(View popupView, OnInputOptionSelectedListener listener) {
        TextView myCash = popupView.findViewById(R.id.my_cash);
        TextView input = popupView.findViewById(R.id.input);

        myCash.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (listener != null) {
                listener.onMyCashSelected();
            }
        });

        input.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (listener != null) {
                listener.onInputSelected();
            }
        });
    }


    private void showRemoveMeConfirmation() {
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager.getLiveFid();
        String mainFid = fidManager.getMainFid();

        // Check if liveFid is the mainFid
        if (liveFid != null && liveFid.equals(mainFid)) {
            // Remove main FID - show full confirmation with two dialogs
            UserConfirmDialog firstDialog = new UserConfirmDialog(context, "Remove Me",
                context.getString(R.string.you_are_clear_all_the_data_of_safe_do_you_sure_to_do_it),
                choice -> {
                    if (choice == UserConfirmDialog.Choice.YES) {
                        showSecondRemoveMeConfirmation();
                    }
                }, true);
            firstDialog.show();
        } else {
            // Remove non-main FID (master, watched, multisig, or servant) - single confirmation
            UserConfirmDialog dialog = new UserConfirmDialog(context, "Remove FID",
                context.getString(R.string.confirm_remove_non_main_fid),
                choice -> {
                    if (choice == UserConfirmDialog.Choice.YES) {
                        removeNonMainFid(liveFid);
                    }
                }, true);
            dialog.show();
        }
    }

    private void showSecondRemoveMeConfirmation() {
        UserConfirmDialog secondDialog = new UserConfirmDialog(context,"Confirm Again",
                context.getString(R.string.cleared_data_cannot_be_recovered_are_you_sure_to_clear_all_data),
            choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    DatabaseManager databaseManager = DatabaseManager.getInstance();
                    //remove DBs
                    databaseManager.removeAllDatabasesOfFid(FidManager.getInstance().getMainFid());

                    // remove settings
                    String mainFid = FidManager.getInstance().getMainFid();
                    SettingManager.getInstance().removeSettings(context,mainFid);

                    ConfigureManager instance = ConfigureManager.getInstance();
                    Configure currentConfigure = instance.getConfigure();

                    // Remove from configure
                    currentConfigure.getMainCidInfoMap().remove(mainFid);
                    instance.storeConfigure(context, currentConfigure);

                    //clear configure if it is empty
                    if(currentConfigure.getMainCidInfoMap().isEmpty()) {

                        String currentPasswordName = databaseManager.getCurrentPasswordName();
                        databaseManager.removeAllDatabases();

                        instance.removeConfigure(context, currentPasswordName);
                    }
                    // Show success message
                    ToastUtils.makeText(context, R.string.all_data_cleared_successfully);

                    // Close the app
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).finish();
                    }
                }
            }, true);
        secondDialog.show();
    }

    /**
     * Remove a non-main FID (master, watched, multisig, or servant)
     * - Closes all managers for the FID
     * - Removes from appropriate list in Setting (not for master)
     * - Removes from keyInfoMap (not for master)
     * - Switches back to main FID
     */
    private void removeNonMainFid(String fidToRemove) {
        try {
            FidManager fidManager = FidManager.getInstance();
            com.fc.freer.model.Setting currentSetting = SettingManager.getInstance().getCurrentSetting();

            if (currentSetting == null) {
                ToastUtils.showError(context, context.getString(R.string.error_no_current_setting));
                return;
            }

            // Remove databases for this FID
            DatabaseManager databaseManager = DatabaseManager.getInstance();
            databaseManager.removeAllDatabasesOfFid(fidToRemove);

            // Check if it's the master FID
            com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfo = currentSetting.getMainKeyInfo();
            String masterFid = mainKeyInfo != null ? mainKeyInfo.getMaster() : null;
            boolean isMaster = fidToRemove.equals(masterFid);

            if (isMaster) {
                // For master FID, only close managers (don't remove from keyInfoMap)
                // Master is stored in mainKeyInfo.master field, not in separate lists
                com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Removing master FID: %s (managers only)", fidToRemove);
            } else {
                // For other FID types, remove from appropriate list and keyInfoMap
                boolean removed = false;

                // Check if it's in watched list
                if (currentSetting.getWatchedKeyInfoList().stream()
                        .anyMatch(ki -> fidToRemove.equals(ki.getId()))) {
                    currentSetting.removeWatchedKeyInfo(fidToRemove);
                    currentSetting.getKeyInfoMap().remove(fidToRemove);
                    removed = true;
                }
                // Check if it's in multisig list
                else if (currentSetting.getMultisigKeyInfoList().stream()
                        .anyMatch(ki -> fidToRemove.equals(ki.getId()))) {
                    currentSetting.removeMultisigKeyInfo(fidToRemove);
                    currentSetting.getKeyInfoMap().remove(fidToRemove);
                    removed = true;
                }
                // Check if it's in servant list
                else if (currentSetting.getServantKeyInfoList().stream()
                        .anyMatch(ki -> fidToRemove.equals(ki.getId()))) {
                    currentSetting.removeServantKeyInfo(fidToRemove);
                    currentSetting.getKeyInfoMap().remove(fidToRemove);
                    removed = true;
                }

                if (!removed) {
                    ToastUtils.showError(context, context.getString(R.string.error_fid_not_found_in_lists));
                    return;
                }
            }

            // Save updated setting
            SettingManager.getInstance().saveSettings(context, currentSetting);

            // Switch back to main FID home page (similar to clicking "Main FID" in person menu)
            String mainFid = fidManager.getMainFid();
            com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfoObj = fidManager.getMainKeyInfo();

            if (mainFid != null && mainKeyInfoObj != null) {
                // Use async switch to main FID with proper callback
                if (context instanceof android.app.Activity) {
                    android.app.Activity activity = (android.app.Activity) context;

                    fidManager.switchLiveFidAsync(context, mainFid, mainKeyInfoObj, new FidManager.FidSwitchCallback() {
                        @Override
                        public void onSwitchComplete(boolean success, String message) {
                            if (success) {
                                // Show success message
                                ToastUtils.makeText(context, context.getString(R.string.fid_removed_successfully));

                                // Refresh HomeActivity card if we're in HomeActivity
                                if (activity instanceof com.fc.freer.home.HomeActivity) {
                                    ((com.fc.freer.home.HomeActivity) activity).refreshLiveFidCard();
                                }

                                // Refresh cidInfo from API for the main FID
                                refreshMainFidCidInfoAsync(mainFid);
                            } else {
                                ToastUtils.showError(context, context.getString(R.string.error_switching_to_main_fid));
                            }
                        }
                    });
                } else {
                    // Fallback to synchronous switch if not an Activity
                    fidManager.switchLiveFid(context, mainFid, mainKeyInfoObj);
                    ToastUtils.makeText(context, context.getString(R.string.fid_removed_successfully));
                }
            } else {
                ToastUtils.showError(context, context.getString(R.string.error_switching_to_main_fid));
            }

        } catch (Exception e) {
            com.fc.fc_ajdk.utils.TimberLogger.e(TAG, "Error removing non-main FID: " + e.getMessage(), e);
            ToastUtils.showError(context, context.getString(R.string.error_removing_fid, e.getMessage()));
        }
    }

    /**
     * Refresh cidInfo from API for the main FID after switching back from a removed FID
     */
    private void refreshMainFidCidInfoAsync(String mainFid) {
        new Thread(() -> {
            try {
                com.fc.freer.utils.ApiCenter apiCenter = com.fc.freer.utils.ApiCenter.getInstance();
                com.fc.fc_ajdk.fapi.client.FapiClient fapiClient = (com.fc.fc_ajdk.fapi.client.FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);

                if (fapiClient == null || !fapiClient.isConnected()) {
                    com.fc.fc_ajdk.utils.TimberLogger.w(TAG, "FAPI client not available for freerInfo refresh");
                    return;
                }

                // Fetch fresh freerInfo from API
                Freer freerInfo =  fapiClient.freerById(mainFid);

                if (freerInfo != null) {
                    FidManager fidManager = FidManager.getInstance();
                    com.fc.fc_ajdk.data.fcData.KeyInfo currentKeyInfo = fidManager.getLiveKeyInfo();

                    if (currentKeyInfo != null) {
                        // Update KeyInfo with fresh data while preserving user-specific data
                        com.fc.fc_ajdk.data.fcData.KeyInfo updatedKeyInfo = com.fc.fc_ajdk.data.fcData.KeyInfo.updateFromCid(freerInfo, currentKeyInfo);

                        // Update in FidManager and save
                        if (fidManager.updateKeyInfo(context, mainFid, updatedKeyInfo)) {
                            com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Updated freerInfo for main FID: %s", mainFid);

                            // Refresh UI with updated data on main thread
                            if (context instanceof android.app.Activity) {
                                ((android.app.Activity) context).runOnUiThread(() -> {
                                    if (context instanceof com.fc.freer.home.HomeActivity) {
                                        ((com.fc.freer.home.HomeActivity) context).refreshLiveFidCard();
                                    }
                                });
                            }
                        }
                    }
                } else {
                    com.fc.fc_ajdk.utils.TimberLogger.w(TAG, "Failed to fetch freerInfo for main FID: %s", mainFid);
                }
            } catch (Exception e) {
                com.fc.fc_ajdk.utils.TimberLogger.e(TAG, "Error refreshing cidInfo for main FID %s: %s", mainFid, e.getMessage());
            }
        }).start();
    }

    private void setupToolsMenuItems(View popupView) {
        TextView randomTool = popupView.findViewById(R.id.random_tool);
        TextView hashTool = popupView.findViewById(R.id.hash_tool);
        TextView encryptTool = popupView.findViewById(R.id.encrypt_tool);
        TextView decryptTool = popupView.findViewById(R.id.decrypt_tool);
        TextView signWordsTool = popupView.findViewById(R.id.sign_words_tool);
        TextView verifyTool = popupView.findViewById(R.id.verify_tool);
        TextView totpTool = popupView.findViewById(R.id.totp_tool);
        TextView broadcastTool = popupView.findViewById(R.id.broadcast_tool);

        randomTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, RandomBytesGeneratorActivity.class));
        });

        hashTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, HashActivity.class));
        });

        encryptTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, EncryptActivity.class));
        });

        decryptTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, DecryptActivity.class));
        });

        signWordsTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, SignMsgActivity.class));
        });

        verifyTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, VerifyActivity.class));
        });

        totpTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, TotpActivity.class));
        });

        broadcastTool.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, BroadcastActivity.class));
        });
    }

    private void setupAccountMenuItems(View popupView) {
        TextView cashItem = popupView.findViewById(R.id.account_cash);
        TextView incomeItem = popupView.findViewById(R.id.account_income);
        TextView expendItem = popupView.findViewById(R.id.account_expend);

        cashItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, CashActivity.class));
        });

        incomeItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, IncomeActivity.class));
        });

        expendItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            context.startActivity(new Intent(context, ExpenseActivity.class));
        });
    }

    private void showPopup(View anchorView) {
        int[] location = new int[2];
        anchorView.getLocationOnScreen(location);
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, 
            location[0], location[1] - popupWindow.getHeight());
    }

    public interface OnInputOptionSelectedListener {
        void onMyCashSelected();
        void onInputSelected();
    }
} 