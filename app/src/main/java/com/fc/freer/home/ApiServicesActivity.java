package com.fc.freer.home;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.ClientGroup;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCardContainer;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;

import android.content.Intent;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiServicesActivity extends BaseCryptoActivity {
    private static final String TAG = "ApiServicesActivity";

    private LinearLayout apiCardsContainer;
    private ApiCardContainer apiCardContainer;
    private ExecutorService executorService;
    private boolean isFromSettings = false;
    private ActivityResultLauncher<Intent> changeApiServiceLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_api_services;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.api_services);
    }

    @Override
    protected void initializeViews() {
        apiCardsContainer = findViewById(R.id.api_cards_container);
        apiCardContainer = new ApiCardContainer(this, apiCardsContainer, false);
        executorService = Executors.newSingleThreadExecutor();

        // Setup converter click listener
        apiCardContainer.setOnConverterClickListener((apiProvider, serviceType, index) -> {
            Intent intent = new Intent(this, ChangeApiServiceActivity.class);
            intent.putExtra(ChangeApiServiceActivity.EXTRA_SERVICE_TYPE, serviceType.name());
            // Only pass current provider if it's not null (null means adding new)
            if (apiProvider != null) {
                intent.putExtra(ChangeApiServiceActivity.EXTRA_OLD_PROVIDER_ID, apiProvider.getId());
                TimberLogger.d(TAG, "Change API clicked for: " + apiProvider.getId());
            } else {
                TimberLogger.d(TAG, "Add new API for service type: " + serviceType.name());
            }
            changeApiServiceLauncher.launch(intent);
        });
    }

    @Override
    protected void setupButtons() {
        ImageButton backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> finish());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launcher
        changeApiServiceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        String selectedServiceJson = data.getStringExtra(ChangeApiServiceActivity.EXTRA_SELECTED_SERVICE);
                        String serviceTypeName = data.getStringExtra(ChangeApiServiceActivity.EXTRA_SERVICE_TYPE);
                        String oldProviderId = data.getStringExtra(ChangeApiServiceActivity.EXTRA_OLD_PROVIDER_ID);

                        if (selectedServiceJson != null && serviceTypeName != null) {
                            try {
                                ApiProvider selectedProvider = com.fc.fc_ajdk.utils.JsonUtils.fromJson(selectedServiceJson, ApiProvider.class);
                                if (selectedProvider != null) {
                                    // Handle the selected API service
                                    handleSelectedApiService(selectedProvider.fetchServiceType(), oldProviderId, selectedProvider);
                                }
                            } catch (Exception e) {
                                TimberLogger.e(TAG, "Error handling selected service: " + e.getMessage(), e);
                            }
                        }
                    }
                }
        );

        // Check if launched from settings menu
        isFromSettings = getIntent().getBooleanExtra("FROM_SETTINGS", false);

        // Load and display API services
        loadApiServices();
    }

    /**
     * Load API services from ApiCenter and display them
     */
    private void loadApiServices() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            Map<Service.ServiceType, Integer> serviceNumberMap = FreerApplication.getServiceNumberMap();

            Map<Service.ServiceType, ClientGroup> clientGroupMap = apiCenter.getClientGroupMap();

            // Clear existing cards
            apiCardContainer.clearAll();

            // Collect all provider IDs for batch update
            java.util.ArrayList<String> providerIds = new java.util.ArrayList<>();

            // Iterate through all service types defined in serviceNumberMap
            for (Map.Entry<Service.ServiceType, Integer> entry : serviceNumberMap.entrySet()) {
                Service.ServiceType serviceType = entry.getKey();
                Integer requiredCount = entry.getValue();

                // Get the client group for this service type (may be null)
                ClientGroup clientGroup = (clientGroupMap != null) ? clientGroupMap.get(serviceType) : null;

                int existingCount = 0;

                // If client group exists, add cards for existing API accounts
                if (clientGroup != null) {
                    // Get the list of account IDs to determine index
                    List<String> accountIds = clientGroup.getAccountIds();
                    Map<String, ApiAccount> apiAccountMap = clientGroup.getApiAccountMap();

                    if (accountIds != null && apiAccountMap != null) {
                        // Create a card for each API account
                        for (int index = 0; index < accountIds.size(); index++) {
                            String accountId = accountIds.get(index);
                            ApiAccount apiAccount = apiAccountMap.get(accountId);

                            if (apiAccount == null) {
                                continue;
                            }

                            Configure configure = ConfigureManager.getInstance().getConfigure();
                            ApiProvider apiProvider = configure.getApiProviderMap().get(apiAccount.getProviderId());
                            if (apiProvider == null) {
                                continue;
                            }

                            // Add API card using ApiCardContainer (show converter icon)
                            apiCardContainer.addApiCard(serviceType, index, apiProvider, true);

                            // Collect provider ID for batch update
                            if (apiProvider.fetchServiceType() != null && apiProvider.getId() != null) {
                                providerIds.add(apiProvider.getId());
                            }

                            existingCount++;
                        }
                    }
                }

                // Add empty cards if the number of providers is less than required
                if (requiredCount != null && existingCount < requiredCount) {
                    for (int index = existingCount; index < requiredCount; index++) {
                        apiCardContainer.addEmptyApiCard(serviceType, index);
                    }
                }
            }

            // Fetch all service info in a single batch request
            if (!providerIds.isEmpty()) {

                executorService.execute(() -> {
                    try {
                        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                        if (fapiClient != null) {
                            Map<String, Service> serviceMap = fapiClient.serviceByIds(providerIds);

                            if (serviceMap != null) {
                                // Update UI on main thread
                                runOnUiThread(() -> {
                                    List<ApiProvider> apiProviders = apiCardContainer.getApiProviderList();
                                    for (int i = 0; i < apiProviders.size(); i++) {
                                        ApiProvider provider = apiProviders.get(i);
                                        if (provider!=null && provider.getId() != null) {
                                            String providerId = provider.getId();
                                            Service service = serviceMap.get(providerId);

                                            if (service != null) {
                                                provider.updateWithService(service);
                                                apiCardContainer.updateCardStateAtPosition(i,provider);
                                            }
                                        }
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error fetching service info batch: " + e.getMessage(), e);
                    }
                });
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading API services: " + e.getMessage(), e);
        }
    }



    /**
     * Handle the selected API service by creating an ApiAccount, connecting it, and adding to ApiCenter
     */
    private void handleSelectedApiService(Service.ServiceType serviceType, String oldProviderId, ApiProvider selectedProvider) {
        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();

                boolean success = apiCenter.replaceClient(this,serviceType,selectedProvider,oldProviderId);

                if (success && isFromSettings) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(this, getString(R.string.api_service_changed_restarting));

                        // Restart the app properly via MainActivity with clear task flags
                        // This ensures a full cold-start initialization with the new API settings
                        new android.os.Handler().postDelayed(() -> {
                            Intent restartIntent = new Intent(this, com.fc.freer.MainActivity.class);
                            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(restartIntent);
                            finish();
                        }, 500);
                    });
                } else if (success) {
                    // Reload API services to reflect changes
                    runOnUiThread(this::loadApiServices);
                }

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error handling selected API service: " + e.getMessage(), e);
                runOnUiThread(() -> android.widget.Toast.makeText(this, "Error: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * Create client for the given service type
     */
    private Object createClientForServiceType(android.content.Context context, Service.ServiceType serviceType,
                                             ApiAccount apiAccount, ApiProvider apiProvider) {
        switch (serviceType) {
            case FAPI_No1_NrC7:
                // APIP 和 FAPI 统一使用 FapiClient (通过 ApiCenter 创建)
                return com.fc.freer.utils.ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

            default:
                TimberLogger.w(TAG, "Unsupported service type: %s", serviceType);
                return null;
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
