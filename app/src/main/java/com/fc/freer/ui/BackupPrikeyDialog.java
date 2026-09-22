package com.fc.freer.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.fc.freer.home.SetMasterActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.onboarding.LiveFidRecord;
import com.fc.freer.onboarding.Onboarding;
import com.fc.freer.onboarding.OnboardingStatus;
import com.fc.freer.onboarding.PendingIdentityCarve;
import com.fc.freer.onboarding.PendingIdentityCarves;
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
 * <p>
 * A fourth way out is named but not taken here: <b>setting a master</b> carves this key onto
 * the chain sealed to another FID's pubkey, which is a copy that outlives this phone. It is a
 * pointer to {@link SetMasterActivity} and not a button beside Copy Cipher, because it is
 * permanent, costs a fee, needs coins this FID may not have yet, and hands that FID every right
 * this identity has — that screen exists to slow the user down, and reaching it in the same
 * stride as copying a cipher would undo it. Once the chain holds a master the section says so
 * instead: FEIP6 lets a FID name one only once, so there is nothing left to offer.
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

    private LinearLayout masterContainer;
    private TextView masterNoteTextView;
    private TextView masterFidTextView;
    private Button setMasterButton;

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
        showMasterSection();
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
        masterContainer = findViewById(R.id.masterContainer);
        masterNoteTextView = findViewById(R.id.masterNoteTextView);
        masterFidTextView = findViewById(R.id.masterFidTextView);
        setMasterButton = findViewById(R.id.setMasterButton);

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
                        // Two Argon2id runs take seconds; without this the tap looks ignored and a
                        // second tap asks for the password again while the first is still working.
                        WaitingDialog waiting = new WaitingDialog(getContext(),
                                getContext().getString(R.string.please_wait));
                        waiting.show();
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
                                waiting.dismiss();
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

    /**
     * Draw the master section for the main FID: what the chain already holds, what is on its
     * way there, or the offer to carve one — and, when the FID cannot carve yet, why instead of
     * a button that would fail. A sub-identity gets nothing: a master is the main FID's.
     */
    private void showMasterSection() {
        FidManager fidManager = FidManager.getInstance();
        String mainFid = fidManager != null ? fidManager.getMainFid() : null;
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        if (mainFid == null || !mainFid.equals(liveFid)) return;

        masterContainer.setVisibility(View.VISIBLE);
        // Only what the chain said in this process. The cached KeyInfo is written on broadcast,
        // and this section's whole claim is that the copy is *there*.
        LiveFidRecord record = LiveFidRecord.confirmed(mainFid);
        String master = record != null && record.master != null ? record.master.trim() : "";
        if (!master.isEmpty()) {
            masterNoteTextView.setText(R.string.backup_master_already);
            showMasterFid(getContext().getString(R.string.gs_master_fid,
                    shortId(master)), master);
            return;
        }

        PendingIdentityCarve pending = PendingIdentityCarves.of(getContext())
                .getInFlight(mainFid, PendingIdentityCarve.Kind.MASTER, System.currentTimeMillis());
        if (pending != null) {
            masterNoteTextView.setText(R.string.backup_master_pending);
            if (pending.txid != null) {
                showMasterFid(getContext().getString(R.string.gs_txid, shortId(pending.txid)), pending.txid);
            }
            return;
        }

        String blocker = carveBlocker(record);
        if (blocker != null) {
            masterNoteTextView.setText(blocker);
            return;
        }
        masterNoteTextView.setText(R.string.backup_master_explain);
        setMasterButton.setVisibility(View.VISIBLE);
        setMasterButton.setOnClickListener(v -> {
            // The carve screen carries the warnings, and nothing is recorded as backed up on
            // the way through: the checklist reads a master off the chain record itself.
            close();
            Context context = getContext();
            Intent intent = new Intent(context, SetMasterActivity.class);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });
    }

    /** Why a master cannot be carved from this FID yet, or null when it can. */
    private String carveBlocker(LiveFidRecord record) {
        // The chain has not answered yet: SetMasterActivity asks again before it carves.
        if (record == null) return null;
        long balance = record.balance != null ? record.balance : 0L;
        long cd = record.cd != null ? record.cd : 0L;
        if (balance == 0 && cd == 0) return getContext().getString(R.string.backup_master_needs_coins);
        OnboardingStatus wait = Onboarding.coinDayWait(record);
        if (wait == null) return null;
        if (wait.days == null) {
            return getContext().getString(R.string.gs_status_coin_days, wait.have, wait.need);
        }
        if (wait.days == 1) {
            return getContext().getString(R.string.gs_status_coin_days_one_day, wait.have, wait.need);
        }
        return getContext().getString(R.string.gs_status_coin_days_days, wait.have, wait.need, wait.days);
    }

    private void showMasterFid(String display, String value) {
        masterFidTextView.setVisibility(View.VISIBLE);
        masterFidTextView.setText(display);
        masterFidTextView.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("id", value));
            ToastUtils.makeText(getContext(), R.string.copied);
        });
    }

    /** Head and tail kept, as every other ID in the app is shortened. */
    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() <= 13 ? id : id.substring(0, 6) + "…" + id.substring(id.length() - 6);
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
