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
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Map;

/**
 * Combined setup for the live FID's chain (BASE), messaging (DOCK) and data (DISK) servers.
 * <p>
 * Each can default to the current FAPI server, or be set to a different service.
 * A single HOME register TX writes them: {@code home.DOCK} as a plaintext SID/URL (peers must
 * read it to reach the relay), and {@code home.BASE} and {@code home.DISK} public or sealed to
 * the FID public key, as the user ticks (see {@link HomePrivacy}). The main FID's BASE is the
 * server every device of it connects to; the follow box here is this device's way out.
 * <p>
 * On success: DISK is marked locally and its client cached so Data is usable immediately;
 * DOCK enters {@link ImManager}'s pending state so the Talk tile enables once it confirms
 * on-chain (see {@link ImManager#onRegistrationTxSent(String)}).
 */
public class ServerSetupActivity extends BaseCryptoActivity {
    private static final String TAG = "ServerSetupActivity";

    private TextInputEditText baseInput;
    private android.widget.CheckBox basePrivateCheckbox;
    private android.widget.CheckBox diskPrivateCheckbox;
    private android.widget.CheckBox followBaseCheckbox;
    private ActivityResultLauncher<Intent> setBaseLauncher;
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
    private ActivityResultLauncher<Intent> setRoadLauncher;
    private android.widget.CheckBox roadCheckbox;
    private TextInputEditText roadInput;
    /** The home already has a ROAD entry: unticking removes it, with its MAP. */
    private boolean hadRoad;
    private android.widget.CheckBox fudpCheckbox;
    private TextInputEditText fudpInput;
    /** The home already has a FUDP entry: unticking removes it. */
    private boolean hadFudp;

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
        setBaseLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), baseInput);
                    }
                });
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
        setRoadLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), roadInput);
                    }
                });
    }

    @Override
    protected void initializeViews() {
        baseInput = findViewById(R.id.base_input);
        basePrivateCheckbox = findViewById(R.id.base_private_checkbox);
        diskPrivateCheckbox = findViewById(R.id.disk_private_checkbox);
        followBaseCheckbox = findViewById(R.id.follow_base_checkbox);
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
        roadCheckbox = findViewById(R.id.road_checkbox);
        roadInput = findViewById(R.id.road_input);
        roadCheckbox.setOnCheckedChangeListener((b, on) ->
                findViewById(R.id.road_row).setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE));
        fudpCheckbox = findViewById(R.id.fudp_checkbox);
        fudpInput = findViewById(R.id.fudp_input);
        fudpCheckbox.setOnCheckedChangeListener((b, on) ->
                findViewById(R.id.fudp_row).setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE));
        backButton = findViewById(R.id.back_button);

        // An address cannot be sealed: the box is only offered for a service id (or nothing yet).
        bindPrivateBox(baseInput, basePrivateCheckbox);
        bindPrivateBox(diskInput, diskPrivateCheckbox);

        // A device setting, not a carve: saved the moment it changes, used at the next launch.
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        followBaseCheckbox.setChecked(setting == null || setting.isFollowHomeBase());
        followBaseCheckbox.setOnCheckedChangeListener((b, on) -> {
            Setting current = SettingManager.getInstance().getCurrentSetting();
            if (current == null) return;
            current.setFollowHomeBase(on);
            SettingManager.getInstance().saveSettings(this, current);
        });
        showBaseConnection();

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

    /** Which server this device reads through, and why the home BASE is not it, if it is not. */
    private void showBaseConnection() {
        ApiCenter api = ApiCenter.getInstance();
        String url = api.getBaseConnectionUrl();
        android.widget.TextView connection = findViewById(R.id.base_connection_text);
        if (url == null) {
            connection.setText(R.string.server_setup_connected_none);
        } else {
            connection.setText(getString(api.isOnHomeBase()
                    ? R.string.server_setup_connected_home : R.string.server_setup_connected_other, url));
        }
        android.widget.TextView problem = findViewById(R.id.base_problem_text);
        int res = api.getHomeBaseProblemRes();
        if (res != 0) {
            problem.setText(getString(res, api.getHomeBaseProblemArg()));
            problem.setVisibility(android.view.View.VISIBLE);
        }
    }

    private static boolean sealable(CharSequence value) {
        String v = value != null ? value.toString().trim() : "";
        return v.isEmpty() || com.fc.fc_ajdk.fapi.client.HomeServiceResolver.extractSid(v) != null;
    }

    private static void bindPrivateBox(TextInputEditText input, android.widget.CheckBox box) {
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                box.setEnabled(sealable(s));
            }
        });
    }

    @Override
    protected void setupButtons() {
        registerButton.setOnClickListener(v -> register());
        findViewById(R.id.choose_base_button).setOnClickListener(v -> setBaseLauncher.launch(
                ServicePickerUtils.pickerIntent(this, Constants.BASE_NO1_NRC7,
                        getString(R.string.server_setup_base_label))));
        backButton.setOnClickListener(v -> finish());
        chooseDockButton.setOnClickListener(v -> setDockLauncher.launch(ServicePickerUtils.pickerIntent(
                this, Constants.DOCK_NO1_NRC7, getString(R.string.server_setup_dock_label))));
        chooseDiskButton.setOnClickListener(v -> setDiskLauncher.launch(ServicePickerUtils.pickerIntent(
                this, Constants.DISK_NO1_NRC7, getString(R.string.server_setup_disk_label))));
        findViewById(R.id.choose_call_button).setOnClickListener(v -> setCallLauncher.launch(
                ServicePickerUtils.pickerIntent(this, Constants.CALL_NO1_NRC7,
                        getString(R.string.server_setup_call_label))));
        // ROAD delivers only to devices in its own MAP: offer only services running both.
        findViewById(R.id.choose_road_button).setOnClickListener(v -> {
            Intent picker = ServicePickerUtils.pickerIntent(this, Constants.ROAD_NO1_NRC7,
                    getString(R.string.server_setup_road_label));
            picker.putExtra(SetDiskActivity.EXTRA_ALSO_COMPONENT, Constants.MAP_NO1_NRC7);
            setRoadLauncher.launch(picker);
        });
    }

    /** The value of {@code home}'s entry for {@code kind}, under the bare kind or kind@anything. */
    private static String homeValue(Map<String, String> home, String kind) {
        if (home == null) return null;
        String own = home.get(kind + "@No1_NrC7");
        if (own != null && !own.trim().isEmpty()) return own.trim();
        for (Map.Entry<String, String> entry : home.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.trim().isEmpty()) continue;
            if (key.equalsIgnoreCase(kind) || key.toUpperCase(java.util.Locale.ROOT).startsWith(kind + "@")) {
                return value.trim();
            }
        }
        return null;
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
            String existingRoad = homeValue(home, "ROAD");
            String existingFudp = homeValue(home, "FUDP");

            String existingDiskSid = null;
            byte[] prikey = liveKeyInfo != null
                    ? SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher()) : null;
            if (liveKeyInfo != null && DiskHomeManager.isConfigured(liveKeyInfo)) {
                existingDiskSid = DiskHomeManager.resolveSid(liveKeyInfo, prikey);
            }
            String storedDisk = HomePrivacy.valueOf(home, "DISK");
            String storedBase = HomePrivacy.valueOf(home, "BASE");
            String openedBase = HomePrivacy.open(storedBase, prikey);
            String existingBase = openedBase != null
                    ? com.fc.fc_ajdk.fapi.client.HomeServiceResolver.extractSid(openedBase) : null;
            if (existingBase == null) existingBase = openedBase;
            // The server already being read through is a BASE by definition.
            final String baseValue = existingBase != null ? existingBase : fapiSid;

            final String dockValue = (existingDock != null && !existingDock.isEmpty()) ? existingDock : fapiSid;
            final String diskValue = (existingDiskSid != null && !existingDiskSid.isEmpty())
                    ? existingDiskSid : (baseHasDisk ? fapiSid : null);

            runOnUiThread(() -> {
                if (baseValue != null) baseInput.setText(baseValue);
                // What the chain already shows decides the box; nothing there leaves it private.
                if (storedBase != null) basePrivateCheckbox.setChecked(HomePrivacy.isSealed(storedBase));
                if (storedDisk != null) diskPrivateCheckbox.setChecked(HomePrivacy.isSealed(storedDisk));
                if (dockValue != null) dockInput.setText(dockValue);
                if (diskValue != null) diskInput.setText(diskValue);
                // CALL is never prefilled with a default: only what the user already chose.
                if (existingCall != null && !existingCall.isEmpty()) {
                    hadCall = true;
                    callInput.setText(existingCall);
                    callCheckbox.setChecked(true);
                }
                // Nor ROAD, which is paid for while it is kept; suggest one when there is none.
                if (existingRoad != null) {
                    hadRoad = true;
                    roadInput.setText(existingRoad);
                    roadCheckbox.setChecked(true);
                } else {
                    findViewById(R.id.road_suggest).setVisibility(android.view.View.VISIBLE);
                }
                // FUDP is never filled in by the app: only shown so a wrong one can be removed.
                if (existingFudp != null) {
                    hadFudp = true;
                    fudpInput.setText(existingFudp);
                    fudpCheckbox.setChecked(true);
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

        String baseVal = baseInput.getText() != null ? baseInput.getText().toString().trim() : "";
        String dockVal = dockInput.getText() != null ? dockInput.getText().toString().trim() : "";
        String diskSid = diskInput.getText() != null ? diskInput.getText().toString().trim() : "";
        boolean takeCalls = callCheckbox.isChecked();
        String callVal = takeCalls && callInput.getText() != null ? callInput.getText().toString().trim() : "";
        if (takeCalls && callVal.isEmpty()) {
            ToastUtils.makeText(this, R.string.server_setup_call_empty);
            return;
        }
        boolean removeCall = hadCall && !takeCalls;
        boolean useRoad = roadCheckbox.isChecked();
        String roadVal = useRoad && roadInput.getText() != null ? roadInput.getText().toString().trim() : "";
        if (useRoad && roadVal.isEmpty()) {
            ToastUtils.makeText(this, R.string.server_setup_road_empty);
            return;
        }
        boolean useFudp = fudpCheckbox.isChecked();
        String fudpVal = useFudp && fudpInput.getText() != null ? fudpInput.getText().toString().trim() : "";
        if (useFudp && !fudpVal.matches("fudp://[^\\s/:]+:\\d{1,5}/?")) {
            ToastUtils.makeText(this, R.string.server_setup_fudp_bad);
            return;
        }
        ServerSetupManager.HomeEdits edits = new ServerSetupManager.HomeEdits();
        edits.call = callVal;
        edits.removeCall = removeCall;
        edits.road = roadVal;
        edits.removeRoad = hadRoad && !useRoad;
        edits.fudp = fudpVal;
        edits.removeFudp = hadFudp && !useFudp;
        edits.base = baseVal;
        // An address is always public, whatever the box said: it cannot be sealed.
        edits.basePrivate = basePrivateCheckbox.isChecked() && sealable(baseVal);
        edits.diskPrivate = diskPrivateCheckbox.isChecked() && sealable(diskSid);
        if (dockVal.isEmpty() && diskSid.isEmpty() && !edits.touchesAny()) {
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
        ServerSetupManager.register(this, liveKeyInfo, dockVal, diskSid, edits, prikey,
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
