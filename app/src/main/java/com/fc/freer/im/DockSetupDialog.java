package com.fc.freer.im;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.HomeOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;

public class DockSetupDialog {

    public static void show(Activity activity, String suggestedUrl) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_dock_setup, null);

        TextInputEditText urlInput = dialogView.findViewById(R.id.dock_url_input);
        ImageButton backButton = dialogView.findViewById(R.id.btn_back);
        ImageButton confirmButton = dialogView.findViewById(R.id.btn_confirm);

        // Default the DOCK server to the current FAPI client's service ID (SID).
        if (suggestedUrl == null || suggestedUrl.isEmpty()) {
            suggestedUrl = currentFapiSid();
        }
        if (suggestedUrl != null && !suggestedUrl.isEmpty()) {
            urlInput.setText(suggestedUrl);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        backButton.setOnClickListener(v -> {
            hideKeyboard(activity, v);
            dialog.dismiss();
        });

        confirmButton.setOnClickListener(v -> {
            hideKeyboard(activity, v);
            String dockUrl = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
            if (dockUrl.isEmpty()) {
                ToastUtils.makeText(activity, R.string.dock_url_empty);
                return;
            }
            dialog.dismiss();
            registerDock(activity, dockUrl);
        });

        dialogView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                hideKeyboard(activity, v);
            }
            return false;
        });

        dialog.show();
    }

    private static void registerDock(Activity activity, String dockUrl) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(activity, R.string.no_active_fid);
            return;
        }

        Map<String, String> homeMap = new HashMap<>();
        homeMap.put("DOCK@No1_NrC7", dockUrl);

        HomeOpData homeOpData = new HomeOpData();
        homeOpData.setOp(HomeOpData.Op.REGISTER.toLowerCase());
        homeOpData.setHome(homeMap);

        Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.HOME);
        feip.setData(homeOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(activity, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                        new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                        new TxSender.TxCallback() {
                            @Override
                            public void onSuccess(String txId) {
                                // Enter the pending state: keep IM disabled and suppress the
                                // setup dialog until the registration TX confirms on-chain.
                                notifyRegistrationTxSent(txId);
                            }

                            @Override
                            public void onError(String errorMessage) {
                                activity.runOnUiThread(() ->
                                        ToastUtils.makeText(activity,
                                                activity.getString(R.string.failed_to_register_dock) + ": " + errorMessage));
                            }

                            @Override
                            public void onUnsignedTx(RawTxInfo rawTxInfo) {
                                activity.runOnUiThread(() -> {
                                    ToastUtils.makeText(activity, R.string.cannot_sign_transaction);
                                    txSender.showUnsignedTxAsQR(activity, rawTxInfo);
                                });
                            }

                            @Override
                            public void onUnbroadcasted(String signedTxHex) {
                                activity.runOnUiThread(() ->
                                        txSender.showSignedTxAsQR(activity, signedTxHex));
                            }
                        });
            }).start();
        } else {
            ToastUtils.makeText(activity, R.string.failed_to_get_private_key);
        }
    }

    private static String currentFapiSid() {
        try {
            FapiClient client = (FapiClient) ApiCenter.getInstance()
                    .getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client != null && client.getApiAccount() != null) {
                return client.getApiAccount().getProviderId();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void notifyRegistrationTxSent(String txId) {
        com.fc.freer.model.Setting setting =
                com.fc.freer.initiate.SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return;
        ImManager im = setting.getImManager();
        if (im != null) im.onRegistrationTxSent(txId);
    }

    private static void hideKeyboard(Activity activity, View view) {
        if (activity == null || view == null) return;
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
