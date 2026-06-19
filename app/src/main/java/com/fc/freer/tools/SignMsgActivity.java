package com.fc.freer.tools;

import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.fc.freer.BaseCryptoActivity;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fcData.Signature;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.SecurePrikeyManager;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.ImageButton;

public class SignMsgActivity extends BaseCryptoActivity {
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_KEY_REQUEST_CODE = 1002;

    private TextInputEditText resultText;
    private TextInputEditText textInput;
    private TextInputEditText keyInput;
    private RadioGroup optionContainer;
    private RadioButton ecdsaOption;
    private RadioButton schnorrOption;
    private RadioButton symkeyOption;
    private ImageButton clearButton;
    private ImageButton copyButton;
    private ImageButton signButton;

//    private IoIconsView textIcons;
//    private IoIconsView keyIcons;
//    private LinearLayout buttonContainer;
//    private LocalDB<KeyInfo> keyInfoLocalDB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }


    @Override
    protected void initializeViews() {
        resultText = findViewById(R.id.resultView).findViewById(R.id.textBoxWithMakeQrLayout);
        resultText.setHint(R.string.signature);
        textInput = findViewById(R.id.textView).findViewById(R.id.textInput);
        textInput.setHint(R.string.input_text_to_be_signed);
        keyInput = findViewById(R.id.keyView).findViewById(R.id.keyInput);
        keyInput.setHint(R.string.input_the_key);

//        textIcons = findViewById(R.id.textView).findViewById(R.id.textIcons);
//        keyIcons = findViewById(R.id.keyView).findViewById(R.id.keyIcons);

        optionContainer = findViewById(R.id.optionContainer);
        ecdsaOption = findViewById(R.id.ecdsaOption);
        schnorrOption = findViewById(R.id.schnorrOption);
        symkeyOption = findViewById(R.id.symKeyOption);

        clearButton = findViewById(R.id.clearButton);
        copyButton = findViewById(R.id.copyButton);
        signButton = findViewById(R.id.signButton);

//        buttonContainer = findViewById(R.id.buttonContainer);

        // Setup radio buttons
        setupRadioButtons();

        // Setup buttons
        setupButtons();

        // Setup result icons
        setupResultIcons(R.id.resultView, R.id.makeQrIcon, () -> handleQrGeneration(resultText.getText()==null? null:resultText.getText().toString()));

        // Setup text icons
        setupTextIcons(R.id.textView, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);

        // Setup key icons
        setupTextIcons(R.id.keyView, R.id.scanIcon, QR_SCAN_KEY_REQUEST_CODE);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_sign_msg;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.sign);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TEXT_REQUEST_CODE) {
            textInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_KEY_REQUEST_CODE) {
            keyInput.setText(qrContent);
        }
    }

    private void setupRadioButtons() {
        optionContainer.setOnCheckedChangeListener((group, checkedId) -> {
            // Get the parent view that contains the TextInputLayout
            if (checkedId == R.id.symKeyOption) {
                // Show key input for symmetric key mode
                findViewById(R.id.keyView).setVisibility(android.view.View.VISIBLE);
                keyInput.setHint(getString(R.string.input_the_symkey));
            } else {
                // Hide key input for ECDSA/Schnorr modes - will use live FID's private key
                findViewById(R.id.keyView).setVisibility(android.view.View.GONE);
                keyInput.setHint(getString(R.string.input_the_prikey));
            }
        });

        // Set initial state - hide key input by default (ECDSA is default)
        findViewById(R.id.keyView).setVisibility(android.view.View.GONE);
    }

    protected void setupButtons() {
        // Initially disable copy button
        copyButton.setEnabled(false);
        
        // Set click listeners
        setupButton(clearButton, v -> clearInputs());
        setupButton(copyButton, v -> copyToClipboard());
        setupButton(signButton, v -> signMessage());
    }

    private void clearInputs() {
        resultText.setText("");
        clearInput(textInput);
        clearInput(keyInput);
        copyButton.setEnabled(false);
        optionContainer.check(R.id.ecdsaOption);
    }

    private void copyToClipboard() {
        String result = resultText.getText().toString();
        if (!result.isEmpty()) {
            copyToClipboard(result, "signature");
        }
    }

    private void signMessage() {
        String message = textInput.getText().toString();

        if (message.isEmpty()) {
            showToast(getString(R.string.please_input_message));
            return;
        }

        Signature signature = new Signature();
        signature.setMsg(message);

        byte[] keyBytes;

        try {
            if (symkeyOption.isChecked()) {
                // For symmetric key mode, use user input
                String key = keyInput.getText().toString();
                if (key.isEmpty()) {
                    showToast(getString(R.string.please_input_key));
                    return;
                }
                keyBytes = Hex.fromHex(key);
                signature.setAlg(AlgorithmId.FC_Sha256SymSignMsg_No1_NrC7);
            } else {
                // For ECDSA/Schnorr modes, use live FID's private key
                FidManager fidManager = FidManager.getInstance();
                KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
                if (liveKeyInfo == null) {
                    showToast(getString(R.string.no_live_fid_available));
                    return;
                }

                keyBytes = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
                if (keyBytes == null) {
                    showToast(getString(R.string.failed_to_get_prikey));
                    return;
                }

                // Set the appropriate algorithm based on selection
                if (ecdsaOption.isChecked()) {
                    signature.setAlg(AlgorithmId.BTC_EcdsaSignMsg_No1_NrC7);
                } else if (schnorrOption.isChecked()) {
                    signature.setAlg(AlgorithmId.FC_SchnorrSignMsg_No1_NrC7);
                }
            }

            if(keyBytes == null) {
                showToast(getString(R.string.wrong_key));
                return;
            }
        } catch (Exception e) {
            showToast(getString(R.string.wrong_key));
            return;
        }

        // Set the key for signing
        signature.setKey(keyBytes);

        try {
            signature.sign();
            String result = signature.toNiceJson();
            resultText.setText(result);
            copyButton.setEnabled(true);
        } catch (Exception e) {
            showToast(getString(R.string.failed_to_sign_words));
        }
    }

} 