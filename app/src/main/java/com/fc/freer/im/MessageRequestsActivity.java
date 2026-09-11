package com.fc.freer.im;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.MessageRequestAdapter;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows pending stranger peer message requests.
 * The user can preview quarantined messages and accept or reject each request.
 */
public class MessageRequestsActivity extends BaseCryptoActivity
        implements MessageRequestAdapter.OnRequestActionListener {

    private static final String TAG = "MessageRequestsActivity";

    private RecyclerView recyclerView;
    private TextView emptyText;

    private MessageRequestAdapter adapter;
    private final List<PendingIssue> issues = new ArrayList<>();

    private ImManager imManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_message_requests;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.message_requests);
    }

    @Override
    protected void initializeViews() {
        recyclerView = findViewById(R.id.requests_recycler_view);
        emptyText = findViewById(R.id.empty_text);

        adapter = new MessageRequestAdapter(issues, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        com.fc.freer.nobody.NobodyUi.observe(this, fids -> adapter.notifyDataSetChanged());

        loadIssues();
    }

    @Override
    protected void setupButtons() {
        // back handled by BaseCryptoActivity
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadIssues();
    }

    private void loadIssues() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            imManager = setting.getImManager();
        }
        issues.clear();
        if (imManager != null) {
            PendingIssueManager mgr = imManager.getPendingIssueManager();
            if (mgr != null) {
                issues.addAll(mgr.getPendingIssues());
            }
        }
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(issues.isEmpty() ? View.VISIBLE : View.GONE);

        java.util.List<String> peers = new java.util.ArrayList<>();
        for (PendingIssue issue : issues) peers.add(issue.getPeerFid());
        com.fc.freer.nobody.NobodyUi.resolveAsync(peers);
    }

    @Override
    public void onAccept(PendingIssue issue) {
        hideKeyboard();
        if (imManager == null) return;
        PendingIssueManager mgr = imManager.getPendingIssueManager();
        if (mgr == null) return;

        mgr.accept(issue.getId());
        ToastUtils.showInfo(this, getString(R.string.messages_promoted));
        loadIssues();

        // Use the real CID for the title when available; otherwise leave it null
        // so ChatActivity falls back to the full FID (never the last-4 alias).
        String cid = null;
        if (issue.getPeerFid() != null) {
            cid = CidFidManager.getInstance(this).getCidByFid(issue.getPeerFid());
        }

        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.P2P.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, issue.getPeerFid());
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, (cid != null && !cid.isEmpty()) ? cid : null);
        startActivity(intent);
    }

    @Override
    public void onReject(PendingIssue issue) {
        hideKeyboard();
        if (imManager == null) return;
        PendingIssueManager mgr = imManager.getPendingIssueManager();
        if (mgr == null) return;

        mgr.reject(issue.getId());
        ToastUtils.showInfo(this, getString(R.string.messages_purged));
        loadIssues();
    }

    @Override
    public void onItemClick(PendingIssue issue) {
        hideKeyboard();
        if (imManager == null || issue.getPeerFid() == null) return;

        List<com.fc.fc_ajdk.data.fcData.ImMessage> quarantined =
                imManager.getQuarantinedMessages(issue.getPeerFid(), 0);

        if (quarantined.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_message_requests));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (com.fc.fc_ajdk.data.fcData.ImMessage msg : quarantined) {
            if (msg.getContent() != null) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(msg.getContent());
            }
        }

        DialogUtils.show(new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.stranger_messages_count, quarantined.size()))
                .setMessage(sb.toString())
                .setPositiveButton(R.string.accept, (d, w) -> onAccept(issue))
                .setNegativeButton(R.string.reject, (d, w) -> onReject(issue))
                .setNeutralButton(R.string.cancel, null));
    }
}
