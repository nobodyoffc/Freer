package com.fc.freer.multisig;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Tx;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.CashMask;
import com.fc.fc_ajdk.data.fchData.MultisigTxDetail;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.view.CashAmountCard;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.tx.SendTxActivity;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.SecurePrikeyManager;

import java.util.List;
import java.util.Map;

public class SignMultisigTxActivity extends BaseCryptoActivity {
    private static final String TAG = "SignMultisigTxActivity";
    public static final int RESULT_SENT = 1002;  // Add result code constant
    private RawTxInfo rawTxInfo;
    private MultisigTxDetail multisigTxDetail;
    private LinearLayout fragmentContainer;
    private ImageButton makeQrButton;
    private ImageButton copyButton;
    private ImageButton signButton;
    private boolean isBuilt = false;
    private boolean isFullSigned = false;
    private String buildResult = null;
    private Tx tx;
    private final TxHandler txHandler = new TxHandler();


    @Override
    protected int getLayoutId() {
        return R.layout.activity_send_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.sign_multisign_tx);
    }

    @Override
    protected void initializeViews() {
        // Get data from intent
        String txJson = (String)getIntent().getSerializableExtra(SendTxActivity.EXTRA_TX_INFO_JSON);
        rawTxInfo = RawTxInfo.fromJson(txJson,RawTxInfo.class);
        if (rawTxInfo == null) {
            finish();
            return;
        }

        if(rawTxInfo.getSenderInfo()==null || rawTxInfo.getSenderMultisig()==null){
            rawTxInfo.setSenderInfo(FidManager.getInstance().getKeyInfoByFid(rawTxInfo.getSender()));
            rawTxInfo.setSenderMultisig(rawTxInfo.getSenderInfo().getMultisign());
        }

        multisigTxDetail = MultisigTxDetail.fromMultiSigData(rawTxInfo, this);
        
        // Initialize tx for cash database updates
        String rawTx = txHandler.createTxHex(rawTxInfo);
        List<Cash> inputCashList = rawTxInfo.getInputs();
        if (rawTx != null && inputCashList != null) {
            tx = Tx.fromRawTx(rawTx, inputCashList);
        }

        fragmentContainer = findViewById(R.id.fragmentContainer);
        LinearLayout buttonContainer = findViewById(R.id.buttonContainer);
        makeQrButton = findViewById(R.id.makeQrButton);
        copyButton = findViewById(R.id.copyButton);
        signButton = findViewById(R.id.signButton);

        checkState();

        setupSender();
        setupCash();
        setupSendTo();
        setupText();
        setupUnsignedFids();
        setupSignedFids();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release any TxSender callback waiting on this activity. A multisig tx
        // that is left partly signed simply ends as cancelled.
        TxSender currentTxSender = TxSender.getCurrentInstance();
        if (currentTxSender != null) {
            currentTxSender.clearPendingCallback();
        }
    }

    private void refreshFragmentContainer() {
        // Clear existing views
        fragmentContainer.removeAllViews();
        
        // Check if transaction is fully signed
        checkState();

        // Re-setup all views with updated data
        setupSender();
        setupCash();
        setupSendTo();
        setupText();
        setupUnsignedFids();
        setupSignedFids();
    }

    private void checkState() {
        isFullSigned = isFullSigned();
        if (isBuilt) {
            signButton.setImageResource(R.drawable.ic_send);
        } else if(isFullSigned){
            signButton.setImageResource(R.drawable.ic_build);
        }
    }

    private boolean isFullSigned() {
        return multisigTxDetail.getRestSignNum() != null && multisigTxDetail.getRestSignNum() == 0;
    }

    @Override
    protected void setupButtons() {
        makeQrButton.setOnClickListener(v -> {
            if (isBuilt){
                if(buildResult != null)
                    handleQrGeneration(buildResult);
            } else {
                handleQrGeneration(rawTxInfo.toNiceJson());
            }
        });

        copyButton.setOnClickListener(v -> {
            if (isBuilt) {
                if( buildResult != null)
                    copyToClipboard(buildResult, "multisign_result");
            } else {
                copyToClipboard(rawTxInfo.toNiceJson(), "multisig");
            }
        });

        signButton.setOnClickListener(v -> {
            if(isBuilt) {
                // Use the shared broadcast method from BaseCryptoActivity
                broadcastTransaction(buildResult, new TxSendCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        ToastUtils.makeText(SignMultisigTxActivity.this, getString(R.string.tx_sent));
                        // Notify TxSender if the tx came from a TxSender call
                        TxSender currentTxSender = TxSender.getCurrentInstance();
                        if (currentTxSender != null) {
                            currentTxSender.onActivityResult(txId);
                        }
                        setResult(RESULT_SENT);
                        finish();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        ToastUtils.showError(SignMultisigTxActivity.this, getString(R.string.failed_to_send_tx,errorMessage));
                        // Fallback to copying to clipboard if broadcast fails
                        copyToClipboard(buildResult, "multisign_result");
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        // This should not be called for broadcast
                        ToastUtils.showError(SignMultisigTxActivity.this, getString(R.string.toast_unexpected_error));
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        // Network not available, copy the signed transaction
                        copyToClipboard(signedTxHex, "signed_tx");
                        ToastUtils.makeText(SignMultisigTxActivity.this, getString(R.string.tx_signed_but_not_broadcast));
                    }
                });
                return;
            }
            if (isFullSigned) {
                buildResult = txHandler.buildSchnorrMultiSignTx(rawTxInfo);
                if(!Hex.isHexString(buildResult)){
                    ToastUtils.showError(this, getString(R.string.failed_to_build_multisign_tx));
                } else{
                    isBuilt=true;
                    checkState();
                    ToastUtils.makeText(this, getString(R.string.built_you_can_send_it));
                }
                return;
            }

            KeyInfo chosenKeyInfo = FidManager.getInstance().getMainKeyInfo();
            if (rawTxInfo.getSenderMultisig().getFids().contains(chosenKeyInfo.getId())) {
                byte[] priKeyBytes = SecurePrikeyManager.fetchPrikeySilent(chosenKeyInfo.getPrikeyCipher());
                if (priKeyBytes != null) {
                    txHandler.signSchnorrMultiSignTx(rawTxInfo, priKeyBytes);
                    multisigTxDetail = MultisigTxDetail.fromMultiSigData(rawTxInfo, this);
                    refreshFragmentContainer();
                    if(isFullSigned)ToastUtils.makeText(this,getString(R.string.toast_tx_well_signed_build));
                    else ToastUtils.makeText(this,getString(R.string.toast_signed));
                    checkState();
                } else {
                    ToastUtils.showError(this, getString(R.string.toast_failed_get_prikey_of, chosenKeyInfo.getId()));
                }
            } else {
                ToastUtils.showWarning(this, getString(R.string.toast_key_not_part_multisig));
            }
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void setupSender() {
        // Get sender KeyInfo from FidManager instead of rawTxInfo
        KeyInfo senderKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (senderKeyInfo == null || senderKeyInfo.getId() == null || senderKeyInfo.getId().isEmpty()) {
            return;
        }
        createFidCard(senderKeyInfo, fragmentContainer, true);
    }

    private void setupCash() {
        if (multisigTxDetail.getCashIdAmountMap() == null || multisigTxDetail.getCashIdAmountMap().isEmpty()) {
            return;
        }
        addLabel(getString(R.string.spending));
        for (Map.Entry<String, String> entry : multisigTxDetail.getCashIdAmountMap().entrySet()) {
            CashAmountCard card = new CashAmountCard(this);
            card.setCashId(entry.getKey());
            card.setAmount(entry.getValue());
            fragmentContainer.addView(card);
        }
    }

    private void setupSendTo() {
        if (tx == null || tx.getIssuedCashes() == null || tx.getIssuedCashes().isEmpty()) {
            return;
        }

        addLabel(getString(R.string.send_to) + ":");

        // Get best height for locktime calculation
        com.fc.fc_ajdk.fapi.client.FapiClient fapiClient = (com.fc.fc_ajdk.fapi.client.FapiClient)
                com.fc.freer.utils.ApiCenter.getInstance().getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
        long bestHeight = (fapiClient != null && fapiClient.getBestHeight() != null) ? fapiClient.getBestHeight() : 0;


        // Match P2SH outputs with rawTxInfo.outputs
        int outputIndex = 0;
        for (CashMask cashMask : tx.getIssuedCashes()) {
            TxOutputCard card = new TxOutputCard(this);
            Cash cash;

            // Try to find matching Cash in rawTxInfo
            if (rawTxInfo.getOutputs() != null && outputIndex < rawTxInfo.getOutputs().size()) {
                Cash matchedCash = rawTxInfo.getOutputs().get(outputIndex);
                outputIndex++;
                cash = matchedCash;
            } else {
                // change which is not in rawTxInfo.outputs
                cash = new Cash(cashMask.getOwner(), FchUtils.satoshiToCoin(cashMask.getValue()));
            }

            card.setSendTo(cash, this, false, false, false);
            if(cash.getLockTime()!=null && cash.getLockTime()>0){
                card.setLockTime(cash.getLockTime(), bestHeight);
            }

            fragmentContainer.addView(card);
        }


//        // Check if ANY output has a lockTime (per-output CLTV check)
//        boolean hasCltvOutput = false;
//        if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
//            for (Cash output : rawTxInfo.getOutputs()) {
//                if (output.getLockTime() != null && output.getLockTime() > 0) {
//                    hasCltvOutput = true;
//                    break;
//                }
//            }
//        }
//
//
//        if (hasCltvOutput) {
//            // For CLTV transactions, parse P2SH outputs and match with rawTxInfo
//            makeSendToCards();
//        } else {
//            // Regular multisig outputs without CLTV
//            for (CashMask cashMask : tx.getIssuedCashes()) {
//                TxOutputCard card = new TxOutputCard(this);
//                Cash sendTo = new Cash(cashMask.getOwner(), FchUtils.satoshiToCoin(cashMask.getValue()));
//                card.setSendTo(sendTo, this, false, false, false);
//                fragmentContainer.addView(card);
//            }
//        }
    }
//
//    private void makeSendToCards() {
//        // Parse P2SH list from OP_RETURN to get redeemScripts
//        if(rawTxInfo.getOpReturn()==null || rawTxInfo.getOpReturn().isEmpty())return;
//        // Match P2SH outputs with rawTxInfo.outputs
//        int outputIndex = 0;
//        for (CashMask cashMask : tx.getIssuedCashes()) {
//            TxOutputCard card = new TxOutputCard(this);
//            Cash sendTo;
//
//            // Try to find matching Cash in rawTxInfo
//            if (rawTxInfo.getOutputs() != null && outputIndex < rawTxInfo.getOutputs().size()) {
//                Cash matchedSendTo = rawTxInfo.getOutputs().get(outputIndex);
//                outputIndex++;
//                sendTo = matchedSendTo;
//            } else {
//                // change which is not in rawTxInfo.outputs
//                sendTo = new Cash(cashMask.getOwner(), FchUtils.satoshiToCoin(cashMask.getValue()));
//            }
//
//            card.setSendTo(sendTo, this, false, false, false);
//
//            fragmentContainer.addView(card);
//        }
//
//    }


    private void setupText() {
        // Handle carving with container and JSON icon
        if (multisigTxDetail.getOpReturn() != null && !multisigTxDetail.getOpReturn().isEmpty()) {
            addCarvingContainer(multisigTxDetail.getOpReturn());
        }
        if (multisigTxDetail.getmOfN() != null && !multisigTxDetail.getmOfN().isEmpty()) {
            addTextLine(getString(R.string.m_n)+": " + multisigTxDetail.getmOfN());
        }
        if (multisigTxDetail.getRestSignNum() != null) {
            addTextLine(getString(R.string.need_signs)+": " + multisigTxDetail.getRestSignNum());
        }
    }

    private void addTextLine(String text) {
        if(text==null || text.isEmpty())return;
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setPadding(0, 16, 0, 16); // Add vertical padding for spacing

        // Split the text into field name and value
        String[] parts = text.split(": ", 2);
        TextView fieldName = new TextView(this);
        if (parts.length == 2) {
            fieldName.setText(getString(R.string.field_name_format, parts[0]));
            fieldName.setTextColor(getResources().getColor(R.color.field_name, getTheme()));
            fieldName.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView fieldValue = new TextView(this);
            fieldValue.setText(parts[1]);
            fieldValue.setTextColor(getResources().getColor(R.color.text, getTheme()));
            fieldValue.setTypeface(null, android.graphics.Typeface.NORMAL);

            rowLayout.addView(fieldName);
            rowLayout.addView(fieldValue);
        } else {
            // If no colon found, treat the whole text as a field name
            fieldName.setText(text);
            fieldName.setTextColor(getResources().getColor(R.color.field_name, getTheme()));
            fieldName.setTypeface(null, android.graphics.Typeface.BOLD);
            rowLayout.addView(fieldName);
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

    private void setupSignedFids() {
        if (multisigTxDetail.getSignedFidList() == null || multisigTxDetail.getSignedFidList().isEmpty()) {
            return;
        }
        addLabel("Signed members:");
        KeyCardContainer keyCardContainer = new KeyCardContainer(this, fragmentContainer, ChooseMode.WITHOUT_CHOOSE);
        for (String fid : multisigTxDetail.getSignedFidList()) {
            KeyInfo keyInfo = new KeyInfo(fid);
            keyCardContainer.addKeyCard(keyInfo);
        }
    }

    private void setupUnsignedFids() {
        if (multisigTxDetail.getUnSignedFidList() == null || multisigTxDetail.getUnSignedFidList().isEmpty()) {
            return;
        }
        addLabel("Unsigned members:");
        KeyCardContainer keyCardContainer = new KeyCardContainer(this, fragmentContainer, ChooseMode.WITHOUT_CHOOSE, List.of(KeyCardContainer.createSignMenuItem(this)));
        keyCardContainer.setOnMenuItemClickListener((menuItem, keyInfo) -> {
            if ("Sign".equalsIgnoreCase(menuItem)) {
                KeyInfo fullKeyInfo = SettingManager.getInstance().getCurrentKeyInfo();
                if (fullKeyInfo != null) {
                    byte[] priKeyBytes = fullKeyInfo.decryptPrikey(ConfigureManager.getInstance().getSymkey());
                    if (priKeyBytes != null) {
                        txHandler.signSchnorrMultiSignTx(rawTxInfo, priKeyBytes);
                        multisigTxDetail = MultisigTxDetail.fromMultiSigData(rawTxInfo, this);
                        refreshFragmentContainer();
                        ToastUtils.makeText(this, getString(R.string.toast_signed));
                    } else {
                        ToastUtils.showError(this, getString(R.string.toast_failed_get_prikey_of, keyInfo.getId()));
                    }
                } else {
                    ToastUtils.showError(this, getString(R.string.toast_failed_get_key_info_of, keyInfo.getId()));
                }
            }
        });
        for (String fid : multisigTxDetail.getUnSignedFidList()) {
            KeyInfo keyInfo = new KeyInfo(fid);
            keyCardContainer.addKeyCard(keyInfo);
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

            dialog.show();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing JSON dialog: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.failed_to_show_json_dialog));
        }
    }

    /**
     * Updates the cash database after a multisig transaction is built
     * @param builtTx The built transaction hex string
     * @param tx The transaction info containing all outputs including change
     */
    private void updateCashDB(String builtTx, Tx tx) {
        if (builtTx == null || tx == null) {
            TimberLogger.e(TAG, "updateCashDB: builtTx or tx is null");
            return;
        }

        try {
            // 1. Calculate builtTxId = Hex.toHex(Hash.sha256x2(Hex.fromHex(builtTx)))
            String builtTxId = Hex.toHex(Hash.sha256x2(Hex.fromHex(builtTx)));
            TimberLogger.i(TAG, "updateCashDB: builtTxId = %s", builtTxId);

            // Get CashManager instance
            CashManager cashManager = CashManager.getInstance();
            KeyInfo currentKeyInfo = SettingManager.getInstance().getCurrentKeyInfo();

            // 2. Remove spent cash from CashManager's localDB
            if (tx.getSpentCashes() != null) {
                for (CashMask spentCashMask : tx.getSpentCashes()) {
                    if (spentCashMask.getId() != null) {
                        cashManager.removeCash(spentCashMask.getId());
                    }
                }
            }

            // 3. Check issuedCashes and create new cash for own addresses
            if (tx.getIssuedCashes() != null) {
                for (int i = 0; i < tx.getIssuedCashes().size(); i++) {
                    CashMask cashMask = tx.getIssuedCashes().get(i);
                    
                    // Check if this output belongs to current user's address
                    if (cashMask.getOwner() != null && currentKeyInfo != null && currentKeyInfo.getId().equals(cashMask.getOwner())) {
                        // Create new cash for our own address
                        Cash newCash = new Cash();
                        newCash.setBirthTxId(builtTxId);
                        newCash.setBirthIndex(i);
                        newCash.setBirthTime(System.currentTimeMillis() / 1000 + 300); // Current time + 5 minutes (300 seconds)
                        newCash.setValue(cashMask.getValue());
                        newCash.setOwner(cashMask.getOwner());
                        newCash.setValid(true);
                        
                        // Generate cash ID using makeId method
                        String cashId = newCash.makeId();
                        newCash.setId(cashId);
                        
                        // Add to CashManager's DB
                        cashManager.addValidCash(newCash);
                        TimberLogger.i(TAG, "updateCashDB: Added new cash with ID: %s, owner: %s, amount: %s", 
                            cashId, cashMask.getOwner(), FchUtils.satoshiToCoin(cashMask.getValue()));
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


    /**
     * Shared method to broadcast an already signed transaction
     * @param signedTxHex The signed transaction in hex format
     * @param callback Callback to handle the result
     */
    protected void broadcastTransaction(String signedTxHex, TxSendCallback callback) {
        if(NetworkUtils.isNetworkDownToast(this))return;
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        // Show progress message
        showToast(getString(R.string.sending_transaction));
        // Broadcast in background thread
        new Thread(() -> {
            try {
                String result = fapiClient.broadcastTx(signedTxHex);
                if(result==null)
                    result=fapiClient.getLastError();
                String finalResult = result;
                runOnUiThread(() -> {
                    if (com.fc.fc_ajdk.utils.Hex.isHex32(finalResult)) {
                        // Update cash database after successful broadcast
                        CashManager.updateCashOfTx(signedTxHex,this);

                        callback.onSuccess(finalResult);
                    } else {
                        callback.onError("Transaction broadcast failed: " + finalResult);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> callback.onError("Failed to broadcast transaction: " + e.getMessage()));
            }
        }).start();
    }

}