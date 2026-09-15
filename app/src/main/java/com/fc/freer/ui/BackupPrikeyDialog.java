package com.fc.freer.ui;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Kdf;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Dialog for backing up the private key of the live FID
 * Features:
 * - QR code display (encrypted cipher or plain prikey)
 * - Private key text display with visibility toggle
 * - Encrypt checkbox (default checked) - shows cipher QR code
 * - Base58Check checkbox (default checked) - shows prikey in Base58 format
 * - Done button - returns "done"
 * - Copy Cipher button - copies encrypted cipher only
 * - Later button - dismisses dialog
 */
public class BackupPrikeyDialog extends Dialog {
    private static final String TAG = "BackupPrikeyDialog";

    private ImageView qrCodeImageView;
    private TextView prikeyTextView;
    private ImageView visibilityToggle;
    private ImageView makeQrToggle;
    private CheckBox encryptCheckbox;
    private CheckBox base58Checkbox;
    private Button doneButton;
    private Button copyCipherButton;
    private Button laterButton;

    private FrameLayout qrCodeContainer;

    private KeyInfo mainKeyInfo;
    private byte[] symkey;
    private String prikeyHex;
    private String prikeyBase58;
    private String prikeyCipher;
    private String prikeyCipherBase58;

    private boolean isPrikeyVisible = false;
    private boolean isQrVisible = false;

    private BackupPrikeyListener listener;

    public interface BackupPrikeyListener {
        void onDone();
        void onLater();
    }

    public BackupPrikeyDialog(@NonNull Context context) {
        super(context);
    }

    public BackupPrikeyDialog(@NonNull Context context, BackupPrikeyListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_backup_prikey);

        initializeViews();
        loadKeyData();
        setupListeners();
        updateDisplay();
    }

    private void initializeViews() {
        qrCodeImageView = findViewById(R.id.qrCodeImageView);
        qrCodeContainer = findViewById(R.id.qrCodeContainer);
        prikeyTextView = findViewById(R.id.prikeyTextView);
        visibilityToggle = findViewById(R.id.visibilityToggle);
        makeQrToggle = findViewById(R.id.makeQrToggle);
        encryptCheckbox = findViewById(R.id.encryptCheckbox);
        base58Checkbox = findViewById(R.id.base58Checkbox);
        doneButton = findViewById(R.id.doneButton);
        copyCipherButton = findViewById(R.id.copyCipherButton);
        laterButton = findViewById(R.id.laterButton);

        // Initially hide QR code and encrypt checkbox
        qrCodeContainer.setVisibility(android.view.View.GONE);
        encryptCheckbox.setVisibility(android.view.View.GONE);
    }

    private void loadKeyData() {
        // Get live FID's KeyInfo
        mainKeyInfo = SettingManager.getInstance().getCurrentKeyInfo();

        if (mainKeyInfo == null) {
            TimberLogger.e(TAG, "Live KeyInfo is null");
            ToastUtils.makeText(getContext(), R.string.no_key_info_available);
            close();
            return;
        }

        // Check if prikey is available
        if (mainKeyInfo.getPrikeyCipher() == null ) {
            TimberLogger.e(TAG, "Prikey is not available for live FID");
            ToastUtils.makeText(getContext(), R.string.prikey_not_available);
            close();
            return;
        }

        // Get symkey from ConfigureManager
        ConfigureManager configureManager = ConfigureManager.getInstance();
        symkey = configureManager.getSymkey();

        if (symkey == null) {
            TimberLogger.e(TAG, "Symkey is null");
            ToastUtils.makeText(getContext(), R.string.symkey_not_available);
            close();
            return;
        }

        // Get prikey in different formats
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(mainKeyInfo.getPrikeyCipher());
        if(prikey==null){
            TimberLogger.e(TAG, "Prikey is null");
            ToastUtils.makeText(getContext(), R.string.no_prikey_available);
            close();
            return;
        }

        try {
            prikeyHex = Hex.toHex(prikey);
            prikeyBase58 = KeyTools.prikey32To38WifCompressed(prikeyHex);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to convert prikey to Base58: %s", e.getMessage());
            prikeyBase58 = prikeyHex; // Fallback to hex
        }
        // The ciphers are made on first use, from a password the user enters then.
    }

    /**
     * Makes the cipher of the form the dialog shows now (hex or Base58Check) from a password the user
     * enters. It is a Password cipher (Argon2id, AES-256-GCM), so Safe and Freer both decrypt it with
     * that password. The password must be the vault's, so a typo cannot produce a backup nobody can open.
     */
    private void withCipher(Runnable onReady, Runnable onFail) {
        boolean base58 = base58Checkbox.isChecked();
        PasswordInputDialog dialog = new PasswordInputDialog(getContext(),
                getContext().getString(R.string.enter_password_prompt),
                getContext().getString(R.string.password),
                new PasswordInputDialog.OnPasswordInputListener() {
                    @Override
                    public void onConfirm(String password) {
                        String text = base58 ? prikeyBase58 : prikeyHex;
                        if (password == null || password.isEmpty() || text == null) {
                            ToastUtils.makeText(getContext(), R.string.please_enter_password);
                            if (onFail != null) onFail.run();
                            return;
                        }
                        byte[] plain = base58 ? text.getBytes(StandardCharsets.UTF_8) : Hex.fromHex(text);
                        new Thread(() -> {
                            // Checking the password and deriving the cipher key each run Argon2id.
                            boolean verified = ConfigureManager.getInstance().verifyPassword(password.getBytes());
                            String cipher = null;
                            if (verified) {
                                Encryptor encryptor = new Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7);
                                encryptor.setKdf(Kdf.Argon2id_No1_NrC7);
                                CryptoDataByte cryptoDataByte = encryptor.encryptByPassword(plain, password.toCharArray());
                                if (cryptoDataByte.getCode() != null && cryptoDataByte.getCode() == 0) {
                                    cryptoDataByte.setData(null);
                                    cryptoDataByte.setSymkey(null);
                                    cryptoDataByte.setPassword(null);
                                    cipher = cryptoDataByte.toJson();
                                } else {
                                    TimberLogger.e(TAG, "Failed to encrypt prikey: %s", cryptoDataByte.getMessage());
                                }
                            }
                            Arrays.fill(plain, (byte) 0);
                            String made = cipher;
                            qrCodeImageView.post(() -> {
                                if (!isShowing()) return;
                                if (!verified) {
                                    ToastUtils.makeText(getContext(), R.string.incorrect_password);
                                } else if (made == null) {
                                    ToastUtils.makeText(getContext(), R.string.failed_to_encrypt_prikey);
                                } else {
                                    if (base58) prikeyCipherBase58 = made;
                                    else prikeyCipher = made;
                                    onReady.run();
                                    return;
                                }
                                if (onFail != null) onFail.run();
                            });
                        }).start();
                    }

                    @Override
                    public void onCancel() {
                        if (onFail != null) onFail.run();
                    }
                });
        dialog.show();
    }

    private void setupListeners() {
        // Visibility toggle
        visibilityToggle.setOnClickListener(v -> {
            isPrikeyVisible = !isPrikeyVisible;
            updatePrikeyVisibility();
        });

        // Make QR toggle
        makeQrToggle.setOnClickListener(v -> {
            isQrVisible = !isQrVisible;
            updateQrVisibility();
        });

        // Encrypt checkbox
        encryptCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateQRCode();
            updateCopyCipherButton();
        });

        // Base58 checkbox
        base58Checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updatePrikeyText();
            updateQRCode();
        });

        // Done button
        doneButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDone();
            }
            close();
        });

        // Copy Cipher button
        copyCipherButton.setOnClickListener(v -> {
            copyCipherToClipboard();
        });

        // Later button
        laterButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onLater();
            }
            close();
        });
    }

    private void updateDisplay() {
        updateQRCode();
        updatePrikeyText();
        updatePrikeyVisibility();
        updateCopyCipherButton();
    }

    private void updateQRCode() {
        String qrContent;

        if (encryptCheckbox.isChecked()) {
            // Show encrypted cipher of the Base58Check text or the hex text, matching the checkbox
            qrContent = base58Checkbox.isChecked() ? prikeyCipherBase58 : prikeyCipher;
            if (qrContent == null) {
                qrCodeImageView.setImageBitmap(null);
                if (isQrVisible) withCipher(this::updateQRCode, () -> encryptCheckbox.setChecked(false));
                return;
            }
        } else {
            // Show plain prikey, honoring the Base58Check/Hex choice
            qrContent = base58Checkbox.isChecked() ? prikeyBase58 : prikeyHex;
        }

        // Generate QR code bitmap
        List<Bitmap> qrBitmaps = QRCodeGenerator.generateQRBitmaps(qrContent);

        if (!qrBitmaps.isEmpty()) {
            // Display the first QR code (assuming prikey fits in one QR code)
            qrCodeImageView.setImageBitmap(qrBitmaps.get(0));
        } else {
            TimberLogger.e(TAG, "Failed to generate QR code");
            ToastUtils.makeText(getContext(), R.string.error_creating_qr);
        }
    }

    private void updatePrikeyText() {
        String displayText;

        if (base58Checkbox.isChecked()) {
            displayText = prikeyBase58;
        } else {
            displayText = prikeyHex;
        }

        // If not visible, mask the text
        if (!isPrikeyVisible) {
            displayText = maskText(displayText);
        }

        prikeyTextView.setText(displayText);
    }

    private void updatePrikeyVisibility() {
        if (isPrikeyVisible) {
            visibilityToggle.setImageResource(R.drawable.ic_visibility_on);
        } else {
            visibilityToggle.setImageResource(R.drawable.ic_visibility_off);
        }
        updatePrikeyText();
    }

    private void updateQrVisibility() {
        if (isQrVisible) {
            qrCodeContainer.setVisibility(android.view.View.VISIBLE);
            encryptCheckbox.setVisibility(android.view.View.VISIBLE);
            updateQRCode();
        } else {
            qrCodeContainer.setVisibility(android.view.View.GONE);
            encryptCheckbox.setVisibility(android.view.View.GONE);
        }
    }

    private void updateCopyCipherButton() {
        // Enable copy cipher button only when encrypt is checked
        copyCipherButton.setEnabled(encryptCheckbox.isChecked());
    }

    private String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Replace all characters with '*'
        return "*".repeat(text.length());
    }

    private void copyCipherToClipboard() {
        String cipherToCopy = base58Checkbox.isChecked() ? prikeyCipherBase58 : prikeyCipher;
        if (cipherToCopy == null) {
            withCipher(this::copyCipherToClipboard, null);
            return;
        }
        if (!cipherToCopy.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Prikey Cipher", cipherToCopy);
            clipboard.setPrimaryClip(clip);
            ToastUtils.makeText(getContext(), R.string.cipher_copied);
        } else {
            ToastUtils.makeText(getContext(), R.string.no_cipher_to_copy);
        }
    }

    /**
     * Securely close the dialog by erasing sensitive data
     */
    public void close() {
        // Erase sensitive private key data
        if (prikeyHex != null) {
            prikeyHex = null;
        }
        if (prikeyBase58 != null) {
            prikeyBase58 = null;
        }
        if (prikeyCipher != null) {
            prikeyCipher = null;
        }
        if (prikeyCipherBase58 != null) {
            prikeyCipherBase58 = null;
        }

        // Dismiss the dialog
        dismiss();
    }

    public void setListener(BackupPrikeyListener listener) {
        this.listener = listener;
    }
}
