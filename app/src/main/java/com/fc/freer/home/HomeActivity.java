package com.fc.freer.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.FcDate;
import com.fc.freer.FreerApplication;
import com.fc.freer.account.CashActivity;
import com.fc.freer.contact.ContactActivity;
import com.fc.freer.manager.AppManager;
import com.fc.freer.manager.FcObjectManager;
import com.fc.freer.manager.MailManager;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.multisig.MultisigDetailActivity;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.freer.secret.SecretActivity;
import com.fc.freer.ui.StringInputDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.freer.manager.ToastManager;
import com.fc.freer.utils.ToastUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.fc.freer.manager.AvatarManager;
import com.fc.freer.model.Configure;

import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FcManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.R;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.freer.im.ImManager;
import com.fc.freer.im.PendingIssueManager;
import com.fc.freer.ui.PopupMenuHelper;
import com.fc.freer.ui.PersonPopupMenuHelper;
import com.fc.freer.ui.RemindDialog;
import com.fc.freer.ui.BackupPrikeyDialog;


public class HomeActivity extends AppCompatActivity {
    private static final String TAG = "HomeActivity";
    private PopupMenuHelper popupMenuHelper;
    private PersonPopupMenuHelper personPopupMenuHelper;
    private AddPrikeyPopupMenuHelper addPrikeyPopupMenuHelper;
    private boolean isPasswordChanging = false;

    private TextView badgeTodo;
    private TextView badgeGroup;
    private TextView badgeTeam;
    private TextView badgeRoom;
    private TextView badgeTalk;
    private TextView badgeCash;
    private TextView badgeMail;
    private View talkView;
    private View dataView;
    private boolean serverSetupPrompted = false;
    // Set once the live FID's on-chain info (incl. home.DISK) has been fetched at least once.
    // Until then the DISK setup prompt is suppressed: a freshly-added FID's local KeyInfo has
    // no home entries yet, so isDiskReady() would be a false negative and pop the dialog even
    // for a FID that already has its servers configured on-chain.
    private volatile boolean liveFidInfoRefreshed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Guard against process death while backgrounded: if the in-memory
        // session was lost, redirect to re-authentication before touching any
        // session-dependent state (which would otherwise NPE on resume).
        // HomeActivity is a post-CID screen and cannot function without a current
        // setting. Require both the symkey AND a current setting here, otherwise the
        // FID card renders stuck on "Loading..." after a process-death restore where
        // only the symkey was restored (see SessionGuard.ensureSessionWithSetting).
        if (!com.fc.freer.utils.SessionGuard.ensureSessionWithSetting(this)) {
            return;
        }

        try {
            // Log that we're starting the HomeActivity
            TimberLogger.d(TAG, "HomeActivity onCreate started");
            
            // Initialize PopupMenuHelper
            popupMenuHelper = new PopupMenuHelper(this);
            personPopupMenuHelper = new PersonPopupMenuHelper(this);
            addPrikeyPopupMenuHelper = new AddPrikeyPopupMenuHelper(this);
            
            // Get the Configure object from ConfigureManager
            Configure configure = ConfigureManager.getInstance().getConfigure();
            if (configure != null) {
                TimberLogger.d(TAG, "Configure object retrieved from ConfigureManager");

                // Set the current password name in DatabaseManager
                String passwordName = configure.getPasswordName();
                if (passwordName != null) {
                    DatabaseManager.getInstance();
                    TimberLogger.d(TAG, "Set current password name to: " + passwordName);
                }
            } else {
                TimberLogger.w(TAG, "No Configure object available in ConfigureManager");
            }
            
            // Set the content view
            setContentView(R.layout.activity_home);
            TimberLogger.d(TAG, "Content view set successfully");

            // Initialize badge views
            badgeTodo = findViewById(R.id.badge_todo);
            badgeGroup = findViewById(R.id.badge_group);
            badgeTeam = findViewById(R.id.badge_team);
            badgeRoom = findViewById(R.id.badge_room);
            badgeTalk = findViewById(R.id.badge_talk);
            badgeCash = findViewById(R.id.badge_cash);
            badgeMail = findViewById(R.id.badge_mail);

            // Set up click listener for Freer text
            TextView freerTextView = findViewById(R.id.freer_text);
            if (freerTextView != null) {
                freerTextView.setOnClickListener(v -> {
                    RemindDialog dialog = new RemindDialog(this, getString(R.string.freer_v_by_no1_nrc7, FreerApplication.VER));
                    dialog.show();
                });
            }

            // Set up tooltip for fc_time_icon
            ImageView fcTimeIcon = findViewById(R.id.fc_time_icon);
            if (fcTimeIcon != null) {
                fcTimeIcon.setTooltipText(getString(R.string.fc_time_tooltip));
                fcTimeIcon.setOnClickListener(v -> v.performLongClick());
            }

            // Set predefined icons for all menu items
            setMenuIcons();
            TimberLogger.d(TAG, "Menu icons set successfully");

            // Initialize click listeners for all menu items
            setupMenuClickListeners();
            TimberLogger.d(TAG, "Menu click listeners setup completed");

            // Set initial scroll position to bottom
            NestedScrollView iconContainer = findViewById(R.id.iconContainer);
            if (iconContainer != null) {
                iconContainer.post(() -> {
                    iconContainer.fullScroll(View.FOCUS_DOWN);
                });
            }

            // Initialize FidManager first before loading modules
            initializeFidManager();

            // Set up live FID card
            setupLiveFidCard();

            // Load modules in background after UI is set up
            loadModulesAsync();

            checkPrikeyBackup();

        } catch (Exception ex) {
            // Log any exceptions that occur during initialization
            TimberLogger.e(TAG, "Error in HomeActivity onCreate: " + ex.getMessage(), ex);
            ToastUtils.showError(this, getString(R.string.error_initializing_home, ex.getMessage()));
        }
    }

    private void setMenuIcons() {
        try {
            // Set predefined icons for all menu items
            setIconForMenuItem(R.id.menu_code, R.drawable.ic_code);
            setIconForMenuItem(R.id.menu_convert_new, R.drawable.ic_convert);
            setIconForMenuItem(R.id.menu_tools, R.drawable.ic_tools);
            setIconForMenuItem(R.id.menu_secret, R.drawable.ic_lock);
            setIconForMenuItem(R.id.menu_account, R.drawable.ic_account);
            setIconForMenuItem(R.id.menu_contact, R.drawable.ic_contact);
            setIconForMenuItem(R.id.menu_mail, R.drawable.ic_mail);
            setIconForMenuItem(R.id.menu_news, R.drawable.ic_news);
            setIconForMenuItem(R.id.menu_app, R.drawable.ic_app);
            setIconForMenuItem(R.id.menu_service, R.drawable.ic_service);
            setIconForMenuItem(R.id.menu_token, R.drawable.ic_chip);
            setIconForMenuItem(R.id.menu_protocol, R.drawable.ic_protocol);
            setIconForMenuItem(R.id.menu_carve, R.drawable.ic_carve);
            setIconForMenuItem(R.id.menu_todo, R.drawable.ic_todo);
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error setting menu icons: " + ex.getMessage(), ex);
        }
    }

    private void setIconForMenuItem(int menuItemId, int drawableResId) {
        View menuItem = findViewById(menuItemId);
        if (menuItem != null) {
            ImageView iconView = menuItem.findViewWithTag("icon");
            if (iconView == null) {
                // Find the ImageView within the LinearLayout
                if (menuItem instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) menuItem;
                    for (int i = 0; i < viewGroup.getChildCount(); i++) {
                        View child = viewGroup.getChildAt(i);
                        if (child instanceof ImageView) {
                            iconView = (ImageView) child;
                            break;
                        }
                    }
                }
            }

            if (iconView != null) {
                // Set the predefined drawable resource
                iconView.setImageResource(drawableResId);

                // Add padding to make the shape appear smaller while keeping icon size
                int paddingInDp = 16; // adjust this value to control shape size
                float density = getResources().getDisplayMetrics().density;
                int paddingInPx = (int) (paddingInDp * density);
                iconView.setPadding(paddingInPx, paddingInPx, paddingInPx, paddingInPx);

                // Scale the icon (1.0 = original size, 0.5 = half size, 2.0 = double size)
                iconView.setScaleX(1f);
                iconView.setScaleY(1f);

                // Apply text_color tint to the icon
//                iconView.setColorFilter(ContextCompat.getColor(this, R.color.main_background));
            } else {
                TimberLogger.e(TAG, "ImageView not found in menu item with ID: " + menuItemId);
            }
        } else {
            TimberLogger.e(TAG, "Menu item not found with ID: " + menuItemId);
        }
    }

    private void setupMenuClickListeners() {
        try {
            // Code
            View codeView = findViewById(R.id.menu_code);
            if (codeView != null) {
                codeView.setOnClickListener(v -> {
                    // Launch CodeActivity
                    Intent intent = new Intent(HomeActivity.this, CodeActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_code view not found");
            }

            // Test (hidden)
//            View testView = findViewById(R.id.menu_test);
//            if (testView != null) {
//                testView.setVisibility(View.GONE);
//                testView.setOnClickListener(v -> {
//                    Intent intent = new Intent(HomeActivity.this, TestActivity.class);
//                    startActivity(intent);
//                });
//            } else {
//                TimberLogger.e(TAG, "menu_test view not found");
//            }

            // Secrets
            View secretsView = findViewById(R.id.menu_secret);
            if (secretsView != null) {
                secretsView.setOnClickListener(v -> {
                    // Launch SecretsActivity
                    Intent intent = new Intent(HomeActivity.this, SecretActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_secrets view not found");
            }

            // Contact
            View contactView = findViewById(R.id.menu_contact);
            if (contactView != null) {
                contactView.setOnClickListener(v -> {
                    // Launch ContactActivity
                    Intent intent = new Intent(HomeActivity.this, ContactActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_contact view not found");
            }

            // Mail
            View mailView = findViewById(R.id.menu_mail);
            if (mailView != null) {
                mailView.setOnClickListener(v -> {
                    // Launch MailActivity
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.mail.MailActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_mail view not found");
            }

            // Talk (P2P messaging) — disabled until home.DOCK is registered on-chain.
            talkView = findViewById(R.id.menu_talk);
            if (talkView != null) {
                talkView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.im.TalkActivity.class);
                    startActivity(intent);
                });
                updateTalkTileState();
            } else {
                TimberLogger.d(TAG, "menu_talk view not found");
            }

            // Group messaging
            View groupView = findViewById(R.id.menu_square);
            if (groupView != null) {
                groupView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.im.SquareActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.d(TAG, "menu_group view not found");
            }

            // Team messaging
            View teamView = findViewById(R.id.menu_team);
            if (teamView != null) {
                teamView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.im.TeamActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.d(TAG, "menu_team view not found");
            }

            // Room messaging
            View roomView = findViewById(R.id.menu_room);
            if (roomView != null) {
                roomView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.im.RoomActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.d(TAG, "menu_room view not found");
            }

            // News
            View newsView = findViewById(R.id.menu_news);
            if (newsView != null) {
                newsView.setOnClickListener(v -> {
                    // Launch NewsActivity
                    Intent intent = new Intent(HomeActivity.this, NewsActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_news view not found");
            }

            // App
            View appView = findViewById(R.id.menu_app);
            if (appView != null) {
                appView.setOnClickListener(v -> {
                    // Launch AppActivity
                    Intent intent = new Intent(HomeActivity.this, AppActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_app view not found");
            }

            // Service
            View serviceView = findViewById(R.id.menu_service);
            if (serviceView != null) {
                serviceView.setOnClickListener(v -> {
                    // Launch ServiceActivity
                    Intent intent = new Intent(HomeActivity.this, ServiceActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_service view not found");
            }

            // Token
            View tokenView = findViewById(R.id.menu_token);
            if (tokenView != null) {
                tokenView.setOnClickListener(v -> {
                    // Launch MyTokenActivity
                    Intent intent = new Intent(HomeActivity.this, MyTokenActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_token view not found");
            }

            // Protocol
            View protocolView = findViewById(R.id.menu_protocol);
            if (protocolView != null) {
                protocolView.setOnClickListener(v -> {
                    // Launch ProtocolActivity
                    Intent intent = new Intent(HomeActivity.this, ProtocolActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_protocol view not found");
            }

            // Cash (directly opens CashActivity)
            View accountView = findViewById(R.id.menu_account);
            if (accountView != null) {
                accountView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, CashActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_account view not found");
            }

            // Person
            View personView = findViewById(R.id.menu_person);
            if (personView != null) {
                personView.setOnClickListener(v -> {
                    personPopupMenuHelper.showPersonMenu(personView);
                });
            } else {
                TimberLogger.e(TAG, "menu_person view not found");
            }

            // Setting
            View settingsView = findViewById(R.id.menu_settings);
            if (settingsView != null) {
                settingsView.setOnClickListener(v -> {
                    popupMenuHelper.showSettingsMenu(settingsView);
                });
            } else {
                TimberLogger.e(TAG, "menu_settings view not found");
            }

            // QR scan icon (performs same function as QR Code menu)
            View qrScanView = findViewById(R.id.menu_qr);
            if (qrScanView != null) {
                qrScanView.setOnClickListener(v -> {
                    // Launch QRCodeActivity (same as QR Code menu button)
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.qr.QrCodeActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_qr view not found");
            }

            // List icon (opens FidListActivity)
            View listIconView = findViewById(R.id.menu_list_icon);
            if (listIconView != null) {
                listIconView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, FidListActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_list_icon view not found");
            }

            // Convert (performs same function as Tools menu)
            View convertView = findViewById(R.id.menu_convert_new);
            if (convertView != null) {
                convertView.setOnClickListener(v -> {
                    popupMenuHelper.showConvertMenu(convertView);
                });
            } else {
                TimberLogger.e(TAG, "menu_convert_new view not found");
            }

            // Tools (shows cryptographic tools menu)
            View toolsView = findViewById(R.id.menu_tools);
            if (toolsView != null) {
                toolsView.setOnClickListener(v -> {
                    popupMenuHelper.showToolsMenu(toolsView);
                });
            } else {
                TimberLogger.e(TAG, "menu_tools view not found");
            }

            // Pay
            View payView = findViewById(R.id.menu_pay);
            if (payView != null) {
                payView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.account.PayActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_pay view not found");
            }

            // Receive
            View receiveView = findViewById(R.id.menu_receive);
            if (receiveView != null) {
                receiveView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.account.ReceiveActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_receive view not found");
            }

            // TX
            View txView = findViewById(R.id.menu_tx);
            if (txView != null) {
                txView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.tx.TxActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_tx view not found");
            }

            // TOTP
            View totpView = findViewById(R.id.menu_totp);
            if (totpView != null) {
                totpView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.tools.TotpActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_totp view not found");
            }

            // Proof
            View proofView = findViewById(R.id.menu_proof);
            if (proofView != null) {
                proofView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, ProofActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_proof view not found");
            }

            // Data (Disk) — disabled until home.DISK is configured.
            dataView = findViewById(R.id.menu_disk);
            if (dataView != null) {
                dataView.setOnClickListener(v -> {
                    // Launch DataActivity
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.data.DataActivity.class);
                    startActivity(intent);
                });
                updateDataTileState();
            } else {
                TimberLogger.e(TAG, "menu_disk view not found");
            }

            // Carve
            View carveView = findViewById(R.id.menu_carve);
            if (carveView != null) {
                carveView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.tx.CarveActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_carve view not found");
            }

            // Todo (Pending Issues)
            View todoView = findViewById(R.id.menu_todo);
            if (todoView != null) {
                todoView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, com.fc.freer.im.PendingIssueActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_todo view not found");
            }
            updateMenuVisibilityForLiveFid();
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error in setupMenuClickListeners: " + ex.getMessage(), ex);
            ToastUtils.showError(this, getString(R.string.error_setting_up_menu, ex.getMessage()));
        }
    }

    /**
     * Hide DATA, ROOM, TEAM, TALK, and SQUARE menu buttons when liveFid != mainFid.
     * These functions require mainFid's private key and home services.
     */
    private void updateMenuVisibilityForLiveFid() {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) return;
        
        String mainFid = fidManager.getMainFid();
        String liveFid = fidManager.getLiveFid();
        
        boolean isMainFid = mainFid != null && mainFid.equals(liveFid);
        int visibility = isMainFid ? View.VISIBLE : View.GONE;
        
        int[] restrictedMenuIds = {
                R.id.menu_disk, R.id.menu_room, R.id.menu_team,
                R.id.menu_talk, R.id.menu_square
        };
        
        for (int id : restrictedMenuIds) {
            View view = findViewById(id);
            if (view != null) {
                view.setVisibility(visibility);
            }
        }
        
        if (!isMainFid) {
            TimberLogger.d(TAG, "Hidden DATA/ROOM/TEAM/TALK/SQUARE menus: liveFid=%s != mainFid=%s", liveFid, mainFid);
        }
    }

    private void initializeFidManager() {
        try {
            Configure configure = ConfigureManager.getInstance().getConfigure();
            if (configure == null) {
                TimberLogger.e(TAG, "Configure object not found, cannot initialize FidManager");
                return;
            }

            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) {
                TimberLogger.e(TAG, "Setting object not found, cannot initialize FidManager");
                return;
            }

            // Initialize FidManager with mainFid and mainKeyInfo
            String mainFid = setting.getFid();
            com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfo = setting.getMainKeyInfo();

            if (mainFid != null && mainKeyInfo != null) {
                FidManager.getInstance().initialize(mainFid, mainKeyInfo);
                TimberLogger.d(TAG, "FidManager initialized with mainFid: %s", mainFid);
            } else {
                TimberLogger.w(TAG, "Cannot initialize FidManager - mainFid or mainKeyInfo is null");
            }
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error initializing FidManager: " + ex.getMessage(), ex);
        }
    }

    private void checkPrikeyBackup() {
        try {
            Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                TimberLogger.w(TAG, "No current setting available, skipping state check");
                return;
            }

            // Check if prikey backup is required
            Boolean prikeyBackedUp = (Boolean) currentSetting.getStateMap().get(Setting.KEY_PRIKEY_BACKED_UP);

            // If not backed up (null or false), show backup dialog
            if (prikeyBackedUp == null || !prikeyBackedUp) {
                TimberLogger.d(TAG, "Prikey not backed up, showing backup dialog");

                BackupPrikeyDialog backupDialog = new BackupPrikeyDialog(this, new BackupPrikeyDialog.BackupPrikeyListener() {
                    @Override
                    public void onDone() {
                        TimberLogger.d(TAG, "Backup completed by user");
                        // Update the state to indicate backup is done
                        currentSetting.getStateMap().put(Setting.KEY_PRIKEY_BACKED_UP, true);

                        // Save the updated setting
                        SettingManager.getInstance().saveSettings(HomeActivity.this, currentSetting);

                        ToastUtils.makeText(HomeActivity.this, R.string.prikey_backup_completed);
                    }

                    @Override
                    public void onLater() {
                        TimberLogger.d(TAG, "User chose to backup later");
                        // Don't update the state, dialog will show again next time
                    }
                });

                backupDialog.show();
            } else {
                TimberLogger.d(TAG, "Prikey already backed up");
            }
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error checking main FID state: " + ex.getMessage(), ex);
        }
    }



    private void setupLiveFidCard() {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
                TimberLogger.w(TAG, "FidManager or live KeyInfo not available for liveFidCard setup");
                return;
            }

            // Get live KeyInfo
            com.fc.fc_ajdk.data.fcData.KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
            String liveFid = fidManager.getLiveFid();

            // Find UI elements
            ImageView avatarImageView = findViewById(R.id.fidAvatar);
            TextView nameTextView = findViewById(R.id.fidName);
            TextView labelTextView = findViewById(R.id.fidLabel);
            ImageView editIconView = findViewById(R.id.fidEditIcon);
            TextView cashTextView = findViewById(R.id.fidCash);
            TextView balanceTextView = findViewById(R.id.fidBalance);
            TextView cdTextView = findViewById(R.id.fidCd);
            ImageView noPrikeyIconView = findViewById(R.id.multisigIcon);
            ImageView deadIconView = findViewById(R.id.fidDeadIcon);

            // Set avatar
            if (avatarImageView != null) {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(liveFid);
                if (avatarBitmap != null) {
                    avatarImageView.setImageBitmap(avatarBitmap);
                } else {
                    avatarImageView.setImageResource(R.drawable.ic_person);
                }
            }

            // Ensure QR icon is visible
            ImageView qrIconView = findViewById(R.id.fidQrIcon);
            if (qrIconView != null) {
                qrIconView.setVisibility(View.VISIBLE);
            }

            // Set name (cid if available, otherwise fid)
            if (nameTextView != null) {
                String displayName = liveKeyInfo.getCid();
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = liveFid;
                }
                nameTextView.setText(displayName);
            }

            // Set label or edit icon
            if (labelTextView != null && editIconView != null) {
                String label = liveKeyInfo.getLabel();
                if (label != null && !label.trim().isEmpty()) {
                    // Show label, hide edit icon
                    labelTextView.setText(label);
                    labelTextView.setVisibility(View.VISIBLE);
                    editIconView.setVisibility(View.GONE);
                } else {
                    // Show edit icon, hide label
                    labelTextView.setVisibility(View.GONE);
                    editIconView.setVisibility(View.VISIBLE);
                }
            }

            // Set cash amount (handle null case)
            if (cashTextView != null) {
                Long cash = liveKeyInfo.getCash();
                TimberLogger.d(TAG, "Setting cash value: %s for FID: %s", cash, liveFid);
                if (cash != null && cash > 0) {
                    cashTextView.setText(String.valueOf(cash));
                } else {
                    cashTextView.setText("-");
                }
            }

            // Set balance (handle null case)
            if (balanceTextView != null) {
                Long balance = liveKeyInfo.getBalance();
                TimberLogger.d(TAG, "Setting balance value: %s for FID: %s", balance, liveFid);
                if (balance != null && balance > 0) {
                    double balanceInCoins = FchUtils.satoshiToCoin(balance);
                    balanceTextView.setText(formatBalance(balanceInCoins));
                } else {
                    balanceTextView.setText("-");
                }
            }

            // Set cd (confirmation depth, handle null case)
            if (cdTextView != null) {
                Long cd = liveKeyInfo.getCd();
                TimberLogger.d(TAG, "Setting cd value: %s for FID: %s", cd, liveFid);
                if (cd != null && cd > 0) {
                    cdTextView.setText(formatLargeNumber(cd));
                } else {
                    cdTextView.setText("-");
                }
            }

            // Setup tooltips for cash/balance/cd icons
            ImageView cashIconView = findViewById(R.id.fidCashIcon);
            ImageView balanceIconView = findViewById(R.id.fidBalanceIcon);
            ImageView cdIconView = findViewById(R.id.fidCdIcon);

            if (cashIconView != null) {
                cashIconView.setTooltipText(getString(R.string.cash_tooltip));
                cashIconView.setOnClickListener(v -> v.performLongClick());
            }
            if (balanceIconView != null) {
                balanceIconView.setTooltipText(getString(R.string.balance_tooltip));
                balanceIconView.setOnClickListener(v -> v.performLongClick());
            }
            if (cdIconView != null) {
                cdIconView.setTooltipText(getString(R.string.cd_tooltip));
                cdIconView.setOnClickListener(v -> v.performLongClick());
            }

            // Show/hide no_prikey icon or people icon based on FID type and prikeyCipher
            if (noPrikeyIconView != null) {
                String prikeyCipher = liveKeyInfo.getPrikeyCipher();
                if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                    // Check if liveFid is a multisig FID (starts with '3')
                    if (liveFid != null && liveFid.startsWith("3")) {
                        noPrikeyIconView.setImageResource(R.drawable.ic_people);
                    } else {
                        noPrikeyIconView.setImageResource(R.drawable.ic_no_prikey);
                    }
                    noPrikeyIconView.setVisibility(View.VISIBLE);
                } else {
                    noPrikeyIconView.setVisibility(View.GONE);
                }
            }

            // Show/hide dead icon based on isNobody
            if (deadIconView != null) {
                Boolean isNobody = liveKeyInfo.getIsNobody();
                if (isNobody != null && isNobody) {
                    deadIconView.setVisibility(View.VISIBLE);
                    // Setup tooltip for dead icon
                    deadIconView.setTooltipText(getString(R.string.prikey_leaked_tooltip));
                    // Also show tooltip on click
                    deadIconView.setOnClickListener(v -> v.performLongClick());
                } else {
                    deadIconView.setVisibility(View.GONE);
                }
            }

            // Handle CID metrics visibility and values
            LinearLayout cidMetricsLayout = findViewById(R.id.cid_metrics_layout);
            if (cidMetricsLayout != null) {
                TextView textWeight = findViewById(R.id.text_weight);
                TextView textReputation = findViewById(R.id.text_reputation);
                TextView textHot = findViewById(R.id.text_hot);
                ImageView iconWeight = findViewById(R.id.icon_weight);
                ImageView iconReputation = findViewById(R.id.icon_reputation);
                ImageView iconHot = findViewById(R.id.icon_hot);

                // Setup tooltips for CID metrics icons
                if (iconWeight != null) {
                    iconWeight.setTooltipText(getString(R.string.weight_tooltip));
                    iconWeight.setOnClickListener(v -> v.performLongClick());
                }
                if (iconReputation != null) {
                    iconReputation.setTooltipText(getString(R.string.reputation_tooltip));
                    iconReputation.setOnClickListener(v -> v.performLongClick());
                }
                if (iconHot != null) {
                    iconHot.setTooltipText(getString(R.string.hot_tooltip));
                    iconHot.setOnClickListener(v -> v.performLongClick());
                }

                boolean hasData = false;

                if (liveKeyInfo.getWeight() != null && liveKeyInfo.getWeight()!=0) {
                    textWeight.setText(String.valueOf(com.fc.fc_ajdk.utils.NumberUtils.formatNumberValue(liveKeyInfo.getWeight(), 5)));
                    textWeight.setVisibility(View.VISIBLE);
                    iconWeight.setVisibility(View.VISIBLE);
                    hasData = true;
                } else {
                    textWeight.setVisibility(View.GONE);
                    iconWeight.setVisibility(View.GONE);
                }

                if (liveKeyInfo.getReputation() != null && liveKeyInfo.getReputation()!=0) {
                    textReputation.setText(String.valueOf(com.fc.fc_ajdk.utils.NumberUtils.formatNumberValue(liveKeyInfo.getReputation(), 5)));
                    textReputation.setVisibility(View.VISIBLE);
                    iconReputation.setVisibility(View.VISIBLE);
                    hasData = true;
                } else {
                    textReputation.setVisibility(View.GONE);
                    iconReputation.setVisibility(View.GONE);
                }

                if (liveKeyInfo.getHot() != null && liveKeyInfo.getHot()!=0) {
                    textHot.setText(String.valueOf(com.fc.fc_ajdk.utils.NumberUtils.formatNumberValue(liveKeyInfo.getHot(), 5)));
                    textHot.setVisibility(View.VISIBLE);
                    iconHot.setVisibility(View.VISIBLE);
                    hasData = true;
                } else {
                    textHot.setVisibility(View.GONE);
                    iconHot.setVisibility(View.GONE);
                }

                // Only show the layout if we have at least one metric
                if (hasData) {
                    cidMetricsLayout.setVisibility(View.VISIBLE);
                } else {
                    cidMetricsLayout.setVisibility(View.GONE);
                }
            }

            // Set up click listeners
            setupLiveFidCardClickListeners();

            TimberLogger.d(TAG, "Live FID card setup completed for FID: %s", liveFid);
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error setting up live FID card: %s", ex.getMessage(), ex);
        }
    }

    private void showLabelEditDialog(String liveFid, com.fc.fc_ajdk.data.fcData.KeyInfo liveKeyInfo) {
        try {
            TimberLogger.d(TAG, "showLabelEditDialog called for FID: %s", liveFid);
            String currentLabel = liveKeyInfo.getLabel();
            if (currentLabel == null) currentLabel = "";
            TimberLogger.d(TAG, "Current label: '%s'", currentLabel);
            
            StringInputDialog dialog = new StringInputDialog(
                this,
                null, // no prompt text needed
                getString(R.string.input_new_label),
                currentLabel,
                new StringInputDialog.OnInputListener() {
                    @Override
                    public void onConfirm(String newLabel) {
                        updateLiveKeyInfoLabel(liveFid, newLabel);
                    }

                    @Override
                    public void onCancel() {
                        // User cancelled, do nothing
                    }
                }
            );
            dialog.show();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing label edit dialog: " + e.getMessage(), e);
            ToastUtils.showError(this, getString(R.string.error_opening_label_editor));
        }
    }

    private void updateLiveKeyInfoLabel(String fid, String newLabel) {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null) {
                TimberLogger.w(TAG, "FidManager not available for label update");
                return;
            }

            com.fc.fc_ajdk.data.fcData.KeyInfo currentKeyInfo = fidManager.getLiveKeyInfo();
            if (currentKeyInfo == null) {
                TimberLogger.w(TAG, "Current KeyInfo not available for label update");
                return;
            }

            // Update the existing KeyInfo in-place instead of creating a new one
            currentKeyInfo.setLabel(newLabel.trim().isEmpty() ? null : newLabel.trim());

            // Save the updated KeyInfo (using the same object reference)
            if (fidManager.updateKeyInfo(this, fid, currentKeyInfo)) {
                TimberLogger.d(TAG, "Successfully updated label for FID: %s", fid);
                
                // Refresh UI
                runOnUiThread(this::refreshLiveFidCard);
                
                ToastUtils.makeText(this, getString(R.string.label_updated));
            } else {
                TimberLogger.w(TAG, "Failed to save updated label for FID: %s", fid);
                ToastUtils.showError(this, getString(R.string.failed_to_save_label));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating label for FID %s: %s", fid, e.getMessage(), e);
            ToastUtils.showError(this, getString(R.string.error_with_message,e.getMessage()));
        }
    }

    private void setupLiveFidCardClickListeners() {
        try {

            // 1. Click CID/FID text to copy the live FID (use liveKeyInfo.getId() as requested)
            TextView nameTextView = findViewById(R.id.fidName);
            if (nameTextView != null) {
                nameTextView.setOnClickListener(v -> {
                    FidManager fidManager = FidManager.getInstance();
                    KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();

                    String idToCopy = liveKeyInfo.getId();
                    if (idToCopy != null) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("Live FID", idToCopy);
                        clipboard.setPrimaryClip(clip);
                        ToastUtils.makeText(this, getString(R.string.copied));
                    }
                });
            }

            // Click QR icon to show FID QR code dialog
            ImageView qrIconView = findViewById(R.id.fidQrIcon);
            if (qrIconView != null) {
                qrIconView.setOnClickListener(v -> {
                    FidManager fidManager = FidManager.getInstance();
                    String liveFid = fidManager.getLiveFid();
                    if (liveFid != null && !liveFid.isEmpty()) {
                        // Show QR code dialog with FID as title and content
                        com.fc.freer.utils.QRCodeGenerator.generateAndShowQRCode(this, liveFid, liveFid);
                    } else {
                        ToastUtils.showWarning(this, getString(R.string.no_fid_available));
                    }
                });
            }

            // Click refresh icon to manually refresh the live FID info from the API
            ImageView refreshIconView = findViewById(R.id.fidRefreshIcon);
            if (refreshIconView != null) {
                refreshIconView.setOnClickListener(v -> {
                    FidManager fidManager = FidManager.getInstance();
                    String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
                    if (liveFid != null && !liveFid.isEmpty()) {
                        ToastUtils.showInfo(this, getString(R.string.refresh));
                        refreshLiveFidInfoFromApi();
                    } else {
                        ToastUtils.showWarning(this, getString(R.string.no_fid_available));
                    }
                });
            }

            // 2. Click label text to edit the label
            TextView labelTextView = findViewById(R.id.fidLabel);
            if (labelTextView != null) {
                labelTextView.setOnClickListener(v -> {
                    FidManager fidManager = FidManager.getInstance();
                    TimberLogger.d(TAG, "Label TextView clicked!");
                    // Get fresh data from FidManager to avoid stale references
                    String currentLiveFid = fidManager.getLiveFid();
                    KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();
                    if (currentLiveFid != null && currentLiveKeyInfo != null) {
                        showLabelEditDialog(currentLiveFid, currentLiveKeyInfo);
                    } else {
                        ToastUtils.showWarning(this, getString(R.string.no_active_fid_available));
                    }
                });
            }

            // 3. Click edit icon to add/edit the label
            ImageView editIconView = findViewById(R.id.fidEditIcon);
            if (editIconView != null) {
                editIconView.setOnClickListener(v -> {
                    TimberLogger.d(TAG, "Edit icon clicked!");
                    // Get fresh data from FidManager to avoid stale references
                    FidManager currentFidManager = FidManager.getInstance();
                    if (currentFidManager != null) {
                        String currentLiveFid = currentFidManager.getLiveFid();
                        com.fc.fc_ajdk.data.fcData.KeyInfo currentLiveKeyInfo = currentFidManager.getLiveKeyInfo();
                        if (currentLiveFid != null && currentLiveKeyInfo != null) {
                            showLabelEditDialog(currentLiveFid, currentLiveKeyInfo);
                        } else {
                            ToastUtils.showWarning(this, getString(R.string.no_fid_available));
                        }
                    }
                });
            }

            // 2. Click avatar to show avatar dialog
            ImageView avatarImageView = findViewById(R.id.fidAvatar);
            if (avatarImageView != null) {
                avatarImageView.setOnClickListener(v -> {
                    AvatarManager.showAvatarDialog(this, FidManager.getInstance().getLiveFid());
                });
            }

            // 3. Click no_prikey/people icon to add private key or open multisig detail
            setMultisignIconListener();

            // 4. Click cash, balance, or cd numbers/labels to open CashActivity
            TextView cashTextView = findViewById(R.id.fidCash);
            TextView balanceTextView = findViewById(R.id.fidBalance);
            TextView cdTextView = findViewById(R.id.fidCd);
//            TextView cashLabelTextView = findViewById(R.id.fidCashLabel);
//            TextView balanceLabelTextView = findViewById(R.id.fidBalanceLabel);
//            TextView cdLabelTextView = findViewById(R.id.fidCdLabel);
            
            View.OnClickListener openCashActivity = v -> {
                try {
                    Intent intent = new Intent(HomeActivity.this, CashActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error opening CashActivity: " + e.getMessage(), e);
                    ToastUtils.showError(this, getString(R.string.error_with_message,e.getMessage()));
                }
            };
            
            // Set click listeners for numbers
            if (cashTextView != null) {
                cashTextView.setOnClickListener(openCashActivity);
            }
            if (balanceTextView != null) {
                balanceTextView.setOnClickListener(openCashActivity);
            }
            if (cdTextView != null) {
                cdTextView.setOnClickListener(openCashActivity);
            }
            
            // Set click listeners for labels
//            if (cashLabelTextView != null) {
//                cashLabelTextView.setOnClickListener(openCashActivity);
//            }
//            if (balanceLabelTextView != null) {
//                balanceLabelTextView.setOnClickListener(openCashActivity);
//            }
//            if (cdLabelTextView != null) {
//                cdLabelTextView.setOnClickListener(openCashActivity);
//            }

            // 5. Click good number or hot number to open RateHistoryActivity
            TextView textReputation = findViewById(R.id.text_reputation);
            TextView textHot = findViewById(R.id.text_hot);

            View.OnClickListener openRateHistory = v -> {
                try {
                    FidManager currentFidManager = FidManager.getInstance();
                    String currentLiveFid = currentFidManager != null ? currentFidManager.getLiveFid() : null;
                    if (currentLiveFid != null) {
                        Intent intent = new Intent(HomeActivity.this, RateHistoryActivity.class);
                        intent.putExtra(RateHistoryActivity.EXTRA_RATEE_FID, currentLiveFid);
                        startActivity(intent);
                    } else {
                        ToastUtils.showWarning(this, getString(R.string.no_fid_available));
                    }
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error opening RateHistoryActivity: " + e.getMessage(), e);
                    ToastUtils.showError(this, getString(R.string.error_with_message, e.getMessage()));
                }
            };

            if (textReputation != null) {
                textReputation.setOnClickListener(openRateHistory);
            }
            if (textHot != null) {
                textHot.setOnClickListener(openRateHistory);
            }

            // 6. Click any blank place of liveFidCard to open DetailActivity
            View liveFidCard = findViewById(R.id.liveFidCard);
            if (liveFidCard != null) {
                liveFidCard.setOnClickListener(v -> {
                    try {
                        // Get the current live KeyInfo instead of using the captured parameter
                        FidManager currentFidManager= FidManager.getInstance();

                        com.fc.fc_ajdk.data.fcData.KeyInfo currentLiveKeyInfo = currentFidManager != null ? currentFidManager.getLiveKeyInfo() : null;
                        if (currentLiveKeyInfo != null) {
                            Intent intent = new Intent(HomeActivity.this, com.fc.freer.ui.DetailActivity.class);
                            intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, currentLiveKeyInfo.toJson());
                            intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, currentLiveKeyInfo.getClass().getName());
                            startActivity(intent);
                        } else {
                            ToastUtils.showError(this, getString(R.string.no_fid_available));
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error opening DetailActivity for live KeyInfo: " + e.getMessage(), e);
                        ToastUtils.showError(this, R.string.error_opening_detail+": " + e.getMessage());
                    }
                });
            }

            TimberLogger.d(TAG, "Live FID card click listeners setup completed");
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error setting up live FID card click listeners: " + ex.getMessage(), ex);
        }
    }

    private void setMultisignIconListener() {
        ImageView noPrikeyIconView = findViewById(R.id.multisigIcon);
        if (noPrikeyIconView != null) {
            noPrikeyIconView.setOnClickListener(v -> {
                FidManager currentFidManager = FidManager.getInstance();
                if (currentFidManager != null) {
                    String currentLiveFid = currentFidManager.getLiveFid();

                    // Check if this is a multisig FID (starts with '3')
                    if (currentLiveFid != null && currentLiveFid.startsWith("3")) {
                        TimberLogger.d(TAG, "People icon clicked for multisig FID: %s", currentLiveFid);
                        Multisig multisig = currentFidManager.getLiveKeyInfo().getMultisign();

                        if (multisig != null) {
                            Intent intent = new Intent(this, MultisigDetailActivity.class);
                            intent.putExtra("multisig", multisig.toJson());
                            startActivity(intent);
                        } else {
                            ToastUtils.showWarning(this, getString(R.string.multisign_data_not_found_for_fid, currentLiveFid));
                            TimberLogger.w(TAG, "Multisig not found for FID: %s", currentLiveFid);
                        }
                    }
//                    else {
//                        TimberLogger.d(TAG, "No prikey icon clicked!");
//                        addPrikeyPopupMenuHelper.showAddPrikeyMenu(noPrikeyIconView);
//                    }
                }
            });
        }
    }

    private String formatBalance(double balance) {
        if (balance >= 100000) {
            return formatLargeNumber((long) balance);
        } else if (balance >= 1000) {
            return String.valueOf((long) balance);
        } else {
            return String.valueOf(balance);
        }
    }

    private String formatLargeNumber(long number) {
        if (number >= 1000000000) {
            return String.format("%.1fB", number / 1000000000.0);
        } else if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    private void loadModulesAsync() {
        new Thread(() -> {
            try {
                loadModules();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error in background module loading: " + e.getMessage(), e);
                runOnUiThread(() -> ToastUtils.showError(this, getString(R.string.error_with_message , e.getMessage())));
            }
        }
        ).start();
    }

    private void loadModules() {

        Configure configure = ConfigureManager.getInstance().getConfigure();
        if (configure == null) {
            TimberLogger.e(TAG, "Configure object not found in ConfigureManager");
            return;
        }

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) {
            TimberLogger.e(TAG, "Setting object not found in ConfigureManager");
            return;
        }
        // Initialize PendingIssueManager early from local DB so the badge is
        // available immediately, before any network calls complete.
        String mainFid = setting.getFid();
        com.fc.freer.im.PendingIssueManager earlyPim =
                new com.fc.freer.im.PendingIssueManager(this, mainFid, null);
        // Attach the badge listener immediately so issues created by the initial IM sync
        // (which runs after this point) refresh the Todo badge without waiting for an onResume.
        earlyPim.setCountChangeListener(newCount -> runOnUiThread(this::updateBadges));
        FidManager.getInstance().setPendingIssueManager(earlyPim);
        runOnUiThread(this::updateBadges);

        // Check network connectivity before initializing API services
        boolean isNetworkAvailable = NetworkUtils.isNetworkAvailable(this);
        if (!isNetworkAvailable) {
            TimberLogger.w(TAG, "Network is not available, skipping API initialization");
            runOnUiThread(() -> ToastUtils.showWarning(this, getString(R.string.network_unavailable_continuing_offline)));
        } else {
            // Initialize API services using ApiCenter
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) {
                    TimberLogger.e(TAG, "Failed to get ApiCenter instance");
                    return;
                }
                
                apiCenter.setCurrentSetting(setting);
                apiCenter.setCurrentConfigure(configure);
                
                // Initialize API client groups with error handling
                apiCenter.initiate(this);

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error initializing API services: %s", e.getMessage(), e);
                // Don't treat API service initialization failure as fatal
                TimberLogger.w(TAG, "Continuing without API services due to initialization error");
            }
        }

        // Initialize managers for the current liveFid (FidManager should already be initialized)
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : setting.getFid();

        refreshLiveFidInfoFromApi();

        loadFcDate();

        initManagers(setting, liveFid);

        // Initialize ImManager (requires FudpNode + FapiClient from ApiCenter)
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        ImManager imManager = setting.getOrCreateImManager(this, fapiClient);
        if (imManager != null) {
            // Wire the already-created PendingIssueManager into ImManager so it gains
            // ContactPolicy, ImManager back-reference, and FudpNode for full functionality.
            imManager.setPendingIssueManager(earlyPim);
            FidManager.getInstance().setImManager(imManager);
            imManager.setChannelSetupCallback(suggestedUrl ->
                    runOnUiThread(() -> maybeShowServerSetupPrompt(true)));
            TimberLogger.d(TAG, "ImManager initialized for FID: %s", liveFid);
        }

        runOnUiThread(this::updateBadges);

        // Check FAPI connection and retry if needed
//        checkAndRetryFapiPing();
    }

    private void loadFcDate() {
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) return;
        FapiResponse lastResponse = fapiClient.getLastResponse();
        if (lastResponse == null) return;
        Long bestHeightBoxed = lastResponse.getBestHeight();
        if (bestHeightBoxed == null) return;
        final long bestHeight = bestHeightBoxed;
        runOnUiThread(() -> {
            TextView fcDateTextView = findViewById(R.id.fc_date_text);
            fcDateTextView.setText(FcDate.fromHeight(bestHeight).toString());

            // Set up tooltip with detailed information
            String heightInfo = getString(R.string.height_info_format,
                    bestHeight,
                    com.fc.fc_ajdk.utils.FcUtils.heightToShortDate(bestHeight));
            fcDateTextView.setTooltipText(heightInfo);
            fcDateTextView.setOnClickListener(View::performLongClick);
        });
    }

    private void initManagers(Setting setting, String fid) {
        if (fid == null) fid = setting.getFid();
        
        TimberLogger.d(TAG, "Initializing managers for FID: %s", fid);
        
        FidManager.getInstance();
        ToastManager toastManager = ToastManager.getInstance(this,fid);
        FidManager.getInstance().setToastManager(toastManager);

        if (setting.getManagers() != null) {
            for (FcManager.ManagerType managerType : setting.getManagers()) {
                switch (managerType) {
                    case APP:
                        AppManager appManager = AppManager.getInstance(this, fid);
                        FidManager.getInstance().setAppManager(appManager);
                        TimberLogger.d(TAG, "Loaded AppManager for FID: %s", fid);
                        break;

                    case CASH:
                        CashManager cashManager = CashManager.getInstance(this, fid);
                        FidManager.getInstance().setCashManager(cashManager);
                        TimberLogger.d(TAG, "Loaded CashManager for FID: %s", fid);
                        break;
                        
                    case SECRET:
                        SecretManager secretManager = SecretManager.getInstance(this, fid);
                        FidManager.getInstance().setSecretManager(secretManager);
                        TimberLogger.d(TAG, "Loaded SecretManager for FID: %s", fid);
                        break;

                    case CONTACT:
                        ContactManager contactManager = ContactManager.getInstance(this, fid);
                        FidManager.getInstance().setContactManager(contactManager);
                        TimberLogger.d(TAG, "Loaded ContactManager for FID: %s", fid);
                        break;
                    case MAIL:
                        MailManager mailManager = MailManager.getInstance(this, fid);
                        FidManager.getInstance().setMailManager(mailManager);
                        TimberLogger.d(TAG, "Loaded MailManager for FID: %s", fid);
                        break;

                    case FC_OBJECT:
                        FcObjectManager fcObjectManager = FcObjectManager.getInstance(this, fid);
                        FidManager.getInstance().setFcObjectManager(fcObjectManager);
                        TimberLogger.d(TAG, "Loaded FcObjectManager for FID: %s", fid);
                        break;

                    case PROOF:
                        ProofManager proofManager = ProofManager.getInstance(this, fid);
                        FidManager.getInstance().setProofManager(proofManager);
                        TimberLogger.d(TAG, "Loaded ProofManager for FID: %s", fid);
                        break;

                    default:
                        TimberLogger.w(TAG, "Unknown manager type: %s", managerType);
                        break;
                }
            }
            
            // Store references in FidManager for future access
            // This will be handled by FidManager.reloadManagers() when switching FIDs
            TimberLogger.d(TAG, "Manager initialization completed for FID: %s", fid);
        } else {
            TimberLogger.w(TAG, "No managers defined in setting");
        }
    }

    private void refreshLiveFidInfoFromApi() {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager != null) {
            fidManager.refreshLiveFidCidInfoAsync(this, this::onLiveFidInfoRefreshed);
        }
    }

    /**
     * Invoked on the UI thread once the live FID's on-chain info (incl. home.DISK) has been
     * fetched. The KeyInfo home map is now authoritative, so it's safe to evaluate the DISK
     * server-setup prompt without a false positive for a FID already configured on-chain.
     */
    private void onLiveFidInfoRefreshed() {
        liveFidInfoRefreshed = true;
        refreshLiveFidCard();
        maybeShowServerSetupPrompt(false);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        TimberLogger.d(TAG, "=== HOME ACTIVITY onActivityResult ===");
        TimberLogger.d(TAG, "requestCode: %d, resultCode: %d", requestCode, resultCode);

        // Handle password change completion - must return immediately to prevent UI refresh
        if (requestCode == 9999) {
            if (resultCode == RESULT_OK) {
                // Password was changed successfully, app should be restarting
                // Do NOT refresh UI - just wait for System.exit(0) from ChangePasswordActivity
                isPasswordChanging = true;
                TimberLogger.d(TAG, "Password changed successfully, waiting for app exit");
            } else {
                // Password change was cancelled or failed
                isPasswordChanging = false;
                TimberLogger.d(TAG, "Password change cancelled or failed");
            }
            // IMPORTANT: Return immediately - do not execute the RESULT_OK refresh logic below
            return;
        }

        // Handle API services change completion
        if (requestCode == 9998) {
            // App restart is handled by ApiServicesActivity via FLAG_ACTIVITY_CLEAR_TASK
            // No special handling needed here
            return;
        }

        // Handle SetCidActivity completion
        if (requestCode == 9997) {
            if (resultCode == RESULT_OK) {
                // CID was set successfully
                TimberLogger.d(TAG, "CID set successfully");
                Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
                if (currentSetting != null) {
                    // Mark that user has been prompted to set CID
                    currentSetting.getStateMap().put(Setting.KEY_PROMOTED_SET_CID, true);
                    // Save the updated setting
                    SettingManager.getInstance().saveSettings(this, currentSetting);
                }
            } else {
                // CID setting was cancelled or failed
                TimberLogger.d(TAG, "CID setting cancelled or failed");
            }
            // IMPORTANT: Return immediately - do not execute the RESULT_OK refresh logic below
            return;
        }

        if (resultCode == RESULT_OK) {
            // Give a small delay to ensure FID switching is complete
            new android.os.Handler().postDelayed(() -> {
                FidManager fidManager = FidManager.getInstance();
                if (fidManager != null) {
                    String currentLiveFid = fidManager.getLiveFid();
                    KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();

                    // Refresh the live FID card with current FID data
                    refreshLiveFidCard();

                    // Also refresh API data in background and update UI again
                    refreshLiveFidInfoFromApi();

                } else {
                    TimberLogger.e(TAG, "FidManager is null in onActivityResult!");
                }
            }, 100); // Small delay to ensure FID switch is complete
        } else {
            TimberLogger.d(TAG, "Activity result was not RESULT_OK, skipping refresh");
        }
    }
    
    public void refreshLiveFidCard() {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
                TimberLogger.w(TAG, "FidManager or live KeyInfo not available for refresh");
                return;
            }

            // Get fresh KeyInfo
            com.fc.fc_ajdk.data.fcData.KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
            String liveFid = fidManager.getLiveFid();

            // Find UI elements and update with fresh data
            ImageView avatarImageView = findViewById(R.id.fidAvatar);
            TextView nameTextView = findViewById(R.id.fidName);
            TextView labelTextView = findViewById(R.id.fidLabel);
            ImageView editIconView = findViewById(R.id.fidEditIcon);
            TextView cashTextView = findViewById(R.id.fidCash);
            TextView balanceTextView = findViewById(R.id.fidBalance);
            TextView cdTextView = findViewById(R.id.fidCd);
            ImageView noPrikeyIconView = findViewById(R.id.multisigIcon);
            ImageView deadIconView = findViewById(R.id.fidDeadIcon);

            // Update avatar
            if (avatarImageView != null) {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(liveFid);
                if (avatarBitmap != null) {
                    avatarImageView.setImageBitmap(avatarBitmap);
                } else {
                    avatarImageView.setImageResource(R.drawable.ic_person);
                }
            }

            // Ensure QR icon is visible
            ImageView qrIconViewRefresh = findViewById(R.id.fidQrIcon);
            if (qrIconViewRefresh != null) {
                qrIconViewRefresh.setVisibility(View.VISIBLE);
            }

            // Update name (cid if available, otherwise fid)
            if (nameTextView != null) {
                String displayName = liveKeyInfo.getCid();
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = liveFid;
                }
                nameTextView.setText(displayName);
                TimberLogger.d(TAG, "Name TextView updated to: '%s', visibility: %s", displayName, nameTextView.getVisibility() == View.VISIBLE ? "VISIBLE" : "NOT_VISIBLE");
            } else {
                TimberLogger.w(TAG, "Name TextView is null!");
            }

            // Update label or edit icon
            if (labelTextView != null && editIconView != null) {
                String label = liveKeyInfo.getLabel();
                TimberLogger.d(TAG, "Label value: '%s'", label);
                if (label != null && !label.trim().isEmpty()) {
                    // Show label, hide edit icon
                    labelTextView.setText(label);
                    labelTextView.setVisibility(View.VISIBLE);
                    editIconView.setVisibility(View.GONE);
                    TimberLogger.d(TAG, "Label TextView updated to: '%s', visibility: %s", label, labelTextView.getVisibility() == View.VISIBLE ? "VISIBLE" : "NOT_VISIBLE");
                } else {
                    // Show edit icon, hide label
                    labelTextView.setVisibility(View.GONE);
                    editIconView.setVisibility(View.VISIBLE);
                    TimberLogger.d(TAG, "No label, showing edit icon instead");
                }
            } else {
                TimberLogger.w(TAG, "Label TextView or Edit Icon is null!");
            }

            // Update cash amount (handle null case)
            if (cashTextView != null) {
                Long cash = liveKeyInfo.getCash();
                TimberLogger.d(TAG, "Refreshing cash value: %s for FID: %s", cash, liveFid);
                String cashText = (cash != null && cash > 0) ? String.valueOf(cash) : "-";
                cashTextView.setText(cashText);
                TimberLogger.d(TAG, "Cash TextView updated to: '%s', visibility: %s", cashText, cashTextView.getVisibility() == View.VISIBLE ? "VISIBLE" : "NOT_VISIBLE");
            } else {
                TimberLogger.w(TAG, "Cash TextView is null!");
            }

            // Update balance (handle null case)
            if (balanceTextView != null) {
                Long balance = liveKeyInfo.getBalance();
                TimberLogger.d(TAG, "Refreshing balance value: %s for FID: %s", balance, liveFid);
                String balanceText;
                if (balance != null && balance > 0) {
                    double balanceInCoins = FchUtils.satoshiToCoin(balance);
                    balanceText = formatBalance(balanceInCoins);
                } else {
                    balanceText = "-";
                }
                balanceTextView.setText(balanceText);
                TimberLogger.d(TAG, "Balance TextView updated to: '%s', visibility: %s", balanceText, balanceTextView.getVisibility() == View.VISIBLE ? "VISIBLE" : "NOT_VISIBLE");
            } else {
                TimberLogger.w(TAG, "Balance TextView is null!");
            }

            // Update cd (confirmation depth, handle null case)
            if (cdTextView != null) {
                Long cd = liveKeyInfo.getCd();
                TimberLogger.d(TAG, "Refreshing cd value: %s for FID: %s", cd, liveFid);
                String cdText = (cd != null && cd > 0) ? formatLargeNumber(cd) : "-";
                cdTextView.setText(cdText);
                TimberLogger.d(TAG, "CD TextView updated to: '%s', visibility: %s", cdText, cdTextView.getVisibility() == View.VISIBLE ? "VISIBLE" : "NOT_VISIBLE");
            } else {
                TimberLogger.w(TAG, "CD TextView is null!");
            }
            // Update no_prikey icon or people icon based on FID type and prikeyCipher
            if (noPrikeyIconView != null) {
                String prikeyCipher = liveKeyInfo.getPrikeyCipher();
                if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                    // Check if liveFid is a multisig FID (starts with '3')
                    if (liveFid != null && liveFid.startsWith("3")) {
                        noPrikeyIconView.setImageResource(R.drawable.ic_people);
                    } else {
                        noPrikeyIconView.setImageResource(R.drawable.ic_no_prikey);
                    }
                    noPrikeyIconView.setVisibility(View.VISIBLE);
                } else {
                    noPrikeyIconView.setVisibility(View.GONE);
                }
            }

            // Update dead icon based on isNobody
            if (deadIconView != null) {
                Boolean isNobody = liveKeyInfo.getIsNobody();
                if (isNobody != null && isNobody) {
                    deadIconView.setVisibility(View.VISIBLE);
                    // Setup tooltip for dead icon
                    deadIconView.setTooltipText(getString(R.string.prikey_leaked_tooltip));
                    // Also show tooltip on click
                    deadIconView.setOnClickListener(v -> v.performLongClick());
                } else {
                    deadIconView.setVisibility(View.GONE);
                }
            }

            // Update CID metrics visibility and values
            LinearLayout cidMetricsLayout = findViewById(R.id.cid_metrics_layout);
            if (cidMetricsLayout != null) {
                TextView textWeight = findViewById(R.id.text_weight);
                TextView textReputation = findViewById(R.id.text_reputation);
                TextView textHot = findViewById(R.id.text_hot);
                ImageView iconWeight = findViewById(R.id.icon_weight);
                ImageView iconReputation = findViewById(R.id.icon_reputation);
                ImageView iconHot = findViewById(R.id.icon_hot);

                // Setup tooltips for CID metrics icons
                if (iconWeight != null) {
                    iconWeight.setTooltipText(getString(R.string.weight_tooltip));
                    iconWeight.setOnClickListener(v -> v.performLongClick());
                }
                if (iconReputation != null) {
                    iconReputation.setTooltipText(getString(R.string.reputation_tooltip));
                    iconReputation.setOnClickListener(v -> v.performLongClick());
                }
                if (iconHot != null) {
                    iconHot.setTooltipText(getString(R.string.hot_tooltip));
                    iconHot.setOnClickListener(v -> v.performLongClick());
                }

                boolean hasData = false;

                if (liveKeyInfo.getWeight() != null && liveKeyInfo.getWeight()!=0) {
                    textWeight.setText(String.valueOf(com.fc.fc_ajdk.utils.NumberUtils.formatNumberValue(liveKeyInfo.getWeight(), 5)));
                    textWeight.setVisibility(View.VISIBLE);
                    iconWeight.setVisibility(View.VISIBLE);
                    hasData = true;
                } else {
                    textWeight.setVisibility(View.GONE);
                    iconWeight.setVisibility(View.GONE);
                }

                if (liveKeyInfo.getReputation() != null && liveKeyInfo.getReputation()!=0) {
                    textReputation.setText(String.valueOf(com.fc.fc_ajdk.utils.NumberUtils.formatNumberValue(liveKeyInfo.getReputation(), 5)));
                    textReputation.setVisibility(View.VISIBLE);
                    iconReputation.setVisibility(View.VISIBLE);
                    hasData = true;
                } else {
                    textReputation.setVisibility(View.GONE);
                    iconReputation.setVisibility(View.GONE);
                }

                if (liveKeyInfo.getHot() != null && liveKeyInfo.getHot()!=0) {
                    textHot.setText(String.valueOf(com.fc.fc_ajdk.utils.NumberUtils.formatNumberValue(liveKeyInfo.getHot(), 5)));
                    textHot.setVisibility(View.VISIBLE);
                    iconHot.setVisibility(View.VISIBLE);
                    hasData = true;
                } else {
                    textHot.setVisibility(View.GONE);
                    iconHot.setVisibility(View.GONE);
                }

                // Only show the layout if we have at least one metric
                if (hasData) {
                    cidMetricsLayout.setVisibility(View.VISIBLE);
                } else {
                    cidMetricsLayout.setVisibility(View.GONE);
                }
            }

            updateMenuVisibilityForLiveFid();
            TimberLogger.d(TAG, "Live FID card refreshed with API data");
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error refreshing live FID card: " + ex.getMessage(), ex);
        }
    }

    /**
     * The Talk tile stays enabled even without home.DOCK: P2P chat runs in
     * send-only mode (direct put into the recipient's DOCK), which is what lets
     * a zero-balance newcomer ask for their first FCH. The send-only banner in
     * TalkActivity explains the receive limitation.
     */
    private void updateTalkTileState() {
        if (talkView == null) return;
        talkView.setEnabled(true);
        talkView.setAlpha(1f);
    }

    /** Grey out and disable the Data tile until home.DISK is configured. */
    private void updateDataTileState() {
        if (dataView == null) return;
        boolean ready = isDiskReady();
        dataView.setEnabled(ready);
        dataView.setAlpha(ready ? 1f : 0.4f);
    }

    private boolean isDiskReady() {
        com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfo = FidManager.getInstance().getMainKeyInfo();
        return com.fc.freer.data.DiskHomeManager.isConfigured(mainKeyInfo);
    }

    /**
     * Show the combined server-setup prompt at most once per visit when either the
     * messaging (DOCK) or data (DISK) server is unconfigured. Routes both the
     * IM-driven (onChannelNotConfigured) and the DISK-unset cases through one guard
     * so the user isn't double-prompted.
     */
    private void maybeShowServerSetupPrompt(boolean includeDock) {
        if (serverSetupPrompted) return;
        if (isFinishing() || isDestroyed()) return;

        // DISK config is a reliable local-home check; DOCK is only considered when the
        // caller knows the on-chain check ran (the ImManager callback), to avoid a
        // spurious prompt before ImManager has determined the DOCK state.
        // The DISK check is only trustworthy once the on-chain home has been fetched; before
        // that a missing home.DISK entry just means "not loaded yet", not "not configured".
        boolean needDisk = liveFidInfoRefreshed && !isDiskReady() && !isDiskRegistrationPending();
        boolean needDock = includeDock && !isImChannelReady() && !isImRegistrationPending();
        if (!needDisk && !needDock) return;

        serverSetupPrompted = true;
        com.fc.freer.im.ChannelSetupDialog.show(this, null);
    }

    private boolean isImRegistrationPending() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return false;
        ImManager imManager = setting.getImManager();
        return imManager != null && imManager.isRegistrationPending();
    }

    private boolean isDiskRegistrationPending() {
        String fid = FidManager.getInstance().getMainFid();
        return com.fc.freer.data.DiskHomeManager.isRegistrationPending(this, fid);
    }

    private boolean isImChannelReady() {
        if (hasDockInLocalHome()) return true;
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return false;
        ImManager imManager = setting.getImManager();
        return imManager != null && imManager.isChannelConfigured();
    }

    private boolean hasDockInLocalHome() {
        com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfo = FidManager.getInstance().getMainKeyInfo();
        if (mainKeyInfo == null || mainKeyInfo.getHome() == null) return false;
        for (java.util.Map.Entry<String, String> entry : mainKeyInfo.getHome().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && key.startsWith("DOCK") && value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void updateBadges() {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();

            // TODO badge: pending issues count — read from FidManager directly so
            // the badge works as soon as the local DB is loaded (before ImManager).
            int todoCount = 0;
            PendingIssueManager pendingMgrForBadge = FidManager.getInstance().getPendingIssueManager();
            if (pendingMgrForBadge != null) {
                todoCount = pendingMgrForBadge.getPendingCount();
            }
            setBadgeCount(badgeTodo, todoCount);

            // IM badges: unread counts by type
            int groupCount = 0, teamCount = 0, roomCount = 0, talkCount = 0;
            if (setting != null) {
                ImManager imManager = setting.getImManager();
                if (imManager != null) {
                    groupCount = imManager.getUnreadCountByType(ImType.SQUARE);
                    teamCount = imManager.getUnreadCountByType(ImType.TEAM);
                    roomCount = imManager.getUnreadCountByType(ImType.ROOM);
                    talkCount = imManager.getUnreadCountByType(ImType.P2P);
                }
            }
            setBadgeCount(badgeGroup, groupCount);
            setBadgeCount(badgeTeam, teamCount);
            setBadgeCount(badgeRoom, roomCount);
            setBadgeCount(badgeTalk, talkCount);

            // Cash badge: new cash count
            int cashCount = 0;
            FidManager fidManager = FidManager.getInstance();
            if (fidManager != null) {
                CashManager cashManager = fidManager.getCashManager();
                if (cashManager != null) {
                    cashCount = cashManager.getNewCashCount();
                }
            }
            setBadgeCount(badgeCash, cashCount);

            // Mail badge: unread mail count
            int mailCount = 0;
            MailManager mailManager = MailManager.getInstance();
            if (mailManager != null) {
                mailCount = mailManager.getUnreadMailCount();
            }
            setBadgeCount(badgeMail, mailCount);

            // Keep the Talk/Data tiles' enabled state in sync with DOCK/DISK availability.
            updateTalkTileState();
            updateDataTileState();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating badges: " + e.getMessage(), e);
        }
    }

    private void setBadgeCount(TextView badge, int count) {
        if (badge == null) return;
        if (count > 0) {
            badge.setText(count > 99 ? "99+" : String.valueOf(count));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Don't refresh UI if password change is in progress (app will exit soon)
        if (isPasswordChanging) {
            TimberLogger.d(TAG, "HomeActivity onResume - skipping refresh due to password change in progress");
            return;
        }


        // Refresh the live FID card when activity resumes to ensure it shows current live FID
        FidManager fidManager = FidManager.getInstance();
        if (fidManager != null && fidManager.getLiveKeyInfo() != null) {
            refreshLiveFidCard();
            TimberLogger.d(TAG, "HomeActivity onResume - refreshed live FID card for: %s", fidManager.getLiveFid());
        }

        updateBadges();

        loadFcDate();

        // Check FAPI connection and retry if needed
//        checkAndRetryFapiPing();

        // Register stranger peer callback
        registerStrangerPeerCallback();

        // Prompt to set up servers if DISK is unconfigured (local-home check). The DOCK
        // case is driven by ImManager's onChannelNotConfigured callback.
        maybeShowServerSetupPrompt(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up popup menu helpers to prevent dialog leaks
        if (personPopupMenuHelper != null) {
            personPopupMenuHelper.cleanup();
        }
        if (addPrikeyPopupMenuHelper != null) {
            addPrikeyPopupMenuHelper.cleanup();
        }

        // Unregister stranger peer callback
        unregisterStrangerPeerCallback();

        TimberLogger.d(TAG, "HomeActivity onDestroy completed");
    }

    private final ImManager.ImListener teamNotificationListener = new ImManager.ImListener() {
        @Override
        public void onMessageReceived(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            runOnUiThread(() -> updateBadges());
        }
        @Override
        public void onMessageSent(com.fc.fc_ajdk.data.fcData.ImMessage message) {}
        @Override
        public void onConversationUpdated(com.fc.fc_ajdk.data.fcData.Conversation conversation) {
            runOnUiThread(() -> updateBadges());
        }
        @Override
        public void onPresenceChanged(String fid, boolean online) {}
        @Override
        public void onError(String error) {}

        @Override
        public void onStrangerMessageReceived(String senderFid, int totalCount) {
            runOnUiThread(() -> updateBadges());
        }

        @Override
        public void onRoomInviteReceived(com.fc.freer.im.PendingIssue issue) {
            runOnUiThread(() -> {
                updateBadges();
                showRoomInviteDialog(issue);
            });
        }

        @Override
        public void onChannelNotConfigured(String suggestedUrl) {
            runOnUiThread(() -> {
                updateTalkTileState();
                maybeShowServerSetupPrompt(true);
            });
        }

        @Override
        public void onChannelConfigured() {
            // home.DOCK confirmed on-chain (e.g. a pending registration landed):
            // enable the Talk tile and let the user know messaging is ready.
            runOnUiThread(() -> {
                updateTalkTileState();
                updateBadges();
                ToastUtils.showInfo(HomeActivity.this, getString(R.string.im_channel_ready));
            });
        }
    };



    private void showRoomInviteDialog(com.fc.freer.im.PendingIssue issue) {
        if (isFinishing() || isDestroyed()) return;

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return;
        ImManager imManager = setting.getImManager();
        if (imManager == null) return;

        com.fc.freer.im.RoomInviteDialog.show(HomeActivity.this, issue,
                new com.fc.freer.im.RoomInviteDialog.DialogCallback() {
                    @Override
                    public void onAccepted(com.fc.freer.im.PendingIssue issue) {
                        com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                        if (m != null && issue.getId() != null) {
                            m.acceptRoomInvite(issue.getId());
                        }
                        ToastUtils.showInfo(HomeActivity.this, getString(R.string.room_invite_accepted));
                    }

                    @Override
                    public void onRejected(com.fc.freer.im.PendingIssue issue) {
                        com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                        if (m != null && issue.getId() != null) {
                            m.rejectRoomInvite(issue.getId());
                        }
                        ToastUtils.showInfo(HomeActivity.this, getString(R.string.room_invite_rejected));
                    }

                    @Override
                    public void onDeferred(com.fc.freer.im.PendingIssue issue) {
                        TimberLogger.d(TAG, "Room invite deferred: %s", issue.getId());
                    }
                });
    }


    private void registerStrangerPeerCallback() {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;

            // Always attach the badge listener to the PendingIssueManager held in
            // FidManager — it is available even before ImManager is fully started, so this
            // must run before the imManager null-check below. Otherwise, on a freshly-loaded
            // FID whose ImManager isn't ready yet, the listener never attaches and pending
            // issues that arrive from the initial IM sync don't refresh the Todo badge until
            // the next onResume.
            PendingIssueManager pendingMgr = FidManager.getInstance().getPendingIssueManager();
            if (pendingMgr != null) {
                pendingMgr.setCountChangeListener(newCount -> runOnUiThread(() -> updateBadges()));
            }

            ImManager imManager = setting.getImManager();
            if (imManager == null) return;

            imManager.addListener(teamNotificationListener);

            FidManager fidMgr = FidManager.getInstance();
            if (fidMgr != null) {
                CashManager cashMgr = fidMgr.getCashManager();
                if (cashMgr != null) {
                    cashMgr.setNewCashListener(newCashCount -> runOnUiThread(() -> updateBadges()));
                }
            }

            imManager.setStrangerPeerCallback(peerFid -> {
                if (com.fc.freer.im.P2pChatSettingsActivity.isAutoAcceptContacts(HomeActivity.this, imManager.getLiveFid())) {
                    ContactManager contactManager = ContactManager.getInstance();
                    if (contactManager != null && contactManager.checkIfFidExisted(peerFid)) {
                        com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                        if (m != null) m.accept("STRANGER_PEER_" + peerFid);
                        TimberLogger.i(TAG, "Auto-accepted contact peer: %s", peerFid);
                        ToastUtils.showInfo(HomeActivity.this, getString(R.string.contact_auto_accepted, peerFid));
                        return;
                    }
                }

                com.fc.freer.im.PendingIssueManager mgr = imManager.getPendingIssueManager();
                com.fc.freer.im.PendingIssue issue = (mgr != null)
                        ? mgr.getIssue("STRANGER_PEER_" + peerFid)
                        : null;
                if (issue == null) {
                    issue = com.fc.freer.im.PendingIssue.createStrangerPeer(peerFid);
                }
                com.fc.freer.im.StrangerPeerDialog.show(HomeActivity.this, issue,
                        new com.fc.freer.im.StrangerPeerDialog.DialogCallback() {
                            @Override
                            public void onAccepted(String peerFid) {
                                com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                                if (m != null) m.accept("STRANGER_PEER_" + peerFid);
                                ToastUtils.showInfo(HomeActivity.this, getString(R.string.peer_accepted));
                            }

                            @Override
                            public void onRejected(String peerFid) {
                                com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                                if (m != null) m.reject("STRANGER_PEER_" + peerFid);
                                ToastUtils.showInfo(HomeActivity.this, getString(R.string.peer_rejected));
                            }

                            @Override
                            public void onDismissed(String peerFid) {
                                TimberLogger.d(TAG, "Stranger peer dialog dismissed for: %s", peerFid);
                            }

                            @Override
                            public void onBlacklisted(String peerFid) {
                                com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                                if (m != null) m.reject("STRANGER_PEER_" + peerFid);
                                com.fc.freer.im.ContactPolicy policy = imManager.getContactPolicy();
                                if (policy != null) {
                                    policy.addToBlacklist(peerFid);
                                }
                                imManager.purgeQuarantinedMessages(peerFid);
                                ToastUtils.showInfo(HomeActivity.this, getString(R.string.peer_blacklisted));
                            }

                            @Override
                            public void onBlockAllStrangers(String peerFid) {
                                com.fc.freer.im.PendingIssueManager m = imManager.getPendingIssueManager();
                                if (m != null) m.reject("STRANGER_PEER_" + peerFid);
                                com.fc.freer.im.ContactPolicy policy = imManager.getContactPolicy();
                                if (policy != null) {
                                    policy.addToBlacklist(peerFid);
                                    policy.setStrategy(com.fc.freer.im.ContactPolicy.Strategy.CONTACTS_ONLY);
                                }
                                imManager.purgeQuarantinedMessages(peerFid);
                                ToastUtils.showInfo(HomeActivity.this, getString(R.string.all_strangers_blocked));
                            }
                        });
            });
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error registering stranger peer callback: %s", e.getMessage());
        }
    }

    private void unregisterStrangerPeerCallback() {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;
            ImManager imManager = setting.getImManager();
            if (imManager != null) {
                imManager.setStrangerPeerCallback(null);
                imManager.setChannelSetupCallback(null);
                imManager.removeListener(teamNotificationListener);

                PendingIssueManager pendingMgr = FidManager.getInstance().getPendingIssueManager();
                if (pendingMgr != null) {
                    pendingMgr.setCountChangeListener(null);
                }
            }

            FidManager fidMgr = FidManager.getInstance();
            if (fidMgr != null) {
                CashManager cashMgr = fidMgr.getCashManager();
                if (cashMgr != null) {
                    cashMgr.setNewCashListener(null);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error unregistering stranger peer callback: %s", e.getMessage());
        }
    }

} 