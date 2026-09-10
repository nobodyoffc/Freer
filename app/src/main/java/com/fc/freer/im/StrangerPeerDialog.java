package com.fc.freer.im;

import com.fc.freer.utils.DialogUtils;

import android.app.Activity;
import android.content.Intent;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.freer.R;

/**
 * Shows a timed confirmation dialog when a stranger peer connects.
 * Auto-dismisses after timeout, leaving the issue in PENDING state.
 */
public class StrangerPeerDialog {

    private static final long TIMEOUT_MS = 30_000;
    private static final long TICK_MS = 1_000;

    public interface DialogCallback {
        void onAccepted(String peerFid);
        void onRejected(String peerFid);
        void onDismissed(String peerFid);
        default void onBlacklisted(String peerFid) {}
        default void onBlockAllStrangers(String peerFid) {}
    }

    /**
     * Show the dialog with a full PendingIssue, displaying message preview when available.
     */
    public static void show(Activity activity, PendingIssue issue, DialogCallback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (issue == null) return;

        String peerFid = issue.getPeerFid();

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_stranger_peer, null);

        TextView titleText = dialogView.findViewById(R.id.stranger_title);
        TextView messageText = dialogView.findViewById(R.id.stranger_message);
        TextView previewText = dialogView.findViewById(R.id.message_preview_text);
        TextView countdownText = dialogView.findViewById(R.id.countdown_text);
        Button acceptBtn = dialogView.findViewById(R.id.btn_accept);
        Button rejectBtn = dialogView.findViewById(R.id.btn_reject);
        Button laterBtn = dialogView.findViewById(R.id.btn_later);
        Button viewAllBtn = dialogView.findViewById(R.id.btn_view_all);
        TextView linkBlacklist = dialogView.findViewById(R.id.link_add_to_blacklist);
        TextView linkBlockAll = dialogView.findViewById(R.id.link_block_all_strangers);

        titleText.setText(R.string.stranger_peer_title);

        String alias = peerFid;
        if (peerFid != null && peerFid.length() > 8) {
            alias = StringUtils.omitMiddle(peerFid,13) ;
        }

        PendingIssue.StrangerPeerData data = issue.getDataAs(PendingIssue.StrangerPeerData.class);
        if (data != null && data.firstMessageContent != null && !data.firstMessageContent.isEmpty()) {
            messageText.setText(activity.getString(R.string.stranger_peer_message_with_preview,
                    alias, data.firstMessageContent));
            previewText.setVisibility(View.GONE);
        } else {
            messageText.setText(activity.getString(R.string.stranger_peer_message, alias));
            previewText.setVisibility(View.GONE);
        }

        viewAllBtn.setVisibility(View.VISIBLE);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        CountDownTimer timer = new CountDownTimer(TIMEOUT_MS, TICK_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) (millisUntilFinished / 1000);
                countdownText.setText(activity.getString(R.string.auto_dismiss_countdown, seconds));
            }

            @Override
            public void onFinish() {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                    if (callback != null) callback.onDismissed(peerFid);
                }
            }
        };

        acceptBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onAccepted(peerFid);
        });

        rejectBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onRejected(peerFid);
        });

        laterBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onDismissed(peerFid);
        });

        viewAllBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onDismissed(peerFid);
            activity.startActivity(new Intent(activity, MessageRequestsActivity.class));
        });

        linkBlacklist.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onBlacklisted(peerFid);
        });

        linkBlockAll.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onBlockAllStrangers(peerFid);
        });

        DialogUtils.show(dialog);
        timer.start();
    }

    /**
     * Backward-compatible overload: creates a minimal PendingIssue from peerFid.
     */
    public static void show(Activity activity, String peerFid, DialogCallback callback) {
        PendingIssue issue = PendingIssue.createStrangerPeer(peerFid);
        show(activity, issue, callback);
    }
}
