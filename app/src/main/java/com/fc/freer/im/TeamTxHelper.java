package com.fc.freer.im;

import android.app.Activity;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.handler.TeamHandler;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

/**
 * Shared helpers for sending on-chain team join / take-over transactions.
 * Used by PendingIssueActivity, PendingIssueDetailActivity, and TeamActivity so the
 * TX-sending logic is not duplicated across the three accept-invitation paths.
 */
class TeamTxHelper {

    private static final String TAG = "TeamTxHelper";

    interface TxResultCallback {
        void onSuccess();
        void onError(String message);
    }

    static void sendJoinTx(Activity activity, String teamId, ImManager imManager,
                            TxResultCallback callback) {
        sendTeamTx(activity, teamId, imManager, false, callback);
    }

    static void sendTakeOverTx(Activity activity, String teamId, ImManager imManager,
                                TxResultCallback callback) {
        sendTeamTx(activity, teamId, imManager, true, callback);
    }

    private static void sendTeamTx(Activity activity, String teamId, ImManager imManager,
                                    boolean isTakeOver, TxResultCallback callback) {
        if (teamId == null) {
            if (callback != null)
                callback.onError(activity.getString(R.string.failed));
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(activity, activity.getString(R.string.no_active_fid));
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(activity, activity.getString(R.string.failed));
            return;
        }

        new Thread(() -> {
            String consensusId = resolveConsensusId(teamId, imManager);

            TeamOpData opData = isTakeOver
                    ? TeamOpData.makeTakeOver(teamId, consensusId)
                    : TeamOpData.makeJoin(teamId, consensusId);
            Feip feip = Feip.fromName("Team");
            feip.setData(opData);
            String feipJson = feip.toJson();

            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(activity, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            activity.runOnUiThread(() -> {
                                if (callback != null) callback.onSuccess();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            activity.runOnUiThread(() -> {
                                if (callback != null) callback.onError(errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private static String resolveConsensusId(String teamId, ImManager imManager) {
        if (imManager != null) {
            TeamHandler teamHandler = imManager.getTeamHandler();
            if (teamHandler != null) {
                Team local = teamHandler.getTeam(teamId);
                if (local != null && local.getConsensusId() != null) {
                    return local.getConsensusId();
                }
            }
        }
        try {
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                    .getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient != null) {
                Team team = fapiClient.entityById("team", Team.class, teamId);
                if (team != null) return team.getConsensusId();
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to fetch team for consensusId: %s", e.getMessage());
        }
        return null;
    }
}
