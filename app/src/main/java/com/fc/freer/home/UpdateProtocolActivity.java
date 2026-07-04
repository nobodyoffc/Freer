package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.PROTOCOL;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.ProtocolOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.freer.manager.ProtocolManager;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateProtocolActivity extends BaseCryptoActivity {

    public static final String EXTRA_PROTOCOL_JSON = "extra_protocol_json";

    private Protocol currentProtocol;
    private TextView protocolIdValue;
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
    private ImageButton updateButton;
    private ImageButton backButton;

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
        return R.layout.activity_update_protocol;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_protocol);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get protocol data from intent
        String protocolJson = getIntent().getStringExtra(EXTRA_PROTOCOL_JSON);
        if (protocolJson != null) {
            currentProtocol = JsonUtils.fromJson(protocolJson, Protocol.class);
        }

        if (currentProtocol == null) {
            ToastUtils.makeText(this, R.string.protocol_data_not_found);
            finish();
            return;
        }

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
        populateFields();

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.typeView, R.id.scanIcon, QR_SCAN_TYPE);
        TextIconsUtils.setupTextIcons(this, R.id.snView, R.id.scanIcon, QR_SCAN_SN);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        TextIconsUtils.setupTextIcons(this, R.id.didView, R.id.scanIcon, QR_SCAN_DID);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.langView, R.id.scanIcon, QR_SCAN_LANG);
        TextIconsUtils.setupTextIcons(this, R.id.fileUrlsView, R.id.scanIcon, QR_SCAN_FILE_URLS);
        TextIconsUtils.setupTextIcons(this, R.id.preDidView, R.id.scanIcon, QR_SCAN_PRE_DID);
        TextIconsUtils.setupTextIcons(this, R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS);
    }

    @Override
    protected void initializeViews() {
        protocolIdValue = findViewById(R.id.protocolIdValue);

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
        nameInput.setHint(R.string.name_required);

        typeInput = typeView.findViewById(R.id.textInput);
        typeInput.setHint(R.string.type);

        snInput = snView.findViewById(R.id.textInput);
        snInput.setHint(R.string.serial_number);

        verInput = verView.findViewById(R.id.textInput);
        verInput.setHint(R.string.version);

        didInput = didView.findViewById(R.id.textInput);
        didInput.setHint(R.string.did);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.description);

        langInput = langView.findViewById(R.id.textInput);
        langInput.setHint(R.string.language);

        fileUrlsInput = fileUrlsView.findViewById(R.id.textInput);
        fileUrlsInput.setHint(R.string.file_urls_comma_separated);

        preDidInput = preDidView.findViewById(R.id.textInput);
        preDidInput.setHint(R.string.pre_did);

        waitersInput = waitersView.findViewById(R.id.textInput);
        waitersInput.setHint(R.string.waiters_comma_separated);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        updateButton = findViewById(R.id.updateButton);
        backButton = findViewById(R.id.back_button);
    }

    private void populateFields() {
        if (currentProtocol == null) return;

        protocolIdValue.setText(currentProtocol.getId());

        if (currentProtocol.getName() != null) {
            nameInput.setText(currentProtocol.getName());
        }
        if (currentProtocol.getType() != null) {
            typeInput.setText(currentProtocol.getType());
        }
        if (currentProtocol.getSn() != null) {
            snInput.setText(currentProtocol.getSn());
        }
        if (currentProtocol.getVer() != null) {
            verInput.setText(currentProtocol.getVer());
        }
        if (currentProtocol.getDid() != null) {
            didInput.setText(currentProtocol.getDid());
        }
        if (currentProtocol.getDesc() != null) {
            descInput.setText(currentProtocol.getDesc());
        }
        if (currentProtocol.getLang() != null) {
            langInput.setText(currentProtocol.getLang());
        }
        if (currentProtocol.getHome() != null) {
            fileUrlsInput.setText(formatStringMap(currentProtocol.getHome()));
        }
        if (currentProtocol.getPrePid() != null) {
            preDidInput.setText(currentProtocol.getPrePid());
        }
        if (currentProtocol.getWaiters() != null) {
            waitersInput.setText(String.join(", ", currentProtocol.getWaiters()));
        }
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

        updateButton.setOnClickListener(v -> {
            hideKeyboard();
            updateProtocol();
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
        if (currentProtocol == null) {
            ToastUtils.makeText(this, R.string.protocol_data_not_found);
            return;
        }

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

        // Verify ownership
        if (!liveKeyInfo.getId().equals(currentProtocol.getOwner())) {
            ToastUtils.makeText(this, R.string.only_owner_can_update);
            return;
        }

        // Create updated protocol object - keep the original ID
        Protocol updatedProtocol = new Protocol();
        updatedProtocol.setId(currentProtocol.getId());
        updatedProtocol.setOwner(currentProtocol.getOwner());
        updatedProtocol.setName(name);
        if (!type.isEmpty()) updatedProtocol.setType(type);
        if (!sn.isEmpty()) updatedProtocol.setSn(sn);
        if (!ver.isEmpty()) updatedProtocol.setVer(ver);
        if (!did.isEmpty()) updatedProtocol.setDid(did);
        if (!desc.isEmpty()) updatedProtocol.setDesc(desc);
        if (!lang.isEmpty()) updatedProtocol.setLang(lang);
        if (homeMap != null) updatedProtocol.setHome(homeMap);
        if (!preDid.isEmpty()) updatedProtocol.setPrePid(preDid);
        if (waiters != null) updatedProtocol.setWaiters(waiters);
        
        // Mark as off-chain (false = saved but not carved yet)
        updatedProtocol.setOnChain(false);
        updatedProtocol.setActive(true);
        updatedProtocol.setLastTime(System.currentTimeMillis());

        // Save to database
        ProtocolManager protocolManager = ProtocolManager.getInstance();
        if (protocolManager != null) {
            protocolManager.updateProtocol(updatedProtocol);
            protocolManager.commit();
            ToastUtils.makeText(this, R.string.protocol_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.protocol_not_ready_try_later);
        }
    }

    private void updateProtocol() {
        if (currentProtocol == null) {
            ToastUtils.makeText(this, R.string.protocol_data_not_found);
            return;
        }

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

        // Verify ownership
        if (!liveKeyInfo.getId().equals(currentProtocol.getOwner())) {
            ToastUtils.makeText(this, R.string.only_owner_can_update);
            return;
        }

        // Determine if this is a new protocol (publish) or existing protocol (update)
        boolean isNewProtocol = currentProtocol.getId() != null && currentProtocol.getId().startsWith("local_");
        
        // Create FEIP data
        ProtocolOpData protocolOpData;
        if (isNewProtocol) {
            protocolOpData = ProtocolOpData.makePublish(
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
        } else {
            protocolOpData = ProtocolOpData.makeUpdate(
                currentProtocol.getId(),
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
        }

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
                                boolean wasNewProtocol = currentProtocol.getId() != null && currentProtocol.getId().startsWith("local_");
                                
                                if (wasNewProtocol) {
                                    ProtocolManager protocolManager = ProtocolManager.getInstance();
                                    if (protocolManager != null) {
                                        protocolManager.removeProtocol(currentProtocol);
                                        
                                        Protocol updatedProtocol = new Protocol();
                                        updatedProtocol.setId(txId);
                                        updatedProtocol.setOwner(currentProtocol.getOwner());
                                        updatedProtocol.setName(name);
                                        if (!type.isEmpty()) updatedProtocol.setType(type);
                                        if (!sn.isEmpty()) updatedProtocol.setSn(sn);
                                        if (!ver.isEmpty()) updatedProtocol.setVer(ver);
                                        if (!did.isEmpty()) updatedProtocol.setDid(did);
                                        if (!desc.isEmpty()) updatedProtocol.setDesc(desc);
                                        if (!lang.isEmpty()) updatedProtocol.setLang(lang);
                                        if (homeMap != null) updatedProtocol.setHome(homeMap);
                                        if (!preDid.isEmpty()) updatedProtocol.setPrePid(preDid);
                                        if (waiters != null) updatedProtocol.setWaiters(waiters);
                                        
                                        updatedProtocol.setOnChain(null);
                                        updatedProtocol.setActive(true);
                                        updatedProtocol.setLastTime(System.currentTimeMillis());
                                        
                                        protocolManager.addProtocol(updatedProtocol);
                                        protocolManager.commit();
                                    }
                                    
                                }

                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(UpdateProtocolActivity.this,
                                    getString(R.string.failed_to_update_protocol) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(UpdateProtocolActivity.this,
                                    R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(UpdateProtocolActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(UpdateProtocolActivity.this, signedTxHex);
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

    private List<String> parseCommaSeparated(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String[] parts = input.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                nonEmpty.add(part);
            }
        }
        return nonEmpty.isEmpty() ? null : nonEmpty;
    }

    private String formatStringMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                parts.add(entry.getKey());
            } else {
                parts.add(entry.getKey() + ":" + entry.getValue());
            }
        }
        return String.join(", ", parts);
    }

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

