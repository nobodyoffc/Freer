package com.fc.freer.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ProofCardContainer;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Proofs the user hid from the proof list. They are kept only on this device and can be revealed again.
 */
public class HiddenProofActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenProofActivity";

    private ProofCardContainer proofCardContainer;
    private ProofManager proofManager;
    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private TextView proofStatisticsTextView;
    private List<Proof> proofList = new ArrayList<>();

    @Override protected int getLayoutId() { return R.layout.activity_hidden_proof; }
    @Override protected String getActivityTitle() { return getString(R.string.hidden_proofs); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (fidManager != null && liveFid != null) proofManager = ProofManager.getInstance(this, liveFid);
        } catch (Exception e) { TimberLogger.e(TAG, "Error initializing ProofManager: " + e.getMessage()); }

        if (proofManager == null) { ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later)); finish(); return; }

        proofList = proofManager.getLocalHiddenProofs();
        if (proofList == null || proofList.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_hidden_proofs_found)); finish(); return; }

        ToolbarUtils.setupToolbar(this, getActivityTitle());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { finish(); } });
        setupButtons();
        loadProofCardList();
    }

    @Override protected void initializeViews() {
        revealButton = findViewById(R.id.reveal_button);
        backButton = findViewById(R.id.back_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        proofStatisticsTextView = findViewById(R.id.proof_statistics);
    }

    @Override protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void loadProofCardList() {
        LinearLayout proofListContainer = findViewById(R.id.fragment_container);
        proofCardContainer = new ProofCardContainer(this, proofListContainer, ChooseMode.CHOOSE_MULTI);
        proofCardContainer.setOnProofListChangedListener(updatedProofList -> updateUI());
        Map<String, String> cidMap = proofCardContainer.getCidMap(proofList, this);
        for (Proof proof : proofList) proofCardContainer.addProofCard(proof, cidMap);
        setupSelectAllCheckBox();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && proofCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) proofCardContainer.selectAll(isChecked);
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && proofCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);
            selectAllCheckBox.setChecked(proofCardContainer.areAllSelected());
            setupSelectAllCheckBox();
        }
    }

    private void updateUI() { updateButtonStates(); updateSelectAllCheckBoxState(); updateStatistics(); }
    private void updateButtonStates() { boolean hasSelected = proofCardContainer != null && !proofCardContainer.getSelectedProofs().isEmpty(); revealButton.setEnabled(hasSelected); revealButton.setAlpha(hasSelected ? 1.0f : 0.5f); }
    private void updateStatistics() { if (proofStatisticsTextView != null) { proofStatisticsTextView.setText(getString(R.string.total_count, proofCardContainer != null ? proofCardContainer.getProofList().size() : 0)); proofStatisticsTextView.setVisibility(View.VISIBLE); } }

    @Override protected void setupButtons() {
        revealButton.setOnClickListener(v -> { hideKeyboard(); if (proofCardContainer == null) return; List<Proof> selectedProofs = proofCardContainer.getSelectedProofs(); if (selectedProofs.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_items_selected)); return; } performRevealOperation(selectedProofs); });
        backButton.setOnClickListener(v -> { hideKeyboard(); finish(); });
    }

    private void performRevealOperation(List<Proof> proofsToReveal) {
        for (Proof proof : proofsToReveal) { proofManager.removeFromLocalDeletedList(proof); proofManager.addProof(proof); }
        proofManager.commit();
        for (Proof proof : proofsToReveal) proofCardContainer.removeProofById(proof.getId());
        proofList.removeAll(proofsToReveal);
        ToastUtils.makeText(this, getString(R.string.proofs_revealed_successfully, proofsToReveal.size()));
        if (proofList.isEmpty()) { setResult(Activity.RESULT_OK); finish(); } else { updateUI(); setResult(Activity.RESULT_OK); }
    }
}
