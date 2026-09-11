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

import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.ConversationAdapter;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Activity for P2P conversations list.
 * Shows all direct message conversations.
 */
public class TalkActivity extends BaseCryptoActivity implements ConversationAdapter.OnConversationClickListener {
    private static final String TAG = "TalkActivity";
    
    private RecyclerView conversationsRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private EditText searchEditText;
    private View clearSearchIcon;
    private ImageButton clearButton;
    private ImageButton moreButton;
    private ImageButton newChatButton;
    private ImageButton deleteButton;
    
    // Message Requests banner
    private View messageRequestsBanner;
    private TextView messageRequestsBadge;

    // Send-only mode banner (no DOCK registered)
    private View sendOnlyBanner;
    
    private ConversationAdapter adapter;
    private List<Conversation> conversations = new ArrayList<>();
    private List<Conversation> filteredConversations = new ArrayList<>();
    
    private ImManager imManager;
    private String liveFid;
    private OnBackPressedCallback selectionBackCallback;

    private final ImManager.ImListener imListener = new ImManager.ImListener() {
        @Override
        public void onMessageReceived(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.P2P) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onMessageSent(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.P2P) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onConversationUpdated(Conversation conversation) {
            if (conversation.getType() == ImType.P2P) {
                runOnUiThread(() -> updateConversation(conversation));
            }
        }

        @Override
        public void onPresenceChanged(String fid, boolean online) {}

        @Override
        public void onError(String error) {
            runOnUiThread(() -> TimberLogger.e(TAG, error));
        }

        @Override
        public void onChannelConfigured() {
            // home.DOCK confirmed on-chain (e.g. a pending registration landed);
            // refresh so the IM UI reflects the now-enabled channel.
            runOnUiThread(() -> {
                checkDockSetup();
                loadConversations();
            });
        }

        @Override
        public void onStrangerMessageReceived(String senderFid, int totalCount) {
            runOnUiThread(() -> updateMessageRequestsBanner());
        }
    };
    
    @Override
    protected int getLayoutId() {
        return R.layout.activity_talk;
    }
    
    @Override
    protected String getActivityTitle() {
        return getString(R.string.talk);
    }
    
    @Override
    protected void initializeViews() {
        conversationsRecyclerView = findViewById(R.id.conversations_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        searchEditText = findViewById(R.id.search_edit_text);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        clearButton = findViewById(R.id.clear_button);
        moreButton = findViewById(R.id.more_button);
        newChatButton = findViewById(R.id.new_chat_button);
        deleteButton = findViewById(R.id.delete_button);
        
        messageRequestsBanner = findViewById(R.id.message_requests_banner);
        messageRequestsBadge = findViewById(R.id.message_requests_badge);
        sendOnlyBanner = findViewById(R.id.send_only_banner);
        
        adapter = new ConversationAdapter(filteredConversations, this);
        conversationsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        conversationsRecyclerView.setAdapter(adapter);
        NobodyUi.observe(this, fids -> adapter.notifyDataSetChanged());
        
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
        });
        
        messageRequestsBanner.setOnClickListener(v -> {
            hideKeyboard();
            startActivity(new Intent(this, MessageRequestsActivity.class));
        });

        sendOnlyBanner.setOnClickListener(v -> {
            hideKeyboard();
            startActivity(new Intent(this, com.fc.freer.data.ServerSetupActivity.class));
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

        moreButton.setOnClickListener(this::showMoreMenu);
        
        newChatButton.setOnClickListener(v -> {
            hideKeyboard();
            openNewChat();
        });

        deleteButton.setOnClickListener(v -> {
            hideKeyboard();
            confirmDeleteSelected();
        });
    }
    
    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in talk list
    }
    
    private void initManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            liveFid = setting.getMainFid();
            imManager = setting.getImManager();
            if (imManager == null)
                ToastUtils.makeText(this, getString(R.string.im_not_ready_try_later));
            else registerListener();
        }
    }
    
    private void registerListener() {
        if (imManager != null) {
            imManager.addListener(imListener);
        }
    }

    private void unregisterListener() {
        if (imManager != null) {
            imManager.removeListener(imListener);
        }
    }
    
    private void loadConversations() {
        if (imManager == null) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }
        
        List<Conversation> all = imManager.getConversations();
        conversations.clear();

        List<String> fidsToResolve = new ArrayList<>();
        for (Conversation conv : all) {
            if (conv.getType() == ImType.P2P) {
                // The first-FCH board is asked via a direct post, not a chat, so
                // it never belongs in the conversation list. Skip any leftover
                // board conversation from an earlier build.
                if (NobodyBoard.isDefaultNobody(conv.getTargetId())) continue;
                conversations.add(conv);
                if ((conv.getDisplayName() == null || conv.getDisplayName().isEmpty())
                        && conv.getTargetId() != null && !conv.getTargetId().isEmpty()) {
                    fidsToResolve.add(conv.getTargetId());
                }
            }
        }

        resolveCidsForConversations(fidsToResolve);

        List<String> partners = new ArrayList<>();
        for (Conversation conv : conversations) partners.add(conv.getTargetId());
        NobodyUi.resolveAsync(partners);
        
        filterConversations(searchEditText.getText().toString());
        swipeRefreshLayout.setRefreshing(false);
        
        updateMessageRequestsBanner();
    }
    
    private void refreshConversations() {
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
        resolveCidForConversation(updated);
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

    private void updateMessageRequestsBanner() {
        if (messageRequestsBanner == null) return;
        if (imManager == null) {
            messageRequestsBanner.setVisibility(View.GONE);
            return;
        }
        PendingIssueManager mgr = imManager.getPendingIssueManager();
        int count = (mgr != null) ? mgr.getPendingCount() : 0;
        if (count > 0) {
            messageRequestsBanner.setVisibility(View.VISIBLE);
            messageRequestsBadge.setText(String.valueOf(count));
        } else {
            messageRequestsBanner.setVisibility(View.GONE);
        }
    }
    
    private void resolveCidsForConversations(List<String> fids) {
        if (fids.isEmpty()) return;
        CidFidManager cidFidManager = CidFidManager.getInstance(this);
        Map<String, String> fidToCid = cidFidManager.getCidsByFids(fids);
        if (fidToCid.isEmpty()) return;
        
        for (Conversation conv : conversations) {
            if (conv.getType() == ImType.P2P
                    && (conv.getDisplayName() == null || conv.getDisplayName().isEmpty())) {
                String cid = fidToCid.get(conv.getTargetId());
                if (cid != null && !cid.isEmpty()) {
                    conv.setDisplayName(cid);
                }
            }
        }
    }
    
    private void resolveCidForConversation(Conversation conv) {
        if (conv == null || conv.getType() != ImType.P2P) return;
        if (conv.getDisplayName() != null && !conv.getDisplayName().isEmpty()) return;
        if (conv.getTargetId() == null || conv.getTargetId().isEmpty()) return;
        
        CidFidManager cidFidManager = CidFidManager.getInstance(this);
        String cid = cidFidManager.getCidByFid(conv.getTargetId());
        if (cid != null && !cid.isEmpty()) {
            conv.setDisplayName(cid);
        }
    }
    
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

    private void confirmDeleteSelected() {
        List<Conversation> selected = adapter.getSelectedConversations();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_conversations_selected));
            return;
        }

        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete)
                .setMessage(getString(R.string.confirm_delete_conversations, selected.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteSelectedConversations(selected))
                .setNegativeButton(R.string.cancel, null));
    }

    private void deleteSelectedConversations(List<Conversation> selected) {
        if (imManager == null) return;

        for (Conversation conv : selected) {
            imManager.deleteConversation(conv.getId());
        }

        exitSelectionMode();
        loadConversations();
        ToastUtils.makeText(this, getString(R.string.conversations_deleted, selected.size()));
    }
    
    private void openNewChat() {
        Intent intent = new Intent(this, NewTalkActivity.class);
        startActivity(intent);
    }
    
    private void openChat(String targetFid, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.P2P.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, targetFid);
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, displayName);
        startActivity(intent);
    }
    
    private void showMoreMenu(View anchor) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_talk_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.menu_setup_home).setOnClickListener(v -> {
            popupWindow.dismiss();
            setupHome();
        });

        popupView.findViewById(R.id.menu_privacy_settings).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, P2pChatSettingsActivity.class));
        });

        popupView.findViewById(R.id.menu_manage_blacklist).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, BlacklistActivity.class));
        });

        popupView.findViewById(R.id.menu_newcomer_requests).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, NewcomerRequestsActivity.class));
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    private void setupHome() {
        startActivity(new Intent(this, com.fc.freer.data.ServerSetupActivity.class));
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
            checkDockSetup();
            loadConversations();
        });
    }

    private void checkDockSetup() {
        // Without a DOCK the chat still works in send-only mode (P2pHandler puts
        // directly into the recipient's DOCK), so no blocking dialog here — just
        // a banner explaining that replies can't be received until a DOCK is set.
        // Honor an already-known local DOCK first and suppress while a
        // registration TX is pending confirmation on-chain.
        boolean sendOnly = imManager != null && !hasDockInLocalHome()
                && !imManager.isChannelConfigured()
                && !imManager.isRegistrationPending();
        if (sendOnlyBanner != null) {
            sendOnlyBanner.setVisibility(sendOnly ? View.VISIBLE : View.GONE);
        }
    }

    private boolean hasDockInLocalHome() {
        KeyInfo mainKeyInfo = FidManager.getInstance().getMainKeyInfo();
        if (mainKeyInfo == null || mainKeyInfo.getHome() == null || mainKeyInfo.getHome().isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : mainKeyInfo.getHome().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && key.startsWith("DOCK") && value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterListener();
    }

}
