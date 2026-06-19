package com.fc.freer.im;

import android.content.Intent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.ConversationAdapter;
import com.fc.freer.im.handler.SquareHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;

import java.util.ArrayList;
import java.util.List;

public class LeftSquareActivity extends BaseCryptoActivity implements ConversationAdapter.OnConversationClickListener {

    private RecyclerView conversationsRecyclerView;
    private TextView emptyText;

    private ConversationAdapter adapter;
    private final List<Conversation> conversations = new ArrayList<>();

    private ImManager imManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_archive_list;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.left_squares);
    }

    @Override
    protected void initializeViews() {
        conversationsRecyclerView = findViewById(R.id.conversations_recycler_view);
        emptyText = findViewById(R.id.empty_text);

        ImageButton clearBtn = findViewById(R.id.clear_button);
        ImageButton deleteBtn = findViewById(R.id.delete_button);
        if (clearBtn != null) clearBtn.setVisibility(View.GONE);
        if (deleteBtn != null) deleteBtn.setVisibility(View.GONE);

        adapter = new ConversationAdapter(conversations, this);
        conversationsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        conversationsRecyclerView.setAdapter(adapter);

        initManager();
        loadConversations();
    }

    @Override
    protected void setupButtons() {
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void initManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            imManager = setting.getImManager();
        }
    }

    private void loadConversations() {
        conversations.clear();

        if (imManager != null) {
            SquareHandler squareHandler = imManager.getSquareHandler();
            List<Conversation> all = imManager.getConversations();
            for (Conversation conv : all) {
                if (conv.getType() == ImType.SQUARE
                        && conv.getLeftGroup() != null && conv.getLeftGroup()) {
                    resolveSquareInfo(conv, squareHandler);
                    conversations.add(conv);
                }
            }
        }

        adapter.notifyDataSetChanged();
        emptyText.setVisibility(conversations.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(R.string.no_left_squares);
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
    }

    @Override
    public void onConversationClick(Conversation conversation) {
        hideKeyboard();
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.SQUARE.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, conversation.getTargetId());
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, conversation.getDisplayName());
        startActivity(intent);
    }

    @Override
    public void onConversationLongClick(Conversation conversation) {
        hideKeyboard();
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (imManager == null) initManager();
        loadConversations();
    }
}
