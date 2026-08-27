package com.fc.freer.im.adapter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

import java.util.ArrayList;
import java.util.List;

public class TeamInvitationAdapter extends RecyclerView.Adapter<TeamInvitationAdapter.ViewHolder> {

    private static final String TAG = "TeamInvitationAdapter";

    private final List<Team> items = new ArrayList<>();

    public interface ActionListener {
        void onJoin(Team team);
        void onDelete(Team team);
    }

    private ActionListener actionListener;

    public void setActionListener(ActionListener listener) {
        this.actionListener = listener;
    }

    public void setItems(List<Team> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void removeItem(String teamId) {
        for (int i = 0; i < items.size(); i++) {
            if (teamId.equals(items.get(i).getId())) {
                items.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_team_invitation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView avatarView;
        private final TextView nameText;
        private final TextView idText;
        private final TextView memberCountText;
        private final ImageButton joinBtn;
        private final ImageButton deleteBtn;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarView = itemView.findViewById(R.id.team_avatar);
            nameText = itemView.findViewById(R.id.team_name_text);
            idText = itemView.findViewById(R.id.team_id_text);
            memberCountText = itemView.findViewById(R.id.team_member_count_text);
            joinBtn = itemView.findViewById(R.id.btn_join);
            deleteBtn = itemView.findViewById(R.id.btn_delete);
        }

        void bind(Team team) {
            nameText.setText(team.getStdName() != null ? team.getStdName() : team.getId());
            idText.setText(team.getId() != null ? team.getId() : "");

            long memberNum = team.getMemberNum() != null ? team.getMemberNum() : 0;
            memberCountText.setText(itemView.getContext()
                    .getString(R.string.members_count, memberNum));

            loadAvatar(team.getId(), team.getOwner());

            joinBtn.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onJoin(team);
            });

            deleteBtn.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onDelete(team);
            });
        }

        /**
         * A team's tile, drawn from the team id and badged with its owner.
         * The owner may be unknown, which only costs the badge — the tile
         * is a hash and is always renderable.
         */
        private void loadAvatar(String teamId, String ownerFid) {
            avatarView.setImageResource(R.drawable.ic_team);
            if (teamId == null || teamId.isEmpty()) return;
            avatarView.setClipToOutline(false);
            avatarView.setBackground(null);
            Context context = itemView.getContext();
            int sizePx = avatarView.getWidth() > 0
                    ? avatarView.getWidth()
                    : (int) (48 * context.getResources().getDisplayMetrics().density);
            new Thread(() -> {
                try {
                    AvatarManager avatarManager = AvatarManager.getInstance(context);
                    Bitmap bitmap = avatarManager.getGroupAvatarBitmap(teamId, ownerFid, sizePx);
                    if (bitmap != null && context instanceof Activity) {
                        ((Activity) context).runOnUiThread(() -> avatarView.setImageBitmap(bitmap));
                    }
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error loading avatar: %s", e.getMessage());
                }
            }).start();
        }
    }
}
