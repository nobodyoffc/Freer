package com.fc.freer.tx;

import static android.view.View.VISIBLE;
import static com.fc.fc_ajdk.constants.Constants.CHANGE_OUTPUT_FEE;
import static com.fc.fc_ajdk.constants.Constants.CHANGE_P2SH_OUTPUT_FEE;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.account.CashActivity;
import com.fc.freer.multisig.SignMultisigTxActivity;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.JsonUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.P2SH;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.tx.dialog.AddTxInputDialog;
import com.fc.freer.tx.dialog.AddTxOutputDialog;
import com.fc.freer.tx.dialog.AddOutputFromFidListDialog;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.ui.PopupMenuHelper;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.ContactManager;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.DecimalFormat;
import java.util.Map;

public class CreateTxActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateTxActivity";
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_OUTPUT_FID_REQUEST_CODE = 1005;
    private static final int QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE = 1006;

    // The fee cost of a change output (34 satoshis)
    // When rest == -CHANGE_OUTPUT_FEE, we can skip the change output

    public static final String EXTRA_TX_INFO_JSON = "extra_tx_info_json";

    private RawTxInfo rawTxInfo;

    private LinearLayout txContainer;
    private LinearLayout inputCardsContainer;
    private LinearLayout outputCardsContainer;
    private LinearLayout outputInputContainer;
    private LinearLayout opreturnContainer;
    private LinearLayout buttonContainer;

    private List<View> inputCards = new ArrayList<>();
    private List<TxOutputCard> outputCards = new ArrayList<>();

    private TextInputEditText opreturnInput;
    private TextInputEditText outputFidInput;
    private TextInputEditText outputAmountInput;
    private TextInputEditText outputLockDaysInput;
    private TextView outputRestText;
    private ImageButton addOutputButton;
    private ImageButton clearButton;
    private ImageButton copyButton;
    private ImageButton importButton;
    private ImageButton createButton;
    private ImageButton plusInputButton;
    private ImageView payToListView;
    private ImageView lockTimeToggleView;
    private ImageView importArrayButton;
    private TextView outputHint;
    private TextView totalAndFeeText;
    private TextView totalInputText;

    private KeyInfo keyInfo;
    private String opreturn;
    private long fee;
    private long totalInput;
    private long totalOutput;
    private long rest;
    private long totalCd;
    private boolean isLockModeEnabled = false;
    private boolean feeCalculationFailed = false; // Flag to track if fee calculation failed

    private ActivityResultLauncher<Intent> importTxLauncher;
    private ActivityResultLauncher<Intent> importSendToArrayLauncher;
    private ActivityResultLauncher<Intent> signTxLauncher;
    private ActivityResultLauncher<Intent> chooseContactLauncher;

    private Dialog currentDialog;
    private WaitingDialog waitingDialog;

    // Cache for fetched multisig info (FID -> Multisig)
    private java.util.HashMap<String, Multisig> cachedMultisignMap = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
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
                    String importedText = result.getData().getStringExtra(com.fc.freer.ui.ImportActivity.EXTRA_IMPORT_RESULT);
                    if (importedText != null) {
                        String liveFid = FidManager.getInstance().getLiveFid();
                        com.fc.freer.utils.ImportParser.ParseResult<RawTxInfo> parseResult =
                            com.fc.freer.utils.ImportParser.parseTxInfo(this, importedText, liveFid);

                        if (parseResult.success && parseResult.data != null) {
                            handleImportedTxInfo(parseResult.data);
                        } else {
                            showToast(parseResult.error);
                        }
                    }
                }
            }
        );

        // Initialize importSendToArrayLauncher
        importSendToArrayLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                    String importedText = result.getData().getStringExtra(com.fc.freer.ui.ImportActivity.EXTRA_IMPORT_RESULT);
                    if (importedText != null) {
                        com.fc.freer.utils.ImportParser.ParseResult<List<Cash>> parseResult =
                            com.fc.freer.utils.ImportParser.parseSendToArray(this, importedText);

                        if (parseResult.success && parseResult.data != null && !parseResult.data.isEmpty()) {
                            handleImportedSendToArray(parseResult.data);
                        } else {
                            showToast(parseResult.error);
                        }
                    }
                }
            }
        );

        // Initialize signTxLauncher
        signTxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == SendTxActivity.RESULT_SIGNED ||
                    result.getResultCode() == SignMultisigTxActivity.RESULT_SENT) {
                    finish();  // Finish this activity when transaction is signed
                }
            }
        );

        // Initialize chooseContactLauncher
        chooseContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleContactSelectionResult(result.getData());
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
        totalAndFeeText = findViewById(R.id.totalAndFeeText);
        totalInputText = findViewById(R.id.totalInputText);

        opreturnInput = opreturnContainer.findViewById(R.id.opreturnInput).findViewById(R.id.textInput);
        opreturnInput.setHint(R.string.input_the_text_carved_on_chain);

        // Initialize output input fields
        outputFidInput = outputInputContainer.findViewById(R.id.outputFidInput).findViewById(R.id.keyInput);
        outputFidInput.setHint(R.string.fid);
        outputAmountInput = outputInputContainer.findViewById(R.id.outputAmountInput).findViewById(R.id.textInput);
        outputAmountInput.setHint(R.string.amount);
        outputRestText = findViewById(R.id.outputRestText);
        addOutputButton = findViewById(R.id.addOutputButton);

        // Disable plus icon by default
        addOutputButton.setEnabled(false);
        addOutputButton.setAlpha(0.3f);

        // Initialize per-output lockTime input (hidden by default)
        View lockDaysInputView = findViewById(R.id.outputLockDaysInput);
        outputLockDaysInput = lockDaysInputView.findViewById(R.id.numberInput);
        outputLockDaysInput.setHint(R.string.input_days_to_lock);

        clearButton = findViewById(R.id.clearButton);
        copyButton = findViewById(R.id.copyButton);
        importButton = findViewById(R.id.importButton);
        createButton = findViewById(R.id.createButton);
        plusInputButton = findViewById(R.id.plusInputButton);
        payToListView = findViewById(R.id.addFromList);
        lockTimeToggleView = findViewById(R.id.lockTimeToggle);
        importArrayButton = findViewById(R.id.importArrayButton);

        // Setup icons
        setupTextIcons(R.id.opreturnInput, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);
        setupPeopleAndScanIcons(R.id.outputFidInput, R.id.peopleAndScanIcons, QR_SCAN_OUTPUT_FID_REQUEST_CODE);
        setupTextIcons(R.id.outputAmountInput, R.id.scanIcon, QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE);

        // Add text change listeners to validate and enable/disable plus button
        setupOutputFieldWatchers();
    }

    private void setupOutputFieldWatchers() {
        android.text.TextWatcher outputFieldWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                validateOutputFields();
            }
        };

        outputFidInput.addTextChangedListener(outputFieldWatcher);
        outputAmountInput.addTextChangedListener(outputFieldWatcher);

        // Special watcher for lock days input
        outputLockDaysInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                validateOutputFields();
                if (isLockModeEnabled) {
                    updateTotalAndFeeText();
                }
            }
        });

        // Watcher for opreturn input to update fee and rest when text changes
        opreturnInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // Update fee and rest calculations when opreturn text changes
                updateTotalAndFeeText();
            }
        });
    }

    private void validateOutputFields() {
        boolean isValid = true;

        // Check FID
        String fid = outputFidInput.getText() != null ? outputFidInput.getText().toString().trim() : "";
        if (fid.isEmpty()) {
            isValid = false;
        }

        // Check amount
        String amount = outputAmountInput.getText() != null ? outputAmountInput.getText().toString().trim() : "";
        if (amount.isEmpty()) {
            isValid = false;
        }

        // Check lock days if lock mode is enabled
        if (isLockModeEnabled) {
            String lockDays = outputLockDaysInput.getText() != null ? outputLockDaysInput.getText().toString().trim() : "";
            if (lockDays.isEmpty()) {
                isValid = false;
            }
        }

        // Enable/disable button based on validation
        addOutputButton.setEnabled(isValid);
        addOutputButton.setAlpha(isValid ? 1.0f : 0.3f);
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

                        // Use unified layout for input card
                        View cardView = createInputCardView(cash);
                        inputCards.add(cardView);
                        inputCardsContainer.addView(cardView, inputCardsContainer.getChildCount() - 1);
                        currentDialog = null;
                        updateTotalAndFeeText();
                    });
                    dialog.setOnDismissListener(d -> currentDialog = null);
                    dialog.show();
                }
            });
        });

        payToListView.setOnClickListener(v -> {
            TimberLogger.i(TAG, "plusOutputButton clicked");
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            List<String> fidList = FreerApplication.getFidList();
            if(fidList.isEmpty()){
                ToastUtils.makeText(this, R.string.fid_list_is_empty);
                return;
            }

            // Calculate lockTime if lock mode is enabled
            Long lockTime = null;
            if (isLockModeEnabled && outputLockDaysInput.getText() != null) {
                String daysStr = outputLockDaysInput.getText().toString().trim();
                if (!daysStr.isEmpty()) {
                    try {
                        int days = Integer.parseInt(daysStr);
                        if (days > 0) {
                            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                            if(fapiClient == null || fapiClient.getBestHeight() == null) {
                                ToastUtils.makeText(this, getString(R.string.failed_to_get_best_height_when_making_lock_time));
                                return;
                            } else {
                                lockTime = fapiClient.getBestHeight() + daysToHeights(days);
                            }
                        }
                    } catch (NumberFormatException e) {
                        TimberLogger.e(TAG, "Invalid lock days: " + e.getMessage());
                    }
                }
            }

            AddOutputFromFidListDialog dialog = new AddOutputFromFidListDialog(this,fidList, rawTxInfo, rest, false, lockTime);
            currentDialog = dialog;
            dialog.setOnDoneListener(sendToList -> {
                for (Cash cash : sendToList) {
                    TxOutputCard card = makeCardWithSendTo(cash);

                    outputCards.add(card);
                    outputCardsContainer.addView(card);
                }
                currentDialog = null;
                updateTotalAndFeeText();
            });
            dialog.setOnDismissListener(d -> currentDialog = null);
            dialog.show();
        });

        // Add long press listener for plus output button
        payToListView.setOnLongClickListener(v -> {
            TimberLogger.i(TAG, "payToList clicked");
            AddTxOutputDialog dialog = new AddTxOutputDialog(this, rawTxInfo, rest);
            currentDialog = dialog;  // Set the current dialog
            dialog.setOnDoneListener(sendTo -> {
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(sendTo, this, true, false);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card);
                currentDialog = null;  // Clear the current dialog
                updateTotalAndFeeText();
            });
            dialog.setOnDismissListener(d -> currentDialog = null);  // Clear the current dialog when dismissed
            dialog.show();
            return true;
        });

        // Add click listener for lock time toggle icon
        lockTimeToggleView.setOnClickListener(v -> {
            TimberLogger.i(TAG, "lockTimeToggle clicked");
            toggleLockMode();
        });

        // Add click listener for import array button
        importArrayButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "importArrayButton clicked");
            Intent intent = com.fc.freer.ui.ImportActivity.createIntent(
                this,
                getString(R.string.import_send_to_array),
                getString(R.string.import_send_to_array_hint),
                false
            );
            importSendToArrayLauncher.launch(intent);
        });

        // Add output button listener
        addOutputButton.setOnClickListener(v -> {
            if(outputFidInput.getText() == null || outputAmountInput.getText() == null){
                ToastUtils.makeText(this, R.string.please_input_all_fields);
                return;
            }
            if(outputFidInput.getText().toString().isEmpty() || outputAmountInput.getText().toString().isEmpty()){
                ToastUtils.makeText(this,  R.string.please_input_all_fields);
                return;
            }
            String fid = outputFidInput.getText().toString();
            if (!KeyTools.isGoodFid(fid)) {
                ToastUtils.makeText(this, R.string.invalid_fid);
                return;
            }

            try {
                double amount = Double.parseDouble(outputAmountInput.getText().toString());

                // Validate amount range
                if (amount < Constants.MIN_AMOUNT || amount > Constants.MAX_AMOUNT) {
                    ToastUtils.makeText(this, getString(R.string.amount_must_be_between, Constants.MIN_AMOUNT, Constants.MAX_AMOUNT));
                    return;
                }

                // Calculate lockTime from days input if lock mode is enabled
                Long lockTime = null;
                if (isLockModeEnabled && outputLockDaysInput.getText() != null) {
                    String daysStr = outputLockDaysInput.getText().toString().trim();
                    if (!daysStr.isEmpty()) {
                        try {
                            int days = Integer.parseInt(daysStr);
                            if (days > 0) {
                                // Calculate lockTime (best height + days in minutes)
                                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                                if(fapiClient == null || fapiClient.getBestHeight() == null) {
                                    ToastUtils.makeText(this, getString(R.string.failed_to_get_best_height_when_making_lock_time));
                                    return;
                                } else {
                                    lockTime = fapiClient.getBestHeight() + daysToHeights(days);

                                    // Check if this is a P2SH multisig CLTV output
                                    if (fid.startsWith("3")) {
                                        // Handle multisig CLTV output asynchronously
                                        handleMultisignCltvOutputForAddButton(fid, amount, lockTime);
                                        return; // Exit early, continuation happens in async callback
                                    }
                                }
                            }
                        } catch (NumberFormatException e) {
                            ToastUtils.makeText(this, R.string.invalid_lock_days);
                            return;
                        }
                    }
                }

                Cash cash = new Cash(fid, amount, lockTime);
                rawTxInfo.getOutputs().add(cash);

                TxOutputCard card = makeCardWithSendTo(cash);


                outputCards.add(card);
                outputCardsContainer.addView(card);
//                outputHint.setVisibility(View.GONE);

                // Clear input fields
                outputFidInput.setText("");
                outputAmountInput.setText("");
                if (isLockModeEnabled) {
                    outputLockDaysInput.setText("");
                }

                // The text watchers will automatically disable the button when fields are cleared

                updateTotalAndFeeText();
            } catch (NumberFormatException e) {
                ToastUtils.makeText(this, R.string.invalid_amount);
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
                if (child != payToListView) {
                    outputCardsContainer.removeView(child);
                }
            }

            opreturnInput.setText("");
            outputFidInput.setText("");
            outputAmountInput.setText("");
            outputLockDaysInput.setText("");

            // Reset lock mode
            isLockModeEnabled = false;
            findViewById(R.id.outputLockDaysInput).setVisibility(View.GONE);

            // Reset lock icon color to accent
            lockTimeToggleView.setColorFilter(getResources().getColor(R.color.accent));

            inputCards.clear();
            outputCards.clear();

            // Reset plus button to disabled state
            addOutputButton.setEnabled(false);
            addOutputButton.setAlpha(0.3f);

            updateTotalAndFeeText();
        });

        copyButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "copyButton clicked");
            copyTxToClipboard();
        });

        importButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "importButton clicked");
            Intent intent = com.fc.freer.ui.ImportActivity.createIntent(
                this,
                getString(R.string.import_tx),
                getString(R.string.import_the_tx_json)
            );
            importTxLauncher.launch(intent);
        });

        createButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "sendButton clicked");

            updateRawTxInfo();

            if(rawTxInfo ==null){
                ToastUtils.makeText(this, R.string.no_tx_to_sign);
                return;
            }

            // Get live KeyInfo
            KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
            if (liveKeyInfo == null) {
                ToastUtils.makeText(this, R.string.no_active_fid);
                return;
            }

            // Check if inputs are empty - if so, use TxSender for auto-selection
            if (rawTxInfo.getInputs() == null || rawTxInfo.getInputs().isEmpty()) {
                handleAutoInputSelection(liveKeyInfo);
                return;
            }

            // Manual input mode - validate transaction
            if(!goodTxInfo(rawTxInfo)) return;

            signTx(liveKeyInfo.getId());
        });
    }

    @NonNull
    private TxOutputCard makeCardWithSendTo(Cash cash) {
        TxOutputCard card = new TxOutputCard(this);
        card.setSendTo(cash, this, true, false);
        card.setOnDeleteListener(this::removeOutputCard);

        Long lockTime = cash.getLockTime();
        // Show lockTime info on the card if it has lockTime
        if (lockTime != null && lockTime > 0) {
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            long bestHeight = (fapiClient != null && fapiClient.getBestHeight() != null) ? fapiClient.getBestHeight() : 0;
            if (bestHeight > 0) {
                card.setLockTime(lockTime, bestHeight);
            }
        }
        return card;
    }

    private void toggleLockMode() {
        isLockModeEnabled = !isLockModeEnabled;

        View lockDaysInputView = findViewById(R.id.outputLockDaysInput);
        if (isLockModeEnabled) {
            // Show the lock days input for outputs
            lockDaysInputView.setVisibility(View.VISIBLE);
            outputLockDaysInput.requestFocus();

            // Hide opreturn container when lock mode is enabled
            opreturnContainer.setVisibility(View.GONE);

            // Change lock icon color to warning
            lockTimeToggleView.setColorFilter(getResources().getColor(R.color.warning));

            // Show keyboard
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(outputLockDaysInput, InputMethodManager.SHOW_IMPLICIT);

            ToastUtils.makeText(this, R.string.lock_mode_enabled);
        } else {
            // Hide the lock days input
            lockDaysInputView.setVisibility(View.GONE);
            outputLockDaysInput.setText("");

            // Show opreturn container when lock mode is disabled
            opreturnContainer.setVisibility(View.VISIBLE);

            // Change lock icon color back to accent
            lockTimeToggleView.setColorFilter(getResources().getColor(R.color.accent));

            ToastUtils.makeText(this, R.string.lock_mode_disabled);
        }

        // Revalidate fields when lock mode is toggled
        validateOutputFields();
        updateTotalAndFeeText();
    }

    private void copyTxToClipboard() {
        TimberLogger.i(TAG, "copyButton clicked");
        // Hide keyboard
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
        }

        updateRawTxInfo();

        if(rawTxInfo ==null){
            ToastUtils.makeText(this, R.string.no_tx_to_copy);
            return;
        }
        RawTxInfo copied = new RawTxInfo().copy(rawTxInfo);
        copied.setSenderInfo(null);
        String json = copied.toNiceJson();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Transaction JSON", json);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(this, R.string.tx_json_copied_to_clipboard);
    }

    private void setupPeopleAndScanIcons(int layoutId, int iconsViewId, int qrRequestCode) {
        View layoutView = findViewById(layoutId);
        IoIconsView iconsView = layoutView.findViewById(iconsViewId);

        // Configure to show only people and scan icons
        iconsView.init(this, false, true, true, true,false);

        // Set up people icon click listener
        iconsView.setOnPeopleClickListener(isSingleChoice -> {
            TimberLogger.i(TAG, "People icon clicked for outputFidInput");
            Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
            intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
            chooseContactLauncher.launch(intent);
        });

        // Set up scan icon click listener
        iconsView.setOnScanClickListener(() -> {
            TimberLogger.i(TAG, "Scan icon clicked for outputFidInput");
            startQrScan(qrRequestCode);
        });
    }

    private void handleContactSelectionResult(Intent data) {
        List<String> selectedFids = data.getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
        if (selectedFids != null) {
            try {
                if (!selectedFids.isEmpty()) {
                    if (selectedFids.size() == 1) {
                        // Single FID selected - put it in the input field
                        String fid = selectedFids.get(0);
                        if (fid != null && !fid.isEmpty()) {
                            outputFidInput.setText(fid);
                        } else {
                            ToastUtils.makeText(this, R.string.contact_has_no_fid);
                        }
                    } else {
                        // Multiple FIDs selected - create batch outputs using AddOutputFromFidListDialog
                        List<String> fidList = new ArrayList<>();
                        for (String fid : selectedFids) {
                            if (fid != null && !fid.isEmpty()) {
                                fidList.add(fid);
                            }
                        }

                        if (!fidList.isEmpty()) {
                            // Calculate lockTime if lock mode is enabled
                            Long lockTime = null;
                            if (isLockModeEnabled && outputLockDaysInput.getText() != null) {
                                String daysStr = outputLockDaysInput.getText().toString().trim();
                                if (!daysStr.isEmpty()) {
                                    try {
                                        int days = Integer.parseInt(daysStr);
                                        if (days > 0) {
                                            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                                            if(fapiClient == null || fapiClient.getBestHeight() == null) {
                                                ToastUtils.makeText(this, getString(R.string.failed_to_get_best_height_when_making_lock_time));
                                                return;
                                            } else {
                                                long heights = daysToHeights(days);
                                                lockTime = fapiClient.getBestHeight() + heights;
                                            }
                                        }
                                    } catch (NumberFormatException e) {
                                        TimberLogger.e(TAG, "Invalid lock days: " + e.getMessage());
                                    }
                                }
                            }

                            AddOutputFromFidListDialog dialog = new AddOutputFromFidListDialog(this, fidList, rawTxInfo, rest, false, lockTime);
                            currentDialog = dialog;
                            dialog.setOnDoneListener(sendToList -> {
                                for (Cash cash : sendToList) {
                                    TxOutputCard card = makeCardWithSendTo(cash);

                                    outputCards.add(card);
                                    outputCardsContainer.addView(card);
                                }
//                                outputHint.setVisibility(View.GONE);
                                currentDialog = null;
                                updateTotalAndFeeText();
                            });
                            dialog.setOnDismissListener(d -> currentDialog = null);
                            dialog.show();
                            ToastUtils.makeText(this, getString(R.string.toast_creating_outputs, fidList.size()));
                        } else {
                            ToastUtils.makeText(this, R.string.contact_has_no_fid);
                        }
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error parsing selected contacts: " + e.getMessage());
                ToastUtils.makeText(this, getString(R.string.failed_error_msg,e.getMessage()));
            }
        }
    }

    public static long daysToHeights(int days) {
        return days;//TODO (days * 24L * 60L) + 1;
    }

    private void updateRawTxInfo() {
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
        String feeText = feeCalculationFailed ? "-" : df.format(FchUtils.satoshiToCash(fee));
        String outputText = String.format(Locale.US, "%s %s F    %s %s c",
            getString(R.string.paying),
            df.format(FchUtils.satoshiToCoin(totalOutput)),
            getString(R.string.fee),
            feeText);
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

        // Update Rest text for output input
        DecimalFormat df2 = new DecimalFormat("0.########");
        df2.setDecimalSeparatorAlwaysShown(false);
        String restValueText = feeCalculationFailed ? "-" : df2.format(FchUtils.satoshiToCoin(rest));
        String restTextStr = String.format(Locale.US, getString(R.string.rest_s_f), restValueText);
        outputRestText.setText(restTextStr);

        // Set color based on rest value - red if negative and not -34
//        if (feeCalculationFailed) {
//            outputRestText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
//        } else if (rest < 0 && rest != -34) {
//            outputRestText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
//        } else {
//            outputRestText.setTextColor(getResources().getColor(R.color.field_name));
//        }

        outputRestText.setOnClickListener(v -> {
            // Don't allow filling rest amount if fee calculation failed
            if (feeCalculationFailed) {
                ToastUtils.makeText(this, getString(R.string.fee_calculation_failed));
                return;
            }

            if (rest > 0) {
                long adjustedRest = rest;

                // When adding an output, we need to account for the cost of that output itself
                // Calculate the actual fee increase by simulating the addition of the output
                //FID and lock days are required for lock mode. They effect the size of OpReturn.
                Editable inputtedText = outputFidInput.getText();
                if(inputtedText==null || !KeyTools.isGoodFid(inputtedText.toString().trim())){
                    ToastUtils.makeText(this, getString(R.string.enter_a_valid_fid));
                    return;
                }
                String payee = inputtedText.toString().trim();
                String liveFid = FidManager.getInstance().getLiveFid();
                if (isLockModeEnabled && outputLockDaysInput.getText() != null && !outputLockDaysInput.getText().toString().trim().isEmpty()) {
                    String daysStr = outputLockDaysInput.getText().toString().trim();
                    if (!daysStr.isEmpty() && !payee.isEmpty()) {
                        try {
                            int days = Integer.parseInt(daysStr);
                            if (days > 0) {
                                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                                if (fapiClient != null && fapiClient.getBestHeight() != null) {
                                    long lockTime = fapiClient.getBestHeight() + daysToHeights(days);

                                    // Check if this is a P2SH multisig address
                                    if (payee.startsWith("3")) {
                                        // Need to fetch multisig info for accurate fee calculation
                                        fetchMultisignInfoForFeeCalculation(payee, lockTime);
                                        return; // Exit early, will continue in callback
                                    }

                                    // Calculate the adjusted rest for non-multisig CLTV output
                                    adjustedRest = calculateAdjustedRestForOutput(payee, lockTime, null, adjustedRest);
                                }
                            }
                        } catch (Exception e) {
                            // If calculation fails, use basic adjustment
                            adjustedRest = adjustChangeFeeForRest(liveFid, payee, adjustedRest);
                        }
                    } else {
                        ToastUtils.makeText(this, getString(R.string.enter_a_valid_lock_days));
                        return;
                    }
                } else {
                    adjustedRest = adjustChangeFeeForRest(liveFid, payee, adjustedRest);
                }
                outputAmountInput.setText(df2.format(FchUtils.satoshiToCoin(adjustedRest)));
            }
        });
    }

    //The original change fee is for the sender. The rest is for the receiver.
    private long adjustChangeFeeForRest(String payer, String payee, long adjustedRest) {
        if(payer.equals(payee))return adjustedRest;
        long changeToPayer = TxHandler.getChangeFee(payer);
        long changeToPayee = TxHandler.getChangeFee(payee);

        adjustedRest = adjustedRest + changeToPayer - changeToPayee;

        return adjustedRest;
    }

    private boolean goodTxInfo(RawTxInfo rawTxInfo) {
        // Check inputs exist
        if(rawTxInfo.getInputs()==null || rawTxInfo.getInputs().isEmpty()){
            ToastUtils.makeText(this, R.string.inputs_cannot_be_empty);
            return false;
        }

        // Validate each input
        List<Cash> inputs = rawTxInfo.getInputs();
        for(Cash cash : inputs){
            if(cash.getValue()<=0){
                ToastUtils.makeText(this, R.string.input_value_must_be_greater_than_0);
                return false;
            }
            if(cash.getBirthTxId() == null || !Hex.isHex32(cash.getBirthTxId())){
                ToastUtils.makeText(this, R.string.invalid_txid);
                return false;
            }
            if(cash.getBirthIndex()<0){
                ToastUtils.makeText(this, R.string.invalid_index);
                return false;
            }

            if(cash.getLockTime()!=null){

                //Add redeem script for P2SH_CLTV
                if(cash.getRedeemScript()==null && cash.getOwner().startsWith("F")){
                    cash.setRedeemScript(new P2SH(cash.getOwner(),cash.getLockTime()).getRedeemScript());
                }

                //Check if lock time is unlocked
                FapiClient fapiClient = CashManager.getInstance().loadFapiClient();
                Long bestHeight = fapiClient.getBestHeight();
                if(bestHeight != null){
                    if(!TxHandler.isLockTimeUnlocked(cash.getLockTime(), bestHeight)) {
                        ToastUtils.makeText(this, getString(R.string.there_is_unlocked_cash));
                        return false;
                    }
                }
            }
        }

        // Validate outputs if they exist
        if(rawTxInfo.getOutputs() != null) {
            for(Cash output : rawTxInfo.getOutputs()){
                if(output.getOwner() == null || output.getOwner().isEmpty()){
                    ToastUtils.makeText(this, R.string.invalid_fid);
                    return false;
                }
                if(output.getAmount() <= 0){
                    ToastUtils.makeText(this, R.string.invalid_amount);
                    return false;
                }
            }
        }

        // Validate the final transaction
        updateTotalsAndFee();

        long rest = totalInput - totalOutput - fee;
        if(rest < 0){
            // If rest is exactly -CHANGE_OUTPUT_FEE, the TX is valid without change output
            // Otherwise, inputs are insufficient
            boolean isNotChange = (rawTxInfo.getSender().startsWith("3") && rest != -CHANGE_P2SH_OUTPUT_FEE) || rest != -CHANGE_OUTPUT_FEE;
            if(isNotChange) {
                ToastUtils.makeText(this, R.string.inputs_are_not_enough_to_cover_the_fee);
                return false;
            }
        }

        updateTotalAndFeeText();
        return true;
    }

    /**
     * Update totals and fee calculations
     */
    private void updateTotalsAndFee() {
        totalInput = 0;
        totalOutput = 0;
        fee = 0;
        rest = 0;
        totalCd = 0;
        feeCalculationFailed = false;

        if(rawTxInfo ==null){
            rawTxInfo = new RawTxInfo();
        }
        if(rawTxInfo.getInputs()==null) rawTxInfo.setInputs(new ArrayList<>());
        if(rawTxInfo.getOutputs()==null) rawTxInfo.setOutputs(new ArrayList<>());

        CashManager.refreshCd(rawTxInfo.getInputs());
        for(Cash cash : rawTxInfo.getInputs()){
            totalInput += cash.getValue();
            // Add cd from each cash
            if (cash.getCd() != null) {
                totalCd += cash.getCd();
            }
        }

        for(Cash cash : rawTxInfo.getOutputs()){
            totalOutput += cash.getValue();
        }

        // Only calculate fee if there are inputs (outputs can be empty)
        if (!rawTxInfo.getInputs().isEmpty()) {
            // When in lock mode, CLTV outputs generate P2SH opReturn data
            // We need to sync this to rawTxInfo.opReturn for accurate fee calculation
            if (isLockModeEnabled) {
                rawTxInfo.setOpReturn(null); // Clear user opReturn in lock mode
            } else {
                // Update rawTxInfo.opReturn from the input field to ensure fee calculation includes it
                String opreturnText = opreturnInput.getText() != null ? opreturnInput.getText().toString() : null;
                if (opreturnText != null && !opreturnText.isEmpty()) {
                    rawTxInfo.setOpReturn(opreturnText);
                } else {
                    rawTxInfo.setOpReturn(null);
                }
            }

            TxHandler.FeeResult feeResult = TxHandler.calcFee(rawTxInfo);

            TimberLogger.i(TAG, "Real fee calculation - Fee: " + feeResult.fee() +
                ", OpReturn length: " + (feeResult.finalOpReturnBytes() != null ? feeResult.finalOpReturnBytes().length : 0) +
                ", Outputs count: " + (rawTxInfo.getOutputs() != null ? rawTxInfo.getOutputs().size() : 0));

            // Check if fee calculation failed
            if (feeResult.fee() == null) {
                feeCalculationFailed = true;
                fee = 0;
                rest = 0;
                TimberLogger.e(TAG, "Fee calculation failed - FeeResult.fee is null");
            } else {
                fee = feeResult.fee();

                // Update rawTxInfo.opReturn with CLTV-generated opReturn if present
                // This ensures the opReturn is included in subsequent calculations
                if (feeResult.finalOpReturnBytes() != null && feeResult.finalOpReturnBytes().length > 0) {
                    rawTxInfo.setOpReturn(new String(feeResult.finalOpReturnBytes()));
                }

                rest = totalInput - totalOutput - fee;
                long changeFee = TxHandler.getChangeFee(rawTxInfo.getSender());
                if(rest == -changeFee)rest = 0;
            }
        }
    }

    private void removeInputCard(View cardView, Cash cash) {
        inputCards.remove(cardView);
        inputCardsContainer.removeView(cardView);
        rawTxInfo.getInputs().remove(cash);
        updateTotalAndFeeText();
    }

    private void removeOutputCard(TxOutputCard card) {
        outputCards.remove(card);
        outputCardsContainer.removeView(card);
        rawTxInfo.getOutputs().remove(card.getSendTo());
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

        // Keep the imported opReturn: clearing opreturnInput below fires its
        // TextWatcher, which syncs the emptied field back into rawTxInfo.
        String importedOpReturn = rawTxInfo.getOpReturn();

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
            if (child != payToListView) {
                outputCardsContainer.removeView(child);
            }
        }
        outputCards.clear();

        // Clear text inputs
        opreturnInput.setText("");

        // Add input cards using unified layout
        if (rawTxInfo.getInputs() != null && !rawTxInfo.getInputs().isEmpty()) {
            for (Cash cash : rawTxInfo.getInputs()) {
                View cardView = createInputCardView(cash);
                inputCards.add(cardView);
                inputCardsContainer.addView(cardView, inputCardsContainer.getChildCount() - 1);
            }
        }

        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        Long bestHeight = null;
        if(fapiClient!=null && fapiClient.getBestHeight()!=null)
            bestHeight = fapiClient.getBestHeight();

        // Add output cards
        if (rawTxInfo.getOutputs() != null) {
            for (Cash cash : rawTxInfo.getOutputs()) {
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(cash, this, true, false);
                card.setOnDeleteListener(this::removeOutputCard);
                if(cash.getLockTime()!=null && cash.getLockTime()>0 && bestHeight!=null)
                    card.setLockTime(cash.getLockTime(), bestHeight);
                outputCards.add(card);
                outputCardsContainer.addView(card);
            }
        }

        // Set opreturn text. Restore the model too: the TextWatcher nulled it
        // while the field was cleared above.
        rawTxInfo.setOpReturn(importedOpReturn);
        if (importedOpReturn != null) {
            opreturnInput.setText(importedOpReturn);
        }

        updateTotalAndFeeText();
    }

    /**
     * Handle imported Cash array from ImportSendToArrayActivity
     */
    private void handleImportedSendToArray(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) {
            TimberLogger.e(TAG, "Imported Cash list is empty");
            return;
        }

        TimberLogger.i(TAG, "Handling imported Cash array with " + cashList.size() + " items");

        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        Long bestHeight = null;
        if (fapiClient != null && fapiClient.getBestHeight() != null) {
            bestHeight = fapiClient.getBestHeight();
        }

        // Calculate lockTime if lock mode is enabled and user has entered lock days
        Long lockTimeToApply = null;
        if (isLockModeEnabled && outputLockDaysInput.getText() != null) {
            String daysStr = outputLockDaysInput.getText().toString().trim();
            if (!daysStr.isEmpty()) {
                try {
                    int days = Integer.parseInt(daysStr);
                    if (days > 0 && fapiClient != null && fapiClient.getBestHeight() != null) {
                        lockTimeToApply = fapiClient.getBestHeight() + daysToHeights(days);
                        TimberLogger.i(TAG, "Applying lockTime " + lockTimeToApply + " to imported Cash array (lock mode enabled)");
                    }
                } catch (NumberFormatException e) {
                    TimberLogger.e(TAG, "Invalid lock days: " + e.getMessage());
                }
            }
        }

        // Add each Cash to outputs
        for (Cash cash : cashList) {
            // Apply lockTime from lock mode to outputs that don't already have lockTime
            // If lock mode is enabled and cash doesn't have lockTime, apply the calculated lockTime
            if (lockTimeToApply != null && (cash.getLockTime() == null || cash.getLockTime() == 0)) {
                cash.setLockTime(lockTimeToApply);
                TimberLogger.i(TAG, "Applied lockTime " + lockTimeToApply + " to imported Cash for " + cash.getOwner());
            }

            // Add to rawTxInfo outputs
            rawTxInfo.getOutputs().add(cash);

            // Create output card
            TxOutputCard card = new TxOutputCard(this);
            card.setSendTo(cash, this, true, false);
            card.setOnDeleteListener(this::removeOutputCard);

            // Set lock time if present
            if (cash.getLockTime() != null && cash.getLockTime() > 0 && bestHeight != null) {
                card.setLockTime(cash.getLockTime(), bestHeight);
            }

            outputCards.add(card);
            outputCardsContainer.addView(card);
        }

        updateTotalAndFeeText();
        ToastUtils.makeText(this, getString(R.string.imported_send_to_count, cashList.size()));
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_tx;
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


    /**
     * Handle automatic input selection when inputs are empty.
     * Uses TxSender to automatically select appropriate cash inputs.
     */
    private void handleAutoInputSelection(KeyInfo liveKeyInfo) {
        String sender = liveKeyInfo.getId();

        // Get outputs from rawTxInfo
        List<Cash> outputs = null;
        if (rawTxInfo != null && rawTxInfo.getOutputs() != null && !rawTxInfo.getOutputs().isEmpty()) {
            outputs = rawTxInfo.getOutputs();
        }

        // Get opReturn
        String opreturnText = opreturnInput.getText() != null ? opreturnInput.getText().toString().trim() : null;
        if (opreturnText != null && opreturnText.isEmpty()) {
            opreturnText = null;
        }

        // Get required instances
        CashManager cashManager = CashManager.getInstance();
        TxHandler txHandler = new TxHandler();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

        // Create TxSender and send transaction with confirmation UI
        TxSender txSender = new TxSender();

        txSender.sendTx(
            this,
            sender,
            outputs,
            opreturnText,  // opReturn
            null,          // prikey will be fetched in SendTxActivity
            0L,            // cd
                // bestHeight
            TxHandler.DEFAULT_FEE_RATE,
            null,          // multisig
            RawTxInfo.VERSION_2,
                null, cashManager,
            txHandler,
            fapiClient,
            new TxSender.TxCallback() {
                @Override
                public void onSuccess(String txId) {
                    runOnUiThread(() -> {
                        // tx_sent toast is shown by TxSender on broadcast success; don't duplicate it here.
                        finish();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(CreateTxActivity.this, getString(R.string.failed_to_send_tx, errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(CreateTxActivity.this, R.string.no_prikey_available);
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(CreateTxActivity.this, R.string.tx_signed_but_not_broadcast);
                    });
                }
            },
            true  // withConfirm = true to show SendTxActivity
        );
    }

    /**
     * Get multisig info from local sources (keyInfoMap and contactManager)
     * @param fid The FID to look up
     * @return Multisig info if found, null otherwise
     */
    private Multisig getLocalMultisignInfo(String fid) {
        if (fid == null || fid.isEmpty() || !fid.startsWith("3")) {
            return null;
        }

        // First check keyInfoMap in current setting
        com.fc.freer.model.Setting currentSetting = SettingManager.getInstance().getCurrentSetting();
        if (currentSetting != null) {
            Map<String, KeyInfo> keyInfoMap = currentSetting.getKeyInfoMap();
            if (keyInfoMap != null && keyInfoMap.containsKey(fid)) {
                KeyInfo keyInfo = keyInfoMap.get(fid);
                if (keyInfo != null && keyInfo.getMultisign() != null) {
                    TimberLogger.i(TAG, "Found multisig info in keyInfoMap for FID: " + fid);
                    return keyInfo.getMultisign();
                }
            }
        }

        // Then check contactManager
        ContactManager contactManager = ContactManager.getInstance();
        if (contactManager != null) {
            Contact contact = contactManager.getContactByFid(fid);
            if (contact != null && contact.getMultisign() != null) {
                TimberLogger.i(TAG, "Found multisig info in contacts for FID: " + fid);
                return contact.getMultisign();
            }
        }

        TimberLogger.i(TAG, "No local multisig info found for FID: " + fid);
        return null;
    }

    /**
     * Handle multisig CLTV output when adding from the add button
     * This checks if the P2SH address is multisig and fetches the info if needed
     */
    private void handleMultisignCltvOutputForAddButton(String fid, double amount, Long lockTime) {

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        String liveFid = liveKeyInfo.getId();

        // Check if this is paying to the live FID (self)
        if (fid.equals(liveFid) && liveKeyInfo.getMultisign() != null) {
            // Use sender's multisig info
            addOutputWithMultisign(fid, amount, lockTime,
                    liveKeyInfo.getMultisign());
            return;
        }

        // Check cache first
        if (cachedMultisignMap.containsKey(fid)) {
            Multisig cachedMultisig = cachedMultisignMap.get(fid);
            if (cachedMultisig != null) {
                TimberLogger.i(TAG, "Using cached multisig info for FID: " + fid);
                addOutputWithMultisign(fid, amount, lockTime,
                        cachedMultisig);
                return;
            }
        }

        // Try to get multisig info from local sources
        Multisig localMultisig = getLocalMultisignInfo(fid);
        if (localMultisig != null) {
            // Found in local keyInfoMap or contactManager
            // Cache it for future use
            cachedMultisignMap.put(fid, localMultisig);
            addOutputWithMultisign(fid, amount, lockTime,
                    localMultisig);
            return;
        }

        // Need to fetch from APIP
        fetchSingleMultisignInfoAsync(fid, amount, lockTime);
    }

    /**
     * Fetch multisig info for P2SH address to calculate accurate fee
     * This is called when "Rest..." is clicked and the FID is a P2SH multisig address
     */
    private void fetchMultisignInfoForFeeCalculation(String fid, Long lockTime) {
        // Check if we already have this multisig info cached
        if (cachedMultisignMap.containsKey(fid)) {
            Multisig cachedMultisig = cachedMultisignMap.get(fid);
            if (cachedMultisig != null) {
                TimberLogger.i(TAG, "Using cached multisig info for fee calculation: " + fid);
                calculateAndFillRestAmount(fid, lockTime, cachedMultisig);
                return;
            }
        }

        // Check if this is paying to the live FID (self)
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo != null && fid.equals(liveKeyInfo.getId()) && liveKeyInfo.getMultisign() != null) {
            cachedMultisignMap.put(fid, liveKeyInfo.getMultisign());
            calculateAndFillRestAmount(fid, lockTime, liveKeyInfo.getMultisign());
            return;
        }

        // Try to get multisig info from local sources
        Multisig localMultisig = getLocalMultisignInfo(fid);
        if (localMultisig != null) {
            cachedMultisignMap.put(fid, localMultisig);
            calculateAndFillRestAmount(fid, lockTime, localMultisig);
            return;
        }

        // Need to fetch from APIP
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.failed_to_get_apip_client));
            return;
        }

        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.fetching_multisign_info));
        waitingDialog.show();

        new Thread(() -> {
            try {
                List<String> fidList = new ArrayList<>();
                fidList.add(fid);

                // Fetch multisig info from APIP
                Map<String, Multisig> multisignMap = fapiClient.multisigByIds(
                    fidList
                );

                if (multisignMap == null || !multisignMap.containsKey(fid)) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_get_multisign_info_for_fid) + ": " + fid);
                    });
                    return;
                }

                Multisig multisig = multisignMap.get(fid);
                if (multisig != null) {
                    // Cache the fetched multisig info for future use
                    cachedMultisignMap.put(fid, multisig);
                    TimberLogger.i(TAG, "Cached multisig info for FID: " + fid);

                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        calculateAndFillRestAmount(fid, lockTime, multisig);
                    });
                } else {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_get_multisign_info_for_fid) + ": " + fid);
                    });
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching multisig info: " + e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.failed_error_msg, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Calculate adjusted rest amount and fill it into the output amount input
     * This uses the fetched multisig info to create a temporary output for accurate fee calculation
     */
    private void calculateAndFillRestAmount(String fid, Long lockTime, Multisig multisig) {
        long adjustedRest = calculateAdjustedRestForOutput(fid, lockTime, multisig, rest);

        if (adjustedRest > 0) {
            DecimalFormat df = new DecimalFormat("0.########");
            df.setDecimalSeparatorAlwaysShown(false);
            outputAmountInput.setText(df.format(FchUtils.satoshiToCoin(adjustedRest)));
        }
    }

    /**
     * Calculate the adjusted rest amount by simulating the addition of an output
     * This calculates the actual fee increase from adding the output
     *
     * @param fid The FID of the output
     * @param lockTime The lockTime for CLTV output (null for non-CLTV)
     * @param multisig The multisig info for P2SH multisig addresses (null for non-multisig)
     * @param currentRest The current rest amount before adjustment
     * @return The adjusted rest amount after accounting for the output cost
     */
    private long calculateAdjustedRestForOutput(String fid, Long lockTime, Multisig multisig, long currentRest) {
        long adjustedRest = currentRest;

        TimberLogger.i(TAG, "calculateAdjustedRestForOutput - Input rest: " + currentRest +
            ", totalInput: " + totalInput + ", totalOutput: " + totalOutput +
            ", current fee: " + fee + ", sender: " + rawTxInfo.getSender());

        String liveFid = FidManager.getInstance().getLiveFid();
        try {
            // Calculate current fee
            long currentFee = fee;

            // Create a temporary RawTxInfo with the new output to calculate what the fee would be
            RawTxInfo tempRawTxInfo = new RawTxInfo();
            tempRawTxInfo.setSenderInfo(rawTxInfo.getSenderInfo());
            tempRawTxInfo.setSender(rawTxInfo.getSender());  // Important: needed for change output size calculation
            tempRawTxInfo.setInputs(rawTxInfo.getInputs());
            tempRawTxInfo.setOutputs(new ArrayList<>(rawTxInfo.getOutputs() != null ? rawTxInfo.getOutputs() : new ArrayList<>()));
            tempRawTxInfo.setFeeRate(rawTxInfo.getFeeRate());
            tempRawTxInfo.setOpReturn(rawTxInfo.getOpReturn());

            // Create a temporary output with dummy amount (size is what matters)
            Cash tempOutput;
            if (multisig != null) {
                // P2SH multisig CLTV output
                if(!fid.equals(multisig.getId())){
                    throw new RuntimeException("FID "+fid+" is not the same of the Multisig: " + multisig.getId());
                }
                tempOutput = new Cash();
                tempOutput.setAmount(0.00001);

                tempOutput.addMultisigCltvInfo(fid, lockTime, multisig);
            } else {
                // Regular output (possibly CLTV)
                tempOutput = new Cash(fid, 0.00001, lockTime);
            }
            tempRawTxInfo.getOutputs().add(tempOutput);

            // Calculate fee with the new output
            TxHandler.FeeResult feeResult = TxHandler.calcFee(tempRawTxInfo);

            TimberLogger.i(TAG, "Temp fee calculation - OpReturn length: " +
                (feeResult.finalOpReturnBytes() != null ? feeResult.finalOpReturnBytes().length : 0));

            if (feeResult.fee() != null) {
                long newFee = feeResult.fee();

                // The difference is the cost of adding this output
                long outputCost = newFee - currentFee;

                // We need to account for the change output fee that will be saved
                // The change fee depends on the sender's address type (not the output's address type)
                String senderFid = rawTxInfo.getSender();
                long changeFee = TxHandler.getChangeFee(senderFid);

                // When we spend all the rest, we won't have a change output
                // So we save the changeFee, but we need to pay for the new output
                // The actual cost to us is: outputCost - changeFee
                adjustedRest -= (outputCost - changeFee);

                TimberLogger.i(TAG, "Fee calculation for output - Sender: " + senderFid +
                    ", Current fee: " + currentFee +
                    ", New fee: " + newFee + ", Output cost: " + outputCost +
                    ", Change fee: " + changeFee + ", Adjusted rest: " + adjustedRest);
            } else {
                TimberLogger.e(TAG, "Fee calculation failed - using basic adjustment");
                // If fee calculation fails, use basic adjustment
                adjustedRest = adjustChangeFeeForRest(liveFid, fid, adjustedRest);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error calculating fee: " + e.getMessage());
            // If calculation fails, use basic adjustment
            adjustedRest = adjustChangeFeeForRest(liveFid, fid, adjustedRest);
        }

        return adjustedRest;
    }

    /**
     * Fetch multisig info for a single FID and add the output
     */
    private void fetchSingleMultisignInfoAsync(String fid, double amount, Long lockTime) {
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.failed_to_get_apip_client));
            return;
        }

        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.fetching_multisign_info));
        waitingDialog.show();

        new Thread(() -> {
            try {
                List<String> fidList = new ArrayList<>();
                fidList.add(fid);

                // Fetch multisig info from APIP
                Map<String, Multisig> multisignMap = fapiClient.multisigByIds(
                        fidList
                );

                if (multisignMap == null || !multisignMap.containsKey(fid)) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_get_multisign_info_for_fid) + ": " + fid);
                    });
                    return;
                }

                Multisig multisig = multisignMap.get(fid);
                if (multisig != null) {
                    // Cache the fetched multisig info for future use
                    cachedMultisignMap.put(fid, multisig);
                    TimberLogger.i(TAG, "Cached multisig info for FID: " + fid);

                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        addOutputWithMultisign(fid, amount, lockTime,
                                multisig);
                    });
                } else {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_get_multisign_info_for_fid) + ": " + fid);
                    });
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching multisig info: " + e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.failed_error_msg, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Add output with multisig information (for multisig CLTV)
     */
    private void addOutputWithMultisign(String fid, double amount, Long lockTime, Multisig multisig) {
        Cash cash = new Cash(fid,amount);
        cash.addMultisigCltvInfo(fid,lockTime,multisig);
        if(cash.getOwner()==null)return;

        rawTxInfo.getOutputs().add(cash);

        TxOutputCard card = makeCardWithSendTo(cash);

        outputCards.add(card);
        outputCardsContainer.addView(card);

        // Clear input fields
        outputFidInput.setText("");
        outputAmountInput.setText("");
        if (isLockModeEnabled) {
            outputLockDaysInput.setText("");
        }

        updateTotalAndFeeText();
    }

    /**
     * Proceed with transaction creation after all multisig info is ready
     */
    private void signTx(String liveFid) {
        // Convert RawTxInfo to JSON
        String offLineTxInfoJson = rawTxInfo.toJsonWithSenderInfo();

        // Check if live FID is multisig and launch appropriate activity
        Intent intent;
        if (liveFid != null && liveFid.startsWith("3")) {
            // For multisig transactions, use SignMultisigTxActivity
            intent = new Intent(this, SignMultisigTxActivity.class);
            intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, offLineTxInfoJson);
        } else {
            // For regular transactions, use SendTxActivity
            intent = new Intent(this, SendTxActivity.class);
            intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, offLineTxInfoJson);
        }

        signTxLauncher.launch(intent);
    }

    /**
     * Dismiss waiting dialog if showing
     */
    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
            waitingDialog = null;
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clear any pending operations
        dismissWaitingDialog();

        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
            currentDialog = null;
        }

        // Clear references
        inputCards.clear();
        outputCards.clear();
        cachedMultisignMap.clear();
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

    /**
     * Creates an input card view for a given Cash object.
     * Uses the unified layout that adapts based on whether the cash has owner information.
     */
    private View createInputCardView(Cash cash) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_cash_card, inputCardsContainer, false);

        TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
        TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
        ImageButton deleteButton = cardView.findViewById(R.id.deleteButton);
        View txIdIndexRow = cardView.findViewById(R.id.txIdIndexRow);
        View idBriefLastTimeRow = cardView.findViewById(R.id.idBriefLastTimeRow);
        TextView txIdText = cardView.findViewById(R.id.txIdText);
        TextView indexText = cardView.findViewById(R.id.indexText);
        TextView idBrief = cardView.findViewById(R.id.cash_id_brief);
        TextView lastTime = cardView.findViewById(R.id.cash_last_time);

        deleteButton.setVisibility(VISIBLE);

        // Determine which row to show based on whether cash has owner information
        boolean hasOwner = cash.getOwner() != null && !cash.getOwner().isEmpty();

        if (hasOwner) {
            // Show ID Brief and Last Time row
            txIdIndexRow.setVisibility(View.GONE);
            idBriefLastTimeRow.setVisibility(VISIBLE);

            // Set ID brief
            String cashId = cash.getId();
            if (cashId != null && !cashId.isEmpty()) {
                idBrief.setText(cashId);
            } else {
                idBrief.setText("");
            }

            // Set last time
            Long lastTimeValue = cash.getLastTime();
            if (lastTimeValue != null) {
                String formattedTime = com.fc.fc_ajdk.utils.DateUtils.longToTime(lastTimeValue * 1000, com.fc.fc_ajdk.utils.DateUtils.TO_MINUTE);
                lastTime.setText(formattedTime);
            } else {
                lastTime.setText("");
            }
        } else {
            // Show TxId and Index row
            txIdIndexRow.setVisibility(VISIBLE);
            idBriefLastTimeRow.setVisibility(View.GONE);

            // Set TxId
            if (cash.getBirthTxId() != null) {
                txIdText.setText(cash.getBirthTxId());
            }

            // Set Index
            indexText.setText(String.valueOf(cash.getBirthIndex()));
        }

        // Set amount value
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(cash.getValue())) + " F";
        amountValue.setText(amountText);

        // Set CD value
        // The server's cd stops at the last time it indexed this cash.
        CashManager.refreshCd(cash);
        Long cd = cash.getCd();
        if (cd != null) {
            cdValue.setText(String.format(Locale.US, "%d cd", cd));
        } else {
            cdValue.setText("0 cd");
        }

        // Set lock time if present
        if (cash.getLockTime() != null && cash.getLockTime() > 0) {
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            long bestHeight = (fapiClient != null && fapiClient.getBestHeight() != null) ? fapiClient.getBestHeight() : 0;
            if (bestHeight > 0) {
                setLockTimeForCashCard(cardView, cash.getLockTime(), bestHeight);
            }
        }

        // Set click listeners to show detail activity
        View.OnClickListener showDetailListener = v -> showCashDetailActivity(cash);
        amountValue.setOnClickListener(showDetailListener);
        cdValue.setOnClickListener(showDetailListener);
        if (hasOwner) {
            idBrief.setOnClickListener(showDetailListener);
            lastTime.setOnClickListener(showDetailListener);
        } else {
            txIdText.setOnClickListener(v -> {
                copyToClipboard(cash.getBirthTxId(), getString(R.string.txid));
            });
        }
        cardView.setOnClickListener(showDetailListener);

        // Add delete button functionality
        deleteButton.setOnClickListener(v -> removeInputCard(cardView, cash));

        return cardView;
    }

    /**
     * Sets locktime information for cash card inputs
     * @param cardView The cash card view
     * @param lockTime The block height at which the input becomes spendable
     * @param bestHeight The current best block height
     */
    private void setLockTimeForCashCard(View cardView, Long lockTime, long bestHeight) {
        if (lockTime == null || lockTime <= 0) {
            cardView.findViewById(R.id.lockTimeContainer).setVisibility(View.GONE);
            return;
        }

        LinearLayout lockTimeContainer = cardView.findViewById(R.id.lockTimeContainer);
        android.widget.ImageView lockTimeIcon = cardView.findViewById(R.id.lockTimeIcon);
        TextView lockTimeDaysText = cardView.findViewById(R.id.lockTimeDaysText);

        lockTimeContainer.setVisibility(VISIBLE);

        // Calculate blocks and days until unlock
        long blocksToLock = lockTime - bestHeight;
        boolean isUnlocked = blocksToLock <= 0;
        if(blocksToLock < 0) blocksToLock = 0;
        long daysToLock = (long) ((blocksToLock+1) / (24.0 * 60)); // 1 block ≈ 1 minute

        // Set the days text (primary info)
        String daysInfo = String.format(java.util.Locale.US, "%d%s(%d%s@%d)",
            daysToLock,
            getString(R.string.days),
            blocksToLock,
            getString(R.string.blocks), lockTime);
        lockTimeDaysText.setText(daysInfo);
        lockTimeIcon.setVisibility(VISIBLE);

        // Set green color and unlock icon for unlocked inputs
        if (isUnlocked) {
            lockTimeIcon.setImageResource(R.drawable.ic_unlock);
            lockTimeIcon.setColorFilter(getColor(android.R.color.holo_green_dark));
            lockTimeDaysText.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            lockTimeIcon.setImageResource(R.drawable.ic_lock);
            lockTimeIcon.clearColorFilter();
            lockTimeDaysText.setTextColor(getColor(R.color.warning));
        }

        // Make the locktime container clickable to copy locktime value
        lockTimeContainer.setClickable(true);
        lockTimeContainer.setFocusable(true);
        lockTimeContainer.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("lockTime", String.valueOf(lockTime));
            clipboard.setPrimaryClip(clip);
        });
    }

    private void addCashCards(List<Cash> cashList) {
        for (Cash cash : cashList) {
            View cardView = createInputCardView(cash);
            inputCards.add(cardView);
            inputCardsContainer.addView(cardView, inputCardsContainer.getChildCount() - 1);
        }
    }

    public void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(this, R.string.copied);
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
                        View cardView = createInputCardView(cash);
                        inputCards.add(cardView);
                        inputCardsContainer.addView(cardView, inputCardsContainer.getChildCount() - 1);
                    }

                    updateTotalAndFeeText();
                }
            }
        }
    }
} 