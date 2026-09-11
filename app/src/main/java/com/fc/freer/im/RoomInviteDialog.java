package com.fc.freer.im;

import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.utils.DialogUtils;

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
 * Shows a timed dialog when a room invitation is received.
 * User can accept, reject, or defer (decide later / auto-dismiss).
 */
public class RoomInviteDialog {

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

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_room_invite, null);

        ImageView avatarView = dialogView.findViewById(R.id.peer_avatar);
        TextView titleText = dialogView.findViewById(R.id.notification_title);
        TextView messageText = dialogView.findViewById(R.id.notification_message);
        TextView detailText = dialogView.findViewById(R.id.notification_detail);
        TextView countdownText = dialogView.findViewById(R.id.countdown_text);
        Button acceptBtn = dialogView.findViewById(R.id.btn_accept);
        Button rejectBtn = dialogView.findViewById(R.id.btn_reject);
        Button laterBtn = dialogView.findViewById(R.id.btn_later);
        TextView viewDetailBtn = dialogView.findViewById(R.id.btn_view_detail);
        TextView viewPendingBtn = dialogView.findViewById(R.id.btn_view_pending);

        loadAvatar(activity, avatarView, issue.getPeerFid());

        PendingIssue.RoomInviteData data =
                issue.getDataAs(PendingIssue.RoomInviteData.class);

        titleText.setText(R.string.room_invite_notification_title);

        String senderAlias = issue.getPeerFid();
        if (senderAlias != null && senderAlias.length() > 8) {
            senderAlias = senderAlias.substring(0, 8) + "...";
        }

        String roomName = (data != null && data.roomName != null) ? data.roomName : "";
        String roomId = (data != null && data.roomId != null) ? data.roomId : "";

        String message = activity.getString(R.string.room_invite_notification_message,
                senderAlias, roomName.isEmpty() ? roomId : roomName);
        String peerFid = issue.getPeerFid();
        NobodyUi.setName(messageText, peerFid, message);
        // Anyone can invite in a nobody's name
        NobodyUi.bindBannerResolving(dialogView.findViewById(R.id.nobodyBanner), peerFid,
                R.string.nobody_inviter_warning, () -> {
                    NobodyUi.setName(messageText, peerFid, message);
                    loadAvatar(activity, avatarView, peerFid);
                });

        if (!roomId.isEmpty()) {
            detailText.setText(activity.getString(R.string.room_invite_notification_detail, roomId));
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

        DialogUtils.show(dialog);
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
                TimberLogger.e("RoomInviteDialog", "Error loading avatar: %s", e.getMessage());
            }
        }).start();
    }
}
