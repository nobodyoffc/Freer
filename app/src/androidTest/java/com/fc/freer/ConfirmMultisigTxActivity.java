package com.fc.freer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.MultisigTxDetail;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.tx.SendTxActivity;
import com.fc.freer.tx.view.CashAmountCard;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;

import java.util.Map;

public class ConfirmMultisigTxActivity extends BaseCryptoActivity {
    private RawTxInfo rawTxInfo;
    private MultisigTxDetail multisigTxDetail;
    private LinearLayout fragmentContainer;
    private LinearLayout buttonContainer;
    private Button makeQrButton;
    private Button copyButton;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_send_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.confirm_multisign_tx);
    }

    @Override
    protected void initializeViews() {
        // Get data from intent
        String txInfoJson = getIntent().getStringExtra(SendTxActivity.EXTRA_TX_INFO_JSON);
        rawTxInfo = RawTxInfo.fromJson(txInfoJson,RawTxInfo.class);
        if (rawTxInfo == null) {
            finish();
            return;
        }
        multisigTxDetail = MultisigTxDetail.fromMultiSigData(rawTxInfo, this);

        fragmentContainer = findViewById(R.id.fragmentContainer);
        buttonContainer = findViewById(R.id.buttonContainer);
        makeQrButton = findViewById(R.id.makeQrButton);
        copyButton = findViewById(R.id.copyButton);

        setupSender();
        setupCash();
        setupSendTo();
        setupText();
        setupSignedFids();
        setupUnsignedFids();
    }

    @Override
    protected void setupButtons() {
        makeQrButton.setOnClickListener(v -> {
            handleQrGeneration(rawTxInfo.toNiceJson());
        });

        copyButton.setOnClickListener(v -> {
            copyToClipboard(rawTxInfo.toNiceJson(), "multisig");
        });
    }

    @Override
    protected void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
//        showToast(getString(R.string.copied));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void setupSender() {
        if (multisigTxDetail.getSender() == null || multisigTxDetail.getSender().isEmpty()) {
            return;
        }
        createMultisignFidCard(multisigTxDetail.getSender(), fragmentContainer, true);
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
        if (multisigTxDetail.getSendToList() == null || multisigTxDetail.getSendToList().isEmpty()) {
            return;
        }
        addLabel(getString(R.string.send_to)+":");
        for (Cash cash : multisigTxDetail.getSendToList()) {
            TxOutputCard card = new TxOutputCard(this);
            card.setSendTo(cash, this);
            fragmentContainer.addView(card);
        }
    }

    private void setupText() {
        if (multisigTxDetail.getOpReturn() != null && !multisigTxDetail.getOpReturn().isEmpty()) {
            addTextLine(getString(R.string.carving)+": " + multisigTxDetail.getOpReturn());
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
        if (parts.length == 2) {
            TextView fieldName = new TextView(this);
            fieldName.setText(parts[0] + ": ");
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
            TextView fieldName = new TextView(this);
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
        KeyCardContainer keyCardContainer = new KeyCardContainer(this, fragmentContainer, ChooseMode.WITHOUT_CHOOSE);
        for (String fid : multisigTxDetail.getUnSignedFidList()) {
            KeyInfo keyInfo = new KeyInfo(fid);
            keyCardContainer.addKeyCard(keyInfo);
        }
    }
} 