package com.fc.freer.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.AppManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.AppCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HiddenAppActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenAppActivity";

    private AppCardContainer appCardContainer;
    private LinearLayout appListContainer;
    private AppManager appManager;

    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private ScrollView appScrollView;
    private TextView appStatisticsTextView;

    private List<App> appList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_hidden_app;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.hidden_apps);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize AppManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                appManager = AppManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize AppManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing AppManager with liveFid: " + e.getMessage());
        }

        if (appManager == null) {
            ToastUtils.makeText(this, getString(R.string.app_not_ready_try_later));
            finish();
            return;
        }

        // Load hidden apps from local deleted list
        appList = appManager.getLocalHiddenApps();

        if (appList == null || appList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_hidden_apps_found));
            finish();
            return;
        }

        // Setup toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        loadAppCardList();
    }

    @Override
    protected void initializeViews() {
        revealButton = findViewById(R.id.reveal_button);
        backButton = findViewById(R.id.back_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        appScrollView = findViewById(R.id.app_scroll_view);
        appStatisticsTextView = findViewById(R.id.app_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadAppCardList() {
        appListContainer = findViewById(R.id.fragment_container);

        appCardContainer = new AppCardContainer(this, appListContainer, ChooseMode.CHOOSE_MULTI);
        appCardContainer.setHideEditButton(true);

        appCardContainer.setOnAppListChangedListener(updatedAppList -> {
            updateUI();
        });

        Map<String, String> cidMap = appCardContainer.getCidMap(appList, this);
        for (App app : appList) {
            appCardContainer.addAppCard(app, cidMap);
        }

        setupSelectAllCheckBox();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && appCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    appCardContainer.selectAll(isChecked);
                }
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && appCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (appCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (appCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasSelected = appCardContainer != null && !appCardContainer.getSelectedApps().isEmpty();
        revealButton.setEnabled(hasSelected);
        revealButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (appStatisticsTextView == null) {
            return;
        }

        int containerSize = appCardContainer != null ? appCardContainer.getAppList().size() : 0;
        appStatisticsTextView.setText(getString(R.string.total_count, containerSize));
        appStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        revealButton.setOnClickListener(v -> {
            hideKeyboard();
            if (appCardContainer == null) return;
            List<App> selectedApps = appCardContainer.getSelectedApps();
            if (selectedApps.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_apps_selected));
                return;
            }
            performRevealOperation(selectedApps);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performRevealOperation(List<App> appsToReveal) {
        // Remove from local deleted list and add back to main database
        for (App app : appsToReveal) {
            appManager.removeFromLocalDeletedList(app);
            appManager.addApp(app);
        }
        appManager.commit();

        // Remove revealed apps from the card container
        for (App app : appsToReveal) {
            appCardContainer.removeAppById(app.getId());
        }

        // Update the main list
        appList.removeAll(appsToReveal);

        ToastUtils.makeText(this, getString(R.string.apps_revealed_successfully, appsToReveal.size()));

        if (appList.isEmpty()) {
            setResult(Activity.RESULT_OK);
            finish();
        } else {
            updateUI();
            setResult(Activity.RESULT_OK);
        }
    }
}

