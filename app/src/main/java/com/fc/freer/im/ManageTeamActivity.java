package com.fc.freer.im;

import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.handler.TeamHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.Collections;

public class ManageTeamActivity extends BaseCryptoActivity {
    private static final String TAG = "ManageTeamActivity";
    public static final String EXTRA_TEAM_ID = "extra_team_id";

    private String teamId;
    private ImageButton backButton;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_manage_team;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.manage_team);
    }

    @Override
    protected void initializeViews() {
        teamId = getIntent().getStringExtra(EXTRA_TEAM_ID);
        if (teamId == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            finish();
            return;
        }
        backButton = findViewById(R.id.back_button);
    }

    @Override
    protected void setupButtons() {
        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });

        findViewById(R.id.btn_appoint_manager).setOnClickListener(v -> {
            hideKeyboard();
            showFidInputDialog(getString(R.string.appoint_manager), this::appointManagers);
        });

        findViewById(R.id.btn_cancel_appointment).setOnClickListener(v -> {
            hideKeyboard();
            showFidInputDialog(getString(R.string.cancel_appointment), this::cancelAppointment);
        });

        findViewById(R.id.btn_transfer_team).setOnClickListener(v -> {
            hideKeyboard();
            showTransferDialog();
        });

        findViewById(R.id.btn_create_symkey).setOnClickListener(v -> {
            hideKeyboard();
            createSymkey();
        });

        findViewById(R.id.btn_disband_team).setOnClickListener(v -> {
            hideKeyboard();
            showDisbandConfirmation();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void showFidInputDialog(String title, FidListCallback callback) {
        EditText input = new EditText(this);
        input.setHint(R.string.enter_fids_one_per_line);
        input.setInputType(android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP);
        input.setPadding(32, 24, 32, 24);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        ToastUtils.makeText(this, R.string.fids_required);
                        return;
                    }
                    String[] lines = text.split("\\n");
                    java.util.List<String> fids = new java.util.ArrayList<>();
                    for (String line : lines) {
                        String fid = line.trim();
                        if (!fid.isEmpty()) fids.add(fid);
                    }
                    if (!fids.isEmpty()) {
                        callback.onFids(fids.toArray(new String[0]));
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                .show();
    }

    private void showTransferDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.transferee_fid);
        input.setPadding(32, 24, 32, 24);

        new AlertDialog.Builder(this)
                .setTitle(R.string.transfer_team)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    String transferee = input.getText().toString().trim();
                    if (transferee.isEmpty()) {
                        ToastUtils.makeText(this, R.string.transferee_fid_required);
                        return;
                    }
                    transferTeam(transferee);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                .show();
    }

    private void showDisbandConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.disband_team)
                .setMessage(R.string.confirm_disband_team)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    disbandTeam();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                .show();
    }

    private void appointManagers(String[] fids) {
        broadcastTeamOp(TeamOpData.makeAppoint(teamId, fids),
                R.string.managers_appointed_successfully, R.string.failed_to_appoint_managers);
    }

    private void cancelAppointment(String[] fids) {
        broadcastTeamOp(TeamOpData.makeCancelAppointment(teamId, fids),
                R.string.appointment_cancelled_successfully, R.string.failed_to_cancel_appointment);
    }

    private void transferTeam(String transferee) {
        broadcastTeamOpWithNotification(TeamOpData.makeTransfer(teamId, transferee),
                R.string.team_transferred_successfully, R.string.failed_to_transfer_team,
                transferee);
    }

    private void broadcastTeamOpWithNotification(TeamOpData opData, int successMsgId, int failMsgId,
                                                  String targetFid) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            sendTeamTransferNotification(targetFid, txId);
                            runOnUiThread(() -> {
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(ManageTeamActivity.this,
                                    getString(failMsgId) + ": " + errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void sendTeamTransferNotification(String targetFid, String txId) {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;
            ImManager imManager = setting.getImManager();
            if (imManager == null) return;

            String teamName = null;
            TeamHandler teamHandler = imManager.getTeamHandler();
            if (teamHandler != null) {
                Team team = teamHandler.getTeam(teamId);
                if (team != null) teamName = team.getStdName();
            }

            imManager.sendTeamTransferNotification(targetFid, teamId, teamName);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to send transfer notification: %s", e.getMessage());
        }
    }

    private void disbandTeam() {
        broadcastTeamOp(TeamOpData.makeDisband(Collections.singletonList(teamId)),
                R.string.team_disbanded_successfully, R.string.failed_to_disband_team);
    }

    private void createSymkey() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        TeamHandler teamHandler = setting.getImManager().getTeamHandler();
        if (teamHandler == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        boolean success = teamHandler.createSymkey(teamId);
        if (success) {
            ToastUtils.makeText(this, getString(R.string.symkey_created_successfully));
        } else {
            ToastUtils.makeText(this, getString(R.string.failed_to_create_symkey));
        }
    }

    private void broadcastTeamOp(TeamOpData opData, int successMsgId, int failMsgId) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(ManageTeamActivity.this,
                                    getString(failMsgId) + ": " + errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    interface FidListCallback {
        void onFids(String[] fids);
    }
}
