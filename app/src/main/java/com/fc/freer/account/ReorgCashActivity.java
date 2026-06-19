package com.fc.freer.account;

import static com.fc.fc_ajdk.core.fch.TxHandler.DEFAULT_FEE_RATE;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.multisig.SignMultisigTxActivity;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.NumberUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.SendTxActivity;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class ReorgCashActivity extends BaseCryptoActivity {
    private static final String TAG = "ReorgCashActivity";
    public static final String EXTRA_SELECTED_CASH = "selected_cash";
    
    // UI elements for spend cash display
    private TextView spendCashCountTextView;
    private TextView spendCashAmountTextView;
    private TextView spendCashCdTextView;
    
    // UI elements for issue cash input
    private TextInputEditText countEditText;
    private TextInputEditText amountEditText;
    private TextView totalAmountTextView;
    
    // Buttons
    private ImageButton clearButton;
    private ImageButton reorgButton;
    
    // Data
    private List<Cash> selectedCashList = new ArrayList<>();
    private long totalCashAmount = 0;
    private long totalCashCd = 0L;
    private long lastFee;
    private boolean isMultiSign = false;
    private Multisig multisig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        
        // Get selected cash from intent
        loadSelectedCash();
        
        // Initialize UI
        updateSpendCashDisplay();
        setupInputValidation();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_reorg_cash;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.reorg_cash);
    }

    @Override
    protected void initializeViews() {
        // Spend cash display views
        spendCashCountTextView = findViewById(R.id.spend_cash_count);
        spendCashAmountTextView = findViewById(R.id.spend_cash_amount);
        spendCashCdTextView = findViewById(R.id.spend_cash_cd);
        
        // Issue cash input views
        countEditText = findViewById(R.id.count_edit_text);
        amountEditText = findViewById(R.id.amount_edit_text);
        totalAmountTextView = findViewById(R.id.total_amount_text);
        
        // Buttons
        clearButton = findViewById(R.id.clear_button);
        reorgButton = findViewById(R.id.reorg_button);
        // Note: the back/cancel button (R.id.back_button) is wired by
        // BaseCryptoActivity.setupBackButton(); this layout has no separate cancel_button.
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // ReorgCashActivity doesn't use QR scanning
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == SendTxActivity.RESULT_SIGNED && resultCode == SendTxActivity.RESULT_SIGNED) {
            // Transaction was successfully signed and sent
            ToastUtils.makeText(this, R.string.reorg_completed_successfully);
            setResult(RESULT_OK); // Set result to indicate transaction was sent
            finish();
        } else if (requestCode == SendTxActivity.RESULT_SIGNED && resultCode == SignMultisigTxActivity.RESULT_SENT) {
            // Multisig transaction was successfully built and sent
            ToastUtils.makeText(this, R.string.reorg_completed_successfully);
            setResult(RESULT_OK); // Set result to indicate transaction was sent
            finish();
        }
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> clearInputs());

        reorgButton.setOnClickListener(v -> performReorg());
    }
    
    private void loadSelectedCash() {
        try {
            ArrayList<String> cashJsonList = getIntent().getStringArrayListExtra(EXTRA_SELECTED_CASH);
            if (cashJsonList != null && !cashJsonList.isEmpty()) {
                selectedCashList.clear();
                totalCashAmount = 0;
                totalCashCd = 0L;
                
                for (String cashJson : cashJsonList) {
                    Cash cash = Cash.fromJson(cashJson, Cash.class);
                    if (cash != null) {
                        selectedCashList.add(cash);
                        totalCashAmount += cash.getValue();
                        if (cash.getCd() != null) {
                            totalCashCd += cash.getCd();
                        }
                    }
                }
                
                TimberLogger.i(TAG, "Loaded %d cash items, total amount: %.8f FCH", 
                    selectedCashList.size(), FchUtils.satoshiToCoin(totalCashAmount));
            } else {
                TimberLogger.e(TAG, "No selected cash received");
                ToastUtils.makeText(this, R.string.no_selected_cash);
                finish();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading selected cash: %s", e.getMessage());
            ToastUtils.makeText(this, R.string.error_loading_cash);
            finish();
        }
    }
    
    private void updateSpendCashDisplay() {
        if (spendCashCountTextView != null) {
            spendCashCountTextView.setText(String.valueOf(selectedCashList.size()));
        }
        
        if (spendCashAmountTextView != null) {
            double amount = FchUtils.satoshiToCoin(totalCashAmount);
            spendCashAmountTextView.setText(String.format("%.8f", amount));
        }
        
        if (spendCashCdTextView != null) {
            String formattedCd = formatLargeNumber(totalCashCd);
            spendCashCdTextView.setText(formattedCd);
        }
    }
    
    private void setupInputValidation() {
        TextWatcher validationWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                validateInputs();
            }
        };
        
        if (countEditText != null) {
            countEditText.addTextChangedListener(validationWatcher);
        }
        
        if (amountEditText != null) {
            amountEditText.addTextChangedListener(validationWatcher);
        }
    }
    
    private void validateInputs() {

        String countStr = countEditText.getText().toString().trim();
        String amountStr = amountEditText.getText().toString().trim();
        
        boolean isValid = true;
        double calculatedTotal = 0.0;
        
        if (!countStr.isEmpty() && !amountStr.isEmpty()) {
            try {
                int count = Integer.parseInt(countStr);
                double amount = Double.parseDouble(amountStr);
                calculatedTotal = count * amount;
                
                if (FchUtils.coinToSatoshi(calculatedTotal) > totalCashAmount) {
                    isValid = false;
                }
            } catch (NumberFormatException e) {
                isValid = false;
            }
        } else if (!amountStr.isEmpty()) {
            try {
                double amount = Double.parseDouble(amountStr);
                long amountSatoshi = FchUtils.coinToSatoshi(amount);
                if (amountSatoshi <= 0) {
                    // Partial/zero input while typing (e.g. "0", "0.0") - not yet valid.
                    isValid = false;
                } else {
                    int count = (int) (totalCashAmount / amountSatoshi);
                    calculatedTotal = count * amount;

                    if (FchUtils.coinToSatoshi(calculatedTotal) > totalCashAmount) {
                        isValid = false;
                    }
                }
            } catch (NumberFormatException e) {
                isValid = false;
            }
        }
        
        // Update total amount display with color indication
        if (totalAmountTextView != null) {
            if (calculatedTotal > 0) {
                totalAmountTextView.setText(String.format("%.8f", calculatedTotal));
                int color = isValid ? 
                    getResources().getColor(R.color.text, getTheme()) :
                    getResources().getColor(android.R.color.holo_red_dark, getTheme());
                totalAmountTextView.setTextColor(color);
            } else {
                totalAmountTextView.setText(String.valueOf(FchUtils.satoshiToCoin(totalCashAmount)));
                totalAmountTextView.setTextColor(getResources().getColor(R.color.text, getTheme()));
            }
        }
        
        // Enable/disable reorg button based on validation
        if (reorgButton != null) {
            boolean hasInput = !countStr.isEmpty() || !amountStr.isEmpty();
            reorgButton.setEnabled(hasInput && isValid);
            reorgButton.setAlpha(hasInput && isValid ? 1.0f : 0.5f);
        }
    }
    
    private void clearInputs() {
        if (countEditText != null) {
            countEditText.setText("");
        }
        
        if (amountEditText != null) {
            amountEditText.setText("");
        }
        
        if (totalAmountTextView != null) {
            totalAmountTextView.setText("0.00000000");
            totalAmountTextView.setTextColor(getResources().getColor(R.color.text, getTheme()));
        }
    }
    
    private void performReorg() {
        // Validate selectedCashList size
        if (selectedCashList.size() > 200) {
            ToastUtils.makeText(this, R.string.too_many_cash_selected);
            return;
        }
        
        String countStr = countEditText.getText() != null ? countEditText.getText().toString().trim() : "";
        String amountStr = amountEditText.getText() != null ? amountEditText.getText().toString().trim() : "";
        
        try {
            // Get sender FID from first cash
            String senderFid = selectedCashList.get(0).getOwner();

            // Get KeyInfo for the sender
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            KeyInfo senderKeyInfo = fidManager.getKeyInfoByFid(senderFid);

            if (senderKeyInfo == null) {
                ToastUtils.makeText(this, R.string.sender_key_not_found);
                return;
            }

            if(senderKeyInfo.getId().startsWith("3")){
                isMultiSign=true;
                multisig =senderKeyInfo.getMultisign();
            }

            List<Cash> cashList = createSendToList(countStr, amountStr);
            if (cashList == null || cashList.isEmpty()) {
                ToastUtils.makeText(this, R.string.failed_to_create_transaction_info);
                return;
            }
            
            // Create RawTxInfo
            RawTxInfo rawTxInfo = new RawTxInfo();
            rawTxInfo.setSender(senderFid);
            rawTxInfo.setInputs(selectedCashList);
            rawTxInfo.setOutputs(cashList);
            rawTxInfo.setFeeRate(TxHandler.DEFAULT_FEE_RATE);

            // Set sender KeyInfo to RawTxInfo
            rawTxInfo.setSenderInfo(senderKeyInfo);
            
            // Launch appropriate activity based on multisig status
            Intent intent;
            if (isMultiSign) {
                // For multisig transactions, use SignMultisigTxActivity
                intent = new Intent(this, SignMultisigTxActivity.class);
                intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toJson());
                startActivityForResult(intent, SendTxActivity.RESULT_SIGNED);
            } else {
                // For regular transactions, use SendTxActivity
                intent = new Intent(this, SendTxActivity.class);
                intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toJson());
                startActivityForResult(intent, SendTxActivity.RESULT_SIGNED);
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error performing reorg: %s", e.getMessage());
            ToastUtils.makeText(this, R.string.reorg_failed);
        }
    }
    
    private List<Cash> createSendToList(String countStr, String amountStr) {
        try {
            String senderFid = selectedCashList.get(0).getOwner();
            
            // Normalize input parameters
            Integer count = NumberUtils.parsePositiveInteger(countStr);
            Double amount = NumberUtils.parsePositiveDouble(amountStr);
            
            // Determine reorg strategy based on input
            ReorgStrategy strategy = determineReorgStrategy(count, amount);
            
            // Execute the appropriate strategy
            return executeReorgStrategy(strategy, senderFid, count, amount);
            
        } catch (NumberFormatException e) {
            TimberLogger.e(TAG, "Invalid number format in input: %s", e.getMessage());
            return null;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating Cash list: %s", e.getMessage());
            return null;
        }
    }
    
    private enum ReorgStrategy {
        CONSOLIDATE_ALL,     // No count or amount specified
        SPLIT_BY_COUNT,      // Only count specified
        SPLIT_BY_AMOUNT,     // Only amount specified
        EXACT_DISTRIBUTION   // Both count and amount specified
    }
    
    
    private ReorgStrategy determineReorgStrategy(Integer count, Double amount) {
        if (count == null && amount == null) {
            return ReorgStrategy.CONSOLIDATE_ALL;
        } else if (count != null && amount == null) {
            return ReorgStrategy.SPLIT_BY_COUNT;
        } else if (count == null) {
            return ReorgStrategy.SPLIT_BY_AMOUNT;
        } else {
            return ReorgStrategy.EXACT_DISTRIBUTION;
        }
    }
    
    private List<Cash> executeReorgStrategy(ReorgStrategy strategy, String senderFid, Integer count, Double amount) {
        switch (strategy) {
            case CONSOLIDATE_ALL:
                return createConsolidatedOutput(senderFid);
            case SPLIT_BY_COUNT:
                return createOutputsByCount(senderFid, count);
            case SPLIT_BY_AMOUNT:
                return createOutputsByAmount(senderFid, amount);
            case EXACT_DISTRIBUTION:
                return createExactOutputs(senderFid, count, amount);
            default:
                TimberLogger.e(TAG, "Unknown reorg strategy: %s", strategy);
                return null;
        }
    }
    
    private List<Cash> createConsolidatedOutput(String senderFid) {
        List<Cash> cashList = new ArrayList<>();

        // Add single consolidated output with placeholder amount
        cashList.add(new Cash(senderFid, 0.00001));

        // Calculate fee using TxHandler (handles P2SH inputs properly)
        TxHandler.FeeResult feeResult = calculateFeeForOutputs(cashList);
        if (feeResult.fee() == null) {
            ToastUtils.makeText(this, R.string.fee_calculation_failed);
            return null;
        }
        long fee = feeResult.fee();
        long remainingAmount = totalCashAmount - fee;

        if (remainingAmount > 0) {
            double remainingCoin = FchUtils.satoshiToCoin(remainingAmount);
            cashList.clear();
            cashList.add(new Cash(senderFid, remainingCoin));
            TimberLogger.i(TAG, "Consolidated to single output: %.8f FCH (fee: %d sats)", remainingCoin, fee);
        }

        return cashList;
    }
    
    private List<Cash> createOutputsByCount(String senderFid, int count) {
        // Strategy 2: "a" bills total = (a-1) equal denomination bills + 1 change bill.
        // The change bill is added automatically by TxHandler (back to the sender),
        // so here we only emit the (a-1) equal bills.
        if (count <= 1) {
            // a-1 == 0 equal bills: everything collapses into the single change bill.
            return createConsolidatedOutput(senderFid);
        }

        int equalCount = count - 1;

        // Estimate the fee. Because the placeholder bills sum to far less than the
        // input, calcFee already accounts for the change output TxHandler will add,
        // i.e. the fee covers all `count` outputs. Output size is value-independent,
        // so MIN_AMOUNT placeholders give the same fee as the final amounts.
        List<Cash> placeholders = new ArrayList<>();
        for (int i = 0; i < equalCount; i++) {
            placeholders.add(new Cash(senderFid, Cash.MIN_AMOUNT));
        }
        TxHandler.FeeResult feeResult = calculateFeeForOutputs(placeholders);
        if (feeResult == null || feeResult.fee() == null) {
            ToastUtils.makeText(this, R.string.fee_calculation_failed);
            return null;
        }
        long fee = feeResult.fee();

        long availableAmount = totalCashAmount - fee;
        long denomSats = availableAmount / count;
        if (denomSats <= 0) {
            showInsufficientFundsError();
            return null;
        }

        List<Cash> cashList = new ArrayList<>();
        for (int i = 0; i < equalCount; i++) {
            cashList.add(new Cash(senderFid, FchUtils.satoshiToCoin(denomSats)));
        }

        // Change bill (created by TxHandler) = availableAmount - equalCount*denomSats
        long changeSats = availableAmount - denomSats * equalCount;
        TimberLogger.i(TAG, "By count: %d equal outputs of %.8f FCH + change %.8f FCH (fee: %d sats)",
            equalCount, FchUtils.satoshiToCoin(denomSats), FchUtils.satoshiToCoin(changeSats), fee);

        return cashList;
    }

    private List<Cash> createOutputsByAmount(String senderFid, double amount) {
        // Strategy 3: several bills of denomination x + 1 change bill.
        // Only the denomination bills are emitted; TxHandler adds the change output.
        long amountSatoshi = FchUtils.coinToSatoshi(amount);
        if (amountSatoshi <= 0) {
            showInsufficientFundsError();
            return null;
        }

        int count = (int) (totalCashAmount / amountSatoshi);

        // Shrink the count until the bills plus the fee (which already includes the
        // change output) fit within the selected cash.
        while (count > 0) {
            List<Cash> cashList = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                cashList.add(new Cash(senderFid, amount));
            }

            TxHandler.FeeResult feeResult = calculateFeeForOutputs(cashList);
            if (feeResult == null || feeResult.fee() == null) {
                ToastUtils.makeText(this, R.string.fee_calculation_failed);
                return null;
            }
            long fee = feeResult.fee();
            long changeSats = totalCashAmount - amountSatoshi * (long) count - fee;

            if (changeSats >= 0) {
                TimberLogger.i(TAG, "By amount: %d outputs of %.8f FCH + change %.8f FCH (fee: %d sats)",
                    count, amount, FchUtils.satoshiToCoin(changeSats), fee);
                return cashList;
            }
            count--;
        }

        showInsufficientFundsError();
        return null;
    }

    private List<Cash> createExactOutputs(String senderFid, int count, double amount) {
        // Strategy 1: exactly `count` bills of denomination `amount` + 1 change bill.
        // The change bill is added automatically by TxHandler; if it would be
        // negative the inputs cannot cover the bills plus fee, so report an error.
        List<Cash> cashList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cashList.add(new Cash(senderFid, amount));
        }

        TxHandler.FeeResult feeResult = calculateFeeForOutputs(cashList);
        if (feeResult == null || feeResult.fee() == null) {
            ToastUtils.makeText(this, R.string.fee_calculation_failed);
            return null;
        }
        long fee = feeResult.fee();

        long outputsSats = FchUtils.coinToSatoshi(amount) * (long) count;
        long changeSats = totalCashAmount - outputsSats - fee;

        if (changeSats < 0) {
            showInsufficientFundsError();
            return null;
        }

        TimberLogger.i(TAG, "Exact: %d outputs of %.8f FCH + change %.8f FCH (fee: %d sats)",
            count, amount, FchUtils.satoshiToCoin(changeSats), fee);

        return cashList;
    }
    
    private void showInsufficientFundsError() {
        ToastUtils.makeText(this, R.string.not_enough_cash_for_payment_and_fee);
    }

    /**
     * Calculate fee for a list of outputs using TxHandler
     * This method properly handles P2SH inputs (CLTV) and generates correct opReturn data
     * @param outputs List of Cash outputs
     * @return FeeResult containing fee amount and any generated opReturn bytes
     */
    private TxHandler.FeeResult calculateFeeForOutputs(List<Cash> outputs) {
        // Sender/changeTo must be set so calcFee can size the (automatic) change
        // output; otherwise getChangeFee(null) throws an NPE.
        String senderFid = selectedCashList.get(0).getOwner();

        RawTxInfo tempRawTxInfo = new RawTxInfo();
        tempRawTxInfo.setInputs(selectedCashList);
        tempRawTxInfo.setOutputs(outputs);
        tempRawTxInfo.setSender(senderFid);
        tempRawTxInfo.setChangeTo(senderFid);
        tempRawTxInfo.setFeeRate(DEFAULT_FEE_RATE);
        tempRawTxInfo.setOpReturn(null);
        if (isMultiSign) {
            tempRawTxInfo.setSenderMultisig(multisig);
        }

        return TxHandler.calcFee(tempRawTxInfo);
    }

    /**
     * Count regular and P2SH inputs from the selected cash list
     * @return int array: [regularInputCount, p2shInputCount]
     */
    private int[] countInputTypes() {
        int regularInputCount = 0;
        int p2shInputCount = 0;
        for (Cash cash : selectedCashList) {
            if (cash.getRedeemScript() != null && !cash.getRedeemScript().isEmpty()) {
                p2shInputCount++;
            } else {
                regularInputCount++;
            }
        }
        return new int[]{regularInputCount, p2shInputCount};
    }

    /**
     * Helper method to format large numbers (borrowed from CashActivity)
     */
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
}