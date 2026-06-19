package com.fc.freer.im;

import android.content.Intent;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.ui.MenuItem;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

public class ChooseFidActivity extends BaseCryptoActivity {
    private static final String TAG = "ChooseFidActivity";

    public static final String EXTRA_CANDIDATE_FIDS = "extra_candidate_fids";
    public static final String EXTRA_CHOOSE_MODE = "extra_choose_mode";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_SELECTED_FIDS = "extra_selected_fids";

    private LinearLayout candidatesLayout;
    private ImageButton confirmButton;
    private KeyCardContainer keyCardContainer;
    private ChooseMode chooseMode;
    private String activityTitle;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_choose_fid;
    }

    @Override
    protected String getActivityTitle() {
        activityTitle = getIntent().getStringExtra(EXTRA_TITLE);
        return activityTitle != null ? activityTitle : getString(R.string.choose_fid);
    }

    @Override
    protected void initializeViews() {
        candidatesLayout = findViewById(R.id.candidates_layout);
        confirmButton = findViewById(R.id.confirm_button);

        String modeStr = getIntent().getStringExtra(EXTRA_CHOOSE_MODE);
        chooseMode = ChooseMode.CHOOSE_MULTI;
        if (modeStr != null) {
            try {
                chooseMode = ChooseMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                TimberLogger.w(TAG, "Invalid choose mode: %s", modeStr);
            }
        }

        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN) {
            confirmButton.setVisibility(android.view.View.GONE);
        }

        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(MenuItem.createDetailMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, candidatesLayout, chooseMode, menuItems, false);

        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN) {
            keyCardContainer.setOnKeyClickedListener(this::onSingleFidSelected);
        }

        loadCandidates();
    }

    @Override
    protected void setupButtons() {
        confirmButton.setOnClickListener(v -> {
            hideKeyboard();
            confirmSelection();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void loadCandidates() {
        ArrayList<String> candidateFids = getIntent().getStringArrayListExtra(EXTRA_CANDIDATE_FIDS);
        if (candidateFids == null || candidateFids.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            finish();
            return;
        }

        CidFidManager cidFidManager = CidFidManager.getInstance();

        for (String fid : candidateFids) {
            KeyInfo keyInfo = new KeyInfo();
            keyInfo.setId(fid);
            if (cidFidManager != null) {
                String cid = cidFidManager.getCidByFid(fid);
                if (cid != null && !cid.isEmpty()) {
                    keyInfo.setCid(cid);
                }
            }
            keyCardContainer.addKeyCard(keyInfo);
        }
    }

    private void onSingleFidSelected(KeyInfo keyInfo) {
        ArrayList<String> result = new ArrayList<>();
        result.add(keyInfo.getId());

        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_SELECTED_FIDS, result);
        setResult(RESULT_OK, data);
        finish();
    }

    private void confirmSelection() {
        List<KeyInfo> selected = keyCardContainer.getSelectedKeys();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_fid_selected);
            return;
        }

        ArrayList<String> selectedFids = new ArrayList<>();
        for (KeyInfo ki : selected) {
            selectedFids.add(ki.getId());
        }

        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_SELECTED_FIDS, selectedFids);
        setResult(RESULT_OK, data);
        finish();
    }
}
