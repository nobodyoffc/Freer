package com.fc.freer.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.ui.StringInputDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.fc.freer.manager.AvatarManager;
import com.fc.freer.model.Configure;

import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FcManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.MultisignManager;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.utils.IconCreator;
import com.fc.freer.ui.PopupMenuHelper;
import com.fc.freer.ui.PersonPopupMenuHelper;
import com.fc.freer.ui.RemindDialog;


public class HomeActivity extends AppCompatActivity {
    private static final String TAG = "HomeActivity";
    private PopupMenuHelper popupMenuHelper;
    private PersonPopupMenuHelper personPopupMenuHelper;
    private AddPrikeyPopupMenuHelper addPrikeyPopupMenuHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
                    DatabaseManager.getInstance(this);
                    TimberLogger.d(TAG, "Set current password name to: " + passwordName);
                }
            } else {
                TimberLogger.w(TAG, "No Configure object available in ConfigureManager");
            }
            
            // Set the content view
            setContentView(R.layout.activity_home);
            TimberLogger.d(TAG, "Content view set successfully");

            // Set up click listener for Safe text
            TextView safeTextView = findViewById(R.id.freer_text);
            if (safeTextView != null) {
                safeTextView.setOnClickListener(v -> {
                    RemindDialog dialog = new RemindDialog(this, getString(R.string.safe_v_by_no1_nrc7));
                    dialog.show();
                });
            }

            // Generate icons for all menu items
            generateMenuIcons();
            TimberLogger.d(TAG, "Menu icons generated successfully");

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

        } catch (Exception ex) {
            // Log any exceptions that occur during initialization
            TimberLogger.e(TAG, "Error in HomeActivity onCreate: " + ex.getMessage(), ex);
            Toast.makeText(this, getString(R.string.error_initializing_home, ex.getMessage()), FreerApplication.TOAST_LASTING).show();
        }
    }

    private void generateMenuIcons() {
        try {
            // Generate icons for all menu items except settings
            generateIconForMenuItem(R.id.menu_list, getString(R.string.menu_list));
            generateIconForMenuItem(R.id.menu_totp, getString(R.string.menu_totp));
            generateIconForMenuItem(R.id.menu_test, getString(R.string.menu_test));
//            generateIconForMenuItem(R.id.menu_decode, getString(R.string.menu_decode));
            generateIconForMenuItem(R.id.menu_multisign, getString(R.string.menu_multi_sign));
            generateIconForMenuItem(R.id.menu_sign_tx, getString(R.string.menu_sign_tx));
            generateIconForMenuItem(R.id.menu_convert_new, getString(R.string.menu_convert));
            generateIconForMenuItem(R.id.menu_tools, getString(R.string.menu_tools));
            generateIconForMenuItem(R.id.menu_secrets, getString(R.string.menu_secrets));
            generateIconForMenuItem(R.id.menu_cash, getString(R.string.cash));
            // Skip settings as it uses a custom gear icon
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error generating menu icons: " + ex.getMessage(), ex);
        }
    }

    private void generateIconForMenuItem(int menuItemId, String menuText) {
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
                // Generate icon with the menu text
                Bitmap iconBitmap = IconCreator.createSquareIcon(menuText, 82, 2, getColor(R.color.main_background), getColor(R.color.colorAccent));
                iconView.setImageBitmap(iconBitmap);
            } else {
                TimberLogger.e(TAG, "ImageView not found in menu item: " + menuText);
            }
        } else {
            TimberLogger.e(TAG, "Menu item not found: " + menuText);
        }
    }

    private void setupMenuClickListeners() {
        try {
            // List
            View listView = findViewById(R.id.menu_list);
            if (listView != null) {
                listView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, FidListActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_list view not found");
            }


            // Test
            View testView = findViewById(R.id.menu_test);
            if (testView != null) {
                testView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, TestActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_test view not found");
            }

            // Secrets
            View secretsView = findViewById(R.id.menu_secrets);
            if (secretsView != null) {
                secretsView.setOnClickListener(v -> {
                    // Launch SecretsActivity
                    Intent intent = new Intent(HomeActivity.this, SecretActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_secrets view not found");
            }


            // Sign TX
            View signTxView = findViewById(R.id.menu_sign_tx);
            if (signTxView != null) {
                signTxView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, CreateTxActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_sign_tx view not found");
            }

            // Multisign
            View multisignView = findViewById(R.id.menu_multisign);
            if (multisignView != null) {
                multisignView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, MultisignActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_multisign view not found");
            }



            // TOTP
            View totpView = findViewById(R.id.menu_totp);
            if (totpView != null) {
                totpView.setOnClickListener(v -> {
                    Intent intent = new Intent(HomeActivity.this, TotpActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_totp view not found");
            }


            // Cash
            View cashView = findViewById(R.id.menu_cash);
            if (cashView != null) {
                cashView.setOnClickListener(v -> {
                    // Launch CashActivity
                    Intent intent = new Intent(HomeActivity.this, CashActivity.class);
                    startActivity(intent);
                });
            } else {
                TimberLogger.e(TAG, "menu_cash view not found");
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
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error in setupMenuClickListeners: " + ex.getMessage(), ex);
            Toast.makeText(this, getString(R.string.error_setting_up_menu, ex.getMessage()), FreerApplication.TOAST_LASTING).show();
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
            String mainFid = fidManager.getMainFid();
            
            // Find UI elements
            ImageView avatarImageView = findViewById(R.id.fidAvatar);
            TextView nameTextView = findViewById(R.id.fidName);
            TextView labelTextView = findViewById(R.id.fidLabel);
            ImageView editIconView = findViewById(R.id.fidEditIcon);
            TextView cashTextView = findViewById(R.id.fidCash);
            TextView balanceTextView = findViewById(R.id.fidBalance);
            TextView cdTextView = findViewById(R.id.fidCd);
            ImageView noPrikeyIconView = findViewById(R.id.fidNoPrikeyIcon);

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

            // Show/hide no_prikey icon or people icon based on FID type and prikeyCipher
            if (noPrikeyIconView != null) {
                String prikeyCipher = liveKeyInfo.getPrikeyCipher();
                if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                    // Check if liveFid is a multisign FID (starts with '3')
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
            Toast.makeText(this, "Error opening label editor", Toast.LENGTH_SHORT).show();
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
                
                Toast.makeText(this, "Label updated successfully", Toast.LENGTH_SHORT).show();
            } else {
                TimberLogger.w(TAG, "Failed to save updated label for FID: %s", fid);
                Toast.makeText(this, "Failed to save label", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating label for FID %s: %s", fid, e.getMessage(), e);
            Toast.makeText(this, "Error updating label: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(this, R.string.copied_to_clipboard+": " + idToCopy, FreerApplication.TOAST_LASTING).show();
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
                        Toast.makeText(this, "No active FID available", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(this, "No active FID available", Toast.LENGTH_SHORT).show();
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

            // 3. Click no_prikey/people icon to add private key or open multisign detail
            ImageView noPrikeyIconView = findViewById(R.id.fidNoPrikeyIcon);
            if (noPrikeyIconView != null) {
                noPrikeyIconView.setOnClickListener(v -> {
                    FidManager currentFidManager = FidManager.getInstance();
                    if (currentFidManager != null) {
                        String currentLiveFid = currentFidManager.getLiveFid();
                        
                        // Check if this is a multisign FID (starts with '3')
                        if (currentLiveFid != null && currentLiveFid.startsWith("3")) {
                            TimberLogger.d(TAG, "People icon clicked for multisign FID: %s", currentLiveFid);
                            // Get multisign from MultisignManager and open MultisignDetailActivity
                            com.fc.fc_ajdk.data.fchData.Multisign multisign = currentFidManager.getLiveKeyInfo().getMultisign();
                            
                            if (multisign != null) {
                                Intent intent = new Intent(this, MultisignDetailActivity.class);
                                intent.putExtra("multisign", multisign.toJson());
                                startActivity(intent);
                            } else {
                                Toast.makeText(this, "Multisign data not found for FID: " + currentLiveFid, Toast.LENGTH_SHORT).show();
                                TimberLogger.w(TAG, "Multisign not found for FID: %s", currentLiveFid);
                            }
                        } else {
                            TimberLogger.d(TAG, "No prikey icon clicked!");
                            addPrikeyPopupMenuHelper.showAddPrikeyMenu(noPrikeyIconView);
                        }
                    }
                });
            }

            // 4. Click any blank place of liveFidCard to open DetailActivity
            View liveFidCard = findViewById(R.id.fidCard);
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
                            Toast.makeText(this, R.string.error_opening_detail + ": Live KeyInfo not available", FreerApplication.TOAST_LASTING).show();
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error opening DetailActivity for live KeyInfo: " + e.getMessage(), e);
                        Toast.makeText(this, R.string.error_opening_detail+": " + e.getMessage(), FreerApplication.TOAST_LASTING).show();
                    }
                });
            }

            TimberLogger.d(TAG, "Live FID card click listeners setup completed");
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error setting up live FID card click listeners: " + ex.getMessage(), ex);
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
            return String.format("%.1fb", number / 1000000000.0);
        } else if (number >= 1000000) {
            return String.format("%.1fm", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fk", number / 1000.0);
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
                runOnUiThread(() -> Toast.makeText(this, "Failed to initialize application modules: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
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
        // Check network connectivity before initializing API services
        boolean isNetworkAvailable = NetworkUtils.isNetworkAvailable(this);
        if (!isNetworkAvailable) {
            TimberLogger.w(TAG, "Network is not available, skipping API initialization");
            runOnUiThread(() -> Toast.makeText(this, getString(R.string.network_unavailable_continuing_offline), Toast.LENGTH_LONG).show());
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

        initManagers(setting, liveFid);

        refreshLiveFidInfoFromApi();

    }

    private void initManagers(Setting setting, String fid) {
        if (fid == null) fid = setting.getFid();
        
        TimberLogger.d(TAG, "Initializing managers for FID: %s", fid);
        
        FidManager fidManager = FidManager.getInstance();
        
        if (setting.getManagers() != null) {
            for (FcManager.ManagerType managerType : setting.getManagers()) {
                switch (managerType) {
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
            fidManager.refreshLiveFidCidInfoAsync(this, this::refreshLiveFidCard);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        TimberLogger.d(TAG, "=== HOME ACTIVITY onActivityResult ===");
        TimberLogger.d(TAG, "requestCode: %d, resultCode: %d", requestCode, resultCode);
        
        if (resultCode == RESULT_OK) {
            // Give a small delay to ensure FID switching is complete
            new android.os.Handler().postDelayed(() -> {
                FidManager fidManager = FidManager.getInstance();
                if (fidManager != null) {
                    String currentLiveFid = fidManager.getLiveFid();
                    KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();
                    
                    TimberLogger.d(TAG, "=== HOMEACTIVITY REFRESH START ===");
                    TimberLogger.d(TAG, "Current liveFid in FidManager: %s", currentLiveFid);
                    TimberLogger.d(TAG, "Current liveKeyInfo ID: %s", currentLiveKeyInfo != null ? currentLiveKeyInfo.getId() : "null");
                    TimberLogger.d(TAG, "Current liveKeyInfo CID: %s", currentLiveKeyInfo != null ? currentLiveKeyInfo.getCid() : "null");
                    
                    // Refresh the live FID card with current FID data
                    refreshLiveFidCard();
                    
                    // Also refresh API data in background and update UI again
                    refreshLiveFidInfoFromApi();
                    
                    TimberLogger.d(TAG, "=== HOMEACTIVITY REFRESH COMPLETED ===");
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
            String mainFid = fidManager.getMainFid();
            
            // Find UI elements and update with fresh data
            ImageView avatarImageView = findViewById(R.id.fidAvatar);
            TextView nameTextView = findViewById(R.id.fidName);
            TextView labelTextView = findViewById(R.id.fidLabel);
            ImageView editIconView = findViewById(R.id.fidEditIcon);
            TextView cashTextView = findViewById(R.id.fidCash);
            TextView balanceTextView = findViewById(R.id.fidBalance);
            TextView cdTextView = findViewById(R.id.fidCd);
            ImageView noPrikeyIconView = findViewById(R.id.fidNoPrikeyIcon);

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
                    // Check if liveFid is a multisign FID (starts with '3')
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

            TimberLogger.d(TAG, "Live FID card refreshed with API data");
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error refreshing live FID card: " + ex.getMessage(), ex);
        }
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
        
        TimberLogger.d(TAG, "HomeActivity onDestroy completed");
    }

} 