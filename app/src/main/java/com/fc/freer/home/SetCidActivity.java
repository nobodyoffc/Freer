package com.fc.freer.home;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;


import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.CidOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.feip.FeipHandler;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;

import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.List;

public class SetCidActivity extends com.fc.freer.BaseCryptoActivity {
    private static final String TAG = "SetCidActivity";

    // Live FID card elements
    private ImageView fidAvatar;
    private TextView fidName;
    private TextView fidLabel;
    private ImageView fidEditIcon;
    private TextView fidCash;
    private TextView fidBalance;
    private TextView fidCd;
    private ImageView fidNoPrikeyIcon;

    // CID information
    private TextView currentCidLabel;
    private TextView currentCidValue;
    private TextView usedCidsLabel;
    private TextView usedCidsValue;

    // Input and preview
    private EditText nameInput;
    private TextView yourCidLabel;
    private TextView yourCidValue;
    private ImageView yourCidTick;

    // Buttons
    private Button neverButton;
    private Button checkButton;
    private ImageButton carveButton;

    private KeyInfo liveKeyInfo;
    private String liveFid;
    private String generatedCid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLiveFidCard();
        setupListeners();
        displayCidInfo();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_set_cid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.set_cid);
    }

    @Override
    protected void initializeViews() {
        // Live FID card elements
        fidAvatar = findViewById(R.id.fidAvatar);
        fidName = findViewById(R.id.fidName);
        fidLabel = findViewById(R.id.fidLabel);
        fidEditIcon = findViewById(R.id.fidEditIcon);
        fidCash = findViewById(R.id.fidCash);
        fidBalance = findViewById(R.id.fidBalance);
        fidCd = findViewById(R.id.fidCd);
        fidNoPrikeyIcon = findViewById(R.id.multisigIcon);

        // CID information
        currentCidLabel = findViewById(R.id.currentCidLabel);
        currentCidValue = findViewById(R.id.currentCidValue);
        usedCidsLabel = findViewById(R.id.usedCidsLabel);
        usedCidsValue = findViewById(R.id.usedCidsValue);

        // Input and preview
        nameInput = findViewById(R.id.nameInput);
        yourCidLabel = findViewById(R.id.yourCidLabel);
        yourCidValue = findViewById(R.id.yourCidValue);
        yourCidTick = findViewById(R.id.yourCidTick);


        // Buttons
        neverButton = findViewById(R.id.neverButton);
        checkButton = findViewById(R.id.checkButton);
        carveButton = findViewById(R.id.carveButton);
        setCarveButton(false);

        // Get live FID data
        FidManager fidManager = FidManager.getInstance();
        if (fidManager != null) {
            liveKeyInfo = fidManager.getLiveKeyInfo();
            liveFid = fidManager.getLiveFid();
        }

        if (liveKeyInfo == null || liveFid == null) {
            ToastUtils.showError(this, getString(R.string.error_no_live_fid));
            finish();
        }
    }

    private void setupLiveFidCard() {
        if (liveKeyInfo == null || liveFid == null) return;

        try {
            // Set avatar
            if (fidAvatar != null) {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(liveFid);
                if (avatarBitmap != null) {
                    fidAvatar.setImageBitmap(avatarBitmap);
                } else {
                    fidAvatar.setImageResource(R.drawable.ic_person);
                }
            }

            // Set name (cid if available, otherwise fid)
            if (fidName != null) {
                String displayName = liveKeyInfo.getCid();
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = liveFid;
                }
                fidName.setText(displayName);
            }

            // Set label or edit icon
            if (fidLabel != null && fidEditIcon != null) {
                String label = liveKeyInfo.getLabel();
                if (label != null && !label.trim().isEmpty()) {
                    fidLabel.setText(label);
                    fidLabel.setVisibility(VISIBLE);
                    fidEditIcon.setVisibility(GONE);
                } else {
                    fidLabel.setVisibility(GONE);
                    fidEditIcon.setVisibility(VISIBLE);
                }
            }

            // Set cash amount
            if (fidCash != null) {
                Long cash = liveKeyInfo.getCash();
                if (cash != null && cash > 0) {
                    fidCash.setText(String.valueOf(cash));
                } else {
                    fidCash.setText("-");
                }
            }

            // Set balance
            if (fidBalance != null) {
                Long balance = liveKeyInfo.getBalance();
                if (balance != null && balance > 0) {
                    double balanceInCoins = FchUtils.satoshiToCoin(balance);
                    fidBalance.setText(formatBalance(balanceInCoins));
                } else {
                    fidBalance.setText("-");
                }
            }

            // Set cd
            if (fidCd != null) {
                Long cd = liveKeyInfo.getCd();
                if (cd != null && cd > 0) {
                    fidCd.setText(formatLargeNumber(cd));
                } else {
                    fidCd.setText("-");
                }
            }

            // Set no prikey icon
            if (fidNoPrikeyIcon != null) {
                String prikeyCipher = liveKeyInfo.getPrikeyCipher();
                if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                    if (liveFid != null && liveFid.startsWith("3")) {
                        fidNoPrikeyIcon.setImageResource(R.drawable.ic_people);
                    } else {
                        fidNoPrikeyIcon.setImageResource(R.drawable.ic_no_prikey);
                    }
                    fidNoPrikeyIcon.setVisibility(VISIBLE);
                } else {
                    fidNoPrikeyIcon.setVisibility(GONE);
                }
            }

        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error setting up live FID card: " + ex.getMessage(), ex);
        }
    }

    private void displayCidInfo() {
        if (liveKeyInfo == null) return;

        // Display current CID
        String currentCid = liveKeyInfo.getCid();
        if (currentCidValue != null) {
            if (currentCid != null && !currentCid.trim().isEmpty()) {
                currentCidValue.setText(currentCid);
            } else {
                currentCidValue.setVisibility(GONE);
                currentCidLabel.setVisibility(GONE);
            }
        }

        // Display used CIDs
        List<String> usedCids = liveKeyInfo.getUsedCids();
        if (usedCidsValue != null) {
            if (usedCids != null && !usedCids.isEmpty()) {
                StringBuilder usedCidsStr = new StringBuilder();
                for (int i = 0; i < usedCids.size(); i++) {
                    if (i > 0) usedCidsStr.append(", ");
                    usedCidsStr.append(usedCids.get(i));
                }
                usedCidsValue.setText(usedCidsStr.toString());
            } else {
                usedCidsValue.setVisibility(GONE);
                usedCidsLabel.setVisibility(GONE);
            }
        }

        if(checkIfExceed4Cids())nameInput.setHint(getString(R.string.input_the_name_of_any_used_cid));
    }

    private void setupListeners() {
        // TextWatcher removed - checking now done via Check button
    }

    private boolean checkCidName() {
        if (yourCidLabel == null || yourCidValue == null || liveFid == null) {
            ToastUtils.showError(this, getString(R.string.error_no_live_fid));
            return false;
        }

        String inputName = nameInput.getText().toString();
        if (inputName.trim().isEmpty()) {
            yourCidLabel.setVisibility(VISIBLE);
            yourCidValue.setText("");
            yourCidValue.setVisibility(VISIBLE);
            yourCidTick.setVisibility(GONE);
            generatedCid = null;
            ToastUtils.showWarning(this, getString(R.string.please_enter_valid_name));
            return false;
        }

        String trimmedName = inputName.trim();

        // Validate CID name
        CidOpData cidOpData = new CidOpData();
        if (!cidOpData.isGoodCidName(trimmedName)) {
            yourCidLabel.setVisibility(VISIBLE);
            yourCidValue.setText(getString(R.string.invalid_name));
            yourCidValue.setVisibility(VISIBLE);
            yourCidTick.setVisibility(VISIBLE);
            generatedCid = null;
            ToastUtils.showWarning(this, getString(R.string.invalid_name));
            return false;
        }

        // Generate CID with last 4 chars of FID
        String fidSuffix = liveFid.length() >= 4 ? liveFid.substring(liveFid.length() - 4) : liveFid;
        generatedCid = trimmedName + "_" + fidSuffix;

        yourCidLabel.setVisibility(VISIBLE);
        yourCidValue.setText(generatedCid);
        yourCidValue.setVisibility(VISIBLE);
        yourCidTick.setVisibility(VISIBLE);
        // Check if CID is available
        checkCidAvailability(generatedCid, trimmedName, 4);
        return true; // Initial validation passed, availability check is async
    }

    private void checkCidAvailability(String cidToCheck, String baseName, int suffixLength) {
        if (checkIfExceed4Cids()){
            setCarveButton(false);
            return;
        }
        if (liveFid == null) {
            setCarveButton(false);
            return;
        }
        if(nameInput.getText().toString().isEmpty()){
            setCarveButton(false);
            return;
        }
        // Run availability check in background
        new Thread(() -> {
            try {
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) {
                    runOnUiThread(() -> {
                        ToastUtils.showError(this, getString(R.string.error_no_api_client));
                        setCarveButton(false);
                    });
                    return;
                }

                String ownerFid = fapiClient.getFidByUsedCid(cidToCheck);

                runOnUiThread(() -> {
                    if (ownerFid != null) {
                        // CID is already taken, try with longer suffix
                        if (suffixLength < liveFid.length()) {
                            int newSuffixLength = Math.min(suffixLength + 1, liveFid.length());
                            String newFidSuffix = liveFid.substring(liveFid.length() - newSuffixLength);
                            String newCid = baseName + "_" + newFidSuffix;
                            generatedCid = newCid;
                            yourCidLabel.setVisibility(VISIBLE);
                            yourCidValue.setText(newCid);
                            yourCidValue.setVisibility(VISIBLE);
                            yourCidTick.setVisibility(VISIBLE);
                            setCarveButton(false);
                            // Check the new CID
                            checkCidAvailability(newCid, baseName, newSuffixLength);
                        } else {
                            // Even with full FID as suffix, still taken
                            generatedCid = null;
                            setCarveButton(false);
                        }
                    } else {
                        // CID is available
                        setCarveButton(true);
                    }
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error checking CID availability: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    ToastUtils.showError(this, getString(R.string.error_checking_cid_availability));
                    setCarveButton(false);
                });
            }
        }).start();
    }

    private void setCarveButton(boolean enabled) {
        if(enabled){
            carveButton.setAlpha(1f);
        }else {
            carveButton.setAlpha(0.5f);
        }
        carveButton.setEnabled(enabled);
    }


    private boolean checkIfExceed4Cids() {
        if(liveKeyInfo!=null && liveKeyInfo.getUsedCids()!=null && liveKeyInfo.getUsedCids().size()==4){
            String inputName = nameInput.getText().toString();
            boolean good = false;
            for(String cid:liveKeyInfo.getUsedCids()){
                if(inputName.equals(cid.substring(0,cid.indexOf("_")))){
                    good=true;
                    break;
                }
            }

            if(!good){
                ToastUtils.makeText(this, getString(R.string.no_more_than_4_used_cids_allowed));
                nameInput.setHint(getString(R.string.input_the_name_of_any_used_cid));
                return true;
            }
        }
        return false;
    }

    private void carveCid() {
        if (generatedCid == null || generatedCid.trim().isEmpty()) {
            ToastUtils.showWarning(this, getString(R.string.please_enter_valid_name));
            return;
        }

        try {
            // Create FEIP JSON for CID registration using FeipHandler
            FeipHandler feipHandler = new FeipHandler();
            String feipJson = feipHandler.cidRegister(nameInput.getText().toString().trim());

            if (feipJson == null || feipJson.trim().isEmpty()) {
                ToastUtils.showError(this, getString(R.string.error_creating_transaction));
                return;
            }

            // A multisig FID has no prikey of its own: the tx is built here and
            // signed by the cosigners in SignMultisigTxActivity.
            boolean isMultisig = liveFid != null && liveFid.startsWith("3");
            if (isMultisig && liveKeyInfo.getMultisign() == null) {
                ToastUtils.showError(this, getString(R.string.failed_to_get_multisign_info_for_fid) + ": " + liveFid);
                return;
            }

            // Use the same logic as CreateContactActivity for on-chain operations
            byte[] prikey = isMultisig ? null : SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
            if (isMultisig || prikey != null) {
                new Thread(() -> {
                    CashManager cashManager = CashManager.getInstance();
                    TxSender txSender = new TxSender();
                    txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                liveKeyInfo.setCid(generatedCid);

                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SetCidActivity.this, getString(R.string.toast_failed_carve_cid_onchain, errorMessage));
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(SetCidActivity.this, getString(R.string.toast_sign_tx_failed_unsigned));
                                txSender.showUnsignedTxAsQR(SetCidActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                // Show signed transaction as QR code for manual broadcasting
                                txSender.showSignedTxAsQR(SetCidActivity.this, signedTxHex);
                            });
                        }
                    });
                }).start();
            } else {
                ToastUtils.showError(this, getString(R.string.failed_to_get_prikey));
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating CID registration transaction: " + e.getMessage(), e);
            ToastUtils.showError(this, getString(R.string.error_creating_transaction) + ": " + e.getMessage());
        }
    }

    private String formatBalance(double balance) {
        if (balance >= 100000) {
            return formatLargeNumber((long) balance);
        } else if (balance >= 1000) {
            return String.valueOf((long) balance);
        } else {
            return String.valueOf(balance);
        }
    }

    private String formatLargeNumber(long number) {
        if (number >= 1000000000) {
            return String.format("%.1fb", number / 1000000000.0);
        } else if (number >= 1000000) {
            return String.format("%.1fm", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fk", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // SetCidActivity doesn't need QR scanning functionality
        // This method is required by BaseCryptoActivity but not used here
    }

    @Override
    protected void setupButtons() {
        // Never button - mark as promoted to stop future CID prompts
        if (neverButton != null) {
            neverButton.setOnClickListener(v -> markPromotedSetCid());
        }

        // Check button
        if (checkButton != null) {
            checkButton.setOnClickListener(v -> checkCidName());
        }

        // Carve button
        if (carveButton != null) {
            carveButton.setOnClickListener(v -> {
                // Check the input first before carving
                    carveCid();
            });
        }
    }

    /**
     * Mark that the user has been prompted to set CID and chose "Never"
     * This will prevent future prompts from showing
     */
    private void markPromotedSetCid() {
        try {
            com.fc.freer.initiate.SettingManager settingManager = com.fc.freer.initiate.SettingManager.getInstance();
            com.fc.freer.model.Setting currentSetting = settingManager.getCurrentSetting();

            if (currentSetting != null) {
                // Mark as promoted in the state map
                if (currentSetting.getStateMap() == null) {
                    currentSetting.setStateMap(new java.util.HashMap<>());
                }
                currentSetting.getStateMap().put(com.fc.freer.model.Setting.KEY_PROMOTED_SET_CID, true);

                // Save the updated setting
                settingManager.saveSettings(this, currentSetting);

                TimberLogger.d(TAG, "Marked KEY_PROMOTED_SET_CID as true to stop future CID prompts");
                ToastUtils.makeText(this, getString(R.string.setting_saved));

                // Close the activity
                finish();
            } else {
                ToastUtils.showError(this, getString(R.string.error_no_current_setting));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error marking promoted set CID: " + e.getMessage(), e);
            ToastUtils.showError(this, getString(R.string.error_saving_settings) + ": " + e.getMessage());
        }
    }
}