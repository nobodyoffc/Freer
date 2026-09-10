package com.fc.freer.im;

import android.content.Intent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.adapter.DockItemsAdapter;
import com.fc.freer.im.dock.DockItemRouter;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays unknown dock items that were fetched but not automatically handled.
 * Allows user to view details and delete items.
 */
public class DockItemsActivity extends BaseCryptoActivity
        implements DockItemsAdapter.OnItemClickListener {

    private static final String TAG = "DockItemsActivity";

    private RecyclerView recyclerView;
    private TextView emptyText;
    private SwipeRefreshLayout swipeRefreshLayout;

    private DockItemsAdapter adapter;
    private final List<DockItem> items = new ArrayList<>();

    private ImManager imManager;
    private DockItemRouter dockRouter;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_dock_items;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.dock_items);
    }

    @Override
    protected void initializeViews() {
        recyclerView = findViewById(R.id.dock_items_recycler_view);
        emptyText = findViewById(R.id.empty_text);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);

        adapter = new DockItemsAdapter(items, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::loadItems);

        ImageButton backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        loadItems();
    }

    @Override
    protected void setupButtons() {
        // Back button handled in initializeViews
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadItems();
    }

    private void loadItems() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            imManager = setting.getImManager();
        }

        items.clear();

        if (imManager != null) {
            dockRouter = imManager.getDockRouter();
            if (dockRouter != null) {
                items.addAll(dockRouter.getUnknownItems());
            }
        }

        adapter.notifyDataSetChanged();
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        swipeRefreshLayout.setRefreshing(false);
    }

    @Override
    public void onItemClick(DockItem item, int position) {
        showItemDetail(item, position);
    }

    @Override
    public void onItemLongClick(DockItem item, int position) {
        showDeleteDialog(item, position);
    }

    private void showItemDetail(DockItem item, int position) {
        StringBuilder detail = new StringBuilder();
        detail.append("ID: ").append(item.getId()).append("\n");
        detail.append("Type: ").append(item.getDataType() != null ? item.getDataType() : "Unknown").append("\n");
        detail.append("Sender: ").append(item.getSender() != null ? item.getSender() : "?").append("\n");
        if (item.getSize() != null) {
            detail.append("Size: ").append(item.getSize()).append(" bytes\n");
        }
        if (item.getDataHash() != null) {
            detail.append("Hash: ").append(item.getDataHash()).append("\n");
        }
        if (item.getRecipients() != null) {
            detail.append("Recipients: ").append(item.getRecipients()).append("\n");
        }

        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dock_items))
                .setMessage(detail.toString())
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.dock_item_delete, (d, w) -> deleteItem(item, position)));
    }

    private void showDeleteDialog(DockItem item, int position) {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete_dock_item)
                .setPositiveButton(R.string.dock_item_delete, (d, w) -> deleteItem(item, position))
                .setNegativeButton(R.string.cancel, null));
    }

    private void deleteItem(DockItem item, int position) {
        if (dockRouter != null) {
            dockRouter.removeUnknownItem(item.getId());
        }
        items.remove(position);
        adapter.notifyItemRemoved(position);
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        ToastUtils.showInfo(this, getString(R.string.dock_item_deleted));
    }
}
