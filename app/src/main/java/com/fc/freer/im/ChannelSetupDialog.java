package com.fc.freer.im;

import android.app.Activity;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;

import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;

/**
 * Prompts the user to set up their messaging (DOCK) and data (DISK) servers via the
 * combined {@link com.fc.freer.data.ServerSetupActivity}. DOCK is the only supported
 * messaging channel because phones rarely have a fixed reachable IP (which FUDP requires).
 * If the user dismisses the prompt, ImManager.setChannelSetupDismissed(true) is called so
 * the IM UI stays disabled until home.DOCK is registered on-chain.
 */
public class ChannelSetupDialog {

    public static void show(Activity activity, String suggestedDockUrl) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.server_setup_title)
                .setMessage(R.string.server_setup_message)
                .setCancelable(false)
                .setPositiveButton(R.string.server_setup_action, (d, w) -> {
                    markDismissed(false);
                    activity.startActivity(new Intent(activity, com.fc.freer.data.ServerSetupActivity.class));
                })
                .setNegativeButton(R.string.im_setup_cancel, (d, w) -> {
                    markDismissed(true);
                    d.dismiss();
                })
                .create();

        dialog.show();
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
