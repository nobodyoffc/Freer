package com.fc.freer.im.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.freer.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DockItemsAdapter extends RecyclerView.Adapter<DockItemsAdapter.ViewHolder> {

    private final List<DockItem> items;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(DockItem item, int position);
        void onItemLongClick(DockItem item, int position);
    }

    public DockItemsAdapter(List<DockItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dock_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DockItem item = items.get(position);
        holder.bind(item, position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDataType;
        private final TextView tvSender;
        private final TextView tvSize;
        private final TextView tvCreated;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDataType = itemView.findViewById(R.id.tv_data_type);
            tvSender = itemView.findViewById(R.id.tv_sender);
            tvSize = itemView.findViewById(R.id.tv_size);
            tvCreated = itemView.findViewById(R.id.tv_created);
        }

        void bind(DockItem item, int position) {
            String dataType = item.getDataType();
            tvDataType.setText(itemView.getContext().getString(R.string.dock_item_type,
                    dataType != null ? dataType : itemView.getContext().getString(R.string.dock_item_unknown_type)));

            tvSender.setText(itemView.getContext().getString(R.string.dock_item_sender,
                    item.getSender() != null ? item.getSender() : "?"));

            if (item.getSize() != null) {
                tvSize.setText(itemView.getContext().getString(R.string.dock_item_size,
                        formatSize(item.getSize())));
                tvSize.setVisibility(View.VISIBLE);
            } else {
                tvSize.setVisibility(View.GONE);
            }

            if (item.getCreateTime() != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                tvCreated.setText(itemView.getContext().getString(R.string.dock_item_created,
                        sdf.format(new Date(item.getCreateTime()))));
                tvCreated.setVisibility(View.VISIBLE);
            } else {
                tvCreated.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item, position);
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onItemLongClick(item, position);
                return true;
            });
        }

        private String formatSize(Long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
