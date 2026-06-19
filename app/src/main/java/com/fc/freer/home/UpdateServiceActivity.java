package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.SERVICE;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.ServiceOpData;
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
import com.fc.freer.manager.ServiceManager;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateServiceActivity extends BaseCryptoActivity {

    public static final String EXTRA_SERVICE_JSON = "extra_service_json";

    private Service currentService;
    private TextView serviceIdValue;
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
    private ImageButton updateButton;
    private ImageButton backButton;

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

    @Override
    protected int getLayoutId() {
        return R.layout.activity_update_service;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_service);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get service data from intent
        String serviceJson = getIntent().getStringExtra(EXTRA_SERVICE_JSON);
        if (serviceJson != null) {
            currentService = JsonUtils.fromJson(serviceJson, Service.class);
        }

        if (currentService == null) {
            ToastUtils.makeText(this, R.string.service_data_not_found);
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
        TextIconsUtils.setupTextIcons(this, R.id.stdNameView, R.id.scanIcon, QR_SCAN_STD_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.localNamesView, R.id.scanIcon, QR_SCAN_LOCAL_NAMES);
        TextIconsUtils.setupTextIcons(this, R.id.typeView, R.id.scanIcon, QR_SCAN_TYPE);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.verView, R.id.scanIcon, QR_SCAN_VER);
        TextIconsUtils.setupTextIcons(this, R.id.componentsView, R.id.scanIcon, QR_SCAN_API_GROUPS);
        TextIconsUtils.setupTextIcons(this, R.id.urlsView, R.id.scanIcon, QR_SCAN_URLS);
        TextIconsUtils.setupTextIcons(this, R.id.waitersView, R.id.scanIcon, QR_SCAN_WAITERS);
        TextIconsUtils.setupTextIcons(this, R.id.protocolsView, R.id.scanIcon, QR_SCAN_PROTOCOLS);
        TextIconsUtils.setupTextIcons(this, R.id.codesView, R.id.scanIcon, QR_SCAN_CODES);
        TextIconsUtils.setupTextIcons(this, R.id.servicesView, R.id.scanIcon, QR_SCAN_SERVICES);
    }

    @Override
    protected void initializeViews() {
        serviceIdValue = findViewById(R.id.serviceIdValue);

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
        stdNameInput.setHint(R.string.standard_name_required);

        localNamesInput = localNamesView.findViewById(R.id.textInput);
        localNamesInput.setHint(R.string.local_names_comma_separated);

        typeInput = typeView.findViewById(R.id.textInput);
        typeInput.setHint(R.string.type);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.description);

        verInput = verView.findViewById(R.id.textInput);
        verInput.setHint(R.string.version);

        componentsInput = componentsView.findViewById(R.id.textInput);
        componentsInput.setHint(R.string.api_groups_comma_separated);

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

        pricePerKBInput = pricePerKBView.findViewById(R.id.textInput);
        pricePerKBInput.setHint(R.string.price_per_kb);

        pricePerKBInInput = pricePerKBInView.findViewById(R.id.textInput);
        pricePerKBInInput.setHint(R.string.price_per_kb_in);

        pricePerKBOutInput = pricePerKBOutView.findViewById(R.id.textInput);
        pricePerKBOutInput.setHint(R.string.price_per_kb_out);

        pricePerDayKBInput = pricePerDayKBView.findViewById(R.id.textInput);
        pricePerDayKBInput.setHint(R.string.price_per_day_kb);

        minPaymentInput = minPaymentView.findViewById(R.id.textInput);
        minPaymentInput.setHint(R.string.min_payment);

        pricePerRequestInput = pricePerRequestView.findViewById(R.id.textInput);
        pricePerRequestInput.setHint(R.string.price_per_request);

        sessionDaysInput = sessionDaysView.findViewById(R.id.textInput);
        sessionDaysInput.setHint(R.string.session_days);

        consumeViaShareInput = consumeViaShareView.findViewById(R.id.textInput);
        consumeViaShareInput.setHint(R.string.consume_via_share);

        orderViaShareInput = orderViaShareView.findViewById(R.id.textInput);
        orderViaShareInput.setHint(R.string.order_via_share);

        currencyInput = currencyView.findViewById(R.id.textInput);
        currencyInput.setHint(R.string.currency);

        maxDataSizeInput = maxDataSizeView.findViewById(R.id.textInput);
        maxDataSizeInput.setHint(R.string.max_data_size);

        dataExpireInput = dataExpireView.findViewById(R.id.textInput);
        dataExpireInput.setHint(R.string.data_expires_in_days);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        updateButton = findViewById(R.id.updateButton);
        backButton = findViewById(R.id.back_button);
    }

    private void populateFields() {
        if (currentService == null) return;

        serviceIdValue.setText(currentService.getId());

        if (currentService.getStdName() != null) {
            stdNameInput.setText(currentService.getStdName());
        }
        if (currentService.getLocalNames() != null && !currentService.getLocalNames().isEmpty()) {
            localNamesInput.setText(formatStringMap(currentService.getLocalNames()));
        }
        if (currentService.fetchServiceType() != null) {
            typeInput.setText(currentService.fetchServiceType().name());
        }
        if (currentService.getDesc() != null) {
            descInput.setText(currentService.getDesc());
        }
        if (currentService.getVer() != null) {
            verInput.setText(currentService.getVer());
        }
        if (currentService.getComponents() != null) {
            componentsInput.setText(String.join(", ", currentService.getComponents()));
        }
        if (currentService.getHome() != null && !currentService.getHome().isEmpty()) {
            List<String> urlStrings = new ArrayList<>();
            for (Map.Entry<String, String> entry : currentService.getHome().entrySet()) {
                if (entry.getValue().isEmpty()) {
                    urlStrings.add(entry.getKey());
                } else {
                    urlStrings.add(entry.getKey() + ":" + entry.getValue());
                }
            }
            urlsInput.setText(String.join(", ", urlStrings));
        }
        if (currentService.getWaiters() != null) {
            waitersInput.setText(String.join(", ", currentService.getWaiters()));
        }
        if (currentService.getProtocols() != null) {
            protocolsInput.setText(String.join(", ", currentService.getProtocols()));
        }
        if (currentService.getCodes() != null) {
            codesInput.setText(String.join(", ", currentService.getCodes()));
        }
        if (currentService.getServices() != null) {
            servicesInput.setText(String.join(", ", currentService.getServices()));
        }
        if (currentService.getPricePerKB() != null) {
            pricePerKBInput.setText(currentService.getPricePerKB());
        }
        if (currentService.getPricePerKBIn() != null) {
            pricePerKBInInput.setText(currentService.getPricePerKBIn());
        }
        if (currentService.getPricePerKBOut() != null) {
            pricePerKBOutInput.setText(currentService.getPricePerKBOut());
        }
        if (currentService.getPricePerKBDay() != null) {
            pricePerDayKBInput.setText(currentService.getPricePerKBDay());
        }
        if (currentService.getMinPayment() != null) {
            minPaymentInput.setText(currentService.getMinPayment());
        }
        if (currentService.getPricePerRequest() != null) {
            pricePerRequestInput.setText(currentService.getPricePerRequest());
        }
        if (currentService.getSessionDays() != null) {
            sessionDaysInput.setText(currentService.getSessionDays());
        }
        if (currentService.getConsumeViaShare() != null) {
            consumeViaShareInput.setText(currentService.getConsumeViaShare());
        }
        if (currentService.getOrderViaShare() != null) {
            orderViaShareInput.setText(currentService.getOrderViaShare());
        }
        if (currentService.getCurrency() != null) {
            currencyInput.setText(currentService.getCurrency());
        }
        if (currentService.getMaxDataSize() != null) {
            maxDataSizeInput.setText(currentService.getMaxDataSize());
        }
        if (currentService.getDataExpiresInDays() != null) {
            dataExpireInput.setText(currentService.getDataExpiresInDays());
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
            saveServiceToDatabase();
        });

        updateButton.setOnClickListener(v -> {
            hideKeyboard();
            updateService();
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
        if (currentService == null) {
            ToastUtils.makeText(this, R.string.service_data_not_found);
            return;
        }

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

        // Verify ownership
        if (!liveKeyInfo.getId().equals(currentService.getOwner())) {
            ToastUtils.makeText(this, R.string.only_owner_can_update);
            return;
        }

        // Create updated service object - keep the original ID
        Service updatedService = new Service();
        updatedService.setId(currentService.getId());
        updatedService.setOwner(currentService.getOwner());
        updatedService.setStdName(stdName);
        if (localNames != null) updatedService.setLocalNames(localNames);
        if (!desc.isEmpty()) updatedService.setDesc(desc);
        if (!type.isEmpty()) updatedService.makeServiceType(Service.ServiceType.valueOf(type));
        if (!ver.isEmpty()) updatedService.setVer(ver);
        if (apiGroups != null) updatedService.setComponents(apiGroups);
        if (urls != null) updatedService.setHome(urls);
        if (waiters != null) updatedService.setWaiters(waiters);
        if (protocols != null) updatedService.setProtocols(protocols);
        if (codes != null) updatedService.setCodes(codes);
        if (services != null) updatedService.setServices(services);
        if (!pricePerKB.isEmpty()) updatedService.setPricePerKB(pricePerKB);
        if (!pricePerKBIn.isEmpty()) updatedService.setPricePerKBIn(pricePerKBIn);
        if (!pricePerKBOut.isEmpty()) updatedService.setPricePerKBOut(pricePerKBOut);
        if (!pricePerDayKB.isEmpty()) updatedService.setPricePerKBDay(pricePerDayKB);
        if (!minPayment.isEmpty()) updatedService.setMinPayment(minPayment);
        if (!pricePerRequest.isEmpty()) updatedService.setPricePerRequest(pricePerRequest);
        if (!sessionDays.isEmpty()) updatedService.setSessionDays(sessionDays);
        if (!consumeViaShare.isEmpty()) updatedService.setConsumeViaShare(consumeViaShare);
        if (!orderViaShare.isEmpty()) updatedService.setOrderViaShare(orderViaShare);
        if (!currency.isEmpty()) updatedService.setCurrency(currency);
        if (!maxDataSize.isEmpty()) updatedService.setMaxDataSize(maxDataSize);
        if (!dataExpire.isEmpty()) updatedService.setDataExpiresInDays(dataExpire);
        
        // Mark as off-chain (false = saved but not carved yet)
        updatedService.setOnChain(false);
        updatedService.setActive(true);
        updatedService.setLastTime(System.currentTimeMillis());

        // Save to database
        ServiceManager serviceManager = ServiceManager.getInstance();
        if (serviceManager != null) {
            serviceManager.updateService(updatedService);
            serviceManager.commit();
            ToastUtils.makeText(this, R.string.service_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.service_not_ready_try_later);
        }
    }

    private void updateService() {
        if (currentService == null) {
            ToastUtils.makeText(this, R.string.service_data_not_found);
            return;
        }

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

        // Verify ownership
        if (!liveKeyInfo.getId().equals(currentService.getOwner())) {
            ToastUtils.makeText(this, R.string.only_owner_can_update);
            return;
        }

        // Determine if this is a new service (publish) or existing service (update)
        boolean isNewService = currentService.getId() != null && currentService.getId().startsWith("local_");
        
        // Create FEIP data - convert Lists to arrays for ServiceOpData methods
        ServiceOpData serviceOpData;
        if (isNewService) {
            serviceOpData = ServiceOpData.makePublish(
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
                null
            );
        } else {
            serviceOpData = ServiceOpData.makeUpdate(
                currentService.getId(),
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
                null
            );
        }
        
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
                                boolean wasNewService = currentService.getId() != null && currentService.getId().startsWith("local_");
                                
                                if (wasNewService) {
                                    ServiceManager serviceManager = ServiceManager.getInstance();
                                    if (serviceManager != null) {
                                        serviceManager.removeService(currentService);
                                        
                                        Service updatedService = new Service();
                                        updatedService.setId(txId);
                                        updatedService.setOwner(currentService.getOwner());
                                        updatedService.setStdName(stdName);
                                        if (localNames != null) updatedService.setLocalNames(localNames);
                                        if (!desc.isEmpty()) updatedService.setDesc(desc);
                                        if (!type.isEmpty()) updatedService.makeServiceType(Service.ServiceType.valueOf(type));
                                        if (!ver.isEmpty()) updatedService.setVer(ver);
                                        if (apiGroups != null) updatedService.setComponents(apiGroups);
                                        if (urls != null) updatedService.setHome(urls);
                                        if (waiters != null) updatedService.setWaiters(waiters);
                                        if (protocols != null) updatedService.setProtocols(protocols);
                                        if (codes != null) updatedService.setCodes(codes);
                                        if (services != null) updatedService.setServices(services);
                                        if (!pricePerKB.isEmpty()) updatedService.setPricePerKB(pricePerKB);
                                        if (!pricePerKBIn.isEmpty()) updatedService.setPricePerKBIn(pricePerKBIn);
                                        if (!pricePerKBOut.isEmpty()) updatedService.setPricePerKBOut(pricePerKBOut);
                                        if (!pricePerDayKB.isEmpty()) updatedService.setPricePerKBDay(pricePerDayKB);
                                        if (!minPayment.isEmpty()) updatedService.setMinPayment(minPayment);
                                        if (!pricePerRequest.isEmpty()) updatedService.setPricePerRequest(pricePerRequest);
                                        if (!sessionDays.isEmpty()) updatedService.setSessionDays(sessionDays);
                                        if (!consumeViaShare.isEmpty()) updatedService.setConsumeViaShare(consumeViaShare);
                                        if (!orderViaShare.isEmpty()) updatedService.setOrderViaShare(orderViaShare);
                                        if (!currency.isEmpty()) updatedService.setCurrency(currency);
                                        if (!maxDataSize.isEmpty()) updatedService.setMaxDataSize(maxDataSize);
                                        if (!dataExpire.isEmpty()) updatedService.setDataExpiresInDays(dataExpire);
                                        
                                        updatedService.setOnChain(null);
                                        updatedService.setActive(true);
                                        updatedService.setLastTime(System.currentTimeMillis());
                                        
                                        serviceManager.addService(updatedService);
                                        serviceManager.commit();
                                    }
                                    
                                    ToastUtils.makeText(UpdateServiceActivity.this,
                                        getString(R.string.service_published_successfully, txId));
                                } else {
                                    ToastUtils.makeText(UpdateServiceActivity.this,
                                        getString(R.string.service_updated_successfully, txId));
                                }
                                
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(UpdateServiceActivity.this,
                                    getString(R.string.failed_to_update_service) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(UpdateServiceActivity.this,
                                    R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(UpdateServiceActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(UpdateServiceActivity.this, signedTxHex);
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
        List<String> urlList = new ArrayList<>();
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
        }
    }
}

