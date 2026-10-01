package com.fc.freer.im;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

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
import com.fc.freer.data.TextEditorActivity;
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
import com.fc.freer.utils.DialogUtils;
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

    private static final String TAG = "CreateTeamActivity";

    private TextInputEditText nameInput;
    private TextInputEditText descInput;
    private TextInputEditText consensusInput;
    private TextInputEditText dockInput;
    private TextInputEditText diskInput;
    private TextInputEditText callInput;

    private ImageButton chooseDockButton;
    private ImageButton chooseDiskButton;
    private ImageButton chooseCallButton;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;
    private ImageButton viewConsensusButton;
    private ImageButton editConsensusButton;

    private EntityFieldPickers pickers;

    private ActivityResultLauncher<Intent> chooseDockLauncher;
    private ActivityResultLauncher<Intent> chooseDiskLauncher;
    private ActivityResultLauncher<Intent> chooseCallLauncher;
    private ActivityResultLauncher<Intent> pickDocLauncher;
    private ActivityResultLauncher<Intent> editDocLauncher;

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
        callInput = findViewById(R.id.call_input);

        chooseDockButton = findViewById(R.id.choose_dock_button);
        chooseDiskButton = findViewById(R.id.choose_disk_button);
        chooseCallButton = findViewById(R.id.choose_call_button);
        viewConsensusButton = findViewById(R.id.view_consensus_button);
        editConsensusButton = findViewById(R.id.edit_consensus_button);

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

        chooseCallLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), callInput);
                    }
                });

        chooseDiskLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), diskInput);
                    }
                });

        pickDocLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && result.getData().getData() != null) {
                        importPickedDoc(result.getData().getData());
                    }
                });

        // Editing re-hashes the text, so the document comes back under a different DID.
        editDocLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    String did = result.getData().getStringExtra(TextEditorActivity.EXTRA_RESULT_DID);
                    if (did != null && !did.isEmpty()) consensusInput.setText(did);
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

        viewConsensusButton.setOnClickListener(v -> {
            hideKeyboard();
            previewSelectedDoc();
        });

        editConsensusButton.setOnClickListener(v -> {
            hideKeyboard();
            editConsensusDoc();
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

        chooseCallButton.setOnClickListener(v -> {
            hideKeyboard();
            chooseCallLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.CALL_NO1_NRC7, getString(R.string.server_setup_call_label)));
        });
    }

    /**
     * Prefill DOCK and DISK with the SIDs of the main FID's home DOCK/DISK, or the BASE server's
     * SID for whichever the main FID has not set yet. The team home carries SIDs, never URLs, so
     * a URL-valued home.DOCK is replaced by the SID of the service it resolved to.
     */
    private void prefillDefaults() {
        new Thread(() -> {
            ApiCenter api = ApiCenter.getInstance();
            String baseSid = api.getBaseServiceSid();

            KeyInfo mainKeyInfo = FidManager.getInstance() != null
                    ? FidManager.getInstance().getMainKeyInfo() : null;
            Map<String, String> home = mainKeyInfo != null ? mainKeyInfo.getHome() : null;

            // A configured entry that cannot be turned into a SID is left blank for the user to
            // pick, rather than silently swapped for BASE.
            final String dockValue;
            String homeDock = home != null ? home.get(Constants.DOCK_NO1_NRC7) : null;
            if (homeDock != null && !homeDock.isEmpty()) {
                String sid = HomeServiceResolver.extractSid(homeDock);
                dockValue = sid != null ? sid : clientSid(api.getClient(ApiCenter.ConnectionRole.DOCK));
            } else {
                dockValue = baseSid;
            }

            final String diskValue;
            if (mainKeyInfo != null && DiskHomeManager.isConfigured(mainKeyInfo)) {
                byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(mainKeyInfo.getPrikeyCipher());
                diskValue = DiskHomeManager.resolveSid(mainKeyInfo, prikey);
            } else {
                diskValue = baseSid;
            }

            runOnUiThread(() -> {
                if (dockValue != null && isEmpty(dockInput)) dockInput.setText(dockValue);
                if (diskValue != null && isEmpty(diskInput)) diskInput.setText(diskValue);
            });
        }).start();
    }

    /** SID of the service a client is connected to, or null. */
    private static String clientSid(FapiClient client) {
        if (client == null) return null;
        if (client.getApiAccount() != null && client.getApiAccount().getProviderId() != null) {
            return client.getApiAccount().getProviderId();
        }
        return client.getServiceSid();
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
        final String callVal = getText(callInput);

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

        // Team home values are stored as plaintext (sid)<id> so any peer can resolve them, so
        // both DOCK and DISK must be SIDs. The DISK is also where the consensus is uploaded.
        final String dockSid = HomeServiceResolver.extractSid(dockVal);
        if (dockSid == null) {
            ToastUtils.makeText(this, R.string.team_dock_must_be_service);
            return;
        }
        final String diskSid = HomeServiceResolver.extractSid(diskVal);
        if (diskSid == null) {
            ToastUtils.makeText(this, R.string.team_disk_must_be_service);
            return;
        }
        final String dockHomeValue = "(sid)" + dockSid;
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
            // When they are not on this device, offer to import the file rather than dead-ending.
            HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
            File docFile = ConsensusDocHelper.resolveLocalDoc(this, hatManager, consensusId);
            if (docFile == null) {
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    publishButton.setEnabled(true);
                    promptImportDoc();
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
            // CALL is optional: without it, meetings run on each host's own.
            Map<String, String> homeMap = new HashMap<>(com.fc.freer.call.CallHome.withCall(null, callVal));
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

                                    // Mint the team's first symkey now, the way a room gets one
                                    // at creation. It has to come after savePendingTeam: the
                                    // owner check reads the team through the cache, and an
                                    // unconfirmed team does not exist on chain to fall back to.
                                    //
                                    // Free at this moment — the team's only member is its owner,
                                    // so there is nobody to distribute to and no network call is
                                    // made. Doing it here spares the owner the "creating
                                    // symkey..." gate on first opening their own team chat.
                                    // Members who join later fetch the key by request, so early
                                    // generation strands nobody.
                                    if (!teamHandler.createSymkey(txId)) {
                                        TimberLogger.w(TAG, "Could not create the symkey for new team %s;"
                                                + " it will be created on first use", txId);
                                    }
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

    /** Offer the system file picker when the chosen consensus DID has no bytes on this device. */
    private void promptImportDoc() {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.consensus_document)
                .setMessage(R.string.consensus_doc_not_local)
                .setPositiveButton(R.string.select_consensus_document, (d, w) -> launchDocPicker())
                .setNegativeButton(R.string.cancel, null));
    }

    private void launchDocPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        pickDocLauncher.launch(intent);
    }

    /**
     * Import a picked file into the local data store and put its content id in the consensus
     * field. The id is the hash of the bytes, so importing the same document twice is a no-op.
     */
    private void importPickedDoc(Uri uri) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        new Thread(() -> {
            HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
            ConsensusDocHelper helper = new ConsensusDocHelper(this, hatManager);
            ConsensusDocHelper.ImportedDoc imported = helper.importDocFromUri(this, uri);
            runOnUiThread(() -> {
                if (imported == null) {
                    ToastUtils.makeText(this, getString(R.string.failed_to_read_consensus_document)
                            + (helper.getLastError() != null ? ": " + helper.getLastError() : ""));
                    return;
                }
                consensusInput.setText(imported.did);
                ConsensusDocViewer.showFile(this, getString(R.string.consensus_document),
                        imported.file, 0, null);
            });
        }).start();
    }

    /**
     * Open the consensus document in the text editor. An empty field is seeded with a template
     * first, so a new team starts from something to react to rather than a blank file — most of
     * a consensus is the same questions every time, and an owner given a starting point writes a
     * usable one. An existing local document is opened as it stands.
     */
    private void editConsensusDoc() {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        final String did = getText(consensusInput);
        new Thread(() -> {
            HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
            ConsensusDocHelper helper = new ConsensusDocHelper(this, hatManager);

            String editId = did;
            if (editId.isEmpty() || ConsensusDocHelper.resolveLocalDoc(this, hatManager, editId) == null) {
                ConsensusDocHelper.ImportedDoc seeded = helper.createDocFromText(this,
                        getString(R.string.consensus_template),
                        getString(R.string.consensus_template_name));
                if (seeded == null) {
                    runOnUiThread(() -> ToastUtils.makeText(this,
                            getString(R.string.failed_to_read_consensus_document)
                                    + (helper.getLastError() != null ? ": " + helper.getLastError() : "")));
                    return;
                }
                editId = seeded.did;
            }

            final String hatId = editId;
            runOnUiThread(() -> {
                consensusInput.setText(hatId);
                Intent intent = new Intent(this, TextEditorActivity.class);
                intent.putExtra(TextEditorActivity.EXTRA_HAT_ID, hatId);
                editDocLauncher.launch(intent);
            });
        }).start();
    }

    /** Read the document currently named in the consensus field, before committing to it. */
    private void previewSelectedDoc() {
        String did = getText(consensusInput);
        if (did.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_consensus_doc));
            return;
        }
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
        File local = ConsensusDocHelper.resolveLocalDoc(this, hatManager, did);
        if (local == null) {
            promptImportDoc();
            return;
        }
        ConsensusDocViewer.showFile(this, getString(R.string.consensus_document), local, 0, null);
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
