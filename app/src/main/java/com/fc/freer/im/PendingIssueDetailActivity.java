package com.fc.freer.im;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PendingIssueDetailActivity extends BaseCryptoActivity {
    private static final String TAG = "PendingIssueDetailAct";
    public static final String EXTRA_ISSUE_JSON = "extra_issue_json";

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private PendingIssue issue;
    private PendingIssueManager pendingIssueManager;

    public static void start(Context context, PendingIssue issue) {
        if (context == null || issue == null) return;
        Intent intent = new Intent(context, PendingIssueDetailActivity.class);
        intent.putExtra(EXTRA_ISSUE_JSON, issue.toJson());
        context.startActivity(intent);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_pending_issue_detail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.pending_issue_detail);
    }

    @Override
    protected void initializeViews() {
        String json = getIntent().getStringExtra(EXTRA_ISSUE_JSON);
        if (json == null || json.isEmpty()) {
            ToastUtils.showWarning(this, "No issue data");
            finish();
            return;
        }
        issue = PendingIssue.fromJson(json);
        if (issue == null) {
            ToastUtils.showWarning(this, "Failed to parse issue data");
            finish();
            return;
        }

        initManager();
        populateHeader();
        populateFields();
        populateExtraData();
        setupActionButtons();
    }

    @Override
    protected void setupButtons() {
        ImageButton backBtn = findViewById(R.id.back_button);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void initManager() {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;
            ImManager imManager = setting.getImManager();
            if (imManager != null) {
                pendingIssueManager = imManager.getPendingIssueManager();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to init manager: %s", e.getMessage());
        }
    }

    private void populateHeader() {
        ImageView avatarView = findViewById(R.id.detail_avatar);
        TextView typeLabelText = findViewById(R.id.detail_type_label);
        TextView statusText = findViewById(R.id.detail_status);

        String typeLabel = getTypeLabel(issue.getIssueType());
        typeLabelText.setText(typeLabel);

        if (issue.getStatus() != null) {
            statusText.setText(issue.getStatus().name());
        }

        loadAvatar(avatarView, issue.getPeerFid());
    }

    private void populateFields() {
        setText(R.id.detail_peer_fid, issue.getPeerFid());

        if (issue.getCreatedAt() != null) {
            setText(R.id.detail_created_at, TIME_FORMAT.format(new Date(issue.getCreatedAt())));
        }

        if (issue.getResolvedAt() != null) {
            findViewById(R.id.label_resolved_at).setVisibility(View.VISIBLE);
            TextView resolvedAtText = findViewById(R.id.detail_resolved_at);
            resolvedAtText.setVisibility(View.VISIBLE);
            resolvedAtText.setText(TIME_FORMAT.format(new Date(issue.getResolvedAt())));
        }

        if (issue.getNote() != null && !issue.getNote().isEmpty()) {
            findViewById(R.id.label_note).setVisibility(View.VISIBLE);
            TextView noteText = findViewById(R.id.detail_note);
            noteText.setVisibility(View.VISIBLE);
            noteText.setText(issue.getNote());
        }
    }

    private void populateExtraData() {
        LinearLayout container = findViewById(R.id.extra_data_container);
        TextView label = findViewById(R.id.label_extra_data);

        if (issue.getIssueType() == null) return;

        switch (issue.getIssueType()) {
            case STRANGER_PEER:
                populateStrangerPeerData(container, label);
                break;
            case TEAM_INVITE:
            case TEAM_TRANSFER:
                populateTeamData(container, label);
                break;
            case ROOM_INVITE:
                populateRoomData(container, label);
                break;
            default:
                break;
        }
    }

    private void populateStrangerPeerData(LinearLayout container, TextView label) {
        PendingIssue.StrangerPeerData data =
                issue.getDataAs(PendingIssue.StrangerPeerData.class);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        if (issue.getMessageCount() > 0) {
            addDataRow(container, getString(R.string.message_count),
                    String.valueOf(issue.getMessageCount()));
        }
        if (data.firstMessageContent != null) {
            addDataRow(container, getString(R.string.first_message), data.firstMessageContent);
        }
        if (data.lastMessageContent != null) {
            addDataRow(container, getString(R.string.last_message), data.lastMessageContent);
        }
        if (data.peerHost != null) {
            addDataRow(container, getString(R.string.peer_host), data.peerHost);
        }
        if (data.peerPort > 0) {
            addDataRow(container, getString(R.string.peer_port), String.valueOf(data.peerPort));
        }
    }

    private void populateTeamData(LinearLayout container, TextView label) {
        PendingIssue.TeamNotificationData data =
                issue.getDataAs(PendingIssue.TeamNotificationData.class);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        if (data.teamName != null) {
            addDataRow(container, getString(R.string.team_name), data.teamName);
        }
        if (data.teamId != null) {
            addDataRow(container, getString(R.string.team_id), data.teamId);
        }
        if (data.senderFid != null) {
            addDataRow(container, getString(R.string.sender), data.senderFid);
        }
        if (data.txId != null) {
            addDataRow(container, getString(R.string.tx_id), data.txId);
        }
    }

    private void populateRoomData(LinearLayout container, TextView label) {
        PendingIssue.RoomInviteData data =
                issue.getDataAs(PendingIssue.RoomInviteData.class);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        if (data.roomName != null) {
            addDataRow(container, getString(R.string.room_name), data.roomName);
        }
        if (data.roomId != null) {
            addDataRow(container, getString(R.string.room_id), data.roomId);
        }
        if (data.ownerFid != null) {
            addDataRow(container, getString(R.string.owner), data.ownerFid);
        }
    }

    private void addDataRow(LinearLayout container, String labelStr, String value) {
        TextView labelView = new TextView(this);
        labelView.setText(labelStr);
        labelView.setTextSize(13);
        labelView.setTextColor(getResources().getColor(R.color.hint, getTheme()));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(15);
        valueView.setTextColor(getResources().getColor(R.color.text, getTheme()));
        valueView.setTextIsSelectable(true);
        valueView.setPadding(0, 2, 0, 12);

        container.addView(labelView);
        container.addView(valueView);
    }

    private void setupActionButtons() {
        LinearLayout actionContainer = findViewById(R.id.action_buttons_container);
        Button acceptBtn = findViewById(R.id.btn_accept);
        Button rejectBtn = findViewById(R.id.btn_reject);

        boolean isPending = issue.getStatus() == PendingIssue.IssueStatus.PENDING;
        actionContainer.setVisibility(isPending ? View.VISIBLE : View.GONE);

        if (!isPending) return;

        acceptBtn.setOnClickListener(v -> {
            hideKeyboard();
            if (pendingIssueManager == null || issue.getId() == null) return;

            if (issue.getIssueType() == PendingIssue.IssueType.TEAM_INVITE
                    || issue.getIssueType() == PendingIssue.IssueType.TEAM_TRANSFER) {
                pendingIssueManager.acceptTeamIssue(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.team_notification_accepted));
            } else if (issue.getIssueType() == PendingIssue.IssueType.ROOM_INVITE) {
                pendingIssueManager.acceptRoomInvite(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.room_invite_accepted));
            } else {
                pendingIssueManager.accept(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.peer_accepted));
            }
            finish();
        });

        rejectBtn.setOnClickListener(v -> {
            hideKeyboard();
            if (pendingIssueManager == null || issue.getId() == null) return;

            if (issue.getIssueType() == PendingIssue.IssueType.TEAM_INVITE
                    || issue.getIssueType() == PendingIssue.IssueType.TEAM_TRANSFER) {
                pendingIssueManager.rejectTeamIssue(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.team_notification_rejected));
            } else if (issue.getIssueType() == PendingIssue.IssueType.ROOM_INVITE) {
                pendingIssueManager.rejectRoomInvite(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.room_invite_rejected));
            } else {
                pendingIssueManager.reject(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.peer_rejected));
            }
            finish();
        });
    }

    private String getTypeLabel(PendingIssue.IssueType type) {
        if (type == null) return "";
        switch (type) {
            case STRANGER_PEER:
                return getString(R.string.stranger_peer_request);
            case TEAM_INVITE:
                return getString(R.string.team_invite_request);
            case TEAM_TRANSFER:
                return getString(R.string.team_transfer_request);
            case ROOM_INVITE:
                return getString(R.string.room_invite_request);
            default:
                return type.name();
        }
    }

    private void setText(int viewId, String text) {
        TextView tv = findViewById(viewId);
        if (tv != null && text != null) {
            tv.setText(text);
        }
    }

    private void loadAvatar(ImageView avatarView, String fid) {
        if (fid == null || fid.isEmpty() || avatarView == null) return;
        new Thread(() -> {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                Bitmap bitmap = avatarManager.getAvatarBitmap(fid);
                if (bitmap != null && !isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> avatarView.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading avatar: %s", e.getMessage());
            }
        }).start();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    public void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
