package com.fc.freer.account;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ChooseMode;
import com.google.android.material.textfield.TextInputEditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;

public class PayActivity extends BaseCryptoActivity {
    private static final String TAG = "PayActivity";
    private static final int QR_SCAN_RECIPIENT_REQUEST = 1001;

    private TextInputEditText recipientInput;
    private TextInputEditText amountInput;
    private ImageButton clearButton;
    private ImageButton expenseButton;
    private ImageButton scanQrButton;
    private ImageButton payButton;
    private KeyInfo liveKeyInfo;

    private ActivityResultLauncher<Intent> chooseContactLauncher;
    private ActivityResultLauncher<Intent> scanQrLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_pay;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.pay);
    }

    @Override
    protected void initializeViews() {
        // Initialize activity result launcher for choosing contacts
        chooseContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleChooseContactResult(result.getData());
                }
            }
        );

        // Initialize activity result launcher for QR scanning
        scanQrLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleScanQrResult(result.getData());
                }
            }
        );

        // Get live KeyInfo from FidManager
        FidManager fidManager = FidManager.getInstance();
        liveKeyInfo = fidManager.getLiveKeyInfo();

        if (liveKeyInfo == null) {
            ToastUtils.showError(this, getString(R.string.no_active_fid));
            finish();
            return;
        }

        // Set up the live FID card
        setupLiveFidCard();

        // Initialize input fields
        View recipientContainer = findViewById(R.id.recipientInputContainer);
        recipientInput = recipientContainer.findViewById(R.id.keyInput);
        recipientInput.setHint(getString(R.string.payee));

        amountInput = findViewById(R.id.amountInput);
        amountInput.setHint(getString(R.string.amount));
        // Initialize buttons
        clearButton = findViewById(R.id.clearButton);
        expenseButton = findViewById(R.id.expenseButton);
        scanQrButton = findViewById(R.id.scanQrButton);
        payButton = findViewById(R.id.payButton);

        // Setup IoIconsView for recipient input (with people and scan icons)
        setupIoIconsView(
            R.id.recipientInputContainer,
            R.id.peopleAndScanIcons,
            false, // no makeQr
            true,  // show people
            true, true,  // show scan
                // no file
                false,   // no makeQr listener
                null,
                isSingleChoice -> onPeopleClick(isSingleChoice),
                () -> pasteFromClipboard(recipientInput),
                () -> launchQrScanner(),  // scan listener
                null);   // no file listener

        // Add click listener to root layout to hide keyboard
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> {
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        });
    }

    private void setupLiveFidCard() {
        View liveFidCard = findViewById(R.id.liveFidCard);

        // Find UI elements in the FID card
        ImageView avatarImageView = liveFidCard.findViewById(R.id.fidAvatar);
        TextView nameTextView = liveFidCard.findViewById(R.id.fidName);
        TextView labelTextView = liveFidCard.findViewById(R.id.fidLabel);
        ImageView editIconView = liveFidCard.findViewById(R.id.fidEditIcon);
        TextView cashTextView = liveFidCard.findViewById(R.id.fidCash);
        TextView balanceTextView = liveFidCard.findViewById(R.id.fidBalance);
        TextView cdTextView = liveFidCard.findViewById(R.id.fidCd);
        ImageView noPrikeyIconView = liveFidCard.findViewById(R.id.multisigIcon);

        // Set avatar
        if (avatarImageView != null) {
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            String liveFid = liveKeyInfo.getId();
            Bitmap avatarBitmap = avatarManager.getAvatarBitmap(liveFid);
            if (avatarBitmap != null) {
                avatarImageView.setImageBitmap(avatarBitmap);
            } else {
                avatarImageView.setImageResource(R.drawable.ic_person);
            }
            // Grayscale marks a nobody FID (leaked/public prikey)
            if (Boolean.TRUE.equals(liveKeyInfo.getIsNobody())) {
                com.fc.freer.im.NobodyBoard.applyNobodyMark(avatarImageView);
            } else {
                com.fc.freer.im.NobodyBoard.clearNobodyMark(avatarImageView);
            }
        }

        // Set name (cid if available, otherwise fid)
        if (nameTextView != null) {
            String displayName = liveKeyInfo.getCid();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = liveKeyInfo.getId();
            }
            nameTextView.setText(displayName);
        }

        // Set label or edit icon visibility
        if (labelTextView != null && editIconView != null) {
            String label = liveKeyInfo.getLabel();
            if (label != null && !label.trim().isEmpty()) {
                labelTextView.setText(label);
                labelTextView.setVisibility(View.VISIBLE);
                editIconView.setVisibility(View.GONE);
            } else {
                labelTextView.setVisibility(View.GONE);
                editIconView.setVisibility(View.VISIBLE);
            }
        }

        // Set cash amount
        if (cashTextView != null) {
            Long cash = liveKeyInfo.getCash();
            if (cash != null && cash > 0) {
                cashTextView.setText(String.valueOf(cash));
            } else {
                cashTextView.setText("-");
            }
        }

        // Set balance
        if (balanceTextView != null) {
            Long balance = liveKeyInfo.getBalance();
            if (balance != null && balance > 0) {
                double balanceInCoins = FchUtils.satoshiToCoin(balance);
                balanceTextView.setText(formatBalance(balanceInCoins));
            } else {
                balanceTextView.setText("-");
            }
        }

        // Set cd
        if (cdTextView != null) {
            Long cd = liveKeyInfo.getCd();
            if (cd != null && cd > 0) {
                cdTextView.setText(formatLargeNumber(cd));
            } else {
                cdTextView.setText("-");
            }
        }

        // Set no_prikey icon visibility
        if (noPrikeyIconView != null) {
            String prikeyCipher = liveKeyInfo.getPrikeyCipher();
            if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                String liveFid = liveKeyInfo.getId();
                if (liveFid != null && liveFid.startsWith("3")) {
                    noPrikeyIconView.setImageResource(R.drawable.ic_people);
                } else {
                    noPrikeyIconView.setImageResource(R.drawable.ic_no_prikey);
                }
                noPrikeyIconView.setVisibility(View.VISIBLE);
            } else {
                noPrikeyIconView.setVisibility(View.GONE);
            }
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
    protected void setupButtons() {
        // Clear button
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                // Hide keyboard
                View currentFocus = getCurrentFocus();
                if (currentFocus != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                }

                recipientInput.setText("");
                amountInput.setText("");
                ToastUtils.makeText(this, getString(R.string.cleared));
            });
        }

        // Expense button
        if (expenseButton != null) {
            expenseButton.setOnClickListener(v -> {
                // Hide keyboard
                View currentFocus = getCurrentFocus();
                if (currentFocus != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                }

                // Launch ExpenseActivity
                Intent intent = new Intent(this, ExpenseActivity.class);
                startActivity(intent);
            });
        }

        // Scan button
        if (scanQrButton != null) {
            scanQrButton.setOnClickListener(v -> launchQrScanner());
        }

        // Pay button
        if (payButton != null) {
            payButton.setOnClickListener(v -> handlePay());
        }
    }

    private void onPeopleClick(boolean isSingleChoice) {
        // Hide keyboard
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
        }

        // Launch on-chain FID search (also lets the user pick from contacts) to choose a recipient
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
        chooseContactLauncher.launch(intent);
    }

    private void handleChooseContactResult(Intent data) {
        if (data == null) return;

        List<String> fids = data.getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
        if (fids != null && !fids.isEmpty()) {
            recipientInput.setText(fids.get(0));
        } else {
            ToastUtils.makeText(this, R.string.contact_has_no_fid);
        }
    }

    @Override
    protected void handleChooseKeyResult(Intent data) {
        if (data != null) {
            String selectedFid = data.getStringExtra("selected_fid");
            if (selectedFid != null && !selectedFid.isEmpty()) {
                recipientInput.setText(selectedFid);
            }
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_RECIPIENT_REQUEST) {
            // Handle recipient QR scan result
            if (qrContent != null && !qrContent.isEmpty()) {
                recipientInput.setText(qrContent);
            }
        }
    }

    private void launchQrScanner() {
        Intent intent = new Intent(this, com.fc.freer.qr.QrCodeActivity.class);
        intent.putExtra("is_return_string", true);
        scanQrLauncher.launch(intent);
    }

    private void handleScanQrResult(Intent data) {
        if (data != null) {
            String qrContent = data.getStringExtra("qr_content");
            if (qrContent != null && !qrContent.isEmpty()) {
                parseFreecashUri(qrContent);
            }
        }
    }

    private void parseFreecashUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            ToastUtils.showError(this, getString(R.string.invalid_qr_code_content));
            return;
        }

        try {
            // Expected format: freecash:FID@1?value=SATOSHIS
            if (!uri.startsWith("freecash:")) {
                // If not in freecash format, validate and set as recipient
                if (!KeyTools.isGoodFid(uri)) {
                    ToastUtils.showError(this, getString(R.string.invalid_fid));
                    return;
                }
                recipientInput.setText(uri);
                return;
            }

            // Remove the "freecash:" prefix
            String content = uri.substring(9);

            // Extract FID (from start to '@')
            int atIndex = content.indexOf('@');
            if (atIndex == -1) {
                // No '@' found, validate and treat whole content as FID
                if (!KeyTools.isGoodFid(content)) {
                    ToastUtils.showError(this, getString(R.string.invalid_fid));
                    return;
                }
                recipientInput.setText(content);
                return;
            }

            String fid = content.substring(0, atIndex);
            if (!KeyTools.isGoodFid(fid)) {
                ToastUtils.showError(this, getString(R.string.invalid_fid));
                return;
            }
            recipientInput.setText(fid);

            // Extract value parameter
            int valueIndex = content.indexOf("value=");
            if (valueIndex != -1) {
                String valueStr = content.substring(valueIndex + 6);
                // Remove any trailing parameters
                int ampIndex = valueStr.indexOf('&');
                if (ampIndex != -1) {
                    valueStr = valueStr.substring(0, ampIndex);
                }

                try {
                    // Parse value as decimal satoshis (supports scientific notation like '4e8')
                    long satoshis = (long) Double.parseDouble(valueStr);
                    double coins = FchUtils.satoshiToCoin(satoshis);
                    amountInput.setText(String.valueOf(coins));
                } catch (NumberFormatException e) {
                    TimberLogger.e(TAG, "Error parsing value: %s", e.getMessage());
                    ToastUtils.showError(this, getString(R.string.invalid_amount_format));
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing freecash URI: %s", e.getMessage());
            ToastUtils.showError(this, getString(R.string.failed_error_msg, e.getMessage()));
        }
    }

    private void handlePay() {
        // Validate inputs
        String recipient = recipientInput.getText() != null ? recipientInput.getText().toString().trim() : "";
        String amountStr = amountInput.getText() != null ? amountInput.getText().toString().trim() : "";

        if (recipient.isEmpty()) {
            ToastUtils.showError(this, getString(R.string.please_enter_recipient_address));
            return;
        }

        if (amountStr.isEmpty()) {
            ToastUtils.showError(this, getString(R.string.please_enter_amount));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
            if (amount <= 0) {
                ToastUtils.showError(this, getString(R.string.amount_must_be_greater_than_zero));
                return;
            }
        } catch (NumberFormatException e) {
            ToastUtils.showError(this, getString(R.string.invalid_amount_format));
            return;
        }

        // Create Cash list
        List<Cash> cashList = new ArrayList<>();
        cashList.add(new Cash(recipient, amount));

        // Get required instances
        CashManager cashManager = CashManager.getInstance();
        TxHandler txHandler = new TxHandler();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

        // Get sender FID
        String sender = liveKeyInfo.getId();

        // Create TxSender and send transaction
        TxSender txSender = new TxSender();

        // Use sendTx with withConfirm = true to show SendTxActivity
        txSender.sendTx(
            this,
            sender,
                cashList,
            null,  // no opReturn
            null,  // prikey will be fetched in SendTxActivity
            0L,    // cd
                // bestHeight
            TxHandler.DEFAULT_FEE_RATE,
            null,  // multisig
            RawTxInfo.VERSION_2,
                null, cashManager,
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
                        ToastUtils.showError(PayActivity.this, getString(R.string.failed_to_send_tx,errorMessage));
                    });
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> {
                        ToastUtils.showWarning(PayActivity.this, getString(R.string.no_prikey_available));
                    });
                }

                @Override
                public void onUnbroadcasted(String signedTxHex) {
                    runOnUiThread(() -> {
                        ToastUtils.makeText(PayActivity.this, getString(R.string.tx_signed_but_not_broadcast));
                    });
                }
            },
            true  // withConfirm = true to show SendTxActivity
        );
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        liveKeyInfo = null;
    }
}