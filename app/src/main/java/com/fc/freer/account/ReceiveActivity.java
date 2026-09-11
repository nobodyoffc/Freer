package com.fc.freer.account;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.nobody.NobodyRegistry;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class ReceiveActivity extends BaseCryptoActivity {
    private static final String TAG = "ReceiveActivity";

    private TextInputEditText amountInput;
    private ImageView qrCodeImageView;
    private ImageButton clearButton;
    private ImageButton incomeButton;
    private KeyInfo liveKeyInfo;
    private String liveFid;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_receive;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.receive);
    }

    @Override
    protected void initializeViews() {
        // Get live KeyInfo from FidManager
        FidManager fidManager = FidManager.getInstance();
        liveKeyInfo = fidManager.getLiveKeyInfo();
        liveFid = fidManager.getLiveFid();

        if (liveKeyInfo == null || liveFid == null) {
            ToastUtils.showError(this, getString(R.string.no_active_fid));
            finish();
            return;
        }

        // Initialize view components
        amountInput = findViewById(R.id.amountInput);
        qrCodeImageView = findViewById(R.id.qrCodeImageView);
        clearButton = findViewById(R.id.clearButton);
        incomeButton = findViewById(R.id.incomeButton);

        // Set up the live FID card
        setupLiveFidCard();

        // Add text change listener to amount input
        amountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                generateQRCode();
            }
        });

        // Generate initial QR code (just the FID)
        generateQRCode();
    }

    @Override
    protected void setupButtons() {
        // Clear button
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                amountInput.setText("");
                generateQRCode();
                ToastUtils.makeText(this, getString(R.string.cleared));
            });
        }

        // Income button
        if (incomeButton != null) {
            incomeButton.setOnClickListener(v -> {
                // Launch IncomeActivity
                Intent intent = new Intent(this, IncomeActivity.class);
                startActivity(intent);
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // ReceiveActivity doesn't need QR scanning functionality
        // This method is required by BaseCryptoActivity but not used here
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

        // The key's own flag also counts: it may predate the registry
        if (Boolean.TRUE.equals(liveKeyInfo.getIsNobody())) {
            NobodyRegistry.get().mark(liveKeyInfo.getId());
        }

        // Set avatar (the bitmap carries the nobody mark)
        if (avatarImageView != null) {
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            Bitmap avatarBitmap = avatarManager.getAvatarBitmap(liveFid);
            if (avatarBitmap != null) {
                avatarImageView.setImageBitmap(avatarBitmap);
            } else {
                avatarImageView.setImageResource(R.drawable.ic_person);
            }
        }
        NobodyUi.bindBanner(liveFidCard.findViewById(R.id.nobodyBanner), liveFid, R.string.nobody_receive_warning);

        // Set name (cid if available, otherwise fid)
        if (nameTextView != null) {
            String displayName = liveKeyInfo.getCid();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = liveFid;
            }
            NobodyUi.setName(nameTextView, liveKeyInfo.getId(), displayName);
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

    private void generateQRCode() {
        try {
            String qrContent;
            String amountStr = amountInput.getText() != null ? amountInput.getText().toString().trim() : "";

            if (amountStr.isEmpty()) {
                // No amount specified, just show the FID
                qrContent = liveFid;
            } else {
                // Parse amount and convert to satoshis
                try {
                    double amount = Double.parseDouble(amountStr);
                    if (amount <= 0) {
                        // Invalid amount, just show the FID
                        qrContent = liveFid;
                    } else {
                        // Convert coins to satoshis
                        long satoshis = FchUtils.coinToSatoshi(amount);
                        // Create freecash URI format: freecash:<fid>@1?value=<satoshis>
                        qrContent = String.format("freecash:%s@1?value=%d", liveFid, satoshis);
                    }
                } catch (NumberFormatException e) {
                    // Invalid number format, just show the FID
                    TimberLogger.w(TAG, "Invalid amount format: %s", amountStr);
                    qrContent = liveFid;
                }
            }

            // Generate QR code bitmap
            List<Bitmap> qrBitmaps = QRCodeGenerator.generateQRBitmaps(qrContent);
            if (!qrBitmaps.isEmpty()) {
                qrCodeImageView.setImageBitmap(qrBitmaps.get(0));
                TimberLogger.d(TAG, "Generated QR code for: %s", qrContent);
            } else {
                TimberLogger.e(TAG, "Failed to generate QR code");
                ToastUtils.showError(this, getString(R.string.error_creating_qr));
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error generating QR code: %s", e.getMessage(), e);
            ToastUtils.showError(this, getString(R.string.toast_error_generating_qr, e.getMessage()));
        }
    }
}