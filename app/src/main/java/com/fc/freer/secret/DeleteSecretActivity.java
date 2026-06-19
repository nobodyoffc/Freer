package com.fc.freer.secret;

import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.SecretOpData;
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
import com.fc.freer.manager.SecretManager;
import com.fc.freer.utils.SecretCardContainer;
import com.fc.freer.utils.ChooseMode;
import android.widget.ImageButton;

import java.util.ArrayList;
import java.util.List;

public class DeleteSecretActivity extends BaseCryptoActivity {
    private static final String TAG = "DeleteSecretActivity";

    private SecretCardContainer secretCardContainer;
    private LinearLayout secretListContainer;
    private SecretManager secretManager;

    private ImageButton deleteButton;
    private ScrollView secretScrollView;
    private TextView secretStatisticsTextView;

    private List<Secret> secretList = new ArrayList<>();
    private boolean isFailedDecryption = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_delete_secret;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.delete_secrets);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the secret details from intent
        String secretListJson = getIntent().getStringExtra("secretList");
        if (secretListJson == null || secretListJson.isEmpty()) {
            ToastUtils.makeText(this, "No secrets provided for deletion");
            finish();
            return;
        }

        // Check if this is for failed decryption secrets
        isFailedDecryption = getIntent().getBooleanExtra("isFailedDecryption", false);

        try {
            secretList = JsonUtils.listFromJson(secretListJson, Secret.class);
            if (secretList.isEmpty()) {
                ToastUtils.makeText(this, "No valid secrets found");
                finish();
                return;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing secret list: %s", e.getMessage());
            ToastUtils.makeText(this, "Error loading secret data");
            finish();
            return;
        }

        // Initialize SecretManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                secretManager = SecretManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize SecretManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing SecretManager with liveFid: " + e.getMessage());
        }

        if (secretManager == null) {
            ToastUtils.makeText(this, getString(R.string.secret_not_ready_try_later));
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
        loadSecretCardList();
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        deleteButton = findViewById(R.id.delete_button);

        // Initialize scroll view
        secretScrollView = findViewById(R.id.secret_scroll_view);

        // Initialize statistics TextView
        secretStatisticsTextView = findViewById(R.id.secret_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // DeleteSecretActivity doesn't use QR scanning functionality
    }

    private void loadSecretCardList() {
        // Initialize the LinearLayout container for secret cards
        secretListContainer = findViewById(R.id.fragment_container);

        // Create SecretCardContainer without selection (WITHOUT_CHOOSE mode)
        secretCardContainer = new SecretCardContainer(this, secretListContainer, ChooseMode.WITHOUT_CHOOSE);

        // Set up list change listener
        secretCardContainer.setOnSecretListChangedListener(updatedSecretList -> {
            // Update the main secretList to match the container
            secretList.clear();
            secretList.addAll(updatedSecretList);
            updateUI(); // This calls updateButtonStates and updateStatistics
        });

        // Add all secret cards to the manager
        for (Secret secret : secretList) {
            secretCardContainer.addSecretCard(secret);
        }

        // Update UI after cards are loaded
        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasSecrets = secretCardContainer != null && !secretCardContainer.getSecretList().isEmpty();

        // Delete button is enabled when there are secrets in the container
        deleteButton.setEnabled(hasSecrets);
        deleteButton.setAlpha(hasSecrets ? 1.0f : 0.5f);
    }

    /**
     * Updates the statistics TextView with container size
     */
    private void updateStatistics() {
        if (secretStatisticsTextView == null || secretCardContainer == null) {
            return;
        }

        int containerSize = secretCardContainer.getSecretList().size();

        StringBuilder statsText = new StringBuilder();
        statsText.append("Total: ").append(containerSize);

        secretStatisticsTextView.setText(statsText.toString());
        secretStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        deleteButton.setOnClickListener(v -> {
            if (secretCardContainer == null) return;
            List<Secret> secretsToDelete = new ArrayList<>(secretCardContainer.getSecretList());
            if (secretsToDelete.isEmpty()) {
                ToastUtils.makeText(this, "No secrets to delete");
                return;
            }
            performDeleteOperation(secretsToDelete);
        });
    }

    private void refreshCardList() {
        if (secretCardContainer != null) {
            secretCardContainer.clearAll();

            // Re-add remaining secrets from the main list
            for (Secret secret : secretList) {
                secretCardContainer.addSecretCard(secret);
            }

            updateUI();
        }
    }

    private void performDeleteOperation(List<Secret> secretsToDelete) {
        // First, separate off-chain and on-chain secrets
        List<Secret> remainingOnChainSecrets = secretManager.deleteOffChainEntities(secretsToDelete);

        // Commit the off-chain deletions
        secretManager.commit();

        // Remove deleted off-chain secrets from the UI and update the list
        if (remainingOnChainSecrets.size() < secretsToDelete.size()) {
            int deletedCount = secretsToDelete.size() - remainingOnChainSecrets.size();
            ToastUtils.makeText(this, "Deleted " + deletedCount + " off-chain secrets locally");

            // Update the secretList to remove deleted off-chain secrets
            secretList.clear();
            secretList.addAll(remainingOnChainSecrets);

            // Refresh the card list to reflect the changes
            refreshCardList();
        }

        // If there are no on-chain secrets left to delete, finish
        if (remainingOnChainSecrets.isEmpty()) {
            setResult(Activity.RESULT_OK);
            finish();
            return;
        }

        // Handle on-chain secrets deletion
        performOnChainDeleteOperation(remainingOnChainSecrets);
    }

    private void performOnChainDeleteOperation(List<Secret> onChainSecretsToDelete) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, "No active key available");
            return;
        }

        // Extract IDs for the delete operation
        List<String> secretIds = new ArrayList<>();
        for (Secret secret : onChainSecretsToDelete) {
            if (secret.getId() != null) {
                secretIds.add(secret.getId());
            }
        }

        if (secretIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_secret_ids));
            return;
        }

        // Create FEIP for delete operation
        String feipJson = makeDeleteSecretFeip(secretIds);

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
                            // Remove deleted secrets from database
                            secretManager.removeSecretDetails(onChainSecretsToDelete);

                            secretManager.commit();

                            ToastUtils.makeText(DeleteSecretActivity.this, "Secrets deleted successfully on-chain");
                            setResult(Activity.RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(DeleteSecretActivity.this, "Failed to delete secrets on-chain: " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(DeleteSecretActivity.this, "Cannot sign transaction - showing unsigned TX");
                            txSender.showUnsignedTxAsQR(DeleteSecretActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(DeleteSecretActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();

        } else {
            // Delete locally without blockchain transaction
            secretManager.removeSecretDetails(onChainSecretsToDelete);

            secretManager.commit();

            ToastUtils.makeText(this, "Secrets deleted locally");
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    private static String makeDeleteSecretFeip(List<String> secretIds) {
        Feip feip = Feip.fromName(SECRET);
        SecretOpData secretOpData = SecretOpData.makeDelete(secretIds);
        feip.setData(secretOpData);
        return feip.toJson();
    }
}