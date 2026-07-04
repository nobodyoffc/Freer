package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.CODE;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.CodeOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.CodeManager;
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
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;

public class CreateCodeActivity extends BaseCryptoActivity {

    private TextInputEditText nameInput;
    private TextInputEditText verInput;
    private TextInputEditText didInput;
    private TextInputEditText descInput;
    private TextInputEditText langsInput;
    private TextInputEditText urlsInput;
    private TextInputEditText protocolsInput;
    private TextInputEditText waitersInput;

    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_VER = 1002;
    private static final int QR_SCAN_DID = 1003;
    private static final int QR_SCAN_DESC = 1004;
    private static final int QR_SCAN_LANGS = 1005;
    private static final int QR_SCAN_URLS = 1006;
    private static final int QR_SCAN_PROTOCOLS = 1007;
    private static final int QR_SCAN_WAITERS = 1008;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_code;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_code);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();

        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        TextIconsUtils.setupTextIcons(this, R.id.didView, R.id.scanIcon, QR_SCAN_DID);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.langsView, R.id.scanIcon, QR_SCAN_LANGS);
        TextIconsUtils.setupTextIcons(this, R.id.urlsView, R.id.scanIcon, QR_SCAN_URLS);
        TextIconsUtils.setupTextIcons(this, R.id.protocolsView, R.id.scanIcon, QR_SCAN_PROTOCOLS);
        TextIconsUtils.setupTextIcons(this, R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS);
    }

    @Override
    protected void initializeViews() {
        View nameView = findViewById(R.id.nameView);
        View verView = findViewById(R.id.verView);
        View didView = findViewById(R.id.didView);
        View descView = findViewById(R.id.descView);
        View langsView = findViewById(R.id.langsView);
        View urlsView = findViewById(R.id.urlsView);
        View protocolsView = findViewById(R.id.protocolsView);
        View waitersView = findViewById(R.id.waitersView);

        nameInput = nameView.findViewById(R.id.textInput);
        nameInput.setHint(R.string.name_required);

        verInput = verView.findViewById(R.id.textInput);
        verInput.setHint(R.string.version);

        didInput = didView.findViewById(R.id.textInput);
        didInput.setHint(R.string.did);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.description);

        langsInput = langsView.findViewById(R.id.textInput);
        langsInput.setHint(R.string.languages_comma_separated);

        urlsInput = urlsView.findViewById(R.id.textInput);
        urlsInput.setHint(R.string.urls_comma_separated);

        protocolsInput = protocolsView.findViewById(R.id.textInput);
        protocolsInput.setHint(R.string.protocols_comma_separated);

        waitersInput = waitersView.findViewById(R.id.textInput);
        waitersInput.setHint(R.string.waiters_comma_separated);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> { hideKeyboard(); clearInputs(); });
        saveButton.setOnClickListener(v -> { hideKeyboard(); saveCodeToDatabase(); });
        publishButton.setOnClickListener(v -> { hideKeyboard(); publishCode(); });
        backButton.setOnClickListener(v -> { hideKeyboard(); finish(); });
    }

    private void clearInputs() {
        nameInput.setText("");
        verInput.setText("");
        didInput.setText("");
        descInput.setText("");
        langsInput.setText("");
        urlsInput.setText("");
        protocolsInput.setText("");
        waitersInput.setText("");
    }

    private void saveCodeToDatabase() {
        String name = getText(nameInput);
        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_name);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        Code newCode = new Code();
        newCode.setId("local_" + System.currentTimeMillis());
        newCode.setOwner(liveKeyInfo.getId());
        newCode.setName(name);
        String ver = getText(verInput);
        if (!ver.isEmpty()) newCode.setVer(ver);
        String did = getText(didInput);
        if (!did.isEmpty()) newCode.setDid(did);
        String desc = getText(descInput);
        if (!desc.isEmpty()) newCode.setDesc(desc);
        java.util.List<String> langs = parseCommaSeparated(getText(langsInput));
        if (langs != null) newCode.setLangs(langs);
        Map<String, String> homeMap = parseUrlMap(getText(urlsInput));
        if (homeMap != null) newCode.setHome(homeMap);
        java.util.List<String> protocols = parseCommaSeparated(getText(protocolsInput));
        if (protocols != null) newCode.setProtocols(protocols);
        java.util.List<String> waiters = parseCommaSeparated(getText(waitersInput));
        if (waiters != null) newCode.setWaiters(waiters);
        
        newCode.setOnChain(false);
        newCode.setActive(true);
        newCode.setLastTime(System.currentTimeMillis());

        CodeManager codeManager = CodeManager.getInstance();
        if (codeManager != null) {
            codeManager.addCode(newCode);
            codeManager.commit();
            ToastUtils.makeText(this, R.string.code_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.code_not_ready_try_later);
        }
    }

    private void publishCode() {
        String name = getText(nameInput);
        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_name);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        String ver = getText(verInput);
        String did = getText(didInput);
        String desc = getText(descInput);
        java.util.List<String> langs = parseCommaSeparated(getText(langsInput));
        Map<String, String> homeMap = parseUrlMap(getText(urlsInput));
        java.util.List<String> protocols = parseCommaSeparated(getText(protocolsInput));
        java.util.List<String> waiters = parseCommaSeparated(getText(waitersInput));

        CodeOpData codeOpData = CodeOpData.makePublish(
            name,
            ver.isEmpty() ? null : ver,
            did.isEmpty() ? null : did,
            desc.isEmpty() ? null : desc,
            langs, homeMap, protocols, waiters
        );

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
                                Code carvedCode = new Code();
                                carvedCode.setId(txId);
                                carvedCode.setOwner(liveKeyInfo.getId());
                                carvedCode.setName(name);
                                if (!ver.isEmpty()) carvedCode.setVer(ver);
                                if (!did.isEmpty()) carvedCode.setDid(did);
                                if (!desc.isEmpty()) carvedCode.setDesc(desc);
                                if (langs != null) carvedCode.setLangs(langs);
                                if (homeMap != null) carvedCode.setHome(homeMap);
                                if (protocols != null) carvedCode.setProtocols(protocols);
                                if (waiters != null) carvedCode.setWaiters(waiters);
                                carvedCode.setOnChain(null);
                                carvedCode.setActive(true);
                                carvedCode.setLastTime(System.currentTimeMillis());

                                CodeManager codeManager = CodeManager.getInstance();
                                if (codeManager != null) {
                                    codeManager.addCode(carvedCode);
                                    codeManager.commit();
                                }

                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(CreateCodeActivity.this, 
                                getString(R.string.failed_to_publish_code) + ": " + errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateCodeActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateCodeActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> txSender.showSignedTxAsQR(CreateCodeActivity.this, signedTxHex));
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

    private java.util.List<String> parseCommaSeparated(String input) {
        if (input == null || input.isEmpty()) return null;
        String[] parts = input.split(",");
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) nonEmpty.add(trimmed);
        }
        return nonEmpty.isEmpty() ? null : nonEmpty;
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

