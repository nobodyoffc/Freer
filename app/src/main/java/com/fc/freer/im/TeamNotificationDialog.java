package com.fc.freer.im;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

/**
 * Shows a timed dialog when a team invite or transfer notification is received.
 * User can accept, reject, or defer (decide later / auto-dismiss).
 */
public class TeamNotificationDialog {

    private static final long TIMEOUT_MS = 30_000;
    private static final long TICK_MS = 1_000;

    public interface DialogCallback {
        void onAccepted(PendingIssue issue);
        void onRejected(PendingIssue issue);
        void onDeferred(PendingIssue issue);
    }

    public static void show(Activity activity, PendingIssue issue, DialogCallback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (issue == null) return;

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_team_notification, null);

        ImageView avatarView = dialogView.findViewById(R.id.peer_avatar);
        TextView titleText = dialogView.findViewById(R.id.notification_title);
        TextView messageText = dialogView.findViewById(R.id.notification_message);
        TextView detailText = dialogView.findViewById(R.id.notification_detail);
        TextView countdownText = dialogView.findViewById(R.id.countdown_text);
        Button acceptBtn = dialogView.findViewById(R.id.btn_accept);
        Button rejectBtn = dialogView.findViewById(R.id.btn_reject);
        Button laterBtn = dialogView.findViewById(R.id.btn_later);
        Button viewDetailBtn = dialogView.findViewById(R.id.btn_view_detail);
        Button viewPendingBtn = dialogView.findViewById(R.id.btn_view_pending);

        loadAvatar(activity, avatarView, issue.getPeerFid());

        PendingIssue.TeamNotificationData data =
                issue.getDataAs(PendingIssue.TeamNotificationData.class);

        boolean isTransfer = issue.getIssueType() == PendingIssue.IssueType.TEAM_TRANSFER;

        titleText.setText(isTransfer
                ? R.string.team_transfer_notification_title
                : R.string.team_invite_notification_title);

        String senderAlias = issue.getPeerFid();
        if (senderAlias != null && senderAlias.length() > 8) {
            senderAlias = senderAlias.substring(0, 8) + "...";
        }

        String teamName = (data != null && data.teamName != null) ? data.teamName : "";
        String teamId = (data != null && data.teamId != null) ? data.teamId : "";

        if (isTransfer) {
            messageText.setText(activity.getString(R.string.team_transfer_notification_message,
                    senderAlias, teamName.isEmpty() ? teamId : teamName));
        } else {
            messageText.setText(activity.getString(R.string.team_invite_notification_message,
                    senderAlias, teamName.isEmpty() ? teamId : teamName));
        }

        if (!teamId.isEmpty()) {
            detailText.setText(activity.getString(R.string.team_notification_detail, teamId));
            detailText.setVisibility(View.VISIBLE);
        } else {
            detailText.setVisibility(View.GONE);
        }

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
                    if (callback != null) callback.onDeferred(issue);
                }
            }
        };

        acceptBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onAccepted(issue);
        });

        rejectBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onRejected(issue);
        });

        laterBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onDeferred(issue);
        });

        viewDetailBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onDeferred(issue);
            PendingIssueDetailActivity.start(activity, issue);
        });

        viewPendingBtn.setOnClickListener(v -> {
            timer.cancel();
            dialog.dismiss();
            if (callback != null) callback.onDeferred(issue);
            activity.startActivity(new Intent(activity, PendingIssueActivity.class));
        });

        dialog.show();
        timer.start();
    }

    private static void loadAvatar(Activity activity, ImageView avatarView, String fid) {
        if (fid == null || fid.isEmpty() || avatarView == null) return;
        new Thread(() -> {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(activity);
                Bitmap bitmap = avatarManager.getAvatarBitmap(fid);
                if (bitmap != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(() -> avatarView.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                TimberLogger.e("TeamNotificationDialog", "Error loading avatar: %s", e.getMessage());
            }
        }).start();
    }
}
