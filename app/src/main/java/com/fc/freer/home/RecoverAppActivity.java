package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.APP;

import android.app.Activity;
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
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.AppOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.AppManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.AppCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecoverAppActivity extends BaseCryptoActivity {
    private static final String TAG = "RecoverAppActivity";

    public static final String EXTRA_APP_IDS = "extra_app_ids";

    private AppCardContainer appCardContainer;
    private LinearLayout appListContainer;
    private AppManager appManager;

    private Button recoverButton;
    private ImageButton backButton;
    private ScrollView appScrollView;
    private TextView appStatisticsTextView;

    private List<App> appList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_recover_app;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.recover_apps);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the app IDs from intent
        String[] appIds = getIntent().getStringArrayExtra(EXTRA_APP_IDS);
        if (appIds == null || appIds.length == 0) {
            ToastUtils.makeText(this, getString(R.string.no_apps_provided));
            finish();
            return;
        }

        // Initialize AppManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                appManager = AppManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize AppManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing AppManager with liveFid: " + e.getMessage());
        }

        if (appManager == null) {
            ToastUtils.makeText(this, getString(R.string.app_not_ready_try_later));
            finish();
            return;
        }

        // Load apps by IDs - only stopped (inactive but not closed) apps can be recovered
        for (String appId : appIds) {
            App app = appManager.getAppById(appId);
            if (app != null && !Boolean.TRUE.equals(app.isActive()) && !Boolean.TRUE.equals(app.isClosed())) {
                appList.add(app);
            }
        }

        if (appList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_recoverable_apps_found));
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
        loadAppCardList();
    }

    @Override
    protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        backButton = findViewById(R.id.back_button);
        appScrollView = findViewById(R.id.app_scroll_view);
        appStatisticsTextView = findViewById(R.id.app_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadAppCardList() {
        appListContainer = findViewById(R.id.fragment_container);

        appCardContainer = new AppCardContainer(this, appListContainer, ChooseMode.WITHOUT_CHOOSE);
        appCardContainer.setHideEditButton(true);
        appCardContainer.setShowClearButton(true);
        appCardContainer.setOnClearIconClickListener(app -> {
            appCardContainer.removeAppById(app.getId());
            // Update appList to sync with container
            appList.clear();
            appList.addAll(appCardContainer.getAppList());
            updateUI();
        });

        appCardContainer.setOnAppListChangedListener(updatedAppList -> {
            appList.clear();
            appList.addAll(updatedAppList);
            updateUI();
        });

        Map<String, String> cidMap = appCardContainer.getCidMap(appList, this);
        for (App app : appList) {
            appCardContainer.addAppCard(app, cidMap);
        }

        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasApps = appCardContainer != null && !appCardContainer.getAppList().isEmpty();
        recoverButton.setEnabled(hasApps);
        recoverButton.setAlpha(hasApps ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (appStatisticsTextView == null || appCardContainer == null) {
            return;
        }

        int containerSize = appCardContainer.getAppList().size();
        appStatisticsTextView.setText(getString(R.string.total_count, containerSize));
        appStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        recoverButton.setOnClickListener(v -> {
            hideKeyboard();
            if (appCardContainer == null) return;
            List<App> appsToRecover = new ArrayList<>(appCardContainer.getAppList());
            if (appsToRecover.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_apps_to_recover));
                return;
            }
            performRecoverOperation(appsToRecover);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performRecoverOperation(List<App> appsToRecover) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        // Extract IDs for the recover operation
        List<String> appIds = new ArrayList<>();
        for (App app : appsToRecover) {
            if (app.getId() != null) {
                appIds.add(app.getId());
            }
        }

        if (appIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_app_ids));
            return;
        }

        // Create FEIP for recover operation
        String feipJson = makeRecoverAppFeip(appIds);

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
                                // Update apps in database
                                for (App app : appsToRecover) {
                                    app.setActive(true);
                                    app.setOnChain(null); // Pending confirmation
                                    appManager.updateApp(app);
                                }
                                appManager.commit();

                                ToastUtils.makeText(RecoverAppActivity.this, 
                                    getString(R.string.apps_recovered_successfully, txId));
                                setResult(Activity.RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(RecoverAppActivity.this, 
                                    getString(R.string.failed_to_recover_apps) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(RecoverAppActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(RecoverAppActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(RecoverAppActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeRecoverAppFeip(List<String> appIds) {
        Feip feip = Feip.fromName(APP);
        AppOpData appOpData = AppOpData.makeRecover(appIds);
        feip.setData(appOpData);
        return feip.toJson();
    }
}
