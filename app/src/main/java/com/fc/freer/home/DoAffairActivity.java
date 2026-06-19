package com.fc.freer.home;

import static com.fc.fc_ajdk.data.fcData.Op.ASK_DATA_STR;
import static com.fc.fc_ajdk.data.fcData.Op.ASK_HAT_STR;
import static com.fc.fc_ajdk.data.fcData.Op.DECRYPT_STR;
import static com.fc.fc_ajdk.data.fcData.Op.ENCRYPT_STR;
import static com.fc.fc_ajdk.data.fcData.Op.NOTIFY_STR;
import static com.fc.fc_ajdk.data.fcData.Op.SHARE_DATA_STR;
import static com.fc.fc_ajdk.data.fcData.Op.SHARE_HAT_STR;
import static com.fc.fc_ajdk.data.fcData.Op.SIGN_STR;
import static com.fc.fc_ajdk.data.fcData.Op.UPDATE_DATA_STR;
import static com.fc.fc_ajdk.data.fcData.Op.VERIFY_STR;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.constants.CodeMessage;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.EncryptType;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.fc_ajdk.data.fcData.Affair;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.ui.PasswordInputDialog;
import com.fc.freer.ui.StringInputDialog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Activity for handling and executing affair actions
 */
public class DoAffairActivity extends BaseCryptoActivity {
    private static final String TAG = "DoAffairActivity";
    public static final String EXTRA_AFFAIR_JSON = "affair_json";

    protected ImageButton doButton;
    private LinearLayout buttonContainer;
    private Affair affair;

    // Affair container views
    private LinearLayout affairContainerLayout;
    private LinearLayout subjectLayout;
    private TextView subjectFid;
    private ImageView subjectAvatar;
    private LinearLayout doLayout;
    private TextView doOp;
    private TextView doOpType;
    private LinearLayout onLayout;
    private TextView onData;
    private LinearLayout toLayout;
    private TextView toFidB;
    private ImageView toAvatar;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_do_affair;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.do_affair);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get affair JSON from intent
        String affairJson = getIntent().getStringExtra(EXTRA_AFFAIR_JSON);
        if (affairJson != null) {
            loadAffair(affairJson);
        }

        setupListeners();
    }

    @Override
    protected void initializeViews() {
        resultTextView = findViewById(R.id.resultView).findViewById(R.id.textBoxWithMakeQrLayout);
        resultTextView.setHint(R.string.result);
        resultTextView.setKeyListener(null);

        clearButton = findViewById(R.id.clearButton);
        copyButton = findViewById(R.id.copyButton);
        doButton = findViewById(R.id.doButton);
        buttonContainer = findViewById(R.id.buttonContainer);

        // Initialize affair container views
        affairContainerLayout = findViewById(R.id.affairContainer);
        subjectLayout = affairContainerLayout.findViewById(R.id.byLayout);
        subjectFid = affairContainerLayout.findViewById(R.id.subjectFid);
        subjectAvatar = affairContainerLayout.findViewById(R.id.subjectAvatar);
        doLayout = affairContainerLayout.findViewById(R.id.doLayout);
        doOp = affairContainerLayout.findViewById(R.id.doOp);
        doOpType = affairContainerLayout.findViewById(R.id.doOpType);
        onLayout = affairContainerLayout.findViewById(R.id.onLayout);
        onData = affairContainerLayout.findViewById(R.id.onData);
        toLayout = affairContainerLayout.findViewById(R.id.toLayout);
        toFidB = affairContainerLayout.findViewById(R.id.toFidB);
        toAvatar = affairContainerLayout.findViewById(R.id.toAvatar);

        // Initially hide affair container until affair is loaded
        affairContainerLayout.setVisibility(View.GONE);

        // Setup IO icons for result output with QR generation
        setupIoIconsView(R.id.resultView, R.id.makeQrIcon, true, false, false, false,
                false, () -> {
                String content = resultTextView.getText().toString();
                if (!TextUtils.isEmpty(content)) {
                    QRCodeGenerator.generateAndShowQRCode(this, content);
                } else {
                    showToast(getString(R.string.no_content_to_generate_qr_code));
                }
            }, null, null, null, null);

        // Display affair if loaded from intent
        if (affair != null) {
            displayAffair(affair);
        }
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
        setupButton(doButton, v -> {
            hideKeyboard();
            doAffair();
        });
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
        doButton.setOnClickListener(v -> {
            hideKeyboard();
            doAffair();
        });
    }

    private void clearInputs() {
        updateResultText(null);
        affair = null;
        affairContainerLayout.setVisibility(View.GONE);
    }

    /**
     * Process affair and execute the operation
     */
    private void doAffair() {
        // Clear the result first
        resultTextView.setText("");
        copyButton.setEnabled(false);

        try {
            // Parse affair from JSON
            if (affair == null) {
                showToast(getString(R.string.error_invalid_affair_data));
                return;
            }

            // Display affair in structured format
            displayAffair(affair);

            // Check if encryption operation requires user input (Symkey or Password)
            if (requiresUserInputForEncryption(affair)) {
                showInputDialogForEncryption(affair);
            } else {
                // Process affair based on operation type
                processAffairOperation(affair, null);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing affair: " + e.getMessage());
            showToast(getString(R.string.error_processing_affair) + ": " + e.getMessage());
        }
    }

    /**
     * Check if affair requires user input for encryption (Symkey or Password)
     */
    private boolean requiresUserInputForEncryption(Affair affair) {
        if (affair.getOp() == null || affair.getOpType() == null) {
            return false;
        }

        String op = affair.getOp().name();
        String opType = affair.getOpType();

        // Only ENCRYPT operations need user input for Symkey or Password
        return op.equals(ENCRYPT_STR) &&
               (opType.equals("Symkey") || opType.equals("Password"));
    }

    /**
     * Show appropriate input dialog for encryption operation
     */
    private void showInputDialogForEncryption(Affair affair) {
        String opType = affair.getOpType();

        if ("Symkey".equals(opType)) {
            // Show string input dialog for symkey
            StringInputDialog dialog = new StringInputDialog(
                this,
                getString(R.string.enter_symkey_prompt),
                getString(R.string.symkey),
                null,
                new StringInputDialog.OnInputListener() {
                    @Override
                    public void onConfirm(String input) {
                        if (TextUtils.isEmpty(input)) {
                            showToast(getString(R.string.symkey_cannot_be_empty));
                            return;
                        }
                        processAffairOperation(affair, input);
                    }

                    @Override
                    public void onCancel() {
                        TimberLogger.i(TAG, "Symkey input cancelled");
                    }
                }
            );
            dialog.show();
        } else if ("Password".equals(opType)) {
            // Show password input dialog for password
            PasswordInputDialog dialog = new PasswordInputDialog(
                this,
                getString(R.string.enter_password_prompt),
                getString(R.string.password),
                new PasswordInputDialog.OnPasswordInputListener() {
                    @Override
                    public void onConfirm(String password) {
                        if (TextUtils.isEmpty(password)) {
                            showToast(getString(R.string.password_cannot_be_empty));
                            return;
                        }
                        processAffairOperation(affair, password);
                    }

                    @Override
                    public void onCancel() {
                        TimberLogger.i(TAG, "Password input cancelled");
                    }
                }
            );
            dialog.show();
        }
    }

    /**
     * Process affair operation based on op type
     * @param affair The affair to process
     * @param userInput The user input (symkey or password) if required, null otherwise
     */
    private void processAffairOperation(Affair affair, String userInput) {
        if (affair.getOp() == null) {
            showToast("Error: No operation specified in affair");
            return;
        }

        StringBuilder result = new StringBuilder();
        String op = affair.getOp().name().toLowerCase();
        Encryptor encryptor;
        CryptoDataByte cryptoDataByte = null;
        String cipher = null;
        byte[] dataBytes;
        byte[] pubkeyB;

        switch (op) {
            case ENCRYPT_STR:
                if (affair.getData() != null) {
                    dataBytes = JsonUtils.toJson(affair.getData()).getBytes();
                } else if(affair.getDataStr() != null) {
                    dataBytes = affair.getDataStr().getBytes();
                } else {
                    showToast("Error: No data to encrypt");
                    return;
                }

                switch (affair.getOpType()) {
                    case "Symkey":
                        if (userInput == null) {
                            showToast("Error: Symkey required for encryption");
                            return;
                        }
                        encryptor = new Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7);
                        cryptoDataByte = encryptor.encryptBySymkey(dataBytes, userInput.getBytes());
                        break;
                    case "Password":
                        if (userInput == null) {
                            showToast("Error: Password required for encryption");
                            return;
                        }
                        encryptor = new Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7);
                        cryptoDataByte = encryptor.encryptByPassword(dataBytes, userInput.toCharArray());
                        break;
                    case "AsyOneWay":
                        encryptor = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7);
                        if (affair.getPubkeyB() != null) {
                            pubkeyB = Hex.fromHex(affair.getPubkeyB());
                        } else {
                            showToast("Error: No public key specified");
                            return;
                        }
                        cryptoDataByte = encryptor.encryptByAsyOneWay(dataBytes, pubkeyB);
                        break;
                    case "AsyTwoWay":
                        encryptor = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7);
                        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher());
                        if (affair.getPubkeyB() != null) {
                            pubkeyB = Hex.fromHex(affair.getPubkeyB());
                        } else {
                            showToast("Error: No public key specified");
                            return;
                        }
                        cryptoDataByte = encryptor.encryptByAsyTwoWay(dataBytes, prikey, pubkeyB);
                        break;
                    default:
                        break;
                }

                if(cryptoDataByte== null || cryptoDataByte.getCode() != CodeMessage.Code0Success) {
                    showToast("Error: Failed to encrypt data");
                    return;
                }
                result.append(cryptoDataByte.toNiceJson());
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                TimberLogger.i(TAG, "Affair processed successfully");
                break;

            case DECRYPT_STR:
                // Extract cipher from affair data
                if (affair.getData() != null) {
                    cipher = JsonUtils.toJson(affair.getData());
                } else if(affair.getDataStr() != null) {
                    cipher = affair.getDataStr();
                } else {
                    showToast("Error: No cipher to decrypt");
                    return;
                }

                // Call the decrypt method which handles all type detection and user input
                decrypt(cipher);
                break;

            case NOTIFY_STR:
                result.append("Notifying data: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case SIGN_STR:
                result.append("Signing data: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case VERIFY_STR:
                result.append("Verifying data: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case UPDATE_DATA_STR:
                result.append("Updating data: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case ASK_DATA_STR:
                result.append("Asking data: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case SHARE_DATA_STR:
                result.append("Sharing data: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case ASK_HAT_STR:
                result.append("Asking hat: ").append(affair.getData()).append("\n");
                updateResultText(result.toString());
                copyButton.setEnabled(true);
                break;
            case SHARE_HAT_STR:
            default:
                break;
        }
    }

    /**
     * Display affair in structured format
     */
    private void displayAffair(Affair affair) {
        if (affair == null) {
            affairContainerLayout.setVisibility(View.GONE);
            return;
        }

        affairContainerLayout.setVisibility(View.VISIBLE);

        // 1. Subject: <affair.fid> <avatar of this fid>
        if (!TextUtils.isEmpty(affair.getFid())) {
            subjectLayout.setVisibility(View.VISIBLE);
            subjectFid.setText(affair.getFid());
            // Load avatar for subject FID
            loadAvatarForFid(affair.getFid(), subjectAvatar);
        } else {
            subjectLayout.setVisibility(View.GONE);
        }

        // 2. Do: <affair.op> <affair.opType>
        if (affair.getOp() != null) {
            doLayout.setVisibility(View.VISIBLE);
            doOp.setText(affair.getOp().toString());
            if (!TextUtils.isEmpty(affair.getOpType())) {
                doOpType.setVisibility(View.VISIBLE);
                doOpType.setText("(" + affair.getOpType() + ")");
            } else {
                doOpType.setVisibility(View.GONE);
            }
        } else {
            doLayout.setVisibility(View.GONE);
        }

        // 3. On: <affair.dataStr (if it's json, show it pretty. if null, show affair.oid)>
        String dataContent = null;
        if (!TextUtils.isEmpty(affair.getDataStr())) {
            // Try to format as JSON if it's valid JSON
            dataContent = formatDataContent(affair.getDataStr());
        } else if (affair.getData() != null) {
            // If dataStr is empty but data object exists, try to format it
            dataContent = formatDataObject(affair.getData());
        } else if (!TextUtils.isEmpty(affair.getOid())) {
            // Fall back to OID if no data
            dataContent = affair.getOid();
        }

        if (!TextUtils.isEmpty(dataContent)) {
            onLayout.setVisibility(View.VISIBLE);
            onData.setText(dataContent);
        } else {
            onLayout.setVisibility(View.GONE);
        }

        // 4. To: <affair.fidB> <avatar of this fid>
        if (!TextUtils.isEmpty(affair.getFidB())) {
            toLayout.setVisibility(View.VISIBLE);
            toFidB.setText(affair.getFidB());
            // Load avatar for target FID
            loadAvatarForFid(affair.getFidB(), toAvatar);
        } else {
            toLayout.setVisibility(View.GONE);
        }
    }

    /**
     * Load avatar for a FID
     */
    private void loadAvatarForFid(String fid, ImageView avatarView) {
        if (TextUtils.isEmpty(fid) || avatarView == null) {
            return;
        }

        try {
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
            if (avatarBitmap != null) {
                avatarView.setImageBitmap(avatarBitmap);
            } else {
                avatarView.setImageResource(R.drawable.ic_person);
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to load avatar for FID: %s", fid);
            avatarView.setImageResource(R.drawable.ic_person);
        }
    }

    /**
     * Format data content - try to parse and pretty-print JSON
     */
    private String formatDataContent(String data) {
        if (TextUtils.isEmpty(data)) {
            return null;
        }

        // Try to parse as JSON and pretty-print
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Object jsonObject = JsonParser.parseString(data);
            return gson.toJson(jsonObject);
        } catch (JsonSyntaxException e) {
            // Not JSON, return as-is
            return data;
        }
    }

    /**
     * Format data object - convert to pretty JSON
     */
    private String formatDataObject(Object data) {
        if (data == null) {
            return null;
        }

        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            return gson.toJson(data);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to format data object: %s", e.getMessage());
            return data.toString();
        }
    }

    /**
     * Load and parse affair from JSON
     */
    private void loadAffair(String affairJson) {
        try {
            affair = Affair.fromJson(affairJson, Affair.class);
            if (affair != null) {
                displayAffair(affair);
                TimberLogger.i(TAG, "Affair loaded successfully: %s", affair.getId());
            } else {
                TimberLogger.e(TAG, "Failed to parse affair JSON");
                showToast(getString(R.string.error_invalid_affair_data));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading affair: %s", e.getMessage());
            showToast(getString(R.string.error_loading_affair));
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    /**
     * Decrypt cipher text and display the result
     * @param cipher The cipher text to decrypt
     */
    private void decrypt(String cipher) {
        // Clear result first
        resultTextView.setText("");
        copyButton.setEnabled(false);

        try {
            CryptoDataByte cryptoDataByte = null;

            // Step 1: If cipher starts with 'A', try decryptBundleTry first
            if (cipher.startsWith("A")) {
                byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(
                    FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher()
                );

                if (prikey != null) {
                    cryptoDataByte = Decryptor.decryptBundleTry(cipher, prikey);

                    // If decryptBundleTry failed, parse as JSON
                    if (cryptoDataByte.getCode() != CodeMessage.Code0Success) {
                        cryptoDataByte = CryptoDataByte.fromJson(cipher);
                    }
                } else {
                    cryptoDataByte = CryptoDataByte.fromJson(cipher);
                }
            } else {
                // Parse cipher as JSON
                cryptoDataByte = CryptoDataByte.fromJson(cipher);
            }

            if (cryptoDataByte==null) {
                showToast(getString(R.string.invalid_cipher));
                return;
            }

            // Step 2: Handle Symkey or Password types
            EncryptType type = cryptoDataByte.getType();
            if (type == EncryptType.Symkey) {
                showSymkeyInputDialogForDecrypt(cryptoDataByte);
                return;
            } else if (type == EncryptType.Password) {
                showPasswordInputDialogForDecrypt(cryptoDataByte);
                return;
            }

            // Step 3: Handle AsyOneWay or AsyTwoWay types
            if (type == EncryptType.AsyOneWay || type == EncryptType.AsyTwoWay) {
                byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(
                    FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher()
                );

                if (prikey == null) {
                    showToast(getString(R.string.no_private_key));
                    return;
                }

                cryptoDataByte.setPrikeyB(prikey);
            }

            // Step 4: Decrypt and show result
            decryptAndShowResult(cryptoDataByte);

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting cipher: " + e.getMessage());
            showToast(getString(R.string.error_decryption_failed) + ": " + e.getMessage());
        }
    }

    /**
     * Show symkey input dialog for decryption
     */
    private void showSymkeyInputDialogForDecrypt(CryptoDataByte cryptoDataByte) {
        StringInputDialog dialog = new StringInputDialog(
            this,
            getString(R.string.enter_symkey_prompt),
            getString(R.string.symkey),
            null,
            new StringInputDialog.OnInputListener() {
                @Override
                public void onConfirm(String input) {
                    if (TextUtils.isEmpty(input)) {
                        showToast(getString(R.string.symkey_cannot_be_empty));
                        return;
                    }
                    try {
                        byte[] symkey = Hex.fromHex(input);
                        cryptoDataByte.setSymkey(symkey);
                        decryptAndShowResult(cryptoDataByte);
                    } catch (Exception e) {
                        showToast(getString(R.string.error_invalid_symkey) + ": " + e.getMessage());
                    }
                }

                @Override
                public void onCancel() {
                    TimberLogger.i(TAG, "Symkey input cancelled for decryption");
                }
            }
        );
        dialog.show();
    }

    /**
     * Show password input dialog for decryption
     */
    private void showPasswordInputDialogForDecrypt(CryptoDataByte cryptoDataByte) {
        PasswordInputDialog dialog = new PasswordInputDialog(
            this,
            getString(R.string.enter_password_prompt),
            getString(R.string.password),
            new PasswordInputDialog.OnPasswordInputListener() {
                @Override
                public void onConfirm(String password) {
                    if (TextUtils.isEmpty(password)) {
                        showToast(getString(R.string.password_cannot_be_empty));
                        return;
                    }
                    cryptoDataByte.setPassword(password.getBytes());
                    decryptAndShowResult(cryptoDataByte);
                }

                @Override
                public void onCancel() {
                    TimberLogger.i(TAG, "Password input cancelled for decryption");
                }
            }
        );
        dialog.show();
    }

    /**
     * Decrypt CryptoDataByte and show the result
     */
    private void decryptAndShowResult(CryptoDataByte cryptoDataByte) {
        try {
            Decryptor decryptor = new Decryptor();
            cryptoDataByte = decryptor.decrypt(cryptoDataByte);

            if (cryptoDataByte.getCode() == CodeMessage.Code0Success) {
                String result = new String(cryptoDataByte.getData());
                updateResultText(result);
                copyButton.setEnabled(true);
                TimberLogger.i(TAG, "Decryption successful");
            } else {
                String errorMsg = getString(R.string.error_decryption_failed);
                if (cryptoDataByte.getMessage() != null) {
                    errorMsg += ": " + cryptoDataByte.getMessage();
                }
                showToast(errorMsg);
                TimberLogger.e(TAG, "Decryption failed: " + cryptoDataByte.getMessage());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error during decryption: " + e.getMessage());
            showToast(getString(R.string.error_decryption_failed) + ": " + e.getMessage());
        }
    }
}
