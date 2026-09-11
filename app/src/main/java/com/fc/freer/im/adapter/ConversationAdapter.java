package com.fc.freer.im.adapter;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RecyclerView adapter for conversation list.
 */
public class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder> {
    
    private final List<Conversation> conversations;
    private final OnConversationClickListener listener;
    private boolean selectionMode = false;
    private final Set<String> selectedIds = new HashSet<>();
    private final Set<String> unavailableTargetIds = new HashSet<>();
    private final Set<String> pendingTargetIds = new HashSet<>();
    
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd", Locale.getDefault());
    
    public interface OnConversationClickListener {
        void onConversationClick(Conversation conversation);
        void onConversationLongClick(Conversation conversation);
        void onSelectionChanged(int selectedCount);
    }
    
    public ConversationAdapter(List<Conversation> conversations, OnConversationClickListener listener) {
        this.conversations = conversations;
        this.listener = listener;
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedIds.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggleSelection(String conversationId) {
        if (selectedIds.contains(conversationId)) {
            selectedIds.remove(conversationId);
        } else {
            selectedIds.add(conversationId);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedIds.size());
        }
    }

    public void selectAll() {
        selectedIds.clear();
        for (Conversation conv : conversations) {
            if (conv.getId() != null) {
                selectedIds.add(conv.getId());
            }
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedIds.size());
        }
    }

    public void deselectAll() {
        selectedIds.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedIds.size());
        }
    }

    public List<Conversation> getSelectedConversations() {
        List<Conversation> selected = new ArrayList<>();
        for (Conversation conv : conversations) {
            if (conv.getId() != null && selectedIds.contains(conv.getId())) {
                selected.add(conv);
            }
        }
        return selected;
    }

    public int getSelectedCount() {
        return selectedIds.size();
    }

    /**
     * Set target IDs whose DOCK service is unavailable.
     * These will show a red indicator on the avatar.
     * Does NOT call notifyDataSetChanged — caller should trigger refresh.
     */
    public void setUnavailableTargetIds(Set<String> targetIds) {
        unavailableTargetIds.clear();
        if (targetIds != null) {
            unavailableTargetIds.addAll(targetIds);
        }
    }

    /**
     * Set target IDs for conversations that are pending blockchain confirmation.
     * These are rendered grayed-out and non-interactive.
     * Does NOT call notifyDataSetChanged — caller should trigger refresh.
     */
    public void setPendingTargetIds(Set<String> targetIds) {
        pendingTargetIds.clear();
        if (targetIds != null) {
            pendingTargetIds.addAll(targetIds);
        }
    }

    public boolean isPending(String targetId) {
        return targetId != null && pendingTargetIds.contains(targetId);
    }
    
    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new ConversationViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder holder, int position) {
        Conversation conversation = conversations.get(position);
        boolean isSelected = conversation.getId() != null && selectedIds.contains(conversation.getId());
        boolean isDockUnavailable = conversation.getTargetId() != null
                && unavailableTargetIds.contains(conversation.getTargetId());
        boolean isPending = conversation.getTargetId() != null
                && pendingTargetIds.contains(conversation.getTargetId());
        holder.bind(conversation, listener, selectionMode, isSelected, isDockUnavailable, isPending, this);
    }
    
    @Override
    public int getItemCount() {
        return conversations.size();
    }
    
    static class ConversationViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox checkboxSelect;
        private final ImageView avatar;
        private final View onlineIndicator;
        private final TextView name;
        private final TextView time;
        private final TextView preview;
        private final TextView unreadBadge;
        private final ImageView typeIndicator;
        private final View metricsLayout;
        private final TextView textMemberNum;
        private final ImageView iconHot;
        private final TextView textTcdd;
        private final ImageView iconGood;
        private final TextView textTrate;
        private final TextView textShortId;
        
        ConversationViewHolder(@NonNull View itemView) {
            super(itemView);
            checkboxSelect = itemView.findViewById(R.id.checkbox_select);
            avatar = itemView.findViewById(R.id.avatar);
            onlineIndicator = itemView.findViewById(R.id.online_indicator);
            name = itemView.findViewById(R.id.name);
            time = itemView.findViewById(R.id.time);
            preview = itemView.findViewById(R.id.preview);
            unreadBadge = itemView.findViewById(R.id.unread_badge);
            typeIndicator = itemView.findViewById(R.id.type_indicator);
            metricsLayout = itemView.findViewById(R.id.group_metrics_layout);
            textMemberNum = itemView.findViewById(R.id.text_member_num);
            iconHot = itemView.findViewById(R.id.icon_hot);
            textTcdd = itemView.findViewById(R.id.text_tcdd);
            iconGood = itemView.findViewById(R.id.icon_good);
            textTrate = itemView.findViewById(R.id.text_trate);
            textShortId = itemView.findViewById(R.id.text_short_id);
        }
        
        void bind(Conversation conversation, OnConversationClickListener listener,
                  boolean selectionMode, boolean isSelected, boolean isDockUnavailable,
                  boolean isPending, ConversationAdapter adapter) {
            checkboxSelect.setVisibility(selectionMode && !isPending ? View.VISIBLE : View.GONE);
            checkboxSelect.setChecked(isSelected);
            // Name
            // Show the CID (resolved into displayName) when available, otherwise
            // fall back to the full FID. Never truncate so the name is always a
            // complete CID or FID.
            String displayName = conversation.getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = conversation.getTargetId();
            }
            com.fc.freer.nobody.NobodyUi.setName(name, conversation.getTargetId(), displayName);
            
            // Avatar
            //
            // A P2P thread's target *is* a FID, so it gets the person's
            // circle. A group's target is a room id or a txid, which
            // AvatarMaker cannot read and must never be handed: it would
            // composite a human face out of an identifier. Groups get the
            // rounded-square tile drawn from their own id instead, badged
            // with the owner — so a group can no longer be mistaken for a
            // DM with the person who happens to own it.
            String targetId = conversation.getTargetId();
            boolean isGroup = conversation.getType() == ImType.SQUARE
                    || conversation.getType() == ImType.TEAM
                    || conversation.getType() == ImType.ROOM;

            // The silhouette, reset on every bind because holders are
            // recycled. A person is clipped to the oval background's
            // outline as before. A group is not clipped at all: the tile
            // bitmap already carries its own rounded-square corners, and
            // leaving the oval outline in place would clip it straight
            // back into a circle — the exact confusion the tile exists to
            // end. Dropping the clip rather than swapping in a rounded
            // outline also keeps this off `clipToOutline`, which only
            // applies under hardware rendering and so cannot be checked
            // by a test that draws to a software canvas.
            if (isGroup) {
                avatar.setBackground(null);
                avatar.setClipToOutline(false);
            } else {
                avatar.setBackgroundResource(R.drawable.avatar_background);
                avatar.setClipToOutline(true);
            }

            AvatarManager avatarManager = AvatarManager.getInstance(itemView.getContext());
            Bitmap avatarBitmap = null;
            if (isGroup && targetId != null && !targetId.isEmpty()) {
                int sizePx = avatar.getWidth() > 0
                        ? avatar.getWidth()
                        : (int) (48 * itemView.getContext().getResources()
                                .getDisplayMetrics().density);
                avatarBitmap = avatarManager.getGroupAvatarBitmap(
                        targetId, conversation.getAvatarDid(), sizePx);
            } else if (conversation.getType() == ImType.P2P
                    && targetId != null && !targetId.isEmpty()) {
                avatarBitmap = avatarManager.getAvatarBitmap(targetId);
            }

            if (avatarBitmap != null) {
                avatar.setImageBitmap(avatarBitmap);
                avatar.setPadding(0, 0, 0, 0);
                avatar.setImageTintList(null);
            } else {
                int iconRes = R.drawable.ic_person;
                if (conversation.getType() != null) {
                    switch (conversation.getType()) {
                        case SQUARE -> iconRes = R.drawable.ic_group;
                        case TEAM -> iconRes = R.drawable.ic_team;
                        case ROOM -> iconRes = R.drawable.ic_room;
                    }
                }
                setDefaultAvatar(iconRes);
            }

            // Gray out entire item for pending-confirmation items
            float itemAlpha = isPending ? 0.45f : 1.0f;
            itemView.setAlpha(itemAlpha);

            // Gray out avatar for disconnected groups (only if not already fully grayed for pending)
            avatar.setAlpha(isDockUnavailable && !isPending ? 0.4f : 1.0f);

            // Short ID under avatar for GROUP/TEAM/ROOM
            if (conversation.getType() == ImType.SQUARE
                    || conversation.getType() == ImType.TEAM
                    || conversation.getType() == ImType.ROOM) {
                String tid = conversation.getTargetId();
                if (tid != null && tid.length() > 8) {
                    textShortId.setText(tid.substring(0, 4) + "..." + tid.substring(tid.length() - 4));
                    textShortId.setVisibility(View.VISIBLE);
                } else if (tid != null) {
                    textShortId.setText(tid);
                    textShortId.setVisibility(View.VISIBLE);
                } else {
                    textShortId.setVisibility(View.GONE);
                }
            } else {
                textShortId.setVisibility(View.GONE);
            }
            
            // Time
            if (conversation.getLastMessageTime() != null) {
                time.setText(formatTime(conversation.getLastMessageTime()));
                time.setVisibility(View.VISIBLE);
            } else {
                time.setVisibility(View.GONE);
            }
            
            // Preview — pending confirmation takes priority over dock-disconnected
            if (isPending) {
                preview.setText(R.string.pending_tx_confirmation);
                preview.setTextColor(itemView.getContext().getColor(R.color.hint));
            } else if (isDockUnavailable) {
                preview.setText(R.string.dock_disconnected);
                preview.setTextColor(itemView.getContext().getColor(R.color.warning));
            } else {
                String previewText = conversation.getLastMessageContent();
                if (previewText == null || previewText.isEmpty()) {
                    previewText = "";
                }
                if (conversation.getLastMessageSenderName() != null &&
                        conversation.getType() != ImType.P2P) {
                    previewText = conversation.getLastMessageSenderName() + ": " + previewText;
                }
                preview.setText(previewText);
                preview.setTextColor(itemView.getContext().getColor(R.color.hint));
            }
            
            // Unread badge
            Integer unread = conversation.getUnreadCount();
            if (unread != null && unread > 0) {
                unreadBadge.setVisibility(View.VISIBLE);
                unreadBadge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                unreadBadge.setVisibility(View.GONE);
            }
            
            // Online indicator (P2P only)
            if (conversation.getType() == ImType.P2P &&
                    conversation.getIsOnline() != null && conversation.getIsOnline()) {
                onlineIndicator.setVisibility(View.VISIBLE);
            } else {
                onlineIndicator.setVisibility(View.GONE);
            }
            
            // Type indicator and group/team/room metrics
            if (conversation.getType() == ImType.SQUARE || conversation.getType() == ImType.TEAM
                    || conversation.getType() == ImType.ROOM) {
                typeIndicator.setVisibility(View.GONE);
                metricsLayout.setVisibility(View.VISIBLE);
                long memberNum = conversation.getMemberNum() != null ? conversation.getMemberNum() : 0;
                textMemberNum.setText(String.valueOf(memberNum));

                if (conversation.getType() == ImType.TEAM) {
                    long tCdd = conversation.gettCdd() != null ? conversation.gettCdd() : 0;
                    textTcdd.setText(String.valueOf(tCdd));
                    textTcdd.setVisibility(View.VISIBLE);
                    iconHot.setVisibility(View.VISIBLE);

                    Float tRate = conversation.gettRate();
                    if (tRate != null) {
                        iconGood.setVisibility(View.VISIBLE);
                        textTrate.setVisibility(View.VISIBLE);
                        textTrate.setText(String.format(Locale.getDefault(), "%.1f", tRate));
                    } else {
                        iconGood.setVisibility(View.GONE);
                        textTrate.setVisibility(View.GONE);
                    }
                } else {
                    iconHot.setVisibility(View.GONE);
                    textTcdd.setVisibility(View.GONE);
                    iconGood.setVisibility(View.GONE);
                    textTrate.setVisibility(View.GONE);
                }
            } else if (conversation.getType() != ImType.P2P) {
                metricsLayout.setVisibility(View.GONE);
                typeIndicator.setVisibility(View.VISIBLE);
            } else {
                metricsLayout.setVisibility(View.GONE);
                typeIndicator.setVisibility(View.GONE);
            }
            
            itemView.setOnClickListener(v -> {
                if (isPending) return; // non-interactive while pending
                if (selectionMode) {
                    adapter.toggleSelection(conversation.getId());
                } else if (listener != null) {
                    listener.onConversationClick(conversation);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (isPending) return true; // consume but do nothing
                if (listener != null) {
                    listener.onConversationLongClick(conversation);
                }
                return true;
            });
        }
        
        private void setDefaultAvatar(int iconRes) {
            avatar.setImageResource(iconRes);
            int pad = (int) (8 * itemView.getContext().getResources().getDisplayMetrics().density);
            avatar.setPadding(pad, pad, pad, pad);
            avatar.setImageTintList(android.content.res.ColorStateList.valueOf(
                    itemView.getContext().getColor(R.color.hint)));
        }
        
        private String formatTime(long timestamp) {
            Date date = new Date(timestamp);
            Date now = new Date();
            
            // Same day - show time
            if (isSameDay(date, now)) {
                return TIME_FORMAT.format(date);
            }
            
            // Different day - show date
            return DATE_FORMAT.format(date);
        }
        
        private boolean isSameDay(Date date1, Date date2) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
            return fmt.format(date1).equals(fmt.format(date2));
        }
    }
}
