package com.fc.freer.im;

import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;

public class BlacklistActivity extends BaseCryptoActivity
        implements BlacklistAdapter.OnRemoveListener {

    private RecyclerView recyclerView;
    private TextView emptyText;

    private BlacklistAdapter adapter;
    private final List<String> blacklist = new ArrayList<>();

    private ContactPolicy contactPolicy;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_blacklist;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.manage_blacklist);
    }

    @Override
    protected void initializeViews() {
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        recyclerView = findViewById(R.id.blacklist_recycler_view);
        emptyText = findViewById(R.id.empty_text);

        adapter = new BlacklistAdapter(blacklist, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        loadBlacklist();
    }

    @Override
    protected void setupButtons() {
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBlacklist();
    }

    private void loadBlacklist() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            ImManager imManager = setting.getImManager();
            if (imManager != null) {
                contactPolicy = imManager.getContactPolicy();
            }
        }

        blacklist.clear();
        if (contactPolicy != null) {
            blacklist.addAll(contactPolicy.getBlacklist());
        }
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(blacklist.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onRemove(String fid, int position) {
        hideKeyboard();
        if (contactPolicy == null) return;

        DialogUtils.show(new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.remove_from_blacklist)
                .setMessage(getString(R.string.confirm_remove_from_blacklist, fid))
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    contactPolicy.removeFromBlacklist(fid);
                    // Back to a stranger, not an accepted contact: the next message or call asks again.
                    Setting setting = SettingManager.getInstance().getCurrentSetting();
                    ImManager im = setting != null ? setting.getImManager() : null;
                    PendingIssueManager issues = im != null ? im.getPendingIssueManager() : null;
                    if (issues != null) issues.forgetStranger(fid);
                    if (position >= 0 && position < blacklist.size()) {
                        blacklist.remove(position);
                        adapter.notifyItemRemoved(position);
                    }
                    emptyText.setVisibility(blacklist.isEmpty() ? View.VISIBLE : View.GONE);
                    ToastUtils.showInfo(this, getString(R.string.removed_from_blacklist));
                })
                .setNegativeButton(R.string.cancel, null)
                );
    }
}
