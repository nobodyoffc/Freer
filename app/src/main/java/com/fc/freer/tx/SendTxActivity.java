package com.fc.freer.tx;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxCreator;
import com.fc.fc_ajdk.data.apipData.TxInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.CashMark;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.home.BaseCryptoActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.core.crypto.Hash;

import java.util.List;

public class SendTxActivity extends BaseCryptoActivity {
    private static final String TAG = "SendTxActivity";
    public static final int RESULT_SIGNED = 1001;
    private KeyInfo senderKeyInfo;
    private String rawTx;
    private List<Cash> inputCashList;
    private TxInfo txInfo;
    private LinearLayout fragmentContainer;
    private LinearLayout buttonContainer;
    private Button makeQrButton;
    private Button copyButton;
    private Button sendButton;
    private String signedTx = null;
    private RawTxInfo rawTxInfo;
    private boolean isSent = false;

    public static final String EXTRA_TX_INFO_JSON = "extra_tx_info_json";

    @Override
    protected int getLayoutId() {
        return R.layout.activity_send_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.sign_tx);
    }

    @Override
    protected void initializeViews() {
        // Get data from intent
        String offLineTxInfoJson = getIntent().getStringExtra(EXTRA_TX_INFO_JSON);
        if(offLineTxInfoJson == null){
            Toast.makeText(this, getString(R.string.the_tx_is_empty), FreerApplication.TOAST_LASTING).show();
            finish();
            return;
        }

        try {
            rawTxInfo = RawTxInfo.fromJson(offLineTxInfoJson, RawTxInfo.class);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing RawTxInfo JSON: " + e.getMessage());
            Toast.makeText(this, getString(R.string.invalid_transaction_data), FreerApplication.TOAST_LASTING).show();
            finish();
            return;
        }

        if(rawTxInfo == null){
            Toast.makeText(this, getString(R.string.the_tx_is_empty), FreerApplication.TOAST_LASTING).show();
            finish();
            return;
        }

        senderKeyInfo = rawTxInfo.getSenderInfo();
        rawTx = TxCreator.createUnsignedTx(rawTxInfo);
        inputCashList = rawTxInfo.getInputs();
        
        if (senderKeyInfo == null || rawTx == null || inputCashList == null) {
            finish();
            return;
        }
        
        txInfo = TxInfo.fromRawTx(rawTx, inputCashList);

        fragmentContainer = findViewById(R.id.fragmentContainer);
        buttonContainer = findViewById(R.id.buttonContainer);
        makeQrButton = findViewById(R.id.makeQrButton);
        copyButton = findViewById(R.id.copyButton);
        sendButton = findViewById(R.id.signButton);

        setupSender();
        setupSendTo();
        setupText();
        setupSummary();

        updateButtonTexts();
    }

    @Override
    protected void setupButtons() {
        makeQrButton.setOnClickListener(v -> {
            if (signedTx != null) {
                handleQrGeneration(signedTx);
            }
        });

        copyButton.setOnClickListener(v -> {
            if (isSent && signedTx != null) {
                copyToClipboard(signedTx, "tx_id");
            }else{
                copyToClipboard(rawTxInfo.toNiceJson(), "raw_tx");
            }
        });

        sendButton.setOnClickListener(v -> {
            if(isSent) {
                setResult(RESULT_SIGNED);
                finish();
                return;
            }

            // Use the shared transaction sending method from BaseCryptoActivity
            sendTransaction(rawTxInfo, senderKeyInfo, new TxSendCallback() {
                @Override
                public void onSuccess(String txId) {
                    isSent = true;
                    signedTx = txId; // Store the txId as result
                    Toast.makeText(SendTxActivity.this, getString(R.string.transaction_sent_successfully) + ": " + txId, FreerApplication.TOAST_LASTING).show();
                    updateButtonTexts();
                }

                @Override
                public void onError(String errorMessage) {
                    Toast.makeText(SendTxActivity.this, getString(R.string.failed_to_send_transaction) + ": " + errorMessage, FreerApplication.TOAST_LASTING).show();
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    Toast.makeText(SendTxActivity.this, getString(R.string.failed_to_get_private_key), FreerApplication.TOAST_LASTING).show();
                }
            });
        });
    }

    public void updateButtonTexts() {
        if(isSent){
            copyButton.setText(R.string.copy_result);
            sendButton.setText(R.string.done);
        } else{
            copyButton.setText(R.string.copy_tx);
            sendButton.setText(R.string.send);
        }
    }

    @Override
    protected void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast(getString(R.string.copied_to_clipboard));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void setupSender() {
        if (senderKeyInfo == null || senderKeyInfo.getId() == null || senderKeyInfo.getId().isEmpty()) {
            return;
        }
        createFidCard(senderKeyInfo, fragmentContainer, true);
    }

    private void setupSendTo() {
        if (txInfo.getIssuedCashes() == null || txInfo.getIssuedCashes().isEmpty()) {
            return;
        }
        addLabel(getString(R.string.send_to) + ":");
        for (CashMark cashMark : txInfo.getIssuedCashes()) {
            TxOutputCard card = new TxOutputCard(this);
            SendTo sendTo = new SendTo(cashMark.getOwner(), FchUtils.satoshiToCoin(cashMark.getValue()));
            card.setSendTo(sendTo, this, false, false);
            fragmentContainer.addView(card);
        }
    }

    private void setupText() {
        if (txInfo.getOpReBrief() != null && !txInfo.getOpReBrief().isEmpty()) {
            addTextLine(getString(R.string.carving) + ": " + txInfo.getOpReBrief());
        }
    }

    private void setupSummary() {

        // Spending sum
        long spendingSum = 0;
        long cdd = 0;
        for (Cash cash : inputCashList) {
            spendingSum += cash.getValue();
            if(cash.getCd()!=null)cdd+=cash.getCd();
        }
        addTextLine(getString(R.string.sum) + ": " + FchUtils.satoshiToCoin(spendingSum) + " F");

        // Paying sum
        long payingSum = 0;
        if (txInfo.getIssuedCashes() != null) {
            for (CashMark cashMark : txInfo.getIssuedCashes()) {
                payingSum += cashMark.getValue();
            }
        }
//        addTextLine(getString(R.string.paying_sum) + ": " + FchUtils.satoshiToCoin(payingSum) + " F");

        // Fee
        long fee = spendingSum - payingSum;
        addTextLine(getString(R.string.fee) + ": " + FchUtils.satoshiToCash(fee) + " c");

        // CDD
        addTextLine(getString(R.string.cdd) + ": " + cdd);
    }

    private void addTextLine(String text) {
        if(text==null || text.isEmpty()) return;
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setPadding(0, 16, 0, 16);

        String[] parts = text.split(": ", 2);
        if (parts.length == 2) {
            TextView fieldName = new TextView(this);
            fieldName.setText(parts[0] + ": ");
            fieldName.setTextColor(getResources().getColor(R.color.field_name, getTheme()));
            fieldName.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView fieldValue = new TextView(this);
            fieldValue.setText(parts[1]);
            fieldValue.setTextColor(getResources().getColor(R.color.text_color, getTheme()));
            fieldValue.setTypeface(null, android.graphics.Typeface.NORMAL);
            fieldValue.setOnClickListener(v -> copyToClipboard(parts[1], "summary_value"));
            fieldValue.setClickable(true);
            fieldValue.setFocusable(true);

            rowLayout.addView(fieldName);
            rowLayout.addView(fieldValue);
        } else {
            TextView fieldValue = new TextView(this);
            fieldValue.setText("\t"+text);
            fieldValue.setTextColor(getResources().getColor(R.color.text_color, getTheme()));
            fieldValue.setTypeface(null, android.graphics.Typeface.NORMAL);
            fieldValue.setOnClickListener(v -> copyToClipboard(text, "summary_value"));
            fieldValue.setClickable(true);
            fieldValue.setFocusable(true);
            rowLayout.addView(fieldValue);
        }

        fragmentContainer.addView(rowLayout);
    }

    private void addLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getResources().getColor(R.color.field_name, getTheme()));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, 16, 0, 8);
        fragmentContainer.addView(label);
    }

    /**
     * Updates the cash database after a transaction is signed
     * @param signedTx The signed transaction hex string
     * @param txInfo The transaction info containing all outputs including change
     */
    private void updateCashDB(String signedTx, TxInfo txInfo) {
        if (signedTx == null || txInfo == null) {
            TimberLogger.e(TAG, "updateCashDB: signedTx or txInfo is null");
            return;
        }

        try {
            // 1. Calculate signedTxId = Hex.toHex(Hash.sha256x2(Hex.fromHex(signedTx)))
            String signedTxId = Hex.toHex(Hash.sha256x2(Hex.fromHex(signedTx)));
            TimberLogger.i(TAG, "updateCashDB: signedTxId = %s", signedTxId);

            // Get CashManager instance
            CashManager cashManager = CashManager.getInstance();

            // 2. Remove spent cash from CashManager's localDB
            if (txInfo.getSpentCashes() != null) {
                for (CashMark spentCashMark : txInfo.getSpentCashes()) {
                    if (spentCashMark.getId() != null) {
                        cashManager.removeCash(spentCashMark.getId());
                    }
                }
            }

            // 3. Check issuedCashes and create new cash for own addresses
            if (txInfo.getIssuedCashes() != null) {
                for (int i = 0; i < txInfo.getIssuedCashes().size(); i++) {
                    CashMark cashMark = txInfo.getIssuedCashes().get(i);
                    
                    // Check if this output belongs to any of our KeyInfo addresses
                    if (cashMark.getOwner() != null && SettingManager.getInstance().getCurrentKeyInfo().getId().equals(cashMark.getOwner())) {
                        // Create new cash for our own address
                        Cash newCash = new Cash();
                        newCash.setBirthTxId(signedTxId);
                        newCash.setBirthIndex(i);
                        newCash.setBirthTime(System.currentTimeMillis() / 1000 + 300); // Current time + 5 minutes (300 seconds)
                        newCash.setValue(cashMark.getValue());
                        newCash.setOwner(cashMark.getOwner());
                        newCash.setValid(true);
                        
                        // Generate cash ID using makeId method
                        String cashId = newCash.makeId();
                        newCash.setId(cashId);
                        
                        // Add to CashManager's DB
                        cashManager.addValidCash(newCash);
                        TimberLogger.i(TAG, "updateCashDB: Added new cash with ID: %s, owner: %s, amount: %s", 
                            cashId, cashMark.getOwner(), FchUtils.satoshiToCoin(cashMark.getValue()));
                    }
                }
            }

            // Commit changes to database
            cashManager.commit();
            TimberLogger.i(TAG, "updateCashDB: Successfully updated cash database");

        } catch (Exception e) {
            TimberLogger.e(TAG, "updateCashDB: Error updating cash database: %s", e.getMessage());
        }
    }
} 