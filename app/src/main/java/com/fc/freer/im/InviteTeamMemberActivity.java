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

    /**
     * Read the team from chain so membership is judged against what the indexer actually holds.
     * Falls back to the local copy, and to no filtering at all, rather than blocking the invite.
     */
    private Team loadTeamForFiltering() {
        try {
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                    .getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient != null) {
                Team fresh = fapiClient.entityById("team", Team.class, teamId);
                if (fresh != null) return fresh;
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Could not refresh team before inviting: %s", e.getMessage());
        }
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        ImManager imManager = setting != null ? setting.getImManager() : null;
        return imManager != null ? imManager.getTeam(teamId) : null;
    }

    /**
     * Drop FIDs that would make the invite a no-op.
     * <p>
     * The indexer already skips the owner and existing members when it applies an invite, so
     * carving them changes nothing on chain — but the transaction is still paid for, and the
     * "you've been invited" notification would still reach someone who is already in the team
     * and send them to a join screen with nothing to join. Filtering first keeps the fee and the
     * notification honest.
     * <p>
     * FIDs already sitting in {@code invitees} are dropped from the transaction too — re-adding
     * them is a set operation that changes nothing — but they are still worth notifying, since a
     * resend is the only way to nudge an invitation that was missed.
     *
     * @param fidList  what the user picked
     * @param members  filled with the FIDs skipped for already being in the team
     * @param notify   filled with the FIDs worth notifying, invited now or pending from before
     * @return the FIDs that actually need to go on chain
     */
    private List<String> filterInvitees(List<String> fidList, Team team,
                                        List<String> members, List<String> notify) {
        List<String> toInvite = new java.util.ArrayList<>();
        if (team == null) {
            // Membership unknown: invite exactly what was picked rather than guess.
            toInvite.addAll(fidList);
            notify.addAll(fidList);
            return toInvite;
        }

        java.util.Set<String> memberSet = new java.util.HashSet<>();
        if (team.getMembers() != null) memberSet.addAll(team.getMembers());
        if (team.getOwner() != null) memberSet.add(team.getOwner());
        java.util.Set<String> inviteeSet = new java.util.HashSet<>();
        if (team.getInvitees() != null) inviteeSet.addAll(team.getInvitees());

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String fid : fidList) {
            if (fid == null || fid.isEmpty() || !seen.add(fid)) continue;
            if (memberSet.contains(fid)) {
                members.add(fid);
            } else if (inviteeSet.contains(fid)) {
                notify.add(fid);
            } else {
                toInvite.add(fid);
                notify.add(fid);
            }
        }
        return toInvite;
    }

    private void inviteMembers(List<String> picked) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            finish();
            return;
        }
        new Thread(() -> {
            Team team = loadTeamForFiltering();
            List<String> alreadyMembers = new java.util.ArrayList<>();
            List<String> notify = new java.util.ArrayList<>();
            List<String> toInvite = filterInvitees(picked, team, alreadyMembers, notify);
            runOnUiThread(() -> onInviteesFiltered(liveKeyInfo, toInvite, alreadyMembers, notify));
        }).start();
    }

    private void onInviteesFiltered(KeyInfo liveKeyInfo, List<String> toInvite,
                                    List<String> alreadyMembers, List<String> notify) {
        if (!alreadyMembers.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.invite_skipped_members, alreadyMembers.size()));
        }

        if (toInvite.isEmpty()) {
            // Nothing new to carve. Anyone still pending is re-notified for free.
            if (!notify.isEmpty()) {
                sendTeamInviteNotifications(notify);
                ToastUtils.makeText(this, getString(R.string.invite_notification_resent));
            } else if (alreadyMembers.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.invite_nobody_to_invite));
            }
            finish();
            return;
        }

        carveInvite(liveKeyInfo, toInvite, notify);
    }

    private void carveInvite(KeyInfo liveKeyInfo, List<String> fidList, List<String> notify) {
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
                            sendTeamInviteNotifications(notify);
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
