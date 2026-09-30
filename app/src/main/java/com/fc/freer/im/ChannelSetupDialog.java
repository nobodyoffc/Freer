package com.fc.freer.im;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.R;
import com.fc.freer.data.ServerSetupManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

/**
 * Lets the user set up their messaging (DOCK) and data (DISK) servers. Opened from the
 * getting-started checklist, which replaced the automatic prompt. DOCK is the only
 * supported messaging channel because phones rarely have a fixed reachable IP (which FUDP
 * requires). Offers three choices:
 * <ul>
 *   <li><b>Use this server</b> — one tap: register the current FAPI server as DOCK (and DISK
 *       if it advertises a disk component) directly, without opening the setup screen.</li>
 *   <li><b>Advanced</b> — opens {@link com.fc.freer.data.ServerSetupActivity} for manual entry.</li>
 *   <li><b>Not now</b> — dismisses for this session ({@link ImManager#setChannelSetupDismissed}).</li>
 * </ul>
 * The one-tap path and the Advanced path share {@link ServerSetupManager}, so both broadcast the
 * same TX, merged over the home map the chain holds now, and record the same pending carve.
 */
public class ChannelSetupDialog {

    public static void show(Activity activity, String suggestedDockUrl) {
        show(activity, suggestedDockUrl, null);
    }

    /**
     * @param onBroadcast run on the UI thread once the one-tap registration has been broadcast,
     *                    so the caller can show it as waiting for the chain; may be null
     */
    public static void show(Activity activity, String suggestedDockUrl, Runnable onBroadcast) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        // Resolving the base server's SID / disk capability may touch the network, so do it off
        // the main thread, then build the dialog on the UI thread with the results.
        new Thread(() -> {
            ApiCenter api = ApiCenter.getInstance();
            final String baseSid = api.getBaseServiceSid();
            final boolean baseHasDisk = api.baseHasDiskComponent();
            activity.runOnUiThread(() -> showDialog(activity, baseSid, baseHasDisk, onBroadcast));
        }).start();
    }

    private static void showDialog(Activity activity, String baseSid, boolean baseHasDisk,
                                   Runnable onBroadcast) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_channel_setup, null);
        TextView message = view.findViewById(R.id.channel_setup_message);
        Button useServer = view.findViewById(R.id.btn_use_server);
        Button advanced = view.findViewById(R.id.btn_advanced);
        Button notNow = view.findViewById(R.id.btn_not_now);

        final boolean canUseBase = baseSid != null && !baseSid.isEmpty();
        if (canUseBase) {
            String suggest = activity.getString(
                    baseHasDisk ? R.string.server_setup_suggest_both : R.string.server_setup_suggest_dock_only);
            message.setText(suggest + "\n\n" + activity.getString(R.string.server_setup_suggest_sid, baseSid));
        } else {
            // No resolvable base server — the one-tap path can't run; steer to manual setup.
            message.setText(R.string.server_setup_message);
            useServer.setVisibility(View.GONE);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .create();

        useServer.setOnClickListener(v -> {
            dialog.dismiss();
            acceptDefault(activity, baseSid, baseHasDisk, onBroadcast);
        });
        advanced.setOnClickListener(v -> {
            dialog.dismiss();
            openAdvanced(activity);
        });
        notNow.setOnClickListener(v -> {
            markDismissed(true);
            dialog.dismiss();
        });

        DialogUtils.show(dialog);
    }

    /** One-tap: register the current server as DOCK (and DISK if available) directly. */
    private static void acceptDefault(Activity activity, String baseSid, boolean baseHasDisk,
                                      Runnable onBroadcast) {
        KeyInfo liveKeyInfo = FidManager.getInstance() != null
                ? FidManager.getInstance().getLiveKeyInfo() : null;
        if (liveKeyInfo == null) {
            ToastUtils.makeText(activity, R.string.no_active_fid);
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            // Can't sign silently (locked / watch-only) — fall back to the manual screen, which
            // handles unsigned/unbroadcasted flows via QR.
            openAdvanced(activity);
            return;
        }

        final String dockVal = baseSid;
        final String diskSid = baseHasDisk ? baseSid : "";
        ToastUtils.makeText(activity, R.string.publishing);
        final TxSender txSender = new TxSender();
        ServerSetupManager.register(activity, liveKeyInfo, dockVal, diskSid, null, prikey,
                new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        // TxSender already toasts "sent"; the shared helper recorded the pending carve.
                        if (onBroadcast != null) activity.runOnUiThread(onBroadcast);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        activity.runOnUiThread(() -> ToastUtils.makeText(activity,
                                activity.getString(R.string.server_setup_failed) + ": " + errorMessage));
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        activity.runOnUiThread(() -> {
                            ToastUtils.makeText(activity, R.string.cannot_sign_transaction);
                            txSender.showUnsignedTxAsQR(activity, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        activity.runOnUiThread(() -> txSender.showSignedTxAsQR(activity, signedTxHex));
                    }
                });
    }

    private static void openAdvanced(Activity activity) {
        markDismissed(false);
        activity.startActivity(new Intent(activity, com.fc.freer.data.ServerSetupActivity.class));
    }

    private static void markDismissed(boolean dismissed) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return;
        ImManager im = setting.getImManager();
        if (im != null) {
            im.setChannelSetupDismissed(dismissed);
        }
    }
}
