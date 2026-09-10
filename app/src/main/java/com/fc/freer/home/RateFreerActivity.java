package com.fc.freer.home;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.ReputationOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.account.CashActivity;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RateFreerActivity extends BaseCryptoActivity {

    private static final String TAG = "RateFreerActivity";

    public static final String EXTRA_FREER_JSON = "extra_freer_json";

    private static final String RATE_GOOD = "good";
    private static final String RATE_BAD = "bad";

    private Freer currentFreer;

    // Views
    private TextView freerTypeLabel;
    private TextView freerIdValue;
    private TextView freerCidValue;
    private TextView freerReputationValue;
    private TextView freerHotValue;
    private EditText causeInput;
    private LinearLayout cashCardsContainer;
    private ImageButton plusCashButton;
    private LinearLayout totalCddContainer;
    private TextView totalCddValue;
    private ImageButton clearButton;
    private ImageButton rateBadButton;
    private ImageButton rateGoodButton;
    private ImageButton backButton;

    // Cash management
    private List<Cash> selectedCashList = new ArrayList<>();
    private List<View> cashCardViews = new ArrayList<>();
    private long totalCdd = 0;

    private ActivityResultLauncher<Intent> cashSelectLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_rate_freer;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.rate_freer);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String freerJson = getIntent().getStringExtra(EXTRA_FREER_JSON);
        if (freerJson != null) {
            currentFreer = JsonUtils.fromJson(freerJson, Freer.class);
        }
        if (currentFreer == null) {
            ToastUtils.makeText(this, R.string.no_item_to_rate);
            finish();
            return;
        }

        cashSelectLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleCashSelectionResult(result.getData());
                }
            }
        );

        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        ToolbarUtils.setupToolbar(this, getString(R.string.rate_freer));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        displayFreerInfo();
    }

    @Override
    protected void initializeViews() {
        freerTypeLabel = findViewById(R.id.freerTypeLabel);
        freerIdValue = findViewById(R.id.freerIdValue);
        freerCidValue = findViewById(R.id.freerCidValue);
        freerReputationValue = findViewById(R.id.freerReputationValue);
        freerHotValue = findViewById(R.id.freerHotValue);
        causeInput = findViewById(R.id.causeInput);
        cashCardsContainer = findViewById(R.id.cashCardsContainer);
        plusCashButton = findViewById(R.id.plusCashButton);
        totalCddContainer = findViewById(R.id.totalCddContainer);
        totalCddValue = findViewById(R.id.totalCddValue);
        clearButton = findViewById(R.id.clearButton);
        rateBadButton = findViewById(R.id.rateBadButton);
        rateGoodButton = findViewById(R.id.rateGoodButton);
        backButton = findViewById(R.id.backButton);
    }

    private void displayFreerInfo() {
        if (currentFreer == null) return;

        freerTypeLabel.setText(R.string.rate_this_freer);
        freerIdValue.setText(currentFreer.getId() != null ? currentFreer.getId() : "-");

        String cid = currentFreer.getCid();
        if (cid != null && !cid.isEmpty()) {
            freerCidValue.setText(cid);
            freerCidValue.setVisibility(View.VISIBLE);
        } else {
            freerCidValue.setVisibility(View.GONE);
        }

        Long reputation = currentFreer.getReputation();
        freerReputationValue.setText(reputation != null ? String.valueOf(reputation) : "0");

        Long hot = currentFreer.getHot();
        freerHotValue.setText(hot != null ? String.valueOf(hot) : "0");
    }

    @Override
    protected void setupButtons() {
        plusCashButton.setOnClickListener(v -> {
            hideKeyboard();
            Intent intent = new Intent(this, CashActivity.class);
            intent.putExtra(CashActivity.EXTRA_SELECT_MODE, true);
            cashSelectLauncher.launch(intent);
        });

        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearAllSelections();
        });

        rateBadButton.setOnClickListener(v -> {
            hideKeyboard();
            submitRating(RATE_BAD);
        });

        rateGoodButton.setOnClickListener(v -> {
            hideKeyboard();
            submitRating(RATE_GOOD);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void handleCashSelectionResult(Intent data) {
        List<String> cashJsonList = data.getStringArrayListExtra(CashActivity.EXTRA_SELECTED_CASH);
        if (cashJsonList == null || cashJsonList.isEmpty()) {
            return;
        }

        for (String cashJson : cashJsonList) {
            try {
                Cash cash = Cash.fromJson(cashJson);
                if (cash != null && !isCashAlreadySelected(cash)) {
                    selectedCashList.add(cash);
                    addCashCardView(cash);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error parsing cash JSON: " + e.getMessage());
            }
        }

        updateTotalCdd();
    }

    private boolean isCashAlreadySelected(Cash cash) {
        for (Cash selected : selectedCashList) {
            if (selected.getId() != null && selected.getId().equals(cash.getId())) {
                return true;
            }
        }
        return false;
    }

    private void addCashCardView(Cash cash) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_cash_card, cashCardsContainer, false);

        TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
        TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
        ImageButton deleteButton = cardView.findViewById(R.id.deleteButton);
        View idBriefLastTimeRow = cardView.findViewById(R.id.idBriefLastTimeRow);
        View txIdIndexRow = cardView.findViewById(R.id.txIdIndexRow);
        TextView idBrief = cardView.findViewById(R.id.cash_id_brief);
        TextView lastTime = cardView.findViewById(R.id.cash_last_time);

        deleteButton.setVisibility(View.VISIBLE);

        txIdIndexRow.setVisibility(View.GONE);
        idBriefLastTimeRow.setVisibility(View.VISIBLE);

        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(cash.getValue())) + " F";
        amountValue.setText(amountText);

        // The server's cd stops at the last time it indexed this cash.
        CashManager.refreshCd(cash);
        Long cd = cash.getCd();
        if (cd != null) {
            cdValue.setText(String.format(Locale.US, "%d cd", cd));
        } else {
            cdValue.setText("0 cd");
        }

        String cashId = cash.getId();
        if (cashId != null && !cashId.isEmpty()) {
            idBrief.setText(cashId);
        }

        Long lastTimeValue = cash.getLastTime();
        if (lastTimeValue != null) {
            String formattedTime = com.fc.fc_ajdk.utils.DateUtils.longToTime(
                lastTimeValue * 1000, com.fc.fc_ajdk.utils.DateUtils.TO_MINUTE);
            lastTime.setText(formattedTime);
        }

        deleteButton.setOnClickListener(v -> {
            removeCashCard(cardView, cash);
        });

        int insertIndex = cashCardsContainer.indexOfChild(plusCashButton);
        if (insertIndex < 0) insertIndex = cashCardsContainer.getChildCount();
        cashCardsContainer.addView(cardView, insertIndex);
        cashCardViews.add(cardView);
    }

    private void removeCashCard(View cardView, Cash cash) {
        cashCardsContainer.removeView(cardView);
        cashCardViews.remove(cardView);
        selectedCashList.remove(cash);
        updateTotalCdd();
    }

    private void updateTotalCdd() {
        totalCdd = 0;
        CashManager.refreshCd(selectedCashList);
        for (Cash cash : selectedCashList) {
            if (cash.getCd() != null) {
                totalCdd += cash.getCd();
            }
        }

        if (totalCdd > 0) {
            totalCddContainer.setVisibility(View.VISIBLE);
            totalCddValue.setText(getString(R.string.total_cd_weight, totalCdd));
        } else {
            totalCddContainer.setVisibility(View.GONE);
        }
    }

    private void clearAllSelections() {
        for (View cardView : cashCardViews) {
            cashCardsContainer.removeView(cardView);
        }
        cashCardViews.clear();
        selectedCashList.clear();
        totalCdd = 0;
        totalCddContainer.setVisibility(View.GONE);

        causeInput.setText("");
    }

    private void submitRating(String rate) {
        if (selectedCashList.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_select_cash);
            return;
        }

        if (totalCdd < 1) {
            ToastUtils.makeText(this, R.string.total_cdd_must_be_at_least_one);
            return;
        }

        if (currentFreer == null || currentFreer.getId() == null || currentFreer.getId().isEmpty()) {
            ToastUtils.makeText(this, R.string.no_item_to_rate);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        String sender = liveKeyInfo.getId();

        String cause = causeInput.getText().toString().trim();

        // The ratee goes in data.fid. It used to be set on the
        // envelope's `did`, which is FEIP0's *document* id rather than a
        // party, so the parser never found a ratee and these carves
        // confirmed while changing nobody's score.
        Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.REPUTATION);
        ReputationOpData opData = ReputationOpData.makeRate(
            currentFreer.getId(), rate, cause.isEmpty() ? null : cause);
        feip.setData(opData);
        String feipJson = feip.toJson();

        CashManager cashManager = CashManager.getInstance();
        TxHandler txHandler = new TxHandler();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(
            com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);

        RawTxInfo rawTxInfo = new RawTxInfo();
        rawTxInfo.setSender(sender);
        rawTxInfo.setInputs(new ArrayList<>(selectedCashList));
        rawTxInfo.setOpReturn(feipJson);
        rawTxInfo.setVer(RawTxInfo.VERSION_2);

        TxSender txSender = new TxSender();
        txSender.sendTx(
            this,
            rawTxInfo,
            null,
            cashManager,
            txHandler,
            fapiClient,
            new TxSender.TxCallback() {
                @Override
                public void onSuccess(String txId) {
                    runOnUiThread(() -> {
                        finish();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(RateFreerActivity.this,
                            getString(R.string.failed_to_submit_rating, errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(RateFreerActivity.this, R.string.no_prikey_available);
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(RateFreerActivity.this, R.string.tx_signed_but_not_broadcast);
                    });
                }
            },
            true
        );
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    public void hideKeyboard() {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
        }
    }
}
