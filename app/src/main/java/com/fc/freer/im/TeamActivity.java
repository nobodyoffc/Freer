package com.fc.freer.im;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.ConversationAdapter;
import com.fc.freer.im.handler.TeamHandler;
import com.fc.freer.manager.CashManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.RemindDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Activity for Team conversations list.
 * Shows all team message conversations (encrypted with team symkey).
 */
public class TeamActivity extends BaseCryptoActivity implements ConversationAdapter.OnConversationClickListener {
    private static final String TAG = "TeamActivity";

    private RecyclerView conversationsRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private EditText searchEditText;
    private View clearSearchIcon;
    private ImageButton clearButton;
    private ImageButton newChatButton;
    private ImageButton moreButton;
    private ImageButton deleteButton;

    private ConversationAdapter adapter;
    private final List<Conversation> conversations = new ArrayList<>();
    private final List<Conversation> filteredConversations = new ArrayList<>();

    private View pendingIssuesBanner;
    private TextView pendingIssuesBadge;

    private ImManager imManager;
    private String liveFid;
    private TeamSyncManager teamSyncManager;
    private OnBackPressedCallback selectionBackCallback;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_talk;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.team);
    }

    @Override
    protected void initializeViews() {
        conversationsRecyclerView = findViewById(R.id.conversations_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        searchEditText = findViewById(R.id.search_edit_text);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        clearButton = findViewById(R.id.clear_button);
        newChatButton = findViewById(R.id.new_chat_button);
        moreButton = findViewById(R.id.more_button);
        deleteButton = findViewById(R.id.delete_button);

        pendingIssuesBanner = findViewById(R.id.message_requests_banner);
        pendingIssuesBadge = findViewById(R.id.message_requests_badge);
        TextView bannerText = findViewById(R.id.message_requests_text);
        if (bannerText != null) {
            bannerText.setText(R.string.pending_issues);
        }
        if (pendingIssuesBanner != null) {
            pendingIssuesBanner.setOnClickListener(v -> {
                hideKeyboard();
                startActivity(new Intent(this, PendingIssueActivity.class));
            });
        }

        adapter = new ConversationAdapter(filteredConversations, this);
        conversationsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        conversationsRecyclerView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::refreshConversations);

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterConversations(s.toString());
                clearSearchIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                updateClearButtonVisibility();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        clearSearchIcon.setOnClickListener(v -> {
            searchEditText.setText("");
            hideKeyboard();
            if (adapter.isSelectionMode()) {
                exitSelectionMode();
            }
        });

        selectionBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelectionMode();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, selectionBackCallback);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            searchEditText.setText("");
            if (adapter.isSelectionMode()) {
                exitSelectionMode();
            }
        });

        newChatButton.setOnClickListener(this::showPlusMenu);

        moreButton.setVisibility(View.VISIBLE);
        moreButton.setOnClickListener(this::showMoreMenu);

        deleteButton.setOnClickListener(v -> {
            hideKeyboard();
            launchLeaveTeams();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void initManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            liveFid = setting.getMainFid();
            imManager = setting.getImManager();
            if (imManager == null) {
                ToastUtils.makeText(this, getString(R.string.im_not_ready_try_later));
            } else {
                registerListener();
                teamSyncManager = new TeamSyncManager(liveFid);
            }
        }
    }

    private final ImManager.ImListener imListener = new ImManager.ImListener() {
        @Override
        public void onMessageReceived(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.TEAM) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onMessageSent(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.TEAM) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onConversationUpdated(Conversation conversation) {
            if (conversation.getType() == ImType.TEAM) {
                runOnUiThread(() -> updateConversation(conversation));
            }
        }

        @Override
        public void onPresenceChanged(String fid, boolean online) {}

        @Override
        public void onError(String error) {
            runOnUiThread(() -> TimberLogger.e(TAG, error));
        }

    };

    private void registerListener() {
        if (imManager != null) imManager.addListener(imListener);
    }

    private void unregisterListener() {
        if (imManager != null) imManager.removeListener(imListener);
    }

    private void updatePendingIssuesBanner() {
        if (pendingIssuesBanner == null) return;
        if (imManager == null) {
            pendingIssuesBanner.setVisibility(View.GONE);
            return;
        }
        PendingIssueManager mgr = imManager.getPendingIssueManager();
        int count = (mgr != null) ? mgr.getPendingCount() : 0;
        if (count > 0) {
            pendingIssuesBanner.setVisibility(View.VISIBLE);
            if (pendingIssuesBadge != null) {
                pendingIssuesBadge.setText(String.valueOf(count));
            }
        } else {
            pendingIssuesBanner.setVisibility(View.GONE);
        }
    }

    private void triggerTeamSync() {
        if (teamSyncManager == null || imManager == null) return;

        new Thread(() -> {
            teamSyncManager.sync(
                    imManager.getFapiClient(),
                    imManager.getTeamHandler(),
                    imManager.getConversationsDb(),
                    imManager.getDockRegistry(),
                    new TeamSyncManager.SyncCallback() {
                        @Override
                        public void onSyncComplete(int newCount, int updatedCount) {
                            if (newCount > 0 || updatedCount > 0) {
                                runOnUiThread(() -> loadConversations());
                            }
                        }

                        @Override
                        public void onSyncError(String error) {
                            TimberLogger.e(TAG, "Team sync error: %s", error);
                        }
                    }
            );
        }).start();
    }

    private void loadConversations() {
        if (imManager == null) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        List<Conversation> all = imManager.getConversations();
        conversations.clear();

        TeamHandler teamHandler = imManager.getTeamHandler();
        Set<String> confirmedTeamIds = new HashSet<>();
        for (Conversation conv : all) {
            if (conv.getType() == ImType.TEAM) {
                if (conv.getLeftGroup() != null && conv.getLeftGroup()) continue;
                resolveTeamInfo(conv, teamHandler);
                conversations.add(conv);
                confirmedTeamIds.add(conv.getTargetId());
            }
        }

        // Inject pending teams (TX broadcast but not yet confirmed on chain).
        Set<String> pendingIds = new HashSet<>();
        if (teamHandler != null) {
            for (Team pending : teamHandler.getPendingTeams()) {
                if (confirmedTeamIds.contains(pending.getId())) continue; // already confirmed
                Conversation conv = new Conversation();
                conv.setId("TEAM_PENDING_" + pending.getId());
                conv.setType(ImType.TEAM);
                conv.setTargetId(pending.getId());
                conv.setDisplayName(pending.getStdName());
                conv.setAvatarDid(pending.getOwner());
                conversations.add(0, conv); // prepend so pending items appear at top
                pendingIds.add(pending.getId());
            }
        }

        adapter.setPendingTargetIds(pendingIds);
        adapter.setUnavailableTargetIds(imManager.getUnavailableTargetIds("team"));
        filterConversations(searchEditText.getText().toString());
        swipeRefreshLayout.setRefreshing(false);
    }

    private void resolveTeamInfo(Conversation conv, TeamHandler teamHandler) {
        if (teamHandler == null || conv.getTargetId() == null) return;
        Team team = teamHandler.getTeam(conv.getTargetId());
        if (team == null) return;

        if (team.getStdName() != null) {
            conv.setDisplayName(team.getStdName());
        }
        if (team.getOwner() != null) {
            conv.setAvatarDid(team.getOwner());
        }
        conv.setMemberNum(team.getMemberNum());
        conv.settCdd(team.gettCdd());
        conv.settRate(team.gettRate());
    }

    private void refreshConversations() {
        triggerTeamSync();
        loadConversations();
    }

    private void filterConversations(String query) {
        filteredConversations.clear();

        if (query == null || query.isEmpty()) {
            filteredConversations.addAll(conversations);
        } else {
            String lowerQuery = query.toLowerCase();
            for (Conversation conv : conversations) {
                if ((conv.getDisplayName() != null && conv.getDisplayName().toLowerCase().contains(lowerQuery)) ||
                    (conv.getTargetId() != null && conv.getTargetId().toLowerCase().contains(lowerQuery)) ||
                    (conv.getLastMessageContent() != null && conv.getLastMessageContent().toLowerCase().contains(lowerQuery))) {
                    filteredConversations.add(conv);
                }
            }
        }

        adapter.notifyDataSetChanged();
    }

    private void updateConversation(Conversation updated) {
        if (imManager != null) {
            TeamHandler teamHandler = imManager.getTeamHandler();
            resolveTeamInfo(updated, teamHandler);
        }
        for (int i = 0; i < conversations.size(); i++) {
            if (conversations.get(i).getId().equals(updated.getId())) {
                conversations.set(i, updated);
                filterConversations(searchEditText.getText().toString());
                return;
            }
        }
        conversations.add(0, updated);
        filterConversations(searchEditText.getText().toString());
    }

    // ========== Selection Mode ==========

    @Override
    public void onConversationClick(Conversation conversation) {
        hideKeyboard();
        openChat(conversation.getTargetId(), conversation.getDisplayName());
    }

    @Override
    public void onConversationLongClick(Conversation conversation) {
        hideKeyboard();
        if (!adapter.isSelectionMode()) {
            enterSelectionMode();
            adapter.toggleSelection(conversation.getId());
        }
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        deleteButton.setEnabled(selectedCount > 0);
    }

    private void enterSelectionMode() {
        adapter.setSelectionMode(true);
        deleteButton.setVisibility(View.VISIBLE);
        deleteButton.setEnabled(false);
        selectionBackCallback.setEnabled(true);
        updateClearButtonVisibility();
    }

    private void exitSelectionMode() {
        adapter.setSelectionMode(false);
        deleteButton.setVisibility(View.GONE);
        selectionBackCallback.setEnabled(false);
        updateClearButtonVisibility();
    }

    private void updateClearButtonVisibility() {
        boolean hasSearchText = searchEditText.getText().length() > 0;
        boolean inSelectionMode = adapter.isSelectionMode();
        clearButton.setVisibility((hasSearchText || inSelectionMode) ? View.VISIBLE : View.GONE);
    }

    private void launchLeaveTeams() {
        List<Conversation> selected = adapter.getSelectedConversations();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_teams_selected));
            return;
        }

        ArrayList<String> teamIds = new ArrayList<>();
        for (Conversation conv : selected) {
            if (conv.getTargetId() != null) {
                teamIds.add(conv.getTargetId());
            }
        }

        if (teamIds.isEmpty()) return;

        exitSelectionMode();

        TeamHandler teamHandler = (imManager != null) ? imManager.getTeamHandler() : null;
        int ownedCount = 0;
        if (teamHandler != null && liveFid != null) {
            for (String tid : teamIds) {
                if (teamHandler.isOwner(tid, liveFid)) {
                    ownedCount++;
                }
            }
        }

        if (ownedCount == teamIds.size()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.disband_team)
                    .setMessage(R.string.confirm_disband_teams)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        hideKeyboard();
                        disbandTeams(teamIds);
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                    .show();
        } else if (ownedCount > 0) {
            new RemindDialog(this, getString(R.string.cannot_leave_own_teams_or_rooms), true).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.leave_teams)
                    .setMessage(R.string.confirm_leave_teams)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        hideKeyboard();
                        leaveTeams(teamIds);
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                    .show();
        }
    }

    private void disbandTeams(List<String> teamIds) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        TeamOpData opData = TeamOpData.makeDisband(new ArrayList<>(teamIds));
        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            markTeamConversationsAsLeft(teamIds);
                            runOnUiThread(() -> {
                                loadConversations();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(TeamActivity.this, errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void leaveTeams(List<String> teamIds) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        TeamOpData opData = TeamOpData.makeLeave(new ArrayList<>(teamIds));
        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            markTeamConversationsAsLeft(teamIds);
                            runOnUiThread(() -> {
                                loadConversations();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(TeamActivity.this, errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void markTeamConversationsAsLeft(List<String> teamIds) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) return;

        ImManager im = setting.getImManager();
        for (String tid : teamIds) {
            String convId = ImType.TEAM.name() + "_" + tid;
            Conversation conv = im.getConversationsDb().get(convId);
            if (conv != null) {
                conv.setLeftGroup(true);
                im.getConversationsDb().put(convId, conv);
            }
            if (im.getDockRegistry() != null) {
                im.getDockRegistry().unregister("team", tid);
            }
        }
    }

    // ========== More Menu ==========

    private void showMoreMenu(View anchor) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_team_more_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.about_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            com.fc.freer.utils.AboutDialog.show(this, R.string.about_team_title, R.string.about_team_message);
        });

        popupView.findViewById(R.id.menu_disbanded_teams).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, DisbandedTeamActivity.class));
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    // ========== "+" Menu ==========

    private void showPlusMenu(View anchor) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_team_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.menu_join_team).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, JoinTeamActivity.class));
        });

        popupView.findViewById(R.id.menu_create_team).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, CreateTeamActivity.class));
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    private void openChat(String teamId, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.TEAM.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, teamId);
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, displayName);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Don't blank the list while ImManager is still initializing: show a
        // "Connecting…" banner and load from the local DB once it is ready.
        ensureImReadyWithBanner(im -> {
            imManager = im;
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting != null) liveFid = setting.getMainFid();
            registerListener();
            if (teamSyncManager == null && liveFid != null) {
                teamSyncManager = new TeamSyncManager(liveFid);
            }
            loadConversations();
            triggerTeamSync();
            updatePendingIssuesBanner();
            retryFailedDocks();
        });
    }

    private void retryFailedDocks() {
        if (imManager == null || !imManager.hasFailedDocks("team")) return;
        imManager.retryFailedDocks("team", reconnected -> runOnUiThread(() -> {
            // Update red dots whether retry succeeded or not
            adapter.setUnavailableTargetIds(imManager.getUnavailableTargetIds("team"));
            adapter.notifyDataSetChanged();
        }));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterListener();
    }
}
