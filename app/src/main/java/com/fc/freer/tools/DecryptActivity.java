package com.fc.freer.tools;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.ToastUtils;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.EncryptType;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.SettingManager;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.ImageButton;

public class DecryptActivity extends BaseCryptoActivity {
    private static final String TAG = "DecryptActivity";
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_KEY_REQUEST_CODE = 1002;

    private TextInputEditText cipherEditText;
    private TextInputEditText keyEditText;
    private ImageButton clearButton;
    private ImageButton copyDataButton;
    private ImageButton decryptButton;
    private LinearLayout buttonContainer;
    private CheckBox toHexCheckBox;
    private LocalDB<KeyInfo> keyInfoLocalDB;
    private IoIconsView keyIcons;
    private boolean isUsingDefaultKey = true;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        
        // Set up buttons
        setupButtons();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_decrypt;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.decrypt);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TEXT_REQUEST_CODE) {
            cipherEditText.setText(qrContent);
        } else if (requestCode == QR_SCAN_KEY_REQUEST_CODE) {
            keyEditText.setText(qrContent);
        }
    }

    @Override
    protected void initializeViews() {
        TimberLogger.i(TAG, "Initializing views in DecryptActivity");
        cipherEditText = findViewById(R.id.cipherView).findViewById(R.id.textInput);
        cipherEditText.setHint(R.string.input_the_cipher);
        keyEditText = findViewById(R.id.keyView).findViewById(R.id.keyInput);
        keyEditText.setHint(R.string.password_or_symkey);
        clearButton = findViewById(R.id.clearButton);
        copyDataButton = findViewById(R.id.copyDataButton);
        copyDataButton.setEnabled(false);
        decryptButton = findViewById(R.id.decryptButton);
        resultTextView = findViewById(R.id.resultView).findViewById(R.id.textBoxWithMakeQrLayout);
        resultTextView.setHint(R.string.result);
        buttonContainer = findViewById(R.id.buttonContainer);
        toHexCheckBox = findViewById(R.id.toHexCheckBox);

        // Setup icons using shared methods
        setupTextIcons(R.id.cipherView, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);
        setupResultIcons(R.id.resultView, R.id.makeQrIcon, () -> handleQrGeneration(resultTextView.getText().toString()));

        // Setup key input icons without people icon
        keyIcons = findViewById(R.id.keyView).findViewById(R.id.peopleAndScanIcons);
        keyIcons.init(this, false, false, true, true,false); // showMakeQr=false, showPeople=false, showScan=true, showFile=false
        keyIcons.setOnScanClickListener(() -> startQrScan(QR_SCAN_KEY_REQUEST_CODE));

        // Set current user's key automatically
        setCurrentUserKey();

        // Add text watcher to detect user input
        setupKeyInputWatcher();
    }

    @Override
    protected void setupButtons() {
        // Set click listeners using shared method
        setupButton(clearButton, v -> clearInputs());
        setupButton(copyDataButton, v -> copyConvertedDataToClipboard());
        setupButton(decryptButton, v -> decrypt());
    }

    private void setCurrentUserKey() {
        KeyInfo currentKeyInfo = SettingManager.getInstance().getCurrentKeyInfo();
        if (currentKeyInfo != null && currentKeyInfo.getPrikeyCipher() != null) {
            // Set the key ID as hint text
            String text = getString(R.string.your_prikey_or_input_password_or_symkey);
            keyEditText.setText(text);
            keyEditText.setTextColor(getResources().getColor(R.color.hint, getTheme()));

            // Store the actual private key cipher for decryption
            String priKeyCipher = currentKeyInfo.getPrikeyCipher();
            keyEditText.setTag(priKeyCipher);
            isUsingDefaultKey = true;
        }
    }

    private void setupKeyInputWatcher() {
        keyEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // If user is typing, switch to password/symkey mode
                if (count > 0 && isUsingDefaultKey) {
                    isUsingDefaultKey = false;
                    keyEditText.setTextColor(getResources().getColor(R.color.text, getTheme()));
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        keyEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && isUsingDefaultKey) {
                keyEditText.setText("");
                isUsingDefaultKey = false;
                keyEditText.setTextColor(getResources().getColor(R.color.text, getTheme()));
            }
        });
    }

    private void clearInputs() {
        cipherEditText.setText("");
        resultTextView.setText("");
        copyDataButton.setEnabled(false);
        toHexCheckBox.setChecked(false);
        // Reset current user's key
        setCurrentUserKey();
        isUsingDefaultKey = true; // Ensure the flag is reset
    }

    private void decrypt() {
        // Always clear the result first
        resultTextView.setText("");
        copyDataButton.setEnabled(false);
        if( cipherEditText.getText()==null){
            ToastUtils.makeText(this, getString(R.string.please_enter_cipher));
            return;
        }
        String cipher = cipherEditText.getText().toString();
        String key = isUsingDefaultKey ?
                (String) keyEditText.getTag()
                : keyEditText.getText()!=null?
                        keyEditText.getText().toString()
                        : null;
        if(key==null){
            ToastUtils.makeText(this, getString(R.string.please_enter_key));
            return;
        }
        // Validate inputs
        if (TextUtils.isEmpty(cipher)) {
            showToast(getString(R.string.please_enter_cipher));
            return;
        }

        if (TextUtils.isEmpty(key)) {
            showToast(getString(R.string.please_enter_key));
            return;
        }

        try {
            // Parse and validate cipher data
            CryptoDataByte cryptoDataByteCipher = parseCipherData(cipher);
            if (cryptoDataByteCipher == null) {
                showToast(getString(R.string.not_a_valid_cipher));
                return;
            }
            
            // Get cipher type and parse key data
            EncryptType cipherType = cryptoDataByteCipher.getType();
            byte[] keyBytes = parseKeyData(key, cipherType);
            
            // Validate key for asymmetric encryption
            if (keyBytes == null && 
                (cipherType == EncryptType.AsyOneWay || cipherType == EncryptType.AsyTwoWay)) {
                showToast(getString(R.string.not_a_valid_key));
                return;
            }

            // Perform decryption
            decryptData(cryptoDataByteCipher, key, keyBytes, cipherType);

            // Check decryption result
            if (cryptoDataByteCipher.getCode() != 0) {
                showToast(getString(R.string.failed_to_decrypt_with_message, cryptoDataByteCipher.getMessage()));
                return;
            }

            // Show decrypted data and enable copy button
            byte[] data = cryptoDataByteCipher.getData();
            if(data==null){
                showToast(getString(R.string.got_nothing_from_cipher, cryptoDataByteCipher.getMessage()));
                return;
            }
            if (toHexCheckBox.isChecked()) {
                updateResultText(Hex.toHex(data));
            } else {
                updateResultText(new String(data));
            }
            copyDataButton.setEnabled(true);
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting: " + e.getMessage());
            showToast(getString(R.string.error_decrypting, e.getMessage()));
        }
    }
    
    private CryptoDataByte parseCipherData(String cipher) {
        if (JsonUtils.isJson(cipher)) {
            return CryptoDataByte.fromJson(cipher);
        } else if (StringUtils.isBase64(cipher)) {
            byte[] bundle = Base64.decode(cipher, Base64.DEFAULT);
            return CryptoDataByte.fromBundle(bundle);
        }
        return null;
    }
    
    private byte[] parseKeyData(String key, EncryptType cipherType) {
        if (JsonUtils.isJson(key)) {
            Configure configure = ConfigureManager.getInstance().getConfigure();
            return Decryptor.decryptPrikey(key, configure.getSymkey());
        } 
        
        // For asymmetric encryption, extract private key
        if (cipherType == EncryptType.AsyOneWay || cipherType == EncryptType.AsyTwoWay) {
            return KeyTools.getPrikey32(key);
        }
        
        // For other encryption types, return null
        return null;
    }
    
    private void decryptData(CryptoDataByte cryptoDataByteCipher, String key, byte[] keyBytes, EncryptType cipherType) {
        switch (cipherType) {
            case Password -> Decryptor.decryptByPassword(cryptoDataByteCipher, key.toCharArray());
            case Symkey -> Decryptor.decryptBySymkey(cryptoDataByteCipher, key);
            case AsyOneWay, AsyTwoWay -> Decryptor.decryptByAsyKey(cryptoDataByteCipher, keyBytes);
        }
    }

} 