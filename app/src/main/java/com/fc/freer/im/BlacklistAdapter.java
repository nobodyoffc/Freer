package com.fc.freer.im;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.freer.R;

import java.util.List;

public class BlacklistAdapter extends RecyclerView.Adapter<BlacklistAdapter.ViewHolder> {

    public interface OnRemoveListener {
        void onRemove(String fid, int position);
    }

    private final List<String> blacklist;
    private final OnRemoveListener listener;

    public BlacklistAdapter(List<String> blacklist, OnRemoveListener listener) {
        this.blacklist = blacklist;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_blacklist, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String fid = blacklist.get(position);
        holder.fidText.setText(fid);
        holder.removeBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRemove(fid, holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return blacklist.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView fidText;
        final Button removeBtn;

        ViewHolder(View itemView) {
            super(itemView);
            fidText = itemView.findViewById(R.id.blacklist_fid_text);
            removeBtn = itemView.findViewById(R.id.btn_remove);
        }
    }
}
