package com.fc.freer.myKeys;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.home.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.utils.KeyCardManager;
import com.fc.freer.utils.ToolbarUtils;

import java.util.List;

public class RandomNewKeysActivity extends BaseCryptoActivity {
    private static final String TAG = "RandomNewKeysActivity";
    private static final int BATCH_SIZE = 10;
    
    private KeyCardManager keyCardManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        generateNewKeys();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_random_new_keys;
    }

    @Override
    protected String getActivityTitle() {
        return TAG;
    }

    @Override
    protected void initializeViews() {
        LinearLayout keyListContainer = findViewById(R.id.key_list);
        keyCardManager = new KeyCardManager(this, keyListContainer, false,List.of( KeyCardManager.createDeleteMenuItem(this)));
    }

    private void generateNewKeys() {
        byte[] symKey = ConfigureManager.getInstance().getSymkey();
        for (int i = 0; i < BATCH_SIZE; i++) {
            KeyInfo keyInfo = KeyInfo.createNew(symKey);
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    @Override
    protected void setupButtons() {
        Button clearButton = findViewById(R.id.clear_button);
        Button moreButton = findViewById(R.id.more_button);
        Button saveButton = findViewById(R.id.save_button);

        clearButton.setOnClickListener(v -> keyCardManager.clearAll());

        moreButton.setOnClickListener(v -> generateNewKeys());

        saveButton.setOnClickListener(v -> {
            List<KeyInfo> selectedKeys = keyCardManager.getSelectedKeys();
            if (selectedKeys.isEmpty()) {
                Toast.makeText(this, "No keys selected", FreerApplication.TOAST_LASTING).show();
                return;
            }
            
            // Save all selected keys
            saveToConfigureAndFinish(selectedKeys);
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }
} 