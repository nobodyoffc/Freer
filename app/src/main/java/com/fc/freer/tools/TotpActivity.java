package com.fc.freer.tools;

import static com.fc.fc_ajdk.data.feipData.Secret.Type.TOTP;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.secret.CreateSecretActivity;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import com.fc.freer.R;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.secret.ImportTotpActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import android.widget.ImageButton;

public class TotpActivity extends BaseCryptoActivity {
    private TextView currentTimeText;
    private LinearLayout totpCardList;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private List<TotpCard> totpCards = new ArrayList<>();
    private int lastCountdown = -1;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_totp;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.menu_totp);
    }

    @Override
    protected void initializeViews() {
        currentTimeText = findViewById(R.id.currentTimeText);
        totpCardList = findViewById(R.id.totpCardList);
        setupTimeUpdater();
        loadTotpCards();
    }

    @Override
    protected void setupButtons() {
        ImageButton importButton = findViewById(R.id.importButton);
        ImageButton createButton = findViewById(R.id.createButton);
        importButton.setOnClickListener(v -> {
            Intent intent = new Intent(TotpActivity.this, ImportTotpActivity.class);
            intent.putExtra("type", "TOTP");
            startActivity(intent);
        });
        createButton.setOnClickListener(v -> {
            Intent intent = new Intent(TotpActivity.this, CreateSecretActivity.class);
            intent.putExtra("type", "TOTP");
            startActivity(intent);
        });
    }

    private void setupTimeUpdater() {
        timeHandler = new Handler(Looper.getMainLooper());
        timeRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(now));
                currentTimeText.setText(time);
                int seconds = (int) ((now / 1000) % 30);
                int countdown = 30 - seconds;
                for (TotpCard card : totpCards) {
                    card.refreshCountdown(now);
                }
                if (countdown == 30 || lastCountdown == 1) { // countdown resets to 30, or just passed 0
                    for (TotpCard card : totpCards) {
                        card.refreshPassword(now);
                    }
                }
                lastCountdown = countdown;
                timeHandler.postDelayed(this, 1000);
            }
        };
        timeHandler.post(timeRunnable);
    }

    private void loadTotpCards() {
        totpCardList.removeAllViews();
        totpCards.clear();
        
        // Get SecretManager from FidManager
        FidManager fidManager = FidManager.getInstance();
        SecretManager secretManager = fidManager != null ? fidManager.getSecretManager() : null;
        
        if (secretManager == null) {
            ToastUtils.makeText(this, R.string.no_totp_found);
            return;
        }
        
        List<Secret> allSecrets = secretManager.searchSecrets(TOTP.displayName);
        if(allSecrets == null) {
            ToastUtils.makeText(this, R.string.no_totp_found);
            return;
        }
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher());
        for (Secret detail : allSecrets) {
            detail.decryptContent(prikey);
            if (TOTP.displayName.equalsIgnoreCase(detail.getType())) {
                addTotpCard(detail);
            }
        }
        SecurePrikeyManager.erasePrikey(prikey);
    }

    private void addTotpCard(Secret detail) {
        TotpCard card = new TotpCard(this, detail, System.currentTimeMillis());
        totpCardList.addView(card);
        totpCards.add(card);
    }

    @Override
    protected void onDestroy() {
        if (timeHandler != null && timeRunnable != null) {
            timeHandler.removeCallbacks(timeRunnable);
        }
        super.onDestroy();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTotpCards();
    }
} 