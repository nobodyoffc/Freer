package com.fc.freer.multisign;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.MultisignTxDetail;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.home.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxHandler;
import com.fc.freer.tx.view.CashAmountCard;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.tx.SendTxActivity;
import com.fc.freer.utils.KeyCardManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.CashMark;
import com.fc.fc_ajdk.data.apipData.TxInfo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.SecurePrikeyManager;

import java.util.List;
import java.util.Map;

public class SignMultisignTxActivity extends BaseCryptoActivity {
    private static final String TAG = "SignMultisignTxActivity";
    public static final int RESULT_BUILT = 1002;  // Add result code constant
    private RawTxInfo rawTxInfo;
    private MultisignTxDetail multisignTxDetail;
    private LinearLayout fragmentContainer;
    private Button makeQrButton;
    private Button copyButton;
    private Button signButton;
    private boolean isBuilt = false;
    private boolean isFullSigned = false;
    private String buildResult = null;
    private TxInfo txInfo;
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
        multisignTxDetail = MultisignTxDetail.fromMultiSigData(rawTxInfo, this);
        
        // Initialize txInfo for cash database updates
        String rawTx = txHandler.createTxHex(rawTxInfo);
        List<Cash> inputCashList = rawTxInfo.getInputs();
        if (rawTx != null && inputCashList != null) {
            txInfo = TxInfo.fromRawTx(rawTx, inputCashList);
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
        setupSignedFids();
        setupUnsignedFids();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        setupSignedFids();
        setupUnsignedFids();
    }

    private void checkState() {
        isFullSigned = isFullSigned();
        if (isBuilt) {
            signButton.setText(getString(R.string.send));
            copyButton.setText(getString(R.string.copy_result));
        } else if(isFullSigned){
            signButton.setText(getString(R.string.build));
        }
    }

    private boolean isFullSigned() {
        return multisignTxDetail.getRestSignNum() != null && multisignTxDetail.getRestSignNum() == 0;
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
                copyToClipboard(rawTxInfo.toNiceJson(), "multisign");
            }
        });

        signButton.setOnClickListener(v -> {
            if(isBuilt) {
                // Use the shared broadcast method from BaseCryptoActivity
                broadcastTransaction(buildResult, new TxSendCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        Toast.makeText(SignMultisignTxActivity.this, getString(R.string.transaction_sent_successfully) + ": " + txId, FreerApplication.TOAST_LASTING).show();
                        setResult(RESULT_BUILT);
                        finish();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        Toast.makeText(SignMultisignTxActivity.this, getString(R.string.failed_to_send_transaction) + ": " + errorMessage, FreerApplication.TOAST_LASTING).show();
                        // Fallback to copying to clipboard if broadcast fails
                        copyToClipboard(buildResult, "multisign_result");
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        // This should not be called for broadcast
                        Toast.makeText(SignMultisignTxActivity.this, "Unexpected error", FreerApplication.TOAST_LASTING).show();
                    }
                });
                return;
            }
            if (isFullSigned) {
                buildResult = txHandler.buildSchnorrMultiSignTx(rawTxInfo);
                if(!Hex.isHexString(buildResult)){
                    Toast.makeText(this, getString(R.string.failed_to_build_multisign_tx), FreerApplication.TOAST_LASTING).show();
                } else{
                    isBuilt=true;
                    checkState();
                    Toast.makeText(this, getString(R.string.built_you_can_broadcast_it), FreerApplication.TOAST_LASTING).show();
                }
                return;
            }

            KeyInfo chosenKeyInfo = FidManager.getInstance().getMainKeyInfo();
            if (rawTxInfo.getMultisign().getFids().contains(chosenKeyInfo.getId())) {
                byte[] priKeyBytes = SecurePrikeyManager.fetchPrikeySilent(chosenKeyInfo.getPrikeyCipher());
                if (priKeyBytes != null) {
                    txHandler.signSchnorrMultiSignTx(rawTxInfo, priKeyBytes);
                    multisignTxDetail = MultisignTxDetail.fromMultiSigData(rawTxInfo, this);
                    refreshFragmentContainer();
                    if(isFullSigned)Toast.makeText(this,"The TX is well signed! Build it.", FreerApplication.TOAST_LASTING).show();
                    else Toast.makeText(this,"Signed!", FreerApplication.TOAST_LASTING).show();
                    checkState();
                } else {
                    Toast.makeText(this, "Failed to get the priKey of " + chosenKeyInfo.getId(), FreerApplication.TOAST_LASTING).show();
                }
            } else {
                Toast.makeText(this, "Selected key is not part of this multisign transaction", FreerApplication.TOAST_LASTING).show();
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
        if (multisignTxDetail.getCashIdAmountMap() == null || multisignTxDetail.getCashIdAmountMap().isEmpty()) {
            return;
        }
        addLabel(getString(R.string.spending));
        for (Map.Entry<String, String> entry : multisignTxDetail.getCashIdAmountMap().entrySet()) {
            CashAmountCard card = new CashAmountCard(this);
            card.setCashId(entry.getKey());
            card.setAmount(entry.getValue());
            fragmentContainer.addView(card);
        }
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
        if (multisignTxDetail.getOpReturn() != null && !multisignTxDetail.getOpReturn().isEmpty()) {
            addTextLine(getString(R.string.carving)+": " + multisignTxDetail.getOpReturn());
        }
        if (multisignTxDetail.getmOfN() != null && !multisignTxDetail.getmOfN().isEmpty()) {
            addTextLine(getString(R.string.m_n)+": " + multisignTxDetail.getmOfN());
        }
        if (multisignTxDetail.getRestSignNum() != null) {
            addTextLine(getString(R.string.need_signs)+": " + multisignTxDetail.getRestSignNum());
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
            fieldValue.setTextColor(getResources().getColor(R.color.text_color, getTheme()));
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
        if (multisignTxDetail.getSignedFidList() == null || multisignTxDetail.getSignedFidList().isEmpty()) {
            return;
        }
        addLabel("Signed members:");
        KeyCardManager keyCardManager = new KeyCardManager(this, fragmentContainer, null);
        for (String fid : multisignTxDetail.getSignedFidList()) {
            KeyInfo keyInfo = new KeyInfo(fid);
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    private void setupUnsignedFids() {
        if (multisignTxDetail.getUnSignedFidList() == null || multisignTxDetail.getUnSignedFidList().isEmpty()) {
            return;
        }
        addLabel("Unsigned members:");
        KeyCardManager keyCardManager = new KeyCardManager(this, fragmentContainer, null, List.of(KeyCardManager.createSignMenuItem(this)));
        keyCardManager.setOnMenuItemClickListener((menuItem, keyInfo) -> {
            if ("Sign".equalsIgnoreCase(menuItem)) {
                KeyInfo fullKeyInfo = SettingManager.getInstance().getCurrentKeyInfo();
                if (fullKeyInfo != null) {
                    byte[] priKeyBytes = fullKeyInfo.decryptPrikey(ConfigureManager.getInstance().getSymkey());
                    if (priKeyBytes != null) {
                        txHandler.signSchnorrMultiSignTx(rawTxInfo, priKeyBytes);
                        multisignTxDetail = MultisignTxDetail.fromMultiSigData(rawTxInfo, this);
                        refreshFragmentContainer();
                        Toast.makeText(this, "Signed!", FreerApplication.TOAST_LASTING).show();
                    } else {
                        Toast.makeText(this, "Failed to get the priKey of " + keyInfo.getId(), FreerApplication.TOAST_LASTING).show();
                    }
                } else {
                    Toast.makeText(this, "Failed to get the key info of " + keyInfo.getId(), FreerApplication.TOAST_LASTING).show();
                }
            }
        });
        for (String fid : multisignTxDetail.getUnSignedFidList()) {
            KeyInfo keyInfo = new KeyInfo(fid);
            keyCardManager.addKeyCard(keyInfo);
        }
    }

    /**
     * Updates the cash database after a multisign transaction is built
     * @param builtTx The built transaction hex string
     * @param txInfo The transaction info containing all outputs including change
     */
    private void updateCashDB(String builtTx, TxInfo txInfo) {
        if (builtTx == null || txInfo == null) {
            TimberLogger.e(TAG, "updateCashDB: builtTx or txInfo is null");
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
                    
                    // Check if this output belongs to current user's address
                    if (cashMark.getOwner() != null && currentKeyInfo != null && currentKeyInfo.getId().equals(cashMark.getOwner())) {
                        // Create new cash for our own address
                        Cash newCash = new Cash();
                        newCash.setBirthTxId(builtTxId);
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