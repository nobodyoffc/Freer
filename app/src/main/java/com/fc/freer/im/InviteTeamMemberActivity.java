package com.fc.freer.im;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.List;

/**
 * Thin controller for inviting members to a team. FID collection is delegated
 * entirely to {@link SearchFidsOnChainActivity} (search on chain, contacts, and
 * the global FID list all live there); this activity only turns the returned
 * FIDs into an on-chain Team invite transaction and sends the notifications.
 */
public class InviteTeamMemberActivity extends BaseCryptoActivity {
    public static final String EXTRA_TEAM_ID = "extra_team_id";
    private static final String TAG = "InviteTeamMember";

    private String teamId;
    private ActivityResultLauncher<Intent> pickFidsLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_invite_member;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.invite_members);
    }

    @Override
    protected void initializeViews() {
        teamId = getIntent().getStringExtra(EXTRA_TEAM_ID);

        pickFidsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    java.util.ArrayList<String> fids = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        fids = result.getData().getStringArrayListExtra(
                                SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                    }
                    if (fids != null && !fids.isEmpty()) {
                        inviteMembers(fids);
                    } else {
                        // Nothing chosen or the picker was cancelled: nothing to invite.
                        finish();
                    }
                });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isSessionRedirected()) return;

        if (teamId == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            finish();
            return;
        }

        // Only open the picker on a fresh start, not on configuration-change recreation.
        if (savedInstanceState == null) {
            launchFidPicker();
        }
    }

    @Override
    protected void setupButtons() {
        // No buttons of our own; the picker provides all input controls.
    }

    @Override
    protected void setupBackButton() {
        // Navigation is handled by the picker; nothing to wire here.
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void launchFidPicker() {
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_TITLE, getString(R.string.invite_members));
        pickFidsLauncher.launch(intent);
    }

    // Called from background thread (inside TxSender callback).
    // The consensus document is published at team creation (uploaded to the team's DISK and
    // committed on-chain via Team.consensusId + Team.home.DISK), so an invitee discovers and
    // downloads it entirely from the confirmed on-chain team. We therefore only send a
    // lightweight "you've been invited" notification here — no off-chain HAT, which would
    // otherwise race ahead of the invite TX's on-chain confirmation.
    private void sendTeamInviteNotifications(List<String> fids) {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;
            ImManager imManager = setting.getImManager();
            if (imManager == null) return;

            String teamName = null;
            if (imManager.getTeamHandler() != null) {
                Team team = imManager.getTeam(teamId);
                if (team != null) teamName = team.getStdName();
            }
            String displayName = teamName != null ? teamName : teamId;

            for (String fid : fids) {
                imManager.sendTeamInviteNotification(fid, teamId, displayName);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to send invite notifications: %s", e.getMessage());
        }
    }

    private void inviteMembers(List<String> fidList) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            finish();
            return;
        }

        TeamOpData opData = TeamOpData.makeInvite(teamId, fidList.toArray(new String[0]));
        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            finish();
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
                            sendTeamInviteNotifications(fidList);
                            runOnUiThread(() -> finish());
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(InviteTeamMemberActivity.this,
                                        getString(R.string.failed_to_send_invitation) + ": " + errorMessage);
                                finish();
                            });
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {
                            // Signing continues in a separate activity (e.g. multisig); leave
                            // this activity in place so the flow can return to it.
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            // Broadcast continues elsewhere; nothing to do here.
                        }

                        @Override
                        public void onCancelled() {
                            runOnUiThread(() -> finish());
                        }
                    });
        }).start();
    }
}
