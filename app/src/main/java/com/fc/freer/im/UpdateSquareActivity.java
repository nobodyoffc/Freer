package com.fc.freer.im;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.SquareOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.account.CashActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateSquareActivity extends BaseCryptoActivity {
    private static final String TAG = "UpdateSquareActivity";
    public static final String EXTRA_SQUARE_ID = "extra_square_id";

    private TextInputEditText nameInput;
    private TextInputEditText descInput;
    private TextInputEditText dockInput;
    private TextView cddNoteView;

    private ImageButton clearButton;
    private ImageButton publishButton;
    private ImageButton backButton;
    private ImageButton chooseDockButton;

    private ActivityResultLauncher<Intent> chooseDockLauncher;

    private String groupId;

    private String pendingFeipJson;
    private long pendingCddToUpdate;

    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_DESC = 1002;
    private static final int QR_SCAN_DOCK = 1006;
    private static final int REQUEST_SELECT_CASH = 2001;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_update_group;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_square);
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
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.dockView, R.id.scanIcon, QR_SCAN_DOCK);

        loadSquareInfo();
    }

    @Override
    protected void initializeViews() {
        View nameView = findViewById(R.id.nameView);
        View descView = findViewById(R.id.descView);
        View dockView = findViewById(R.id.dockView);

        nameInput = nameView.findViewById(R.id.textInput);
        nameInput.setHint(R.string.square_name);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.square_description);

        dockInput = dockView.findViewById(R.id.textInput);
        dockInput.setHint(R.string.home_dock_hint);

        cddNoteView = findViewById(R.id.cdd_note);

        clearButton = findViewById(R.id.clearButton);
        publishButton = findViewById(R.id.publishButton);
        backButton = findViewById(R.id.back_button);
        chooseDockButton = findViewById(R.id.choose_dock_button);

        chooseDockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), dockInput);
                    }
                });

        groupId = getIntent().getStringExtra(EXTRA_SQUARE_ID);
        if (groupId == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            finish();
        }
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            nameInput.setText("");
            descInput.setText("");
            dockInput.setText("");
        });

        publishButton.setOnClickListener(v -> {
            hideKeyboard();
            publishUpdate();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });

        chooseDockButton.setOnClickListener(v -> {
            hideKeyboard();
            chooseDockLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.DOCK_NO1_NRC7, getString(R.string.server_setup_dock_label)));
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_NAME:
                nameInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_DOCK:
                dockInput.setText(qrContent);
                break;
        }
    }

    private void loadSquareInfo() {
        if (groupId == null) return;

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) return;

        Square square = setting.getImManager().getSquare(groupId);
        if (square != null) {
            if (square.getName() != null) nameInput.setText(square.getName());
            if (square.getDesc() != null) descInput.setText(square.getDesc());
            if (square.getHome() != null) {
                String dockValue = square.getHome().get("DOCK@No1_NrC7");
                if (dockValue == null) dockValue = square.getHome().get("DOCK");
                if (dockValue != null) dockInput.setText(dockValue);
            }
            if (square.getCddToUpdate() != null) {
                cddNoteView.setText(getString(R.string.cdd_requirement_note, square.getCddToUpdate()));
                cddNoteView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void publishUpdate() {
        String name = getText(nameInput);

        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.square_name_required);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        String desc = getText(descInput);
        Map<String, String> homeMap = buildHomeMap();
        SquareOpData opData = SquareOpData.makeUpdate(groupId, name, desc.isEmpty() ? null : desc, homeMap);
        Feip feip = Feip.fromName("Square");
        feip.setData(opData);
        pendingFeipJson = feip.toJson();
        pendingCddToUpdate = resolveCddToUpdate();

        // The update tx must destroy more CD than cddToUpdate. Let the user pick which
        // cashes to spend so we never consume cashes they want to keep automatically.
        publishButton.setEnabled(false);

        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.cdd_required_title)
                .setMessage(getString(R.string.select_cash_for_cdd, pendingCddToUpdate))
                .setPositiveButton(R.string.select_cash, (dialog, which) -> {
                    Intent intent = new Intent(this, CashActivity.class);
                    intent.putExtra(CashActivity.EXTRA_SELECT_MODE, true);
                    startActivityForResult(intent, REQUEST_SELECT_CASH);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> publishButton.setEnabled(true))
                .setOnCancelListener(dialog -> publishButton.setEnabled(true))
                );
    }

    private long resolveCddToUpdate() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null && setting.getImManager() != null) {
            Square square = setting.getImManager().getSquare(groupId);
            if (square != null && square.getCddToUpdate() != null) {
                return square.getCddToUpdate();
            }
        }
        return 0L;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_SELECT_CASH) return;

        if (resultCode != RESULT_OK || data == null) {
            publishButton.setEnabled(true);
            return;
        }

        ArrayList<String> cashJsonList = data.getStringArrayListExtra(CashActivity.EXTRA_SELECTED_CASH);
        if (cashJsonList == null || cashJsonList.isEmpty()) {
            publishButton.setEnabled(true);
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        List<Cash> selectedCashes = new ArrayList<>();
        for (String json : cashJsonList) {
            Cash cash = Cash.fromJson(json);
            if (cash != null) selectedCashes.add(cash);
        }

        if (selectedCashes.isEmpty()) {
            publishButton.setEnabled(true);
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        sendUpdateWithCashes(selectedCashes);
    }

    private void sendUpdateWithCashes(List<Cash> selectedCashes) {
        if (pendingFeipJson == null) {
            publishButton.setEnabled(true);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            publishButton.setEnabled(true);
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            publishButton.setEnabled(true);
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        final String sender = liveKeyInfo.getId();
        final String feipJson = pendingFeipJson;
        final long cddToUpdate = pendingCddToUpdate;

        new Thread(() -> {
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            Long bestHeight = (fapiClient != null) ? fapiClient.getBestHeight() : null;

            // Verify the chosen cashes destroy strictly more CD than required before sending.
            long totalCd = 0;
            for (Cash cash : selectedCashes) {
                Long cd = (bestHeight != null) ? cash.makeCd(bestHeight) : cash.getCd();
                if (cd != null && cd > 0) totalCd += cd;
            }

            if (totalCd <= cddToUpdate) {
                final long finalTotalCd = totalCd;
                runOnUiThread(() -> {
                    publishButton.setEnabled(true);
                    ToastUtils.makeText(UpdateSquareActivity.this,
                            getString(R.string.cdd_not_enough_to_update, finalTotalCd, cddToUpdate));
                });
                return;
            }

            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            RawTxInfo rawTxInfo = new RawTxInfo(sender, selectedCashes, null, feipJson, cddToUpdate,
                    TxHandler.DEFAULT_FEE_RATE, null, RawTxInfo.VERSION_2);

            txSender.sendTx(this, rawTxInfo, prikey, cashManager, new TxHandler(), fapiClient,
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                publishButton.setEnabled(true);
                                ToastUtils.makeText(UpdateSquareActivity.this,
                                        getString(R.string.failed_to_update_square) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                publishButton.setEnabled(true);
                                ToastUtils.makeText(UpdateSquareActivity.this,
                                        R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(UpdateSquareActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                publishButton.setEnabled(true);
                                txSender.showSignedTxAsQR(UpdateSquareActivity.this, signedTxHex);
                            });
                        }

                        @Override
                        public void onCancelled() {
                            runOnUiThread(() -> publishButton.setEnabled(true));
                        }
                    }, true);
        }).start();
    }

    private Map<String, String> buildHomeMap() {
        String dock = getText(dockInput);
        if (dock.isEmpty()) return null;

        Map<String, String> homeMap = new HashMap<>();
        homeMap.put("DOCK@No1_NrC7", dock);
        return homeMap;
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }
}
