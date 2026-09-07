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
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.AppOpData;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.CodeOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.Remark;
import com.fc.fc_ajdk.data.feipData.RemarkOpData;
import com.fc.fc_ajdk.data.feipData.ProtocolOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.ServiceOpData;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
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

/**
 * Activity for rating Apps, Codes, Protocols, or Services.
 * The rating weight is determined by the CD (Coin Days) of the consumed cash.
 */
public class RateActivity extends BaseCryptoActivity {

    private static final String TAG = "RateActivity";

    public static final String EXTRA_APP_JSON = "extra_app_json";
    public static final String EXTRA_CODE_JSON = "extra_code_json";
    public static final String EXTRA_PROTOCOL_JSON = "extra_protocol_json";
    public static final String EXTRA_SERVICE_JSON = "extra_service_json";
    public static final String EXTRA_TEAM_JSON = "extra_team_json";
    public static final String EXTRA_REMARK_JSON = "extra_remark_json";

    // Item type enum
    public enum ItemType {
        APP, CODE, PROTOCOL, SERVICE, TEAM, REMARK
    }

    private ItemType currentItemType;
    private App currentApp;
    private Code currentCode;
    private Protocol currentProtocol;
    private Service currentService;
    private Team currentTeam;
    private Remark currentRemark;

    // Views
    private TextView itemTypeLabel;
    private TextView itemNameValue;
    private TextView itemDescValue;
    private TextView itemIdValue;
    private TextView itemTCddValue;
    private TextView itemTRateValue;
    private LinearLayout cashCardsContainer;
    private ImageButton plusCashButton;
    private LinearLayout totalCdContainer;
    private TextView totalCdValue;
    private LinearLayout ratingContainer;
    private EditText causeInput;
    private RadioButton rate0, rate1, rate2, rate3, rate4, rate5;
    private RadioButton[] ratingButtons;
    private ImageButton clearButton;
    private ImageButton rateButton;
    private ImageButton backButton;

    // Cash management
    private List<Cash> selectedCashList = new ArrayList<>();
    private List<View> cashCardViews = new ArrayList<>();
    private long totalCd = 0;
    
    // Selected rating (default is 5 - Excellent)
    private int selectedRating = 5;

    // Activity result launcher for cash selection
    private ActivityResultLauncher<Intent> cashSelectLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_rate_app;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.rate_item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Parse item data from intent
        if (!parseIntentData()) {
            ToastUtils.makeText(this, R.string.no_item_to_rate);
            finish();
            return;
        }

        // Initialize cash selection launcher
        cashSelectLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleCashSelectionResult(result.getData());
                }
            }
        );

        // Set up root layout touch listener to hide keyboard
        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        // Setup toolbar with appropriate title
        ToolbarUtils.setupToolbar(this, getActivityTitleForType());

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        displayItemInfo();
    }

    /**
     * Parse item data from intent and determine item type
     */
    private boolean parseIntentData() {
        String appJson = getIntent().getStringExtra(EXTRA_APP_JSON);
        if (appJson != null) {
            currentApp = JsonUtils.fromJson(appJson, App.class);
            if (currentApp != null) {
                currentItemType = ItemType.APP;
                return true;
            }
        }

        String codeJson = getIntent().getStringExtra(EXTRA_CODE_JSON);
        if (codeJson != null) {
            currentCode = JsonUtils.fromJson(codeJson, Code.class);
            if (currentCode != null) {
                currentItemType = ItemType.CODE;
                return true;
            }
        }

        String protocolJson = getIntent().getStringExtra(EXTRA_PROTOCOL_JSON);
        if (protocolJson != null) {
            currentProtocol = JsonUtils.fromJson(protocolJson, Protocol.class);
            if (currentProtocol != null) {
                currentItemType = ItemType.PROTOCOL;
                return true;
            }
        }

        String serviceJson = getIntent().getStringExtra(EXTRA_SERVICE_JSON);
        if (serviceJson != null) {
            currentService = JsonUtils.fromJson(serviceJson, Service.class);
            if (currentService != null) {
                currentItemType = ItemType.SERVICE;
                return true;
            }
        }

        String teamJson = getIntent().getStringExtra(EXTRA_TEAM_JSON);
        if (teamJson != null) {
            currentTeam = JsonUtils.fromJson(teamJson, Team.class);
            if (currentTeam != null) {
                currentItemType = ItemType.TEAM;
                return true;
            }
        }

        String remarkJson = getIntent().getStringExtra(EXTRA_REMARK_JSON);
        if (remarkJson != null) {
            currentRemark = JsonUtils.fromJson(remarkJson, Remark.class);
            if (currentRemark != null) {
                currentItemType = ItemType.REMARK;
                return true;
            }
        }

        return false;
    }

    /**
     * Get activity title based on item type
     */
    private String getActivityTitleForType() {
        if (currentItemType == null) {
            return getString(R.string.rate_item);
        }
        switch (currentItemType) {
            case APP:
                return getString(R.string.rate_app);
            case CODE:
                return getString(R.string.rate_code);
            case PROTOCOL:
                return getString(R.string.rate_protocol);
            case SERVICE:
                return getString(R.string.rate_service);
            case TEAM:
                return getString(R.string.rate_team);
            case REMARK:
                return getString(R.string.rate_remark);
            default:
                return getString(R.string.rate_item);
        }
    }

    @Override
    protected void initializeViews() {
        itemTypeLabel = findViewById(R.id.itemTypeLabel);
        itemNameValue = findViewById(R.id.itemNameValue);
        itemDescValue = findViewById(R.id.itemDescValue);
        itemIdValue = findViewById(R.id.itemIdValue);
        itemTCddValue = findViewById(R.id.itemTCddValue);
        itemTRateValue = findViewById(R.id.itemTRateValue);
        cashCardsContainer = findViewById(R.id.cashCardsContainer);
        plusCashButton = findViewById(R.id.plusCashButton);
        totalCdContainer = findViewById(R.id.totalCdContainer);
        totalCdValue = findViewById(R.id.totalCdValue);
        ratingContainer = findViewById(R.id.ratingContainer);
        causeInput = findViewById(R.id.causeInput);
        clearButton = findViewById(R.id.clearButton);
        rateButton = findViewById(R.id.rateButton);
        backButton = findViewById(R.id.backButton);
        
        // Initialize radio buttons
        rate0 = findViewById(R.id.rate0);
        rate1 = findViewById(R.id.rate1);
        rate2 = findViewById(R.id.rate2);
        rate3 = findViewById(R.id.rate3);
        rate4 = findViewById(R.id.rate4);
        rate5 = findViewById(R.id.rate5);
        
        ratingButtons = new RadioButton[]{rate0, rate1, rate2, rate3, rate4, rate5};
        
        // Set up click listeners for manual mutual exclusivity
        setupRatingButtons();
        
        // Set rate5 (Excellent) as default selected rating
        selectRating(5);
    }
    
    /**
     * Set up rating buttons with manual mutual exclusivity
     */
    private void setupRatingButtons() {
        for (int i = 0; i < ratingButtons.length; i++) {
            final int rating = i;
            ratingButtons[i].setOnClickListener(v -> {
                hideKeyboard();
                selectRating(rating);
            });
        }
    }
    
    /**
     * Select a rating and uncheck all others
     */
    private void selectRating(int rating) {
        selectedRating = rating;
        for (int i = 0; i < ratingButtons.length; i++) {
            ratingButtons[i].setChecked(i == rating);
        }
    }

    /**
     * Display item information based on item type
     */
    private void displayItemInfo() {
        if (currentItemType == null) return;

        switch (currentItemType) {
            case APP:
                displayAppInfo();
                break;
            case CODE:
                displayCodeInfo();
                break;
            case PROTOCOL:
                displayProtocolInfo();
                break;
            case SERVICE:
                displayServiceInfo();
                break;
            case TEAM:
                displayTeamInfo();
                break;
            case REMARK:
                displayRemarkInfo();
                break;
        }
    }

    private void displayAppInfo() {
        if (currentApp == null) return;
        
        itemTypeLabel.setText(R.string.rate_this_app);
        itemNameValue.setText(currentApp.getStdName() != null ? currentApp.getStdName() : "-");
        itemDescValue.setText(currentApp.getDesc() != null ? currentApp.getDesc() : "");
        itemIdValue.setText(currentApp.getId() != null ? currentApp.getId() : "");
        
        Long tCdd = currentApp.gettCdd();
        itemTCddValue.setText(tCdd != null ? String.valueOf(tCdd) : "0");
        
        Float tRate = currentApp.gettRate();
        itemTRateValue.setText(tRate != null ? String.format(Locale.US, "%.2f", tRate) : "-");
    }

    private void displayCodeInfo() {
        if (currentCode == null) return;
        
        itemTypeLabel.setText(R.string.rate_this_code);
        itemNameValue.setText(currentCode.getName() != null ? currentCode.getName() : "-");
        itemDescValue.setText(currentCode.getDesc() != null ? currentCode.getDesc() : "");
        itemIdValue.setText(currentCode.getId() != null ? currentCode.getId() : "");
        
        Long tCdd = currentCode.gettCdd();
        itemTCddValue.setText(tCdd != null ? String.valueOf(tCdd) : "0");
        
        Float tRate = currentCode.gettRate();
        itemTRateValue.setText(tRate != null ? String.format(Locale.US, "%.2f", tRate) : "-");
    }

    private void displayProtocolInfo() {
        if (currentProtocol == null) return;
        
        itemTypeLabel.setText(R.string.rate_this_protocol);
        itemNameValue.setText(currentProtocol.getName() != null ? currentProtocol.getName() : "-");
        itemDescValue.setText(currentProtocol.getDesc() != null ? currentProtocol.getDesc() : "");
        itemIdValue.setText(currentProtocol.getId() != null ? currentProtocol.getId() : "");
        
        Long tCdd = currentProtocol.gettCdd();
        itemTCddValue.setText(tCdd != null ? String.valueOf(tCdd) : "0");
        
        Float tRate = currentProtocol.gettRate();
        itemTRateValue.setText(tRate != null ? String.format(Locale.US, "%.2f", tRate) : "-");
    }

    private void displayServiceInfo() {
        if (currentService == null) return;
        
        itemTypeLabel.setText(R.string.rate_this_service);
        itemNameValue.setText(currentService.getStdName() != null ? currentService.getStdName() : "-");
        itemDescValue.setText(currentService.getDesc() != null ? currentService.getDesc() : "");
        itemIdValue.setText(currentService.getId() != null ? currentService.getId() : "");
        
        Long tCdd = currentService.gettCdd();
        itemTCddValue.setText(tCdd != null ? String.valueOf(tCdd) : "0");
        
        Float tRate = currentService.gettRate();
        itemTRateValue.setText(tRate != null ? String.format(Locale.US, "%.2f", tRate) : "-");
    }

    private void displayTeamInfo() {
        if (currentTeam == null) return;

        itemTypeLabel.setText(R.string.rate_this_team);
        itemNameValue.setText(currentTeam.getStdName() != null ? currentTeam.getStdName() : "-");
        itemDescValue.setText(currentTeam.getDesc() != null ? currentTeam.getDesc() : "");
        itemIdValue.setText(currentTeam.getId() != null ? currentTeam.getId() : "");

        Long tCdd = currentTeam.gettCdd();
        itemTCddValue.setText(tCdd != null ? String.valueOf(tCdd) : "0");

        Float tRate = currentTeam.gettRate();
        itemTRateValue.setText(tRate != null ? String.format(Locale.US, "%.2f", tRate) : "-");
    }

    /**
     * A remark has a title rather than a stdName, and its summary is
     * the closest thing it has to a description.
     */
    private void displayRemarkInfo() {
        if (currentRemark == null) return;

        itemTypeLabel.setText(R.string.rate_this_remark);
        itemNameValue.setText(currentRemark.getTitle() != null ? currentRemark.getTitle() : "-");
        itemDescValue.setText(currentRemark.getSummary() != null ? currentRemark.getSummary() : "");
        itemIdValue.setText(currentRemark.getId() != null ? currentRemark.getId() : "");

        Long tCdd = currentRemark.gettCdd();
        itemTCddValue.setText(tCdd != null ? String.valueOf(tCdd) : "0");

        Float tRate = currentRemark.gettRate();
        itemTRateValue.setText(tRate != null ? String.format(Locale.US, "%.2f", tRate) : "-");
    }

    @Override
    protected void setupButtons() {
        // Plus cash button - open CashActivity for selection
        plusCashButton.setOnClickListener(v -> {
            hideKeyboard();
            Intent intent = new Intent(this, CashActivity.class);
            intent.putExtra(CashActivity.EXTRA_SELECT_MODE, true);
            cashSelectLauncher.launch(intent);
        });

        // Clear button - reset all selections
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearAllSelections();
        });

        // Rate button - submit rating
        rateButton.setOnClickListener(v -> {
            hideKeyboard();
            submitRating();
        });

        // Back button
        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    /**
     * Handle result from CashActivity
     */
    private void handleCashSelectionResult(Intent data) {
        List<String> cashJsonList = data.getStringArrayListExtra(CashActivity.EXTRA_SELECTED_CASH);
        if (cashJsonList == null || cashJsonList.isEmpty()) {
            return;
        }

        // Parse cash objects from JSON
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

        updateTotalCd();
    }

    /**
     * Check if cash is already in the selected list
     */
    private boolean isCashAlreadySelected(Cash cash) {
        for (Cash selected : selectedCashList) {
            if (selected.getId() != null && selected.getId().equals(cash.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add a cash card view to the container
     */
    private void addCashCardView(Cash cash) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_cash_card, cashCardsContainer, false);

        TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
        TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
        ImageButton deleteButton = cardView.findViewById(R.id.deleteButton);
        View idBriefLastTimeRow = cardView.findViewById(R.id.idBriefLastTimeRow);
        View txIdIndexRow = cardView.findViewById(R.id.txIdIndexRow);
        TextView idBrief = cardView.findViewById(R.id.cash_id_brief);
        TextView lastTime = cardView.findViewById(R.id.cash_last_time);

        // Show delete button
        deleteButton.setVisibility(View.VISIBLE);

        // Show ID brief row, hide txId row
        txIdIndexRow.setVisibility(View.GONE);
        idBriefLastTimeRow.setVisibility(View.VISIBLE);

        // Set amount
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(cash.getValue())) + " F";
        amountValue.setText(amountText);

        // Set CD value
        Long cd = cash.getCd();
        if (cd != null) {
            cdValue.setText(String.format(Locale.US, "%d cd", cd));
        } else {
            cdValue.setText("0 cd");
        }

        // Set ID brief
        String cashId = cash.getId();
        if (cashId != null && !cashId.isEmpty()) {
            idBrief.setText(cashId);
        }

        // Set last time
        Long lastTimeValue = cash.getLastTime();
        if (lastTimeValue != null) {
            String formattedTime = com.fc.fc_ajdk.utils.DateUtils.longToTime(
                lastTimeValue * 1000, com.fc.fc_ajdk.utils.DateUtils.TO_MINUTE);
            lastTime.setText(formattedTime);
        }

        // Delete button handler
        deleteButton.setOnClickListener(v -> {
            removeCashCard(cardView, cash);
        });

        // Insert before the plus button
        int insertIndex = cashCardsContainer.indexOfChild(plusCashButton);
        if (insertIndex < 0) insertIndex = cashCardsContainer.getChildCount();
        cashCardsContainer.addView(cardView, insertIndex);
        cashCardViews.add(cardView);
    }

    /**
     * Remove a cash card from the container
     */
    private void removeCashCard(View cardView, Cash cash) {
        cashCardsContainer.removeView(cardView);
        cashCardViews.remove(cardView);
        selectedCashList.remove(cash);
        updateTotalCd();
    }

    /**
     * Update total CD display
     */
    private void updateTotalCd() {
        totalCd = 0;
        for (Cash cash : selectedCashList) {
            if (cash.getCd() != null) {
                totalCd += cash.getCd();
            }
        }

        if (totalCd > 0) {
            totalCdContainer.setVisibility(View.VISIBLE);
            totalCdValue.setText(getString(R.string.total_cd_weight, totalCd));
        } else {
            totalCdContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Clear all selections
     */
    private void clearAllSelections() {
        // Clear cash cards
        for (View cardView : cashCardViews) {
            cashCardsContainer.removeView(cardView);
        }
        cashCardViews.clear();
        selectedCashList.clear();
        totalCd = 0;
        totalCdContainer.setVisibility(View.GONE);

        // Clear rating selection
        selectedRating = -1;
        for (RadioButton rb : ratingButtons) {
            rb.setChecked(false);
        }

        // Clear the reason
        if (causeInput != null) causeInput.setText("");
    }

    /**
     * Get selected rating value (0-5), or null if none selected
     */
    private Integer getSelectedRating() {
        if (selectedRating >= 0 && selectedRating <= 5) {
            return selectedRating;
        }
        return null;
    }

    /**
     * Submit rating to blockchain
     */
    private void submitRating() {
        // Validate cash selection
        if (selectedCashList.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_select_cash);
            return;
        }
        
        // Validate total CD is greater than 0
        if (totalCd <= 0) {
            ToastUtils.makeText(this, R.string.total_cd_must_be_greater_than_zero);
            return;
        }

        // Validate rating selection
        Integer rating = getSelectedRating();
        if (rating == null) {
            ToastUtils.makeText(this, R.string.please_select_rating);
            return;
        }

        // Get item ID
        String itemId = getItemId();
        if (itemId == null || itemId.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_item_to_rate);
            return;
        }

        // Get sender info
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        String sender = liveKeyInfo.getId();

        // Owner cannot rate their own item
        String owner = getItemOwner();
        if (owner != null && owner.equals(sender)) {
            ToastUtils.makeText(this, R.string.cannot_rate_own_item);
            return;
        }

        // Optional free text saying why. Blank means omit the field entirely
        // rather than carve an empty string, the same rule as RateFreerActivity.
        String cause = causeInput == null ? null : causeInput.getText().toString().trim();
        if (cause != null && cause.isEmpty()) cause = null;

        // Create FEIP message based on item type
        String feipJson = createFeipRateJson(itemId, rating, cause);
        if (feipJson == null) {
            ToastUtils.makeText(this, R.string.no_item_to_rate);
            return;
        }

        // Get required instances
        CashManager cashManager = CashManager.getInstance();
        TxHandler txHandler = new TxHandler();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(
            com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);

        // Create RawTxInfo with selected cash as inputs
        RawTxInfo rawTxInfo = new RawTxInfo();
        rawTxInfo.setSender(sender);
        rawTxInfo.setInputs(new ArrayList<>(selectedCashList));
        rawTxInfo.setOpReturn(feipJson);
        rawTxInfo.setVer(RawTxInfo.VERSION_2);

        // Use TxSender to send the transaction
        TxSender txSender = new TxSender();
        txSender.sendTx(
            this,
            rawTxInfo,
            null, // Private key will be handled in SendTxActivity
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
                        ToastUtils.makeText(RateActivity.this, 
                            getString(R.string.failed_to_submit_rating, errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(RateActivity.this, R.string.no_prikey_available);
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(RateActivity.this, R.string.tx_signed_but_not_broadcast);
                    });
                }
            },
            true // withConfirm = true to show SendTxActivity
        );
    }

    /**
     * Get item ID based on item type
     */
    private String getItemId() {
        if (currentItemType == null) return null;
        switch (currentItemType) {
            case APP:
                return currentApp != null ? currentApp.getId() : null;
            case CODE:
                return currentCode != null ? currentCode.getId() : null;
            case PROTOCOL:
                return currentProtocol != null ? currentProtocol.getId() : null;
            case SERVICE:
                return currentService != null ? currentService.getId() : null;
            case TEAM:
                return currentTeam != null ? currentTeam.getId() : null;
            case REMARK:
                return currentRemark != null ? currentRemark.getId() : null;
            default:
                return null;
        }
    }

    private String getItemOwner() {
        if (currentItemType == null) return null;
        switch (currentItemType) {
            case APP:
                return currentApp != null ? currentApp.getOwner() : null;
            case CODE:
                return currentCode != null ? currentCode.getOwner() : null;
            case PROTOCOL:
                return currentProtocol != null ? currentProtocol.getOwner() : null;
            case SERVICE:
                return currentService != null ? currentService.getOwner() : null;
            case TEAM:
                return currentTeam != null ? currentTeam.getOwner() : null;
            case REMARK:
                // FEIP22 bars the `publisher`, which is the same rule
                // under a different word: the Publish protocols call
                // the record's author a publisher, not an owner.
                return currentRemark != null ? currentRemark.getPublisher() : null;
            default:
                return null;
        }
    }

    /**
     * Create FEIP JSON for rating based on item type
     */
    private String createFeipRateJson(String itemId, Integer rating, String cause) {
        if (currentItemType == null) return null;

        Feip feip;
        Object opData;

        switch (currentItemType) {
            case APP:
                feip = Feip.fromProtocolName(Feip.FeipProtocol.APP);
                opData = AppOpData.makeRate(itemId, rating, cause);
                break;
            case CODE:
                feip = Feip.fromProtocolName(Feip.FeipProtocol.CODE);
                opData = CodeOpData.makeRate(itemId, rating, cause);
                break;
            case PROTOCOL:
                feip = Feip.fromProtocolName(Feip.FeipProtocol.PROTOCOL);
                opData = ProtocolOpData.makeRate(itemId, rating, cause);
                break;
            case SERVICE:
                feip = Feip.fromProtocolName(Feip.FeipProtocol.SERVICE);
                opData = ServiceOpData.makeRate(itemId, rating, cause);
                break;
            case TEAM:
                feip = Feip.fromProtocolName(Feip.FeipProtocol.TEAM);
                opData = TeamOpData.makeRate(itemId, rating, cause);
                break;
            case REMARK:
                feip = Feip.fromProtocolName(Feip.FeipProtocol.REMARK);
                opData = RemarkOpData.makeRate(itemId, rating, cause);
                break;
            default:
                return null;
        }

        feip.setData(opData);
        return feip.toJson();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    /**
     * Hide keyboard helper
     */
    public void hideKeyboard() {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
        }
    }
}
