package com.fc.freer.myKeys;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.app.AlertDialog;

import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.ui.DetailFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.fc.freer.utils.TextIconsUtils;
import android.widget.ImageButton;

public class CreateKeyByPrikeyActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateKeyByPrikey";
    private static final int QR_SCAN_PRIKEY_REQUEST_CODE = 1001;
    private static final int QR_SCAN_LABEL_REQUEST_CODE = 1002;
    
    private DetailFragment detailFragment;
    private TextInputEditText prikeyInput;
    private TextInputEditText labelInput;
    private ImageButton clearButton;
    private ImageButton previewButton;
    private ImageButton saveButton;
    private LinearLayout keyInfoContainer;
    private LinearLayout inputContainer;
    private LinearLayout buttonContainer;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_key_by_pub_key;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_key_by_prikey);
    }

    @Override
    protected void initializeViews() {
        keyInfoContainer = findViewById(R.id.keyInfoContainer);
        inputContainer = findViewById(R.id.inputContainer);
        buttonContainer = findViewById(R.id.buttonContainer);
        
        // Initialize input fields from included layouts
        View pubkeyView = findViewById(R.id.pubkeyView);
        View labelView = findViewById(R.id.labelView);
        
        prikeyInput = pubkeyView.findViewById(R.id.textInput);
        prikeyInput.setHint(R.string.input_the_prikey);
        labelInput = labelView.findViewById(R.id.textInput);
        labelInput.setHint(R.string.input_the_label);

        clearButton = findViewById(R.id.clearButton);
        previewButton = findViewById(R.id.previewButton);
        saveButton = findViewById(R.id.saveButton);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearInputs());
        previewButton.setOnClickListener(v -> previewKeyInfo());
        saveButton.setOnClickListener(v -> saveKeyInfo());

        // Setup scan icons using TextIconsUtils
        TextIconsUtils.setupTextIcons(this, R.id.pubkeyView, R.id.scanIcon, QR_SCAN_PRIKEY_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.labelView, R.id.scanIcon, QR_SCAN_LABEL_REQUEST_CODE);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_PRIKEY_REQUEST_CODE) {
            prikeyInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_LABEL_REQUEST_CODE) {
            labelInput.setText(qrContent);
        }
    }

    private void clearInputs() {
        prikeyInput.setText("");
        labelInput.setText("");
        clearDetailFragment();
    }

    private void clearDetailFragment() {
        if (detailFragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(detailFragment)
                    .commit();
            detailFragment = null;
        }
    }

    private void previewKeyInfo() {
        String prikey = prikeyInput.getText() != null ? prikeyInput.getText().toString() : "";
        String label = labelInput.getText() != null ? labelInput.getText().toString() : "";

        if (prikey.isEmpty()) {
            return;
        }

        byte[] prikey32 = KeyTools.getPrikey32(prikey);
        if(prikey32 ==null){
            ToastUtils.makeText(this, getString(R.string.toast_invalid_private_key));
            return;
        }

        // Create a new KeyInfo object
        byte[] symkey = ConfigureManager.getInstance().getSymkey();
        KeyInfo keyInfo = new KeyInfo(label, prikey32, symkey);

        // Note: Avatar generation will be handled by the configure-based system

        // Show the KeyInfo in the detail fragment
        showDetailFragment(keyInfo);
    }

    private void showDetailFragment(KeyInfo keyInfo) {
        // Remove existing fragment if any
        clearDetailFragment();

        // Create and show new fragment
        detailFragment = DetailFragment.newInstance(keyInfo, KeyInfo.class);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.keyInfoContainer, detailFragment)
                .commit();
    }

    private void saveKeyInfo() {
        if (detailFragment == null) {
            // If no preview was done, try to create KeyInfo from inputs
            KeyInfo keyInfo = createKeyInfoFromInputs();
            if (keyInfo == null) {
                return;
            }
            saveAndFinishWithKeyInfo(keyInfo);
        } else {
            // Use the previewed KeyInfo
            KeyInfo keyInfo = (KeyInfo) detailFragment.getCurrentEntity();
            if (keyInfo != null) {
                saveAndFinishWithKeyInfo(keyInfo);
            }
        }
    }

    private KeyInfo createKeyInfoFromInputs() {
        String prikey = prikeyInput.getText() != null ? prikeyInput.getText().toString() : "";
        String label = labelInput.getText() != null ? labelInput.getText().toString() : "";

        if (prikey.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.toast_private_key_empty));
            return null;
        }
        byte[] prikey32 = KeyTools.getPrikey32(prikey);
        if(prikey32==null){
            ToastUtils.makeText(this, getString(R.string.toast_invalid_private_key));
            return null;
        }

        return new KeyInfo(label, prikey32, ConfigureManager.getInstance().getSymkey());
    }


//    private void saveKeyInfoToDatabase(KeyInfo keyInfo) {
//        // Check if the key already exists
//        // Check if key already exists in configure
//        ConfigureManager configureManager = ConfigureManager.getInstance();
//        com.fc.freer.model.Configure configure = configureManager.getConfigure();
//        if (configure != null && configure.getMainCidInfoMap()!=null && configure.getMainCidInfoMap().containsKey(keyInfo.getId())) {
//            new AlertDialog.Builder(this)
//                .setTitle("Key Already Exists")
//                .setMessage("A key with this ID already exists. Do you want to replace it?")
//                .setPositiveButton("Replace", (dialog, which) -> {
//                    saveAndFinishWithKeyInfo(keyInfo);
//                })
//                .setNegativeButton("Cancel", null)
//                .show();
//        } else {
//            saveAndFinishWithKeyInfo(keyInfo);
//        }
//    }

} 