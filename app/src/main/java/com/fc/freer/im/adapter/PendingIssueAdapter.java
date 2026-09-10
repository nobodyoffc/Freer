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

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.PendingIssue;
import com.fc.freer.manager.AvatarManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PendingIssueAdapter extends RecyclerView.Adapter<PendingIssueAdapter.ViewHolder> {

    private static final String TAG = "PendingIssueAdapter";
    private final List<PendingIssue> items = new ArrayList<>();
    private ActionListener actionListener;
    private OnItemClickListener itemClickListener;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public interface ActionListener {
        void onAccept(PendingIssue issue);
        void onReject(PendingIssue issue);
    }

    public interface OnItemClickListener {
        void onItemClick(PendingIssue issue);
    }

    public void setActionListener(ActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setItems(List<PendingIssue> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pending_issue, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PendingIssue issue = items.get(position);
        holder.bind(issue);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView avatarView;
        private final TextView peerFidText;
        private final TextView descriptionText;
        private final TextView timeText;
        private final ImageButton acceptBtn;
        private final ImageButton rejectBtn;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarView = itemView.findViewById(R.id.peer_avatar);
            peerFidText = itemView.findViewById(R.id.peer_fid_text);
            descriptionText = itemView.findViewById(R.id.issue_description_text);
            timeText = itemView.findViewById(R.id.issue_time_text);
            acceptBtn = itemView.findViewById(R.id.btn_accept);
            rejectBtn = itemView.findViewById(R.id.btn_reject);
        }

        void bind(PendingIssue issue) {
            peerFidText.setText(issue.getPeerFid());

            loadAvatar(issue.getPeerFid());

            String typeLabel;
            switch (issue.getIssueType()) {
                case STRANGER_PEER:
                    typeLabel = itemView.getContext().getString(R.string.stranger_peer_request);
                    break;
                case ROOM_INVITE:
                    typeLabel = itemView.getContext().getString(R.string.room_invite_request);
                    break;
                case TEAM_INVITE:
                    typeLabel = itemView.getContext().getString(R.string.team_invite_notification_title);
                    break;
                case CONSENSUS_CHANGE:
                    typeLabel = itemView.getContext().getString(R.string.consensus_change_request);
                    break;
                default:
                    typeLabel = issue.getIssueType().name();
                    break;
            }

            if (issue.getNote() != null && !issue.getNote().isEmpty()) {
                typeLabel = typeLabel + " - " + issue.getNote();
            }
            descriptionText.setText(typeLabel);

            if (issue.getCreatedAt() != null) {
                timeText.setText(TIME_FORMAT.format(new Date(issue.getCreatedAt())));
            } else {
                timeText.setText("");
            }

            // A consensus change is not a yes/no on this row: the member has to read the
            // documents and choose between signing, postponing and leaving, so the row opens
            // the detail screen instead of offering a one-tap decision.
            boolean isPending = issue.getStatus() == PendingIssue.IssueStatus.PENDING;
            boolean inlineDecidable = isPending
                    && issue.getIssueType() != PendingIssue.IssueType.CONSENSUS_CHANGE
                    && issue.getIssueType() != PendingIssue.IssueType.TEAM_INVITE;
            acceptBtn.setVisibility(inlineDecidable ? View.VISIBLE : View.GONE);
            rejectBtn.setVisibility(inlineDecidable ? View.VISIBLE : View.GONE);

            if (!isPending && issue.getStatus() != null) {
                String statusStr = issue.getStatus().name().toLowerCase(Locale.ROOT);
                descriptionText.setText(typeLabel + " - " + statusStr);
            }

            acceptBtn.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onAccept(issue);
            });

            rejectBtn.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onReject(issue);
            });

            itemView.setOnClickListener(v -> {
                if (itemClickListener != null) itemClickListener.onItemClick(issue);
            });
        }

        private void loadAvatar(String fid) {
            if (fid == null || fid.isEmpty()) {
                avatarView.setImageResource(R.drawable.ic_person);
                return;
            }
            Context context = itemView.getContext();
            new Thread(() -> {
                try {
                    AvatarManager avatarManager = AvatarManager.getInstance(context);
                    Bitmap bitmap = avatarManager.getAvatarBitmap(fid);
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
