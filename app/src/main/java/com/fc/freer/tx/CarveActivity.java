package com.fc.freer.tx;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.contact.ChooseContactActivity;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.JsonUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.multisig.SignMultisigTxActivity;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.tx.dialog.AddTxOutputDialog;
import com.fc.freer.tx.dialog.AddOutputFromFidListDialog;
import com.fc.freer.tx.view.TxOutputCard;
import com.fc.freer.manager.FidManager;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.ApiCenter;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.ImageButton;

import java.util.ArrayList;
import java.util.List;
import java.text.DecimalFormat;

public class CarveActivity extends BaseCryptoActivity {
    private static final String TAG = "CarveActivity";
    private static final int QR_SCAN_TEXT_REQUEST_CODE = 1001;
    private static final int QR_SCAN_OUTPUT_FID_REQUEST_CODE = 1005;
    private static final int QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE = 1006;

    private RawTxInfo rawTxInfo;

    private LinearLayout outputCardsContainer;
    private LinearLayout outputInputContainer;
//    private LinearLayout opreturnContainer;
    private LinearLayout payeeContainer;
    private CheckBox addPayeeCheckbox;

    private List<TxOutputCard> outputCards = new ArrayList<>();

    private TextInputEditText opreturnInput;
    private TextInputEditText outputFidInput;
    private TextInputEditText outputAmountInput;
    private TextView outputMaxText;
    private Button addOutputButton;
    private ImageButton clearButton;
    private ImageButton createButton;
    private ImageButton copyButton;
    private ImageButton opReturnButton;
    private TextView payToListView;
    private TextView outputHint;

    private KeyInfo keyInfo;
    private String opreturn;
    private long fee;
    private long totalOutput;
    private long rest;

    private ActivityResultLauncher<Intent> signTxLauncher;
    private ActivityResultLauncher<Intent> chooseContactLauncher;

    private Dialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        rawTxInfo = new RawTxInfo();

        // Initialize signTxLauncher
        signTxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == SendTxActivity.RESULT_SIGNED ||
                    result.getResultCode() == SignMultisigTxActivity.RESULT_SENT) {
                    finish();
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
        outputCardsContainer = findViewById(R.id.outputCardsContainer);
        outputInputContainer = findViewById(R.id.outputInputContainer);
//        opreturnContainer = findViewById(R.id.opreturnContainer);
        payeeContainer = findViewById(R.id.payeeContainer);
        addPayeeCheckbox = findViewById(R.id.addPayeeCheckbox);
        outputHint = findViewById(R.id.outputHint);

        opreturnInput = findViewById(R.id.textInput);
        opreturnInput.setHint(R.string.input_the_text_carved_on_chain);

        // Initialize output input fields
        outputFidInput = outputInputContainer.findViewById(R.id.outputFidInput).findViewById(R.id.keyInput);
        outputFidInput.setHint(R.string.fid);
        outputAmountInput = outputInputContainer.findViewById(R.id.outputAmountInput).findViewById(R.id.textInput);
        outputAmountInput.setHint(R.string.amount);
        outputAmountInput.setText("0.001");
        outputMaxText = findViewById(R.id.outputRestText);
        addOutputButton = findViewById(R.id.addOutputButton);

        clearButton = findViewById(R.id.clearButton);
        createButton = findViewById(R.id.createButton);
        copyButton = findViewById(R.id.copyButton);
        opReturnButton = findViewById(R.id.opReturnButton);
        payToListView = findViewById(R.id.addFromList);

        // Setup icons
        setupTextIcons(R.id.opreturnInput, R.id.scanIcon, QR_SCAN_TEXT_REQUEST_CODE);
        setupPeopleAndScanIcons(R.id.outputFidInput, R.id.peopleAndScanIcons, QR_SCAN_OUTPUT_FID_REQUEST_CODE);
        setupTextIcons(R.id.outputAmountInput, R.id.scanIcon, QR_SCAN_OUTPUT_AMOUNT_REQUEST_CODE);

        // Initially hide payee container
        payeeContainer.setVisibility(View.GONE);
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

        // Add payee checkbox listener
        addPayeeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            payeeContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) {
                // Clear outputs when unchecked
                outputCards.clear();
                outputCardsContainer.removeAllViews();
                outputFidInput.setText("");
                outputAmountInput.setText("");
                if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
                    rawTxInfo.getOutputs().clear();
                }
                outputHint.setVisibility(View.VISIBLE);
            }
        });

        payToListView.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            List<String> fidList = FreerApplication.getFidList();
            if (fidList.isEmpty()) {
                ToastUtils.makeText(this, R.string.fid_list_is_empty);
                return;
            }

            AddOutputFromFidListDialog dialog = new AddOutputFromFidListDialog(this, fidList, rawTxInfo, rest, false);
            currentDialog = dialog;
            dialog.setOnDoneListener(sendToList -> {
                for (Cash cash : sendToList) {
                    TxOutputCard card = new TxOutputCard(this);
                    card.setSendTo(cash, this, true,false );
                    card.setOnDeleteListener(this::removeOutputCard);
                    outputCards.add(card);
                    outputCardsContainer.addView(card);
                }
                outputHint.setVisibility(View.GONE);
                currentDialog = null;
                updateFeeText();
            });
            dialog.setOnDismissListener(d -> currentDialog = null);
            dialog.show();
        });

        // Add long press listener for plus output button
        payToListView.setOnLongClickListener(v -> {
            AddTxOutputDialog dialog = new AddTxOutputDialog(this, rawTxInfo, rest);
            currentDialog = dialog;
            dialog.setOnDoneListener(sendTo -> {
                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(sendTo, this, true, false);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card);
                outputHint.setVisibility(View.GONE);
                currentDialog = null;
                updateFeeText();
            });
            dialog.setOnDismissListener(d -> currentDialog = null);
            dialog.show();
            return true;
        });

        // Add output button listener
        addOutputButton.setOnClickListener(v -> {
            if (outputFidInput.getText() == null || outputAmountInput.getText() == null) {
                ToastUtils.makeText(this, R.string.please_input_all_fields);
                return;
            }
            if (outputFidInput.getText().toString().isEmpty() || outputAmountInput.getText().toString().isEmpty()) {
                ToastUtils.makeText(this, R.string.please_input_all_fields);
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

                Cash cash = new Cash(fid, amount);
                rawTxInfo.getOutputs().add(cash);

                TxOutputCard card = new TxOutputCard(this);
                card.setSendTo(cash, this, true, false);
                card.setOnDeleteListener(this::removeOutputCard);
                outputCards.add(card);
                outputCardsContainer.addView(card);
                outputHint.setVisibility(View.GONE);

                // Clear input fields
                outputFidInput.setText("");
                outputAmountInput.setText("");

                updateFeeText();
            } catch (NumberFormatException e) {
                ToastUtils.makeText(this, R.string.invalid_amount);
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

            outputCardsContainer.removeAllViews();
            opreturnInput.setText("");
            outputFidInput.setText("");
            outputAmountInput.setText("");
            outputCards.clear();
            outputHint.setVisibility(View.VISIBLE);
            addPayeeCheckbox.setChecked(false);
        });

        createButton.setOnClickListener(v -> handleCarve());

        // OpReturn history button - shows the OpReturn (carve) history of the live FID
        if (opReturnButton != null) {
            opReturnButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, com.fc.freer.account.OpReturnActivity.class);
                startActivity(intent);
            });
        }

        copyButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }

            // Build minimal RawTxInfo for copying (without inputs - TxSender will handle)
            RawTxInfo copyTxInfo = new RawTxInfo();
            if (rawTxInfo != null && rawTxInfo.getOutputs() != null) {
                copyTxInfo.setOutputs(rawTxInfo.getOutputs());
            }
            String opreturnText = opreturnInput.getText() != null ? opreturnInput.getText().toString().trim() : null;
            if (opreturnText != null && !opreturnText.isEmpty()) {
                copyTxInfo.setOpReturn(opreturnText);
            }

            String json = copyTxInfo.toNiceJson();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transaction JSON", json);
            clipboard.setPrimaryClip(clip);
            ToastUtils.makeText(this, R.string.tx_json_copied_to_clipboard);
        });
    }

    private void setupPeopleAndScanIcons(int layoutId, int iconsViewId, int qrRequestCode) {
        View layoutView = findViewById(layoutId);
        IoIconsView iconsView = layoutView.findViewById(iconsViewId);

        // Configure to show only people and scan icons
        iconsView.init(this, false, true, true, true,false);

        // Set up people icon click listener
        iconsView.setOnPeopleClickListener(isSingleChoice -> {
            Intent intent = new Intent(this, ChooseContactActivity.class);
            intent.putExtra(ChooseContactActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
            chooseContactLauncher.launch(intent);
        });

        // Set up scan icon click listener
        iconsView.setOnScanClickListener(() -> {
            startQrScan(qrRequestCode);
        });
    }

    private void handleContactSelectionResult(Intent data) {
        String selectedContactsJson = data.getStringExtra(ChooseContactActivity.EXTRA_SELECTED_CONTACTS);
        if (selectedContactsJson != null) {
            try {
                List<Contact> selectedContacts = JsonUtils.listFromJson(selectedContactsJson, Contact.class);
                if (!selectedContacts.isEmpty()) {
                    if (selectedContacts.size() == 1) {
                        // Single contact selected - put FID in the input field
                        Contact contact = selectedContacts.get(0);
                        if (contact.getFid() != null && !contact.getFid().isEmpty()) {
                            outputFidInput.setText(contact.getFid());
                        } else {
                            ToastUtils.makeText(this, R.string.contact_has_no_fid);
                        }
                    } else {
                        // Multiple contacts selected - create batch outputs
                        List<String> fidList = new ArrayList<>();
                        for (Contact contact : selectedContacts) {
                            if (contact.getFid() != null && !contact.getFid().isEmpty()) {
                                fidList.add(contact.getFid());
                            }
                        }

                        if (!fidList.isEmpty()) {
                            AddOutputFromFidListDialog dialog = new AddOutputFromFidListDialog(this, fidList, rawTxInfo, rest, false);
                            currentDialog = dialog;
                            dialog.setOnDoneListener(sendToList -> {
                                for (Cash cash : sendToList) {
                                    TxOutputCard card = new TxOutputCard(this);
                                    card.setSendTo(cash, this, true, false);
                                    card.setOnDeleteListener(this::removeOutputCard);
                                    outputCards.add(card);
                                    outputCardsContainer.addView(card);
                                }
                                outputHint.setVisibility(View.GONE);
                                currentDialog = null;
                                updateFeeText();
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
                ToastUtils.makeText(this, getString(R.string.failed_error_msg, e.getMessage()));
            }
        }
    }

    private void updateFeeText() {
        // Simple fee estimation for display purposes only
        // TxSender will calculate actual fee
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);

        // Show placeholder text - actual available amount managed by TxSender
        String maxTextStr = getString(R.string.amount);
        outputMaxText.setText(maxTextStr);
        outputMaxText.setOnClickListener(null);
    }

    private void removeOutputCard(TxOutputCard card) {
        outputCards.remove(card);
        outputCardsContainer.removeView(card);
        rawTxInfo.getOutputs().remove(card.getSendTo());
        if (outputCards.isEmpty()) {
            outputHint.setVisibility(View.VISIBLE);
        }
        updateFeeText();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_carve;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.carve);
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
            if (currentDialog instanceof AddTxOutputDialog) {
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

    private void handleCarve() {
        // Validate opreturn input
        String opreturnText = opreturnInput.getText() != null ? opreturnInput.getText().toString().trim() : "";

        if (opreturnText.isEmpty()) {
            ToastUtils.makeText(this, R.string.please_input_all_fields);
            return;
        }

        // Get live KeyInfo
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        // Get sender FID
        String sender = liveKeyInfo.getId();
        if (sender == null || sender.isEmpty()) {
            ToastUtils.makeText(this, R.string.invalid_sender);
            return;
        }

        // Create outputs list (may be empty if checkbox is unchecked)
        List<Cash> outputs = null;
        if (addPayeeCheckbox.isChecked() && rawTxInfo != null && rawTxInfo.getOutputs() != null && !rawTxInfo.getOutputs().isEmpty()) {
            outputs = rawTxInfo.getOutputs();
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
//                        ToastUtils.makeText(CarveActivity.this, getString(R.string.tx_sent));
                        finish();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(CarveActivity.this, getString(R.string.failed_to_send_tx, errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(CarveActivity.this, R.string.no_prikey_available);
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(CarveActivity.this, R.string.tx_signed_but_not_broadcast);
                    });
                }
            },
            true  // withConfirm = true to show SendTxActivity
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
            currentDialog = null;
        }

        outputCards.clear();
        rawTxInfo = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
        }
    }
}
