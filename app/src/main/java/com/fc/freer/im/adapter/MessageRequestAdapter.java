package com.fc.freer.im.adapter;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.freer.R;
import com.fc.freer.im.PendingIssue;

import java.util.List;

public class MessageRequestAdapter extends RecyclerView.Adapter<MessageRequestAdapter.ViewHolder> {

    public interface OnRequestActionListener {
        void onAccept(PendingIssue issue);
        void onReject(PendingIssue issue);
        void onItemClick(PendingIssue issue);
    }

    private final List<PendingIssue> issues;
    private final OnRequestActionListener listener;

    public MessageRequestAdapter(List<PendingIssue> issues, OnRequestActionListener listener) {
        this.issues = issues;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message_request, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PendingIssue issue = issues.get(position);
        holder.bind(issue, listener);
    }

    @Override
    public int getItemCount() {
        return issues.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView peerFidText;
        private final TextView messageCountText;
        private final TextView timestampText;
        private final TextView messagePreviewText;
        private final ImageButton acceptBtn;
        private final ImageButton rejectBtn;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            peerFidText = itemView.findViewById(R.id.request_peer_fid);
            messageCountText = itemView.findViewById(R.id.request_message_count);
            timestampText = itemView.findViewById(R.id.request_timestamp);
            messagePreviewText = itemView.findViewById(R.id.request_message_preview);
            acceptBtn = itemView.findViewById(R.id.btn_accept);
            rejectBtn = itemView.findViewById(R.id.btn_reject);
        }

        void bind(PendingIssue issue, OnRequestActionListener listener) {
            String fid = issue.getPeerFid();
            if (fid != null && fid.length() > 12) {
                com.fc.freer.nobody.NobodyUi.setName(peerFidText, fid,
                        fid.substring(0, 6) + "..." + fid.substring(fid.length() - 4));
            } else {
                com.fc.freer.nobody.NobodyUi.setName(peerFidText, fid, fid);
            }

            int count = issue.getMessageCount();
            messageCountText.setText(itemView.getContext()
                    .getString(R.string.stranger_messages_count, count));

            if (issue.getCreatedAt() != null) {
                CharSequence relative = DateUtils.getRelativeTimeSpanString(
                        issue.getCreatedAt(), System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
                timestampText.setText(relative);
            } else {
                timestampText.setText("");
            }

            PendingIssue.StrangerPeerData data = issue.getDataAs(PendingIssue.StrangerPeerData.class);
            if (data != null && data.firstMessageContent != null && !data.firstMessageContent.isEmpty()) {
                messagePreviewText.setVisibility(View.VISIBLE);
                messagePreviewText.setText(data.firstMessageContent);
            } else {
                messagePreviewText.setVisibility(View.GONE);
            }

            acceptBtn.setOnClickListener(v -> {
                if (listener != null) listener.onAccept(issue);
            });
            rejectBtn.setOnClickListener(v -> {
                if (listener != null) listener.onReject(issue);
            });
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(issue);
            });
        }
    }
}
