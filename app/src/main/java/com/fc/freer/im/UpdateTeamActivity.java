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
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Edit a team the live FID owns.
 * <p>
 * Consensus is handled the same way {@link CreateTeamActivity} handles it: the field holds a
 * data DID, the document behind it is uploaded unencrypted and permanent to the team's DISK on
 * publish, and the id carved on chain is the hash the upload returned — never a string the user
 * typed. The document can also be read back from the DISK before deciding to change it.
 * <p>
 * Two rules make the change safe for members:
 * <ul>
 *   <li>{@code home} is <b>merged</b> over the team's stored map, never replaced. The parser
 *       overwrites {@code home} wholesale, so carving a partial map would drop the DISK entry
 *       and leave the consensus unfetchable for everyone.</li>
 *   <li>Moving the team to a new DISK carries the current consensus document across first, so a
 *       member who has not re-signed yet can still read what they originally agreed to.</li>
 * </ul>
 */
public class UpdateTeamActivity extends BaseCryptoActivity {
    private static final String TAG = "UpdateTeamActivity";
    public static final String EXTRA_TEAM_ID = "extra_team_id";

    private TextInputEditText nameInput;
    private TextInputEditText consensusInput;
    private TextInputEditText descInput;
    private TextInputEditText dockInput;
    private TextInputEditText diskInput;
    private TextInputEditText callInput;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;
    private ImageButton chooseDockButton;
    private ImageButton chooseDiskButton;
    private ImageButton chooseCallButton;
    private ImageButton viewConsensusButton;

    private ActivityResultLauncher<Intent> chooseDockLauncher;
    private ActivityResultLauncher<Intent> chooseDiskLauncher;
    private ActivityResultLauncher<Intent> chooseCallLauncher;
    private ActivityResultLauncher<Intent> pickDocLauncher;
    private EntityFieldPickers pickers;

    private String teamId;
    private Team team;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_CONSENSUS = 1002;
    private static final int QR_SCAN_DESC = 1003;
    private static final int QR_SCAN_DOCK = 1004;
    private static final int QR_SCAN_DISK = 1005;
    private static final int QR_SCAN_CALL = 1006;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_update_team;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_team);
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
        TextIconsUtils.setupTextIcons(this, R.id.dockView, R.id.scanIcon, QR_SCAN_DOCK);
        TextIconsUtils.setupTextIcons(this, R.id.diskView, R.id.scanIcon, QR_SCAN_DISK);
        TextIconsUtils.setupTextIcons(this, R.id.callView, R.id.scanIcon, QR_SCAN_CALL);
        // The consensus field carries a data DID: offer the same local-data chooser create does.
        pickers.bindDidField(R.id.consensusView, R.id.scanIcon, QR_SCAN_CONSENSUS, consensusInput);

        loadTeamInfo();
    }

    @Override
    protected void initializeViews() {
        View nameView = findViewById(R.id.nameView);
        View consensusView = findViewById(R.id.consensusView);
        View descView = findViewById(R.id.descView);
        View dockView = findViewById(R.id.dockView);
        View diskView = findViewById(R.id.diskView);

        nameInput = nameView.findViewById(R.id.textInput);
        nameInput.setHint(R.string.team_name);

        consensusInput = consensusView.findViewById(R.id.textInput);
        consensusInput.setHint(R.string.consensus_doc_hint);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.team_description);

        dockInput = dockView.findViewById(R.id.textInput);
        dockInput.setHint(R.string.home_dock_hint);

        diskInput = diskView.findViewById(R.id.textInput);
        diskInput.setHint(R.string.server_setup_disk_hint);

        callInput = findViewById(R.id.callView).findViewById(R.id.textInput);
        callInput.setHint(R.string.home_call_hint);

        clearButton = findViewById(R.id.clearButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);
        chooseDockButton = findViewById(R.id.choose_dock_button);
        chooseDiskButton = findViewById(R.id.choose_disk_button);
        chooseCallButton = findViewById(R.id.choose_call_button);
        viewConsensusButton = findViewById(R.id.view_consensus_button);

        pickers = new EntityFieldPickers(this);

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

        chooseCallLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), callInput);
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

        teamId = getIntent().getStringExtra(EXTRA_TEAM_ID);
        if (teamId == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            finish();
        }
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            nameInput.setText("");
            consensusInput.setText("");
            descInput.setText("");
            dockInput.setText("");
            diskInput.setText("");
            callInput.setText("");
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            confirmThenPublish();
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

        chooseCallButton.setOnClickListener(v -> {
            hideKeyboard();
            chooseCallLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.CALL_NO1_NRC7, getString(R.string.server_setup_call_label)));
        });

        viewConsensusButton.setOnClickListener(v -> {
            hideKeyboard();
            viewConsensusDoc();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_NAME:
                nameInput.setText(qrContent);
                break;
            case QR_SCAN_CONSENSUS:
                consensusInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_DOCK:
                dockInput.setText(qrContent);
                break;
            case QR_SCAN_DISK:
                diskInput.setText(qrContent);
                break;
            case QR_SCAN_CALL:
                callInput.setText(qrContent);
                break;
        }
    }

    private void loadTeamInfo() {
        if (teamId == null) return;

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) return;

        team = setting.getImManager().getTeam(teamId);
        if (team == null) return;

        if (team.getStdName() != null) nameInput.setText(team.getStdName());
        if (team.getConsensusId() != null) consensusInput.setText(team.getConsensusId());
        if (team.getDesc() != null) descInput.setText(team.getDesc());

        Map<String, String> home = team.getHome();
        if (home != null) {
            String dockValue = home.get(Constants.DOCK_NO1_NRC7);
            if (dockValue != null) dockInput.setText(sidOrRaw(dockValue));
            String diskValue = home.get(Constants.DISK_NO1_NRC7);
            if (diskValue != null) diskInput.setText(sidOrRaw(diskValue));
            callInput.setText(com.fc.freer.call.CallHome.display(home));
        }
    }

    /**
     * Read the consensus document named in the field — which is what the owner is looking at and
     * about to publish, not necessarily what the team currently carries. Only an empty field
     * falls back to the team's stored consensus.
     */
    private void viewConsensusDoc() {
        String did = getText(consensusInput);
        if (did.isEmpty() && team != null && team.getConsensusId() != null) {
            did = team.getConsensusId();
        }
        if (did.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_consensus_doc));
            return;
        }
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        // Falling back to the DISKs: the team's own, and the one being typed into the form, since
        // while a move is being set up the document may be on either side of it.
        ConsensusDocViewer.fetchAndShow(this, HatManager.getInstance(this, liveKeyInfo.getId()),
                getString(R.string.consensus_document), did,
                Arrays.asList(ConsensusDocHelper.resolveTeamDiskSid(team),
                        HomeServiceResolver.extractSid(getText(diskInput))),
                0, null);
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

    /** Warn before a consensus change, which obliges every member to sign again. */
    private void confirmThenPublish() {
        String consensusId = getText(consensusInput);
        boolean consensusChanged = team != null && !consensusId.isEmpty()
                && !consensusId.equals(team.getConsensusId());
        if (!consensusChanged) {
            publishUpdate();
            return;
        }
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.consensus_document)
                .setMessage(R.string.consensus_change_warning)
                .setPositiveButton(R.string.publish, (d, w) -> publishUpdate())
                .setNegativeButton(R.string.cancel, null));
    }

    private void publishUpdate() {
        String name = getText(nameInput);
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
        if (diskVal.isEmpty()) {
            ToastUtils.makeText(this, R.string.team_servers_required);
            return;
        }
        // The DISK must resolve to a SID: it is where the consensus lives, and members find it
        // by resolving the plaintext SID published in the team home.
        final String diskSid = HomeServiceResolver.extractSid(diskVal);
        if (diskSid == null) {
            ToastUtils.makeText(this, R.string.team_disk_must_be_service);
            return;
        }

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

        final String desc = getText(descInput);
        final String oldConsensusId = team != null ? team.getConsensusId() : null;
        final String oldDiskSid = team != null ? ConsensusDocHelper.resolveTeamDiskSid(team) : null;
        final boolean consensusChanged = !consensusId.equals(oldConsensusId);
        final boolean diskChanged = !diskSid.equals(oldDiskSid);

        final Map<String, String> homeMap = buildHomeMap(dockVal, diskSid, getText(callInput));

        publishButton.setEnabled(false);
        final WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.publishing));
        waitingDialog.show();

        new Thread(() -> {
            HatManager hatManager = HatManager.getInstance(this, liveKeyInfo.getId());
            ConsensusDocHelper helper = new ConsensusDocHelper(this, hatManager);

            // Step 1: moving DISK carries the superseded consensus across, so a member who has
            // not re-signed can still read what they originally agreed to.
            //
            // Only worth warning about when that document was reachable to begin with. A team
            // whose home carries no DISK — the state this screen exists to repair — never had a
            // consensus any member could fetch, so failing to carry it forward loses nothing and
            // is not news. Where the team did have a DISK, failing to move the document is a real
            // regression for members mid-signature, so report it and let the owner decide rather
            // than silently degrading their team or blocking the repair.
            if (diskChanged && oldConsensusId != null && !oldConsensusId.isEmpty()) {
                runOnUiThread(() -> waitingDialog.setHint(getString(R.string.migrating_consensus_document)));
                boolean moved = helper.ensureDocOnDisk(this, oldConsensusId, oldDiskSid, diskSid, null);
                boolean wasReachable = oldDiskSid != null && !oldDiskSid.isEmpty();
                if (!moved && wasReachable
                        && !confirmOnUiThread(waitingDialog,
                                getString(R.string.failed_to_migrate_consensus_document),
                                helper.getLastError())) {
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        publishButton.setEnabled(true);
                    });
                    return;
                }
            }

            // Step 2: put the new consensus on the DISK being carved — the new one when the team
            // is also moving. The document need not be on this device: it may already be on the
            // target, or still on the DISK the team is leaving, and either is placed without
            // asking the owner for a file they already published. Only a document nobody can
            // find falls through to the picker, because an id that resolves to nothing is
            // exactly how a consensus ends up unreadable for every member.
            String finalConsensusId = consensusId;
            if (consensusChanged) {
                File docFile = ConsensusDocHelper.resolveLocalDoc(this, hatManager, consensusId);
                if (docFile == null) {
                    runOnUiThread(() -> waitingDialog.setHint(getString(R.string.migrating_consensus_document)));
                    if (!helper.ensureDocOnDisk(this, consensusId, oldDiskSid, diskSid, null)) {
                        runOnUiThread(() -> {
                            waitingDialog.dismiss();
                            publishButton.setEnabled(true);
                            promptImportDoc();
                        });
                        return;
                    }
                } else {
                    final long totalBytes = docFile.length();
                    runOnUiThread(() -> waitingDialog.setHint(getString(R.string.uploading_consensus_document)));
                    finalConsensusId = helper.uploadConsensusDoc(docFile, diskSid,
                            bytesUploaded -> runOnUiThread(() -> waitingDialog.setHint(
                                    getString(R.string.uploading_consensus_document) + " "
                                            + FileShareHelper.formatSize(bytesUploaded)
                                            + " / " + FileShareHelper.formatSize(totalBytes))));
                    if (finalConsensusId == null) {
                        failPublish(waitingDialog, getString(R.string.failed_to_upload_consensus_document),
                                helper.getLastError());
                        return;
                    }
                }
            }

            runOnUiThread(() -> waitingDialog.setHint(getString(R.string.publishing)));

            final String carvedConsensusId = finalConsensusId;
            TeamOpData opData = TeamOpData.makeUpdate(teamId, name, carvedConsensusId,
                    null, null, null, desc.isEmpty() ? null : desc);
            opData.setHome(homeMap);

            Feip feip = Feip.fromName("Team");
            feip.setData(opData);
            String feipJson = feip.toJson();

            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            savePending(carvedConsensusId, name, desc, homeMap);
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
                                ToastUtils.makeText(UpdateTeamActivity.this,
                                        getString(R.string.failed_to_update_team) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                publishButton.setEnabled(true);
                                txSender.showUnsignedTxAsQR(UpdateTeamActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                waitingDialog.dismiss();
                                publishButton.setEnabled(true);
                                txSender.showSignedTxAsQR(UpdateTeamActivity.this, signedTxHex);
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
     * Ask the user, from the publishing background thread, whether to carry on after a
     * non-fatal failure. Blocks that thread until they answer.
     *
     * @return true to continue publishing
     */
    private boolean confirmOnUiThread(WaitingDialog waitingDialog, String message, String detail) {
        if (isFinishing() || isDestroyed()) return false;

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] proceed = {false};
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }
            waitingDialog.dismiss();
            DialogUtils.show(new AlertDialog.Builder(this)
                    .setTitle(R.string.consensus_document)
                    .setMessage(message + (detail != null ? "\n\n" + detail : "")
                            + "\n\n" + getString(R.string.publish_anyway))
                    .setPositiveButton(R.string.publish, (d, w) -> {
                        proceed[0] = true;
                        waitingDialog.show();
                        latch.countDown();
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> latch.countDown())
                    .setOnCancelListener(d -> latch.countDown()));
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return proceed[0];
    }

    private void failPublish(WaitingDialog waitingDialog, String message, String detail) {
        runOnUiThread(() -> {
            waitingDialog.dismiss();
            publishButton.setEnabled(true);
            ToastUtils.makeText(this, message + (detail != null ? ": " + detail : ""));
        });
    }

    /**
     * Record the edited team locally so the change shows before the TX confirms. Mirrors the
     * pending team {@link CreateTeamActivity} saves on create.
     */
    private void savePending(String consensusId, String name, String desc, Map<String, String> homeMap) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null || team == null) return;
        TeamHandler teamHandler = setting.getImManager().getTeamHandler();
        if (teamHandler == null) return;

        team.setStdName(name);
        team.setConsensusId(consensusId);
        team.setDesc(desc.isEmpty() ? null : desc);
        team.setHome(homeMap);
        team.setLastTime(System.currentTimeMillis());
        teamHandler.savePendingTeam(team);
    }

    /**
     * Merge the edited DOCK/DISK/CALL over the team's stored home. The parser replaces {@code home}
     * wholesale, so anything omitted here would be erased on chain — including keys this screen
     * does not edit. That also lets an emptied CALL box remove the CALL: meetings then run on
     * each host's own (VOICE_SPEC §8).
     */
    private Map<String, String> buildHomeMap(String dockVal, String diskSid, String callVal) {
        Map<String, String> homeMap = new HashMap<>(
                com.fc.freer.call.CallHome.withCall(team != null ? team.getHome() : null, callVal));

        if (!dockVal.isEmpty()) {
            homeMap.put(Constants.DOCK_NO1_NRC7, toSidHomeValue(dockVal));
        }
        // Home values are plaintext (sid)<id> so any peer can resolve them without a key.
        homeMap.put(Constants.DISK_NO1_NRC7, "(sid)" + diskSid);
        return homeMap;
    }

    /** Normalize a DOCK/DISK input to a plaintext {@code (sid)<id>} home value when it is a SID. */
    private String toSidHomeValue(String input) {
        String sid = HomeServiceResolver.extractSid(input);
        return sid != null ? "(sid)" + sid : input;
    }

    /** Show the bare SID for a {@code (sid)<id>} home value, else the value as stored. */
    private String sidOrRaw(String homeValue) {
        String sid = HomeServiceResolver.extractSid(homeValue);
        return sid != null ? sid : homeValue;
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }
}
