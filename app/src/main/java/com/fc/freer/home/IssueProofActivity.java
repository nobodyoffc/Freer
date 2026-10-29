package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.PROOF;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.data.feipData.ProofOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class IssueProofActivity extends BaseCryptoActivity {
    private static final String TAG = "IssueProofActivity";
    public static final String EXTRA_PROOF_JSON = "extra_proof_json";

    // Input Components
    private TextInputEditText titleInput;
    private TextInputEditText contentInput;
    private TextInputEditText cosignersInput;
    private TextInputEditText issueToInput;
    private CheckBox transferableCheckBox;
    private CheckBox allSignsRequiredCheckBox;

    // Action Buttons
    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton carveButton;

    // Cosigner Management
    private LinearLayout cosignerCardContainer;
    private KeyCardContainer keyCardContainer;
    private IoIconsView cosignersIoIcons;
    private Set<String> cosignerFids = new HashSet<>();

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> chooseContactLauncher;
    private ActivityResultLauncher<Intent> chooseIssueToLauncher;
    private ActivityResultLauncher<Intent> qrScanLauncher;

    // Define request codes for QR scan
    private static final int QR_SCAN_TITLE_REQUEST_CODE = 3001;
    private static final int QR_SCAN_CONTENT_REQUEST_CODE = 3002;
    private static final int QR_SCAN_COSIGNERS_REQUEST_CODE = 3003;
    private static final int QR_SCAN_ISSUE_TO_REQUEST_CODE = 3004;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_issue_proof;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.issue_proof);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launchers
        initializeActivityResultLaunchers();

        // Set up root layout touch listener to hide keyboard
        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

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

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.titleView, R.id.scanIcon, QR_SCAN_TITLE_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.contentView, R.id.scanIcon, QR_SCAN_CONTENT_REQUEST_CODE);

        // Setup IoIconsView for cosigners input
        setupCosignersIoIcons();
        setupIssueToIoIcons();
    }

    @Override
    protected void initializeViews() {
        // Input Components
        View titleView = findViewById(R.id.titleView);
        View contentView = findViewById(R.id.contentView);
        View cosignersView = findViewById(R.id.cosignersView);

        titleInput = titleView.findViewById(R.id.textInput);
        titleInput.setHint(R.string.input_proof_title);

        contentInput = contentView.findViewById(R.id.textInput);
        contentInput.setHint(R.string.input_proof_content);
        contentInput.setMinLines(3);
        contentInput.setMaxLines(6);

        cosignersInput = cosignersView.findViewById(R.id.keyInput);
        cosignersInput.setHint(R.string.input_cosigners_comma_separated);

        issueToInput = findViewById(R.id.issueToView).findViewById(R.id.keyInput);
        issueToInput.setHint(R.string.input_proof_issue_to);

        transferableCheckBox = findViewById(R.id.transferableCheckBox);
        allSignsRequiredCheckBox = findViewById(R.id.allSignsRequiredCheckBox);

        // Action Buttons
        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        carveButton = findViewById(R.id.carveButton);

        // Initialize cosigner card container
        cosignerCardContainer = findViewById(R.id.cosignerCardContainer);
        keyCardContainer = new KeyCardContainer(this, cosignerCardContainer, ChooseMode.WITHOUT_CHOOSE_WITH_DELETE);

        // Set up listener for when cosigners are removed
        keyCardContainer.setOnMenuItemClickListener((menuItemId, keyInfo) -> {
            if ("delete".equals(menuItemId)) {
                cosignerFids.remove(keyInfo.getId());
                updateCosignersInput();
            }
        });

        // Set default checkbox values
        transferableCheckBox.setChecked(false);
        allSignsRequiredCheckBox.setChecked(false);

        // Load proof data if editing
        loadProofDataFromIntent();
    }

    /**
     * Load proof data from intent if editing an existing proof
     */
    private void loadProofDataFromIntent() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_PROOF_JSON)) {
            String proofJson = intent.getStringExtra(EXTRA_PROOF_JSON);
            if (proofJson != null && !proofJson.isEmpty()) {
                try {
                    Proof proof = JsonUtils.fromJson(proofJson, Proof.class);
                    populateProofData(proof);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Failed to parse proof JSON: %s", e.getMessage());
                    ToastUtils.makeText(this, getString(R.string.toast_failed_load_proof_data));
                }
            }
        }
    }

    /**
     * Populate the form fields with proof data
     */
    private void populateProofData(Proof proof) {
        if (proof == null) return;

        // Set title
        if (proof.getTitle() != null) {
            titleInput.setText(proof.getTitle());
        }

        // Set content
        if (proof.getContent() != null) {
            contentInput.setText(proof.getContent());
        }

        // Set transferable checkbox
        if (proof.isTransferable() != null) {
            transferableCheckBox.setChecked(proof.isTransferable());
        }

        // Set cosigners
        if (proof.getCosignersInvited() != null && !proof.getCosignersInvited().isEmpty()) {
            for (String fid : proof.getCosignersInvited()) {
                addCosignerFid(fid);
            }
            // A draft keeps "all signs required" as active=false, as the chain would index it
            allSignsRequiredCheckBox.setChecked(Boolean.FALSE.equals(proof.isActive()));
        }

        // A draft owned by someone other than its issuer is to be issued to that owner
        if (proof.getOwner() != null && !proof.getOwner().equals(proof.getIssuer())) {
            issueToInput.setText(proof.getOwner());
        }

        ToastUtils.makeText(this, getString(R.string.proof_loaded_for_editing));
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearAllInputs());
        saveButton.setOnClickListener(v -> saveProofToDatabase());
        carveButton.setOnClickListener(v -> confirmIssueToThenCarve());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TITLE_REQUEST_CODE) {
            titleInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_CONTENT_REQUEST_CODE) {
            contentInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_COSIGNERS_REQUEST_CODE) {
            if (qrContent != null) addCosignerFid(qrContent.trim());
        } else if (requestCode == QR_SCAN_ISSUE_TO_REQUEST_CODE) {
            if (qrContent != null) issueToInput.setText(qrContent.trim());
        }
    }

    private void clearAllInputs() {
        titleInput.setText("");
        contentInput.setText("");
        cosignersInput.setText("");
        issueToInput.setText("");
        transferableCheckBox.setChecked(false);
        allSignsRequiredCheckBox.setChecked(false);

        // Clear cosigners
        clearCosigners();

        ToastUtils.makeText(this, getString(R.string.cleared));
    }

    private void saveProofToDatabase() {
        String title = titleInput.getText() != null ? titleInput.getText().toString().trim() : "";
        String content = contentInput.getText() != null ? contentInput.getText().toString().trim() : "";
        boolean transferable = transferableCheckBox.isChecked();
        boolean allSignsRequired = allSignsRequiredCheckBox.isChecked();

        // Validate required fields
        if (title.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_proof_title));
            return;
        }

        if (content.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_proof_content));
            return;
        }

        if (!addTypedCosigners()) return;

        String issueTo = readIssueTo();
        if (INVALID_ISSUE_TO.equals(issueTo)) return;

        // Get cosigners from the set
        List<String> cosignersList = new ArrayList<>(cosignerFids);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String issuer = liveKeyInfo.getId();

        Proof proof = new Proof();
        proof.setTitle(title);
        proof.setContent(content);
        if (!cosignersList.isEmpty()) {
            proof.setCosignersInvited(cosignersList);
        }
        proof.setTransferable(transferable);
        proof.setActive(!(allSignsRequired && !cosignersList.isEmpty()));
        proof.setDestroyed(false);
        proof.setIssuer(issuer);
        proof.setOwner(issueTo != null ? issueTo : issuer);

        // Save to database only (no blockchain operation)
        proof.setOnChain(false); // Mark as off-chain
        proof.setLastHeight(Constants.MaX_HEIGHT); // Set update height to a high value
        proof.checkIdWithCreate();

        ProofManager proofManager = ProofManager.getInstance();
        boolean existed = proofManager.checkIfExisted(proof.getId());
        if (existed) {
            DialogUtils.show(new AlertDialog.Builder(this)
                    .setTitle(R.string.proof_existed_title)
                    .setMessage(R.string.proof_already_exists_message)
                    .setPositiveButton(R.string.replace, (dialog, which) -> {
                        proofManager.saveAndFinish(this, proof, false);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    );
        } else {
            proofManager.saveAndFinish(this, proof, false);
        }
    }

    /**
     * The first owner is named in the op, not paid, so no tx confirmation asks about a nobody: ask here.
     */
    private void confirmIssueToThenCarve() {
        String issueTo = readIssueTo();
        if (INVALID_ISSUE_TO.equals(issueTo)) return;
        if (issueTo == null) {
            carveProof();
            return;
        }
        NobodyGuard.confirm(this, java.util.Collections.singletonList(issueTo),
                R.string.nobody_consequence_send, this::carveProof);
    }

    private void carveProof() {
        String title = titleInput.getText() != null ? titleInput.getText().toString().trim() : "";
        String content = contentInput.getText() != null ? contentInput.getText().toString().trim() : "";
        boolean transferable = transferableCheckBox.isChecked();
        boolean allSignsRequired = allSignsRequiredCheckBox.isChecked();

        // Validate required fields
        if (title.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_proof_title));
            return;
        }

        if (content.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_input_proof_content));
            return;
        }

        if (!addTypedCosigners()) return;

        String issueTo = readIssueTo();
        if (INVALID_ISSUE_TO.equals(issueTo)) return;

        // Get cosigners from the set
        String[] cosignersArray = null;
        if (!cosignerFids.isEmpty()) {
            cosignersArray = cosignerFids.toArray(new String[0]);
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String issuer = liveKeyInfo.getId();

        Proof proof = new Proof();
        proof.setTitle(title);
        proof.setContent(content);
        if (cosignersArray != null) {
            List<String> cosignersList = new ArrayList<>(cosignerFids);
            proof.setCosignersInvited(cosignersList);
        }
        proof.setTransferable(transferable);
        proof.setActive(!(allSignsRequired && cosignersArray != null));
        proof.setDestroyed(false);
        proof.setIssuer(issuer);
        proof.setOwner(issueTo != null ? issueTo : issuer);

        String feipJson = makeIssueProofFeip(title, content, cosignersArray, transferable, allSignsRequired, issueTo);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            proof.setId(txId);
                            proof.setOnChain(null);
                            proof.setLastHeight(Constants.MaX_HEIGHT);
                            ProofManager.getInstance().saveAndFinish(IssueProofActivity.this, proof, true);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(IssueProofActivity.this, getString(R.string.toast_failed_carve_feip, errorMessage));
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(IssueProofActivity.this, getString(R.string.toast_sign_tx_failed_unsigned));
                            txSender.showUnsignedTxAsQR(IssueProofActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(IssueProofActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();
        } else {
            proof.checkIdWithCreate();

            ProofManager proofManager = ProofManager.getInstance();
            boolean existed = proofManager.checkIfExisted(proof.getId());
            if (existed) {
                DialogUtils.show(new AlertDialog.Builder(this)
                        .setTitle(R.string.proof_existed_title)
                        .setMessage(R.string.proof_already_exists_message)
                        .setPositiveButton(R.string.replace, (dialog, which) -> {
                            proof.setOnChain(null);
                            proofManager.saveAndFinish(this, proof, true);
                        })
                        .setNegativeButton(R.string.cancel, null)
                        );
            } else {
                proof.setOnChain(null);
                proofManager.saveAndFinish(this, proof, true);
            }
        }
    }

    private static String makeIssueProofFeip(String title, String content, String[] cosigners, Boolean transferable, Boolean allSignsRequired, String issueTo) {
        Feip feip = Feip.fromName(PROOF);
        ProofOpData proofOpData = ProofOpData.makeIssue(title, content, cosigners, transferable, allSignsRequired, issueTo);
        feip.setData(proofOpData);
        return feip.toJson();
    }

    private static final String INVALID_ISSUE_TO = "";

    /**
     * Reads the first owner: null when empty or the issuer, INVALID_ISSUE_TO (after a toast) when not a FID.
     */
    private String readIssueTo() {
        String fid = issueToInput.getText() != null ? issueToInput.getText().toString().trim() : "";
        if (fid.isEmpty()) return null;
        if (!KeyTools.isGoodFid(fid)) {
            ToastUtils.makeText(this, getString(R.string.toast_invalid_fid) + ": " + fid);
            return INVALID_ISSUE_TO;
        }
        if (fid.equals(FidManager.getInstance().getLiveFid())) return null;
        return fid;
    }

    private void setupIssueToIoIcons() {
        IoIconsView issueToIoIcons = findViewById(R.id.issueToView).findViewById(R.id.peopleAndScanIcons);
        if (issueToIoIcons == null) return;
        issueToIoIcons.init(this, false, true, true, true, false);
        issueToIoIcons.setSingleChoice(true);
        issueToIoIcons.setOnPeopleClickListener(isSingleChoice -> {
            Intent intent = new Intent(IssueProofActivity.this, SearchFidsOnChainActivity.class);
            intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
            chooseIssueToLauncher.launch(intent);
        });
        issueToIoIcons.setOnScanClickListener(() ->
                IoIconsView.launchQrScanner(IssueProofActivity.this, QR_SCAN_ISSUE_TO_REQUEST_CODE));
    }

    /**
     * Initialize activity result launchers for contact chooser and QR scanner
     */
    private void initializeActivityResultLaunchers() {
        // Initialize choose contact launcher
        chooseContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    java.util.List<String> fids = result.getData()
                            .getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                    if (fids != null) {
                        for (String fid : fids) {
                            addCosignerFid(fid);
                        }
                    }
                }
            }
        );

        chooseIssueToLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    java.util.List<String> fids = result.getData()
                            .getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                    if (fids != null && !fids.isEmpty()) issueToInput.setText(fids.get(0));
                }
            }
        );

        // Initialize QR scan launcher
        qrScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String qrContent = result.getData().getStringExtra("qr_content");
                    if (qrContent != null && !qrContent.trim().isEmpty()) {
                        addCosignerFid(qrContent.trim());
                    }
                }
            }
        );
    }

    /**
     * Setup IoIconsView for cosigners input field
     */
    private void setupCosignersIoIcons() {
        View cosignersView = findViewById(R.id.cosignersView);
        cosignersIoIcons = cosignersView.findViewById(R.id.peopleAndScanIcons);

        if (cosignersIoIcons != null) {
            // Initialize with people and scan icons only (no make QR, no file)
            cosignersIoIcons.init(this, false, true, true, true,false);

            // Set up people icon click listener
            cosignersIoIcons.setOnPeopleClickListener(isSingleChoice -> {
                Intent intent = new Intent(IssueProofActivity.this, SearchFidsOnChainActivity.class);
                intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
                chooseContactLauncher.launch(intent);
            });

            // Set up scan icon click listener
            cosignersIoIcons.setOnScanClickListener(() -> {
                IoIconsView.launchQrScanner(IssueProofActivity.this, QR_SCAN_COSIGNERS_REQUEST_CODE);
            });
        }
    }

    /**
     * Add a cosigner FID to the list and update UI
     */
    private void addCosignerFid(String fid) {
        if (fid == null || fid.trim().isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.toast_invalid_fid));
            return;
        }

        String trimmedFid = fid.trim();

        if (!KeyTools.isGoodFid(trimmedFid)) {
            ToastUtils.makeText(this, getString(R.string.toast_invalid_fid));
            return;
        }

        if (trimmedFid.equals(FidManager.getInstance().getLiveFid())) {
            ToastUtils.makeText(this, getString(R.string.toast_cosigner_is_issuer));
            return;
        }

        // Check if already added
        if (cosignerFids.contains(trimmedFid)) {
            ToastUtils.makeText(this, getString(R.string.toast_cosigner_already_added));
            return;
        }

        // Add to set
        cosignerFids.add(trimmedFid);

        // Create KeyInfo for display
        KeyInfo keyInfo = new KeyInfo();
        keyInfo.setId(trimmedFid);

        // Add card to container
        keyCardContainer.addKeyCard(keyInfo);

        // Clear the input field after adding the FID to the card container
        if (cosignersInput != null) {
            cosignersInput.setText("");
        }

        ToastUtils.makeText(this, getString(R.string.toast_cosigner_added));
    }

    /**
     * Adds the FIDs typed into the cosigners input (separated by commas or spaces).
     * Returns false, leaving the input as it is, when one of them is not a FID.
     */
    private boolean addTypedCosigners() {
        String text = cosignersInput.getText() != null ? cosignersInput.getText().toString().trim() : "";
        if (text.isEmpty()) return true;
        String[] fids = text.split("[,，;；\\s]+");
        for (String fid : fids) {
            if (!fid.isEmpty() && !KeyTools.isGoodFid(fid)) {
                ToastUtils.makeText(this, getString(R.string.toast_invalid_fid) + ": " + fid);
                return false;
            }
        }
        for (String fid : fids) {
            if (!fid.isEmpty() && !cosignerFids.contains(fid)) addCosignerFid(fid);
        }
        cosignersInput.setText("");
        return true;
    }

    /**
     * Update the cosigners input field with current cosigner FIDs
     */
    private void updateCosignersInput() {
        if (cosignersInput != null) {
            String cosignersText = String.join(", ", cosignerFids);
            cosignersInput.setText(cosignersText);
        }
    }

    /**
     * Clear all cosigner cards and reset the set
     */
    private void clearCosigners() {
        cosignerFids.clear();
        keyCardContainer.clearAll();
        updateCosignersInput();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle QR scan result for cosigners
        if (requestCode == QR_SCAN_COSIGNERS_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String qrContent = data.getStringExtra("qr_content");
            if (qrContent != null && !qrContent.trim().isEmpty()) {
                addCosignerFid(qrContent.trim());
            }
        }
    }
}
