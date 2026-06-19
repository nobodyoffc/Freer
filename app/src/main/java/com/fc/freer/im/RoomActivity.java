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

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.ConversationAdapter;
import com.fc.freer.im.handler.RoomHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.RemindDialog;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for Room conversations list.
 * Shows all locally-defined room conversations (encrypted with room symkey).
 */
public class RoomActivity extends BaseCryptoActivity implements ConversationAdapter.OnConversationClickListener {
    private static final String TAG = "RoomActivity";
    
    private RecyclerView conversationsRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private EditText searchEditText;
    private View clearSearchIcon;
    private ImageButton clearButton;
    private ImageButton newChatButton;
    private ImageButton deleteButton;
    
    private ConversationAdapter adapter;
    private final List<Conversation> conversations = new ArrayList<>();
    private final List<Conversation> filteredConversations = new ArrayList<>();
    
    private ImManager imManager;
    private String liveFid;
    private OnBackPressedCallback selectionBackCallback;
    
    @Override
    protected int getLayoutId() {
        return R.layout.activity_talk;
    }
    
    @Override
    protected String getActivityTitle() {
        return getString(R.string.room);
    }
    
    @Override
    protected void initializeViews() {
        conversationsRecyclerView = findViewById(R.id.conversations_recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        searchEditText = findViewById(R.id.search_edit_text);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        clearButton = findViewById(R.id.clear_button);
        newChatButton = findViewById(R.id.new_chat_button);
        deleteButton = findViewById(R.id.delete_button);
        
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

        ImageButton moreButton = findViewById(R.id.more_button);
        if (moreButton != null) {
            moreButton.setVisibility(View.VISIBLE);
            moreButton.setOnClickListener(this::showMoreMenu);
        }

        newChatButton.setOnClickListener(this::launchCreateRoom);

        if (deleteButton != null) {
            deleteButton.setOnClickListener(v -> {
                hideKeyboard();
                launchLeaveRooms();
            });
        }
    }
    
    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }
    
    private void initManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            liveFid = setting.getMainFid();
            imManager = setting.getImManager();
            if (imManager == null) {
                ToastUtils.makeText(this, getString(R.string.im_not_ready_try_later));
            } else {
                registerListener();
            }
        }
    }

    private final ImManager.ImListener imListener = new ImManager.ImListener() {
        @Override
        public void onMessageReceived(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.ROOM) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onMessageSent(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.ROOM) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onConversationUpdated(Conversation conversation) {
            if (conversation.getType() == ImType.ROOM) {
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
        public void onRoomInviteReceived(PendingIssue issue) {
            runOnUiThread(() -> showRoomInviteDialog(issue));
        }
    };

    private void showRoomInviteDialog(PendingIssue issue) {
        if (isFinishing() || isDestroyed()) return;
        if (imManager == null) return;

        RoomInviteDialog.show(this, issue, new RoomInviteDialog.DialogCallback() {
            @Override
            public void onAccepted(PendingIssue issue) {
                PendingIssueManager m = imManager.getPendingIssueManager();
                if (m != null && issue.getId() != null) {
                    m.acceptRoomInvite(issue.getId());
                }
                ToastUtils.showInfo(RoomActivity.this, getString(R.string.room_invite_accepted));
                loadConversations();
            }

            @Override
            public void onRejected(PendingIssue issue) {
                PendingIssueManager m = imManager.getPendingIssueManager();
                if (m != null && issue.getId() != null) {
                    m.rejectRoomInvite(issue.getId());
                }
                ToastUtils.showInfo(RoomActivity.this, getString(R.string.room_invite_rejected));
            }

            @Override
            public void onDeferred(PendingIssue issue) {
                TimberLogger.d(TAG, "Room invite deferred: %s", issue.getId());
            }
        });
    }

    private void registerListener() {
        if (imManager != null) imManager.addListener(imListener);
    }

    private void unregisterListener() {
        if (imManager != null) imManager.removeListener(imListener);
    }
    
    private void loadConversations() {
        if (imManager == null) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }
        
        List<Conversation> all = imManager.getConversations();
        conversations.clear();

        RoomHandler roomHandler = imManager.getRoomHandler();
        for (Conversation conv : all) {
            if (conv.getType() == ImType.ROOM) {
                if (conv.getLeftGroup() != null && conv.getLeftGroup()) continue;
                resolveRoomInfo(conv, roomHandler);
                conversations.add(conv);
            }
        }
        
        adapter.setUnavailableTargetIds(imManager.getUnavailableTargetIds("room"));
        filterConversations(searchEditText.getText().toString());
        swipeRefreshLayout.setRefreshing(false);
    }

    private void resolveRoomInfo(Conversation conv, RoomHandler roomHandler) {
        if (roomHandler == null || conv.getTargetId() == null) return;
        Room room = roomHandler.getRoom(conv.getTargetId());
        if (room == null) return;

        if (room.getName() != null) {
            conv.setDisplayName(room.getName());
        }
        if (room.getOwner() != null) {
            conv.setAvatarDid(room.getOwner());
        }
        conv.setMemberNum((long) room.getMemberCount());
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
        if (imManager != null) {
            RoomHandler roomHandler = imManager.getRoomHandler();
            resolveRoomInfo(updated, roomHandler);
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
        if (deleteButton != null) {
            deleteButton.setEnabled(selectedCount > 0);
        }
    }

    private void enterSelectionMode() {
        adapter.setSelectionMode(true);
        if (deleteButton != null) {
            deleteButton.setVisibility(View.VISIBLE);
            deleteButton.setEnabled(false);
        }
        selectionBackCallback.setEnabled(true);
        updateClearButtonVisibility();
    }

    private void exitSelectionMode() {
        adapter.setSelectionMode(false);
        if (deleteButton != null) {
            deleteButton.setVisibility(View.GONE);
        }
        selectionBackCallback.setEnabled(false);
        updateClearButtonVisibility();
    }

    private void updateClearButtonVisibility() {
        boolean hasSearchText = searchEditText.getText().length() > 0;
        boolean inSelectionMode = adapter.isSelectionMode();
        clearButton.setVisibility((hasSearchText || inSelectionMode) ? View.VISIBLE : View.GONE);
    }

    private void launchLeaveRooms() {
        List<Conversation> selected = adapter.getSelectedConversations();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_rooms_selected));
            return;
        }

        exitSelectionMode();

        RoomHandler roomHandler = (imManager != null) ? imManager.getRoomHandler() : null;
        int ownedCount = 0;
        if (roomHandler != null && liveFid != null) {
            for (Conversation conv : selected) {
                if (conv.getTargetId() != null) {
                    Room room = roomHandler.getRoom(conv.getTargetId());
                    if (room != null && room.isOwner(liveFid)) {
                        ownedCount++;
                    }
                }
            }
        }

        if (ownedCount == selected.size()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.close_room)
                    .setMessage(R.string.confirm_close_rooms)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        hideKeyboard();
                        if (imManager != null) {
                            for (Conversation conv : selected) {
                                if (conv.getTargetId() != null) {
                                    imManager.closeRoom(conv.getTargetId());
                                    markRoomConversationAsLeft(conv.getTargetId());
                                }
                            }
                            ToastUtils.makeText(this, getString(R.string.rooms_closed));
                            loadConversations();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                    .show();
        } else if (ownedCount > 0) {
            new RemindDialog(this, getString(R.string.cannot_leave_own_teams_or_rooms), true).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.leave_room)
                    .setMessage(R.string.confirm_leave_room)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        hideKeyboard();
                        if (imManager != null) {
                            for (Conversation conv : selected) {
                                if (conv.getTargetId() != null) {
                                    imManager.leaveRoom(conv.getTargetId());
                                    markRoomConversationAsLeft(conv.getTargetId());
                                }
                            }
                            ToastUtils.makeText(this, getString(R.string.left_room));
                            loadConversations();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                    .show();
        }
    }

    private void markRoomConversationAsLeft(String roomId) {
        if (imManager == null) return;
        imManager.markRoomConversationAsLeft(roomId);
    }

    // ========== More Menu ==========

    private void showMoreMenu(View anchor) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_room_more_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.menu_closed_rooms).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, ClosedRoomActivity.class));
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    // ========== Create Room ==========

    private void launchCreateRoom(View anchor) {
        hideKeyboard();
        Intent intent = new Intent(this, CreateRoomActivity.class);
        startActivity(intent);
    }
    
    private void openChat(String roomId, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.ROOM.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, roomId);
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
            loadConversations();
            retryFailedDocks();
        });
    }

    private void retryFailedDocks() {
        if (imManager == null || !imManager.hasFailedDocks("room")) return;
        imManager.retryFailedDocks("room", reconnected -> runOnUiThread(() -> {
            adapter.setUnavailableTargetIds(imManager.getUnavailableTargetIds("room"));
            adapter.notifyDataSetChanged();
        }));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterListener();
    }
}
