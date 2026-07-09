package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.CODE;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.CodeOpData;
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
import com.fc.freer.utils.EntityFieldPickers;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.freer.manager.CodeManager;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateCodeActivity extends BaseCryptoActivity {

    public static final String EXTRA_CODE_JSON = "extra_code_json";

    private Code currentCode;
    private TextView codeIdValue;
    private TextInputEditText nameInput, verInput, didInput, descInput;
    private TextInputEditText langsInput, urlsInput, protocolsInput, waitersInput;
    private ImageButton clearButton, saveButton, updateButton, backButton;

    private EntityFieldPickers pickers;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_VER = 1002;
    private static final int QR_SCAN_DID = 1003;
    private static final int QR_SCAN_DESC = 1004;
    private static final int QR_SCAN_LANGS = 1005;
    private static final int QR_SCAN_URLS = 1006;
    private static final int QR_SCAN_PROTOCOLS = 1007;
    private static final int QR_SCAN_WAITERS = 1008;

    @Override
    protected int getLayoutId() { return R.layout.activity_update_code; }

    @Override
    protected String getActivityTitle() { return getString(R.string.update_code); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String codeJson = getIntent().getStringExtra(EXTRA_CODE_JSON);
        if (codeJson != null) currentCode = JsonUtils.fromJson(codeJson, Code.class);

        if (currentCode == null) {
            ToastUtils.makeText(this, R.string.code_data_not_found);
            finish();
            return;
        }

        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) hideKeyboard();
            return false;
        });

        ToolbarUtils.setupToolbar(this, getActivityTitle());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });

        setupButtons();
        populateFields();

        pickers = new EntityFieldPickers(this);

        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        // DID field: pick a local data DID via the file icon, or enter it manually.
        pickers.bindDidField(R.id.didView, R.id.scanIcon, QR_SCAN_DID, didInput);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.langsView, R.id.scanIcon, QR_SCAN_LANGS);
        TextIconsUtils.setupTextIcons(this, R.id.urlsView, R.id.scanIcon, QR_SCAN_URLS);
        // Protocols are entity ids: pick them from the protocol list via the file icon.
        pickers.bindEntityIdField(R.id.protocolsView, R.id.scanIcon, QR_SCAN_PROTOCOLS, protocolsInput, true, ProtocolActivity.class);
        // Waiters are FIDs: search them on chain (or pick from contacts) via the people icon.
        pickers.bindFidField(R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS, waitersInput, true);
    }

    @Override
    protected void initializeViews() {
        codeIdValue = findViewById(R.id.codeIdValue);

        View nameView = findViewById(R.id.nameView);
        View verView = findViewById(R.id.verView);
        View didView = findViewById(R.id.didView);
        View descView = findViewById(R.id.descView);
        View langsView = findViewById(R.id.langsView);
        View urlsView = findViewById(R.id.urlsView);
        View protocolsView = findViewById(R.id.protocolsView);
        View waitersView = findViewById(R.id.waitersView);

        nameInput = nameView.findViewById(R.id.textInput);
        setLabel(nameView, R.string.name_required);
        verInput = verView.findViewById(R.id.textInput);
        setLabel(verView, R.string.version);
        didInput = didView.findViewById(R.id.textInput);
        setLabel(didView, R.string.did);
        descInput = descView.findViewById(R.id.textInput);
        setLabel(descView, R.string.description);
        langsInput = langsView.findViewById(R.id.textInput);
        setLabel(langsView, R.string.languages_comma_separated);
        urlsInput = urlsView.findViewById(R.id.textInput);
        setLabel(urlsView, R.string.urls_comma_separated);
        protocolsInput = protocolsView.findViewById(R.id.textInput);
        setLabel(protocolsView, R.string.protocols_comma_separated);
        waitersInput = waitersView.findViewById(R.id.textInput);
        setLabel(waitersView, R.string.waiters_comma_separated);
        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        updateButton = findViewById(R.id.updateButton);
        backButton = findViewById(R.id.back_button);
    }

    private void populateFields() {
        if (currentCode == null) return;
        codeIdValue.setText(currentCode.getId());
        if (currentCode.getName() != null) nameInput.setText(currentCode.getName());
        if (currentCode.getVer() != null) verInput.setText(currentCode.getVer());
        if (currentCode.getDid() != null) didInput.setText(currentCode.getDid());
        if (currentCode.getDesc() != null) descInput.setText(currentCode.getDesc());
        if (currentCode.getLangs() != null) langsInput.setText(String.join(", ", currentCode.getLangs()));
        if (currentCode.getHome() != null) urlsInput.setText(formatStringMap(currentCode.getHome()));
        if (currentCode.getProtocols() != null) protocolsInput.setText(String.join(", ", currentCode.getProtocols()));
        if (currentCode.getWaiters() != null) waitersInput.setText(String.join(", ", currentCode.getWaiters()));
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> { hideKeyboard(); clearInputs(); });
        saveButton.setOnClickListener(v -> { hideKeyboard(); saveCodeToDatabase(); });
        updateButton.setOnClickListener(v -> { hideKeyboard(); updateCode(); });
        backButton.setOnClickListener(v -> { hideKeyboard(); finish(); });
    }

    private void clearInputs() {
        nameInput.setText(""); verInput.setText(""); didInput.setText(""); descInput.setText("");
        langsInput.setText(""); urlsInput.setText(""); protocolsInput.setText(""); waitersInput.setText("");
    }

    private void saveCodeToDatabase() {
        if (currentCode == null) { ToastUtils.makeText(this, R.string.code_data_not_found); return; }
        String name = getText(nameInput);
        if (name.isEmpty()) { ToastUtils.makeText(this, R.string.please_enter_name); return; }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) { ToastUtils.makeText(this, R.string.no_active_fid); return; }
        if (!liveKeyInfo.getId().equals(currentCode.getOwner())) { ToastUtils.makeText(this, R.string.only_owner_can_update); return; }

        Code updatedCode = new Code();
        updatedCode.setId(currentCode.getId());
        updatedCode.setOwner(currentCode.getOwner());
        updatedCode.setName(name);
        String ver = getText(verInput); if (!ver.isEmpty()) updatedCode.setVer(ver);
        String did = getText(didInput); if (!did.isEmpty()) updatedCode.setDid(did);
        String desc = getText(descInput); if (!desc.isEmpty()) updatedCode.setDesc(desc);
        java.util.List<String> langs = parseCommaSeparated(getText(langsInput)); if (langs != null) updatedCode.setLangs(langs);
        Map<String, String> homeMap = parseUrlMap(getText(urlsInput)); if (homeMap != null) updatedCode.setHome(homeMap);
        java.util.List<String> protocols = parseCommaSeparated(getText(protocolsInput)); if (protocols != null) updatedCode.setProtocols(protocols);
        java.util.List<String> waiters = parseCommaSeparated(getText(waitersInput)); if (waiters != null) updatedCode.setWaiters(waiters);
        updatedCode.setOnChain(false);
        updatedCode.setActive(true);
        updatedCode.setLastTime(System.currentTimeMillis());

        CodeManager codeManager = CodeManager.getInstance();
        if (codeManager != null) {
            codeManager.updateCode(updatedCode);
            codeManager.commit();
            ToastUtils.makeText(this, R.string.code_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.code_not_ready_try_later);
        }
    }

    private void updateCode() {
        if (currentCode == null) { ToastUtils.makeText(this, R.string.code_data_not_found); return; }
        String name = getText(nameInput);
        if (name.isEmpty()) { ToastUtils.makeText(this, R.string.please_enter_name); return; }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) { ToastUtils.makeText(this, R.string.no_active_fid); return; }
        if (!liveKeyInfo.getId().equals(currentCode.getOwner())) { ToastUtils.makeText(this, R.string.only_owner_can_update); return; }

        String ver = getText(verInput);
        String did = getText(didInput);
        String desc = getText(descInput);
        java.util.List<String> langs = parseCommaSeparated(getText(langsInput));
        Map<String, String> homeMap = parseUrlMap(getText(urlsInput));
        java.util.List<String> protocols = parseCommaSeparated(getText(protocolsInput));
        java.util.List<String> waiters = parseCommaSeparated(getText(waitersInput));

        boolean isNewCode = currentCode.getId() != null && currentCode.getId().startsWith("local_");
        CodeOpData codeOpData = isNewCode ?
            CodeOpData.makePublish(name, ver.isEmpty() ? null : ver, did.isEmpty() ? null : did, desc.isEmpty() ? null : desc, langs, homeMap, protocols, waiters) :
            CodeOpData.makeUpdate(currentCode.getId(), name, ver.isEmpty() ? null : ver, did.isEmpty() ? null : did, desc.isEmpty() ? null : desc, langs, homeMap, protocols, waiters);

        Feip feip = Feip.fromName(CODE);
        feip.setData(codeOpData);
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
                                boolean wasNewCode = currentCode.getId() != null && currentCode.getId().startsWith("local_");
                                if (wasNewCode) {
                                    CodeManager codeManager = CodeManager.getInstance();
                                    if (codeManager != null) {
                                        codeManager.removeCode(currentCode);
                                        Code updatedCode = new Code();
                                        updatedCode.setId(txId);
                                        updatedCode.setOwner(currentCode.getOwner());
                                        updatedCode.setName(name);
                                        if (!ver.isEmpty()) updatedCode.setVer(ver);
                                        if (!did.isEmpty()) updatedCode.setDid(did);
                                        if (!desc.isEmpty()) updatedCode.setDesc(desc);
                                        if (langs != null) updatedCode.setLangs(langs);
                                        if (homeMap != null) updatedCode.setHome(homeMap);
                                        if (protocols != null) updatedCode.setProtocols(protocols);
                                        if (waiters != null) updatedCode.setWaiters(waiters);
                                        updatedCode.setOnChain(null);
                                        updatedCode.setActive(true);
                                        updatedCode.setLastTime(System.currentTimeMillis());
                                        codeManager.addCode(updatedCode);
                                        codeManager.commit();
                                    }
                                }
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(UpdateCodeActivity.this, getString(R.string.failed_to_update_code) + ": " + errorMessage));
                        }

                        @Override public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(UpdateCodeActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(UpdateCodeActivity.this, rawTxInfo);
                            });
                        }

                        @Override public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> txSender.showSignedTxAsQR(UpdateCodeActivity.this, signedTxHex));
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private String getText(TextInputEditText input) { return input.getText() != null ? input.getText().toString().trim() : ""; }

    /**
     * Set a persistent floating label on the field's TextInputLayout so it stays visible above the
     * pre-populated content when editing an existing entity.
     */
    private void setLabel(View container, int hintRes) {
        TextInputLayout layout = container.findViewById(R.id.textInputWithScanLayout);
        if (layout != null) {
            layout.setHint(getString(hintRes));
        }
    }

    private java.util.List<String> parseCommaSeparated(String input) {
        if (input == null || input.isEmpty()) return null;
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : input.split(",")) { String t = part.trim(); if (!t.isEmpty()) nonEmpty.add(t); }
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
            case QR_SCAN_NAME: nameInput.setText(qrContent); break;
            case QR_SCAN_VER: verInput.setText(qrContent); break;
            case QR_SCAN_DID: didInput.setText(qrContent); break;
            case QR_SCAN_DESC: descInput.setText(qrContent); break;
            case QR_SCAN_LANGS: langsInput.setText(qrContent); break;
            case QR_SCAN_URLS: urlsInput.setText(qrContent); break;
            case QR_SCAN_PROTOCOLS: protocolsInput.setText(qrContent); break;
            case QR_SCAN_WAITERS: waitersInput.setText(qrContent); break;
        }
    }
}

