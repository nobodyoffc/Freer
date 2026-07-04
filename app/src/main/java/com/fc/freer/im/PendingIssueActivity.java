package com.fc.freer.im;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.PendingIssueAdapter;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ToastUtils;

import java.util.List;

public class PendingIssueActivity extends BaseCryptoActivity {
    private static final String TAG = "PendingIssueActivity";

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView emptyText;
    private PendingIssueAdapter adapter;
    private PendingIssueManager pendingIssueManager;
    private ImManager imManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_pending_issues;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.pending_issues);
    }

    @Override
    protected void initializeViews() {
        recyclerView = findViewById(R.id.issues_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        emptyText = findViewById(R.id.empty_text);

        adapter = new PendingIssueAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(issue -> {
            hideKeyboard();
            PendingIssueDetailActivity.start(PendingIssueActivity.this, issue);
        });

        adapter.setActionListener(new PendingIssueAdapter.ActionListener() {
            @Override
            public void onAccept(PendingIssue issue) {
                hideKeyboard();
                if (pendingIssueManager == null || issue.getId() == null) return;
                if (issue.getIssueType() == PendingIssue.IssueType.ROOM_INVITE) {
                    pendingIssueManager.acceptRoomInvite(issue.getId());
                    ToastUtils.showInfo(PendingIssueActivity.this,
                            getString(R.string.room_invite_accepted));
                    loadIssues();
                } else {
                    pendingIssueManager.accept(issue.getId());
                    ToastUtils.showInfo(PendingIssueActivity.this,
                            getString(R.string.peer_accepted));
                    loadIssues();
                }
            }

            @Override
            public void onReject(PendingIssue issue) {
                hideKeyboard();
                if (pendingIssueManager != null && issue.getId() != null) {
                    if (issue.getIssueType() == PendingIssue.IssueType.ROOM_INVITE) {
                        pendingIssueManager.rejectRoomInvite(issue.getId());
                        ToastUtils.showInfo(PendingIssueActivity.this,
                                getString(R.string.room_invite_rejected));
                    } else {
                        pendingIssueManager.reject(issue.getId());
                        ToastUtils.showInfo(PendingIssueActivity.this,
                                getString(R.string.peer_rejected));
                    }
                    loadIssues();
                }
            }
        });

        swipeRefreshLayout.setOnRefreshListener(this::loadIssues);

        initManager();
        loadIssues();
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

    private void loadIssues() {
        if (pendingIssueManager == null) {
            showEmpty(true);
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        List<PendingIssue> issues = pendingIssueManager.getPendingIssues();
        adapter.setItems(issues);
        showEmpty(issues.isEmpty());
        swipeRefreshLayout.setRefreshing(false);
    }

    private void showEmpty(boolean empty) {
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadIssues();
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
