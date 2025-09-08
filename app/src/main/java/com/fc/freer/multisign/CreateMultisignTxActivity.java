package com.fc.freer.multisign;

import static com.fc.fc_ajdk.constants.Constants.COIN_TO_SATOSHI;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.WindowManager;
import android.content.ClipboardManager;
import android.content.ClipData;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisign;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.home.BaseCryptoActivity;
import com.fc.freer.tx.TxHandler;
import com.fc.freer.tx.dialog.AddTxInputDialog;
import com.fc.freer.tx.dialog.AddTxOutputDialog;
import com.fc.freer.tx.dialog.AddOutputFromFidListDialog;
import com.fc.freer.home.CashActivity;
import com.fc.freer.ui.PopupMenuHelper;

import com.fc.freer.tx.view.TxInputCard;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.tx.ImportTxInfoActivity;
import com.fc.freer.tx.SendTxActivity;
import com.google.android.material.textfield.TextInputEditText;
import android.view.LayoutInflater;

import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.DecimalFormat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class CreateMultisignTxActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateMultisignTxActivity";
    public static final String EXTRA_TX_INFO_JSON = "extra_tx_info_json";

    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_OUTPUT_FID_REQUEST_CODE = 1005;
    private static final int QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE = 1006;
    private static final int REQUEST_CODE_CHOOSE_CASH = 1004;
    private RawTxInfo rawTxInfo;

    private LinearLayout txContainer;
    private LinearLayout inputCardsContainer;
    private LinearLayout outputCardsContainer;
    private LinearLayout outputInputContainer;
    private LinearLayout opreturnContainer;
    private LinearLayout buttonContainer;

    private List<TxInputCard> inputCards = new ArrayList<>();
    private List<TxOutputCard> outputCards = new ArrayList<>();

    private TextInputEditText opreturnInput;
    private TextInputEditText outputFidInput;
    private TextInputEditText outputAmountInput;
    private TextView outputMaxText;
    private Button addOutputButton;
    private Button clearButton;
    private Button importTxButton;
    private Button createButton;
    private Button copyButton;
    private ImageButton plusInputButton;
    private TextView plusOutputButton;
    private TextView outputHint;
    private TextView inputHint;
    private TextView totalAndFeeText;
    private TextView totalInputText;

    private long feeRateLong;
    private long fee;
    private long totalInput;
    private long totalOutput;
    private long rest;
    private long totalCd;

    // Track the current dialog
    private Dialog currentDialog;
    private final TxHandler txHandler = new TxHandler();


    private ActivityResultLauncher<Intent> importTxLauncher;
    private ActivityResultLauncher<Intent> signMultisignTxLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimberLogger.i(TAG, "onCreate started");

        // Set window flags to prevent transition issues
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        // Initialize rawTxInfo from intent or create new one
        String txJson = getIntent().getStringExtra(EXTRA_TX_INFO_JSON);
        if (txJson != null) {
            rawTxInfo = RawTxInfo.fromJson(txJson, RawTxInfo.class);
        }
        if (rawTxInfo == null) {
            rawTxInfo = new RawTxInfo();
            // If we have a Multisign in the intent, set it
            Multisign multisign = (Multisign) getIntent().getSerializableExtra("multisign");
            if (multisign != null) {
                rawTxInfo.setMultisign(multisign);
            }
        }
        
        // If we have rawTxInfo from intent, handle it immediately
        if (rawTxInfo != null && txJson != null) {
            try {
                handleImportedTxInfo(rawTxInfo);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error handling imported RawTxInfo: " + e.getMessage());
            }
        }
        TimberLogger.i(TAG, "RawTxInfo initialized");
        feeRateLong = (long) (TxHandler.DEFAULT_FEE_RATE / 1000 * COIN_TO_SATOSHI);


        // Initialize importTxLauncher
        importTxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                    String offLineTxInfoJson = result.getData().getStringExtra(SendTxActivity.EXTRA_TX_INFO_JSON);
                    if (offLineTxInfoJson != null) {
                        try {
                            RawTxInfo importedRawTxInfo = RawTxInfo.fromJson(offLineTxInfoJson, RawTxInfo.class);
                            if (importedRawTxInfo != null) {
                                handleImportedTxInfo(importedRawTxInfo);
                            }
                        } catch (Exception e) {
                            TimberLogger.e(TAG, "Error parsing RawTxInfo JSON: " + e.getMessage());
                        }
                    }
                }
            }
        );

        // Initialize signMultisignTxLauncher
        signMultisignTxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == SignMultisignTxActivity.RESULT_BUILT) {
                    finish();  // Finish this activity when transaction is built
                }
            }
        );

        TimberLogger.i(TAG, "onCreate completed");
    }

    private void initViews() {
        TimberLogger.i(TAG, "initViews started");
        txContainer = findViewById(R.id.txContainer);
        inputCardsContainer = findViewById(R.id.inputCardsContainer);
        outputCardsContainer = findViewById(R.id.outputCardsContainer);
        outputInputContainer = findViewById(R.id.outputInputContainer);
        opreturnContainer = findViewById(R.id.opreturnContainer);
        buttonContainer = findViewById(R.id.buttonContainer);
        outputHint = findViewById(R.id.outputHint);
        inputHint = findViewById(R.id.inputHint);
        totalAndFeeText = findViewById(R.id.totalAndFeeText);
        totalInputText = findViewById(R.id.totalInputText);

        opreturnInput = opreturnContainer.findViewById(R.id.opreturnInput).findViewById(R.id.textInput);
        opreturnInput.setHint(R.string.input_the_text_carved_on_chain);

        // Initialize output input fields
        outputFidInput = outputInputContainer.findViewById(R.id.outputFidInput).findViewById(R.id.textInput);
        outputFidInput.setHint(R.string.fid);
        outputAmountInput = outputInputContainer.findViewById(R.id.outputAmountInput).findViewById(R.id.textInput);
        outputAmountInput.setHint(R.string.amount);
        outputMaxText = findViewById(R.id.outputMaxText);
        addOutputButton = findViewById(R.id.addOutputButton);

        clearButton = findViewById(R.id.clearButton);
        importTxButton = findViewById(R.id.importTxButton);
        createButton = findViewById(R.id.createButton);
        copyButton = findViewById(R.id.copyButton);
        plusInputButton = findViewById(R.id.plusInputButton);
        plusOutputButton = findViewById(R.id.addFromList);

        setupTextIcons(R.id.opreturnInput, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);
        setupTextIcons(R.id.outputFidInput, R.id.scanIcon, QR_SCAN_OUTPUT_FID_REQUEST_CODE);
        setupTextIcons(R.id.outputAmountInput, R.id.scanIcon, QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE);
    }

    private void setupListeners() {

        // Add click listener to root layout to hide keyboard
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> {
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        });

        copyButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }

            if (!makeRawTxInfo()) {
                Toast.makeText(this, getString( R.string.failed_to_create_transaction_info), FreerApplication.TOAST_LASTING).show();
                return;
            }

            if (rawTxInfo == null) {
                Toast.makeText(this, getString( R.string.no_transaction_to_copy), FreerApplication.TOAST_LASTING).show();
                return;
            }

            String json = rawTxInfo.toNiceJson();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transaction JSON", json);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.copied), FreerApplication.TOAST_LASTING).show();
        });

        plusInputButton.setOnClickListener(v -> {
            // Hide keyboard before showing dialog
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }

            // Show popup menu for input options
            PopupMenuHelper popupMenuHelper = new PopupMenuHelper(this);
            popupMenuHelper.showInputOptionsMenu(v, new PopupMenuHelper.OnInputOptionSelectedListener() {
                @Override
                public void onMyCashSelected() {
                    // Start CashActivity for selecting cash
                    Intent intent = new Intent(CreateMultisignTxActivity.this, CashActivity.class);
                    intent.putExtra("select_mode", true);
                    startActivityForResult(intent, REQUEST_CODE_CHOOSE_CASH);
                }

                @Override
                public void onInputSelected() {
                    // Show the original AddTxInputDialog
                    AddTxInputDialog dialog = new AddTxInputDialog(CreateMultisignTxActivity.this, rawTxInfo);
                    currentDialog = dialog;
                    dialog.setOnDoneListener(cash -> {
                        if(cash==null)return;
                        TimberLogger.i(TAG, "AddTxInputDialog done, cash: " + cash);
                        TxInputCard card = new TxInputCard(CreateMultisignTxActivity.this);
                        card.setCash(cash);
                        card.setOnDeleteListener(CreateMultisignTxActivity.this::removeInputCard);
                        inputCards.add(card);
                        inputCardsContainer.addView(card, inputCardsContainer.getChildCount() - 1);
                        inputHint.setVisibility(View.GONE);
                        currentDialog = null;
                        updateTotalAndFeeText();
                    });
                    dialog.setOnDismissListener(d -> currentDialog = null);
                    dialog.show();
                }
            });
        });

        plusOutputButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }

            AddOutputFromFidListDialog dialog = new AddOutputFromFidListDialog(this, rawTxInfo, rest);
            currentDialog = dialog;
            dialog.setOnDoneListener(sendToList -> {
                for (SendTo sendTo : sendToList) {
                    TxOutputCard card = new TxOutputCard(this);
                    card.setSendTo(sendTo, this, true);
                    card.setOnDeleteListener(this::removeOutputCard);
                    outputCards.add(card);
                    outputCardsContainer.addView(card);
                }
                outputHint.setVisibility(View.GONE);
                currentDialog = null;
                updateTotalAndFeeText();
            });
            dialog.setOnDismissListener(d -> currentDialog = null);
            dialog.show();
        });

        // Add long press listener for plus output button
        plusOutputButton.setOnLongClickListener(v -> {
            AddTxOutputDialog dialog = new AddTxOutputDialog(this, rawTxInfo, rest);
            currentDialog = dialog;  // Set the current dialog
            dialog.setOnDoneListener(sendTo -> {
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(sendTo, this, true);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card);
                outputHint.setVisibility(View.GONE);
                currentDialog = null;  // Clear the current dialog
                updateTotalAndFeeText();
            });
            dialog.setOnDismissListener(d -> currentDialog = null);  // Clear the current dialog when dismissed
            dialog.show();
            return true;
        });

        // Add output button listener
        addOutputButton.setOnClickListener(v -> {
            if(outputFidInput.getText() == null || outputAmountInput.getText() == null){
                Toast.makeText(this, R.string.please_input_all_fields , FreerApplication.TOAST_LASTING).show();
                return;
            }
            if(outputFidInput.getText().toString().isEmpty() || outputAmountInput.getText().toString().isEmpty()){
                Toast.makeText(this,  R.string.please_input_all_fields , FreerApplication.TOAST_LASTING).show();
                return;
            }
            String fid = outputFidInput.getText().toString();
            if (!com.fc.fc_ajdk.core.crypto.KeyTools.isGoodFid(fid)) {
                Toast.makeText(this, R.string.invalid_fid, FreerApplication.TOAST_LASTING).show();
                return;
            }

            try {
                double amount = Double.parseDouble(outputAmountInput.getText().toString());
                
                // Validate amount range
                if (amount < com.fc.fc_ajdk.constants.Constants.MIN_AMOUNT || amount > com.fc.fc_ajdk.constants.Constants.MAX_AMOUNT) {
                    Toast.makeText(this, getString(R.string.amount_must_be_between, com.fc.fc_ajdk.constants.Constants.MIN_AMOUNT, com.fc.fc_ajdk.constants.Constants.MAX_AMOUNT), FreerApplication.TOAST_LASTING).show();
                    return;
                }

                // Validate against rest
                if (amount > FchUtils.satoshiToCoin(rest)) {
                    Toast.makeText(this, R.string.total_amount_exceeds_available_balance, FreerApplication.TOAST_LASTING).show();
                    return;
                }

                SendTo sendTo = new SendTo(fid, amount);
                rawTxInfo.getOutputs().add(sendTo);
                
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(sendTo, this, true);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card);
                outputHint.setVisibility(View.GONE);
                
                // Clear input fields
                outputFidInput.setText("");
                outputAmountInput.setText("");
                
                updateTotalAndFeeText();
            } catch (NumberFormatException e) {
                Toast.makeText(this, R.string.invalid_amount, FreerApplication.TOAST_LASTING).show();
            }
        });

        clearButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }

            rawTxInfo = new RawTxInfo();

            // Remove all views except the plus buttons
            for (int i = inputCardsContainer.getChildCount() - 1; i >= 0; i--) {
                View child = inputCardsContainer.getChildAt(i);
                if (child != plusInputButton) {
                    inputCardsContainer.removeView(child);
                }
            }

            for (int i = outputCardsContainer.getChildCount() - 1; i >= 0; i--) {
                View child = outputCardsContainer.getChildAt(i);
                if (child != plusOutputButton) {
                    outputCardsContainer.removeView(child);
                }
            }

            opreturnInput.setText("");
            outputFidInput.setText("");
            outputAmountInput.setText("");
            inputCards.clear();
            outputCards.clear();
            inputHint.setVisibility(View.VISIBLE);
            outputHint.setVisibility(View.VISIBLE);
        });

        importTxButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            importTxLauncher.launch(ImportTxInfoActivity.createIntent(this));
        });

        createButton.setOnClickListener(v -> {

            if (!makeRawTxInfo()) return;

            if (rawTxInfo == null) {
                TimberLogger.e(TAG, "Failed to create multisign TX");
                Toast.makeText(this, getString(R.string.failed_to_create_multisign_tx), FreerApplication.TOAST_LASTING).show();
                return;
            }

            // Start SignMultisignTxActivity
            Intent intent = new Intent(this, SignMultisignTxActivity.class);
            intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toJsonWithSenderInfo());
            signMultisignTxLauncher.launch(intent);  // Use launcher instead of startActivity
        });
    }

    private boolean makeRawTxInfo() {
        if(opreturnInput.getText()!=null && !opreturnInput.getText().toString().isEmpty())
            rawTxInfo.setOpReturn(opreturnInput.getText().toString());

        // Automatically use the current live FID as sender
        String liveFid = FidManager.getInstance().getLiveFid();
        if (liveFid == null) {
            Toast.makeText(this, getString(R.string.no_active_fid_available), FreerApplication.TOAST_LASTING).show();
            return false;
        }

        // For multisign transactions, ensure the live FID is a multisign address
        if (!liveFid.startsWith("3")) {
            Toast.makeText(this, getString(R.string.multisign_tx_requires_multisign_fid), FreerApplication.TOAST_LASTING).show();
            return false;
        }

        Multisign multisign = rawTxInfo.getMultisign();
        if (multisign == null) {
            // Try to get it from MultisignManager using the live FID
            KeyInfo keyInfo = FidManager.getInstance().getLiveKeyInfo();
            multisign = keyInfo.getMultisign();
            if (multisign == null) {
                Toast.makeText(this, getString(R.string.failed_to_get_multisign_info_for_fid), FreerApplication.TOAST_LASTING).show();
                return false;
            }
            rawTxInfo.setMultisign(multisign);
        }

        if(!goodTxInfo(rawTxInfo)) return false;

        // Automatically set sender to live FID
        rawTxInfo.setSender(liveFid);
        return true;
    }

    private void updateTotalAndFeeText() {
        updateTotalsAndFee(rawTxInfo);
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);

        // Create SpannableString for output text
        String outputText = String.format(Locale.US, "%s %s F    %s %s c",
                getString(R.string.paying),
                df.format(FchUtils.satoshiToCoin(totalOutput)),
                getString(R.string.fee),
                df.format(FchUtils.satoshiToCash(fee)));
        SpannableString spannableOutput = new SpannableString(outputText);
        
        // Make "Paying" and "Fee" bold and colored
        int payingIndex = outputText.indexOf(getString(R.string.paying));
        int feeIndex = outputText.indexOf(getString(R.string.fee));
        int fieldNameColor = getResources().getColor(R.color.field_name);
        
        spannableOutput.setSpan(new StyleSpan(Typeface.BOLD), payingIndex, payingIndex + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableOutput.setSpan(new ForegroundColorSpan(fieldNameColor), payingIndex, payingIndex + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        spannableOutput.setSpan(new StyleSpan(Typeface.BOLD), feeIndex, feeIndex + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableOutput.setSpan(new ForegroundColorSpan(fieldNameColor), feeIndex, feeIndex + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        totalAndFeeText.setText(spannableOutput);

        // Create SpannableString for input text
        int cashCount = (rawTxInfo == null || rawTxInfo.getInputs() == null) ? 0 : rawTxInfo.getInputs().size();
        String inputText = String.format(Locale.US, "%s %d cash   %s F    %d cd",
                getString(R.string.spending),
                cashCount,
                df.format(FchUtils.satoshiToCoin(totalInput)),
                totalCd);
        SpannableString spannableInput = new SpannableString(inputText);
        
        // Make "Spending" bold and colored
        int spendingIndex = inputText.indexOf(getString(R.string.spending));
        spannableInput.setSpan(new StyleSpan(Typeface.BOLD), spendingIndex, spendingIndex + getString(R.string.spending).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableInput.setSpan(new ForegroundColorSpan(fieldNameColor), spendingIndex, spendingIndex + getString(R.string.spending).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        totalInputText.setText(spannableInput);
        
        // Update Max text for output input
        DecimalFormat df2 = new DecimalFormat("0.########");
        df2.setDecimalSeparatorAlwaysShown(false);
        String maxTextStr = String.format(Locale.US, "Max: %s F", df2.format(FchUtils.satoshiToCoin(rest)));
        outputMaxText.setText(maxTextStr);
        outputMaxText.setOnClickListener(v -> {
            outputAmountInput.setText(df2.format(FchUtils.satoshiToCoin(rest)));
        });
    }

    private boolean goodTxInfo(RawTxInfo rawTxInfo) {

        List<Cash> inputs = rawTxInfo.getInputs();
        for(Cash cash : inputs){
            if(cash.getValue()<=0){
                Toast.makeText(this, getString(R.string.input_value_must_be_greater_than_0), FreerApplication.TOAST_LASTING).show();
                return false;
            }
            if(!Hex.isHex32(cash.getBirthTxId())){
                Toast.makeText(this, getString(R.string.invalid_txid), FreerApplication.TOAST_LASTING).show();
                return false;
            }
            if(cash.getBirthIndex()<0){
                Toast.makeText(this, getString(R.string.invalid_index), FreerApplication.TOAST_LASTING).show();
                return false;
            }
        }

        if(rawTxInfo.getMultisign()==null){
            Toast.makeText(this, getString(R.string.failed_to_parse_multisign), FreerApplication.TOAST_LASTING).show();
            return false;
        }

        if(rawTxInfo.getInputs()==null || rawTxInfo.getInputs().isEmpty()){
            Toast.makeText(this, getString(R.string.inputs_cannot_be_empty), FreerApplication.TOAST_LASTING).show();
            return false;
        }


        updateTotalsAndFee(rawTxInfo);


        if(totalInput<fee+totalOutput){
            Toast.makeText(this, getString(R.string.inputs_are_not_enough_to_cover_the_fee), FreerApplication.TOAST_LASTING).show();
            return false;
        }

        updateTotalAndFeeText();
        return true;
    }

    private void updateTotalsAndFee(RawTxInfo rawTxInfo) {
        totalInput = 0;
        totalOutput = 0;
        totalCd = 0;
        if(rawTxInfo ==null){
            rawTxInfo = new RawTxInfo();
        }
        if(rawTxInfo.getInputs()==null) rawTxInfo.setInputs(new ArrayList<>());

        if(rawTxInfo.getOutputs()==null) rawTxInfo.setOutputs(new ArrayList<>());

        for(Cash cash : rawTxInfo.getInputs()){
            totalInput += cash.getValue();
            // Add cd from each cash
            if (cash.getCd() != null) {
                totalCd += cash.getCd();
            }
        }

        totalOutput = 0;
        for(SendTo sendTo : rawTxInfo.getOutputs()){
            totalOutput += FchUtils.coinToSatoshi(sendTo.getAmount());
        }

        // Only calculate fee if there are inputs (outputs can be empty)
        if (!rawTxInfo.getInputs().isEmpty()) {
            int opReturnBytesLen = rawTxInfo.getOpReturn() == null ?0: rawTxInfo.getOpReturn().getBytes().length;
            int inputNum = rawTxInfo.getInputs().size();
            int outputNum = rawTxInfo.getOutputs().size();

            int m = rawTxInfo.getMultisign()==null? 2: rawTxInfo.getMultisign().getM();
            int n = rawTxInfo.getMultisign()==null? 3: rawTxInfo.getMultisign().getN();

            fee = feeRateLong * txHandler.calcSizeMultiSign(inputNum, outputNum, opReturnBytesLen, m, n);
            rest = totalInput-totalOutput-fee;
        }
    }

    private void removeInputCard(TxInputCard card) {
        TimberLogger.i(TAG, "removeInputCard called");
        inputCards.remove(card);
        inputCardsContainer.removeView(card);
        rawTxInfo.getInputs().remove(card.getCash());
        if (inputCards.isEmpty()) {
            inputHint.setVisibility(View.VISIBLE);
        }
        updateTotalAndFeeText();
    }

    private void removeOutputCard(TxOutputCard card) {
        TimberLogger.i(TAG, "removeOutputCard called");
        outputCards.remove(card);
        outputCardsContainer.removeView(card);
        rawTxInfo.getOutputs().remove(card.getSendTo());
        if (outputCards.isEmpty()) {
            outputHint.setVisibility(View.VISIBLE);
        }
        updateTotalAndFeeText();
    }

    private void handleImportedTxInfo(RawTxInfo importedRawTxInfo) {
        TimberLogger.i(TAG, "handleImportedTxInfo started");
        TimberLogger.i(TAG, "Imported RawTxInfo: " + (importedRawTxInfo != null ? "not null" : "null"));

        if (importedRawTxInfo == null) {
            TimberLogger.e(TAG, "Imported RawTxInfo is null");
            return;
        }

        // Copy all fields from importedRawTxInfo to rawTxInfo
        rawTxInfo.copy(importedRawTxInfo);

        // Clear existing views and lists

        // Clear input cards
        for (int i = inputCardsContainer.getChildCount() - 1; i >= 0; i--) {
            View child = inputCardsContainer.getChildAt(i);
            if (child != plusInputButton) {
                inputCardsContainer.removeView(child);
            }
        }
        inputCards.clear();

        // Clear output cards
        for (int i = outputCardsContainer.getChildCount() - 1; i >= 0; i--) {
            View child = outputCardsContainer.getChildAt(i);
            if (child != plusOutputButton) {
                outputCardsContainer.removeView(child);
            }
        }
        outputCards.clear();

        // Clear text inputs
        opreturnInput.setText("");

        // Add input cards - check if we have cash with owner information
        if (rawTxInfo.getInputs() != null) {
            TimberLogger.i(TAG, "Adding input cards, count: " + rawTxInfo.getInputs().size());
            boolean hasCashWithOwner = false;
            for (Cash cash : rawTxInfo.getInputs()) {
                if (cash.getOwner() != null && !cash.getOwner().isEmpty()) {
                    hasCashWithOwner = true;
                    break;
                }
            }
            
            if (hasCashWithOwner) {
                // Use cash cards for cash with owner information
                addCashCards(rawTxInfo.getInputs());
            } else {
                // Use TxInputCard for cash without owner information
                for (Cash cash : rawTxInfo.getInputs()) {
                    TimberLogger.i(TAG, "Creating input card for cash: " + cash);
                    TxInputCard card = new TxInputCard(this);
                    card.setCash(cash);
                    card.setOnDeleteListener(this::removeInputCard);
                    inputCards.add(card);
                    inputCardsContainer.addView(card, inputCardsContainer.getChildCount() - 1);
                    TimberLogger.i(TAG, "Added input card, new container child count: " + inputCardsContainer.getChildCount());
                }
            }
        } else {
            TimberLogger.w(TAG, "No inputs to add");
        }

        // Add output cards
        if (rawTxInfo.getOutputs() != null) {
            TimberLogger.i(TAG, "Adding output cards, count: " + rawTxInfo.getOutputs().size());
            for (SendTo sendTo : rawTxInfo.getOutputs()) {
                TimberLogger.i(TAG, "Creating output card for sendTo: " + sendTo);
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(sendTo, this, true);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card, outputCardsContainer.getChildCount() - 1);

                updateTotalAndFeeText();

                TimberLogger.i(TAG, "Added output card, new container child count: " + outputCardsContainer.getChildCount());
            }
        } else {
            TimberLogger.w(TAG, "No outputs to add");
        }

        // Set opreturn text
        if (rawTxInfo.getOpReturn() != null) {
            TimberLogger.i(TAG, "Setting opreturn text: " + rawTxInfo.getOpReturn());
            opreturnInput.setText(rawTxInfo.getOpReturn());
        } else {
            TimberLogger.w(TAG, "No message to set in opreturn");
        }


        // Update hints visibility
        boolean inputHintVisible = inputCards.isEmpty();
        boolean outputHintVisible = outputCards.isEmpty();
        TimberLogger.i(TAG, "Setting hints visibility - Input hint: " + inputHintVisible + ", Output hint: " + outputHintVisible);
        inputHint.setVisibility(inputHintVisible ? View.VISIBLE : View.GONE);
        outputHint.setVisibility(outputHintVisible ? View.VISIBLE : View.GONE);

        updateTotalAndFeeText();

        TimberLogger.i(TAG, "Final container states - Input cards: " + inputCards.size() +
            ", Output cards: " + outputCards.size() +
            ", Input container children: " + inputCardsContainer.getChildCount() +
            ", Output container children: " + outputCardsContainer.getChildCount());

        TimberLogger.i(TAG, "handleImportedTxInfo completed");
    }

    @Override
    protected int getLayoutId() {
        TimberLogger.i(TAG, "getLayoutId called, returning: " + R.layout.activity_create_multisign_tx);
        return R.layout.activity_create_multisign_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_tx);
    }

    @Override
    protected void initializeViews() {
        TimberLogger.i(TAG, "initializeViews called");
        initViews();
        updateTotalAndFeeText();  // Initialize the text with initial values
    }

    @Override
    protected void setupButtons() {
        TimberLogger.i(TAG, "setupButtons called");
        setupListeners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        TimberLogger.i(TAG, "handleQrScanResult called with requestCode: " + requestCode + ", qrContent: " + qrContent);
        if (qrContent == null || qrContent.isEmpty()) {
            TimberLogger.e(TAG, "Invalid QR code content");
            showToast(getString(R.string.invalid_qr_code_content));
            return;
        }

        // Check if any dialog is showing and handle its QR scan result
        if (currentDialog != null) {
            TimberLogger.i(TAG, "Current dialog is: " + currentDialog.getClass().getSimpleName());
            if (currentDialog instanceof AddTxInputDialog) {
                TimberLogger.i(TAG, "Handling QR scan result for AddTxInputDialog");
                ((AddTxInputDialog) currentDialog).handleQrScanResult(requestCode, qrContent);
            } else if (currentDialog instanceof AddTxOutputDialog) {
                TimberLogger.i(TAG, "Handling QR scan result for AddTxOutputDialog with requestCode: " + requestCode);
                ((AddTxOutputDialog) currentDialog).handleQrScanResult(requestCode, qrContent);
            } else {
                TimberLogger.e(TAG, "Unknown dialog type: " + currentDialog.getClass().getSimpleName());
            }
        } else {
            // Handle QR scan results for the main activity
            TimberLogger.i(TAG, "No dialog showing, handling QR scan result for main activity");
            if (requestCode == QR_SCAN_TEXT_REQUEST_CODE) {
                TimberLogger.i(TAG, "Setting QR content to opreturnInput: " + qrContent);
                opreturnInput.setText(qrContent);
            } else if (requestCode == QR_SCAN_OUTPUT_FID_REQUEST_CODE) {
                TimberLogger.i(TAG, "Setting QR content to outputFidInput: " + qrContent);
                outputFidInput.setText(qrContent);
            } else if (requestCode == QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE) {
                TimberLogger.i(TAG, "Setting QR content to outputAmountInput: " + qrContent);
                outputAmountInput.setText(qrContent);
            } else {
                TimberLogger.e(TAG, "Unknown request code: " + requestCode);
            }
        }
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clear any pending operations
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
            currentDialog = null;
        }

        // Clear references
        inputCards.clear();
        outputCards.clear();
        rawTxInfo = null;
        rawTxInfo = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Hide keyboard when activity is paused
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_CHOOSE_CASH && resultCode == RESULT_OK && data != null) {
            // Handle result from CashActivity
            List<String> cashJsonList = data.getStringArrayListExtra("selected_cash");
            if (cashJsonList != null && !cashJsonList.isEmpty()) {
                // Convert JSON strings back to Cash objects
                List<Cash> selectedCash = new ArrayList<>();
                for (String cashJson : cashJsonList) {
                    try {
                        Cash cash = Cash.fromJson(cashJson);
                        if (cash != null) {
                            selectedCash.add(cash);
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error parsing cash JSON: " + e.getMessage());
                    }
                }
                
                if (!selectedCash.isEmpty()) {
                    // Add selected cash to the transaction
                    for (Cash cash : selectedCash) {
                        rawTxInfo.getInputs().add(cash);
                    }
                    
                    // Check if we have cash with owner information
                    boolean hasCashWithOwner = false;
                    for (Cash cash : selectedCash) {
                        if (cash.getOwner() != null && !cash.getOwner().isEmpty()) {
                            hasCashWithOwner = true;
                            break;
                        }
                    }
                    
                    if (hasCashWithOwner) {
                        // Use cash cards for cash with owner information
                        addCashCards(selectedCash);
                    } else {
                        // Use TxInputCard for cash without owner information
                        for (Cash cash : selectedCash) {
                            TxInputCard card = new TxInputCard(this);
                            card.setCash(cash);
                            card.setOnDeleteListener(this::removeInputCard);
                            inputCards.add(card);
                            inputCardsContainer.addView(card, inputCardsContainer.getChildCount() - 1);
                        }
                        inputHint.setVisibility(View.GONE);
                    }
                    
                    updateTotalAndFeeText();
                }
            }
        }
    }

    private void addCashCards(List<Cash> cashList) {
        for (Cash cash : cashList) {
            View cardView = LayoutInflater.from(this).inflate(R.layout.item_cash_card, inputCardsContainer, false);
            
            TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
            TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
            ImageButton deleteButton = cardView.findViewById(R.id.deleteButton);

            // Set amount value
            String amountText = FchUtils.satoshiToCoin(cash.getValue()) + " F";
            amountValue.setText(amountText);

            // Set CD value
            Long cd = cash.getCd();
            if (cd != null) {
                cdValue.setText(String.format(Locale.US, "%d cd", cd));
            } else {
                cdValue.setText("0 cd");
            }

            // Set click listeners to show detail activity
            View.OnClickListener showDetailListener = v -> showCashDetailActivity(cash);
            amountValue.setOnClickListener(showDetailListener);
            cdValue.setOnClickListener(showDetailListener);
            cardView.setOnClickListener(showDetailListener);

            // Add delete button functionality
            deleteButton.setOnClickListener(v -> {
                // Remove the cash from rawTxInfo
                rawTxInfo.getInputs().remove(cash);
                // Remove the card view
                inputCardsContainer.removeView(cardView);
                // Update totals
                updateTotalAndFeeText();
                // Show hint if no more inputs
                if (rawTxInfo.getInputs().isEmpty()) {
                    inputHint.setVisibility(View.VISIBLE);
                }
            });

            inputCardsContainer.addView(cardView, inputCardsContainer.getChildCount() - 1);
            
        }

        // Hide input hint since we have cash cards
        inputHint.setVisibility(View.GONE);
    }

    public void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void showCashDetailActivity(Cash cash) {
        Intent intent = new Intent(this, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, cash.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Cash.class.getName());
        startActivity(intent);
    }
} 