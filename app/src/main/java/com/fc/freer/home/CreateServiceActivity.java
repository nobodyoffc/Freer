package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.SERVICE;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.ServiceOpData;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.manager.ServiceManager;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreateServiceActivity extends BaseCryptoActivity {

    private TextInputEditText stdNameInput;
    private TextInputEditText localNamesInput;
    private TextInputEditText typeInput;
    private TextInputEditText descInput;
    private TextInputEditText verInput;
    private TextInputEditText componentsInput;
    private TextInputEditText urlsInput;
    private TextInputEditText waitersInput;
    private TextInputEditText protocolsInput;
    private TextInputEditText codesInput;
    private TextInputEditText servicesInput;
    private TextInputEditText pricePerKBInput;
    private TextInputEditText pricePerKBInInput;
    private TextInputEditText pricePerKBOutInput;
    private TextInputEditText pricePerDayKBInput;
    private TextInputEditText minPaymentInput;
    private TextInputEditText pricePerRequestInput;
    private TextInputEditText sessionDaysInput;
    private TextInputEditText consumeViaShareInput;
    private TextInputEditText orderViaShareInput;
    private TextInputEditText currencyInput;
    private TextInputEditText maxDataSizeInput;
    private TextInputEditText dataExpireInput;

    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private EntityFieldPickers pickers;

    // QR scan request codes
    private static final int QR_SCAN_STD_NAME = 1001;
    private static final int QR_SCAN_LOCAL_NAMES = 1002;
    private static final int QR_SCAN_TYPE = 1003;
    private static final int QR_SCAN_DESC = 1004;
    private static final int QR_SCAN_VER = 1005;
    private static final int QR_SCAN_API_GROUPS = 1006;
    private static final int QR_SCAN_URLS = 1007;
    private static final int QR_SCAN_WAITERS = 1008;
    private static final int QR_SCAN_PROTOCOLS = 1009;
    private static final int QR_SCAN_CODES = 1010;
    private static final int QR_SCAN_SERVICES = 1011;
    private static final int QR_SCAN_PRICE_PER_KB = 1012;
    private static final int QR_SCAN_PRICE_PER_KB_IN = 1013;
    private static final int QR_SCAN_PRICE_PER_KB_OUT = 1014;
    private static final int QR_SCAN_PRICE_PER_DAY_KB = 1015;
    private static final int QR_SCAN_MIN_PAYMENT = 1016;
    private static final int QR_SCAN_PRICE_PER_REQUEST = 1017;
    private static final int QR_SCAN_SESSION_DAYS = 1018;
    private static final int QR_SCAN_CONSUME_VIA_SHARE = 1019;
    private static final int QR_SCAN_ORDER_VIA_SHARE = 1020;
    private static final int QR_SCAN_CURRENCY = 1021;
    private static final int QR_SCAN_MAX_DATA_SIZE = 1022;
    private static final int QR_SCAN_DATA_EXPIRE = 1023;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_service;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_service);
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
        TextIconsUtils.setupTextIcons(this, R.id.stdNameView, R.id.scanIcon, QR_SCAN_STD_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.localNamesView, R.id.scanIcon, QR_SCAN_LOCAL_NAMES);
        TextIconsUtils.setupTextIcons(this, R.id.typeView, R.id.scanIcon, QR_SCAN_TYPE);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        TextIconsUtils.setupTextIcons(this, R.id.componentsView, R.id.scanIcon, QR_SCAN_API_GROUPS);
        TextIconsUtils.setupTextIcons(this, R.id.urlsView, R.id.scanIcon, QR_SCAN_URLS);
        // Waiters are FIDs: search them on chain (or pick from contacts) via the people icon.
        pickers.bindFidField(R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS, waitersInput, true);
        // Protocols/codes/services are entity ids: pick them from their list via the file icon.
        pickers.bindEntityIdField(R.id.protocolsView, R.id.scanIcon, QR_SCAN_PROTOCOLS, protocolsInput, true, ProtocolActivity.class);
        pickers.bindEntityIdField(R.id.codesView, R.id.scanIcon, QR_SCAN_CODES, codesInput, true, CodeActivity.class);
        pickers.bindEntityIdField(R.id.servicesView, R.id.scanIcon, QR_SCAN_SERVICES, servicesInput, true, ServiceActivity.class);
        // Remaining fields from Price Per KB onward are plain values: keep scan/paste but hide the
        // people and file icons.
        TextIconsUtils.setupTextIcons(this, R.id.pricePerKBView, R.id.scanIcon, QR_SCAN_PRICE_PER_KB);
        TextIconsUtils.setupTextIcons(this, R.id.pricePerKBInView, R.id.scanIcon, QR_SCAN_PRICE_PER_KB_IN);
        TextIconsUtils.setupTextIcons(this, R.id.pricePerKBOutView, R.id.scanIcon, QR_SCAN_PRICE_PER_KB_OUT);
        TextIconsUtils.setupTextIcons(this, R.id.pricePerDayKBView, R.id.scanIcon, QR_SCAN_PRICE_PER_DAY_KB);
        TextIconsUtils.setupTextIcons(this, R.id.minPaymentView, R.id.scanIcon, QR_SCAN_MIN_PAYMENT);
        TextIconsUtils.setupTextIcons(this, R.id.pricePerRequestView, R.id.scanIcon, QR_SCAN_PRICE_PER_REQUEST);
        TextIconsUtils.setupTextIcons(this, R.id.sessionDaysView, R.id.scanIcon, QR_SCAN_SESSION_DAYS);
        TextIconsUtils.setupTextIcons(this, R.id.consumeViaShareView, R.id.scanIcon, QR_SCAN_CONSUME_VIA_SHARE);
        TextIconsUtils.setupTextIcons(this, R.id.orderViaShareView, R.id.scanIcon, QR_SCAN_ORDER_VIA_SHARE);
        TextIconsUtils.setupTextIcons(this, R.id.currencyView, R.id.scanIcon, QR_SCAN_CURRENCY);
        TextIconsUtils.setupTextIcons(this, R.id.maxDataSizeView, R.id.scanIcon, QR_SCAN_MAX_DATA_SIZE);
        TextIconsUtils.setupTextIcons(this, R.id.dataExpireView, R.id.scanIcon, QR_SCAN_DATA_EXPIRE);
    }

    @Override
    protected void initializeViews() {
        View stdNameView = findViewById(R.id.stdNameView);
        View localNamesView = findViewById(R.id.localNamesView);
        View typeView = findViewById(R.id.typeView);
        View descView = findViewById(R.id.descView);
        View verView = findViewById(R.id.verView);
        View componentsView = findViewById(R.id.componentsView);
        View urlsView = findViewById(R.id.urlsView);
        View waitersView = findViewById(R.id.waitersView);
        View protocolsView = findViewById(R.id.protocolsView);
        View codesView = findViewById(R.id.codesView);
        View servicesView = findViewById(R.id.servicesView);
        View pricePerKBView = findViewById(R.id.pricePerKBView);
        View pricePerKBInView = findViewById(R.id.pricePerKBInView);
        View pricePerKBOutView = findViewById(R.id.pricePerKBOutView);
        View pricePerDayKBView = findViewById(R.id.pricePerDayKBView);
        View minPaymentView = findViewById(R.id.minPaymentView);
        View pricePerRequestView = findViewById(R.id.pricePerRequestView);
        View sessionDaysView = findViewById(R.id.sessionDaysView);
        View consumeViaShareView = findViewById(R.id.consumeViaShareView);
        View orderViaShareView = findViewById(R.id.orderViaShareView);
        View currencyView = findViewById(R.id.currencyView);
        View maxDataSizeView = findViewById(R.id.maxDataSizeView);
        View dataExpireView = findViewById(R.id.dataExpireView);

        stdNameInput = stdNameView.findViewById(R.id.textInput);
        setLabel(stdNameView, R.string.standard_name_required);

        localNamesInput = localNamesView.findViewById(R.id.textInput);
        setLabel(localNamesView, R.string.local_names_comma_separated);

        typeInput = typeView.findViewById(R.id.textInput);
        setLabel(typeView, R.string.type);

        descInput = descView.findViewById(R.id.textInput);
        setLabel(descView, R.string.description);

        verInput = verView.findViewById(R.id.textInput);
        setLabel(verView, R.string.version);

        componentsInput = componentsView.findViewById(R.id.textInput);
        setLabel(componentsView, R.string.api_groups_comma_separated);

        urlsInput = urlsView.findViewById(R.id.textInput);
        setLabel(urlsView, R.string.urls_comma_separated);

        waitersInput = waitersView.findViewById(R.id.textInput);
        setLabel(waitersView, R.string.waiters_comma_separated);

        protocolsInput = protocolsView.findViewById(R.id.textInput);
        setLabel(protocolsView, R.string.protocols_comma_separated);

        codesInput = codesView.findViewById(R.id.textInput);
        setLabel(codesView, R.string.codes_comma_separated);

        servicesInput = servicesView.findViewById(R.id.textInput);
        setLabel(servicesView, R.string.services_comma_separated);

        pricePerKBInput = pricePerKBView.findViewById(R.id.textInput);
        setLabel(pricePerKBView, R.string.price_per_kb);

        pricePerKBInInput = pricePerKBInView.findViewById(R.id.textInput);
        setLabel(pricePerKBInView, R.string.price_per_kb_in);

        pricePerKBOutInput = pricePerKBOutView.findViewById(R.id.textInput);
        setLabel(pricePerKBOutView, R.string.price_per_kb_out);

        pricePerDayKBInput = pricePerDayKBView.findViewById(R.id.textInput);
        setLabel(pricePerDayKBView, R.string.price_per_day_kb);

        minPaymentInput = minPaymentView.findViewById(R.id.textInput);
        setLabel(minPaymentView, R.string.min_payment);

        pricePerRequestInput = pricePerRequestView.findViewById(R.id.textInput);
        setLabel(pricePerRequestView, R.string.price_per_request);

        sessionDaysInput = sessionDaysView.findViewById(R.id.textInput);
        setLabel(sessionDaysView, R.string.session_days);

        consumeViaShareInput = consumeViaShareView.findViewById(R.id.textInput);
        setLabel(consumeViaShareView, R.string.consume_via_share);

        orderViaShareInput = orderViaShareView.findViewById(R.id.textInput);
        setLabel(orderViaShareView, R.string.order_via_share);

        currencyInput = currencyView.findViewById(R.id.textInput);
        setLabel(currencyView, R.string.currency);

        maxDataSizeInput = maxDataSizeView.findViewById(R.id.textInput);
        setLabel(maxDataSizeView, R.string.max_data_size);

        dataExpireInput = dataExpireView.findViewById(R.id.textInput);
        setLabel(dataExpireView, R.string.data_expires_in_days);

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
            saveServiceToDatabase();
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishService();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        stdNameInput.setText("");
        localNamesInput.setText("");
        typeInput.setText("");
        descInput.setText("");
        verInput.setText("");
        componentsInput.setText("");
        urlsInput.setText("");
        waitersInput.setText("");
        protocolsInput.setText("");
        codesInput.setText("");
        servicesInput.setText("");
        pricePerKBInput.setText("");
        pricePerKBInInput.setText("");
        pricePerKBOutInput.setText("");
        pricePerDayKBInput.setText("");
        minPaymentInput.setText("");
        pricePerRequestInput.setText("");
        sessionDaysInput.setText("");
        consumeViaShareInput.setText("");
        orderViaShareInput.setText("");
        currencyInput.setText("");
        maxDataSizeInput.setText("");
        dataExpireInput.setText("");
    }

    private void saveServiceToDatabase() {
        String stdName = getText(stdNameInput);
        String localNamesStr = getText(localNamesInput);
        String type = getText(typeInput);
        String desc = getText(descInput);
        String ver = getText(verInput);
        String componentsStr = getText(componentsInput);
        String urlsStr = getText(urlsInput);
        String waitersStr = getText(waitersInput);
        String protocolsStr = getText(protocolsInput);
        String codesStr = getText(codesInput);
        String servicesStr = getText(servicesInput);
        String pricePerKB = getText(pricePerKBInput);
        String pricePerKBIn = getText(pricePerKBInInput);
        String pricePerKBOut = getText(pricePerKBOutInput);
        String pricePerDayKB = getText(pricePerDayKBInput);
        String minPayment = getText(minPaymentInput);
        String pricePerRequest = getText(pricePerRequestInput);
        String sessionDays = getText(sessionDaysInput);
        String consumeViaShare = getText(consumeViaShareInput);
        String orderViaShare = getText(orderViaShareInput);
        String currency = getText(currencyInput);
        String maxDataSize = getText(maxDataSizeInput);
        String dataExpire = getText(dataExpireInput);

        // Validate required field
        if (stdName.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_standard_name);
            return;
        }

        // Parse comma-separated strings to lists
        Map<String, String> localNames = parseUrlMap(localNamesStr);
        List<String> components = parseCommaSeparated(componentsStr);
        Map<String, String> urls = parseUrlMap(urlsStr);
        List<String> waiters = parseCommaSeparated(waitersStr);
        List<String> protocols = parseCommaSeparated(protocolsStr);
        List<String> codes = parseCommaSeparated(codesStr);
        List<String> services = parseCommaSeparated(servicesStr);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create service object with a unique local ID
        Service newService = new Service();
        newService.setId("local_" + System.currentTimeMillis()); // Generate local ID
        newService.setOwner(liveKeyInfo.getId());
        newService.setStdName(stdName);
        if (localNames != null) newService.setLocalNames(localNames);
        if (!desc.isEmpty()) newService.setDesc(desc);
        if (!type.isEmpty()) newService.setType(type);
        if (!ver.isEmpty()) newService.setVer(ver);
        if (components != null) newService.setComponents(components);
        if (urls != null) newService.setHome(urls);
        if (waiters != null) newService.setWaiters(waiters);
        if (protocols != null) newService.setProtocols(protocols);
        if (codes != null) newService.setCodes(codes);
        if (services != null) newService.setServices(services);
        if (!pricePerKB.isEmpty()) newService.setPricePerKB(pricePerKB);
        if (!pricePerKBIn.isEmpty()) newService.setPricePerKBIn(pricePerKBIn);
        if (!pricePerKBOut.isEmpty()) newService.setPricePerKBOut(pricePerKBOut);
        if (!pricePerDayKB.isEmpty()) newService.setPricePerKBDay(pricePerDayKB);
        if (!minPayment.isEmpty()) newService.setMinPayment(minPayment);
        if (!pricePerRequest.isEmpty()) newService.setPricePerRequest(pricePerRequest);
        if (!sessionDays.isEmpty()) newService.setSessionDays(sessionDays);
        if (!consumeViaShare.isEmpty()) newService.setConsumeViaShare(consumeViaShare);
        if (!orderViaShare.isEmpty()) newService.setOrderViaShare(orderViaShare);
        if (!currency.isEmpty()) newService.setCurrency(currency);
        if (!maxDataSize.isEmpty()) newService.setMaxDataSize(maxDataSize);
        if (!dataExpire.isEmpty()) newService.setDataExpiresInDays(dataExpire);
        
        // Mark as off-chain (false = not carved yet, can be carved)
        newService.setOnChain(false);
        newService.setActive(true);
        newService.setLastTime(System.currentTimeMillis());

        // Save to database
        ServiceManager serviceManager = ServiceManager.getInstance();
        if (serviceManager != null) {
            serviceManager.addService(newService);
            serviceManager.commit();
            ToastUtils.makeText(this, R.string.service_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.service_not_ready_try_later);
        }
    }

    private void publishService() {
        String stdName = getText(stdNameInput);
        String localNamesStr = getText(localNamesInput);
        String type = getText(typeInput);
        String desc = getText(descInput);
        String ver = getText(verInput);
        String apiGroupsStr = getText(componentsInput);
        String urlsStr = getText(urlsInput);
        String waitersStr = getText(waitersInput);
        String protocolsStr = getText(protocolsInput);
        String codesStr = getText(codesInput);
        String servicesStr = getText(servicesInput);
        String pricePerKB = getText(pricePerKBInput);
        String pricePerKBIn = getText(pricePerKBInInput);
        String pricePerKBOut = getText(pricePerKBOutInput);
        String pricePerDayKB = getText(pricePerDayKBInput);
        String minPayment = getText(minPaymentInput);
        String pricePerRequest = getText(pricePerRequestInput);
        String sessionDays = getText(sessionDaysInput);
        String consumeViaShare = getText(consumeViaShareInput);
        String orderViaShare = getText(orderViaShareInput);
        String currency = getText(currencyInput);
        String maxDataSize = getText(maxDataSizeInput);
        String dataExpire = getText(dataExpireInput);

        // Validate required field
        if (stdName.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_enter_standard_name);
            return;
        }

        // Parse comma-separated strings to lists
        Map<String, String> localNames = parseUrlMap(localNamesStr);
        List<String> apiGroups = parseCommaSeparated(apiGroupsStr);
        Map<String, String> urls = parseUrlMap(urlsStr);
        List<String> waiters = parseCommaSeparated(waitersStr);
        List<String> protocols = parseCommaSeparated(protocolsStr);
        List<String> codes = parseCommaSeparated(codesStr);
        List<String> services = parseCommaSeparated(servicesStr);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Create FEIP data - convert Lists to arrays for ServiceOpData.makePublish
        ServiceOpData serviceOpData = ServiceOpData.makePublish(
            stdName,
            localNames,
            desc.isEmpty() ? null : desc,
            type.isEmpty() ? null : type,
            apiGroups,
            ver.isEmpty() ? null : ver,
            urls,
            waiters,
            protocols,
            codes,
            services,
            null // params - not implemented in this UI
        );
        
        // Set pricing and service configuration fields
        if (!pricePerKB.isEmpty()) serviceOpData.setPricePerKB(pricePerKB);
        if (!pricePerKBIn.isEmpty()) serviceOpData.setPricePerKBIn(pricePerKBIn);
        if (!pricePerKBOut.isEmpty()) serviceOpData.setPricePerKBOut(pricePerKBOut);
        if (!pricePerDayKB.isEmpty()) serviceOpData.setPricePerKBDay(pricePerDayKB);
        if (!minPayment.isEmpty()) serviceOpData.setMinPayment(minPayment);
        if (!pricePerRequest.isEmpty()) serviceOpData.setPricePerRequest(pricePerRequest);
        if (!sessionDays.isEmpty()) serviceOpData.setSessionDays(sessionDays);
        if (!consumeViaShare.isEmpty()) serviceOpData.setConsumeViaShare(consumeViaShare);
        if (!orderViaShare.isEmpty()) serviceOpData.setOrderViaShare(orderViaShare);
        if (!currency.isEmpty()) serviceOpData.setCurrency(currency);
        if (!maxDataSize.isEmpty()) serviceOpData.setMaxDataSize(maxDataSize);
        if (!dataExpire.isEmpty()) serviceOpData.setDataExpiresInDays(dataExpire);

        Feip feip = Feip.fromName(SERVICE);
        feip.setData(serviceOpData);
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
                                // Create service object with transaction ID and save to local database
                                Service carvedService = new Service();
                                carvedService.setId(txId);
                                carvedService.setOwner(liveKeyInfo.getId());
                                carvedService.setStdName(stdName);
                                if (localNames != null) carvedService.setLocalNames(localNames);
                                if (!desc.isEmpty()) carvedService.setDesc(desc);
                                if (!type.isEmpty()) carvedService.setType(type);
                                if (!ver.isEmpty()) carvedService.setVer(ver);
                                if (apiGroups != null) carvedService.setComponents(apiGroups);
                                if (urls != null) carvedService.setHome(urls);
                                if (waiters != null) carvedService.setWaiters(waiters);
                                if (protocols != null) carvedService.setProtocols(protocols);
                                if (codes != null) carvedService.setCodes(codes);
                                if (services != null) carvedService.setServices(services);
                                if (!pricePerKB.isEmpty()) carvedService.setPricePerKB(pricePerKB);
                                if (!pricePerKBIn.isEmpty()) carvedService.setPricePerKBIn(pricePerKBIn);
                                if (!pricePerKBOut.isEmpty()) carvedService.setPricePerKBOut(pricePerKBOut);
                                if (!pricePerDayKB.isEmpty()) carvedService.setPricePerKBDay(pricePerDayKB);
                                if (!minPayment.isEmpty()) carvedService.setMinPayment(minPayment);
                                if (!pricePerRequest.isEmpty()) carvedService.setPricePerRequest(pricePerRequest);
                                if (!sessionDays.isEmpty()) carvedService.setSessionDays(sessionDays);
                                if (!consumeViaShare.isEmpty()) carvedService.setConsumeViaShare(consumeViaShare);
                                if (!orderViaShare.isEmpty()) carvedService.setOrderViaShare(orderViaShare);
                                if (!currency.isEmpty()) carvedService.setCurrency(currency);
                                if (!maxDataSize.isEmpty()) carvedService.setMaxDataSize(maxDataSize);
                                if (!dataExpire.isEmpty()) carvedService.setDataExpiresInDays(dataExpire);
                                
                                // Set onChain to null (pending confirmation)
                                carvedService.setOnChain(null);
                                carvedService.setActive(true);
                                carvedService.setLastTime(System.currentTimeMillis());

                                // Save to database
                                ServiceManager serviceManager = ServiceManager.getInstance();
                                if (serviceManager != null) {
                                    serviceManager.addService(carvedService);
                                    serviceManager.commit();
                                }

                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateServiceActivity.this, 
                                    getString(R.string.failed_to_publish_service) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateServiceActivity.this, 
                                    R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateServiceActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(CreateServiceActivity.this, signedTxHex);
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
        // Filter out empty strings and trim
        List<String> nonEmpty = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }
        return nonEmpty.isEmpty() ? null : nonEmpty;
    }

    /**
     * Parse comma-separated key-value pairs into a Map.
     * Expected format: "key1:value1,key2:value2" or "key1=value1,key2=value2"
     * If a pair doesn't contain ':' or '=', the entire value is used as the key with an empty string value.
     */
    private Map<String, String> parseUrlMap(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Map<String, String> urlMap = new HashMap<>();
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
                // Use colon separator
                key = part.substring(0, colonIndex).trim();
                value = part.substring(colonIndex + 1).trim();
            } else if (equalsIndex > 0) {
                // Use equals separator
                key = part.substring(0, equalsIndex).trim();
                value = part.substring(equalsIndex + 1).trim();
            } else {
                // No separator found, use the whole string as key with empty value
                key = part;
                value = "";
            }
            
            if (!key.isEmpty()) {
                urlMap.put(key, value);
            }
        }
        return urlMap.isEmpty() ? null : urlMap;
    }

    /**
     * Convert a Map to String array in "key:value" format for ServiceOpData.
     */
    private String[] urlMapToArray(Map<String, String> urlMap) {
        if (urlMap == null || urlMap.isEmpty()) {
            return null;
        }
        java.util.List<String> urlList = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : urlMap.entrySet()) {
            urlList.add(entry.getKey() + ":" + entry.getValue());
        }
        return urlList.toArray(new String[0]);
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
            case QR_SCAN_TYPE:
                typeInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_VER:
                verInput.setText(qrContent);
                break;
            case QR_SCAN_API_GROUPS:
                componentsInput.setText(qrContent);
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
            case QR_SCAN_PRICE_PER_KB:
                pricePerKBInput.setText(qrContent);
                break;
            case QR_SCAN_PRICE_PER_KB_IN:
                pricePerKBInInput.setText(qrContent);
                break;
            case QR_SCAN_PRICE_PER_KB_OUT:
                pricePerKBOutInput.setText(qrContent);
                break;
            case QR_SCAN_PRICE_PER_DAY_KB:
                pricePerDayKBInput.setText(qrContent);
                break;
            case QR_SCAN_MIN_PAYMENT:
                minPaymentInput.setText(qrContent);
                break;
            case QR_SCAN_PRICE_PER_REQUEST:
                pricePerRequestInput.setText(qrContent);
                break;
            case QR_SCAN_SESSION_DAYS:
                sessionDaysInput.setText(qrContent);
                break;
            case QR_SCAN_CONSUME_VIA_SHARE:
                consumeViaShareInput.setText(qrContent);
                break;
            case QR_SCAN_ORDER_VIA_SHARE:
                orderViaShareInput.setText(qrContent);
                break;
            case QR_SCAN_CURRENCY:
                currencyInput.setText(qrContent);
                break;
            case QR_SCAN_MAX_DATA_SIZE:
                maxDataSizeInput.setText(qrContent);
                break;
            case QR_SCAN_DATA_EXPIRE:
                dataExpireInput.setText(qrContent);
                break;
        }
    }
}

