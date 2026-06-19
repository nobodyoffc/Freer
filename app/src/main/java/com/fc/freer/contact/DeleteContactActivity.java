package com.fc.freer.contact;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.ContactOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.utils.ContactCardContainer;
import com.fc.freer.utils.ChooseMode;

import java.util.ArrayList;
import java.util.List;

public class DeleteContactActivity extends BaseCryptoActivity {
    private static final String TAG = "DeleteContactActivity";

    private ContactCardContainer contactCardContainer;
    private LinearLayout contactListContainer;
    private ContactManager contactManager;

    private ImageButton cancelButton;
    private ImageButton deleteButton;
    private ScrollView contactScrollView;
    private TextView contactStatisticsTextView;

    private List<Contact> contactList = new ArrayList<>();
    private boolean isFailedDecryption = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_delete_contact;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.delete_contacts);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the contact details from intent
        String contactListJson = getIntent().getStringExtra("contactList");
        if (contactListJson == null || contactListJson.isEmpty()) {
            ToastUtils.makeText(this, "No contacts provided for deletion");
            finish();
            return;
        }

        // Check if this is for failed decryption contacts
        isFailedDecryption = getIntent().getBooleanExtra("isFailedDecryption", false);

        try {
            contactList = JsonUtils.listFromJson(contactListJson, Contact.class);
            if (contactList.isEmpty()) {
                ToastUtils.makeText(this, "No valid contacts found");
                finish();
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing contact list: %s", e.getMessage());
            ToastUtils.makeText(this, "Error loading contact data");
            finish();
            return;
        }

        // Initialize ContactManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                contactManager = ContactManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ContactManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ContactManager with liveFid: " + e.getMessage());
        }

        if (contactManager == null) {
            ToastUtils.makeText(this, getString(R.string.contact_not_ready_try_later));
            finish();
            return;
        }

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
        loadContactCardList();
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        deleteButton = findViewById(R.id.delete_button);

        // Initialize scroll view
        contactScrollView = findViewById(R.id.contact_scroll_view);

        // Initialize statistics TextView
        contactStatisticsTextView = findViewById(R.id.contact_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // DeleteContactActivity doesn't use QR scanning functionality
    }

    private void loadContactCardList() {
        // Initialize the LinearLayout container for contact cards
        contactListContainer = findViewById(R.id.fragment_container);

        // Create ContactCardContainer with WITHOUT_CHOOSE mode (no checkbox, clear icon to remove)
        contactCardContainer = new ContactCardContainer(this, contactListContainer, ChooseMode.WITHOUT_CHOOSE);

        // Set up contact list change listener
        contactCardContainer.setOnContactListChangedListener(updatedContactList -> {
            updateUI();
        });

        // Set up contact remove listener
        contactCardContainer.setOnContactRemoveListener(contact -> {
            // Remove from the main list when removed from UI
            contactList.remove(contact);
        });

        // Add all contact cards to the manager
        for (Contact contact : contactList) {
            contactCardContainer.addContactCard(contact);
        }

        // Update UI after cards are loaded
        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasContacts = contactCardContainer != null && !contactCardContainer.getContactList().isEmpty();

        // Delete button is enabled when there are contacts in the container
        deleteButton.setEnabled(hasContacts);
        deleteButton.setAlpha(hasContacts ? 1.0f : 0.5f);
    }

    /**
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (contactStatisticsTextView == null || contactCardContainer == null) {
            return;
        }

        int containerSize = contactCardContainer.getContactList().size();

        StringBuilder statsText = new StringBuilder();
        statsText.append("Total: ").append(containerSize);

        contactStatisticsTextView.setText(statsText.toString());
        contactStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        deleteButton.setOnClickListener(v -> {
            if (contactCardContainer == null) return;
            List<Contact> contactsToDelete = new ArrayList<>(contactCardContainer.getContactList());
            if (contactsToDelete.isEmpty()) {
                ToastUtils.makeText(this, "No contacts to delete");
                return;
            }
            performDeleteOperation(contactsToDelete);
        });
    }

    private void performDeleteOperation(List<Contact> contactsToDelete) {
        // Separate off-chain and on-chain contacts, delete off-chain ones first
        List<Contact> remainingOnChainContacts = contactManager.deleteOffChainEntities(contactsToDelete);

        // Commit the off-chain deletions
        contactManager.commit();

        // Remove deleted off-chain contacts from the UI
        if (remainingOnChainContacts.size() < contactsToDelete.size()) {
            int deletedCount = contactsToDelete.size() - remainingOnChainContacts.size();
            ToastUtils.makeText(this, "Deleted " + deletedCount + " off-chain contacts locally");
        }

        // If there are no on-chain contacts to delete, we're done
        if (remainingOnChainContacts.isEmpty()) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        // For remaining on-chain contacts, proceed with blockchain deletion
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, "No active key available");
            return;
        }

        // Extract IDs for the delete operation
        List<String> contactIds = new ArrayList<>();
        for (Contact contact : remainingOnChainContacts) {
            if (contact.getId() != null) {
                contactIds.add(contact.getId());
            }
        }

        if (contactIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_contact_ids));
            return;
        }

        // Create FEIP for delete operation
        String feipJson = makeDeleteContactFeip(contactIds);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            // Send transaction on blockchain
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            // Remove deleted contacts from database
                            contactManager.removeContacts(remainingOnChainContacts);

                            contactManager.commit();

                            ToastUtils.makeText(DeleteContactActivity.this, "Contacts deleted successfully on-chain");
                            setResult(Activity.RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(DeleteContactActivity.this, "Failed to delete contacts on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(DeleteContactActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(DeleteContactActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(DeleteContactActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            // Delete locally without blockchain transaction
            contactManager.removeContacts(remainingOnChainContacts);

            contactManager.commit();

            ToastUtils.makeText(this, "Contacts deleted locally");
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    private static String makeDeleteContactFeip(List<String> contactIds) {
        Feip feip = Feip.fromName(CONTACT);
        ContactOpData contactOpData = ContactOpData.makeDelete(contactIds);
        feip.setData(contactOpData);
        return feip.toJson();
    }
}