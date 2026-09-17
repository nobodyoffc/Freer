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
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.feip.FeipHandler;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.onboarding.CidFeip;
import com.fc.freer.onboarding.LiveFidRecord;
import com.fc.freer.onboarding.PendingIdentityCarve;
import com.fc.freer.onboarding.PendingIdentityCarves;

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
    /** The name {@link #generatedCid} was previewed for. */
    private String checkedName;

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

        showInFlightCarve();
    }

    private void setupListeners() {
        // TextWatcher removed - checking now done via Check button
    }

    /**
     * If a CID carve from this FID is still waiting for the chain, say so and keep the carve
     * button off: a second press would pay a second fee for the same name.
     */
    private boolean showInFlightCarve() {
        PendingIdentityCarve inFlight = liveFid == null ? null : PendingIdentityCarves.of(this)
                .getInFlight(liveFid, PendingIdentityCarve.Kind.CID, System.currentTimeMillis());
        if (inFlight == null) return false;
        yourCidLabel.setVisibility(VISIBLE);
        yourCidValue.setVisibility(VISIBLE);
        yourCidValue.setText(getString(R.string.carve_already_pending, inFlight.txid));
        yourCidTick.setVisibility(GONE);
        setCarveButton(false);
        return true;
    }

    private void checkCidName() {
        generatedCid = null;
        checkedName = null;
        setCarveButton(false);
        if (yourCidLabel == null || yourCidValue == null || liveFid == null) {
            ToastUtils.showError(this, getString(R.string.error_no_live_fid));
            return;
        }
        if (showInFlightCarve()) return;

        String name = nameInput.getText().toString().trim();
        yourCidLabel.setVisibility(VISIBLE);
        yourCidValue.setVisibility(VISIBLE);
        yourCidTick.setVisibility(GONE);
        if (name.isEmpty()) {
            yourCidValue.setText("");
            ToastUtils.showWarning(this, getString(R.string.please_enter_valid_name));
            return;
        }
        if (!CidFeip.isGoodName(name)) {
            yourCidValue.setText(getString(R.string.cid_name_rule));
            return;
        }
        yourCidValue.setText(getString(R.string.gs_status_checking));

        // FEIP3 decides the suffix on the indexer. Run the same rule against the chain now:
        // this FID's own usedCids read fresh (a CID registered elsewhere counts toward the
        // limit), and every candidate checked against other FIDs that have ever used it.
        new Thread(() -> {
            CidFeip.Preview preview = null;
            String error = null;
            try {
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) throw new IllegalStateException(getString(R.string.error_no_api_client));
                LiveFidRecord own = LiveFidRecord.fetch(fapiClient, liveFid).record;
                preview = CidFeip.preview(name, liveFid, own.usedCids, fapiClient::getFidByUsedCid);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error checking CID availability: " + e.getMessage(), e);
                error = e.getMessage();
            }
            final CidFeip.Preview result = preview;
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // The user edited the name while the chain was being asked; that answer is stale.
                if (!name.equals(nameInput.getText().toString().trim())) return;
                if (result == null) {
                    yourCidValue.setText("");
                    ToastUtils.showError(this, getString(R.string.error_checking_cid_availability)
                            + (failure != null ? ": " + failure : ""));
                    return;
                }
                showPreview(name, result);
            });
        }).start();
    }

    private void showPreview(String name, CidFeip.Preview preview) {
        switch (preview.kind) {
            case NEW:
                yourCidValue.setText(preview.cid);
                break;
            case REACTIVATE:
                yourCidValue.setText(getString(R.string.cid_preview_reactivate, preview.cid));
                break;
            case LIMIT_REACHED:
                yourCidValue.setText(getString(R.string.cid_preview_limit, preview.cid, CidFeip.MAX_USED_CIDS));
                break;
            case UNAVAILABLE:
            default:
                yourCidValue.setText(getString(R.string.cid_preview_unavailable, name));
                break;
        }
        boolean carvable = preview.isCarvable();
        yourCidTick.setVisibility(carvable ? VISIBLE : GONE);
        if (carvable) {
            generatedCid = preview.cid;
            checkedName = name;
        }
        setCarveButton(carvable);
    }

    private void setCarveButton(boolean enabled) {
        if(enabled){
            carveButton.setAlpha(1f);
        }else {
            carveButton.setAlpha(0.5f);
        }
        carveButton.setEnabled(enabled);
    }

    private void carveCid() {
        String name = nameInput.getText().toString().trim();
        if (generatedCid == null || checkedName == null || !checkedName.equals(name)) {
            // Only a name the chain was just asked about may be carved.
            setCarveButton(false);
            ToastUtils.showWarning(this, getString(R.string.please_enter_valid_name));
            return;
        }
        if (showInFlightCarve()) return;

        try {
            // Create FEIP JSON for CID registration using FeipHandler
            FeipHandler feipHandler = new FeipHandler();
            String feipJson = feipHandler.cidRegister(name);

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
                            // Not written into the KeyInfo: the parser picks the suffix, and the
                            // carve counts only once the chain shows it. The next refresh of the
                            // FID's record clears this and brings the CID in.
                            PendingIdentityCarves.of(SetCidActivity.this).record(PendingIdentityCarve.cid(
                                    liveFid, name, txId, System.currentTimeMillis()));
                            runOnUiThread(() -> {
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
        // "Never" silenced an automatic CID prompt that the getting-started checklist replaced.
        if (neverButton != null) {
            neverButton.setVisibility(GONE);
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
}
