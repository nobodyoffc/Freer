package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.APP;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.AppOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.AppManager;
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

public class CreateAppActivity extends BaseCryptoActivity {

    private TextInputEditText stdNameInput;
    private TextInputEditText localNamesInput;
    private TextInputEditText typesInput;
    private TextInputEditText descInput;
    private TextInputEditText verInput;
    private TextInputEditText urlsInput;
    private TextInputEditText waitersInput;
    private TextInputEditText protocolsInput;
    private TextInputEditText codesInput;
    private TextInputEditText servicesInput;

    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    // QR scan request codes
    private static final int QR_SCAN_STD_NAME = 1001;
    private static final int QR_SCAN_LOCAL_NAMES = 1002;
    private static final int QR_SCAN_TYPES = 1003;
    private static final int QR_SCAN_DESC = 1004;
    private static final int QR_SCAN_VER = 1005;
    private static final int QR_SCAN_URLS = 1006;
    private static final int QR_SCAN_WAITERS = 1007;
    private static final int QR_SCAN_PROTOCOLS = 1008;
    private static final int QR_SCAN_CODES = 1009;
    private static final int QR_SCAN_SERVICES = 1010;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_app;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_app);
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

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.stdNameView, R.id.scanIcon, QR_SCAN_STD_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.localNamesView, R.id.scanIcon, QR_SCAN_LOCAL_NAMES);
        TextIconsUtils.setupTextIcons(this, R.id.typesView, R.id.scanIcon, QR_SCAN_TYPES);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        TextIconsUtils.setupTextIcons(this, R.id.urlsView, R.id.scanIcon, QR_SCAN_URLS);
        TextIconsUtils.setupTextIcons(this, R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS);
        TextIconsUtils.setupTextIcons(this, R.id.protocolsView, R.id.scanIcon, QR_SCAN_PROTOCOLS);
        TextIconsUtils.setupTextIcons(this, R.id.codesView, R.id.scanIcon, QR_SCAN_CODES);
        TextIconsUtils.setupTextIcons(this, R.id.servicesView, R.id.scanIcon, QR_SCAN_SERVICES);
    }

    @Override
    protected void initializeViews() {
        View stdNameView = findViewById(R.id.stdNameView);
        View localNamesView = findViewById(R.id.localNamesView);
        View typesView = findViewById(R.id.typesView);
        View descView = findViewById(R.id.descView);
        View verView = findViewById(R.id.verView);
        View urlsView = findViewById(R.id.urlsView);
        View waitersView = findViewById(R.id.waitersView);
        View protocolsView = findViewById(R.id.protocolsView);
        View codesView = findViewById(R.id.codesView);
        View servicesView = findViewById(R.id.servicesView);

        stdNameInput = stdNameView.findViewById(R.id.textInput);
        stdNameInput.setHint(R.string.standard_name_required);

        localNamesInput = localNamesView.findViewById(R.id.textInput);
        localNamesInput.setHint(R.string.local_names_comma_separated);

        typesInput = typesView.findViewById(R.id.textInput);
        typesInput.setHint(R.string.types_comma_separated);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.description);

        verInput = verView.findViewById(R.id.textInput);
        verInput.setHint(R.string.version);

        urlsInput = urlsView.findViewById(R.id.textInput);
        urlsInput.setHint(R.string.urls_comma_separated);

        waitersInput = waitersView.findViewById(R.id.textInput);
        waitersInput.setHint(R.string.waiters_comma_separated);

        protocolsInput = protocolsView.findViewById(R.id.textInput);
        protocolsInput.setHint(R.string.protocols_comma_separated);

        codesInput = codesView.findViewById(R.id.textInput);
        codesInput.setHint(R.string.codes_comma_separated);

        servicesInput = servicesView.findViewById(R.id.textInput);
        servicesInput.setHint(R.string.services_comma_separated);

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
            saveAppToDatabase();
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishApp();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        stdNameInput.setText("");
        localNamesInput.setText("");
        typesInput.setText("");
        descInput.setText("");
        verInput.setText("");
        urlsInput.setText("");
        waitersInput.setText("");
        protocolsInput.setText("");
        codesInput.setText("");
        servicesInput.setText("");
    }

    private void saveAppToDatabase() {
        String stdName = getText(stdNameInput);
        String localNamesStr = getText(localNamesInput);
        String typesStr = getText(typesInput);
        String desc = getText(descInput);
        String ver = getText(verInput);
        String urlsStr = getText(urlsInput);
        String waitersStr = getText(waitersInput);
        String protocolsStr = getText(protocolsInput);
        String codesStr = getText(codesInput);
        String servicesStr = getText(servicesInput);

        // Validate required field
        if (stdName.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_standard_name);
            return;
        }

        // Parse comma-separated strings to arrays
        Map<String, String> localNames = parseUrlMap(localNamesStr);
        java.util.List<String> types = parseCommaSeparated(typesStr);
        Map<String, String> homeMap = parseUrlMap(urlsStr);
        java.util.List<String> waiters = parseCommaSeparated(waitersStr);
        java.util.List<String> protocols = parseCommaSeparated(protocolsStr);
        java.util.List<String> codes = parseCommaSeparated(codesStr);
        java.util.List<String> services = parseCommaSeparated(servicesStr);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create app object with a unique local ID
        App newApp = new App();
        newApp.setId("local_" + System.currentTimeMillis()); // Generate local ID
        newApp.setOwner(liveKeyInfo.getId());
        newApp.setStdName(stdName);
        if (localNames != null) newApp.setLocalNames(localNames);
        if (!desc.isEmpty()) newApp.setDesc(desc);
        if (types != null) newApp.setTypes(types);
        if (!ver.isEmpty()) newApp.setVer(ver);
        if (homeMap != null) newApp.setHome(homeMap);
        if (waiters != null) newApp.setWaiters(waiters);
        if (protocols != null) newApp.setProtocols(protocols);
        if (codes != null) newApp.setCodes(codes);
        if (services != null) newApp.setServices(services);
        
        // Mark as off-chain (false = not carved yet, can be carved)
        newApp.setOnChain(false);
        newApp.setActive(true);
        newApp.setLastTime(System.currentTimeMillis());

        // Save to database
        AppManager appManager = AppManager.getInstance();
        if (appManager != null) {
            appManager.addApp(newApp);
            appManager.commit();
            ToastUtils.makeText(this, R.string.app_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.app_not_ready_try_later);
        }
    }

    private void publishApp() {
        String stdName = getText(stdNameInput);
        String localNamesStr = getText(localNamesInput);
        String typesStr = getText(typesInput);
        String desc = getText(descInput);
        String ver = getText(verInput);
        String urlsStr = getText(urlsInput);
        String waitersStr = getText(waitersInput);
        String protocolsStr = getText(protocolsInput);
        String codesStr = getText(codesInput);
        String servicesStr = getText(servicesInput);

        // Validate required field
        if (stdName.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_standard_name);
            return;
        }

        // Parse comma-separated strings to arrays
        Map<String, String> localNames = parseUrlMap(localNamesStr);
        java.util.List<String> types = parseCommaSeparated(typesStr);
        Map<String, String> homeMap = parseUrlMap(urlsStr);
        java.util.List<String> waiters = parseCommaSeparated(waitersStr);
        java.util.List<String> protocols = parseCommaSeparated(protocolsStr);
        java.util.List<String> codes = parseCommaSeparated(codesStr);
        java.util.List<String> services = parseCommaSeparated(servicesStr);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create FEIP data
        AppOpData appOpData = AppOpData.makePublish(
                // aid is null for publish
            stdName,
            localNames,
            desc.isEmpty() ? null : desc,
            types,
            homeMap,
            null, // downloads - not implemented in this UI
            waiters,
            protocols,
            codes,
            services
        );

        if (!ver.isEmpty()) {
            appOpData.setVer(ver);
        }

        Feip feip = Feip.fromName(APP);
        feip.setData(appOpData);
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
                                // Create app object with transaction ID and save to local database
                                App carvedApp = new App();
                                carvedApp.setId(txId);
                                carvedApp.setOwner(liveKeyInfo.getId());
                                carvedApp.setStdName(stdName);
                                if (localNames != null) carvedApp.setLocalNames(localNames);
                                if (!desc.isEmpty()) carvedApp.setDesc(desc);
                                if (types != null) carvedApp.setTypes(types);
                                if (!ver.isEmpty()) carvedApp.setVer(ver);
                                if (homeMap != null) carvedApp.setHome(homeMap);
                                if (waiters != null) carvedApp.setWaiters(waiters);
                                if (protocols != null) carvedApp.setProtocols(protocols);
                                if (codes != null) carvedApp.setCodes(codes);
                                if (services != null) carvedApp.setServices(services);
                                
                                // Set onChain to null (pending confirmation)
                                carvedApp.setOnChain(null);
                                carvedApp.setActive(true);
                                carvedApp.setLastTime(System.currentTimeMillis());

                                // Save to database
                                AppManager appManager = AppManager.getInstance();
                                if (appManager != null) {
                                    appManager.addApp(carvedApp);
                                    appManager.commit();
                                }

                                ToastUtils.makeText(CreateAppActivity.this, 
                                    getString(R.string.app_published_successfully, txId));
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateAppActivity.this, 
                                    getString(R.string.failed_to_publish_app) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateAppActivity.this, 
                                    R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateAppActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(CreateAppActivity.this, signedTxHex);
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

    private java.util.List<String> parseCommaSeparated(String input) {
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

    /**
     * Parse comma-separated key-value pairs (same as home URLs): key:value or key=value.
     */
    private Map<String, String> parseUrlMap(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        String[] parts = input.split(",");
        for (String part : parts) {
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
            case QR_SCAN_STD_NAME:
                stdNameInput.setText(qrContent);
                break;
            case QR_SCAN_LOCAL_NAMES:
                localNamesInput.setText(qrContent);
                break;
            case QR_SCAN_TYPES:
                typesInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_VER:
                verInput.setText(qrContent);
                break;
            case QR_SCAN_URLS:
                urlsInput.setText(qrContent);
                break;
            case QR_SCAN_WAITERS:
                waitersInput.setText(qrContent);
                break;
            case QR_SCAN_PROTOCOLS:
                protocolsInput.setText(qrContent);
                break;
            case QR_SCAN_CODES:
                codesInput.setText(qrContent);
                break;
            case QR_SCAN_SERVICES:
                servicesInput.setText(qrContent);
                break;
        }
    }
}

