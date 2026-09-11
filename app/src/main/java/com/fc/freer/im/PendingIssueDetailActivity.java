package com.fc.freer.im;

import android.content.Context;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class PendingIssueDetailActivity extends BaseCryptoActivity {
    private static final String TAG = "PendingIssueDetailAct";
    public static final String EXTRA_ISSUE_JSON = "extra_issue_json";

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private PendingIssue issue;
    private PendingIssueManager pendingIssueManager;
    private ImManager imManager;

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
            ToastUtils.showWarning(this, getString(R.string.toast_no_issue_data));
            finish();
            return;
        }
        issue = PendingIssue.fromJson(json);
        if (issue == null) {
            ToastUtils.showWarning(this, getString(R.string.toast_failed_parse_issue_data));
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
            imManager = setting.getImManager();
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
        // Anything from a nobody peer could have come from anyone
        NobodyUi.bindBannerResolving(findViewById(R.id.nobodyBanner), issue.getPeerFid(),
                R.string.nobody_sender_warning,
                () -> loadAvatar(findViewById(R.id.detail_avatar), issue.getPeerFid()));

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
            case ROOM_INVITE:
                populateRoomData(container, label);
                break;
            case TEAM_INVITE:
                populateTeamInviteData(container, label);
                break;
            case CONSENSUS_CHANGE:
                populateConsensusData(container, label);
                break;
            case HISTORY_REQUEST:
                populateHistoryRequestData(container, label);
                break;
            case HISTORY_IMPORT_FAILED:
                populateHistoryImportFailedData(container, label);
                break;
            default:
                break;
        }
    }

    /** Messages this device would hand over; -1 until counted. Share waits for the count. */
    private int historyMessageCount = -1;

    private void populateHistoryImportFailedData(LinearLayout container, TextView label) {
        PendingIssue.HistoryImportFailedData data = HistoryRequestPrompt.importFailureOf(issue);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        addDataRow(container, getString(R.string.type), data.imType != null ? data.imType.name() : "");
        if (data.targetId != null) {
            addDataRow(container, getString(R.string.history_conversation), data.targetId);
        }
        addDataRow(container, getString(R.string.history_time_range),
                HistoryRequestPrompt.formatRange(data.since, data.before));
        addDataRow(container, getString(R.string.history_import_attempts), String.valueOf(data.attempts));
        if (data.lastError != null) {
            addDataRow(container, getString(R.string.history_import_last_error), data.lastError);
        }
    }

    private void populateHistoryRequestData(LinearLayout container, TextView label) {
        PendingIssue.HistoryRequestData data =
                issue.getDataAs(PendingIssue.HistoryRequestData.class);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        String liveFid = imManager != null ? imManager.getLiveFid() : null;
        addDataRow(container, getString(R.string.type), data.imType != null ? data.imType.name() : "");
        addDataRow(container, getString(R.string.history_request_issue),
                HistoryRequestPrompt.title(this, liveFid, issue));
        addDataRow(container, getString(R.string.history_time_range),
                HistoryRequestPrompt.formatRange(data.since, data.before));

        int before = container.getChildCount();
        addDataRow(container, getString(R.string.history_messages_on_device), "…");
        TextView countValue = (TextView) container.getChildAt(before + 1);

        boolean open = issue.getStatus() == PendingIssue.IssueStatus.PENDING
                || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED;
        if (imManager == null || !open) return;
        new Thread(() -> {
            int count = imManager.countHistoryRequestMessages(issue.getId());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                historyMessageCount = count;
                countValue.setText(count < 0 ? "" : String.valueOf(count));
                Button acceptBtn = findViewById(R.id.btn_accept);
                if (acceptBtn != null) acceptBtn.setEnabled(count > 0);
            });
        }).start();
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

    private void populateTeamInviteData(LinearLayout container, TextView label) {
        PendingIssue.TeamInviteData data = issue.getDataAs(PendingIssue.TeamInviteData.class);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        if (data.teamName != null) addDataRow(container, getString(R.string.team_name), data.teamName);
        if (data.teamId != null) addDataRow(container, getString(R.string.team_id), data.teamId);
        if (data.senderFid != null) addDataRow(container, getString(R.string.from), data.senderFid);
    }

    private void populateConsensusData(LinearLayout container, TextView label) {
        PendingIssue.ConsensusChangeData data =
                issue.getDataAs(PendingIssue.ConsensusChangeData.class);
        if (data == null) return;

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        if (data.teamName != null) {
            addDataRow(container, getString(R.string.team_name), data.teamName);
        }
        if (data.teamId != null) {
            addDataRow(container, getString(R.string.team_id), data.teamId);
        }
        if (data.ownerFid != null) {
            addDataRow(container, getString(R.string.owner), data.ownerFid);
        }
        if (data.newConsensusId != null) {
            addDataRow(container, getString(R.string.new_consensus_id), data.newConsensusId);
        }
        if (data.oldConsensusId != null) {
            addDataRow(container, getString(R.string.old_consensus_id), data.oldConsensusId);
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

        if (issue.getIssueType() == PendingIssue.IssueType.CONSENSUS_CHANGE) {
            actionContainer.setVisibility(View.GONE);
            setupConsensusButtons();
            return;
        }

        if (issue.getIssueType() == PendingIssue.IssueType.TEAM_INVITE) {
            setupTeamInviteButtons(actionContainer, acceptBtn, rejectBtn);
            return;
        }

        if (issue.getIssueType() == PendingIssue.IssueType.HISTORY_REQUEST) {
            setupHistoryRequestButtons(actionContainer, acceptBtn, rejectBtn);
            return;
        }

        if (issue.getIssueType() == PendingIssue.IssueType.HISTORY_IMPORT_FAILED) {
            setupHistoryImportFailedButtons(actionContainer, acceptBtn, rejectBtn);
            return;
        }

        boolean isPending = issue.getStatus() == PendingIssue.IssueStatus.PENDING;
        actionContainer.setVisibility(isPending ? View.VISIBLE : View.GONE);

        if (!isPending) return;

        acceptBtn.setOnClickListener(v -> {
            hideKeyboard();
            if (pendingIssueManager == null || issue.getId() == null) return;
            if (issue.getIssueType() == PendingIssue.IssueType.ROOM_INVITE) {
                pendingIssueManager.acceptRoomInvite(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.room_invite_accepted));
                finish();
            } else {
                pendingIssueManager.accept(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.peer_accepted));
                finish();
            }
        });

        rejectBtn.setOnClickListener(v -> {
            hideKeyboard();
            if (pendingIssueManager == null || issue.getId() == null) return;

            if (issue.getIssueType() == PendingIssue.IssueType.ROOM_INVITE) {
                pendingIssueManager.rejectRoomInvite(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.room_invite_rejected));
            } else {
                pendingIssueManager.reject(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.peer_rejected));
            }
            finish();
        });
    }

    /**
     * Wire the consensus-change decision: read either document, then sign the new consensus,
     * postpone the decision, or leave the team. Reading stays available after the decision is
     * made, so the member can still look up what they signed.
     */
    private void setupConsensusButtons() {
        PendingIssue.ConsensusChangeData data =
                issue.getDataAs(PendingIssue.ConsensusChangeData.class);
        if (data == null) return;

        LinearLayout docContainer = findViewById(R.id.doc_buttons_container);
        Button viewOldBtn = findViewById(R.id.btn_view_old_doc);
        Button viewNewBtn = findViewById(R.id.btn_view_new_doc);
        docContainer.setVisibility(View.VISIBLE);

        // The previous consensus is only readable when this device saw it before the change.
        viewOldBtn.setEnabled(data.oldConsensusId != null);
        viewOldBtn.setOnClickListener(v -> showDoc(getString(R.string.view_old_consensus),
                data.oldConsensusId, data.diskSid));
        viewNewBtn.setOnClickListener(v -> showDoc(getString(R.string.view_new_consensus),
                data.newConsensusId, data.diskSid));

        LinearLayout consensusContainer = findViewById(R.id.consensus_buttons_container);
        boolean open = issue.getStatus() == PendingIssue.IssueStatus.PENDING
                || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED;
        consensusContainer.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!open) return;

        Button signBtn = findViewById(R.id.btn_sign_consensus);
        Button laterBtn = findViewById(R.id.btn_sign_later);
        Button leaveBtn = findViewById(R.id.btn_leave_team);

        signBtn.setOnClickListener(v -> signConsensus(data));
        leaveBtn.setOnClickListener(v -> confirmLeaveTeam(data));

        laterBtn.setEnabled(issue.getStatus() == PendingIssue.IssueStatus.PENDING);
        laterBtn.setOnClickListener(v -> {
            if (pendingIssueManager != null && issue.getId() != null) {
                pendingIssueManager.defer(issue.getId());
                ToastUtils.showInfo(this, getString(R.string.consensus_signing_postponed));
            }
            finish();
        });
    }

    private void showDoc(String title, String consensusId, String diskSid) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.showWarning(this, getString(R.string.no_active_fid));
            return;
        }
        ConsensusDocViewer.fetchAndShow(this, HatManager.getInstance(this, liveKeyInfo.getId()),
                title, consensusId, Collections.singletonList(diskSid), 0, null);
    }

    /**
     * Carve the signature. The parser rejects an {@code agree consensus} whose id differs from
     * the team's stored one, so the id is re-read from the team the sync last wrote rather than
     * taken from the issue, which may have been raised before a further change.
     */
    private void signConsensus(PendingIssue.ConsensusChangeData data) {
        String consensusId = data.newConsensusId;
        if (imManager != null && imManager.getTeamHandler() != null) {
            Team team = imManager.getTeamHandler().getTeam(data.teamId);
            if (team != null && team.getConsensusId() != null) {
                consensusId = team.getConsensusId();
            }
        }
        TeamTxHelper.sendAgreeConsensusTx(this, data.teamId, consensusId,
                new TeamTxHelper.TxResultCallback() {
                    @Override
                    public void onSuccess() {
                        if (pendingIssueManager != null && issue.getId() != null) {
                            pendingIssueManager.resolveConsensusIssue(issue.getId(),
                                    PendingIssue.IssueStatus.ACCEPTED);
                        }
                        ToastUtils.showInfo(PendingIssueDetailActivity.this,
                                getString(R.string.consensus_signed));
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        ToastUtils.showWarning(PendingIssueDetailActivity.this,
                                getString(R.string.failed_to_sign_consensus) + ": " + message);
                    }
                });
    }

    private void confirmLeaveTeam(PendingIssue.ConsensusChangeData data) {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.leave_team)
                .setMessage(getString(R.string.confirm_leave_team_consensus,
                        data.teamName != null ? data.teamName : data.teamId))
                .setPositiveButton(R.string.leave_team, (d, w) -> leaveTeam(data))
                .setNegativeButton(R.string.cancel, null));
    }

    private void leaveTeam(PendingIssue.ConsensusChangeData data) {
        TeamTxHelper.sendLeaveTx(this, data.teamId, new TeamTxHelper.TxResultCallback() {
            @Override
            public void onSuccess() {
                if (pendingIssueManager != null && issue.getId() != null) {
                    pendingIssueManager.resolveConsensusIssue(issue.getId(),
                            PendingIssue.IssueStatus.REJECTED);
                }
                ToastUtils.showInfo(PendingIssueDetailActivity.this,
                        getString(R.string.left_team));
                finish();
            }

            @Override
            public void onError(String message) {
                ToastUtils.showWarning(PendingIssueDetailActivity.this,
                        getString(R.string.failed_to_leave_team) + ": " + message);
            }
        });
    }

    /**
     * Accepting an invitation opens {@link JoinTeamActivity}, which confirms the invitation
     * against the team's on-chain invitees and shows the consensus before any join is carved.
     */
    private void setupTeamInviteButtons(LinearLayout actionContainer, Button acceptBtn, Button rejectBtn) {
        boolean open = issue.getStatus() == PendingIssue.IssueStatus.PENDING
                || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED;
        actionContainer.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!open) return;

        acceptBtn.setText(R.string.accept);
        rejectBtn.setText(R.string.reject);

        acceptBtn.setOnClickListener(v -> {
            if (pendingIssueManager != null && issue.getId() != null) {
                pendingIssueManager.resolveTeamInvite(issue.getId(),
                        PendingIssue.IssueStatus.ACCEPTED);
            }
            startActivity(new Intent(this, JoinTeamActivity.class));
            finish();
        });

        rejectBtn.setOnClickListener(v -> {
            if (pendingIssueManager != null && issue.getId() != null) {
                pendingIssueManager.resolveTeamInvite(issue.getId(),
                        PendingIssue.IssueStatus.REJECTED);
            }
            ToastUtils.showInfo(this, getString(R.string.team_notification_rejected));
            finish();
        });
    }

    /**
     * Share opens the confirmation that says what goes and to whom; nothing is sent before the
     * user agrees there. Decline sends nothing.
     */
    private void setupHistoryRequestButtons(LinearLayout actionContainer, Button acceptBtn, Button rejectBtn) {
        boolean open = issue.getStatus() == PendingIssue.IssueStatus.PENDING
                || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED;
        actionContainer.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!open) return;

        acceptBtn.setText(R.string.history_share_action);
        rejectBtn.setText(R.string.decline);
        // Enabled once the count shows there is something to share.
        acceptBtn.setEnabled(historyMessageCount > 0);

        acceptBtn.setOnClickListener(v -> {
            hideKeyboard();
            if (imManager == null || historyMessageCount <= 0) return;
            HistoryRequestPrompt.confirmShare(this, imManager, issue, historyMessageCount, this::finish);
        });

        rejectBtn.setOnClickListener(v -> {
            hideKeyboard();
            if (imManager == null || issue.getId() == null) return;
            imManager.declineHistoryRequest(issue.getId());
            ToastUtils.showInfo(this, getString(R.string.history_request_declined));
            finish();
        });
    }

    /** Retry downloads the shared history again; Dismiss forgets it without importing. */
    private void setupHistoryImportFailedButtons(LinearLayout actionContainer, Button acceptBtn, Button rejectBtn) {
        boolean open = issue.getStatus() == PendingIssue.IssueStatus.PENDING
                || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED;
        actionContainer.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!open) return;

        acceptBtn.setText(R.string.retry);
        rejectBtn.setText(R.string.dismiss);

        acceptBtn.setOnClickListener(v -> {
            hideKeyboard();
            HistoryRequestPrompt.retryImport(this, imManager, issue, this::finish);
        });
        rejectBtn.setOnClickListener(v -> {
            hideKeyboard();
            HistoryRequestPrompt.dismissImport(this, imManager, issue, this::finish);
        });
    }

    private String getTypeLabel(PendingIssue.IssueType type) {
        if (type == null) return "";
        switch (type) {
            case STRANGER_PEER:
                return getString(R.string.stranger_peer_request);
            case ROOM_INVITE:
                return getString(R.string.room_invite_request);
            case TEAM_INVITE:
                return getString(R.string.team_invite_notification_title);
            case CONSENSUS_CHANGE:
                return getString(R.string.consensus_change_request);
            case HISTORY_REQUEST:
                return getString(R.string.history_request_issue);
            case HISTORY_IMPORT_FAILED:
                return getString(R.string.history_import_failed_issue);
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
