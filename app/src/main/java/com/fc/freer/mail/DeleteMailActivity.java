package com.fc.freer.mail;

import static com.fc.fc_ajdk.constants.IndicesNames.MAIL;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.MailOpData;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.SendTxActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.freer.manager.MailManager;
import com.fc.freer.utils.MailCardContainer;
import com.fc.freer.utils.ChooseMode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DeleteMailActivity extends BaseCryptoActivity {
    private static final String TAG = "DeleteMailActivity";
    private static final int REQUEST_SEND_TX = 1001;

    private MailCardContainer mailCardContainer;
    private LinearLayout mailListContainer;
    private MailManager mailManager;

    private ImageButton deleteButton;
    private ScrollView mailScrollView;
    private TextView mailStatisticsTextView;

    private List<Mail> mailList = new ArrayList<>();
    private boolean isFailedDecryption = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_delete_mail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.delete_mails);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the mail details from intent
        String mailListJson = getIntent().getStringExtra("mailList");
        if (mailListJson == null || mailListJson.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_mails_provided_for_deletion));
            finish();
            return;
        }

        // Check if this is for failed decryption mails
        isFailedDecryption = getIntent().getBooleanExtra("isFailedDecryption", false);

        try {
            List<Mail> allMails = JsonUtils.listFromJson(mailListJson, Mail.class);
            if (allMails.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_valid_mails_found));
                finish();
                return;
            }

            // Filter mails to only include deletable ones (received mails where recipient equals live FID)
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (liveFid == null) {
                ToastUtils.makeText(this, getString(R.string.no_active_fid_available));
                finish();
                return;
            }

            mailList = allMails;

            if (mailList.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_deletable_mails_found));
                finish();
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing mail list: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.error_loading_mail_data));
            finish();
            return;
        }

        // Initialize MailManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                mailManager = MailManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize MailManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing MailManager with liveFid: " + e.getMessage());
        }

        if (mailManager == null) {
            ToastUtils.makeText(this, getString(R.string.mail_not_ready_try_later));
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
        loadMailCardList();
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        deleteButton = findViewById(R.id.delete_button);

        // Initialize scroll view
        mailScrollView = findViewById(R.id.mail_scroll_view);

        // Initialize statistics TextView
        mailStatisticsTextView = findViewById(R.id.mail_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // DeleteMailActivity doesn't use QR scanning functionality
    }

    private void loadMailCardList() {
        // Initialize the LinearLayout container for mail cards
        mailListContainer = findViewById(R.id.fragment_container);

        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

        // Create MailCardContainer with WITHOUT_CHOOSE mode (no checkbox, with clear button)
        mailCardContainer = new MailCardContainer(this, mailListContainer, ChooseMode.WITHOUT_CHOOSE_WITH_DELETE, liveFid);

        // Set up mail list change listener
        mailCardContainer.setOnMailListChangedListener(updatedMailList -> {
            updateUI(); // This calls updateButtonStates and updateStatistics
        });

        // Set up mail remove listener
        mailCardContainer.setOnMailRemoveListener(mail -> {
            // Remove from main list when cleared from card
            mailList.remove(mail);
        });

        // Add all mail cards to the manager
        for (Mail mail : mailList) {
            mailCardContainer.addMailCard(mail);
        }

        // Update UI after cards are loaded
        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasMails = mailCardContainer != null && !mailCardContainer.getMailList().isEmpty();

        // Delete button is enabled when there are mails in the container
        deleteButton.setEnabled(hasMails);
        deleteButton.setAlpha(hasMails ? 1.0f : 0.5f);
    }

    /**
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (mailStatisticsTextView == null || mailCardContainer == null) {
            return;
        }

        int containerSize = mailCardContainer.getMailList().size();

        StringBuilder statsText = new StringBuilder();
        statsText.append("Total: ").append(containerSize);

        mailStatisticsTextView.setText(statsText.toString());
        mailStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        deleteButton.setOnClickListener(v -> {
            if (mailCardContainer == null) return;
            List<Mail> mailsToDelete = new ArrayList<>(mailCardContainer.getMailList());
            if (mailsToDelete.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_mails_to_delete));
                return;
            }
            performDeleteOperation(mailsToDelete);
        });
    }

    private void refreshCardList() {
        if (mailCardContainer != null) {
            mailCardContainer.clearAll();

            // Re-add remaining mails from the main list
            for (Mail mail : mailList) {
                mailCardContainer.addMailCard(mail);
            }

            updateUI();
        }
    }

    private void performDeleteOperation(List<Mail> mailsToDelete) {
        // Separate off-chain and on-chain mails, delete off-chain ones first
        List<Mail> remainingOnChainMails = mailManager.deleteOffChainEntities(mailsToDelete);

        // Remove deleted off-chain mails from the UI
        if (remainingOnChainMails.size() < mailsToDelete.size()) {
            int deletedCount = mailsToDelete.size() - remainingOnChainMails.size();
            ToastUtils.makeText(this, String.format(getString(R.string.deleted_off_chain_mails_locally), deletedCount));
        }

        String me = FidManager.getInstance().getLiveFid();
        Iterator<Mail> iterator = remainingOnChainMails.iterator();

        List<Mail> locallyRemovedMails = new ArrayList<>();
        while(iterator.hasNext()){
            Mail mail = iterator.next();
            if(!mail.getTo().equals(me)){
                locallyRemovedMails.add(mail);
                iterator.remove();
            }
        }

        if(!locallyRemovedMails.isEmpty()) {
            mailManager.removeMails(locallyRemovedMails);
            mailManager.addToLocalDeletedList(locallyRemovedMails);
            ToastUtils.makeText(this, String.format(getString(R.string.deleted_on_chain_mails_locally), locallyRemovedMails.size()));
        }

        // Commit the off-chain deletions
        mailManager.commit();

        // If there are no on-chain mails to delete, we're done
        if (remainingOnChainMails.isEmpty()) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        // For remaining on-chain mails, proceed with blockchain deletion
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        // Extract IDs for the delete operation
        List<String> mailIds = new ArrayList<>();
        for (Mail mail : remainingOnChainMails) {
            if (mail.getId() != null) {
                mailIds.add(mail.getId());
            }
        }

        if (mailIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_mail_ids));
            return;
        }

        // Create FEIP for delete operation
        String feipJson = makeDeleteMailFeip(mailIds);

        // Create RawTxInfo for the deletion transaction
        new Thread(() -> {
            try {
                CashManager cashManager = CashManager.getInstance();
                TxHandler txHandler = new TxHandler();

                // Get valid cash for the transaction (FEIP transactions need minimum CD and fee)
                List<Cash> validCashList = cashManager.getValidCashes(0.0, Feip.CD_REQUIRED, 0,
                    feipJson.getBytes().length, TxHandler.DEFAULT_FEE_RATE, null, this);

                if (validCashList == null || validCashList.isEmpty()) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(DeleteMailActivity.this, getString(R.string.no_valid_cash_available_for_transaction));
                    });
                    return;
                }

                // Create RawTxInfo for the FEIP transaction
                RawTxInfo rawTxInfo = new RawTxInfo(liveKeyInfo.getId(), validCashList, null, feipJson,
                    Feip.CD_REQUIRED, TxHandler.DEFAULT_FEE_RATE, null, RawTxInfo.VERSION_2);

                if (rawTxInfo != null) {
                    runOnUiThread(() -> {
                        // Launch SendTxActivity with the transaction
                        Intent sendTxIntent = new Intent(DeleteMailActivity.this, SendTxActivity.class);
                        sendTxIntent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toNiceJson());
                        startActivityForResult(sendTxIntent, REQUEST_SEND_TX);
                    });
                } else {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(DeleteMailActivity.this, getString(R.string.failed_to_create_transaction));
                    });
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error creating delete transaction: " + e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(DeleteMailActivity.this, getString(R.string.error_creating_transaction, e.getMessage()));
                });
            }
        }).start();
    }

    private static String makeDeleteMailFeip(List<String> mailIds) {
        Feip feip = Feip.fromName(MAIL);
        MailOpData mailOpData = MailOpData.makeDelete(mailIds);
        feip.setData(mailOpData);
        return feip.toJson();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SEND_TX) {
            if (resultCode == SendTxActivity.RESULT_SIGNED && data != null) {
                String txId = data.getStringExtra(SendTxActivity.EXTRA_RESULT_TXID);
                if (txId != null && !txId.isEmpty()) {
                    // Transaction was successfully sent
                    // Remove deleted mails from database
                    List<Mail> mailsToDelete = new ArrayList<>(mailCardContainer.getMailList());
                    mailManager.removeMails(mailsToDelete);
                    mailManager.commit();

                    ToastUtils.makeText(DeleteMailActivity.this, getString(R.string.mails_deleted_successfully_on_chain));
                    setResult(Activity.RESULT_OK);
                    finish();
                } else {
                    ToastUtils.makeText(DeleteMailActivity.this, getString(R.string.invalid_txid));
                }
            } else {
                // Transaction was cancelled or failed
                ToastUtils.makeText(DeleteMailActivity.this, getString(R.string.failed));
            }
        }
    }
}