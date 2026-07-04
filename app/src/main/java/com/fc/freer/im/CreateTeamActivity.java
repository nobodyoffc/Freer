package com.fc.freer.im;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.utils.TimberLogger;
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
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreateTeamActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateTeamActivity";

    private TextInputEditText nameInput;
    private TextInputEditText descInput;
    private TextInputEditText dockInput;
    private TextInputEditText diskInput;

    private TextView consensusDocNameText;
    private ImageButton attachDocButton;
    private ImageButton clearDocButton;
    private ImageButton chooseDockButton;
    private ImageButton chooseDiskButton;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private Uri consensusDocUri;

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> chooseDockLauncher;
    private ActivityResultLauncher<Intent> chooseDiskLauncher;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_DESC = 1003;

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

        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);

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

        dockInput = findViewById(R.id.dock_input);
        diskInput = findViewById(R.id.disk_input);

        consensusDocNameText = findViewById(R.id.consensus_doc_name);
        attachDocButton = findViewById(R.id.attach_doc_button);
        clearDocButton = findViewById(R.id.clear_doc_button);
        chooseDockButton = findViewById(R.id.choose_dock_button);
        chooseDiskButton = findViewById(R.id.choose_disk_button);

        clearButton = findViewById(R.id.clearButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            consensusDocUri = uri;
                            consensusDocNameText.setText(resolveFileName(uri));
                            consensusDocNameText.setTextColor(getResources().getColor(R.color.accent, null));
                            clearDocButton.setVisibility(View.VISIBLE);
                        }
                    }
                });

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

        attachDocButton.setOnClickListener(v -> {
            hideKeyboard();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        clearDocButton.setOnClickListener(v -> {
            consensusDocUri = null;
            consensusDocNameText.setText(R.string.no_consensus_doc);
            consensusDocNameText.setTextColor(getResources().getColor(R.color.hint, null));
            clearDocButton.setVisibility(View.GONE);
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
        clearDocButton.performClick();
    }

    private void publishTeam() {
        String name = getText(nameInput);
        String desc = getText(descInput);
        String dockVal = getText(dockInput);
        String diskVal = getText(diskInput);

        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.team_name_required);
            return;
        }
        if (consensusDocUri == null) {
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
        final Uri docUri = consensusDocUri;
        final WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.uploading_consensus_document));
        waitingDialog.show();

        new Thread(() -> {
            // Step 1: copy the picked document to a temp file.
            File docFile = copyUriToTemp(docUri);
            if (docFile == null) {
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    publishButton.setEnabled(true);
                    ToastUtils.makeText(this, getString(R.string.failed_to_read_consensus_document));
                });
                return;
            }

            try {
                // Step 2: upload the public consensus document to the chosen DISK (permanent),
                // and derive consensusId from it. Report byte progress so the user sees the
                // upload advancing (it dominates the time before the TX is ready).
                final long totalBytes = docFile.length();
                HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
                ConsensusDocHelper consensusHelper = new ConsensusDocHelper(this, hatManager);
                final String consensusId = consensusHelper.uploadConsensusDoc(docFile, diskSid,
                        bytesUploaded -> runOnUiThread(() -> waitingDialog.setHint(
                                getString(R.string.uploading_consensus_document) + " "
                                        + FileShareHelper.formatSize(bytesUploaded)
                                        + " / " + FileShareHelper.formatSize(totalBytes))));
                if (consensusId == null) {
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

                // Step 3: build the team with the computed consensusId and plaintext home.
                Map<String, String> homeMap = new HashMap<>();
                homeMap.put(Constants.DOCK_NO1_NRC7, dockHomeValue);
                homeMap.put(Constants.DISK_NO1_NRC7, diskHomeValue);

                TeamOpData opData = TeamOpData.makeCreate(
                        name, consensusId, null, null, null,
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
                                        pending.setConsensusId(consensusId);
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
            } finally {
                if (docFile.exists()) docFile.delete();
            }
        }).start();
    }

    /** Normalize a DOCK/DISK input to a plaintext {@code (sid)<id>} home value when it is a SID. */
    private String toSidHomeValue(String input) {
        String sid = HomeServiceResolver.extractSid(input);
        return sid != null ? "(sid)" + sid : input;
    }

    private File copyUriToTemp(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            File temp = File.createTempFile("consensus_", ".tmp", getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
            }
            return temp;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to copy consensus doc: %s", e.getMessage());
            return null;
        }
    }

    private String resolveFileName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        return name != null ? name : "file";
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
        }
    }
}
