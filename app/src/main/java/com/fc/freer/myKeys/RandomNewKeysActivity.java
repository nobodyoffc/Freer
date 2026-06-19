package com.fc.freer.myKeys;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import android.widget.ImageButton;

import java.util.List;

public class RandomNewKeysActivity extends BaseCryptoActivity {
    private static final String TAG = "RandomNewKeysActivity";
    private static final int BATCH_SIZE = 10;
    
    private KeyCardContainer keyCardContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If the base aborted setup (session lost / process death), don't run
        // our own onCreate work: views are uninitialized and we're finishing.
        if (isSessionRedirected()) {
            return;
        }

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
        keyCardContainer = new KeyCardContainer(this, keyListContainer, ChooseMode.CHOOSE_MULTI,List.of( KeyCardContainer.createDeleteMenuItem(this)));
    }

    private void generateNewKeys() {
        byte[] symKey = ConfigureManager.getInstance().getSymkey();
        for (int i = 0; i < BATCH_SIZE; i++) {
            KeyInfo keyInfo = KeyInfo.createNew(symKey);
            keyCardContainer.addKeyCard(keyInfo);
        }
        ToastUtils.makeText(this, getString(R.string.keys_generated, BATCH_SIZE));
    }

    @Override
    protected void setupButtons() {
        ImageButton clearButton = findViewById(R.id.clear_button);
        ImageButton moreButton = findViewById(R.id.load_more_button);
        ImageButton saveButton = findViewById(R.id.save_button);

        clearButton.setOnClickListener(v -> keyCardContainer.clearAll());

        moreButton.setOnClickListener(v -> generateNewKeys());

        saveButton.setOnClickListener(v -> {
            List<KeyInfo> selectedKeys = keyCardContainer.getSelectedKeys();
            if (selectedKeys.isEmpty()) {
                ToastUtils.makeText(this, "No keys selected");
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