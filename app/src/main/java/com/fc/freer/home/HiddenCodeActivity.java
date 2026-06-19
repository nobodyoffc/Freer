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

import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CodeManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.CodeCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HiddenCodeActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenCodeActivity";

    private CodeCardContainer codeCardContainer;
    private LinearLayout codeListContainer;
    private CodeManager codeManager;
    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private ScrollView codeScrollView;
    private TextView codeStatisticsTextView;
    private List<Code> codeList = new ArrayList<>();

    @Override protected int getLayoutId() { return R.layout.activity_hidden_code; }
    @Override protected String getActivityTitle() { return getString(R.string.hidden_codes); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (fidManager != null && liveFid != null) codeManager = CodeManager.getInstance(this, liveFid);
        } catch (Exception e) { TimberLogger.e(TAG, "Error initializing CodeManager: " + e.getMessage()); }

        if (codeManager == null) { ToastUtils.makeText(this, getString(R.string.code_not_ready_try_later)); finish(); return; }

        codeList = codeManager.getLocalHiddenCodes();
        if (codeList == null || codeList.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_hidden_codes_found)); finish(); return; }

        ToolbarUtils.setupToolbar(this, getActivityTitle());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { finish(); } });
        setupButtons();
        loadCodeCardList();
    }

    @Override protected void initializeViews() {
        revealButton = findViewById(R.id.reveal_button);
        backButton = findViewById(R.id.back_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        codeScrollView = findViewById(R.id.code_scroll_view);
        codeStatisticsTextView = findViewById(R.id.code_statistics);
    }

    @Override protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void loadCodeCardList() {
        codeListContainer = findViewById(R.id.fragment_container);
        codeCardContainer = new CodeCardContainer(this, codeListContainer, ChooseMode.CHOOSE_MULTI);
        codeCardContainer.setHideEditButton(true);
        codeCardContainer.setOnCodeListChangedListener(updatedCodeList -> updateUI());
        Map<String, String> cidMap = codeCardContainer.getCidMap(codeList, this);
        for (Code code : codeList) codeCardContainer.addCodeCard(code, cidMap);
        setupSelectAllCheckBox();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && codeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) codeCardContainer.selectAll(isChecked);
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && codeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);
            selectAllCheckBox.setChecked(codeCardContainer.areAllSelected());
            setupSelectAllCheckBox();
        }
    }

    private void updateUI() { updateButtonStates(); updateSelectAllCheckBoxState(); updateStatistics(); }
    private void updateButtonStates() { boolean hasSelected = codeCardContainer != null && !codeCardContainer.getSelectedCodes().isEmpty(); revealButton.setEnabled(hasSelected); revealButton.setAlpha(hasSelected ? 1.0f : 0.5f); }
    private void updateStatistics() { if (codeStatisticsTextView != null) { codeStatisticsTextView.setText(getString(R.string.total_count, codeCardContainer != null ? codeCardContainer.getCodeList().size() : 0)); codeStatisticsTextView.setVisibility(View.VISIBLE); } }

    @Override protected void setupButtons() {
        revealButton.setOnClickListener(v -> { hideKeyboard(); if (codeCardContainer == null) return; List<Code> selectedCodes = codeCardContainer.getSelectedCodes(); if (selectedCodes.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_codes_selected)); return; } performRevealOperation(selectedCodes); });
        backButton.setOnClickListener(v -> { hideKeyboard(); finish(); });
    }

    private void performRevealOperation(List<Code> codesToReveal) {
        for (Code code : codesToReveal) { codeManager.removeFromLocalDeletedList(code); codeManager.addCode(code); }
        codeManager.commit();
        for (Code code : codesToReveal) codeCardContainer.removeCodeById(code.getId());
        codeList.removeAll(codesToReveal);
        ToastUtils.makeText(this, getString(R.string.codes_revealed_successfully, codesToReveal.size()));
        if (codeList.isEmpty()) { setResult(Activity.RESULT_OK); finish(); } else { updateUI(); setResult(Activity.RESULT_OK); }
    }
}

