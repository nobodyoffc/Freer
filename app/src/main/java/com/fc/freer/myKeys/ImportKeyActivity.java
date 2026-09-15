package com.fc.freer.myKeys;

import com.fc.freer.utils.WaitingTask;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.ui.DetailFragment;
import com.fc.freer.utils.FcEntityImporter;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Imports an identity from whatever key text the user has: a private key, public key,
 * FID, password cipher or key backup JSON, typed, pasted, scanned or picked from a file.
 * {@link KeyInputDetector} decides which, and the verdict is shown while typing so a
 * watch-only import is never mistaken for restoring a wallet.
 */
public class ImportKeyActivity extends BaseCryptoActivity {
    private static final String TAG = "ImportKey";
    private static final int QR_SCAN_KEY_REQUEST_CODE = 1001;
    private static final int QR_SCAN_PASSWORD_REQUEST_CODE = 1002;
    private static final int QR_SCAN_LABEL_REQUEST_CODE = 1003;

    private DetailFragment detailFragment;
    private TextInputEditText keyInput;
    private TextInputEditText passwordInput;
    private TextInputEditText labelInput;
    private TextView detectedText;
    private View passwordView;
    private View labelView;
    private ImageButton clearButton;
    private ImageButton previewButton;
    private ImageButton saveButton;

    private KeyInputDetector.Kind kind = KeyInputDetector.Kind.EMPTY;
    /** A backup file picked from storage; imported as a stream instead of the input text. */
    private Uri fileUri;
    private FcEntityImporter<KeyInfo> importer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> importerPasswordLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && importer != null) {
                    importer.handleInputResult(result.getData());
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    loadFile(result.getData().getData());
                }
            });

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import_key;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.import_key);
    }

    @Override
    protected void initializeViews() {
        View keyView = findViewById(R.id.keyView);
        passwordView = findViewById(R.id.passwordView);
        labelView = findViewById(R.id.labelView);

        keyInput = keyView.findViewById(R.id.textInput);
        keyInput.setHint(R.string.input_key_hint);
        passwordInput = passwordView.findViewById(R.id.textInput);
        passwordInput.setHint(R.string.input_the_password);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        labelInput = labelView.findViewById(R.id.textInput);
        labelInput.setHint(R.string.input_the_label);
        detectedText = findViewById(R.id.detectedText);

        clearButton = findViewById(R.id.clearButton);
        previewButton = findViewById(R.id.previewButton);
        saveButton = findViewById(R.id.saveButton);

        keyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                onKeyInputChanged();
            }
        });
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearInputs());
        previewButton.setOnClickListener(v -> preview());
        saveButton.setOnClickListener(v -> save());

        setupIoIconsView(R.id.keyView, R.id.scanIcon, false, false, true, true, true,
                null, null,
                () -> {
                    exitFileMode();
                    pasteFromClipboard(keyInput);
                },
                () -> startQrScan(QR_SCAN_KEY_REQUEST_CODE),
                this::openFilePicker);
        TextIconsUtils.setupTextIcons(this, R.id.passwordView, R.id.scanIcon, QR_SCAN_PASSWORD_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.labelView, R.id.scanIcon, QR_SCAN_LABEL_REQUEST_CODE);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_KEY_REQUEST_CODE) {
            exitFileMode();
            keyInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_PASSWORD_REQUEST_CODE) {
            passwordInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_LABEL_REQUEST_CODE) {
            labelInput.setText(qrContent);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void onKeyInputChanged() {
        clearDetailFragment();
        kind = fileUri != null ? KeyInputDetector.Kind.BACKUP : KeyInputDetector.detect(textOf(keyInput));
        showDetection();
    }

    private void showDetection() {
        String text = textOf(keyInput).trim();
        String message;
        int color = R.color.error;
        switch (kind) {
            case EMPTY -> message = null;
            case PRIKEY -> {
                message = getString(R.string.key_detected_prikey, fidOfPrikey(text));
                color = R.color.primary;
            }
            case PUBKEY -> {
                message = getString(R.string.key_detected_pubkey, fidOfPubkey(text));
                color = R.color.warning;
            }
            case FID -> {
                message = getString(R.string.key_detected_fid);
                color = R.color.warning;
            }
            case CIPHER -> {
                message = getString(R.string.key_detected_cipher);
                color = R.color.primary;
            }
            case BACKUP -> {
                message = getString(R.string.key_detected_backup);
                color = R.color.primary;
            }
            case NON_PASSWORD_CIPHER -> message = getString(R.string.key_non_password_cipher);
            case BAD_PRIKEY -> message = getString(R.string.key_bad_prikey);
            case BAD_PUBKEY -> message = getString(R.string.key_bad_pubkey);
            case BAD_FID -> message = getString(R.string.key_bad_fid);
            case MULTIPLE -> message = getString(R.string.key_multiple);
            default -> message = getString(R.string.key_unrecognized, getString(R.string.input_phrase));
        }
        detectedText.setText(message);
        detectedText.setTextColor(getColor(color));
        detectedText.setVisibility(message == null ? View.GONE : View.VISIBLE);
        passwordView.setVisibility(kind == KeyInputDetector.Kind.CIPHER ? View.VISIBLE : View.GONE);
        labelView.setVisibility(kind == KeyInputDetector.Kind.BACKUP ? View.GONE : View.VISIBLE);
    }

    private static String fidOfPrikey(String text) {
        byte[] prikey32 = KeyInputDetector.toPrikey32(text);
        try {
            return KeyTools.prikeyToFid(prikey32);
        } catch (Exception e) {
            return "?";
        } finally {
            BytesUtils.clearByteArray(prikey32);
        }
    }

    private static String fidOfPubkey(String text) {
        try {
            return KeyTools.pubkeyToFchAddr(text);
        } catch (Exception e) {
            return "?";
        }
    }

    private void preview() {
        hideKeyboard();
        buildKeyInfo(false, this::showDetailFragment);
    }

    private void save() {
        hideKeyboard();
        if (detailFragment != null) {
            KeyInfo keyInfo = (KeyInfo) detailFragment.getCurrentEntity();
            if (keyInfo != null) {
                saveAndFinishWithKeyInfo(keyInfo);
                return;
            }
        }
        if (kind == KeyInputDetector.Kind.BACKUP) {
            importBackup(null);
            return;
        }
        buildKeyInfo(true, this::saveAndFinishWithKeyInfo);
    }

    private void buildKeyInfo(boolean forSave, Consumer<KeyInfo> onReady) {
        String text = textOf(keyInput).trim();
        String label = textOf(labelInput).trim();
        switch (kind) {
            case EMPTY -> ToastUtils.makeText(this, getString(R.string.please_input_a_key));
            case PRIKEY -> {
                KeyInfo keyInfo = newPrikeyKeyInfo(label, KeyInputDetector.toPrikey32(text));
                if (keyInfo != null) onReady.accept(keyInfo);
            }
            case PUBKEY -> onReady.accept(new KeyInfo(label, text));
            case FID -> onReady.accept(KeyInfo.newFromFid(text, label));
            case CIPHER -> decryptCipher(text, label, forSave, onReady);
            case BACKUP -> ToastUtils.makeText(this, getString(R.string.key_backup_no_preview));
            default -> ToastUtils.makeText(this, detectedText.getText().toString());
        }
    }

    private KeyInfo newPrikeyKeyInfo(String label, byte[] prikey32) {
        if (prikey32 != null) {
            try {
                return new KeyInfo(label, prikey32, ConfigureManager.getInstance().getSymkey());
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to make key from prikey: %s", e.getMessage());
            }
        }
        ToastUtils.makeText(this, getString(R.string.toast_invalid_private_key));
        return null;
    }

    private void decryptCipher(String text, String label, boolean forSave, Consumer<KeyInfo> onReady) {
        String password = textOf(passwordInput);
        if (password.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.toast_password_empty));
            return;
        }
        CryptoDataByte cipher = KeyInputDetector.parseCipher(text);
        if (cipher == null) {
            ToastUtils.makeText(this, getString(R.string.toast_failed_decrypt_prikey));
            return;
        }

        // The KDF may be Argon2id, which is intentionally slow, so decrypt off the UI thread.
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.decrypting));
        progress.setCancelable(false);
        progress.show();

        Handler main = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            byte[] data = null;
            try {
                Decryptor.decryptByPassword(cipher, password.toCharArray());
                if (cipher.getCode() != null && cipher.getCode() == 0) data = cipher.getData();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to decrypt key cipher: %s", e.getMessage());
            }
            byte[] plaintext = data;
            main.post(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (isFinishing() || isDestroyed()) {
                    BytesUtils.clearByteArray(plaintext);
                    return;
                }
                onDecrypted(plaintext, password, label, forSave, onReady);
            });
        });
    }

    private void onDecrypted(byte[] plaintext, String password, String label,
                             boolean forSave, Consumer<KeyInfo> onReady) {
        if (plaintext == null) {
            ToastUtils.makeText(this, getString(R.string.toast_failed_decrypt_prikey));
            return;
        }
        try {
            byte[] prikey32 = KeyInputDetector.prikeyFromPlaintext(plaintext);
            if (prikey32 != null) {
                KeyInfo keyInfo = newPrikeyKeyInfo(label, prikey32);
                if (keyInfo != null) onReady.accept(keyInfo);
            } else if (KeyInputDetector.isJson(plaintext)) {
                // An encrypted backup line rather than a bare key: the importer opens it
                // again with the same password and re-encrypts its key for this device.
                if (forSave) {
                    importBackup(password);
                } else {
                    ToastUtils.makeText(this, getString(R.string.key_backup_no_preview));
                }
            } else {
                ToastUtils.makeText(this, getString(R.string.toast_invalid_private_key));
            }
        } finally {
            BytesUtils.clearByteArray(plaintext);
        }
    }

    private void importBackup(String password) {
        // A fresh importer for every attempt, because it accumulates results across calls.
        importer = new FcEntityImporter<>(this, KeyInfo.class, new FcEntityImporter.OnImportListener<>() {
            @Override
            public void onImportSuccess(List<KeyInfo> result) {
                if (result == null || result.isEmpty()) {
                    ToastUtils.makeText(ImportKeyActivity.this, getString(R.string.no_key_info_found));
                    return;
                }
                saveToConfigureAndFinish(result);
            }

            @Override
            public void onImportError(String error) {
                showToast(getString(R.string.operation_failed_with_message, error));
            }

            @Override
            public void onPasswordRequired(Intent intent) {
                importerPasswordLauncher.launch(intent);
            }
        });
        if (password != null) importer.setPassword(password);

        FcEntityImporter<KeyInfo> current = importer;
        Uri uri = fileUri;
        String text = textOf(keyInput).trim();
        // Every password cipher in a backup costs one Argon2id run, so import off the UI thread.
        WaitingTask.run(this, getString(R.string.decrypting), () -> {
            if (uri == null) return current.importEntity(text);
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                return is == null ? null : current.importEntity(is);
            }
        }, result -> {
            if (result == null) ToastUtils.makeText(this, getString(R.string.no_key_info_found));
        });
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"));
        filePickerLauncher.launch(intent);
    }

    private void loadFile(Uri uri) {
        fileUri = uri;
        keyInput.setText(R.string.file_loaded_import_it);
        keyInput.setEnabled(false);
        keyInput.setTextColor(getColor(R.color.hint));
    }

    private void exitFileMode() {
        if (fileUri == null) return;
        fileUri = null;
        keyInput.setEnabled(true);
        keyInput.setTextColor(getColor(R.color.text));
        keyInput.setText("");
    }

    private void clearInputs() {
        exitFileMode();
        keyInput.setText("");
        passwordInput.setText("");
        labelInput.setText("");
        clearDetailFragment();
    }

    private void showDetailFragment(KeyInfo keyInfo) {
        clearDetailFragment();
        detailFragment = DetailFragment.newInstance(keyInfo, KeyInfo.class);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.keyInfoContainer, detailFragment)
                .commit();
    }

    private void clearDetailFragment() {
        if (detailFragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(detailFragment)
                    .commit();
            detailFragment = null;
        }
    }

    private static String textOf(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString() : "";
    }
}
