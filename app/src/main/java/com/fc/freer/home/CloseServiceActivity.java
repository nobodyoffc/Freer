package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.SERVICE;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.ServiceOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ServiceManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ServiceCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CloseServiceActivity extends BaseCryptoActivity {
    private static final String TAG = "CloseServiceActivity";

    public static final String EXTRA_SERVICE_IDS = "extra_service_ids";

    private ServiceCardContainer serviceCardContainer;
    private LinearLayout serviceListContainer;
    private ServiceManager serviceManager;

    private Button closeButton;
    private ImageButton backButton;
    private ScrollView serviceScrollView;
    private TextView serviceStatisticsTextView;
    private TextInputEditText closeStatementInput;

    private List<Service> serviceList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_close_service;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.close_services);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the service IDs from intent
        String[] serviceIds = getIntent().getStringArrayExtra(EXTRA_SERVICE_IDS);
        if (serviceIds == null || serviceIds.length == 0) {
            ToastUtils.makeText(this, getString(R.string.no_services_provided));
            finish();
            return;
        }

        // Initialize ServiceManager
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        
        try {
            if (fidManager != null && liveFid != null) {
                serviceManager = ServiceManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ServiceManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ServiceManager with liveFid: " + e.getMessage());
        }

        if (serviceManager == null) {
            ToastUtils.makeText(this, getString(R.string.service_not_ready_try_later));
            finish();
            return;
        }

        // Load services by IDs - only include services owned by liveFid
        for (String serviceId : serviceIds) {
            Service service = serviceManager.getServiceById(serviceId);
            if (service != null && !Boolean.TRUE.equals(service.isClosed())) {
                if (liveFid != null && liveFid.equals(service.getOwner())) {
                    serviceList.add(service);
                }
            }
        }

        if (serviceList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_closable_services_found));
            finish();
            return;
        }

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
        loadServiceCardList();
    }

    @Override
    protected void initializeViews() {
        closeButton = findViewById(R.id.close_button);
        backButton = findViewById(R.id.back_button);
        serviceScrollView = findViewById(R.id.service_scroll_view);
        serviceStatisticsTextView = findViewById(R.id.service_statistics);
        closeStatementInput = findViewById(R.id.close_statement_input);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadServiceCardList() {
        serviceListContainer = findViewById(R.id.fragment_container);

        serviceCardContainer = new ServiceCardContainer(this, serviceListContainer, ChooseMode.WITHOUT_CHOOSE);
        serviceCardContainer.setHideEditButton(true);
        serviceCardContainer.setShowClearButton(true);
        serviceCardContainer.setOnClearIconClickListener(service -> {
            serviceCardContainer.removeServiceById(service.getId());
            serviceList.clear();
            serviceList.addAll(serviceCardContainer.getServiceList());
            updateUI();
        });

        serviceCardContainer.setOnServiceListChangedListener(updatedServiceList -> {
            serviceList.clear();
            serviceList.addAll(updatedServiceList);
            updateUI();
        });

        Map<String, String> cidMap = serviceCardContainer.getCidMap(serviceList, this);
        for (Service service : serviceList) {
            serviceCardContainer.addServiceCard(service, cidMap);
        }

        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasServices = serviceCardContainer != null && !serviceCardContainer.getServiceList().isEmpty();
        closeButton.setEnabled(hasServices);
        closeButton.setAlpha(hasServices ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (serviceStatisticsTextView == null || serviceCardContainer == null) {
            return;
        }

        int containerSize = serviceCardContainer.getServiceList().size();
        serviceStatisticsTextView.setText(getString(R.string.total_count, containerSize));
        serviceStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        closeButton.setOnClickListener(v -> {
            hideKeyboard();
            if (serviceCardContainer == null) return;
            List<Service> servicesToClose = new ArrayList<>(serviceCardContainer.getServiceList());
            if (servicesToClose.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_services_to_close));
                return;
            }
            performCloseOperation(servicesToClose);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performCloseOperation(List<Service> servicesToClose) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        List<String> serviceIds = new ArrayList<>();
        for (Service service : servicesToClose) {
            if (service.getId() != null) {
                serviceIds.add(service.getId());
            }
        }

        if (serviceIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_service_ids));
            return;
        }

        String closeStatement = null;
        if (closeStatementInput != null && closeStatementInput.getText() != null) {
            closeStatement = closeStatementInput.getText().toString().trim();
            if (closeStatement.isEmpty()) {
                closeStatement = null;
            }
        }

        String feipJson = makeCloseServiceFeip(serviceIds, closeStatement);

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
                                for (Service service : servicesToClose) {
                                    service.setClosed(true);
                                    service.setActive(false);
                                    service.setOnChain(null);
                                    serviceManager.updateService(service);
                                }
                                serviceManager.commit();

                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("closed_service_ids", serviceIds.toArray(new String[0]));
                                setResult(Activity.RESULT_OK, resultIntent);

                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CloseServiceActivity.this, 
                                    getString(R.string.failed_to_close_services) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CloseServiceActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CloseServiceActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(CloseServiceActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeCloseServiceFeip(List<String> serviceIds, String closeStatement) {
        Feip feip = Feip.fromName(SERVICE);
        ServiceOpData serviceOpData = ServiceOpData.makeClose(serviceIds, closeStatement);
        feip.setData(serviceOpData);
        return feip.toJson();
    }
}

