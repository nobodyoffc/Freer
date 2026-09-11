package com.fc.freer.tx;

import static com.fc.fc_ajdk.constants.FieldNames.RESULT;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Nobody;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.freer.nobody.NobodyUi;
import com.fc.fc_ajdk.data.fchData.Tx;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.CashMask;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.account.CashActivity;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.view.TxOutputCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SendTxActivity extends BaseCryptoActivity {
    private static final String TAG = "SendTxActivity";
    public static final int RESULT_SIGNED = 1001;
    private static final int REQUEST_BROADCAST = 1002;
    private static final int REQUEST_RESELECT_INPUTS = 1003;
    private KeyInfo senderKeyInfo;
    private String rawTx;
    private List<Cash> inputCashList;
    private Tx tx;
    private LinearLayout fragmentContainer;
    private LinearLayout buttonContainer;
    private ImageButton makeQrButton;
    private ImageButton copyButton;
    private ImageButton sendButton;
    private ImageButton reselectInputsButton;
    private String result = null;
    private RawTxInfo rawTxInfo;
    private boolean isSent = false;
    private boolean isBroadcastMode = false;
    // Inputs picked by hand: a spent-input error must not swap in other cash.
    private boolean inputsReselected = false;
    private Map<String, Nobody> fidNobodyMap;
    private long bestHeight = 0;
    private com.fc.freer.ui.WaitingDialog waitingDialog;

    public static final String EXTRA_TX_INFO_JSON = "extra_tx_info_json";
    public static final String EXTRA_RESULT_TXID = "extra_result_txid";

    @Override
    protected int getLayoutId() {
        return R.layout.activity_send_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.send_tx);
    }

    @Override
    protected void initializeViews() {
        // Get data from intent
        String rawTxInfoJson = getIntent().getStringExtra(EXTRA_TX_INFO_JSON);
        if(rawTxInfoJson == null){
            ToastUtils.showWarning(this, getString(R.string.the_tx_is_empty));
            finish();
            return;
        }

        try {
            rawTxInfo = RawTxInfo.fromJson(rawTxInfoJson, RawTxInfo.class);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing RawTxInfo JSON: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.invalid_transaction_data));
            finish();
            return;
        }

        if(rawTxInfo == null){
            ToastUtils.showWarning(this, getString(R.string.the_tx_is_empty));
            finish();
            return;
        }

        senderKeyInfo = rawTxInfo.getSenderInfo();
        if(senderKeyInfo == null){
            senderKeyInfo= FidManager.getInstance().getKeyInfoByFid(rawTxInfo.getSender());
            rawTxInfo.setSenderInfo(senderKeyInfo);
        }

        // Check if prikeyCipher is null or empty to determine broadcast mode
        if(senderKeyInfo != null && (senderKeyInfo.getPrikeyCipher() == null || senderKeyInfo.getPrikeyCipher().isEmpty())) {
            isBroadcastMode = true;
            ToastUtils.makeText(SendTxActivity.this,getString(R.string.no_prikey_copy_tx_sign_it_and_broadcast_it));
        }
        TxHandler txHandler = new TxHandler();
        rawTx = txHandler.createTxHex(rawTxInfo);
        inputCashList = rawTxInfo.getInputs();
        
        if (senderKeyInfo == null ||inputCashList == null) {
            finish();
            return;
        }
        
        tx = Tx.fromRawTx(rawTx, inputCashList);

        Set<String> toIdSet = new HashSet<>();
        for(CashMask cashMask : tx.getIssuedCashes()){
            toIdSet.add(cashMask.getOwner());
        }

        fragmentContainer = findViewById(R.id.fragmentContainer);
        buttonContainer = findViewById(R.id.buttonContainer);
        makeQrButton = findViewById(R.id.makeQrButton);
        copyButton = findViewById(R.id.copyButton);
        sendButton = findViewById(R.id.signButton);
        reselectInputsButton = findViewById(R.id.reselectInputsButton);

        // Debug logging to identify null buttons
        TimberLogger.d(TAG, "Button initialization - makeQrButton: %s, copyButton: %s, sendButton: %s",
            makeQrButton != null, copyButton != null, sendButton != null);

        // Check nobodies in background thread with loading dialog
        waitingDialog = new com.fc.freer.ui.WaitingDialog(this, getString(R.string.sending_transaction));
        waitingDialog.show();

        new Thread(() -> {
            try {
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                // Recipients and the sender; the lookup records nobodies in NobodyRegistry
                if (senderKeyInfo != null && senderKeyInfo.getId() != null) {
                    toIdSet.add(senderKeyInfo.getId());
                }
                fidNobodyMap = fapiClient.checkNobodies(new ArrayList<>(toIdSet));
                if (fapiClient != null && fapiClient.getBestHeight() != null) {
                    bestHeight = fapiClient.getBestHeight();
                }

                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    setupSender();
                    setupSendTo(bestHeight);
                    setupSummary();
                    setupText();
                    updateButtonTexts();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error checking nobodies: " + e.getMessage());
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    // Continue without nobody info
                    setupSender();
                    setupSendTo(bestHeight);
                    setupSummary();
                    setupText();
                    updateButtonTexts();
                });
            }
        }).start();
    }

    @Override
    protected void setupButtons() {
        if (reselectInputsButton != null) {
            reselectInputsButton.setOnClickListener(v -> openCashSelector());
        }

        if (makeQrButton != null) {
            makeQrButton.setOnClickListener(v -> {
                String contentToQr;
                if (result != null) {
                    contentToQr = result;
                } else {
                    contentToQr = rawTxInfo.toNiceJson();
                }
                handleQrGeneration(contentToQr);
            });
        }

        if (copyButton != null) {
            copyButton.setOnClickListener(v -> {
                if (result != null) {
                    copyToClipboard(result, RESULT);
                } else {
                    copyToClipboard(rawTxInfo.toNiceJson(), "raw_tx");
                }
            });
        }

        if (sendButton != null) {
            sendButton.setOnClickListener(v -> {
                if(isSent) {
                    if(waitingDialog!=null)
                        waitingDialog.show();

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(EXTRA_RESULT_TXID, result);
                    setResult(RESULT_SIGNED, resultIntent);

                    // Clear the TxSender callback when finishing
                    TxSender currentTxSender = TxSender.getCurrentInstance();
                    if (currentTxSender != null) {
                        currentTxSender.clearPendingCallback();
                    }

                    if(waitingDialog!=null && waitingDialog.isShowing())
                        waitingDialog.dismiss();
                    finish();
                    return;
                }

                if (isBroadcastMode) {
                    // Launch BroadcastActivity for result
                    Intent broadcastIntent = new Intent(SendTxActivity.this, com.fc.freer.convert.BroadcastActivity.class);
                    startActivityForResult(broadcastIntent, REQUEST_BROADCAST);
                    return;
                }

                // Before broadcasting: a "nobody" is a FID whose private key is public
                // on chain. Funds sent to one can be taken by anyone, and cash spent
                // from one can be spent first by anyone.
                String senderFid = senderKeyInfo != null ? senderKeyInfo.getId() : null;
                NobodyGuard.confirm(this, getRecipientFids(senderFid), R.string.nobody_consequence_send,
                        () -> NobodyGuard.confirm(this,
                                senderFid == null ? Collections.emptyList() : Collections.singletonList(senderFid),
                                R.string.nobody_consequence_send_from, this::performSend));
            });
        }
    }

    /** The output recipients, excluding change back to the sender. */
    private List<String> getRecipientFids(String senderFid) {
        List<String> fids = new ArrayList<>();
        if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
            for (Cash output : rawTxInfo.getOutputs()) {
                String owner = output.getOwner();
                if (owner != null && !owner.equals(senderFid)) fids.add(owner);
            }
        }
        return fids;
    }

    /**
     * Broadcasts the transaction via the shared sending method. Assumes any
     * pre-send confirmations (e.g. nobody-recipient warning) have already passed.
     */
    private void performSend() {
        if(waitingDialog!=null)
            waitingDialog.show();

        sendButton.setAlpha(0.5f);
        // Use the shared transaction sending method from BaseCryptoActivity
        sendTransaction(rawTxInfo, senderKeyInfo, inputsReselected, new TxSendCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        isSent = true;
                        result = txId; // Store the txId as result
                        // tx_sent toast is shown by TxSender on broadcast success; don't duplicate it here.
//                        updateButtonTexts();

                        // Notify TxSender if there's a pending callback
                        TxSender currentTxSender = TxSender.getCurrentInstance();
                        if (currentTxSender != null) {
                            currentTxSender.onActivityResult(txId);
                        }
//                        sendButton.setAlpha(1f);

                        Intent resultIntent = new Intent();
                        resultIntent.putExtra(EXTRA_RESULT_TXID, result);
                        setResult(RESULT_SIGNED, resultIntent);

                        if(waitingDialog!=null && waitingDialog.isShowing())
                            waitingDialog.dismiss();
                        finish();
                        return;
                    }

                    @Override
                    public void onError(String errorMessage) {
                        ToastUtils.showError(SendTxActivity.this, getString(R.string.failed_to_send_tx,errorMessage));

                        // Notify TxSender if there's a pending callback
                        TxSender currentTxSender = TxSender.getCurrentInstance();
                        if (currentTxSender != null) {
                            currentTxSender.onActivityError(errorMessage);
                        }
                        sendButton.setAlpha(1f);
                        if(waitingDialog.isShowing() && waitingDialog.isShowing())
                            waitingDialog.dismiss();
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        ToastUtils.showError(SendTxActivity.this, getString(R.string.failed_to_get_prikey));
                        sendButton.setAlpha(1f);
                        if(waitingDialog.isShowing() && waitingDialog.isShowing())
                            waitingDialog.dismiss();
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        result = signedTxHex; // Store the signed transaction hex in result field
                        ToastUtils.makeText(SendTxActivity.this, getString(R.string.tx_signed_but_not_broadcast));
                        updateButtonTexts();
                        sendButton.setAlpha(1f);
                        if(waitingDialog.isShowing() && waitingDialog.isShowing())
                            waitingDialog.dismiss();
                    }
                });
    }

    public void updateButtonTexts() {
        if (reselectInputsButton != null) {
            reselectInputsButton.setVisibility(isSent ? View.GONE : View.VISIBLE);
        }
        if(isSent){
            if (sendButton != null) {
                sendButton.setImageResource(R.drawable.ic_tick);
            }
        } else{
            if (sendButton != null) {
                if (isBroadcastMode) {
                    sendButton.setImageResource(R.drawable.ic_broadcast);

                } else {
                    sendButton.setImageResource(R.drawable.ic_send);
                }
            }
        }
    }

    @Override
    protected void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast(getString(R.string.copied));
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

    private void setupSendTo(long bestHeight) {
        if (tx.getIssuedCashes() == null || tx.getIssuedCashes().isEmpty()) {
            return;
        }

        // Check if ANY output has a lockTime (per-output CLTV check)
        boolean hasCltvOutput = false;
        if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
            for (Cash output : rawTxInfo.getOutputs()) {
                if (output.getLockTime() != null && output.getLockTime() > 0) {
                    hasCltvOutput = true;
                    break;
                }
            }
        }

        addLabel(getString(R.string.send_to) + ":");

        if (hasCltvOutput) {
            // For CLTV transactions, parse the script and match with rawTxInfo outputs
            // to show locktime per output
            addCltvRecipients();
        } else {
            // For regular transactions, match tx outputs with rawTxInfo to get lockTime

            for (CashMask cashMask : tx.getIssuedCashes()) {
                TxOutputCard card = new TxOutputCard(this);

                // Try to find matching Cash in rawTxInfo to get lockTime
                Cash matchedCash = null;
                if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
                    for (Cash output : rawTxInfo.getOutputs()) {
                        if (output.getOwner().equals(cashMask.getOwner()) &&
                            Math.abs(output.getAmount() - FchUtils.satoshiToCoin(cashMask.getValue())) < 0.00000001) {
                            matchedCash = output;
                            break;
                        }
                    }
                }

                Cash cash;
                if (matchedCash != null) {
                    cash = matchedCash;
                } else {
                    cash = new Cash(cashMask.getOwner(), FchUtils.satoshiToCoin(cashMask.getValue()));
                }

                boolean isNobody = isNobody(cashMask.getOwner());
                card.setSendTo(cash, this, false, false, isNobody);

                // Add lockTime display if this output has lockTime
                if (cash.getLockTime() != null && cash.getLockTime() > 0 && bestHeight > 0) {
                    card.setLockTime(cash.getLockTime(), bestHeight);
                }

                fragmentContainer.addView(card);
            }
        }
    }

    private boolean isNobody(String fid) {
        return NobodyUi.isNobody(fid);
    }

    private void setupText() {
        // Use the full opReturn data from rawTxInfo instead of the truncated opReBrief from tx
        String opReturn = rawTxInfo.getOpReturn();
        TimberLogger.d(TAG, "setupText: opReturn='%s'", opReturn);

        if (opReturn != null && !opReturn.isEmpty()) {
            TimberLogger.d(TAG, "setupText: Adding carving field with opReturn data");
            addCarvingContainer(opReturn);
        } else if (tx.getOpReBrief() != null && !tx.getOpReBrief().isEmpty()) {
            // Fallback to opReBrief if opReturn is not available
            TimberLogger.d(TAG, "setupText: Adding carving field with opReBrief data: '%s'", tx.getOpReBrief());
            addCarvingContainer(tx.getOpReBrief());
        } else {
            TimberLogger.d(TAG, "setupText: No carving data available");
        }

    }

    private void addCarvingContainer(String carvingValue) {
        if (carvingValue == null || carvingValue.isEmpty()) return;

        // Create label (outside container)
        TextView label = new TextView(this);
        label.setText(getString(R.string.carving) + ":");
        label.setTextColor(getResources().getColor(R.color.field_name, getTheme()));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextSize(16);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.setMargins(0, 16, 0, 8);
        label.setLayoutParams(labelParams);

        // Add label to fragment
        fragmentContainer.addView(label);

        // Create container with outline
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setBackgroundResource(R.drawable.container_outline);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        // Set margins for the container
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        containerParams.setMargins(0, 0, 0, 16);
        container.setLayoutParams(containerParams);

        // Create value text
        TextView valueText = new TextView(this);
        valueText.setText(carvingValue);
        valueText.setTextColor(getResources().getColor(R.color.text, getTheme()));
        valueText.setTextSize(16);
        valueText.setTypeface(null, android.graphics.Typeface.NORMAL);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        valueText.setLayoutParams(valueParams);
        valueText.setClickable(true);
        valueText.setFocusable(true);
        valueText.setOnClickListener(v -> copyToClipboard(carvingValue, "carving_value"));

        container.addView(valueText);

        // Check if value is JSON and add icon
        boolean isJson = isJsonString(carvingValue);
        if (isJson) {
            ImageView jsonIcon = new ImageView(this);
            jsonIcon.setImageResource(R.drawable.ic_json);
            int iconSize = (int) (32 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.setMargins((int)(8 * getResources().getDisplayMetrics().density), 0, 0, 0);
            jsonIcon.setLayoutParams(iconParams);
            jsonIcon.setColorFilter(getResources().getColor(R.color.accent, getTheme()));
            jsonIcon.setPadding(4, 4, 4, 4);
            jsonIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            jsonIcon.setClickable(true);
            jsonIcon.setFocusable(true);

            jsonIcon.setOnClickListener(v -> {
                try {
                    String niceJson = JsonUtils.strToNiceJson(carvingValue);
                    if (niceJson != null) {
                        showJsonDialog(getString(R.string.carving), niceJson);
                    } else {
                        ToastUtils.showWarning(this, getString(R.string.invalid_json_format));
                    }
                } catch (Exception e) {
                    ToastUtils.showError(this, getString(R.string.failed_to_parse_json) + ": " + e.getMessage());
                }
            });

            container.addView(jsonIcon);
        }

        // Add container to fragment
        fragmentContainer.addView(container);
    }

    private void setupSummary() {

        // Spending sum
        long spendingSum = 0;
        long cdd = 0;
        for (Cash cash : inputCashList) {
            spendingSum += cash.getValue();
            cdd += liveCd(cash);
        }
        addTextLine(getString(R.string.sum) + ": " + FchUtils.satoshiToCoin(spendingSum) + " F");

        // Paying sum
        long payingSum = 0;
        if (tx.getIssuedCashes() != null) {
            for (CashMask cashMask : tx.getIssuedCashes()) {
                payingSum += cashMask.getValue();
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
            fieldValue.setTextColor(getResources().getColor(R.color.text, getTheme()));
            fieldValue.setTypeface(null, android.graphics.Typeface.NORMAL);
            fieldValue.setOnClickListener(v -> copyToClipboard(parts[1], "summary_value"));
            fieldValue.setClickable(true);
            fieldValue.setFocusable(true);

            // Set layout params for field value to not take up all space if we have an icon
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            fieldValue.setLayoutParams(valueParams);

            // Check if the value contains JSON and add JSON icon if it does
            boolean isJsonValue = isJsonString(parts[1]);
            if (isJsonValue) {
                TimberLogger.d(TAG, "addTextLine: Adding JSON icon for field: %s", parts[0]);
                ImageView jsonIcon = new ImageView(this);
                jsonIcon.setImageResource(R.drawable.ic_json);
                int iconSize = (int) (32 * getResources().getDisplayMetrics().density); // Increased size
                TimberLogger.d(TAG, "addTextLine: Icon size calculated: %d pixels", iconSize);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                iconParams.setMargins((int)(12 * getResources().getDisplayMetrics().density), 0, 0, 0);
                iconParams.width = iconSize;
                iconParams.height = iconSize;
                jsonIcon.setLayoutParams(iconParams);
                jsonIcon.setClickable(true);
                jsonIcon.setFocusable(true);
                jsonIcon.setContentDescription(getString(R.string.carving));

                // Set explicit color tint to ensure visibility
                jsonIcon.setColorFilter(getResources().getColor(R.color.accent, getTheme()));
                jsonIcon.setPadding(8, 8, 8, 8); // Increased padding
                jsonIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                TimberLogger.d(TAG, "addTextLine: JSON icon created and configured");

                String finalValue = parts[1];
                jsonIcon.setOnClickListener(v -> {
                    try {
                        String niceJson = JsonUtils.strToNiceJson(finalValue);
                        if (niceJson != null) {
                            showJsonDialog(getString(R.string.carving), niceJson);
                        } else {
                            ToastUtils.showWarning(this, getString(R.string.invalid_json_format));
                        }
                    } catch (Exception e) {
                        ToastUtils.showError(this, getString(R.string.failed_to_parse_json) + ": " + e.getMessage());
                    }
                });

                rowLayout.addView(fieldName);
                rowLayout.addView(fieldValue);
                rowLayout.addView(jsonIcon);
                TimberLogger.d(TAG, "addTextLine: JSON icon added to layout. Child count: %d", rowLayout.getChildCount());
            } else {
                rowLayout.addView(fieldName);
                rowLayout.addView(fieldValue);
            }
        } else {
            TextView fieldValue = new TextView(this);
            fieldValue.setText("\t"+text);
            fieldValue.setTextColor(getResources().getColor(R.color.text, getTheme()));
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

    private void addCltvRecipients() {
        try {
            // Get raw transaction bytes
            byte[] rawTxBytes = com.fc.fc_ajdk.utils.Hex.fromHex(rawTx);
            org.bitcoinj.core.Transaction transaction = new org.bitcoinj.core.Transaction(
                org.bitcoinj.fch.FchMainNetwork.MAINNETWORK, rawTxBytes);

            // Parse each output to extract CLTV recipients
            List<org.bitcoinj.core.TransactionOutput> outputs = transaction.getOutputs();

            // For CLTV transactions:
            // - Outputs with lockTime in rawTxInfo.outputs are P2SH (time-locked)
            // - Regular outputs and change are P2PKH
            // We match P2SH outputs to rawTxInfo.outputs that have lockTime
            int outputIndex = 0;  // Index in rawTxInfo.outputs

            for (int i = 0; i < outputs.size(); i++) {
                org.bitcoinj.core.TransactionOutput output = outputs.get(i);
                org.bitcoinj.script.Script script = output.getScriptPubKey();

                // Skip OP_RETURN outputs
                if (script.isOpReturn()) {
                    continue;
                }

                // Check if this is a P2SH output (CLTV wrapped in P2SH)
                if (script.isPayToScriptHash()) {
                    // Match P2SH outputs to rawTxInfo.outputs that have lockTime
                    if (rawTxInfo.getOutputs() != null && outputIndex < rawTxInfo.getOutputs().size()) {
                        Cash matchedOutput = rawTxInfo.getOutputs().get(outputIndex);
                        outputIndex++;

                        TxOutputCard card = new TxOutputCard(this);

                        boolean isNobody = isNobody(matchedOutput.getOwner());

                        card.setSendTo(matchedOutput, this, false, false, isNobody);

                        // Add locktime display for P2SH outputs that have lockTime
                        if (matchedOutput.getLockTime() != null && matchedOutput.getLockTime() > 0 && bestHeight > 0) {
                            card.setLockTime(matchedOutput.getLockTime(), bestHeight);
                            TimberLogger.i(TAG, "Added P2SH CLTV recipient: %s, amount: %s, lockTime: %s",
                                matchedOutput.getOwner(), matchedOutput.getAmount(), matchedOutput.getLockTime());
                        } else {
                            TimberLogger.i(TAG, "Added P2SH recipient (no lockTime): %s, amount: %s",
                                matchedOutput.getOwner(), matchedOutput.getAmount());
                        }

                        fragmentContainer.addView(card);
                    } else {
                        // P2SH but not in outputs list - shouldn't happen
                        TimberLogger.w(TAG, "Found P2SH output but no more entries in rawTxInfo.outputs");
                    }
                    continue;
                }

                // This is a regular P2PKH output - could be change or regular output without lockTime
                try {
                    org.bitcoinj.core.Address address = script.getToAddress(
                        org.bitcoinj.fch.FchMainNetwork.MAINNETWORK);
                    String fid = address.toString();

                    TxOutputCard card = new TxOutputCard(this);

                    // Try to match with rawTxInfo.outputs for consistency
                    Cash matchedCash = null;
                    if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
                        for (Cash cashItem : rawTxInfo.getOutputs()) {
                            if (cashItem.getOwner().equals(fid) &&
                                Math.abs(cashItem.getAmount() - FchUtils.satoshiToCoin(output.getValue().getValue())) < 0.00000001) {
                                matchedCash = cashItem;
                                break;
                            }
                        }
                    }

                    Cash cash;
                    if (matchedCash != null) {
                        cash = matchedCash;
                    } else {
                        cash = new Cash(fid, FchUtils.satoshiToCoin(output.getValue().getValue()));
                    }

                    card.setSendTo(cash, this, false, false, isNobody(fid));

                    // Add lockTime display if this output has lockTime (though P2PKH shouldn't have CLTV)
                    if (cash.getLockTime() != null && cash.getLockTime() > 0 && bestHeight > 0) {
                        card.setLockTime(cash.getLockTime(), bestHeight);
                        TimberLogger.i(TAG, "Added P2PKH output with lockTime: %s, amount: %s, lockTime: %s",
                            fid, FchUtils.satoshiToCoin(output.getValue().getValue()), cash.getLockTime());
                    } else {
                        TimberLogger.i(TAG, "Added P2PKH output (no lockTime): %s, amount: %s",
                            fid, FchUtils.satoshiToCoin(output.getValue().getValue()));
                    }

                    fragmentContainer.addView(card);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error parsing P2PKH output: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing CLTV recipients: " + e.getMessage());
            // Fallback to showing normal outputs with lockTime from rawTxInfo
            for (CashMask cashMask : tx.getIssuedCashes()) {
                TxOutputCard card = new TxOutputCard(this);

                // Try to find matching Cash in rawTxInfo
                Cash matchedCash = null;
                if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
                    for (Cash output : rawTxInfo.getOutputs()) {
                        if (output.getOwner().equals(cashMask.getOwner()) &&
                            Math.abs(output.getAmount() - FchUtils.satoshiToCoin(cashMask.getValue())) < 0.00000001) {
                            matchedCash = output;
                            break;
                        }
                    }
                }

                Cash cash;
                if (matchedCash != null) {
                    cash = matchedCash;
                } else {
                    cash = new Cash(cashMask.getOwner(), FchUtils.satoshiToCoin(cashMask.getValue()));
                }

                card.setSendTo(cash, this, false, false,isNobody(cashMask.getOwner()));

                // Add lockTime display if available
                if (cash.getLockTime() != null && cash.getLockTime() > 0 && bestHeight > 0) {
                    card.setLockTime(cash.getLockTime(), bestHeight);
                }

                fragmentContainer.addView(card);
            }
        }
    }

    /**
     * Check if a FID and amount combination exists in rawTxInfo.outputs
     * If not found, it's a change output
     */
    private boolean isInOutputs(String fid, long satoshiValue) {
        if (rawTxInfo == null || rawTxInfo.getOutputs() == null) {
            return false;
        }

        double coinValue = FchUtils.satoshiToCoin(satoshiValue);

        for (Cash output : rawTxInfo.getOutputs()) {
            // Match both FID and amount to identify the output
            if (output.getOwner().equals(fid) &&
                Math.abs(output.getAmount() - coinValue) < 0.00000001) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parse a P2SH-wrapped CLTV redeemScript to extract the recipient's FID
     * For P2SH outputs, we need to parse the redeemScript stored in rawTxInfo
     * CLTV redeemScript format: <lockTime> OP_CHECKLOCKTIMEVERIFY OP_DROP OP_DUP OP_HASH160 <pubkeyHash> OP_EQUALVERIFY OP_CHECKSIG
     */
    private String parseCltvScriptForRecipient(org.bitcoinj.script.Script script) {
        try {
            // For P2SH outputs, the script will just be: OP_HASH160 <scriptHash> OP_EQUAL
            // We can't extract the FID from P2SH directly, so we need to check if this output
            // matches any of the intended outputs in rawTxInfo

            // If this is a P2SH script, return null and let the caller match by value
            if (script.isPayToScriptHash()) {
                TimberLogger.i(TAG, "Found P2SH output (likely CLTV), will match by value from rawTxInfo.outputs");
                return null;
            }

            // For direct CLTV scripts (non-P2SH, though these are non-standard)
            byte[] scriptBytes = script.getProgram();

            // CLTV scripts should have a specific pattern
            // We need to find the pubkeyHash (20 bytes) that comes after OP_HASH160

            // Parse the script to find OP_HASH160 opcode (0xa9)
            for (int i = 0; i < scriptBytes.length - 21; i++) {
                // Look for OP_HASH160 (0xa9) followed by 0x14 (20 bytes length) and then the hash
                if (scriptBytes[i] == (byte) 0xa9 && scriptBytes[i + 1] == 0x14) {
                    // Extract the 20-byte pubkeyHash
                    byte[] pubkeyHash = new byte[20];
                    System.arraycopy(scriptBytes, i + 2, pubkeyHash, 0, 20);

                    // Convert hash160 to FCH address (FID)
                    String fid = com.fc.fc_ajdk.core.crypto.KeyTools.hash160ToFchAddr(pubkeyHash);

                    TimberLogger.i(TAG, "Parsed CLTV script - extracted FID: %s", fid);
                    return fid;
                }
            }

            return null;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing CLTV script: " + e.getMessage());
            return null;
        }
    }

    private void openCashSelector() {
        if (isSent) return;
        // CashActivity lists only the live identity's cash.
        String liveFid = FidManager.getInstance().getLiveFid();
        if (rawTxInfo.getSender() == null || !rawTxInfo.getSender().equals(liveFid)) {
            ToastUtils.showWarning(this, getString(R.string.reselect_inputs_other_sender));
            return;
        }

        ArrayList<String> currentIds = new ArrayList<>();
        for (Cash cash : inputCashList) {
            String id = cash.getId();
            if (id != null) currentIds.add(id);
        }
        Intent intent = new Intent(this, CashActivity.class);
        intent.putExtra(CashActivity.EXTRA_SELECT_MODE, true);
        intent.putStringArrayListExtra(CashActivity.EXTRA_PRESELECTED_CASH_IDS, currentIds);
        startActivityForResult(intent, REQUEST_RESELECT_INPUTS);
    }

    /**
     * Replaces the TX's inputs with the cash picked in CashActivity and redraws the review.
     * Outputs, OP_RETURN, fee rate and required CD stay; fee and change follow the new inputs.
     */
    private void applyReselectedInputs(List<String> cashJsonList) {
        if (cashJsonList == null || cashJsonList.isEmpty()) return;

        List<Cash> selected = new ArrayList<>();
        for (String cashJson : cashJsonList) {
            try {
                Cash cash = Cash.fromJson(cashJson);
                if (cash != null) selected.add(cash);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error parsing cash JSON: " + e.getMessage());
            }
        }
        if (selected.isEmpty()) return;

        String sender = rawTxInfo.getSender();
        long selectedCd = 0;
        for (Cash cash : selected) {
            if (cash.getOwner() != null && !cash.getOwner().equals(sender)) {
                ToastUtils.showWarning(this, getString(R.string.all_cash_must_have_same_owner));
                return;
            }
            boolean locked = cash.getLockTime() != null
                    && !TxHandler.isLockTimeUnlocked(cash.getLockTime(), bestHeight);
            if (Boolean.TRUE.equals(cash.getConflicted()) || locked) {
                ToastUtils.showWarning(this, getString(R.string.selected_cash_not_spendable));
                return;
            }
            cash.setCd(liveCd(cash));
            selectedCd += cash.getCd();
        }
        long requiredCd = rawTxInfo.getCd() != null ? rawTxInfo.getCd() : 0L;
        if (selectedCd < requiredCd) {
            ToastUtils.showWarning(this, getString(R.string.not_enough_cd));
            return;
        }

        List<Cash> previousInputs = rawTxInfo.getInputs();
        Long previousLockTime = rawTxInfo.getLockTime();
        // createTx copies the largest CLTV input lock into the TX lockTime; drop that copy so it
        // doesn't outlive the inputs it came from.
        long previousInputLock = maxLockTime(previousInputs);
        if (previousInputLock > 0 && previousLockTime != null && previousLockTime == previousInputLock) {
            rawTxInfo.setLockTime(null);
        }
        rawTxInfo.setInputs(Cash.makeCashListForPay(selected));

        String newRawTx = new TxHandler().createTxHex(rawTxInfo);
        Tx newTx = null;
        if (newRawTx != null) {
            try {
                newTx = Tx.fromRawTx(newRawTx, rawTxInfo.getInputs());
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error parsing rebuilt TX: " + e.getMessage());
            }
        }
        if (newTx == null) {
            rawTxInfo.setInputs(previousInputs);
            rawTxInfo.setLockTime(previousLockTime);
            // Only the inputs changed, so the rebuild fails when they can't pay outputs plus fee.
            ToastUtils.showWarning(this, getString(R.string.not_enough_fch));
            return;
        }

        rawTx = newRawTx;
        tx = newTx;
        inputCashList = rawTxInfo.getInputs();
        inputsReselected = true;
        // A TX signed earlier but not broadcast spends the old inputs; copy and QR must not offer it.
        result = null;

        fragmentContainer.removeAllViews();
        setupSender();
        setupSendTo(bestHeight);
        setupSummary();
        setupText();
        updateButtonTexts();
        ToastUtils.makeText(this, getString(R.string.inputs_reselected));
    }

    private static long maxLockTime(List<Cash> cashes) {
        long max = 0;
        if (cashes == null) return max;
        for (Cash cash : cashes) {
            if (cash.getLockTime() != null && cash.getLockTime() > max) max = cash.getLockTime();
        }
        return max;
    }

    /** CD at the current height when the birth height is known; otherwise the stored cd. */
    private long liveCd(Cash cash) {
        if (bestHeight > 0 && cash.getValue() != null && cash.getBirthHeight() != null) {
            return FchUtils.cdd(cash.getValue(), cash.getBirthHeight(), bestHeight);
        }
        return cash.getCd() != null ? cash.getCd() : 0L;
    }

    // Method removed: locktime info is now integrated directly into TxOutputCard
    // See TxOutputCard.setLockTime() and addCltvRecipients() for the new implementation

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_RESELECT_INPUTS) {
            if (resultCode == RESULT_OK && data != null) {
                applyReselectedInputs(data.getStringArrayListExtra(CashActivity.EXTRA_SELECTED_CASH));
            }
            return;
        }

        if (requestCode == REQUEST_BROADCAST) {
            if (resultCode == RESULT_OK && data != null) {
                String txId = data.getStringExtra("EXTRA_TXID");
                if (txId != null && !txId.isEmpty()) {
                    // Transaction was successfully broadcast
                    isSent = true;
                    result = txId;
                    ToastUtils.makeText(SendTxActivity.this, getString(R.string.tx_sent));
                    updateButtonTexts();

                    // Notify TxSender if there's a pending callback
                    TxSender currentTxSender = TxSender.getCurrentInstance();
                    if (currentTxSender != null) {
                        currentTxSender.onActivityResult(txId);
                    }

                    // Set result for calling activity
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(EXTRA_RESULT_TXID, txId);
                    setResult(RESULT_SIGNED, resultIntent);
                }
            }
            // If broadcast failed or was cancelled, do nothing and don't finish
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Dismiss waiting dialog if it's showing
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }

        // Clear the TxSender callback when activity is destroyed to prevent memory leaks
        if (!isSent) {
            TxSender currentTxSender = TxSender.getCurrentInstance();
            if (currentTxSender != null) {
                currentTxSender.clearPendingCallback();
            }
        }
    }

    /**
     * Checks if a string contains valid JSON
     */
    private boolean isJsonString(String str) {
        if (str == null || str.trim().isEmpty()) {
            return false;
        }

        // Remove surrounding quotes if present
        String trimmed = str.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }

        boolean result = JsonUtils.isJson(trimmed);
        TimberLogger.d(TAG, "isJsonString: '%s' -> %s", str.length() > 50 ? str.substring(0, 50) + "..." : str, result);
        return result;
    }

    /**
     * Shows a dialog displaying formatted JSON content
     */
    private void showJsonDialog(String title, String jsonContent) {
        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_json_viewer, null);

            TextView titleTextView = dialogView.findViewById(R.id.titleTextView);
            TextView jsonContentTextView = dialogView.findViewById(R.id.jsonContentTextView);
            ImageView makeQrImageView = dialogView.findViewById(R.id.makeQrImageView);
            Button copyButton = dialogView.findViewById(R.id.copyButton);
            Button okButton = dialogView.findViewById(R.id.okButton);

            titleTextView.setText(title);
            jsonContentTextView.setText(jsonContent);

            AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

            makeQrImageView.setOnClickListener(v -> {
                try {
                    com.fc.freer.utils.QRCodeGenerator.generateAndShowQRCode(this, jsonContent);
                } catch (Exception e) {
                    ToastUtils.showError(this, getString(R.string.failed_to_generate_qr) + ": " + e.getMessage());
                }
            });

            copyButton.setOnClickListener(v -> {
                copyToClipboard(jsonContent, "json_content");
            });

            okButton.setOnClickListener(v -> dialog.dismiss());

            DialogUtils.show(dialog);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing JSON dialog: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.failed_to_show_json_dialog));
        }
    }

//    /**
//     * Updates the cash database after a transaction is signed
//     * @param signedTx The signed transaction hex string
//     * @param tx The transaction info containing all outputs including change
//     */
//    private void updateCashDB(String signedTx, Tx tx) {
//        if (signedTx == null || tx == null) {
//            TimberLogger.e(TAG, "updateCashDB: signedTx or tx is null");
//            return;
//        }
//
//        try {
//            // 1. Calculate signedTxId = Hex.toHex(Hash.sha256x2(Hex.fromHex(signedTx)))
//            String signedTxId = Hex.toHex(Hash.sha256x2(Hex.fromHex(signedTx)));
//            TimberLogger.i(TAG, "updateCashDB: signedTxId = %s", signedTxId);
//
//            // Get CashManager instance
//            CashManager cashManager = CashManager.getInstance();
//
//            // 2. Remove spent cash from CashManager's localDB
//            if (tx.getSpentCashes() != null) {
//                for (CashMask spentCashMark : tx.getSpentCashes()) {
//                    if (spentCashMark.getId() != null) {
//                        cashManager.removeCash(spentCashMark.getId());
//                    }
//                }
//            }
//
//            // 3. Check issuedCashes and create new cash for own addresses
//            if (tx.getIssuedCashes() != null) {
//                for (int i = 0; i < tx.getIssuedCashes().size(); i++) {
//                    CashMask cashMark = tx.getIssuedCashes().get(i);
//
//                    // Check if this output belongs to any of our KeyInfo addresses
//                    if (cashMark.getOwner() != null && SettingManager.getInstance().getCurrentKeyInfo().getId().equals(cashMark.getOwner())) {
//                        // Create new cash for our own address
//                        Cash newCash = new Cash();
//                        newCash.setBirthTxId(signedTxId);
//                        newCash.setBirthIndex(i);
//                        newCash.setBirthTime(System.currentTimeMillis() / 1000 + 300); // Current time + 5 minutes (300 seconds)
//                        newCash.setValue(cashMark.getValue());
//                        newCash.setOwner(cashMark.getOwner());
//                        newCash.setValid(true);
//
//                        // Generate cash ID using makeId method
//                        String cashId = newCash.makeId();
//                        newCash.setId(cashId);
//
//                        // Add to CashManager's DB
//                        cashManager.addValidCash(newCash);
//                        TimberLogger.i(TAG, "updateCashDB: Added new cash with ID: %s, owner: %s, amount: %s",
//                            cashId, cashMark.getOwner(), FchUtils.satoshiToCoin(cashMark.getValue()));
//                    }
//                }
//            }
//
//            // Commit changes to database
//            cashManager.commit();
//            TimberLogger.i(TAG, "updateCashDB: Successfully updated cash database");
//
//        } catch (Exception e) {
//            TimberLogger.e(TAG, "updateCashDB: Error updating cash database: %s", e.getMessage());
//        }
//    }
} 