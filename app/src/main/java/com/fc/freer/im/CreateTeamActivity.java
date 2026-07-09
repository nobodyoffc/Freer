package com.fc.freer.im;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.data.DiskHomeManager;
import com.fc.freer.im.handler.TeamHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.EntityFieldPickers;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreateTeamActivity extends BaseCryptoActivity {

    private TextInputEditText nameInput;
    private TextInputEditText descInput;
    private TextInputEditText consensusInput;
    private TextInputEditText dockInput;
    private TextInputEditText diskInput;

    private ImageButton chooseDockButton;
    private ImageButton chooseDiskButton;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private EntityFieldPickers pickers;

    private ActivityResultLauncher<Intent> chooseDockLauncher;
    private ActivityResultLauncher<Intent> chooseDiskLauncher;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_DESC = 1003;
    private static final int QR_SCAN_CONSENSUS = 1004;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_team;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_team);
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

        pickers = new EntityFieldPickers(this);

        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        // Consensus field: pick a local data DID via the file icon, or enter/scan it manually.
        pickers.bindDidField(R.id.consensusView, R.id.scanIcon, QR_SCAN_CONSENSUS, consensusInput);

        prefillDefaults();
    }

    @Override
    protected void initializeViews() {
        View nameView = findViewById(R.id.nameView);
        View descView = findViewById(R.id.descView);

        nameInput = nameView.findViewById(R.id.textInput);
        nameInput.setHint(R.string.team_name);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.team_description);

        View consensusView = findViewById(R.id.consensusView);
        consensusInput = consensusView.findViewById(R.id.textInput);
        consensusInput.setHint(R.string.consensus_doc_hint);

        dockInput = findViewById(R.id.dock_input);
        diskInput = findViewById(R.id.disk_input);

        chooseDockButton = findViewById(R.id.choose_dock_button);
        chooseDiskButton = findViewById(R.id.choose_disk_button);

        clearButton = findViewById(R.id.clearButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);

        chooseDockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), dockInput);
                    }
                });

        chooseDiskLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), diskInput);
                    }
                });
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishTeam();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });

        chooseDockButton.setOnClickListener(v -> {
            hideKeyboard();
            chooseDockLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.DOCK_NO1_NRC7, getString(R.string.server_setup_dock_label)));
        });

        chooseDiskButton.setOnClickListener(v -> {
            hideKeyboard();
            chooseDiskLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.DISK_NO1_NRC7, getString(R.string.server_setup_disk_label)));
        });
    }

    /**
     * Prefill DOCK and DISK with the owner's existing home entries, or the current FAPI
     * server's SID when unset. Mirrors {@link com.fc.freer.data.ServerSetupActivity}.
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

            final String dockValue = (existingDock != null && !existingDock.isEmpty())
                    ? HomeServiceResolver.extractSid(existingDock) != null
                        ? HomeServiceResolver.extractSid(existingDock) : existingDock
                    : fapiSid;
            final String diskValue = (existingDiskSid != null && !existingDiskSid.isEmpty())
                    ? existingDiskSid : (baseHasDisk ? fapiSid : null);

            runOnUiThread(() -> {
                if (dockValue != null && isEmpty(dockInput)) dockInput.setText(dockValue);
                if (diskValue != null && isEmpty(diskInput)) diskInput.setText(diskValue);
            });
        }).start();
    }

    private boolean isEmpty(TextInputEditText input) {
        return input.getText() == null || input.getText().toString().trim().isEmpty();
    }

    private void clearInputs() {
        nameInput.setText("");
        descInput.setText("");
        consensusInput.setText("");
    }

    private void publishTeam() {
        String name = getText(nameInput);
        String desc = getText(descInput);
        final String consensusId = getText(consensusInput);
        String dockVal = getText(dockInput);
        String diskVal = getText(diskInput);

        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.team_name_required);
            return;
        }
        if (consensusId.isEmpty()) {
            ToastUtils.makeText(this, R.string.consensus_document_required);
            return;
        }
        if (dockVal.isEmpty() || diskVal.isEmpty()) {
            ToastUtils.makeText(this, R.string.team_servers_required);
            return;
        }

        // The DISK must resolve to a SID: it is where the consensus is uploaded and the SID
        // is published (plaintext) into the team home for members to resolve.
        final String diskSid = HomeServiceResolver.extractSid(diskVal);
        if (diskSid == null) {
            ToastUtils.makeText(this, R.string.team_disk_must_be_service);
            return;
        }
        // Team home values are stored as plaintext (sid)<id> so any peer can resolve them.
        final String dockHomeValue = toSidHomeValue(dockVal);
        final String diskHomeValue = "(sid)" + diskSid;

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        publishButton.setEnabled(false);
        final WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.uploading_consensus_document));
        waitingDialog.show();

        new Thread(() -> {
            // Step 1: resolve the chosen consensus DID to its local, unencrypted document bytes.
            HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
            File docFile = resolveLocalDataFile(hatManager, consensusId);
            if (docFile == null) {
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    publishButton.setEnabled(true);
                    ToastUtils.makeText(this, getString(R.string.consensus_doc_not_local));
                });
                return;
            }

            // Step 2: upload the public consensus document to the chosen DISK (permanent),
            // deriving the authoritative consensusId from its bytes. Report byte progress so the
            // user sees the upload advancing (it dominates the time before the TX is ready).
            final long totalBytes = docFile.length();
            ConsensusDocHelper consensusHelper = new ConsensusDocHelper(this, hatManager);
            final String uploadedId = consensusHelper.uploadConsensusDoc(docFile, diskSid,
                    bytesUploaded -> runOnUiThread(() -> waitingDialog.setHint(
                            getString(R.string.uploading_consensus_document) + " "
                                    + FileShareHelper.formatSize(bytesUploaded)
                                    + " / " + FileShareHelper.formatSize(totalBytes))));
            if (uploadedId == null) {
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    publishButton.setEnabled(true);
                    ToastUtils.makeText(this, getString(R.string.failed_to_upload_consensus_document)
                            + (consensusHelper.getLastError() != null ? ": " + consensusHelper.getLastError() : ""));
                });
                return;
            }

            // Upload done; the remaining wait is the TX carving/broadcast.
            runOnUiThread(() -> waitingDialog.setHint(getString(R.string.publishing)));

            // Step 3: build the team with the verified consensusId and plaintext home.
            Map<String, String> homeMap = new HashMap<>();
            homeMap.put(Constants.DOCK_NO1_NRC7, dockHomeValue);
            homeMap.put(Constants.DISK_NO1_NRC7, diskHomeValue);

            TeamOpData opData = TeamOpData.makeCreate(
                    name, uploadedId, null, null, null,
                    desc.isEmpty() ? null : desc);
            opData.setHome(homeMap);

            Feip feip = Feip.fromName("Team");
            feip.setData(opData);
            String feipJson = feip.toJson();

            // Step 4: carve the create TX.
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            Setting setting = SettingManager.getInstance().getCurrentSetting();
                            if (setting != null && setting.getImManager() != null) {
                                TeamHandler teamHandler = setting.getImManager().getTeamHandler();
                                if (teamHandler != null) {
                                    Team pending = new Team();
                                    pending.setId(txId);
                                    pending.setOwner(liveKeyInfo.getId());
                                    pending.setStdName(name);
                                    pending.setConsensusId(uploadedId);
                                    pending.setHome(homeMap);
                                    if (!desc.isEmpty()) pending.setDesc(desc);
                                    pending.setActive(true);
                                    List<String> members = new ArrayList<>();
                                    members.add(liveKeyInfo.getId());
                                    pending.setMembers(members);
                                    pending.setLastTime(System.currentTimeMillis());
                                    teamHandler.savePendingTeam(pending);
                                }
                            }
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                publishButton.setEnabled(true);
                                ToastUtils.makeText(CreateTeamActivity.this,
                                        getString(R.string.failed_to_publish_team) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                publishButton.setEnabled(true);
                                ToastUtils.makeText(CreateTeamActivity.this,
                                        R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateTeamActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                publishButton.setEnabled(true);
                                txSender.showSignedTxAsQR(CreateTeamActivity.this, signedTxHex);
                            });
                        }

                        @Override
                        public void onCancelled() {
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                publishButton.setEnabled(true);
                            });
                        }
                    });
        }).start();
    }

    /**
     * Resolve a local, unencrypted document file for the given data DID, or null if the bytes
     * are not available locally. Mirrors {@link com.fc.freer.data.DataActivity}'s local lookup:
     * a {@code local://} loca on the HAT, else the default {@code files/data/<did>} file.
     */
    private File resolveLocalDataFile(HatManager hatManager, String did) {
        Hat hat = hatManager.getHatById(did);
        if (hat != null && hat.getLocas() != null) {
            for (String loca : hat.getLocas()) {
                if (loca != null && loca.startsWith("local://")) {
                    File f = new File(loca.substring("local://".length()));
                    if (f.exists()) return f;
                }
            }
        }
        File defaultFile = new File(new File(getFilesDir(), "data"), did);
        return defaultFile.exists() ? defaultFile : null;
    }

    /** Normalize a DOCK/DISK input to a plaintext {@code (sid)<id>} home value when it is a SID. */
    private String toSidHomeValue(String input) {
        String sid = HomeServiceResolver.extractSid(input);
        return sid != null ? "(sid)" + sid : input;
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_NAME:
                nameInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_CONSENSUS:
                consensusInput.setText(qrContent);
                break;
        }
    }
}
