package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.PROTOCOL;

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
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.ProtocolOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ProtocolManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ProtocolCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecoverProtocolActivity extends BaseCryptoActivity {
    private static final String TAG = "RecoverProtocolActivity";

    public static final String EXTRA_PROTOCOL_IDS = "extra_protocol_ids";

    private ProtocolCardContainer protocolCardContainer;
    private LinearLayout protocolListContainer;
    private ProtocolManager protocolManager;

    private Button recoverButton;
    private ImageButton backButton;
    private ScrollView protocolScrollView;
    private TextView protocolStatisticsTextView;

    private List<Protocol> protocolList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_recover_protocol;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.recover_protocols);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the protocol IDs from intent
        String[] protocolIds = getIntent().getStringArrayExtra(EXTRA_PROTOCOL_IDS);
        if (protocolIds == null || protocolIds.length == 0) {
            ToastUtils.makeText(this, getString(R.string.no_protocols_provided));
            finish();
            return;
        }

        // Initialize ProtocolManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                protocolManager = ProtocolManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ProtocolManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ProtocolManager with liveFid: " + e.getMessage());
        }

        if (protocolManager == null) {
            ToastUtils.makeText(this, getString(R.string.protocol_not_ready_try_later));
            finish();
            return;
        }

        // Load protocols by IDs - only stopped (inactive but not closed) protocols can be recovered
        for (String protocolId : protocolIds) {
            Protocol protocol = protocolManager.getProtocolById(protocolId);
            if (protocol != null && !Boolean.TRUE.equals(protocol.isActive()) && !Boolean.TRUE.equals(protocol.isClosed())) {
                protocolList.add(protocol);
            }
        }

        if (protocolList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_recoverable_protocols_found));
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
        loadProtocolCardList();
    }

    @Override
    protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        backButton = findViewById(R.id.back_button);
        protocolScrollView = findViewById(R.id.protocol_scroll_view);
        protocolStatisticsTextView = findViewById(R.id.protocol_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadProtocolCardList() {
        protocolListContainer = findViewById(R.id.fragment_container);

        protocolCardContainer = new ProtocolCardContainer(this, protocolListContainer, ChooseMode.WITHOUT_CHOOSE);
        protocolCardContainer.setHideEditButton(true);
        protocolCardContainer.setShowClearButton(true);
        protocolCardContainer.setOnClearIconClickListener(protocol -> {
            protocolCardContainer.removeProtocolById(protocol.getId());
            protocolList.clear();
            protocolList.addAll(protocolCardContainer.getProtocolList());
            updateUI();
        });

        protocolCardContainer.setOnProtocolListChangedListener(updatedProtocolList -> {
            protocolList.clear();
            protocolList.addAll(updatedProtocolList);
            updateUI();
        });

        Map<String, String> cidMap = protocolCardContainer.getCidMap(protocolList, this);
        for (Protocol protocol : protocolList) {
            protocolCardContainer.addProtocolCard(protocol, cidMap);
        }

        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasProtocols = protocolCardContainer != null && !protocolCardContainer.getProtocolList().isEmpty();
        recoverButton.setEnabled(hasProtocols);
        recoverButton.setAlpha(hasProtocols ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (protocolStatisticsTextView == null || protocolCardContainer == null) {
            return;
        }

        int containerSize = protocolCardContainer.getProtocolList().size();
        protocolStatisticsTextView.setText(getString(R.string.total_count, containerSize));
        protocolStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        recoverButton.setOnClickListener(v -> {
            hideKeyboard();
            if (protocolCardContainer == null) return;
            List<Protocol> protocolsToRecover = new ArrayList<>(protocolCardContainer.getProtocolList());
            if (protocolsToRecover.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_protocols_to_recover));
                return;
            }
            performRecoverOperation(protocolsToRecover);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performRecoverOperation(List<Protocol> protocolsToRecover) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        // Extract IDs for the recover operation
        List<String> protocolIds = new ArrayList<>();
        for (Protocol protocol : protocolsToRecover) {
            if (protocol.getId() != null) {
                protocolIds.add(protocol.getId());
            }
        }

        if (protocolIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_protocol_ids));
            return;
        }

        // Create FEIP for recover operation
        String feipJson = makeRecoverProtocolFeip(protocolIds);

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
                                // Update protocols in database
                                for (Protocol protocol : protocolsToRecover) {
                                    protocol.setActive(true);
                                    protocol.setOnChain(null); // Pending confirmation
                                    protocolManager.updateProtocol(protocol);
                                }
                                protocolManager.commit();

                                setResult(Activity.RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(RecoverProtocolActivity.this, 
                                    getString(R.string.failed_to_recover_protocols) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(RecoverProtocolActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(RecoverProtocolActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(RecoverProtocolActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeRecoverProtocolFeip(List<String> protocolIds) {
        Feip feip = Feip.fromName(PROTOCOL);
        ProtocolOpData protocolOpData = ProtocolOpData.makeRecover(protocolIds);
        feip.setData(protocolOpData);
        return feip.toJson();
    }
}

