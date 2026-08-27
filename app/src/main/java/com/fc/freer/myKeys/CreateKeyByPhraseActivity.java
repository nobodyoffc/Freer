package com.fc.freer.myKeys;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.core.crypto.Kdf;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.ui.DetailFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.fc.freer.utils.TextIconsUtils;

import java.util.concurrent.Executors;

public class CreateKeyByPhraseActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateKeyByPhrase";
    private static final int QR_SCAN_PHRASE_REQUEST_CODE = 1001;
    private static final int QR_SCAN_LABEL_REQUEST_CODE = 1002;

    private enum DerivationMode { ARGON2ID, SHA256 }
    private interface KeyInfoCallback { void onReady(KeyInfo keyInfo); }

    private DetailFragment detailFragment;
    private TextInputEditText phraseInput;
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
        return getString(R.string.create_key_by_phrase);
    }

    @Override
    protected void initializeViews() {
        keyInfoContainer = findViewById(R.id.keyInfoContainer);
        inputContainer = findViewById(R.id.inputContainer);
        buttonContainer = findViewById(R.id.buttonContainer);

        // Initialize input fields from included layouts
        View phraseView = findViewById(R.id.pubkeyView);
        View labelView = findViewById(R.id.labelView);

        phraseInput = phraseView.findViewById(R.id.textInput);
        phraseInput.setHint(R.string.input_the_phrase);
        labelInput = labelView.findViewById(R.id.textInput);
        labelInput.setHint(R.string.input_the_label);

        clearButton = findViewById(R.id.clearButton);
        previewButton = findViewById(R.id.previewButton);
        saveButton = findViewById(R.id.saveButton);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearInputs());
        previewButton.setOnClickListener(v -> onPreviewClicked());
        saveButton.setOnClickListener(v -> onSaveClicked());

        // Setup scan icons using TextIconsUtils
        TextIconsUtils.setupTextIcons(this, R.id.pubkeyView, R.id.scanIcon, QR_SCAN_PHRASE_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.labelView, R.id.scanIcon, QR_SCAN_LABEL_REQUEST_CODE);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_PHRASE_REQUEST_CODE) {
            phraseInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_LABEL_REQUEST_CODE) {
            labelInput.setText(qrContent);
        }
    }

    private void clearInputs() {
        phraseInput.setText("");
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

    private void onPreviewClicked() {
        String phrase = phraseInput.getText() != null ? phraseInput.getText().toString() : "";
        if (phrase.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_phrase));
            return;
        }
        promptDerivationMode(mode -> deriveKeyInfoAsync(mode, this::showDetailFragment));
    }

    private void onSaveClicked() {
        if (detailFragment != null) {
            KeyInfo keyInfo = (KeyInfo) detailFragment.getCurrentEntity();
            if (keyInfo == null) {
                ToastUtils.makeText(this, getString(R.string.failed_to_get_keyinfo_from_preview));
                return;
            }
            saveAndFinishWithKeyInfo(keyInfo);
            return;
        }

        String phrase = phraseInput.getText() != null ? phraseInput.getText().toString() : "";
        if (phrase.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_phrase));
            return;
        }
        promptDerivationMode(mode -> deriveKeyInfoAsync(mode, this::saveAndFinishWithKeyInfo));
    }

    private void promptDerivationMode(java.util.function.Consumer<DerivationMode> onChosen) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_derivation_method)
                .setMessage(R.string.choose_derivation_method_message)
                .setPositiveButton(R.string.derivation_kdf_argon2id,
                        (d, w) -> onChosen.accept(DerivationMode.ARGON2ID))
                .setNegativeButton(R.string.derivation_sha256,
                        (d, w) -> confirmSha256(onChosen))
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    private void confirmSha256(java.util.function.Consumer<DerivationMode> onChosen) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.derivation_sha256_warning_title)
                .setMessage(R.string.derivation_sha256_warning_message)
                .setPositiveButton(R.string.proceed,
                        (d, w) -> onChosen.accept(DerivationMode.SHA256))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deriveKeyInfoAsync(DerivationMode mode, KeyInfoCallback callback) {
        String phrase = phraseInput.getText() != null ? phraseInput.getText().toString() : "";
        String label = labelInput.getText() != null ? labelInput.getText().toString() : "";
        if (phrase.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_phrase));
            return;
        }

        if (mode == DerivationMode.SHA256) {
            byte[] priKey32 = Hash.sha256(phrase.getBytes());
            callback.onReady(new KeyInfo(label, priKey32, ConfigureManager.getInstance().getSymkey()));
            return;
        }

        // Argon2id is intentionally slow — run off the UI thread.
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.deriving_key));
        progress.setCancelable(false);
        progress.show();

        Handler main = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            // Empty salt so the same phrase always derives the same key AND stays
            // compatible with Safe, which derives with Kdf.Argon2id_No1_NrC7 and new byte[0].
            //
            // NOTE (legacy): earlier Freer builds used a deterministic per-phrase salt:
            //     byte[] salt = Arrays.copyOf(Hash.sha256(phrase.getBytes()), 16);
            // Any private key created by those builds was derived from that salt and will
            // NOT match the empty-salt result below. If such keys must be recovered, restore
            // the salt above for that specific phrase.
            byte[] salt = new byte[0];
            byte[] priKey32 = Kdf.Argon2id_No1_NrC7.deriveSymkey(phrase.toCharArray(), salt);
            KeyInfo keyInfo = new KeyInfo(label, priKey32, ConfigureManager.getInstance().getSymkey());
            main.post(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (!isFinishing() && !isDestroyed()) callback.onReady(keyInfo);
            });
        });
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

}
