package com.fc.freer.multisig;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fcData.ReplyBody;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.ui.ImportActivity;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ImportParser;
import com.fc.freer.utils.KeyCardContainer;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports a multisig TX of the live FID from one or more JSONs, each signed by
 * different members. The TX info container above the import box shows the TX and
 * who signed each added JSON; '+' adds the JSON in the box. Import merges the
 * signatures and returns the merged TX JSON as {@link ImportActivity#EXTRA_IMPORT_RESULT}.
 */
public class BuildMultisigTxActivity extends BaseCryptoActivity {
    private static final String TAG = "BuildMultisigTxActivity";
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private LinearLayout signaturesContainer;
    private TextInputEditText jsonInput;
    private final List<RawTxInfo> rawTxInfoList = new ArrayList<>();
    // Unsigned TX hex of the first added JSON; every later JSON must be the same TX
    private String txHex;
    private ImageButton buildButton;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private final TxHandler txHandler = new TxHandler();


    @Override
    protected int getLayoutId() {
        return R.layout.activity_sign_multisign_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.import_multisig_tx);
    }

    @Override
    protected void initializeViews() {
        signaturesContainer = findViewById(R.id.signaturesContainer);
        jsonInput = findViewById(R.id.jsonInputContainer).findViewById(R.id.textInput);
        jsonInput.setHint(R.string.import_multisig_tx_hint);
        buildButton = findViewById(R.id.buildButton);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        loadFile(result.getData().getData());
                    }
                });

        setupIoIconsView(R.id.jsonInputContainer, R.id.scanIcon, false, false, true, true, true,
                null, null, () -> pasteFromClipboard(jsonInput), this::openFilePicker,
                () -> startQrScan(QR_SCAN_TEXT_REQUEST_CODE));

        renderTxInfo();
    }

    @Override
    protected void setupButtons() {
        findViewById(R.id.addButton).setOnClickListener(v -> addJsonInBox());
        buildButton.setOnClickListener(v -> handleImport());
        findViewById(R.id.clearButton).setOnClickListener(v -> handleClear());
    }

    /**
     * Adds the JSON in the import box to the list.
     * @return false if the box is empty or the JSON is rejected
     */
    private boolean addJsonInBox() {
        String jsonStr = jsonInput.getText() == null ? "" : jsonInput.getText().toString().trim();
        if (jsonStr.isEmpty()) {
            showToast(getString(R.string.please_input_json));
            return false;
        }

        String liveFid = FidManager.getInstance().getLiveFid();
        ImportParser.ParseResult<RawTxInfo> parseResult = ImportParser.parseTxInfo(this, jsonStr, liveFid);
        if (!parseResult.success || parseResult.data == null) {
            showToast(parseResult.error);
            return false;
        }
        RawTxInfo rawTxInfo = parseResult.data;

        // Exported JSONs drop senderInfo, which carries the multisig; restore it from the local FID
        if (rawTxInfo.getSender() == null) rawTxInfo.setSender(liveFid);
        if (rawTxInfo.getSenderMultisig() == null) {
            KeyInfo senderInfo = FidManager.getInstance().getKeyInfoByFid(rawTxInfo.getSender());
            if (senderInfo != null) rawTxInfo.setSenderInfo(senderInfo);
        }
        if (rawTxInfo.getSenderMultisig() == null) {
            showToast(getString(R.string.invalid_multisign_data));
            return false;
        }

        String hex;
        try {
            hex = txHandler.createTxHex(rawTxInfo);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create TX: %s", e.getMessage());
            hex = null;
        }
        if (hex == null) {
            showToast(getString(R.string.invalid_multisign_data));
            return false;
        }
        if (txHex != null && !txHex.equals(hex)) {
            showToast(getString(R.string.not_the_same_multisig_tx));
            return false;
        }

        txHex = hex;
        rawTxInfoList.add(rawTxInfo);
        jsonInput.setText("");
        renderTxInfo();
        return true;
    }

    private void renderTxInfo() {
        signaturesContainer.removeAllViews();
        if (rawTxInfoList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_multisig_tx_added);
            empty.setTextColor(getColor(R.color.hint));
            empty.setPadding(16, 16, 16, 16);
            signaturesContainer.addView(empty);
            return;
        }

        RawTxInfo first = rawTxInfoList.get(0);
        Multisig multisig = first.getSenderMultisig();

        if (first.getOutputs() != null && !first.getOutputs().isEmpty()) {
            addLabel(getString(R.string.send_to) + ":");
            for (Cash cash : first.getOutputs()) {
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(cash, this, false, false, false);
                signaturesContainer.addView(card);
            }
        }

        Set<String> signers = new LinkedHashSet<>();
        for (RawTxInfo info : rawTxInfoList) {
            if (info.getFidSigMap() != null) signers.addAll(info.getFidSigMap().keySet());
        }
        if (multisig != null && multisig.getM() != null) {
            addLabel(getString(R.string.multisig_signatures_collected, signers.size(), multisig.getM()));
        }

        for (int i = 0; i < rawTxInfoList.size(); i++) {
            addSignatureCard(rawTxInfoList.get(i), i + 1);
        }
    }

    private void addLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.field_name));
        label.setTextSize(16);
        label.setPadding(8, 16, 8, 8);
        signaturesContainer.addView(label);
    }

    private void addSignatureCard(RawTxInfo rawTxInfo, int number) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_signature_card, signaturesContainer, false);
        TextView label = cardView.findViewById(R.id.signature_label);
        LinearLayout keyCardContainer = cardView.findViewById(R.id.key_card_container);

        Map<String, List<String>> fidSigMap = rawTxInfo.getFidSigMap();
        if (fidSigMap == null || fidSigMap.isEmpty()) {
            label.setText(getString(R.string.multisig_tx_json_unsigned, number));
        } else {
            label.setText(getString(R.string.multisig_tx_json_signed_by, number));
            KeyCardContainer keyCardManager = new KeyCardContainer(this, keyCardContainer, ChooseMode.WITHOUT_CHOOSE);
            for (String fid : fidSigMap.keySet()) {
                keyCardManager.addKeyCard(new KeyInfo(fid));
            }
        }

        cardView.setOnLongClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(R.string.delete);
            popup.setOnMenuItemClickListener(item -> {
                rawTxInfoList.remove(rawTxInfo);
                if (rawTxInfoList.isEmpty()) txHex = null;
                renderTxInfo();
                return true;
            });
            popup.show();
            return true;
        });

        signaturesContainer.addView(cardView);
    }

    private void handleImport() {
        // A JSON left in the box counts as added
        if (jsonInput.getText() != null && !jsonInput.getText().toString().trim().isEmpty()) {
            if (!addJsonInBox()) return;
        }
        if (rawTxInfoList.isEmpty()) {
            showToast(getString(R.string.please_input_json));
            return;
        }

        List<String> signedJsons = new ArrayList<>();
        for (RawTxInfo info : rawTxInfoList) {
            if (info.getFidSigMap() != null && !info.getFidSigMap().isEmpty()) {
                signedJsons.add(info.toJsonWithSenderInfo());
            }
        }

        RawTxInfo result;
        if (signedJsons.isEmpty()) {
            result = rawTxInfoList.get(0);
        } else {
            ReplyBody replyBody = txHandler.mergeMultisignTxData(signedJsons.toArray(new String[0]));
            if (replyBody == null || replyBody.getCode() != 0 || replyBody.getData() == null) {
                showToast(getString(R.string.failed_to_merge_signatures,
                        replyBody != null ? replyBody.getMessage() : getString(R.string.invalid_multisign_data)));
                return;
            }
            result = (RawTxInfo) replyBody.getData();
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra(ImportActivity.EXTRA_IMPORT_RESULT, result.toJsonWithSenderInfo());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void handleClear() {
        jsonInput.setText("");
        rawTxInfoList.clear();
        txHex = null;
        renderTxInfo();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void loadFile(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                showToast(getString(R.string.failed_to_load_file));
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            jsonInput.setText(out.toString(StandardCharsets.UTF_8.name()).trim());
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to read file: %s", e.getMessage());
            showToast(getString(R.string.failed_to_load_file));
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TEXT_REQUEST_CODE && qrContent != null) {
            jsonInput.setText(qrContent);
        }
    }
}
