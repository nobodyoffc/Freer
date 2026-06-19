package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;

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

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.SquareOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.ConversationAdapter;
import com.fc.freer.im.handler.SquareHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for Square conversations list.
 * Squares are completely open for everyone - public, unencrypted messages.
 */
public class SquareActivity extends BaseCryptoActivity implements ConversationAdapter.OnConversationClickListener {
    private static final String TAG = "SquareActivity";

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

    private ImManager imManager;
    private String liveFid;
    private SquareSyncManager squareSyncManager;
    private OnBackPressedCallback selectionBackCallback;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_talk;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.square);
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

        // Hide the message requests banner (not used for squares)
        View messageRequestsBanner = findViewById(R.id.message_requests_banner);
        if (messageRequestsBanner != null) {
            messageRequestsBanner.setVisibility(View.GONE);
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
            launchQuitGroups();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in group list
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
                squareSyncManager = new SquareSyncManager(liveFid);
            }
        }
    }

    private final ImManager.ImListener imListener = new ImManager.ImListener() {
        @Override
        public void onMessageReceived(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.SQUARE) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onMessageSent(com.fc.fc_ajdk.data.fcData.ImMessage message) {
            if (message.getType() == ImType.SQUARE) {
                runOnUiThread(() -> loadConversations());
            }
        }

        @Override
        public void onConversationUpdated(Conversation conversation) {
            if (conversation.getType() == ImType.SQUARE) {
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

    private void triggerSquareSync() {
        if (squareSyncManager == null || imManager == null) return;

        new Thread(() -> {
            squareSyncManager.sync(
                    imManager.getFapiClient(),
                    imManager.getSquareHandler(),
                    imManager.getConversationsDb(),
                    imManager.getDockRegistry(),
                    new SquareSyncManager.SyncCallback() {
                        @Override
                        public void onSyncComplete(int newCount, int updatedCount) {
                            if (newCount > 0 || updatedCount > 0) {
                                runOnUiThread(() -> loadConversations());
                            }
                        }

                        @Override
                        public void onSyncError(String error) {
                            TimberLogger.e(TAG, "Square sync error: %s", error);
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

        SquareHandler squareHandler = imManager.getSquareHandler();
        for (Conversation conv : all) {
            if (conv.getType() == ImType.SQUARE) {
                if (conv.getLeftGroup() != null && conv.getLeftGroup()) continue;
                resolveSquareInfo(conv, squareHandler);
                conversations.add(conv);
            }
        }

        adapter.setUnavailableTargetIds(imManager.getUnavailableTargetIds("square"));
        filterConversations(searchEditText.getText().toString());
        swipeRefreshLayout.setRefreshing(false);
    }

    private void resolveSquareInfo(Conversation conv, SquareHandler squareHandler) {
        if (squareHandler == null || conv.getTargetId() == null) return;
        Square square = squareHandler.getSquare(conv.getTargetId());
        if (square == null) return;

        if (square.getName() != null) {
            conv.setDisplayName(square.getName());
        }
        if (square.getNamers() != null && !square.getNamers().isEmpty()) {
            String lastNamer = square.getNamers().get(square.getNamers().size() - 1);
            conv.setAvatarDid(lastNamer);
        }
        conv.setMemberNum(square.getMemberNum());
        conv.settCdd(square.gettCdd());
    }

    private void refreshConversations() {
        triggerSquareSync();
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
            SquareHandler squareHandler = imManager.getSquareHandler();
            resolveSquareInfo(updated, squareHandler);
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

    private void launchQuitGroups() {
        List<Conversation> selected = adapter.getSelectedConversations();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_squares_selected));
            return;
        }

        ArrayList<String> groupIds = new ArrayList<>();
        for (Conversation conv : selected) {
            if (conv.getTargetId() != null) {
                groupIds.add(conv.getTargetId());
            }
        }

        if (groupIds.isEmpty()) return;

        exitSelectionMode();

        new AlertDialog.Builder(this)
                .setTitle(R.string.quit_squares)
                .setMessage(R.string.confirm_quit_square)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    quitGroups(groupIds);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                .show();
    }

    private void quitGroups(List<String> groupIds) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        SquareOpData opData = SquareOpData.makeLeave(new ArrayList<>(groupIds));
        Feip feip = Feip.fromName("Square");
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
                            markGroupConversationsAsLeft(groupIds);
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SquareActivity.this,
                                        getString(R.string.squares_quit_successfully));
                                loadConversations();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(SquareActivity.this, errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void markGroupConversationsAsLeft(List<String> groupIds) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) return;

        ImManager im = setting.getImManager();
        for (String gid : groupIds) {
            String convId = ImType.SQUARE.name() + "_" + gid;
            Conversation conv = im.getConversationsDb().get(convId);
            if (conv != null) {
                conv.setLeftGroup(true);
                im.getConversationsDb().put(convId, conv);
            }
            if (im.getDockRegistry() != null) {
                im.getDockRegistry().unregister(SQUARE, gid);
            }
        }
    }

    // ========== More Menu ==========

    private void showMoreMenu(View anchor) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_group_more_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.menu_left_groups).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, LeftSquareActivity.class));
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    // ========== "+" Menu ==========

    private void showPlusMenu(View anchor) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_group_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.menu_join_group).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, JoinSquareActivity.class));
        });

        popupView.findViewById(R.id.menu_create_group).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, CreateSquareActivity.class));
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    private void openChat(String groupId, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.SQUARE.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, groupId);
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
            if (squareSyncManager == null && liveFid != null) {
                squareSyncManager = new SquareSyncManager(liveFid);
            }
            loadConversations();
            triggerSquareSync();
            retryFailedDocks();
        });
    }

    private void retryFailedDocks() {
        if (imManager == null || !imManager.hasFailedDocks("square")) return;
        imManager.retryFailedDocks("square", reconnected -> runOnUiThread(() -> {
            adapter.setUnavailableTargetIds(imManager.getUnavailableTargetIds("square"));
            adapter.notifyDataSetChanged();
        }));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterListener();
    }
}
