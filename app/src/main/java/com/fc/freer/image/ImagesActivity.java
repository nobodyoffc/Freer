package com.fc.freer.image;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.feipData.Image;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ImageManager;
import com.fc.freer.manager.TokenLogoManager;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * FEIP24 Images, as in Freer Mac: the ones this FID published, everyone's, and this FID's
 * deleted ones. With {@link #EXTRA_PICK} it lists only Mine and returns the chosen id, for a
 * field that names an Image (a token's logo).
 */
public class ImagesActivity extends BaseCryptoActivity {
    public static final String EXTRA_PICK = "pick";
    public static final String EXTRA_IMAGE_ID = "image_id";

    private static final ImageManager.Tab[] TABS = {
            ImageManager.Tab.MINE, ImageManager.Tab.DISCOVER, ImageManager.Tab.DELETED};

    private boolean pick;
    private String fid;
    private ImageManager.Tab tab = ImageManager.Tab.MINE;
    private final List<Image> rows = new ArrayList<>();
    private boolean hasMore;
    private boolean loading;
    private int generation;

    private SwipeRefreshLayout refresh;
    private TextView empty;
    private Adapter adapter;

    private ActivityResultLauncher<Intent> publishLauncher;
    private ActivityResultLauncher<Intent> detailLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_images;
    }

    @Override
    protected String getActivityTitle() {
        return getString(getIntent().getBooleanExtra(EXTRA_PICK, false)
                ? R.string.images_pick_title : R.string.images_title);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        publishLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String id = result.getData().getStringExtra(EXTRA_IMAGE_ID);
            if (pick && id != null) {
                finishWith(id);
                return;
            }
            reload();
        });
        detailLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) reload();
        });
        super.onCreate(savedInstanceState);
        if (isSessionRedirected()) return;
        fid = FidManager.getInstance().getLiveFid();
        if (fid == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            finish();
            return;
        }
        reload();
    }

    @Override
    protected void initializeViews() {
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        refresh = findViewById(R.id.image_refresh);
        empty = findViewById(R.id.image_empty);
        RecyclerView list = findViewById(R.id.image_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        refresh.setOnRefreshListener(this::reload);

        TabLayout tabs = findViewById(R.id.image_tabs);
        if (pick) {
            tabs.setVisibility(View.GONE);
        } else {
            tabs.addTab(tabs.newTab().setText(R.string.images_tab_mine));
            tabs.addTab(tabs.newTab().setText(R.string.images_tab_discover));
            tabs.addTab(tabs.newTab().setText(R.string.images_tab_deleted));
            tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab t) {
                    tab = TABS[t.getPosition()];
                    reload();
                }
                @Override public void onTabUnselected(TabLayout.Tab t) {}
                @Override public void onTabReselected(TabLayout.Tab t) { reload(); }
            });
        }
    }

    @Override
    protected void setupButtons() {
        ImageButton create = findViewById(R.id.image_create_button);
        create.setOnClickListener(v -> publishLauncher.launch(new Intent(this, PublishImageActivity.class)));
        ImageButton back = findViewById(R.id.back_button);
        back.setOnClickListener(v -> finish());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // No scan fields here.
    }

    private void reload() {
        if (fid == null) return;
        generation++;
        rows.clear();
        hasMore = false;
        loading = false;
        adapter.notifyDataSetChanged();
        loadPage();
    }

    private void loadPage() {
        if (loading) return;
        loading = true;
        refresh.setRefreshing(true);
        int requestGeneration = generation;
        ImageManager.Tab requestTab = tab;
        Image after = rows.isEmpty() ? null : lastIndexed();
        boolean first = rows.isEmpty();
        int size = FreerApplication.DEFAULT_PAGE_SIZE;
        new Thread(() -> {
            List<Image> page = ImageManager.search(requestTab, fid, after, size);
            runOnUiThread(() -> {
                if (isFinishing() || requestGeneration != generation) return;
                loading = false;
                refresh.setRefreshing(false);
                if (page == null) {
                    if (rows.isEmpty()) showEmpty(getString(R.string.images_load_failed));
                    return;
                }
                hasMore = page.size() >= size;
                List<Image> shown = first && requestTab == ImageManager.Tab.MINE
                        ? ImageManager.withRecent(fid, page) : page;
                rows.addAll(shown);
                adapter.notifyDataSetChanged();
                if (rows.isEmpty()) {
                    showEmpty(getString(requestTab == ImageManager.Tab.MINE ? R.string.images_empty_mine
                            : requestTab == ImageManager.Tab.DISCOVER ? R.string.images_empty_discover
                            : R.string.images_empty_deleted));
                } else {
                    empty.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    /** The last row the index returned (a just-carved row has no height to page after). */
    private Image lastIndexed() {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).getLastHeight() != null) return rows.get(i);
        }
        return null;
    }

    private void showEmpty(String text) {
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
    }

    private void open(Image image) {
        if (pick) {
            finishWith(image.getId());
            return;
        }
        Intent intent = new Intent(this, ImageDetailActivity.class);
        intent.putExtra(ImageDetailActivity.EXTRA_IMAGE, JsonUtils.toJson(image));
        detailLauncher.launch(intent);
    }

    private void finishWith(String imageId) {
        Intent data = new Intent();
        data.putExtra(EXTRA_IMAGE_ID, imageId);
        setResult(RESULT_OK, data);
        finish();
    }

    static String shortId(String id) {
        if (id == null) return "";
        return id.length() > 16 ? id.substring(0, 8) + "…" + id.substring(id.length() - 6) : id;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int ROW = 0;
        private static final int MORE = 1;

        @Override
        public int getItemCount() {
            return rows.size() + (hasMore ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return position < rows.size() ? ROW : MORE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == MORE) {
                Button button = new Button(parent.getContext());
                button.setText(R.string.images_load_more);
                button.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                button.setOnClickListener(v -> loadPage());
                return new RecyclerView.ViewHolder(button) {};
            }
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image_row, parent, false);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position >= rows.size()) return;
            Image image = rows.get(position);
            View view = holder.itemView;
            TextView title = view.findViewById(R.id.image_title);
            TextView summary = view.findViewById(R.id.image_summary);
            TextView meta = view.findViewById(R.id.image_meta);
            ImageView thumb = view.findViewById(R.id.image_thumb);

            title.setText(image.getTitle() == null || image.getTitle().isEmpty()
                    ? getString(R.string.image_untitled) : image.getTitle());
            summary.setText(image.getSummary() == null ? "" : image.getSummary());
            summary.setVisibility(image.getSummary() == null || image.getSummary().isEmpty() ? View.GONE : View.VISIBLE);
            StringBuilder line = new StringBuilder();
            if (tab == ImageManager.Tab.DISCOVER && image.getPublisher() != null) {
                line.append(shortId(image.getPublisher())).append(" · ");
            }
            if (image.getVer() != null) line.append("v").append(image.getVer()).append(" · ");
            line.append(shortId(image.getId()));
            meta.setText(line);

            thumb.setImageResource(R.drawable.ic_gallery);
            thumb.setTag(image.getId());
            int sizePx = (int) (64 * getResources().getDisplayMetrics().density);
            // Other people's pictures are fetched on their detail page, not for a whole list.
            boolean download = tab != ImageManager.Tab.DISCOVER;
            TokenLogoManager.getInstance(ImagesActivity.this).loadImage(image, sizePx, download, bitmap -> {
                if (bitmap != null && image.getId().equals(thumb.getTag())) thumb.setImageBitmap(bitmap);
            });
            view.setOnClickListener(v -> open(image));
        }
    }
}
