package com.fc.freer.tools;

import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.Signature;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.ui.IoIconsView;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.ImageButton;

public class VerifyActivity extends BaseCryptoActivity {
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_KEY_REQUEST_CODE = 1002;

    private ImageView resultIcon;
    private TextInputEditText textInput;
    private TextInputEditText keyInput;
    private ImageButton clearButton;
    private ImageButton verifyButton;
    private IoIconsView textIcons;
    private IoIconsView keyIcons;
    private LinearLayout buttonContainer;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup buttons
        setupButtons();

        // Setup text icons
        setupTextIcons(R.id.textView, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);
        setupTextIcons(R.id.keyView, R.id.scanIcon, QR_SCAN_KEY_REQUEST_CODE);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_verify;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.verify_signature);
    }

    @Override
    protected void initializeViews() {
        resultIcon = findViewById(R.id.resultIcon);
        
        // Get parent views first
        View textView = findViewById(R.id.textView);
        View keyView = findViewById(R.id.keyView);
        
        // Find child views using parent views
        textInput = textView.findViewById(R.id.textInput);
        textInput.setHint(R.string.input_the_signature);
        keyInput = keyView.findViewById(R.id.textInput);
        keyInput.setHint(R.string.only_for_sym_key_sign);

        textIcons = textView.findViewById(R.id.scanIcon);
        keyIcons = keyView.findViewById(R.id.scanIcon);
        
        clearButton = findViewById(R.id.clearButton);
        verifyButton = findViewById(R.id.verifyButton);
        buttonContainer = findViewById(R.id.buttonContainer);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TEXT_REQUEST_CODE) {
            textInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_KEY_REQUEST_CODE) {
            keyInput.setText(qrContent);
        }
    }

    @Override
    protected void setupButtons() {
        // Set click listeners
        setupButton(clearButton, v -> clearInputs());
        setupButton(verifyButton, v -> verifyMessage());
    }

    private void clearInputs() {
        clearInput(textInput);
        clearInput(keyInput);
        resultIcon.setImageResource(R.drawable.ic_verify_default);
    }

    private void verifyMessage() {
        Editable text = textInput.getText();
        if(text==null){
            showToast(getString(R.string.please_input_signature));
            return;
        }
        String signatureJson = text.toString();
        String key = keyInput.isEnabled() ?
                keyInput.getText()==null?null:keyInput.getText().toString() :
            (String) keyInput.getTag();

        if (signatureJson.isEmpty()) {
            showToast(getString(R.string.please_input_message));
            return;
        }

        try {
            // Parse the signature JSON
            Signature signature = Signature.fromJson(signatureJson);

            if(signature.getAlg().equals(AlgorithmId.FC_Sha256SymSignMsg_No1_NrC7)){
                if(key==null || key.isEmpty()){
                    showToast(getString(R.string.symkey_required_for_sha256_signature));
                    return;
                }else{
                    signature.setKey(Hex.fromHex(key));
                }
            }else if(key != null && !key.isEmpty()){
                if(signature.getFid()==null && KeyTools.isGoodFid(key))signature.setFid(key);
                else showToast(getString(R.string.key_is_not_required));
            }
            
            // Verify the signature
            boolean isValid = signature.verify();
            
            // Update the result icon
            if (isValid) {
                resultIcon.setImageResource(R.drawable.ic_verify_success);
                warnIfSignerIsNobody(signature.getFid());
            } else {
                resultIcon.setImageResource(R.drawable.ic_verify_fail);
            }
            
        } catch (Exception e) {
            resultIcon.setImageResource(R.drawable.ic_verify_fail);
        }
    }

    /** A valid signature by a nobody proves nothing: anyone holds that key. */
    private void warnIfSignerIsNobody(String signerFid) {
        if (signerFid == null || !KeyTools.isGoodFid(signerFid)) return;
        NobodyUi.resolveAsync(java.util.Collections.singletonList(signerFid), true, ok -> {
            if (isFinishing() || isDestroyed() || !NobodyUi.isNobody(signerFid)) return;
            DialogUtils.show(new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.nobody_warning_title)
                    .setMessage(R.string.nobody_signature_note)
                    .setPositiveButton(R.string.ok, null));
        });
    }

}
