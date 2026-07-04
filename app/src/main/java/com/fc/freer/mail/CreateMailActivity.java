package com.fc.freer.mail;

import static com.fc.fc_ajdk.constants.IndicesNames.MAIL;
import static com.fc.freer.manager.MailManager.PAY_BACK_NOTICE_FEE;
import static com.fc.freer.manager.MailManager.MAX_PAYING_NOTICE_FEE;

import android.app.AlertDialog;
import android.content.Intent;
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
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.data.feipData.MailOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.contact.ChooseContactActivity;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.MailManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;


public class CreateMailActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateMailActivity";

    // Recipient Selection Components
    private LinearLayout recipientCardContainer;
    private View recipientSelectionView;
    private TextInputEditText recipientInput;
    private IoIconsView recipientIcons;

    // Input Components
    private TextInputEditText contentInput;

    // Custom Notice Fee Components
    private CheckBox customNoticeFeeCheckbox;
    private LinearLayout customNoticeFeeContainer;
    private TextInputEditText customNoticeFeeInput;

    // Action Buttons
    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton carveButton;

    // Recipient Selection State
    private Contact selectedContact = null;
    private WaitingDialog waitingDialog;
    private Double customNoticeFee = null;

    // Define request codes for QR scan and contact selection
    private static final int QR_SCAN_CONTENT_REQUEST_CODE = 3001;
    private static final int CHOOSE_CONTACT_REQUEST_CODE = 3002;
    private Long gotNoticeFeeLong = null;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_mail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_mail);
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
        setupData();

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.contentView, R.id.scanIcon, QR_SCAN_CONTENT_REQUEST_CODE);

        // Setup recipient selection icons
        setupRecipientSelectionIcons();

        // Handle reply intent extras
        handleReplyIntent();
    }

    @Override
    protected void initializeViews() {
        // Recipient Selection Components
        recipientCardContainer = findViewById(R.id.recipientCardContainer);
        recipientSelectionView = findViewById(R.id.recipientSelectionView);
        recipientInput = recipientSelectionView.findViewById(R.id.keyInput);
        recipientInput.setHint(R.string.choose_from_contact_or_input_fid_or_pubkey);
        recipientInput.setFocusable(true);
        recipientInput.setClickable(true);

        // Add text change listener to handle FID/pubkey input
        recipientInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                String input = s.toString().trim();
                if (!input.isEmpty() && selectedContact == null) {
                    // Only process if no contact is currently selected and input is not empty
                    makeContactFromInput(input);
                }
            }
        });

        recipientIcons = recipientSelectionView.findViewById(R.id.peopleAndScanIcons);

        // Input Components

        View contentView = findViewById(R.id.contentView);
        contentInput = contentView.findViewById(R.id.textInput);
        contentInput.setHint(R.string.input_mail_content);

        // Custom Notice Fee Components
        customNoticeFeeCheckbox = findViewById(R.id.customNoticeFeeCheckbox);
        customNoticeFeeContainer = findViewById(R.id.customNoticeFeeContainer);
        customNoticeFeeInput = findViewById(R.id.customNoticeFeeInput);

        // Setup checkbox listener to show/hide custom notice fee input
        customNoticeFeeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                customNoticeFeeContainer.setVisibility(View.VISIBLE);
            } else {
                customNoticeFeeContainer.setVisibility(View.GONE);
                customNoticeFeeInput.setText("");
                customNoticeFee = null;
            }
        });

        // Action Buttons
        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        carveButton = findViewById(R.id.carveButton);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearAllInputs());
        saveButton.setOnClickListener(v -> saveMailToDatabase());
        carveButton.setOnClickListener(v -> sendMail());
    }

    private void setupData() {
        // No special data setup needed for contact selection
    }

    private void handleReplyIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            // Handle pre-filled recipient FID
            String recipientFid = intent.getStringExtra("recipientFid");
            if (recipientFid != null && !recipientFid.trim().isEmpty()) {
                // Create contact from FID and set as selected
                makeContactFromInput(recipientFid);
            }

            // Handle pre-filled reply content
            String replyContent = intent.getStringExtra("replyContent");
            if (replyContent != null && !replyContent.trim().isEmpty()) {
                contentInput.setText(replyContent);
                // Position cursor at the end for user to continue typing
                contentInput.setSelection(replyContent.length());
            }

            // Handle pre-filled reply content
            String gotNoticeFee = intent.getStringExtra("gotNoticeFee");
            if (gotNoticeFee != null && !gotNoticeFee.trim().isEmpty() && !gotNoticeFee.equals("null")) {
                gotNoticeFeeLong = Long.valueOf(gotNoticeFee);
            }
        }
    }

    private void setupRecipientSelectionIcons() {
        if (recipientIcons != null) {
            // Show only people and scan icons, hide make QR and file icons
            recipientIcons.init(this, false, true, true, true,false);
            recipientIcons.setSingleChoice(true);

            // Set up people icon click listener
            recipientIcons.setOnPeopleClickListener(isSingleChoice -> {
                Intent intent = new Intent(this, ChooseContactActivity.class);
                intent.putExtra(ChooseContactActivity.EXTRA_CHOOSE_MODE, com.fc.freer.utils.ChooseMode.CHOOSE_ONE_RETURN.name());
                startActivityForResult(intent, CHOOSE_CONTACT_REQUEST_CODE);
            });

            // Set up scan icon click listener for QR scanning
            recipientIcons.setOnScanClickListener(() -> {
                IoIconsView.launchQrScanner(this, QR_SCAN_CONTENT_REQUEST_CODE + 1); // Different request code for recipient scan
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_CONTENT_REQUEST_CODE) {
            contentInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_CONTENT_REQUEST_CODE + 1) {
            // Handle recipient QR scan result - could be FID or contact info
            handleRecipientQrScan(qrContent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CHOOSE_CONTACT_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String contactJson = data.getStringExtra(ChooseContactActivity.EXTRA_SELECTED_CONTACT);
            if (contactJson != null) {
                try {
                    selectedContact = Contact.fromJson(contactJson, Contact.class);
                    displaySelectedContact();
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error parsing selected contact: %s", e.getMessage());
                    ToastUtils.makeText(this, getString(R.string.error_selecting_contact));
                }
            }
        }
    }

    private void handleRecipientQrScan(String qrContent) {

        try {
            // First try to parse as Contact JSON
            Contact jsonContact = Contact.fromJson(qrContent, Contact.class);
            if (jsonContact != null && jsonContact.getFid() != null && !jsonContact.getFid().trim().isEmpty()) {
                selectedContact = jsonContact;
                displaySelectedContact();
                return;
            }

            makeContactFromInput(qrContent);

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error handling recipient QR scan: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.failed));
        }
    }

    private boolean makeContactFromInput(String input) {
        String targetFid =null;
        // Then check if it's a valid FID
        String pubkey =null;
        if (KeyTools.isGoodFid(input)) {
            targetFid = input;
        }
        // Or if it's a pubkey, convert to FID
        else if (KeyTools.isPubkey(input)) {
            pubkey = KeyTools.getPubkey33(input);
            targetFid = KeyTools.pubkeyToFchAddr(pubkey);
        } else {
            ToastUtils.makeText(this, getString(R.string.invalid_input));
            return false;
        }

        if (targetFid == null || targetFid.trim().isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return false;
        }

        // Check if contact already exists in local database
        ContactManager contactManager = ContactManager.getInstance();
        Contact existingContact = contactManager.getContactByFid(targetFid);

        if (existingContact != null) {
            // Contact exists, use it directly
            selectedContact = existingContact;
            displaySelectedContact();
            return true;
        }

        // Contact doesn't exist, try to fetch from blockchain
        // Pass pubkey to fallback handler in case blockchain fetch fails
        fetchContactFromBlockchain(targetFid, pubkey);

        // Return true to indicate async operation is in progress
        return true;
    }

    private void fetchContactFromBlockchain(String fid, String pubkey) {
        // Show waiting dialog
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, getString(R.string.fetching_cid_from_chain));
        }
        waitingDialog.show();

        new Thread(() -> {
            try {
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                Freer freerInfo = fapiClient.getFreer(fid);

                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (freerInfo != null) {
                        // Successfully fetched from blockchain
                        Contact contact =Contact.fromCid(freerInfo);
                        contact.setOnChain(false);
                        contact.setLastHeight(Constants.MaX_HEIGHT);

                        // Save CID mapping if available
                        if (freerInfo.getCid() != null && !freerInfo.getCid().trim().isEmpty()) {
                            CidFidManager cidFidManager = CidFidManager.getInstance(this);
                            cidFidManager.add(fid, freerInfo.getCid());
                        }

                        // Save contact to database
                        ContactManager contactManager = ContactManager.getInstance();
                        if(!contactManager.checkIfFidExisted(contact.getFid()))
                            contactManager.addContact(contact);

                        selectedContact = contact;
                        displaySelectedContact();

                    } else {
                        // Failed to fetch from blockchain, try fallback with pubkey
                        handleBlockchainFetchFailure(fid, pubkey);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    // Failed to fetch from blockchain, try fallback with pubkey
                    handleBlockchainFetchFailure(fid, pubkey);
                });
            }
        }).start();
    }

    private void handleBlockchainFetchFailure(String fid, String pubkey) {
        if (pubkey != null) {
            // Create contact with pubkey for mail sending
            Contact contact = new Contact();
            contact.setFid(fid);
            contact.setPubkey(pubkey);
            contact.makeId();
            contact.setOnChain(false);
            contact.setLastHeight(Constants.MaX_HEIGHT);
            ToastUtils.makeText(this, getString(R.string.created_contact_with_pubkey_for_sending));

            selectedContact = contact;
            displaySelectedContact();
        } else {
            // FID not found on blockchain and no pubkey available
            ToastUtils.makeText(this, getString(R.string.fid_not_found_on_blockchain));
        }
    }

    // Recipient Selection Methods

    private void displaySelectedContact() {
        if (selectedContact == null) {
            return;
        }

        // Update the input field text
        String displayText = selectedContact.getName();
        if (displayText == null || displayText.trim().isEmpty()) {
            displayText = selectedContact.getFid();
        }
        recipientInput.setText(displayText);
        recipientInput.setClickable(false);
        recipientIcons.setFocusable(false);

        // Hide the recipient selection view and show the recipient card
        recipientSelectionView.setVisibility(View.GONE);
        showRecipientCard();

        ToastUtils.makeText(this, getString(R.string.recipient_selected));
    }

    private void showRecipientCard() {
        if (selectedContact == null || recipientCardContainer == null) {
            return;
        }

        // Clear existing cards
        recipientCardContainer.removeAllViews();

        // Inflate the contact card layout
        View cardView = getLayoutInflater().inflate(R.layout.item_contact_card, recipientCardContainer, false);

        // Set up the card data using contact card layout IDs
        ImageView contactAvatar = cardView.findViewById(R.id.contact_avatar);
        TextView contactName = cardView.findViewById(R.id.contact_name_value);
        TextView contactTitles = cardView.findViewById(R.id.contact_titles_value);
        ImageView onChainIcon = cardView.findViewById(R.id.contact_on_chain_icon);
        ImageButton editButton = cardView.findViewById(R.id.contact_edit_button);

        if(selectedContact.getFid()!=null){
            contactAvatar.setImageBitmap(AvatarManager.getInstance(this).getAvatarBitmap(selectedContact.getFid()));
        }

        // Set name
        String name = selectedContact.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "Unknown Contact";
        }
        contactName.setText(name);

        // Set FID in the titles field
        if(selectedContact.getTitles()!=null)contactTitles.setText(StringUtils.listToString(selectedContact.getTitles()));
        else if(selectedContact.getCid()!=null)contactTitles.setText(selectedContact.getFid());

        // Hide on-chain icon for mail recipients
        onChainIcon.setVisibility(View.GONE);

        // Convert edit button to remove button functionality
        editButton.setImageResource(R.drawable.ic_clear);
        editButton.setContentDescription(getString(R.string.remove));
        editButton.setOnClickListener(v -> clearRecipientSelection());

        // Add the card to the container
        recipientCardContainer.addView(cardView);
        recipientCardContainer.setVisibility(View.VISIBLE);
    }

    private void clearRecipientSelection() {
        selectedContact = null;
        recipientInput.setText("");
        recipientCardContainer.removeAllViews();
        recipientCardContainer.setVisibility(View.GONE);
        // Show the recipient selection view again
        recipientSelectionView.setVisibility(View.VISIBLE);
        recipientInput.setClickable(true);
        recipientInput.setFocusable(true);
        ToastUtils.makeText(this, getString(R.string.cleared));
    }


    // Input handling and action methods

    private void clearAllInputs() {
        contentInput.setText("");
        clearRecipientSelection();
        customNoticeFeeCheckbox.setChecked(false);
        customNoticeFeeInput.setText("");
        customNoticeFee = null;
        ToastUtils.makeText(this, getString(R.string.cleared));
    }

    private void saveMailToDatabase() {
        if (selectedContact == null) {
            ToastUtils.makeText(this, getString(R.string.please_select_recipient_first));
            return;
        }

        String content = contentInput.getText() != null ? contentInput.getText().toString().trim() : "";
        if (content.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_mail_content));
            return;
        }

        if(content.length()>Constants.MaxOpReturnSize){
            ToastUtils.makeText(this, getString(R.string.opreturn_size_can_not_be_larger_than_4kb));
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String senderFid = liveKeyInfo.getId();

        Mail mail = new Mail();
        mail.setFrom(senderFid);
        mail.setTo(selectedContact.getFid());
        mail.setContent(content);

        // Save to database only (no blockchain operation)
        mail.setOnChain(false); // Mark as off-chain
        mail.setLastHeight(Constants.MaX_HEIGHT); // Set update height to a high value
        mail.checkIdWithCreate();

        MailManager mailManager = MailManager.getInstance();
        boolean existed = mailManager.checkIfExisted(mail.getId());
        if (existed) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.mail_existed_title)
                    .setMessage(R.string.mail_already_exists_message)
                    .setPositiveButton(R.string.replace, (dialog, which) -> {
                        mailManager.addMail(mail);
            ToastUtils.makeText(this, getString(R.string.mail_saved_successfully));
            setResult(RESULT_OK);
            finish();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            mailManager.addMail(mail);
            ToastUtils.makeText(this, getString(R.string.mail_saved_successfully));
            setResult(RESULT_OK);
            finish();
        }
    }

    private void sendMail() {
        if (selectedContact == null) {
            ToastUtils.makeText(this, getString(R.string.please_select_recipient_first));
            return;
        }

        String content = contentInput.getText() != null ? contentInput.getText().toString().trim() : "";
        if (content.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_mail_content));
            return;
        }

        if(content.length()>Constants.MaxOpReturnSize){
            ToastUtils.makeText(this, getString(R.string.opreturn_size_can_not_be_larger_than_4kb));
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String senderFid = liveKeyInfo.getId();

        Mail mail = new Mail();
        mail.setFrom(senderFid);
        mail.setTo(selectedContact.getFid());
        mail.setContent(content);
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        mail.encryptContent(prikey,selectedContact.getPubkey());

        String feipJson = makeSendMailFeip(mail);

        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();

                // Calculate amount to send to recipient using MailManager
                double payNoticeFee;

                // Check if custom notice fee is set
                if (customNoticeFeeCheckbox.isChecked() && customNoticeFeeInput.getText() != null) {
                    String customFeeStr = customNoticeFeeInput.getText().toString().trim();
                    if (!customFeeStr.isEmpty()) {
                        try {
                            customNoticeFee = Double.parseDouble(customFeeStr);
                            if (customNoticeFee < 0) {
                                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.invalid_fee_value)));
                                return;
                            }
                            payNoticeFee = customNoticeFee;
                        } catch (NumberFormatException e) {
                            runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.invalid_amount_format)));
                            return;
                        }
                    } else {
                        runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.please_enter_notice_fee)));
                        return;
                    }
                } else {
                    MailManager mailManager = MailManager.getInstance();
                    payNoticeFee = mailManager.calculateMailFee(selectedContact.getFid(), this);
                    if(payNoticeFee==-1){
                        ToastUtils.makeText(this, getString(R.string.the_notice_fee_of_s_is_more_than_your_limit_d,selectedContact.getFid(),MailManager.getInstance().getSetting(MAX_PAYING_NOTICE_FEE)));
                        return;
                    }

                    if(mailManager.getSetting(PAY_BACK_NOTICE_FEE)!=null
                            && "true".equalsIgnoreCase((String)mailManager.getSetting(PAY_BACK_NOTICE_FEE))
                            && gotNoticeFeeLong!=null
                            && gotNoticeFeeLong > payNoticeFee)
                        payNoticeFee = gotNoticeFeeLong;
                }

                txSender.carveFeipWithRecipient(this, senderFid, selectedContact.getFid(), payNoticeFee, feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            mail.setId(txId);
                            mail.setOnChain(null);
                            mail.setBirthTime(System.currentTimeMillis()/1000);
                            mail.setLastHeight(Constants.MaX_HEIGHT);
                            MailManager mailManager = MailManager.getInstance();
                            mailManager.addMail(mail);
                            setResult(RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(CreateMailActivity.this, String.format(getString(R.string.failed_to_send_mail), errorMessage));
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(CreateMailActivity.this, getString(R.string.cannot_sign_transaction_showing_unsigned_tx));
                            txSender.showUnsignedTxAsQR(CreateMailActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(CreateMailActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();
        } else {
            mail.checkIdWithCreate();

            MailManager mailManager = MailManager.getInstance();
            boolean existed = mailManager.checkIfExisted(mail.getId());
            if (existed) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.mail_existed_title)
                        .setMessage(R.string.mail_already_exists_message)
                        .setPositiveButton(R.string.replace, (dialog, which) -> {
                            encryptMailContent(mail, liveKeyInfo.getPubkey(), selectedContact.getPubkey());
                            mail.setOnChain(null);
                            mailManager.addMail(mail);
                            ToastUtils.makeText(this, getString(R.string.mail_saved_successfully));
                            setResult(RESULT_OK);
                            finish();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            } else {
                if(selectedContact.getPubkey()==null){
                    FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                    try{
                        String pubkey = fapiClient.getPubkey(selectedContact.getFid());
                        if(pubkey==null)return;
                        selectedContact.setPubkey(pubkey);
                    }catch (Exception e){
                        ToastUtils.makeText(this,getString(R.string.failed_to_get_pubkey,e.getMessage()));
                        return;
                    }
                }
                encryptMailContent(mail, liveKeyInfo.getPubkey(), selectedContact.getPubkey());
                mail.setOnChain(null);
                mailManager.saveAndFinish(this, mail, true);
            }
        }
    }

    public static String makeSendMailFeip(Mail mail) {
        if(mail==null || mail.getCipher()==null)return null;
        Feip feip = Feip.fromName(MAIL);
        MailOpData mailOpData = MailOpData.makeSend(mail.getCipher());
        feip.setData(mailOpData);
        return feip.toJson();
    }

    private void encryptMailContent(Mail mail, String senderPubkey, String recipientPubkey) {
        if (senderPubkey == null || recipientPubkey == null || mail == null) return;
        FidManager fidManager = FidManager.getInstance();
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(fidManager.getLiveKeyInfo().getPrikeyCipher());
        CryptoDataByte cryptDataByte = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptByAsyTwoWay(mail.getContent().getBytes(), prikey, Hex.fromHex(recipientPubkey));
        if(cryptDataByte==null || cryptDataByte.getCode()!=0){
            ToastUtils.makeText(this,getString(R.string.failed_to_encrypt,cryptDataByte==null?"":cryptDataByte.getMessage()));
            return;
        }
        mail.setCipher(cryptDataByte.toJson());
        mail.setContent(null);
    }

    @Override
    protected void onDestroy() {
        dismissWaitingDialog();
        super.onDestroy();
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
            waitingDialog = null;
        }
    }
}