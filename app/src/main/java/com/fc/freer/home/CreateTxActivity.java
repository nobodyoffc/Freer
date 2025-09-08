package com.fc.freer.home;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxCreator;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.tx.ImportTxInfoActivity;
import com.fc.freer.tx.SendTxActivity;
import com.fc.freer.tx.dialog.AddTxInputDialog;
import com.fc.freer.tx.dialog.AddTxOutputDialog;
import com.fc.freer.tx.dialog.AddOutputFromFidListDialog;
import com.fc.freer.tx.view.TxInputCard;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.manager.FidManager;
import com.fc.freer.ui.PopupMenuHelper;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.DecimalFormat;

public class CreateTxActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateTxActivity";
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_OUTPUT_FID_REQUEST_CODE = 1005;
    private static final int QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE = 1006;
    
    public static final String EXTRA_TX_INFO_JSON = "extra_tx_info_json";

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
    private Button signButton;
    private Button copyButton;
    private ImageButton plusInputButton;
    private TextView plusOutputButton;
    private TextView outputHint;
    private TextView inputHint;
    private TextView totalAndFeeText;
    private TextView totalInputText;

    private KeyInfo keyInfo;
    private String opreturn;
    private long fee;
    private long totalInput;
    private long totalOutput;
    private long rest;
    private long totalCd;

    private ActivityResultLauncher<Intent> importTxLauncher;
    private ActivityResultLauncher<Intent> signTxLauncher;

    private Dialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimberLogger.i(TAG, "onCreate started");

        // Set window flags to prevent transition issues
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );


        // Initialize rawTxInfo
        rawTxInfo = new RawTxInfo();
        TimberLogger.i(TAG, "RawTxInfo initialized");

        // Check if we received RawTxInfo from CashActivity
        String receivedTxInfoJson = getIntent().getStringExtra(EXTRA_TX_INFO_JSON);
        if (receivedTxInfoJson != null) {
            try {
                RawTxInfo receivedRawTxInfo = RawTxInfo.fromJson(receivedTxInfoJson, RawTxInfo.class);
                if (receivedRawTxInfo != null) {
                    handleImportedTxInfo(receivedRawTxInfo);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error parsing received RawTxInfo JSON: " + e.getMessage());
            }
        }

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

        // Initialize signTxLauncher
        signTxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == SendTxActivity.RESULT_SIGNED) {
                    finish();  // Finish this activity when transaction is signed
                }
            }
        );
    }

    private void initViews() {
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
        signButton = findViewById(R.id.createButton);
        copyButton = findViewById(R.id.copyButton);
        plusInputButton = findViewById(R.id.plusInputButton);
        plusOutputButton = findViewById(R.id.addFromList);

        // Setup icons
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

        plusInputButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "plusInputButton clicked");
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
                    Intent intent = new Intent(CreateTxActivity.this, CashActivity.class);
                    intent.putExtra("select_mode", true);
                    startActivityForResult(intent, 1003);
                }

                @Override
                public void onInputSelected() {
                    // Show the original AddTxInputDialog
                    AddTxInputDialog dialog = new AddTxInputDialog(CreateTxActivity.this, rawTxInfo);
                    currentDialog = dialog;
                    dialog.setOnDoneListener(cash -> {
                        if(cash==null)return;
                        TimberLogger.i(TAG, "AddTxInputDialog done, cash: " + cash);
                        TxInputCard card = new TxInputCard(CreateTxActivity.this);
                        card.setCash(cash);
                        card.setOnDeleteListener(CreateTxActivity.this::removeInputCard);
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
            TimberLogger.i(TAG, "plusOutputButton clicked");
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
            TimberLogger.i(TAG, "plusOutputButton long pressed");
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
            if (!KeyTools.isGoodFid(fid)) {
                Toast.makeText(this, R.string.invalid_fid, FreerApplication.TOAST_LASTING).show();
                return;
            }

            try {
                double amount = Double.parseDouble(outputAmountInput.getText().toString());
                
                // Validate amount range
                if (amount < Constants.MIN_AMOUNT || amount > Constants.MAX_AMOUNT) {
                    Toast.makeText(this, getString(R.string.amount_must_be_between, Constants.MIN_AMOUNT, Constants.MAX_AMOUNT), FreerApplication.TOAST_LASTING).show();
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
            TimberLogger.i(TAG, "clearButton clicked");
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
            TimberLogger.i(TAG, "importTxButton clicked");
            // Hide keyboard
//            View currentFocus = getCurrentFocus();
//            if (currentFocus != null) {
//                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
//                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
//            }
            importTxLauncher.launch(ImportTxInfoActivity.createIntent(this));
        });

        signButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "signButton clicked");

            updateOffLineTxInfo();

            if(rawTxInfo ==null){
                Toast.makeText(this, R.string.no_tx_to_sign, FreerApplication.TOAST_LASTING).show();
                return;
            }

            if(!goodTxInfo(rawTxInfo)) return;

            // Convert RawTxInfo to JSON
            String offLineTxInfoJson = rawTxInfo.toJsonWithSenderInfo();

            // Start SendTxActivity
            Intent intent = new Intent(this, SendTxActivity.class);
            intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, offLineTxInfoJson);

            signTxLauncher.launch(intent);
        });

        copyButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "copyButton clicked");
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }


            updateOffLineTxInfo();


            if(rawTxInfo ==null){
                Toast.makeText(this, R.string.no_tx_to_copy, FreerApplication.TOAST_LASTING).show();
                return;
            }
            RawTxInfo copied = new RawTxInfo().copy(rawTxInfo);
            copied.setSenderInfo(null);
            String json = copied.toNiceJson();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transaction JSON", json);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.tx_json_copied_to_clipboard, FreerApplication.TOAST_LASTING).show();

        });
    }

    private void updateOffLineTxInfo() {
        opreturn = opreturnInput.getText()==null? null:opreturnInput.getText().toString();

        // Get current KeyInfo from FidManager
        keyInfo = FidManager.getInstance().getLiveKeyInfo();

        if(opreturn !=null || keyInfo!=null){
            if(rawTxInfo ==null)
                rawTxInfo = new RawTxInfo();
            if(opreturn !=null) rawTxInfo.setOpReturn(opreturn);
            if(keyInfo!=null){
                rawTxInfo.setSenderInfo(keyInfo);
                rawTxInfo.setSender(keyInfo.getId());
            }
        }
    }

    private void updateTotalAndFeeText() {
        updateTotalsAndFee();
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
        
        spannableOutput.setSpan(new StyleSpan(Typeface.BOLD), payingIndex, payingIndex + getString(R.string.paying).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableOutput.setSpan(new ForegroundColorSpan(fieldNameColor), payingIndex, payingIndex + getString(R.string.paying).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        spannableOutput.setSpan(new StyleSpan(Typeface.BOLD), feeIndex, feeIndex + getString(R.string.fee).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableOutput.setSpan(new ForegroundColorSpan(fieldNameColor), feeIndex, feeIndex + getString(R.string.fee).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        
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
                Toast.makeText(this, R.string.input_value_must_be_greater_than_0 , FreerApplication.TOAST_LASTING).show();
                return false;
            }
            if(!Hex.isHex32(cash.getBirthTxId())){
                Toast.makeText(this, R.string.invalid_txid , FreerApplication.TOAST_LASTING).show();
                return false;
            }
            if(cash.getBirthIndex()<0){
                Toast.makeText(this, R.string.invalid_index , FreerApplication.TOAST_LASTING).show();
                return false;
            }
        }

        if(rawTxInfo.getInputs()==null || rawTxInfo.getInputs().isEmpty()){
            Toast.makeText(this, R.string.inputs_cannot_be_empty , FreerApplication.TOAST_LASTING).show();
            return false;
        }

        updateTotalsAndFee();

        if(totalInput<fee+totalOutput){
            Toast.makeText(this, R.string.inputs_are_not_enough_to_cover_the_fee , FreerApplication.TOAST_LASTING).show();
            return false;
        }

        updateTotalAndFeeText();
        return true;
    }

    private void updateTotalsAndFee() {
        totalInput = 0;
        totalOutput = 0;
        fee = 0;
        rest = 0;
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

        for(SendTo sendTo : rawTxInfo.getOutputs()){
            totalOutput += FchUtils.coinToSatoshi(sendTo.getAmount());
        }

        // Only calculate fee if there are inputs (outputs can be empty)
        if (!rawTxInfo.getInputs().isEmpty()) {
            int opReturnBytesLen = rawTxInfo.getOpReturn() == null ? 0 : rawTxInfo.getOpReturn().getBytes().length;
            int inputNum = rawTxInfo.getInputs().size();
            int outputNum = rawTxInfo.getOutputs().size();

            fee = TxCreator.calcFee(inputNum, outputNum, opReturnBytesLen, TxCreator.DEFAULT_FEE_RATE, false, null);
            rest = totalInput - totalOutput - fee;
        }
    }

    private void removeInputCard(TxInputCard card) {
        inputCards.remove(card);
        inputCardsContainer.removeView(card);
        rawTxInfo.getInputs().remove(card.getCash());
        if (inputCards.isEmpty()) {
            inputHint.setVisibility(View.VISIBLE);
        }
        updateTotalAndFeeText();
    }

    private void removeOutputCard(TxOutputCard card) {
        outputCards.remove(card);
        outputCardsContainer.removeView(card);
        rawTxInfo.getOutputs().remove(card.getSendTo());
        if (outputCards.isEmpty()) {
            outputHint.setVisibility(View.VISIBLE);
        }
        updateTotalAndFeeText();
    }

    private void handleImportedTxInfo(RawTxInfo importedRawTxInfo) {

        if (importedRawTxInfo == null) {
            TimberLogger.e(TAG, "Imported RawTxInfo is null");
            return;
        }

        keyInfo=null;
        opreturn=null;

        // Copy all fields from importedRawTxInfo to rawTxInfo
        rawTxInfo.copy(importedRawTxInfo);

        // Always use current live FID's keyInfo as sender
        if(rawTxInfo.getSenderInfo()==null){
            keyInfo = FidManager.getInstance().getLiveKeyInfo();
            if(keyInfo != null){
                rawTxInfo.setSenderInfo(keyInfo);
                rawTxInfo.setSender(keyInfo.getId());
            }
        }
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
        addCashCards(rawTxInfo.getInputs());

        // Add output cards
        if (rawTxInfo.getOutputs() != null) {
            for (SendTo sendTo : rawTxInfo.getOutputs()) {
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(sendTo, this, true);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card);
            }
        }

        // Set opreturn text
        if (rawTxInfo.getOpReturn() != null) {
            opreturnInput.setText(rawTxInfo.getOpReturn());
        }

        // Update hints visibility
        inputHint.setVisibility(inputCards.isEmpty() ? View.VISIBLE : View.GONE);
        outputHint.setVisibility(outputCards.isEmpty() ? View.VISIBLE : View.GONE);

        updateTotalAndFeeText();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_multisign_tx;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_tx);
    }

    @Override
    protected void initializeViews() {
        initViews();
    }

    @Override
    protected void setupButtons() {
        setupListeners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (qrContent == null || qrContent.isEmpty()) {
            showToast(getString(R.string.invalid_qr_code_content));
            return;
        }

        // Check if any dialog is showing and handle its QR scan result
        if (currentDialog != null) {
            if (currentDialog instanceof AddTxInputDialog) {
                ((AddTxInputDialog) currentDialog).handleQrScanResult(requestCode, qrContent);
            } else if (currentDialog instanceof AddTxOutputDialog) {
                ((AddTxOutputDialog) currentDialog).handleQrScanResult(requestCode, qrContent);
            }
        } else {
            // Handle QR scan results for the main activity
            if (requestCode == QR_SCAN_TEXT_REQUEST_CODE) {
                opreturnInput.setText(qrContent);
            } else if (requestCode == QR_SCAN_OUTPUT_FID_REQUEST_CODE) {
                outputFidInput.setText(qrContent);
            } else if (requestCode == QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE) {
                outputAmountInput.setText(qrContent);
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
            
            // Set sender if we have a valid owner
            if (cash.getOwner() != null && !cash.getOwner().isEmpty()) {
                }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 1003 && resultCode == RESULT_OK && data != null) {
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
                        
                        // Create cash card view
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
                        
                        // Set sender if we have a valid owner
                        if (cash.getOwner() != null && !cash.getOwner().isEmpty()) {
                                        }
                    }

                    // Hide input hint since we have cash cards
                    inputHint.setVisibility(View.GONE);
                    updateTotalAndFeeText();
                }
            }
        }
    }
} 