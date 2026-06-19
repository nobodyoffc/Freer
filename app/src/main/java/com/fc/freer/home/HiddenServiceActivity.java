package com.fc.freer.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ServiceManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.ServiceCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HiddenServiceActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenServiceActivity";

    private ServiceCardContainer serviceCardContainer;
    private LinearLayout serviceListContainer;
    private ServiceManager serviceManager;

    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private ScrollView serviceScrollView;
    private TextView serviceStatisticsTextView;

    private List<Service> serviceList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_hidden_service;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.hidden_services);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ServiceManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

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

        // Load hidden services from local deleted list
        serviceList = serviceManager.getLocalHiddenServices();

        if (serviceList == null || serviceList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_hidden_services_found));
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
        revealButton = findViewById(R.id.reveal_button);
        backButton = findViewById(R.id.back_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        serviceScrollView = findViewById(R.id.service_scroll_view);
        serviceStatisticsTextView = findViewById(R.id.service_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadServiceCardList() {
        serviceListContainer = findViewById(R.id.fragment_container);

        serviceCardContainer = new ServiceCardContainer(this, serviceListContainer, ChooseMode.CHOOSE_MULTI);
        serviceCardContainer.setHideEditButton(true);

        serviceCardContainer.setOnServiceListChangedListener(updatedServiceList -> {
            updateUI();
        });

        Map<String, String> cidMap = serviceCardContainer.getCidMap(serviceList, this);
        for (Service service : serviceList) {
            serviceCardContainer.addServiceCard(service, cidMap);
        }

        setupSelectAllCheckBox();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && serviceCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    serviceCardContainer.selectAll(isChecked);
                }
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && serviceCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (serviceCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (serviceCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasSelected = serviceCardContainer != null && !serviceCardContainer.getSelectedServices().isEmpty();
        revealButton.setEnabled(hasSelected);
        revealButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (serviceStatisticsTextView == null) {
            return;
        }

        int containerSize = serviceCardContainer != null ? serviceCardContainer.getServiceList().size() : 0;
        serviceStatisticsTextView.setText(getString(R.string.total_count, containerSize));
        serviceStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        revealButton.setOnClickListener(v -> {
            hideKeyboard();
            if (serviceCardContainer == null) return;
            List<Service> selectedServices = serviceCardContainer.getSelectedServices();
            if (selectedServices.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_services_selected));
                return;
            }
            performRevealOperation(selectedServices);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performRevealOperation(List<Service> servicesToReveal) {
        // Remove from local deleted list and add back to main database
        for (Service service : servicesToReveal) {
            serviceManager.removeFromLocalDeletedList(service);
            serviceManager.addService(service);
        }
        serviceManager.commit();

        // Remove revealed services from the card container
        for (Service service : servicesToReveal) {
            serviceCardContainer.removeServiceById(service.getId());
        }

        // Update the main list
        serviceList.removeAll(servicesToReveal);

        ToastUtils.makeText(this, getString(R.string.services_revealed_successfully, servicesToReveal.size()));

        if (serviceList.isEmpty()) {
            setResult(Activity.RESULT_OK);
            finish();
        } else {
            updateUI();
            setResult(Activity.RESULT_OK);
        }
    }
}

