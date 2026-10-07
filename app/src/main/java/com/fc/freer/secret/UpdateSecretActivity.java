package com.fc.freer.secret;

import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;

import android.app.Activity;
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
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.CarvePlan;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;

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
import com.fc.fc_ajdk.utils.TimberLogger;

public class UpdateSecretActivity extends BaseCryptoActivity {
    private static final String TAG = "UpdateSecretActivity";

    private LinearLayout secretInfoContainer;
    private LinearLayout inputContainer;
    private LinearLayout buttonContainer;
    private TextInputEditText titleInput;
    private TextInputEditText contentInput;
    private TextInputEditText memoInput;
    private AutoCompleteTextView typeInput;
    private ImageButton clearButton;
    private ImageButton updateButton;
    private ImageButton carveButton;
    private Button newRandomButton;

    // Define request codes for QR scan if not already defined
    private static final int QR_SCAN_TITLE_REQUEST_CODE = 1001;
    private static final int QR_SCAN_CONTENT_REQUEST_CODE = 1002;
    private static final int QR_SCAN_MEMO_REQUEST_CODE = 1003;
    private static final int QR_SCAN_TYPE_REQUEST_CODE = 1004;

    private Secret originalSecret;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_update_secret;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_secret);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the secret detail from intent
        String secretJson = getIntent().getStringExtra("secret");
        if (secretJson == null || secretJson.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.toast_no_secret_data));
            finish();
            return;
        }

        try {
            originalSecret = Secret.fromJson(secretJson, Secret.class);
            if (originalSecret == null) {
                ToastUtils.makeText(this, getString(R.string.toast_invalid_secret_data));
                finish();
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing secret data: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.toast_error_loading_secret));
            finish();
            return;
        }

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
        populateFields();

        // Setup scan icons for the three input boxes
        TextIconsUtils.setupTextIcons(this, R.id.titleView, R.id.scanIcon, QR_SCAN_TITLE_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.contentView, R.id.scanIcon, QR_SCAN_CONTENT_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.memoView, R.id.scanIcon, QR_SCAN_MEMO_REQUEST_CODE);
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
        updateButton = findViewById(R.id.updateButton);
        carveButton = findViewById(R.id.carveButton);
        newRandomButton = findViewById(R.id.newRandomButton);
    }

    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearInputs());
        // Save keeps the change on this device; carve puts it on chain. An item that is on
        // chain, or whose carve is pending, can only be carved: a local edit of it would be
        // replaced by the chain copy, and would make it look local-only.
        updateButton.setOnClickListener(v -> saveSecretToDatabase());
        if (originalSecret != null && !CarvePlan.isLocalOnly(originalSecret.getOnChain(), originalSecret.getCarveTime())) {
            updateButton.setVisibility(View.GONE);
        }
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

    private void populateFields() {
        if (originalSecret == null) {
            return;
        }

        // Set title
        if (originalSecret.getTitle() != null) {
            titleInput.setText(originalSecret.getTitle());
        }

        // Set memo
        if (originalSecret.getMemo() != null) {
            memoInput.setText(originalSecret.getMemo());
        }

        // Set type
        if (originalSecret.getType() != null) {
            typeInput.setText(originalSecret.getType());
        }

        // Decrypt and set content
        decryptAndPopulateContent();
    }

    private void decryptAndPopulateContent() {
        if (originalSecret == null || originalSecret.getContentCipher() == null) {
            return;
        }

        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
                ToastUtils.makeText(this, getString(R.string.toast_no_active_key_decrypt));
                return;
            }

            String prikeyCipher = fidManager.getLiveKeyInfo().getPrikeyCipher();

            // Perform decryption in background thread
            new Thread(() -> {
                try {
                    // Get private key without user confirmation dialog
                    byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(prikeyCipher);

                    if (prikey == null) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(this, getString(R.string.toast_failed_get_private_key));
                        });
                        return;
                    }

                    // Decrypt the content
                    String decryptedContent = originalSecret.decryptContent(prikey);

                    // Erase prikey immediately after use
                    BytesUtils.clearByteArray(prikey);

                    // Set content on main thread
                    runOnUiThread(() -> {
                        if (decryptedContent != null && !decryptedContent.isEmpty()) {
                            contentInput.setText(decryptedContent);
                        } else {
                            ToastUtils.makeText(this, getString(R.string.toast_failed_decrypt_content));
                        }
                    });

                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error decrypting content: %s", e.getMessage());
                    runOnUiThread(() -> {
                        ToastUtils.makeText(this, getString(R.string.toast_error_decrypting_content, e.getMessage()));
                    });
                }
            }).start();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initiating content decryption: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.toast_error_initiating_decryption, e.getMessage()));
        }
    }

    private void clearInputs() {
        titleInput.setText("");
        contentInput.setText("");
        memoInput.setText("");
        typeInput.setText("");
    }

    /**
     * The secret as edited, with plain content, or null after telling the user what is wrong.
     * The id is the original one.
     */
    private Secret readEditedSecret() {
        String title = titleInput.getText() != null ? titleInput.getText().toString() : "";
        String content = contentInput.getText() != null ? contentInput.getText().toString() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString() : "";
        String typeDisplay = typeInput.getText() != null ? typeInput.getText().toString() : "";

        if(typeDisplay.equals("TOTP" )) {
            if(!Base32.isBase32(content)) {
                ToastUtils.makeText(this, getString(R.string.toast_totp_update_base32));
                return null;
            }
        }

        if ( content.isEmpty() ) {
            ToastUtils.makeText(this, R.string.please_fill_required_fields);
            return null;
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
            return null;
        }
        String type = selectedType.getDisplayName(selectedType);

        Secret updatedSecret = new Secret();
        updatedSecret.setId(originalSecret.getId()); // Keep original ID
        if(!title.isEmpty()) updatedSecret.setTitle(title);
        if(!memo.isEmpty()) updatedSecret.setMemo(memo);
        if(!type.isEmpty()) updatedSecret.setType(type);
        updatedSecret.setContent(content);
        return updatedSecret;
    }

    private void saveSecretToDatabase() {
        Secret updatedSecret = readEditedSecret();
        if (updatedSecret == null) return;
        String pubkey = FidManager.getInstance().getLiveKeyInfo().getPubkey();

        // Encrypt content and save to database only (no blockchain operation)
        encryptContent(updatedSecret.getContent(), pubkey, updatedSecret);
        updatedSecret.setContent(null); // Clear content after encryption
        updatedSecret.setOnChain(false); // Mark as off-chain
        updatedSecret.setLastHeight(Constants.MaX_HEIGHT); // Set update height to a high value
        updatedSecret.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(),DateUtils.TO_MINUTE));

        SecretManager secretManager = SecretManager.getInstance();
        secretManager.updateSecret(updatedSecret);
        secretManager.commit();

        ToastUtils.makeText(this, getString(R.string.toast_secret_updated));
        setResult(Activity.RESULT_OK);
        finish();
    }

    /**
     * Carve the edited secret, as an 'update' or an 'add' as {@link CarvePlan} decides. After an
     * add the local row is re-keyed to the txid.
     */
    private void carveSecret() {
        Secret updatedSecret = readEditedSecret();
        if (updatedSecret == null) return;
        String content = updatedSecret.getContent();

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String pubkey = liveKeyInfo.getPubkey();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.toast_failed_get_private_key));
            return;
        }
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.toast_failed_check_on_chain));
            return;
        }

        new Thread(() -> {
            CarvePlan plan = CarvePlan.decide(fapiClient, SECRET, Secret.class, originalSecret.getId(),
                    originalSecret.getOnChain(), originalSecret.getCarveTime(), liveKeyInfo.getId(), Secret::getOwner, Secret::getActive);
            if (plan.op == null) {
                runOnUiThread(() -> ToastUtils.makeText(this, getString(plan.blockedMessage)));
                return;
            }
            boolean isUpdate = plan.isUpdate();
            String feipJson = isUpdate ? makeUpdateSecretFeip(updatedSecret, pubkey) : makeAddSecretFeip(updatedSecret, pubkey);

            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), fapiClient, new TxSender.TxCallback() {
                @Override
                public void onSuccess(String txId) {
                    runOnUiThread(() -> {
                        SecretManager secretManager = SecretManager.getInstance();
                        if (!isUpdate) {
                            // A new carve: the secret is now known by its add txid.
                            secretManager.removeEntity(originalSecret);
                            updatedSecret.setId(txId);
                        }
                        updatedSecret.markCarvePending(); // pending until a block confirms it
                        encryptContent(content, pubkey, updatedSecret);
                        updatedSecret.setContent(null);
                        updatedSecret.setLastHeight(Constants.MaX_HEIGHT);
                        updatedSecret.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(),DateUtils.TO_MINUTE));

                        secretManager.updateSecret(updatedSecret);
                        secretManager.commit();

                        setResult(Activity.RESULT_OK);
                        finish();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(UpdateSecretActivity.this, getString(R.string.toast_failed_carve_feip, errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(UpdateSecretActivity.this, getString(R.string.toast_sign_tx_failed_unsigned));
                        txSender.showUnsignedTxAsQR(UpdateSecretActivity.this, rawTxInfo);
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        // Show signed transaction as QR code for manual broadcasting
                        txSender.showSignedTxAsQR(UpdateSecretActivity.this, signedTxHex);
                    });
                }
            });
        }).start();
    }

    private static String makeAddSecretFeip(Secret secret, String pubkey) {
        // The detail carries no id: the add's txid becomes the id.
        String id = secret.getId();
        secret.setId(null);
        String detail = secret.toJson();
        secret.setId(id);
        String secretDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(detail, pubkey).toJson();
        Feip feip = Feip.fromName(SECRET);
        feip.setData(SecretOpData.makeAdd(null, secretDetailCipher));
        return feip.toJson();
    }

    private static String makeUpdateSecretFeip(Secret secret, String pubkey) {
        String secretDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(secret.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(SECRET);
        SecretOpData secretOpData = SecretOpData.makeUpdate(secret.getId(), null, secretDetailCipher);
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