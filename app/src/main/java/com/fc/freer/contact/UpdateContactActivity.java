package com.fc.freer.contact;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.ContactOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.CarvePlan;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class UpdateContactActivity extends BaseCryptoActivity {
    private static final String TAG = "UpdateContactActivity";

    // Input Components
    private LinearLayout inputContainer;
    private ImageView contactAvatarImageView;
    private TextView fidDisplayTextView;
    private TextInputEditText titlesInput;
    private TextInputEditText memoInput;
    private CheckBox seeStatementCheckBox;
    private CheckBox seeWritingsCheckBox;

    // Action Buttons
    private ImageButton clearButton;
    private ImageButton updateButton;
    private ImageButton carveButton;

    // Define request codes for QR scan
    private static final int QR_SCAN_TITLES_REQUEST_CODE = 2001;
    private static final int QR_SCAN_MEMO_REQUEST_CODE = 2002;

    private Contact originalContact;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_update_contact;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_contact);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the contact detail from intent
        String contactJson = getIntent().getStringExtra("contactDetail");
        if (contactJson == null || contactJson.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.toast_no_contact_data));
            finish();
            return;
        }

        try {
            originalContact = Contact.fromJson(contactJson, Contact.class);
            if (originalContact == null) {
                ToastUtils.makeText(this, getString(R.string.toast_invalid_contact_data));
                finish();
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing contact data: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.toast_error_loading_contact));
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
        populateFields();

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.titlesView, R.id.scanIcon, QR_SCAN_TITLES_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.memoView, R.id.scanIcon, QR_SCAN_MEMO_REQUEST_CODE);
    }

    @Override
    protected void initializeViews() {
        // Input Components
        inputContainer = findViewById(R.id.inputContainer);
        contactAvatarImageView = findViewById(R.id.contactAvatarImageView);
        fidDisplayTextView = findViewById(R.id.fidDisplayTextView);

        View titlesView = findViewById(R.id.titlesView);
        View memoView = findViewById(R.id.memoView);

        titlesInput = titlesView.findViewById(R.id.textInput);
        titlesInput.setHint(R.string.input_titles_comma_separated);
        memoInput = memoView.findViewById(R.id.textInput);
        memoInput.setHint(getString(R.string.input_the_memo) + " (optional)");

        seeStatementCheckBox = findViewById(R.id.seeStatementCheckBox);
        seeWritingsCheckBox = findViewById(R.id.seeWritingsCheckBox);

        // Action Buttons
        clearButton = findViewById(R.id.clearButton);
        updateButton = findViewById(R.id.updateButton);
        carveButton = findViewById(R.id.carveButton);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearAllInputs());
        // Save keeps the change on this device; carve puts it on chain. A contact that is on
        // chain, or whose carve is pending, can only be carved: a local edit of it would be
        // replaced by the chain copy, and would make it look local-only.
        updateButton.setOnClickListener(v -> updateContactToDatabase());
        if (originalContact != null && !CarvePlan.isLocalOnly(originalContact.getOnChain(), originalContact.getCarveTime())) {
            updateButton.setVisibility(View.GONE);
        }
        carveButton.setOnClickListener(v -> carveContact());
    }

    private void populateFields() {
        if (originalContact == null) {
            return;
        }

        // Display FID (read-only)
        if (originalContact.getFid() != null) {
            fidDisplayTextView.setText( originalContact.getFid());

            // Load and display avatar
            loadAvatar(originalContact.getFid());
        }

        // Set titles
        if (originalContact.getTitles() != null && !originalContact.getTitles().isEmpty()) {
            StringBuilder titlesStr = new StringBuilder();
            for (int i = 0; i < originalContact.getTitles().size(); i++) {
                if (i > 0) titlesStr.append(", ");
                titlesStr.append(originalContact.getTitles().get(i));
            }
            titlesInput.setText(titlesStr.toString());
        }

        // Set memo
        if (originalContact.getMemo() != null) {
            memoInput.setText(originalContact.getMemo());
        }

        // Set checkboxes
        seeStatementCheckBox.setChecked(originalContact.getSeeStatement() != null ? originalContact.getSeeStatement() : true);
        seeWritingsCheckBox.setChecked(originalContact.getSeeWritings() != null ? originalContact.getSeeWritings() : true);
    }

    private void loadAvatar(String fid) {
        // Load avatar in background thread to avoid blocking UI
        new Thread(() -> {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                android.graphics.Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);

                // Update UI on main thread
                runOnUiThread(() -> {
                    if (avatarBitmap != null) {
                        contactAvatarImageView.setImageBitmap(avatarBitmap);

                        // Make avatar clickable to show full screen dialog
                        contactAvatarImageView.setOnClickListener(v ->
                            AvatarManager.showAvatarDialog(this, fid)
                        );
                    } else {
                        // Set a default placeholder if avatar generation failed
                        contactAvatarImageView.setBackgroundColor(android.graphics.Color.LTGRAY);
                        TimberLogger.w(TAG, "Failed to load avatar for FID: %s", fid);
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading avatar: %s", e.getMessage());
                runOnUiThread(() -> {
                    contactAvatarImageView.setBackgroundColor(android.graphics.Color.LTGRAY);
                });
            }
        }).start();
    }

    private void clearAllInputs() {
        titlesInput.setText("");
        memoInput.setText("");
        seeStatementCheckBox.setChecked(true);
        seeWritingsCheckBox.setChecked(true);
        ToastUtils.makeText(this, getString(R.string.cleared));
    }

    private void updateContactToDatabase() {
        String titlesText = titlesInput.getText() != null ? titlesInput.getText().toString().trim() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString().trim() : "";
        boolean seeStatement = seeStatementCheckBox.isChecked();
        boolean seeWritings = seeWritingsCheckBox.isChecked();

        // Parse titles by comma
        List<String> titlesList = new ArrayList<>();
        if (!titlesText.isEmpty()) {
            String[] titlesArray = titlesText.split(",");
            for (String title : titlesArray) {
                String trimmedTitle = title.trim();
                if (!trimmedTitle.isEmpty()) {
                    titlesList.add(trimmedTitle);
                }
            }
        }

        // Create updated contact detail
        Contact updatedContact = new Contact();
        updatedContact.setId(originalContact.getId()); // Keep original ID
        updatedContact.setFid(originalContact.getFid()); // Keep original FID
        updatedContact.setPubkey(originalContact.getPubkey()); // Keep original pubkey
        updatedContact.setCid(originalContact.getCid()); // Keep original CID
        updatedContact.setNoticeFee(originalContact.getNoticeFee()); // Keep original notice fee

        if (!titlesList.isEmpty()) updatedContact.setTitles(titlesList);
        if (!memo.isEmpty()) updatedContact.setMemo(memo);
        updatedContact.setSeeStatement(seeStatement);
        updatedContact.setSeeWritings(seeWritings);

        // Update locally without blockchain transaction
        updatedContact.setOnChain(false); // Mark as off-chain
        updatedContact.setLastHeight(Constants.MaX_HEIGHT); // Set update height to a high value

        ContactManager contactManager = ContactManager.getInstance();
        boolean existed = contactManager.checkIfExisted(updatedContact.getId());
        if (existed) {
            DialogUtils.show(new AlertDialog.Builder(this)
                    .setTitle(R.string.contact_existed_title)
                    .setMessage(R.string.contact_will_be_updated)
                    .setPositiveButton(R.string.update, (dialog, which) -> {
                        contactManager.updateContact(updatedContact);
                        contactManager.commit();
                        ToastUtils.makeText(this, getString(R.string.toast_contact_updated));
                        setResult(Activity.RESULT_OK);
                        finish();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    );
        } else {
            // Should not happen for update, but handle gracefully
            contactManager.addContact(updatedContact);
            contactManager.commit();
            ToastUtils.makeText(this, getString(R.string.toast_contact_updated));
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    private void carveContact() {
        String titlesText = titlesInput.getText() != null ? titlesInput.getText().toString().trim() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString().trim() : "";
        boolean seeStatement = seeStatementCheckBox.isChecked();
        boolean seeWritings = seeWritingsCheckBox.isChecked();

        // Parse titles by comma
        List<String> titlesList = new ArrayList<>();
        if (!titlesText.isEmpty()) {
            String[] titlesArray = titlesText.split(",");
            for (String title : titlesArray) {
                String trimmedTitle = title.trim();
                if (!trimmedTitle.isEmpty()) {
                    titlesList.add(trimmedTitle);
                }
            }
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String pubkey = liveKeyInfo.getPubkey();

        // Create updated contact detail
        Contact updatedContact = new Contact();
        updatedContact.setId(originalContact.getId()); // Keep original ID for update
        updatedContact.setFid(originalContact.getFid());
        if (!titlesList.isEmpty()) updatedContact.setTitles(titlesList);
        if (!memo.isEmpty()) updatedContact.setMemo(memo);
        updatedContact.setSeeStatement(seeStatement);
        updatedContact.setSeeWritings(seeWritings);

        // Keep original contact properties
        updatedContact.setCid(originalContact.getCid());
        updatedContact.setPubkey(originalContact.getPubkey());
        if (originalContact.getNoticeFee() != null) {
            updatedContact.setNoticeFee(originalContact.getNoticeFee());
        }

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

        // An 'update' or an 'add' as CarvePlan decides; after an add the row is re-keyed.
        new Thread(() -> {
            CarvePlan plan = CarvePlan.decide(fapiClient, CONTACT, Contact.class, originalContact.getId(),
                    originalContact.getOnChain(), originalContact.getCarveTime(), liveKeyInfo.getId(), Contact::getOwner, Contact::getActive);
            if (plan.op == null) {
                runOnUiThread(() -> ToastUtils.makeText(this, getString(plan.blockedMessage)));
                return;
            }
            boolean isUpdate = plan.isUpdate();
            String feipJson = isUpdate ? makeUpdateContactFeip(updatedContact, pubkey) : makeAddContactFeip(updatedContact, pubkey);

            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), fapiClient, new TxSender.TxCallback() {
                @Override
                public void onSuccess(String txId) {
                    runOnUiThread(() -> {
                        ContactManager contactManager = ContactManager.getInstance();
                        if (!isUpdate) {
                            // A new carve: the contact is now known by its add txid.
                            contactManager.removeContact(originalContact);
                            updatedContact.setId(txId);
                        }
                        updatedContact.markCarvePending(); // pending until a block confirms it
                        encryptContactContent(updatedContact, pubkey);
                        updatedContact.setLastHeight(Constants.MaX_HEIGHT);

                        contactManager.updateContact(updatedContact);
                        contactManager.commit();

                        setResult(Activity.RESULT_OK);
                        finish();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(UpdateContactActivity.this, getString(R.string.toast_failed_carve_feip, errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(UpdateContactActivity.this, getString(R.string.toast_sign_tx_failed_unsigned));
                        txSender.showUnsignedTxAsQR(UpdateContactActivity.this, rawTxInfo);
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        // Show signed transaction as QR code for manual broadcasting
                        txSender.showSignedTxAsQR(UpdateContactActivity.this, signedTxHex);
                    });
                }
            });
        }).start();
    }

    private static String makeAddContactFeip(Contact contact, String pubkey) {
        // The detail carries no id: the add's txid becomes the id.
        String id = contact.getId();
        contact.setId(null);
        String detail = contact.toJson();
        contact.setId(id);
        String contactDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(detail, pubkey).toJson();
        Feip feip = Feip.fromName(CONTACT);
        feip.setData(ContactOpData.makeAdd(null, contactDetailCipher));
        return feip.toJson();
    }

    private static String makeUpdateContactFeip(Contact contact, String pubkey) {
        String contactDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(contact.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(CONTACT);
        ContactOpData contactOpData = ContactOpData.makeUpdate(contact.getId(), null, contactDetailCipher);
        feip.setData(contactOpData);
        return feip.toJson();
    }

    private static void encryptContactContent(Contact contact, String pubkey) {
        if (pubkey == null || contact == null) return;
        // Encrypt contact details
        String contactCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(contact.toJson(), pubkey).toJson();
        contact.setCipher(contactCipher);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TITLES_REQUEST_CODE) {
            titlesInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_MEMO_REQUEST_CODE) {
            memoInput.setText(qrContent);
        }
    }
}