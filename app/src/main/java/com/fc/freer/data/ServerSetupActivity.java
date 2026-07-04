package com.fc.freer.data;

import android.content.Intent;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.HomeOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Combined setup for the live FID's messaging (DOCK) and data (DISK) servers.
 * <p>
 * Both can default to the current FAPI server, or be set to different services.
 * A single HOME register TX writes both keys: {@code home.DOCK} as a plaintext SID/URL
 * (peers must read it to reach the relay) and {@code home.DISK} as the SID encrypted with
 * the FID public key (private storage — only the owner resolves it).
 * <p>
 * On success: DISK is marked locally and its client cached so Data is usable immediately;
 * DOCK enters {@link ImManager}'s pending state so the Talk tile enables once it confirms
 * on-chain (see {@link ImManager#onRegistrationTxSent(String)}).
 */
public class ServerSetupActivity extends BaseCryptoActivity {
    private static final String TAG = "ServerSetupActivity";

    private TextInputEditText dockInput;
    private TextInputEditText diskInput;
    private ImageButton chooseDockButton;
    private ImageButton chooseDiskButton;
    private ImageButton registerButton;
    private ImageButton backButton;

    private ActivityResultLauncher<Intent> setDockLauncher;
    private ActivityResultLauncher<Intent> setDiskLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_server_setup;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.server_setup_title);
    }

    @Override
    protected void setupActivityResultLaunchers() {
        super.setupActivityResultLaunchers();
        setDockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), dockInput);
                    }
                });
        setDiskLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), diskInput);
                    }
                });
    }

    @Override
    protected void initializeViews() {
        dockInput = findViewById(R.id.dock_input);
        diskInput = findViewById(R.id.disk_input);
        chooseDockButton = findViewById(R.id.choose_dock_button);
        chooseDiskButton = findViewById(R.id.choose_disk_button);
        registerButton = findViewById(R.id.register_button);
        backButton = findViewById(R.id.back_button);

        prefillDefaults();
    }

    @Override
    protected void setupButtons() {
        registerButton.setOnClickListener(v -> register());
        backButton.setOnClickListener(v -> finish());
        chooseDockButton.setOnClickListener(v -> setDockLauncher.launch(ServicePickerUtils.pickerIntent(
                this, Constants.DOCK_NO1_NRC7, getString(R.string.server_setup_dock_label))));
        chooseDiskButton.setOnClickListener(v -> setDiskLauncher.launch(ServicePickerUtils.pickerIntent(
                this, Constants.DISK_NO1_NRC7, getString(R.string.server_setup_disk_label))));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    /**
     * Prefill DOCK and DISK with the current values (existing home entries) or, if unset,
     * the current FAPI server's SID. DISK only defaults to the FAPI server when that server
     * advertises a "disk" component; otherwise it is left blank for the user to choose one.
     */
    private void prefillDefaults() {
        new Thread(() -> {
            ApiCenter api = ApiCenter.getInstance();
            String fapiSid = api.getBaseServiceSid();
            boolean baseHasDisk = api.baseHasDiskComponent();

            KeyInfo liveKeyInfo = FidManager.getInstance() != null
                    ? FidManager.getInstance().getLiveKeyInfo() : null;
            Map<String, String> home = liveKeyInfo != null ? liveKeyInfo.getHome() : null;

            String existingDock = home != null ? home.get(Constants.DOCK_NO1_NRC7) : null;

            String existingDiskSid = null;
            if (liveKeyInfo != null && DiskHomeManager.isConfigured(liveKeyInfo)) {
                byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
                existingDiskSid = DiskHomeManager.resolveSid(liveKeyInfo, prikey);
            }

            final String dockValue = (existingDock != null && !existingDock.isEmpty()) ? existingDock : fapiSid;
            final String diskValue = (existingDiskSid != null && !existingDiskSid.isEmpty())
                    ? existingDiskSid : (baseHasDisk ? fapiSid : null);

            runOnUiThread(() -> {
                if (dockValue != null) dockInput.setText(dockValue);
                if (diskValue != null) diskInput.setText(diskValue);
            });
        }).start();
    }

    private void register() {
        KeyInfo liveKeyInfo = FidManager.getInstance() != null
                ? FidManager.getInstance().getLiveKeyInfo() : null;
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        String dockVal = dockInput.getText() != null ? dockInput.getText().toString().trim() : "";
        String diskSid = diskInput.getText() != null ? diskInput.getText().toString().trim() : "";
        if (dockVal.isEmpty() && diskSid.isEmpty()) {
            ToastUtils.makeText(this, R.string.server_setup_need_one);
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        // Read-modify-write: preserve existing home entries; set DOCK (plaintext) and/or
        // DISK (encrypted) in a single HOME register TX.
        Map<String, String> homeMap = new HashMap<>();
        if (liveKeyInfo.getHome() != null) homeMap.putAll(liveKeyInfo.getHome());

        final boolean settingDock = !dockVal.isEmpty();
        if (settingDock) homeMap.put(Constants.DOCK_NO1_NRC7, dockVal);

        final boolean settingDisk = !diskSid.isEmpty();
        final String diskEnc;
        if (settingDisk) {
            diskEnc = DiskHomeManager.encryptSid(diskSid, liveKeyInfo.getPubkey());
            if (diskEnc == null) {
                ToastUtils.makeText(this, R.string.server_setup_failed);
                return;
            }
            homeMap.put(DiskHomeManager.DISK_KEY, diskEnc);
        } else {
            diskEnc = null;
        }

        HomeOpData homeOpData = new HomeOpData();
        homeOpData.setOp(HomeOpData.Op.REGISTER.toLowerCase());
        homeOpData.setHome(homeMap);

        Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.HOME);
        feip.setData(homeOpData);
        String feipJson = feip.toJson();

        ToastUtils.makeText(this, R.string.publishing);
        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            // DISK: usable immediately — mark locally and cache its client.
                            // Also set a persisted pending flag so the Data setup prompt stays
                            // suppressed until the TX confirms, even after FidManager refreshes
                            // KeyInfo.home from on-chain (which still lacks the unconfirmed DISK).
                            if (settingDisk) {
                                Map<String, String> localHome = liveKeyInfo.getHome() != null
                                        ? new HashMap<>(liveKeyInfo.getHome()) : new HashMap<>();
                                localHome.put(DiskHomeManager.DISK_KEY, diskEnc);
                                liveKeyInfo.setHome(localHome);
                                DiskHomeManager.resolveAndCacheDiskClient(liveKeyInfo, prikey);
                                DiskHomeManager.markRegistrationPending(
                                        getApplicationContext(), liveKeyInfo.getId());
                            }
                            // DOCK: peers need on-chain visibility — enter pending so the Talk
                            // tile enables once the TX confirms (and the prompt is suppressed).
                            if (settingDock) {
                                Setting setting = SettingManager.getInstance().getCurrentSetting();
                                ImManager im = setting != null ? setting.getImManager() : null;
                                if (im != null) im.onRegistrationTxSent(txId);
                            }
                            runOnUiThread(() -> {
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(ServerSetupActivity.this,
                                    getString(R.string.server_setup_failed) + ": " + errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(ServerSetupActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(ServerSetupActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> txSender.showSignedTxAsQR(ServerSetupActivity.this, signedTxHex));
                        }
                    });
        }).start();
    }
}
