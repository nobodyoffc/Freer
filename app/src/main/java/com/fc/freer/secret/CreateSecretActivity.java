package com.fc.freer.secret;

import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.SecretOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.utils.Base32;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.fc.freer.manager.SecretManager;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.fc_ajdk.utils.BytesUtils;
import android.widget.ImageButton;

public class CreateSecretActivity extends BaseCryptoActivity {
    private LinearLayout secretInfoContainer;
    private LinearLayout inputContainer;
    private LinearLayout buttonContainer;
    private TextInputEditText titleInput;
    private TextInputEditText contentInput;
    private TextInputEditText memoInput;
    private AutoCompleteTextView typeInput;
    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton carveButton;
    private Button newRandomButton;

    // Define request codes for QR scan if not already defined
    private static final int QR_SCAN_TITLE_REQUEST_CODE = 1001;
    private static final int QR_SCAN_CONTENT_REQUEST_CODE = 1002;
    private static final int QR_SCAN_MEMO_REQUEST_CODE = 1003;
    private static final int QR_SCAN_TYPE_REQUEST_CODE = 1004;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_secret;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_secret);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up root layout touch listener to hide keyboard
        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        // Setup toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        setupTypeDropdown();

        // Setup scan icons for the three input boxes
        TextIconsUtils.setupTextIcons(this, R.id.titleView, R.id.scanIcon, QR_SCAN_TITLE_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.contentView, R.id.scanIcon, QR_SCAN_CONTENT_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.memoView, R.id.scanIcon, QR_SCAN_MEMO_REQUEST_CODE);

        // Handle type lock from intent
        String typeFromIntent = getIntent().getStringExtra("type");
        if (typeFromIntent != null && !typeFromIntent.isEmpty()) {
            typeInput.setText(typeFromIntent);
            typeInput.setEnabled(false);
            typeInput.setFocusable(false);
            typeInput.setFocusableInTouchMode(false);
            typeInput.setOnClickListener(null);
            typeInput.setKeyListener(null);
            typeInput.setLongClickable(false);
            typeInput.setCursorVisible(false);
            // Optionally, visually indicate it's locked (e.g., gray out)
            typeInput.setTextColor(getResources().getColor(android.R.color.darker_gray, getTheme()));
        }
    }

    protected void initializeViews() {
        secretInfoContainer = findViewById(R.id.secretInfoContainer);
        inputContainer = findViewById(R.id.inputContainer);
        buttonContainer = findViewById(R.id.buttonContainer);

        View titleView = findViewById(R.id.titleView);
        View contentView = findViewById(R.id.contentView);
        View memoView = findViewById(R.id.memoView);

        titleInput = titleView.findViewById(R.id.textInput);
        titleInput.setHint(R.string.input_the_title);
        contentInput = contentView.findViewById(R.id.textInput);
        contentInput.setHint(R.string.input_the_content);
        memoInput = memoView.findViewById(R.id.textInput);
        memoInput.setHint(getString(R.string.input_the_memo) + " (optional)");

        typeInput = findViewById(R.id.typeInput);
        typeInput.setHint(R.string.select_the_type);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        carveButton = findViewById(R.id.carveButton);
        newRandomButton = findViewById(R.id.newRandomButton);
    }

    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearInputs());
        saveButton.setOnClickListener(v -> saveSecretToDatabase());
        carveButton.setOnClickListener(v -> carveSecret());
        newRandomButton.setOnClickListener(v -> generateRandomContent());
    }

    private void setupTypeDropdown() {
        Secret.Type[] types = Secret.Type.values();
        String[] displayNames = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            displayNames[i] = types[i].displayName;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, displayNames);
        typeInput.setAdapter(adapter);
        typeInput.setThreshold(1);
    }

    private void clearInputs() {
        titleInput.setText("");
        contentInput.setText("");
        memoInput.setText("");
        typeInput.setText("");
    }

    private void saveSecretToDatabase() {
        String title = titleInput.getText() != null ? titleInput.getText().toString() : "";
        String content = contentInput.getText() != null ? contentInput.getText().toString() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString() : "";
        String typeDisplay = typeInput.getText() != null ? typeInput.getText().toString() : "";

        if(typeDisplay.equals("TOTP" )) {
            if(!Base32.isBase32(content)) {
                ToastUtils.makeText(this, getString(R.string.toast_totp_save_base32));
                return;
            }
        }

        if ( content.isEmpty() ) {
            ToastUtils.makeText(this, R.string.please_fill_required_fields);
            return;
        }

        // Map display name back to enum name
        Secret.Type selectedType = null;
        for (Secret.Type t : Secret.Type.values()) {
            if (t.displayName.equals(typeDisplay)) {
                selectedType = t;
                break;
            }
        }
        if (selectedType == null) {
            ToastUtils.makeText(this, R.string.invalid_type_selected);
            return;
        }
        String type = selectedType.getDisplayName(selectedType);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String pubkey = liveKeyInfo.getPubkey();

        Secret secret = new Secret();
        if(!title.isEmpty()) secret.setTitle(title);
        if(!memo.isEmpty()) secret.setMemo(memo);
        if(!type.isEmpty()) secret.setType(type);
        secret.setContent(content); // Store plain content for encryption

        // Encrypt content and save to database only (no blockchain operation)
        encryptContent(content, pubkey, secret);
        secret.setContent(null); // Clear content after encryption
        secret.setOnChain(false); // Mark as off-chain
        secret.setLastHeight(Constants.MaX_HEIGHT); // Set update height to a high value
        secret.checkIdWithCreate();

        SecretManager secretManager = SecretManager.getInstance();
        boolean existed = secretManager.checkIfExisted(secret.getId());
        if (existed) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.secret_existed_title)
                    .setMessage(R.string.secret_already_exists_message)
                    .setPositiveButton(R.string.replace, (dialog, which) -> {
                        secretManager.saveAndFinish(this, secret,false);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            secretManager.saveAndFinish(this, secret,false);
        }
    }

    private void carveSecret() {
        String title = titleInput.getText() != null ? titleInput.getText().toString() : "";
        String content = contentInput.getText() != null ? contentInput.getText().toString() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString() : "";
        String typeDisplay = typeInput.getText() != null ? typeInput.getText().toString() : "";

        if(typeDisplay.equals("TOTP" )) {
            if(!Base32.isBase32(content)) {
                ToastUtils.makeText(this, getString(R.string.toast_totp_save_base32));
                return;
            }
        }

        if ( content.isEmpty() ) {
            ToastUtils.makeText(this, R.string.please_fill_required_fields);
            return;
        }

        // Map display name back to enum name
        Secret.Type selectedType = null;
        for (Secret.Type t : Secret.Type.values()) {
            if (t.displayName.equals(typeDisplay)) {
                selectedType = t;
                break;
            }
        }
        if (selectedType == null) {
            ToastUtils.makeText(this, R.string.invalid_type_selected);
            return;
        }
        String type = selectedType.getDisplayName(selectedType);
        
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String pubkey = liveKeyInfo.getPubkey();

        Secret secret = new Secret();
        if(!title.isEmpty()) secret.setTitle(title);
        if(!memo.isEmpty()) secret.setMemo(memo);
        if(!type.isEmpty()) secret.setType(type);
        secret.setContent(content); // Optionally store plain content

        String feipJson = makeAddSecretFeip(secret, pubkey);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if(prikey!=null) {

            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            secret.setId(txId);
                            secret.setOnChain(null);
                            encryptContent(content, pubkey, secret);
                            secret.setContent(null);
                            secret.setLastHeight(Constants.MaX_HEIGHT);
                            SecretManager.getInstance().saveAndFinish(CreateSecretActivity.this, secret,true);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(CreateSecretActivity.this, getString(R.string.toast_failed_carve_feip, errorMessage));
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(CreateSecretActivity.this, getString(R.string.toast_sign_tx_failed_unsigned));
                            txSender.showUnsignedTxAsQR(CreateSecretActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(CreateSecretActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        }else {

            secret.checkIdWithCreate();

            SecretManager secretManager = SecretManager.getInstance();

            boolean existed = secretManager.checkIfExisted(secret.getId());
            if (existed) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.secret_existed_title)
                        .setMessage(R.string.secret_already_exists_message)
                        .setPositiveButton(R.string.replace, (dialog, which) -> {
                            secretManager.saveAndFinish(this, secret,true);
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            } else {

                encryptContent(content, pubkey, secret);
                // Set content to null before saving to db
                secret.setContent(null);
                secret.setOnChain(null);
                secretManager.saveAndFinish(this, secret,true);
            }
        }
    }

    private static String makeAddSecretFeip(Secret secret, String pubkey) {
        String secretDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(secret.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(SECRET);
        SecretOpData secretOpData = SecretOpData.makeAdd(null,secretDetailCipher);
        feip.setData(secretOpData);
        String feipJson = feip.toJson();
        return feipJson;
    }

    private static void encryptContent(String content, String pubkey, Secret secret) {
        if(pubkey==null||content==null|| secret ==null)return;
        // Encrypt content
        String contentCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(content,pubkey).toJson();
        secret.setContentCipher(contentCipher);
    }

    private void generateRandomContent() {
        byte[] randomBytes = BytesUtils.getRandomBytes(16);
        String base32 = Base32.toBase32(randomBytes);
        contentInput.setText(base32);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TITLE_REQUEST_CODE) {
            titleInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_CONTENT_REQUEST_CODE) {
            contentInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_MEMO_REQUEST_CODE) {
            memoInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_TYPE_REQUEST_CODE) {
            typeInput.setText(qrContent);
        }
        // If requestCode does not match, do nothing
    }
} 