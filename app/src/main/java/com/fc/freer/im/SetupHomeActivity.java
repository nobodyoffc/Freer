package com.fc.freer.im;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ScrollView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.HomeOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Activity for setting up Home on chain.
 * Home data is carved onchain via FEIP protocol, unlike P2P chat settings which are stored locally.
 */
public class SetupHomeActivity extends BaseCryptoActivity {

    private TextInputEditText fudpInput;
    private TextInputEditText mapInput;
    private TextInputEditText roadInput;
    private TextInputEditText dockInput;

    private CheckBox advancedCheckBox;
    private View advancedFieldsContainer;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private static final int QR_SCAN_FUDP = 2001;
    private static final int QR_SCAN_MAP = 2002;
    private static final int QR_SCAN_ROAD = 2003;
    private static final int QR_SCAN_DOCK = 2004;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_setup_home;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.setup_home);
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

        TextIconsUtils.setupTextIcons(this, R.id.fudpView, R.id.scanIcon, QR_SCAN_FUDP);
        TextIconsUtils.setupTextIcons(this, R.id.mapView, R.id.scanIcon, QR_SCAN_MAP);
        TextIconsUtils.setupTextIcons(this, R.id.roadView, R.id.scanIcon, QR_SCAN_ROAD);
        TextIconsUtils.setupTextIcons(this, R.id.dockView, R.id.scanIcon, QR_SCAN_DOCK);

        ScrollView scrollView = findViewById(R.id.scroll_view);
        if (scrollView != null) {
            scrollView.setOnTouchListener((v, event) -> {
                hideKeyboard();
                return false;
            });
        }
    }

    @Override
    protected void initializeViews() {
        View fudpView = findViewById(R.id.fudpView);
        View mapView = findViewById(R.id.mapView);
        View roadView = findViewById(R.id.roadView);
        View dockView = findViewById(R.id.dockView);

        fudpInput = fudpView.findViewById(R.id.textInput);
        fudpInput.setHint(R.string.home_fudp_hint);

        mapInput = mapView.findViewById(R.id.textInput);
        mapInput.setHint(R.string.home_map_hint);

        roadInput = roadView.findViewById(R.id.textInput);
        roadInput.setHint(R.string.home_road_hint);

        dockInput = dockView.findViewById(R.id.textInput);
        dockInput.setHint(R.string.home_dock_hint);

        advancedCheckBox = findViewById(R.id.advancedCheckBox);
        advancedFieldsContainer = findViewById(R.id.advancedFieldsContainer);
        advancedCheckBox.setOnClickListener(v -> {
            hideKeyboard();
            advancedFieldsContainer.setVisibility(advancedCheckBox.isChecked() ? View.VISIBLE : View.GONE);
        });

        clearButton = findViewById(R.id.clearButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);

        loadExistingHome();
    }

    private void loadExistingHome() {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        Map<String, String> existingHome = (liveKeyInfo != null) ? liveKeyInfo.getHome() : null;

        if (existingHome != null && existingHome.containsKey("FUDP@No1_NrC7")) {
            fudpInput.setText(existingHome.get("FUDP@No1_NrC7"));
        } else {
            prefillFudpFromRunningNode();
        }

        if (existingHome != null && existingHome.containsKey("MAP@No1_NrC7")) {
            mapInput.setText(existingHome.get("MAP@No1_NrC7"));
        }

        if (existingHome != null && existingHome.containsKey("ROAD@No1_NrC7")) {
            roadInput.setText(existingHome.get("ROAD@No1_NrC7"));
        }

        if (existingHome != null && existingHome.containsKey("DOCK@No1_NrC7")) {
            dockInput.setText(existingHome.get("DOCK@No1_NrC7"));
        }

        // Advanced unchecked by default; user can expand to view/edit FUDP, MAP, ROAD
        advancedCheckBox.setChecked(false);
        advancedFieldsContainer.setVisibility(View.GONE);

        prefillMapRoadDockFromFapiClient();
    }

    private void prefillMapRoadDockFromFapiClient() {
        new Thread(() -> {
            try {
                Object client = ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (!(client instanceof FapiClient fapiClient) || !fapiClient.isConnected()) {
                    return;
                }
                if (fapiClient.getApiAccount() == null) return;
                String url = fapiClient.getApiAccount().getApiUrl();
                if (url == null || url.isEmpty()) return;
                final String fapiUrl = url.toLowerCase().startsWith("fudp://") ? url : "fudp://" + url;
                runOnUiThread(() -> {
                    if (mapInput.getText() == null || mapInput.getText().toString().trim().isEmpty()) {
                        mapInput.setText(fapiUrl);
                    }
                    if (roadInput.getText() == null || roadInput.getText().toString().trim().isEmpty()) {
                        roadInput.setText(fapiUrl);
                    }
                    if (dockInput.getText() == null || dockInput.getText().toString().trim().isEmpty()) {
                        dockInput.setText(fapiUrl);
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void prefillFudpFromRunningNode() {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;

            FudpNode fudpNode = setting.getFudpNode();
            if (fudpNode == null || !fudpNode.isRunning()) return;

            int port = fudpNode.getConfig().getPort();
            String localIp = getLocalIpAddress();
            if (localIp != null) {
                String currentFudp = "fudp://" + localIp + ":" + port;
                fudpInput.setText(currentFudp);
            }
        } catch (Exception ignored) {
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface intf : Collections.list(interfaces)) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                Enumeration<InetAddress> addresses = intf.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            registerHome();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        fudpInput.setText("");
        mapInput.setText("");
        roadInput.setText("");
        dockInput.setText("");
    }

    private void registerHome() {
        String fudp = getText(fudpInput);
        String map = getText(mapInput);
        String road = getText(roadInput);
        String dock = getText(dockInput);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Read-modify-write: preserve existing home entries not edited here (e.g. DISK),
        // and overlay the four editable fields (set when non-empty, remove when cleared).
        Map<String, String> homeMap = new HashMap<>();
        if (liveKeyInfo.getHome() != null) homeMap.putAll(liveKeyInfo.getHome());
        putOrRemove(homeMap, "FUDP@No1_NrC7", fudp);
        putOrRemove(homeMap, "MAP@No1_NrC7", map);
        putOrRemove(homeMap, "ROAD@No1_NrC7", road);
        putOrRemove(homeMap, "DOCK@No1_NrC7", dock);

        if (homeMap.isEmpty()) {
            ToastUtils.makeText(this, R.string.home_requires_at_least_one_field);
            return;
        }

        HomeOpData homeOpData = new HomeOpData();
        homeOpData.setOp(HomeOpData.Op.REGISTER.toLowerCase());
        homeOpData.setHome(homeMap);

        Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.HOME);
        feip.setData(homeOpData);
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
                            // If this registration set home.DOCK, enter the pending state so
                            // IM stays disabled and the setup dialog is suppressed until the
                            // TX confirms on-chain.
                            if (dock != null && !dock.isEmpty()) {
                                Setting setting = SettingManager.getInstance().getCurrentSetting();
                                ImManager im = (setting != null) ? setting.getImManager() : null;
                                if (im != null) im.onRegistrationTxSent(txId);
                            }
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SetupHomeActivity.this,
                                    getString(R.string.home_registered_successfully, txId));
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SetupHomeActivity.this,
                                    getString(R.string.failed_to_register_home) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SetupHomeActivity.this,
                                    R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(SetupHomeActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(SetupHomeActivity.this, signedTxHex);
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

    private void putOrRemove(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        } else {
            map.remove(key);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_FUDP:
                fudpInput.setText(qrContent);
                break;
            case QR_SCAN_MAP:
                mapInput.setText(qrContent);
                break;
            case QR_SCAN_ROAD:
                roadInput.setText(qrContent);
                break;
            case QR_SCAN_DOCK:
                dockInput.setText(qrContent);
                break;
        }
    }
}
