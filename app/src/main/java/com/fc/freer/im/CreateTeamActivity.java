package com.fc.freer.im;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;

public class CreateTeamActivity extends BaseCryptoActivity {

    private TextInputEditText nameInput;
    private TextInputEditText consensusInput;
    private TextInputEditText descInput;
    private TextInputEditText dockInput;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_CONSENSUS = 1002;
    private static final int QR_SCAN_DESC = 1003;
    private static final int QR_SCAN_DOCK = 1004;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_team;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_team);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();

        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.consensusView, R.id.scanIcon, QR_SCAN_CONSENSUS);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.dockView, R.id.scanIcon, QR_SCAN_DOCK);
    }

    @Override
    protected void initializeViews() {
        View nameView = findViewById(R.id.nameView);
        View consensusView = findViewById(R.id.consensusView);
        View descView = findViewById(R.id.descView);
        View dockView = findViewById(R.id.dockView);

        nameInput = nameView.findViewById(R.id.textInput);
        nameInput.setHint(R.string.team_name);

        consensusInput = consensusView.findViewById(R.id.textInput);
        consensusInput.setHint(R.string.consensus_id_hint);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.team_description);

        dockInput = dockView.findViewById(R.id.textInput);
        dockInput.setHint(R.string.home_dock_hint);

        clearButton = findViewById(R.id.clearButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishTeam();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        nameInput.setText("");
        consensusInput.setText("");
        descInput.setText("");
        dockInput.setText("");
    }

    private Map<String, String> buildHomeMap() {
        String dock = getText(dockInput);
        if (dock.isEmpty()) return null;

        Map<String, String> homeMap = new HashMap<>();
        homeMap.put("DOCK@No1_NrC7", dock);
        return homeMap;
    }

    private void publishTeam() {
        String name = getText(nameInput);
        String consensusId = getText(consensusInput);
        String desc = getText(descInput);

        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.team_name_required);
            return;
        }

        if (consensusId.isEmpty()) {
            ToastUtils.makeText(this, R.string.consensus_id_required);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        TeamOpData opData = TeamOpData.makeCreate(
                name,
                consensusId,
                null, null, null,
                desc.isEmpty() ? null : desc
        );

        Map<String, String> homeMap = buildHomeMap();
        if (homeMap != null) {
            opData.setHome(homeMap);
        }

        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        publishButton.setEnabled(false);

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(CreateTeamActivity.this,
                                        getString(R.string.team_published_successfully, txId));
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                publishButton.setEnabled(true);
                                ToastUtils.makeText(CreateTeamActivity.this,
                                        getString(R.string.failed_to_publish_team) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                publishButton.setEnabled(true);
                                ToastUtils.makeText(CreateTeamActivity.this,
                                        R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(CreateTeamActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                publishButton.setEnabled(true);
                                txSender.showSignedTxAsQR(CreateTeamActivity.this, signedTxHex);
                            });
                        }
                    });
        }).start();
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_NAME:
                nameInput.setText(qrContent);
                break;
            case QR_SCAN_CONSENSUS:
                consensusInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_DOCK:
                dockInput.setText(qrContent);
                break;
        }
    }
}
