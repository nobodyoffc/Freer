package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.CODE;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.CodeOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CodeManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.CodeCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecoverCodeActivity extends BaseCryptoActivity {
    private static final String TAG = "RecoverCodeActivity";
    public static final String EXTRA_CODE_IDS = "extra_code_ids";

    private CodeCardContainer codeCardContainer;
    private LinearLayout codeListContainer;
    private CodeManager codeManager;
    private Button recoverButton;
    private ImageButton backButton;
    private ScrollView codeScrollView;
    private TextView codeStatisticsTextView;
    private List<Code> codeList = new ArrayList<>();

    @Override protected int getLayoutId() { return R.layout.activity_recover_code; }
    @Override protected String getActivityTitle() { return getString(R.string.recover_codes); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        String[] codeIds = getIntent().getStringArrayExtra(EXTRA_CODE_IDS);
        if (codeIds == null || codeIds.length == 0) { ToastUtils.makeText(this, getString(R.string.no_codes_provided)); finish(); return; }

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (fidManager != null && liveFid != null) codeManager = CodeManager.getInstance(this, liveFid);
        } catch (Exception e) { TimberLogger.e(TAG, "Error initializing CodeManager: " + e.getMessage()); }

        if (codeManager == null) { ToastUtils.makeText(this, getString(R.string.code_not_ready_try_later)); finish(); return; }

        for (String codeId : codeIds) {
            Code code = codeManager.getCodeById(codeId);
            if (code != null && !Boolean.TRUE.equals(code.isActive()) && !Boolean.TRUE.equals(code.isClosed())) codeList.add(code);
        }

        if (codeList.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_recoverable_codes_found)); finish(); return; }

        ToolbarUtils.setupToolbar(this, getActivityTitle());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { finish(); } });
        setupButtons();
        loadCodeCardList();
    }

    @Override protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        backButton = findViewById(R.id.back_button);
        codeScrollView = findViewById(R.id.code_scroll_view);
        codeStatisticsTextView = findViewById(R.id.code_statistics);
    }

    @Override protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void loadCodeCardList() {
        codeListContainer = findViewById(R.id.fragment_container);
        codeCardContainer = new CodeCardContainer(this, codeListContainer, ChooseMode.WITHOUT_CHOOSE);
        codeCardContainer.setHideEditButton(true);
        codeCardContainer.setShowClearButton(true);
        codeCardContainer.setOnClearIconClickListener(code -> { codeCardContainer.removeCodeById(code.getId()); codeList.clear(); codeList.addAll(codeCardContainer.getCodeList()); updateUI(); });
        codeCardContainer.setOnCodeListChangedListener(updatedCodeList -> { codeList.clear(); codeList.addAll(updatedCodeList); updateUI(); });
        Map<String, String> cidMap = codeCardContainer.getCidMap(codeList, this);
        for (Code code : codeList) codeCardContainer.addCodeCard(code, cidMap);
        updateUI();
    }

    private void updateUI() { updateButtonStates(); updateStatistics(); }
    private void updateButtonStates() { boolean hasCodes = codeCardContainer != null && !codeCardContainer.getCodeList().isEmpty(); recoverButton.setEnabled(hasCodes); recoverButton.setAlpha(hasCodes ? 1.0f : 0.5f); }
    private void updateStatistics() { if (codeStatisticsTextView != null && codeCardContainer != null) { codeStatisticsTextView.setText(getString(R.string.total_count, codeCardContainer.getCodeList().size())); codeStatisticsTextView.setVisibility(View.VISIBLE); } }

    @Override protected void setupButtons() {
        recoverButton.setOnClickListener(v -> { hideKeyboard(); if (codeCardContainer == null) return; List<Code> codesToRecover = new ArrayList<>(codeCardContainer.getCodeList()); if (codesToRecover.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_codes_to_recover)); return; } performRecoverOperation(codesToRecover); });
        backButton.setOnClickListener(v -> { hideKeyboard(); finish(); });
    }

    private void performRecoverOperation(List<Code> codesToRecover) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) { ToastUtils.makeText(this, getString(R.string.no_active_key_available)); return; }

        List<String> codeIds = new ArrayList<>();
        for (Code code : codesToRecover) if (code.getId() != null) codeIds.add(code.getId());
        if (codeIds.isEmpty()) { ToastUtils.makeText(this, getString(R.string.no_valid_code_ids)); return; }

        Feip feip = Feip.fromName(CODE);
        CodeOpData codeOpData = CodeOpData.makeRecover(codeIds);
        feip.setData(codeOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            for (Code code : codesToRecover) { code.setActive(true); code.setOnChain(null); codeManager.updateCode(code); }
                            codeManager.commit();
                            setResult(Activity.RESULT_OK);
                            finish();
                        });
                    }
                    @Override public void onError(String errorMessage) { runOnUiThread(() -> ToastUtils.makeText(RecoverCodeActivity.this, getString(R.string.failed_to_recover_codes) + ": " + errorMessage)); }
                    @Override public void onUnsignedTx(RawTxInfo rawTxInfo) { runOnUiThread(() -> { ToastUtils.makeText(RecoverCodeActivity.this, R.string.cannot_sign_transaction); txSender.showUnsignedTxAsQR(RecoverCodeActivity.this, rawTxInfo); }); }
                    @Override public void onUnbroadcasted(String signedTxHex) { runOnUiThread(() -> txSender.showSignedTxAsQR(RecoverCodeActivity.this, signedTxHex)); }
                });
            }).start();
        } else { ToastUtils.makeText(this, R.string.failed_to_get_private_key); }
    }
}

