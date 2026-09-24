package com.fc.freer.data;

import android.content.Intent;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

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
    private ActivityResultLauncher<Intent> setCallLauncher;
    private android.widget.CheckBox callCheckbox;
    private TextInputEditText callInput;
    /** The home already has a CALL entry: unticking removes it, and so stops calls. */
    private boolean hadCall;

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
        setCallLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), callInput);
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
        callCheckbox = findViewById(R.id.call_checkbox);
        callInput = findViewById(R.id.call_input);
        // Being callable is a choice: off unless the home already has a CALL service.
        callCheckbox.setOnCheckedChangeListener((b, on) ->
                findViewById(R.id.call_row).setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE));
        backButton = findViewById(R.id.back_button);

        // A multisig FID is a script address with no key pair: it cannot encrypt or decrypt
        // messages, so neither DOCK (messaging relay) nor DISK (private storage, whose SID is
        // encrypted to the owner's pubkey) applies. It also has no prikey to sign the HOME TX.
        if (FidManager.getInstance() != null && FidManager.getInstance().isLiveFidMultisig()) {
            ToastUtils.makeText(this, R.string.server_setup_not_for_multisig);
            finish();
            return;
        }

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
        findViewById(R.id.choose_call_button).setOnClickListener(v -> setCallLauncher.launch(
                ServicePickerUtils.pickerIntent(this, Constants.CALL_NO1_NRC7,
                        getString(R.string.server_setup_call_label))));
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
            String existingCall = home != null ? home.get(Constants.CALL_NO1_NRC7) : null;

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
                // CALL is never prefilled with a default: only what the user already chose.
                if (existingCall != null && !existingCall.isEmpty()) {
                    hadCall = true;
                    callInput.setText(existingCall);
                    callCheckbox.setChecked(true);
                }
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
        boolean takeCalls = callCheckbox.isChecked();
        String callVal = takeCalls && callInput.getText() != null ? callInput.getText().toString().trim() : "";
        if (takeCalls && callVal.isEmpty()) {
            ToastUtils.makeText(this, R.string.server_setup_call_empty);
            return;
        }
        boolean removeCall = hadCall && !takeCalls;
        if (dockVal.isEmpty() && diskSid.isEmpty() && callVal.isEmpty() && !removeCall) {
            ToastUtils.makeText(this, R.string.server_setup_need_one);
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        ToastUtils.makeText(this, R.string.publishing);
        final TxSender txSender = new TxSender();
        // The TX build, broadcast, and all post-broadcast bookkeeping (DISK/DOCK pending flags
        // and the persisted server-setup suppression flag) live in the shared helper, so this
        // manual path and the one-tap dialog path stay identical.
        ServerSetupManager.register(this, liveKeyInfo, dockVal, diskSid, callVal, removeCall, prikey,
                new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
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
    }
}
