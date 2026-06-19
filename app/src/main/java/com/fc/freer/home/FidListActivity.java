package com.fc.freer.home;

import android.content.Intent;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.FreerApplication;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;

import java.util.List;

public class FidListActivity extends BaseCryptoActivity {
    private KeyCardContainer keyCardManager;
    private LinearLayout keyCardContainer;
    private ImageButton clearButton;
    private ImageButton deleteButton;
    private ImageButton addButton;
    private static final int REQUEST_ADD_FID = 1001;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_list;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.fid_list);
    }

    @Override
    protected void initializeViews() {
        keyCardContainer = findViewById(R.id.keyCardContainer);
        clearButton = findViewById(R.id.clearButton);
        deleteButton = findViewById(R.id.deleteButton);
        addButton = findViewById(R.id.addButton);

        // Initialize KeyCardContainer with CHOOSE_MULTI mode
        keyCardManager = new KeyCardContainer(this, keyCardContainer, ChooseMode.CHOOSE_MULTI);
        keyCardManager.setOnKeyListChangedListener(this::onKeyListChanged);

        // Load initial FIDs
        loadFidList();
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            FreerApplication.clearFidList();
            keyCardManager.clearAll();
            updateButtonStates();
        });

        deleteButton.setOnClickListener(v -> {
            List<KeyInfo> selectedKeys = keyCardManager.getSelectedKeys();
            for (KeyInfo keyInfo : selectedKeys) {
                FreerApplication.removeFid(keyInfo.getId());
            }
            loadFidList();
        });

        addButton.setOnClickListener(v -> {
            startActivityForResult(AddFidActivity.createIntent(this), REQUEST_ADD_FID);
        });
    }

    private void loadFidList() {
        keyCardManager.clearAll();
        List<String> fidList = FreerApplication.getFidList();
        for (String fid : fidList) {
            KeyInfo keyInfo = KeyInfo.newFromFid(fid, null);
            if (keyInfo != null) {
                keyCardManager.addKeyCard(keyInfo);
            }
        }
        updateButtonStates();
    }

    private void onKeyListChanged(List<KeyInfo> updatedKeyInfoList) {
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean hasItems = !keyCardManager.getKeyInfoList().isEmpty();
        boolean hasSelection = !keyCardManager.getSelectedKeys().isEmpty();

        clearButton.setEnabled(hasItems);
        deleteButton.setEnabled(hasSelection);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not needed for this activity
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ADD_FID) {
            loadFidList();
        }
    }
} 