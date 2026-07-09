package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.PROTOCOL;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.ProtocolOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.ProtocolManager;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.EntityFieldPickers;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreateProtocolActivity extends BaseCryptoActivity {

    private TextInputEditText nameInput;
    private TextInputEditText typeInput;
    private TextInputEditText snInput;
    private TextInputEditText verInput;
    private TextInputEditText didInput;
    private TextInputEditText descInput;
    private TextInputEditText langInput;
    private TextInputEditText fileUrlsInput;
    private TextInputEditText preDidInput;
    private TextInputEditText waitersInput;

    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private EntityFieldPickers pickers;

    // QR scan request codes
    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_TYPE = 1002;
    private static final int QR_SCAN_SN = 1003;
    private static final int QR_SCAN_VER = 1004;
    private static final int QR_SCAN_DID = 1005;
    private static final int QR_SCAN_DESC = 1006;
    private static final int QR_SCAN_LANG = 1007;
    private static final int QR_SCAN_FILE_URLS = 1008;
    private static final int QR_SCAN_PRE_DID = 1009;
    private static final int QR_SCAN_WAITERS = 1010;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_protocol;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_protocol);
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

        pickers = new EntityFieldPickers(this);

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.typeView, R.id.scanIcon, QR_SCAN_TYPE);
        TextIconsUtils.setupTextIcons(this, R.id.snView, R.id.scanIcon, QR_SCAN_SN);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        // DID field: pick a local data DID via the file icon, or enter it manually.
        pickers.bindDidField(R.id.didView, R.id.scanIcon, QR_SCAN_DID, didInput);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.langView, R.id.scanIcon, QR_SCAN_LANG);
        TextIconsUtils.setupTextIcons(this, R.id.fileUrlsView, R.id.scanIcon, QR_SCAN_FILE_URLS);
        // Pre-PID is a protocol id: pick a previous protocol via the file icon, or enter it manually.
        pickers.bindEntityIdField(R.id.preDidView, R.id.scanIcon, QR_SCAN_PRE_DID, preDidInput, false, ProtocolActivity.class);
        // Waiters are FIDs: search them on chain (or pick from contacts) via the people icon.
        pickers.bindFidField(R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS, waitersInput, true);
    }

    @Override
    protected void initializeViews() {
        View nameView = findViewById(R.id.nameView);
        View typeView = findViewById(R.id.typeView);
        View snView = findViewById(R.id.snView);
        View verView = findViewById(R.id.verView);
        View didView = findViewById(R.id.didView);
        View descView = findViewById(R.id.descView);
        View langView = findViewById(R.id.langView);
        View fileUrlsView = findViewById(R.id.fileUrlsView);
        View preDidView = findViewById(R.id.preDidView);
        View waitersView = findViewById(R.id.waitersView);

        nameInput = nameView.findViewById(R.id.textInput);
        setLabel(nameView, R.string.name_required);

        typeInput = typeView.findViewById(R.id.textInput);
        setLabel(typeView, R.string.type);

        snInput = snView.findViewById(R.id.textInput);
        setLabel(snView, R.string.serial_number);

        verInput = verView.findViewById(R.id.textInput);
        setLabel(verView, R.string.version);

        didInput = didView.findViewById(R.id.textInput);
        setLabel(didView, R.string.did);

        descInput = descView.findViewById(R.id.textInput);
        setLabel(descView, R.string.description);

        langInput = langView.findViewById(R.id.textInput);
        setLabel(langView, R.string.language);

        fileUrlsInput = fileUrlsView.findViewById(R.id.textInput);
        setLabel(fileUrlsView, R.string.file_urls_comma_separated);

        preDidInput = preDidView.findViewById(R.id.textInput);
        setLabel(preDidView, R.string.pre_did);

        waitersInput = waitersView.findViewById(R.id.textInput);
        setLabel(waitersView, R.string.waiters_comma_separated);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        saveButton.setOnClickListener(v -> {
            hideKeyboard();
            saveProtocolToDatabase();
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishProtocol();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        nameInput.setText("");
        typeInput.setText("");
        snInput.setText("");
        verInput.setText("");
        didInput.setText("");
        descInput.setText("");
        langInput.setText("");
        fileUrlsInput.setText("");
        preDidInput.setText("");
        waitersInput.setText("");
    }

    private void saveProtocolToDatabase() {
        String name = getText(nameInput);
        String type = getText(typeInput);
        String sn = getText(snInput);
        String ver = getText(verInput);
        String did = getText(didInput);
        String desc = getText(descInput);
        String lang = getText(langInput);
        String fileUrlsStr = getText(fileUrlsInput);
        String preDid = getText(preDidInput);
        String waitersStr = getText(waitersInput);

        // Validate required field
        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_name);
            return;
        }

        // Parse comma-separated strings to arrays
        Map<String, String> homeMap = parseUrlMap(fileUrlsStr);
        List<String> waiters = parseCommaSeparated(waitersStr);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create protocol object with a unique local ID
        Protocol newProtocol = new Protocol();
        newProtocol.setId("local_" + System.currentTimeMillis());
        newProtocol.setOwner(liveKeyInfo.getId());
        newProtocol.setName(name);
        if (!type.isEmpty()) newProtocol.setType(type);
        if (!sn.isEmpty()) newProtocol.setSn(sn);
        if (!ver.isEmpty()) newProtocol.setVer(ver);
        if (!did.isEmpty()) newProtocol.setDid(did);
        if (!desc.isEmpty()) newProtocol.setDesc(desc);
        if (!lang.isEmpty()) newProtocol.setLang(lang);
        if (homeMap != null) newProtocol.setHome(homeMap);
        if (!preDid.isEmpty()) newProtocol.setPrePid(preDid);
        if (waiters != null) newProtocol.setWaiters(waiters);
        
        // Mark as off-chain (false = not carved yet, can be carved)
        newProtocol.setOnChain(false);
        newProtocol.setActive(true);
        newProtocol.setLastTime(System.currentTimeMillis());

        // Save to database
        ProtocolManager protocolManager = ProtocolManager.getInstance();
        if (protocolManager != null) {
            protocolManager.addProtocol(newProtocol);
            protocolManager.commit();
            ToastUtils.makeText(this, R.string.protocol_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.protocol_not_ready_try_later);
        }
    }

    private void publishProtocol() {
        String name = getText(nameInput);
        String type = getText(typeInput);
        String sn = getText(snInput);
        String ver = getText(verInput);
        String did = getText(didInput);
        String desc = getText(descInput);
        String lang = getText(langInput);
        String fileUrlsStr = getText(fileUrlsInput);
        String preDid = getText(preDidInput);
        String waitersStr = getText(waitersInput);

        // Validate required field
        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_name);
            return;
        }

        // Parse comma-separated strings to arrays
        Map<String, String> homeMap = parseUrlMap(fileUrlsStr);
        List<String> waiters = parseCommaSeparated(waitersStr);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create FEIP data
        ProtocolOpData protocolOpData = ProtocolOpData.makePublish(
            sn.isEmpty() ? null : sn,
            name,
            type.isEmpty() ? null : type,
            ver.isEmpty() ? null : ver,
            did.isEmpty() ? null : did,
            desc.isEmpty() ? null : desc,
            lang.isEmpty() ? null : lang,
            homeMap,
            preDid.isEmpty() ? null : preDid,
            waiters
        );

        Feip feip = Feip.fromName(PROTOCOL);
        feip.setData(protocolOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                // Create protocol object with transaction ID and save to local database
                                Protocol carvedProtocol = new Protocol();
                                carvedProtocol.setId(txId);
                                carvedProtocol.setOwner(liveKeyInfo.getId());
                                carvedProtocol.setName(name);
                                if (!type.isEmpty()) carvedProtocol.setType(type);
                                if (!sn.isEmpty()) carvedProtocol.setSn(sn);
                                if (!ver.isEmpty()) carvedProtocol.setVer(ver);
                                if (!did.isEmpty()) carvedProtocol.setDid(did);
                                if (!desc.isEmpty()) carvedProtocol.setDesc(desc);
                                if (!lang.isEmpty()) carvedProtocol.setLang(lang);
                                if (homeMap != null) carvedProtocol.setHome(homeMap);
                                if (!preDid.isEmpty()) carvedProtocol.setPrePid(preDid);
                                if (waiters != null) carvedProtocol.setWaiters(waiters);
                                
                                // Set onChain to null (pending confirmation)
                                carvedProtocol.setOnChain(null);
                                carvedProtocol.setActive(true);
                                carvedProtocol.setLastTime(System.currentTimeMillis());

                                // Save to database
                                ProtocolManager protocolManager = ProtocolManager.getInstance();
                                if (protocolManager != null) {
                                    protocolManager.addProtocol(carvedProtocol);
                                    protocolManager.commit();
                                }

                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateProtocolActivity.this, 
                                    getString(R.string.failed_to_publish_protocol) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateProtocolActivity.this, 
                                    R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateProtocolActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(CreateProtocolActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    /**
     * Set a persistent floating label on the field's TextInputLayout so it stays visible above the
     * content once the user starts typing.
     */
    private void setLabel(View container, int hintRes) {
        TextInputLayout layout = container.findViewById(R.id.textInputWithScanLayout);
        if (layout != null) {
            layout.setHint(getString(hintRes));
        }
    }

    private List<String> parseCommaSeparated(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String[] parts = input.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        // Filter out empty strings
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                nonEmpty.add(part);
            }
        }
        return nonEmpty.isEmpty() ? null : nonEmpty;
    }

    /** Comma-separated key:value or key=value pairs for the home map. */
    private Map<String, String> parseUrlMap(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        for (String part : input.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            String key;
            String value;
            int colonIndex = part.indexOf(':');
            int equalsIndex = part.indexOf('=');
            if (colonIndex > 0) {
                key = part.substring(0, colonIndex).trim();
                value = part.substring(colonIndex + 1).trim();
            } else if (equalsIndex > 0) {
                key = part.substring(0, equalsIndex).trim();
                value = part.substring(equalsIndex + 1).trim();
            } else {
                key = part;
                value = "";
            }
            if (!key.isEmpty()) {
                map.put(key, value);
            }
        }
        return map.isEmpty() ? null : map;
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_NAME:
                nameInput.setText(qrContent);
                break;
            case QR_SCAN_TYPE:
                typeInput.setText(qrContent);
                break;
            case QR_SCAN_SN:
                snInput.setText(qrContent);
                break;
            case QR_SCAN_VER:
                verInput.setText(qrContent);
                break;
            case QR_SCAN_DID:
                didInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_LANG:
                langInput.setText(qrContent);
                break;
            case QR_SCAN_FILE_URLS:
                fileUrlsInput.setText(qrContent);
                break;
            case QR_SCAN_PRE_DID:
                preDidInput.setText(qrContent);
                break;
            case QR_SCAN_WAITERS:
                waitersInput.setText(qrContent);
                break;
        }
    }
}

