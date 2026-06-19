package com.fc.freer.convert;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.data.fchData.P2SH;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.QRCodeGenerator;

public class ScriptConverterActivity extends BaseCryptoActivity {
    private static final String TAG = "ScriptConverterActivity";

    protected EditText keyEditText;
    private LinearLayout buttonContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupListeners();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_script_converter;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.script_convert);
    }

    protected void initializeViews() {
        keyEditText = findViewById(R.id.keyView).findViewById(R.id.textInput);
        keyEditText.setHint(R.string.input_script);
        resultTextView = findViewById(R.id.resultView).findViewById(R.id.textBoxWithMakeQrLayout);
        resultTextView.setHint(R.string.result);

        clearButton = findViewById(R.id.clearButton);
        copyButton = findViewById(R.id.copyButton);
        convertButton = findViewById(R.id.convertButton);
        buttonContainer = findViewById(R.id.buttonContainer);

        setupIoIconsView(R.id.keyView, R.id.scanIcon, false, false, true,true ,
                false, null, null, () -> pasteFromClipboard(keyEditText), null, () -> startQrScan(0));

        setupIoIconsView(R.id.resultView, R.id.makeQrIcon, true, false, false, false,
                false, () -> {
                String content = resultTextView.getText().toString();
                if (!TextUtils.isEmpty(content)) {
                    QRCodeGenerator.generateAndShowQRCode(this, content);
                } else {
                    showToast(getString(R.string.no_content_to_generate_qr_code));
                }
            }, null, null, null, null);
    }

    private void setupListeners() {
        // Set click listeners
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });
        copyButton.setOnClickListener(v -> {
            hideKeyboard();
            copyConvertedDataToClipboard();
        });
        convertButton.setOnClickListener(v -> {
            hideKeyboard();
            convert();
        });
    }

    @Override
    protected void setupButtons() {
        setupButton(clearButton, v -> {
            hideKeyboard();
            clearInputs();
        });
        setupButton(copyButton, v -> {
            hideKeyboard();
            copyConvertedDataToClipboard();
        });
        setupButton(convertButton, v -> {
            hideKeyboard();
            convert();
        });
    }

    private void clearInputs() {
        keyEditText.setText("");
        keyEditText.setEnabled(true);
        keyEditText.setTextColor(getResources().getColor(R.color.text, getTheme()));
        keyEditText.setTag(null);
        updateResultText(null);
    }

    private void convert() {
        // Always clear the result first
        resultTextView.setText("");
        copyButton.setEnabled(false);

        String inputedText = keyEditText.getText().toString();

        if (TextUtils.isEmpty(inputedText)) {
            showToast(getString(R.string.please_input_script));
            return;
        }

        try {
            String result;
            // Check if input is hex (contains only hex characters)
            if (inputedText.matches("^[0-9a-fA-F]+$")) {
                // Convert hex to ASM
                result = P2SH.scriptHexToAsm(inputedText);
                if (result == null) {
                    showToast(getString(R.string.invalid_script_hex));
                    return;
                }
            } else {
                // Convert ASM to hex
                result = P2SH.scriptAsmToHex(inputedText);
                if (result == null) {
                    showToast(getString(R.string.invalid_script_asm));
                    return;
                }
            }

            if (result != null) {
                updateResultText(result);
                copyButton.setEnabled(true);
            } else {
                showToast(getString(R.string.invalid_script_format));
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting: " + e.getMessage());
            showToast(getString(R.string.error_converting, e.getMessage()));
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        keyEditText.setText(qrContent);
    }
}
