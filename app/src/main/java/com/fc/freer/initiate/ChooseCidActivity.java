package com.fc.freer.initiate;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.home.BaseCryptoActivity;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.utils.KeyCardManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChooseCidActivity extends BaseCryptoActivity {
    private static final String TAG = "ChooseCidActivity";
    
    private LinearLayout cidListContainer;
    private Button cancelButton;
    private Button createButton;
    private ChooseCidPopupMenuHelper popupMenuHelper;
    private Configure configure;
    private KeyInfo selectedKeyInfo;
    private List<KeyInfo> keyInfoList;
    private KeyCardManager keyCardManager;
    private View selectedCardView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get configure from ConfigureManager
        configure = ConfigureManager.getInstance().getConfigure();
        if (configure == null) {
            Toast.makeText(this, "Configuration not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Initialize PopupMenuHelper
        popupMenuHelper = new ChooseCidPopupMenuHelper(this);
        
        // Initialize KeyCardManager with clickToReturn mode
        keyCardManager = new KeyCardManager(this, cidListContainer, null, true, null);
        keyCardManager.setOnKeyClickedListener(this::onKeySelected);
        
        // Load keyInfo list from configure
        loadKeyInfoList();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_choose_cid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.choose_cid);
    }

    @Override
    protected void initializeViews() {
        cidListContainer = findViewById(R.id.cid_list_container);
        cancelButton = findViewById(R.id.cancel_button);
        createButton = findViewById(R.id.create_button);
    }

    @Override
    protected void setupButtons() {
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        createButton.setOnClickListener(v -> {
            // Show the same menu as MyKeysActivity's create button
            popupMenuHelper.showCreateKeyMenu(createButton);
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // This activity doesn't use QR scanning
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
        keyCardManager.clearAll();
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
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    private void onKeySelected(KeyInfo keyInfo) {
        selectedKeyInfo = keyInfo;
        
        // Update card selection highlighting
        updateCardSelection(keyInfo);
        
        // Return the selected keyInfo as JSON string
        Intent resultIntent = new Intent();
        resultIntent.putExtra("selected_key_info_json", selectedKeyInfo.toJson());
        setResult(RESULT_OK, resultIntent);
        finish();
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
            // Handle the created keyInfo
            if (data != null && data.hasExtra("key_info")) {
                String keyInfoJson = data.getStringExtra("key_info");
                if (keyInfoJson != null) {
                    try {
                        KeyInfo newKeyInfo = KeyInfo.fromJson(keyInfoJson,KeyInfo.class);
                        if (newKeyInfo != null) {
                            // The key has already been saved to configure by the creation activity
                            // Just refresh the list and select the new key
                            loadKeyInfoList();
                            selectNewlyCreatedKey(newKeyInfo);
                            
                            Toast.makeText(this, "Key created successfully", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error parsing keyInfo from JSON: " + e.getMessage());
                        Toast.makeText(this, "Error parsing created key", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                // If no key_info in data, the key was already saved to configure
                // Just refresh the list to show the new key
                loadKeyInfoList();
                Toast.makeText(this, "Key created successfully", Toast.LENGTH_SHORT).show();
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
} 