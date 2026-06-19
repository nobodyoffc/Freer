package com.fc.freer.data;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.HatCardContainer;
import com.fc.freer.utils.KeyboardUtils;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for uploading local-only HAT data to DISK.
 * Displays HATs that are only stored locally.
 * Clicking upload starts UploadService and returns to DataActivity,
 * which shows a persistent progress bar.
 */
public class UploadDataActivity extends BaseCryptoActivity {
    private static final String TAG = "UploadDataActivity";
    public static final String EXTRA_HAT_IDS = "extra_hat_ids";

    // Core components
    private HatCardContainer hatCardContainer;
    private HatManager hatManager;

    // UI Elements
    private ScrollView scrollView;
    private LinearLayout fragmentContainer;
    private TextView dataStatistics;
    private LinearLayout dataStatisticsContainer;
    private ImageButton uploadButton;

    // State
    private final List<Hat> hatList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        initializeManagers();
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        loadHatsFromIntent();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_upload_data;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.upload_data);
    }

    private void initializeManagers() {
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                hatManager = HatManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize managers");
                ToastUtils.showError(this, getString(R.string.error_no_live_fid));
                finish();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing managers: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.initialization_failed));
            finish();
        }
    }

    @Override
    protected void initializeViews() {
        scrollView = findViewById(R.id.upload_scroll_view);
        fragmentContainer = findViewById(R.id.fragment_container);
        dataStatistics = findViewById(R.id.data_statistics);
        dataStatisticsContainer = findViewById(R.id.data_statistics_container);
        uploadButton = findViewById(R.id.upload_button);

        // Initialize card container – no checkboxes needed
        hatCardContainer = new HatCardContainer(this, fragmentContainer);
        hatCardContainer.setAlwaysShowCheckboxes(false);
        hatCardContainer.setHideEditButton(true);
        hatCardContainer.setOnHatRemoveListener((hat, position) -> {
            hatCardContainer.removeHat(hat);
            hatList.remove(hat);
            updateStatistics();
            ToastUtils.makeText(this, getString(R.string.removed_from_list));
        });

        // Hide keyboard on scroll
        scrollView.setOnTouchListener((v, event) -> {
            KeyboardUtils.hideKeyboard(this);
            return false;
        });
    }

    @Override
    protected void setupButtons() {
        uploadButton.setOnClickListener(v -> {
            KeyboardUtils.hideKeyboard(this);
            startUpload();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadHatsFromIntent() {
        ArrayList<String> hatIds = getIntent().getStringArrayListExtra(EXTRA_HAT_IDS);
        if (hatIds == null || hatIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_local_only_hats_checked));
            finish();
            return;
        }

        hatList.clear();
        for (String id : hatIds) {
            Hat hat = hatManager.getHatById(id);
            if (hat != null) {
                hatList.add(hat);
            }
        }

        if (hatList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_local_only_hats_checked));
            finish();
            return;
        }

        hatCardContainer.addHats(hatList);
        updateStatistics();
    }

    private void updateStatistics() {
        int count = hatList.size();
        dataStatisticsContainer.setVisibility(View.VISIBLE);
        dataStatistics.setText(getString(R.string.showing_of_total, count, (long) count));
    }

    /**
     * Starts UploadService with the current HAT list and finishes back to DataActivity.
     */
    private void startUpload() {
        if (hatList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_items_selected));
            return;
        }
        ArrayList<String> ids = new ArrayList<>();
        for (Hat h : hatList) ids.add(h.getId());
        Intent i = new Intent(this, UploadService.class);
        i.putStringArrayListExtra(UploadService.EXTRA_HAT_IDS, ids);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        setResult(RESULT_OK);
        finish();
    }
}
