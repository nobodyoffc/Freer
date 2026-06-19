package com.fc.freer.tools;

import static com.fc.fc_ajdk.data.fcData.AlgorithmId.FC_AesGcm256_No1_NrC7;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.contact.ChooseContactActivity;
import com.fc.freer.ui.IoIconsView;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.ImageButton;

public class EncryptActivity extends BaseCryptoActivity {
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_KEY_REQUEST_CODE = 1002;

    private TextInputEditText textInput;
    private TextInputEditText keyInput;
    private TextInputEditText resultText;
    private RadioGroup optionContainer;
    private CheckBox hexAsBytesCheckBox;
    private CryptoDataByte cryptoDataByte;
    private ImageButton copyButton;
    private ActivityResultLauncher<Intent> contactSelectionLauncher;
    private Contact selectedContact;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_encrypt;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.encrypt);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up contact selection launcher
        setupContactSelectionLauncher();

        // Set up buttons
        setupButtons();
    }

    @Override
    protected void initializeViews() {
        textInput = findViewById(R.id.textView).findViewById(R.id.textInput);
        textInput.setHint(R.string.input_the_text_to_be_encrypted);
        
        resultText = findViewById(R.id.resultView).findViewById(R.id.textBoxWithMakeQrLayout);
        resultText.setHint(R.string.cipher);
        
        keyInput = findViewById(R.id.keyView).findViewById(R.id.keyInput);
        keyInput.setHint(R.string.input_the_key);
        
        optionContainer = findViewById(R.id.optionContainer);
        hexAsBytesCheckBox = findViewById(R.id.hexAsBytesCheckBox);

        // Setup icons using shared methods
        setupTextIcons(R.id.textView, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);
        setupResultIcons(R.id.resultView, R.id.makeQrIcon, () -> handleQrGeneration(resultText.getText()==null? null:resultText.getText().toString()));

        // Setup key view with people and scan icons
        setupKeyIcons();
    }

    @Override
    protected void setupButtons() {
        ImageButton clearButton = findViewById(R.id.clearButton);
        copyButton = findViewById(R.id.copyButton);
        ImageButton encryptButton = findViewById(R.id.encryptButton);

        // Initially disable copy button
        copyButton.setEnabled(false);
        
        // Set click listeners
        setupButton(clearButton, v -> clearInputs());
        setupButton(copyButton, v -> copyToClipboard());
        setupButton(encryptButton, v -> handleEncryption());
    }

    private void clearInputs() {
        clearInput(textInput);
        clearInput(keyInput);
        resultText.setText("");
        cryptoDataByte = null;
        selectedContact = null;
        copyButton.setEnabled(false);
        hexAsBytesCheckBox.setChecked(false);
        optionContainer.check(R.id.passwordOption);

        // Re-enable key input if it was disabled for contact selection
        keyInput.setEnabled(true);
        keyInput.setTag(null);
    }

    private void copyToClipboard() {
        if (cryptoDataByte != null) {
            copyToClipboard(cryptoDataByte.toNiceJson(), "cipher");
        }
    }

    private void setupContactSelectionLauncher() {
        contactSelectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        handleContactSelectionResult(result.getData());
                    }
                }
        );
    }

    private void setupKeyIcons() {
        IoIconsView keyIcons = findViewById(R.id.keyView).findViewById(R.id.peopleAndScanIcons);
        if (keyIcons != null) {
            keyIcons.init(this, false, true, true, true, false);

            // Set up people icon click listener
            keyIcons.setOnPeopleClickListener(isSingleChoice -> launchContactSelection());

            // Set up scan icon click listener for QR scanning
            keyIcons.setOnScanClickListener(() -> launchQrScan(QR_SCAN_KEY_REQUEST_CODE));

            // Set up paste icon click listener
            keyIcons.setOnPasteClickListener(() -> pasteFromClipboard(keyInput));
        }
    }

    private void launchContactSelection() {
        Intent intent = new Intent(this, ChooseContactActivity.class);
        intent.putExtra(ChooseContactActivity.EXTRA_CHOOSE_MODE, com.fc.freer.utils.ChooseMode.CHOOSE_ONE_RETURN.name());
        contactSelectionLauncher.launch(intent);
    }

    private void launchQrScan(int requestCode) {
        Intent intent = new Intent(this, com.fc.freer.qr.QrCodeActivity.class);
        intent.putExtra("request_code", requestCode);
        qrScanLauncher.launch(intent);
    }

    private void handleContactSelectionResult(Intent data) {
        String contactJson = data.getStringExtra(ChooseContactActivity.EXTRA_SELECTED_CONTACT);
        if (contactJson != null) {
            try {
                selectedContact = Contact.fromJson(contactJson, Contact.class);
                if (selectedContact != null) {
                    // Set contact name in key input field

                    // Store the public key for encryption
                    if(selectedContact.getPubkey() == null){
                        showToast(getString(R.string.failed_to_get_pubkey_of, selectedContact.getName()));
                        return;
                    }

                    keyInput.setText(selectedContact.getName());

                    keyInput.setTag(selectedContact.getPubkey());

                    // Automatically switch to public key encryption mode
                    optionContainer.check(R.id.pubKeyOption);

                    // Disable key input since we're using the contact's pubkey
                    keyInput.setEnabled(false);

                    showToast("Selected contact: " + selectedContact.getName());
                }
            } catch (Exception e) {
                showToast("Error processing selected contact: " + e.getMessage());
            }
        }
    }

    private void handleEncryption() {
        resultText.setText("");
        copyButton.setEnabled(false);

        String text = textInput.getText() != null ? textInput.getText().toString() : "";
        String key = keyInput.getText() != null ? keyInput.getText().toString() : "";

        if (text.isEmpty() || key.isEmpty()) {
            showToast(getString(R.string.please_input_both_text_and_key));
            return;
        }
        encrypt(text, key);
    }

    private void encrypt(String text, String key) {
        resultText.setText("");
        copyButton.setEnabled(false);

        try {
            String keyType = getSelectedKeyType();
            String actualKey = getActualKey(key, keyType);
            
            if (!validateKey(actualKey, keyType)) {
                return;
            }

            cryptoDataByte = createEncryptedData(text, actualKey, keyType);
            resultText.setText(cryptoDataByte.toNiceJson());
            copyButton.setEnabled(true);
        } catch (Exception e) {
            showError("Encryption failed: " + e.getMessage());
        }
    }

    private String getSelectedKeyType() {
        int selectedId = optionContainer.getCheckedRadioButtonId();
        if (selectedId == R.id.symKeyOption) {
            return "symKey";
        } else if (selectedId == R.id.pubKeyOption) {
            return "pubKey";
        }
        return "password";
    }

    private String getActualKey(String inputKey, String keyType) {
        if (keyType.equals("pubKey") && !keyInput.isEnabled()) {
            return (String) keyInput.getTag();
        }
        return inputKey;
    }

    private boolean validateKey(String key, String keyType) {
        switch (keyType) {
            case "symKey":
                if (!Hex.isHex32(key)) {
                    showError("The symKey should be 32 bytes in hex");
                    return false;
                }
                break;
            case "pubKey":
                if (!KeyTools.isPubkey(key)) {
                    showError("It is not a public key");
                    return false;
                }
                break;
        }
        return true;
    }

    private CryptoDataByte createEncryptedData(String text, String key, String keyType) {
        byte[] dataBytes;
        if (hexAsBytesCheckBox.isChecked()) {
            dataBytes = Hex.fromHex(text);
        } else {
            dataBytes = text.getBytes();
        }
        return switch (keyType) {
            case "password" ->
                    new Encryptor(FC_AesGcm256_No1_NrC7).encryptByPassword(dataBytes, key.toCharArray());
            case "symKey" -> new Encryptor(FC_AesGcm256_No1_NrC7).encryptBySymkey(dataBytes, Hex.fromHex(key));
            case "pubKey" -> new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7)
                    .encryptByAsyOneWay(dataBytes, Hex.fromHex(key));
            default -> throw new IllegalArgumentException("Unsupported key type: " + keyType);
        };
    }

    private void showError(String message) {
        showToast(message);
        copyButton.setEnabled(false);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (qrContent != null) {
            if (requestCode == QR_SCAN_TEXT_REQUEST_CODE) {
                textInput.setText(qrContent);
            } else if (requestCode == QR_SCAN_KEY_REQUEST_CODE) {
                keyInput.setText(qrContent);
            }
        }
    }
} 