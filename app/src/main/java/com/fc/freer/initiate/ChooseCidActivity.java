package com.fc.freer.initiate;

import static com.fc.freer.initiate.CreatePasswordActivity.FROM_NEW_PASSWORD;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChooseCidActivity extends AppCompatActivity {
    private static final String TAG = "ChooseCidActivity";
    public static final String SELECTED_KEY_INFO_JSON = "selected_key_info_json";

    private LinearLayout cidListContainer;
    private Button cancelButton;
    private Button createButton;
    private ChooseCidPopupMenuHelper popupMenuHelper;
    private Configure configure;
    private KeyInfo selectedKeyInfo;
    private List<KeyInfo> keyInfoList;
    private KeyCardContainer keyCardContainer;
    private View selectedCardView;
    private WaitingDialog waitingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_choose_cid);

        // Set up toolbar
        ToolbarUtils.setupToolbar(this, getString(R.string.choose_cid));

        // Set up back button handling
        setupBackButton();

        // Get configure from ConfigureManager
        configure = ConfigureManager.getInstance().getConfigure();
        if (configure == null) {
            ToastUtils.makeText(this, getString(R.string.error_configuration_not_found));
            finish();
            return;
        }

        // Initialize views
        initializeViews();

        // Initialize PopupMenuHelper
        popupMenuHelper = new ChooseCidPopupMenuHelper(this);

        // Initialize KeyCardContainer with CHOOSE_ONE_RETURN mode
        keyCardContainer = new KeyCardContainer(this, cidListContainer, ChooseMode.CHOOSE_ONE_RETURN);
        keyCardContainer.setOnKeyClickedListener(this::onKeySelected);

        // Setup buttons
        setupButtons();

        // Load keyInfo list from configure
        loadKeyInfoList();
    }

    private void initializeViews() {
        cidListContainer = findViewById(R.id.cid_list_container);
        cancelButton = findViewById(R.id.cancel_button);
        createButton = findViewById(R.id.create_button);
    }

    private void setupButtons() {
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        createButton.setOnClickListener(v -> {
            // Show the same menu as MyKeysActivity's create button
            popupMenuHelper.showCreateKeyMenu(createButton);
        });
    }

    private void setupBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
    }

    private void loadKeyInfoList() {
        Map<String, KeyInfo> mainCidInfoMap = configure.getMainCidInfoMap();
        if (mainCidInfoMap == null || mainCidInfoMap.isEmpty()) {
            // No existing keys, show create button only
            createButton.setEnabled(true);
            keyInfoList = new ArrayList<>();
            displayKeyInfoList();
            return;
        }

        keyInfoList = new ArrayList<>(mainCidInfoMap.values());
        
        // Preload avatars for all keys in background
        preloadAvatars();
        
        displayKeyInfoList();
    }
    
    /**
     * Preload avatars for all keys in background to improve performance
     */
    private void preloadAvatars() {
        if (keyInfoList != null && !keyInfoList.isEmpty()) {
            String[] fids = keyInfoList.stream()
                .map(KeyInfo::getId)
                .toArray(String[]::new);
            
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            avatarManager.preloadAvatars(fids);
            
            TimberLogger.d(TAG, "Preloading avatars for %d keys", fids.length);
        }
    }

    private void displayKeyInfoList() {
        keyCardContainer.clearAll();
        selectedCardView = null;
        
        if (keyInfoList.isEmpty()) {
            // Show "No CID yet, please create" message
            TextView emptyMessageView = new TextView(this);
            emptyMessageView.setText(getString(R.string.no_id_please_create));
            emptyMessageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            emptyMessageView.setPadding(20, 40, 20, 40);
            emptyMessageView.setTextSize(16);
            cidListContainer.addView(emptyMessageView);
            return;
        }
        
        for (KeyInfo keyInfo : keyInfoList) {
            keyCardContainer.addKeyCard(keyInfo);
        }
    }

    private void onKeySelected(KeyInfo keyInfo) {
        selectedKeyInfo = keyInfo;

        // Update card selection highlighting
        updateCardSelection(keyInfo);

        // Show waiting dialog
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        }
        waitingDialog.show();

        // Check if this is from new password creation during timeout
        boolean fromNewPassword = getIntent().getBooleanExtra(FROM_NEW_PASSWORD, false);

        if (fromNewPassword) {
            // Create or load Setting for the selected CID
            SettingManager settingManager = SettingManager.getInstance();
            com.fc.freer.model.Setting setting = settingManager.getOrCreateSetting(this, selectedKeyInfo);

            if (setting != null) {
                settingManager.setCurrentSetting(setting);
                TimberLogger.d(TAG, "Setting created and set for CID: %s", selectedKeyInfo.getId());

                // Launch HomeActivity with flags to clear the entire stack
                Intent homeIntent = new Intent(this, com.fc.freer.home.HomeActivity.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(homeIntent);
                finish();
            } else {
                TimberLogger.e(TAG, "Failed to create setting for selected CID");
                ToastUtils.makeText(this, getString(R.string.error_creating_setting));
            }
        } else {
            // Normal flow: return the selected keyInfo as JSON string
            Intent resultIntent = new Intent();
            resultIntent.putExtra(SELECTED_KEY_INFO_JSON, selectedKeyInfo.toJson());
            setResult(RESULT_OK, resultIntent);
            finish();
        }
    }

    // Method to get the selected keyInfo
    public KeyInfo getSelectedKeyInfo() {
        return selectedKeyInfo;
    }
    
    /**
     * Selects the newly created key and highlights it
     * @param newKeyInfo The newly created key info
     */
    private void selectNewlyCreatedKey(KeyInfo newKeyInfo) {
        onKeySelected(newKeyInfo);
    }

    public static final int REQUEST_CODE_CREATE_KEY = 1001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_CREATE_KEY && resultCode == RESULT_OK) {
            // Reload the configure to ensure we have the latest data
            configure = ConfigureManager.getInstance().getConfigure();

            // Handle the created/imported keyInfo
            if (data != null && data.hasExtra("key_info")) {
                String keyInfoJson = data.getStringExtra("key_info");
                if (keyInfoJson != null) {
                    try {
                        KeyInfo newKeyInfo = KeyInfo.fromJson(keyInfoJson,KeyInfo.class);
                        if (newKeyInfo != null) {
                            // The key has already been saved to configure by the creation/import activity
                            // Just refresh the list and select the new key
//                            loadKeyInfoList();
                            selectNewlyCreatedKey(newKeyInfo);
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error parsing keyInfo from JSON: " + e.getMessage());
                        ToastUtils.makeText(this, getString(R.string.error_parsing_key));
                    }
                }
            } else {
                // If no key_info in data, the key(s) were already saved to configure
                // Just refresh the list to show the new key(s)
                loadKeyInfoList();
            }
        }
    }
    
    private void updateCardSelection(KeyInfo selectedKeyInfo) {
        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.key_card_background);
        }

        // Find and highlight the selected card
        for (int i = 0; i < cidListContainer.getChildCount(); i++) {
            View cardView = cidListContainer.getChildAt(i);
            TextView keyIdView = cardView.findViewById(R.id.key_id);
            if (keyIdView != null && selectedKeyInfo.getId().equals(keyIdView.getText().toString())) {
                cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;
                break;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Dismiss waiting dialog to prevent window leak
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
            waitingDialog = null;
        }
    }
} 