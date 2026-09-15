package com.fc.freer.myKeys;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.BaseCryptoActivity;

import com.fc.fc_ajdk.data.fcData.FcSubject;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.feature.avatar.AvatarMaker;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.VaultUnlocker;
import com.fc.freer.initiate.PasswordVerificationDialog;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import android.widget.ImageButton;
public class FindNiceKeysActivity extends BaseCryptoActivity {
    private static final String TAG = "FindNiceKeys";
    private static final long PASSWORD_VERIFICATION_THRESHOLD = 30000; // 30 seconds in milliseconds
    
    private KeyCardContainer keyCardContainer;
    private Map<String, byte[]> avatarCache = new HashMap<>();
    private PowerManager.WakeLock wakeLock;

    private TextView timerText;
    private TextInputEditText matchInput;
    private ImageButton saveButton;
    private ImageButton stopButton;
    private ImageButton startButton;
    private int defaultTimerTextColor;

    private ExecutorService executorService;
    private Handler mainHandler;
    private AtomicBoolean isFinding = new AtomicBoolean(false);
    private AtomicLong startTime = new AtomicLong(0);
    private Runnable timerRunnable;
    private boolean hasExceededThreshold = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_find_nice_keys;
    }

    @Override
    protected String getActivityTitle() {
        return "Find Nice Keys";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Freer:FindNiceKeysWakeLock");

        // Initialize managers
        LinearLayout keyListContainer = findViewById(R.id.keyListContainer);
        keyCardContainer = new KeyCardContainer(this, keyListContainer, ChooseMode.CHOOSE_MULTI);

        // Setup timer
        setupTimer();

        // Initialize executor service
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void initializeViews() {
        timerText = findViewById(R.id.timerText);
        matchInput = findViewById(R.id.matchInput);
        saveButton = findViewById(R.id.saveButton);
        stopButton = findViewById(R.id.stopButton);
        startButton = findViewById(R.id.startButton);
        defaultTimerTextColor = timerText.getCurrentTextColor();

        // Disable buttons initially
        setEnabledWithAlpha(saveButton, false);
        updateButtonStates();

        // Enable start button when match input has text
        matchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateButtonStates();
            }
        });
    }

    @Override
    protected void setupButtons() {
        View.OnClickListener buttonClickListener = v -> {
            hideKeyboard();
            if (v.getId() == R.id.saveButton) {
                List<KeyInfo> selectedKeys = keyCardContainer.getSelectedKeys();
                if (selectedKeys != null && !selectedKeys.isEmpty()) {
                    saveToConfigureAndFinish(selectedKeys);
                } else {
                    showToast(getString(R.string.please_select_keys));
                }
            } else if (v.getId() == R.id.stopButton) {
                stopFinding();
            } else if (v.getId() == R.id.startButton) {
                startFinding();
            }
        };

        saveButton.setOnClickListener(buttonClickListener);
        stopButton.setOnClickListener(buttonClickListener);
        startButton.setOnClickListener(buttonClickListener);
    }

    private void updateButtonStates() {
        boolean finding = isFinding.get();
        boolean hasInput = matchInput != null && matchInput.getText() != null && !matchInput.getText().toString().isEmpty();

        setEnabledWithAlpha(stopButton, finding);
        setEnabledWithAlpha(startButton, !finding && hasInput);
        updateTimerColor();
    }

    private void setEnabledWithAlpha(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private void updateTimerColor() {
        int color = isFinding.get()
                ? ContextCompat.getColor(this, R.color.warning)
                : defaultTimerTextColor;
        timerText.setTextColor(color);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void setupTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinding.get()) {
                    long elapsedTime = System.currentTimeMillis() - startTime.get();
                    updateTimerText(elapsedTime);
                    
                    // Check if we've exceeded the threshold
                    if (elapsedTime > PASSWORD_VERIFICATION_THRESHOLD && !hasExceededThreshold) {
                        hasExceededThreshold = true;
                        TimberLogger.i(TAG, "Activity has exceeded time threshold");
                    }
                    
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    private void updateTimerText(long elapsedTime) {
        long seconds = (elapsedTime / 1000) % 60;
        long minutes = (elapsedTime / (1000 * 60)) % 60;
        long hours = (elapsedTime / (1000 * 60 * 60));
        
        String timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        timerText.setText(timeString);
    }

    private void startFinding() {
        if (isFinding.get()) {
            TimberLogger.i(TAG, "Finding already in progress, ignoring start request");
            return;
        }
        
        // If we've exceeded the threshold, show password verification dialog
        if (hasExceededThreshold) {
            showPasswordVerificationDialog(new PasswordVerificationDialog.PasswordVerificationListener() {
                @Override
                public void onPasswordVerified(byte[] passwordBytes) {
                    VaultUnlocker.verifyPasswordAsync(FindNiceKeysActivity.this, passwordBytes, verified -> {
                        if (verified) {
                            hasExceededThreshold = false; // Reset the flag after successful verification
                            // Now that password is verified, start the finding process
                            startFindingAfterVerification();
                        } else {
                            ToastUtils.makeText(FindNiceKeysActivity.this, R.string.incorrect_password);
                        }
                    });
                }

                @Override
                public void onVerificationCancelled() {
                    ToastUtils.makeText(FindNiceKeysActivity.this, R.string.password_verification_cancelled);
                }
            });
            return;
        }
        
        startFindingAfterVerification();
    }

    private void startFindingAfterVerification() {
        String matchChars = matchInput.getText().toString().toLowerCase();
        if (matchChars.isEmpty()) {
            TimberLogger.i(TAG, "Match characters empty, cannot start finding");
            ToastUtils.makeText(this, R.string.please_enter_matching_characters);
            return;
        }
        
        TimberLogger.i(TAG, "Starting find operation with match pattern: %s", matchChars);
        isFinding.set(true);
        startTime.set(System.currentTimeMillis());
        mainHandler.post(timerRunnable);
        
        // Acquire WakeLock
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            TimberLogger.i(TAG, "WakeLock acquired");
        }
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setEnabledWithAlpha(saveButton, false);
        updateButtonStates();
        
        executorService.execute(() -> findNiceKeys(matchChars));
    }

    private void stopFinding() {
        TimberLogger.i(TAG, "Stopping find operation. Final key count: %d", keyCardContainer.getKeyInfoList().size());
        isFinding.set(false);
        
        // Release WakeLock if held
        if (wakeLock.isHeld()) {
            wakeLock.release();
            TimberLogger.i(TAG, "WakeLock released");
        }
        
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setEnabledWithAlpha(saveButton, true);
        updateButtonStates();
    }

    private void findNiceKeys(String matchChars) {
        TimberLogger.i(TAG, "Starting to find nice keys with match pattern: %s", matchChars);
        int currentKeyCount = keyCardContainer.getKeyInfoList().size();
        int targetKeyCount = currentKeyCount + 10;
        
        while (isFinding.get() && keyCardContainer.getKeyInfoList().size() < targetKeyCount) {
            FcSubject fcSubject = FcSubject.genPrikeyAndFid();
            String newFid = fcSubject.getId();
            byte[] prikeyBytes = fcSubject.getPrikeyBytes();
            
            if (newFid.toLowerCase().endsWith(matchChars)) {
                TimberLogger.i(TAG, "Found matching key with FID: %s", newFid);
                KeyInfo newKeyInfo = new KeyInfo("", prikeyBytes, ConfigureManager.getInstance().getSymkey());
                
                // Generate and cache avatar bytes
                try {
                    byte[] avatarBytes = AvatarMaker.createAvatar(newFid, this);
                    avatarCache.put(newFid, avatarBytes);
                    TimberLogger.i(TAG, "Generated and cached avatar for FID: %s", newFid);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error generating avatar for FID %s: %s", newFid, e.getMessage());
                }
                
                mainHandler.post(() -> {
                    TimberLogger.i(TAG, "Adding new key to list. Current size: %d", keyCardContainer.getKeyInfoList().size());
                    keyCardContainer.addKeyCard(newKeyInfo);
                });
            }
        }
        
        mainHandler.post(() -> {
            stopFinding();
        });
        TimberLogger.i(TAG, "Find nice keys operation completed");
    }

    @Override
    protected void onDestroy() {
        isFinding.set(false);

        // Release WakeLock if held
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            TimberLogger.i(TAG, "WakeLock released in onDestroy");
        }
        
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (executorService != null) {
            executorService.shutdown();
        }
        // Clear the avatar cache
        avatarCache.clear();

        super.onDestroy();
    }

    @Override
    public void finish() {
        if (isFinding.get()) {
            ToastUtils.makeText(this, R.string.stop_finding_progress_first);
            return;
        }
        if (hasExceededThreshold) {
            // Show password verification dialog
            showPasswordVerificationDialog();
        } else {
            super.finish();
        }
    }

    public void showPasswordVerificationDialog() {
        PasswordVerificationDialog dialog = new PasswordVerificationDialog(this, new PasswordVerificationDialog.PasswordVerificationListener() {
            @Override
            public void onPasswordVerified(byte[] passwordBytes) {
                VaultUnlocker.verifyPasswordAsync(FindNiceKeysActivity.this, passwordBytes, verified -> {
                    if (verified) {
                        hasExceededThreshold = false; // Reset the flag after successful verification
                        FindNiceKeysActivity.super.finish();
                    } else {
                        ToastUtils.makeText(FindNiceKeysActivity.this, R.string.incorrect_password);
                    }
                });
            }

            @Override
            public void onVerificationCancelled() {
                // Keep the activity open if verification is cancelled
                ToastUtils.makeText(FindNiceKeysActivity.this, R.string.password_verification_cancelled);
            }
        });
        dialog.show();
    }

    private void showPasswordVerificationDialog(PasswordVerificationDialog.PasswordVerificationListener listener) {
        PasswordVerificationDialog dialog = new PasswordVerificationDialog(this, listener);
        dialog.show();
    }

    @Override
    public void onBackPressed() {
        if (isFinding.get()) {
            ToastUtils.makeText(this, R.string.stop_finding_progress_first);
            return;
        }
        if (hasExceededThreshold) {
            showPasswordVerificationDialog();
        } else {
            super.onBackPressed();
        }
    }
} 