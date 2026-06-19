package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.PROOF;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.ProofOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.utils.ProofCardContainer;
import com.fc.freer.utils.ChooseMode;

import java.util.ArrayList;
import java.util.List;

public class DestroyProofActivity extends BaseCryptoActivity {
    private static final String TAG = "DestroyProofActivity";

    private ProofCardContainer proofCardContainer;
    private LinearLayout proofListContainer;
    private ProofManager proofManager;

    private ImageButton destroyButton;
    private ScrollView proofScrollView;
    private TextView proofStatisticsTextView;

    private List<Proof> proofList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_destroy_proof;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.destroy_proofs);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the proof details from intent
        String proofListJson = getIntent().getStringExtra("proofList");
        if (proofListJson == null || proofListJson.isEmpty()) {
            ToastUtils.makeText(this, "No proofs provided for destruction");
            finish();
            return;
        }

        try {
            proofList = JsonUtils.listFromJson(proofListJson, Proof.class);
            if (proofList.isEmpty()) {
                ToastUtils.makeText(this, "No valid proofs found");
                finish();
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing proof list: %s", e.getMessage());
            ToastUtils.makeText(this, "Error loading proof data");
            finish();
            return;
        }

        // Initialize ProofManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                proofManager = ProofManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ProofManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ProofManager with liveFid: " + e.getMessage());
        }

        if (proofManager == null) {
            ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later));
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
        loadProofCardList();
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        destroyButton = findViewById(R.id.destroy_button);

        // Initialize scroll view
        proofScrollView = findViewById(R.id.proof_scroll_view);

        // Initialize statistics TextView
        proofStatisticsTextView = findViewById(R.id.proof_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // DestroyProofActivity doesn't use QR scanning functionality
    }

    private void loadProofCardList() {
        // Initialize the LinearLayout container for proof cards
        proofListContainer = findViewById(R.id.fragment_container);

        // Create ProofCardContainer with WITHOUT_CHOOSE mode (no checkbox, clear icon to remove)
        proofCardContainer = new ProofCardContainer(this, proofListContainer, ChooseMode.WITHOUT_CHOOSE);

        // Set up proof list change listener
        proofCardContainer.setOnProofListChangedListener(updatedProofList -> {
            updateUI();
        });

        // Set up proof remove listener
        proofCardContainer.setOnProofRemoveListener(proof -> {
            // Remove from the main list when removed from UI
            proofList.remove(proof);
        });

        // Add all proof cards to the manager
        for (Proof proof : proofList) {
            proofCardContainer.addProofCard(proof, null);
        }

        // Update UI after cards are loaded
        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasProofs = proofCardContainer != null && !proofCardContainer.getProofList().isEmpty();

        // Destroy button is enabled when there are proofs in the container
        destroyButton.setEnabled(hasProofs);
        destroyButton.setAlpha(hasProofs ? 1.0f : 0.5f);
    }

    /**
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (proofStatisticsTextView == null || proofCardContainer == null) {
            return;
        }

        int containerSize = proofCardContainer.getProofList().size();

        StringBuilder statsText = new StringBuilder();
        statsText.append("Total: ").append(containerSize);

        proofStatisticsTextView.setText(statsText.toString());
        proofStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        destroyButton.setOnClickListener(v -> {
            if (proofCardContainer == null) return;
            List<Proof> proofsToDestroy = new ArrayList<>(proofCardContainer.getProofList());
            if (proofsToDestroy.isEmpty()) {
                ToastUtils.makeText(this, "No proofs to destroy");
                return;
            }
            performDestroyOperation(proofsToDestroy);
        });
    }

    private void performDestroyOperation(List<Proof> proofsToDestroy) {
        // Separate off-chain and on-chain proofs, delete off-chain ones first
        List<Proof> remainingOnChainProofs = proofManager.deleteOffChainEntities(proofsToDestroy);

        // Commit the off-chain deletions
        proofManager.commit();

        // Remove deleted off-chain proofs from the UI
        if (remainingOnChainProofs.size() < proofsToDestroy.size()) {
            int deletedCount = proofsToDestroy.size() - remainingOnChainProofs.size();
            ToastUtils.makeText(this, "Deleted " + deletedCount + " off-chain proofs locally");
        }

        // If there are no on-chain proofs to destroy, we're done
        if (remainingOnChainProofs.isEmpty()) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        // For remaining on-chain proofs, proceed with blockchain destruction
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, "No active key available");
            return;
        }

        // Extract IDs for the destroy operation
        List<String> proofIds = new ArrayList<>();
        for (Proof proof : remainingOnChainProofs) {
            if (proof.getId() != null) {
                proofIds.add(proof.getId());
            }
        }

        if (proofIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_proof_ids));
            return;
        }

        // Create FEIP for destroy operation
        String feipJson = makeDestroyProofFeip(proofIds);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            // Send transaction on blockchain
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            // Remove destroyed proofs from database
                            proofManager.removeProofs(remainingOnChainProofs);

                            proofManager.commit();

                            ToastUtils.makeText(DestroyProofActivity.this, "Proofs destroyed successfully on-chain");
                            setResult(Activity.RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(DestroyProofActivity.this, "Failed to destroy proofs on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(DestroyProofActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(DestroyProofActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(DestroyProofActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            // Delete locally without blockchain transaction
            proofManager.removeProofs(remainingOnChainProofs);

            proofManager.commit();

            ToastUtils.makeText(this, "Proofs deleted locally");
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    private static String makeDestroyProofFeip(List<String> proofIds) {
        Feip feip = Feip.fromName(PROOF);
        ProofOpData proofOpData = ProofOpData.makeDestroy(proofIds.toArray(new String[0]));
        feip.setData(proofOpData);
        return feip.toJson();
    }
}
