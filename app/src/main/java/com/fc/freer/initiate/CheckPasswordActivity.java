package com.fc.freer.initiate;

import static com.fc.freer.initiate.ChooseCidActivity.SELECTED_KEY_INFO_JSON;
import static com.fc.freer.utils.BackgroundTimeoutManager.FROM_BACKGROUND_TIMEOUT;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.manager.CashManager;
import com.fc.freer.utils.ToastUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.qr.QrCodeActivity;
import com.fc.freer.ui.WaitingDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.fc.freer.utils.ToolbarUtils;


public class CheckPasswordActivity extends AppCompatActivity {

    private EditText passwordInput;
    private TextInputLayout passwordInputLayout;
    private Button verifyButton;
    // Guards against double-tapping the verify button, which would otherwise
    // start two verification threads and launch ChooseCidActivity twice.
    private boolean isVerifying = false;
    private static final String TAG = "CryptoSign";
    /** Set by ChangePasswordActivity: check the open vault's password and change nothing else. */
    public static final String FOR_PASSWORD_CHANGE = "for_password_change";
    private static final int QR_CODE_REQUEST_CODE = 1001;
    private static final int REQUEST_CODE_CHOOSE_CID = 1002;
    private ActivityResultLauncher<Intent> createPasswordLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register this instance so the background auto-lock won't stack a second
        // password screen on top while this one is alive.
        com.fc.freer.utils.BackgroundTimeoutManager.onPasswordCheckCreated();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_check_password);
        // Set up toolbar
        ToolbarUtils.setupToolbar(this, getString(R.string.check_password));
        // Override toolbar navigation click to prevent bypassing password check
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            // Check if back navigation is allowed (e.g., from ChangePasswordActivity)
            boolean allowBackNavigation = getIntent().getBooleanExtra("allow_back_navigation", false);
            if (allowBackNavigation) {
                // Allow user to cancel and return to previous activity
                setResult(RESULT_CANCELED);
                finish();
            } else {
                // Close the entire app to prevent bypassing password check
                finishAffinity();
            }
        });
        // Initialize the activity result launcher for CreatePassword
        // Note: When from_background_timeout is true, CreatePasswordActivity will handle
        // the flow directly (cleanup + ChooseCidActivity + HomeActivity) and won't return here
        createPasswordLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        // Get the Configure object from ConfigureManager
                        Configure configure = ConfigureManager.getInstance().getConfigure();
                        if (configure != null) {
                            // Simply return success result
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            TimberLogger.e(TAG, "Configure object not found in ConfigureManager");
                            ToastUtils.makeText(this, getString(R.string.error_configuration_not_found));
                        }
                    }
                }
        );
        
        // Initialize UI components
        passwordInputLayout = findViewById(R.id.passwordInputLayout);
        passwordInput = findViewById(R.id.password_input);
        verifyButton = findViewById(R.id.verify_button);
        Button createPasswordButton = findViewById(R.id.create_password_button);
        Button clearButton = findViewById(R.id.clear_button);
        
        // Add click listener for the scan icon
        passwordInputLayout.setEndIconOnClickListener(v -> startQrCodeScanner());
        
        // Add Enter key listener to password input
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                verifyPassword();
                return true;
            }
            return false;
        });
        
        verifyButton.setOnClickListener(v -> verifyPassword());
        createPasswordButton.setOnClickListener(v -> startCreatePasswordActivity());
        clearButton.setOnClickListener(v -> {
            passwordInput.setText("");
            // Hide keyboard when clear button is clicked
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(passwordInput.getWindowToken(), 0);
            }
        });
    }

    private void startQrCodeScanner() {
        Intent intent = new Intent(this, QrCodeActivity.class);
        intent.putExtra("is_return_string", true);
        startActivityForResult(intent, QR_CODE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == QR_CODE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String scannedText = data.getStringExtra("qr_content");
            if (scannedText != null && !scannedText.isEmpty()) {
                passwordInput.setText(scannedText);
            }
        }

        // Handle ChooseCidActivity result after password change
        if (requestCode == REQUEST_CODE_CHOOSE_CID) {
            // Back on this screen - clear the guard. Success paths below finish()
            // anyway; failure/cancel paths stay here and need the button usable.
            isVerifying = false;
            verifyButton.setEnabled(true);
            if (resultCode == RESULT_OK && data != null) {
                String keyInfoJson = data.getStringExtra(SELECTED_KEY_INFO_JSON);
                if (keyInfoJson != null) {
                    try {
                        KeyInfo selectedKeyInfo = KeyInfo.fromJson(keyInfoJson, KeyInfo.class);
                        if (selectedKeyInfo != null) {
                            TimberLogger.d(TAG, "User selected CID after password change: %s", selectedKeyInfo.getId());

                            // Create or load Setting for the selected CID
                            com.fc.freer.initiate.SettingManager settingManager = com.fc.freer.initiate.SettingManager.getInstance();
                            com.fc.freer.model.Setting setting = settingManager.getOrCreateSetting(this, selectedKeyInfo);

                            if (setting != null) {
                                settingManager.setCurrentSetting(setting);
                                TimberLogger.d(TAG, "Setting created and set for CID: %s", selectedKeyInfo.getId());

                                // Launch HomeActivity with flags to clear the entire stack
                                // This ensures no old session activities remain
                                Intent homeIntent = new Intent(this, com.fc.freer.home.HomeActivity.class);
                                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(homeIntent);
                                finish();
                            } else {
                                TimberLogger.e(TAG, "Failed to create setting for selected CID");
                                ToastUtils.makeText(this, getString(R.string.error_creating_setting));
                            }
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error parsing selected KeyInfo: " + e.getMessage());
                        ToastUtils.makeText(this, getString(R.string.error_parsing_key_info));
                    }
                }
            } else {
                // User cancelled CID selection - can't continue without a CID
                TimberLogger.d(TAG, "User cancelled CID selection after password change");
                ToastUtils.makeText(this, getString(R.string.please_select_a_cid));
                // Don't finish - let them try again or exit the app
            }
        }
    }
    
    private void startCreatePasswordActivity() {
        Intent intent = new Intent(this, CreatePasswordActivity.class);
        // Add flag to indicate this is from background timeout
        if (getIntent().getBooleanExtra(FROM_BACKGROUND_TIMEOUT, false)) {
            intent.putExtra(FROM_BACKGROUND_TIMEOUT, true);
        }
        createPasswordLauncher.launch(intent);
    }
    
    private void verifyPassword() {
        // Ignore rapid repeat taps while a verification is already in progress.
        if (isVerifying) {
            return;
        }

        String enteredPassword = passwordInput.getText().toString();
        if (enteredPassword.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_password));
            return;
        }

        // Lock the flow so a second tap can't start another verification thread.
        isVerifying = true;
        verifyButton.setEnabled(false);

        // Show waiting dialog
        WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.verifying_password));
        waitingDialog.show();

        // Run verification in background thread to avoid blocking UI
        new Thread(() -> {
            try {
                byte[] passwordBytes = enteredPassword.getBytes();
                if (getIntent().getBooleanExtra(FOR_PASSWORD_CHANGE, false)) {
                    boolean verified = ConfigureManager.getInstance().verifyPassword(passwordBytes);
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        if (verified) {
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            isVerifying = false;
                            verifyButton.setEnabled(true);
                            ToastUtils.makeText(this, getString(R.string.incorrect_password));
                        }
                    });
                    return;
                }
                VaultUnlocker.Result unlocked = VaultUnlocker.unlock(this, passwordBytes, true);
                Configure configure = unlocked.configure;
                ConfigureManager.getInstance().sealPlainPrikeys(this, configure);
                String passwordName = configure != null ? configure.getPasswordName() : null;

                // Switch back to UI thread for UI operations
                runOnUiThread(() -> {
                    waitingDialog.dismiss();

                    if (configure != null) {
                        if (unlocked.unreadableRecordId != null) {
                            ToastUtils.makeText(this, getString(R.string.vault_migration_aborted, unlocked.unreadableRecordId));
                        }

                        // Check if this is from background timeout
                        boolean fromBackgroundTimeout = getIntent().getBooleanExtra(FROM_BACKGROUND_TIMEOUT, false);
                        // Get the current configure's password name (if exists)
                        Configure currentConfigure = ConfigureManager.getInstance().getConfigure();
                        String currentPasswordName = currentConfigure != null ? currentConfigure.getPasswordName() : null;

                        // If from background timeout and password has changed, clear old session and launch ChooseCidActivity
                        if (fromBackgroundTimeout && currentPasswordName != null && !currentPasswordName.equals(passwordName)) {
                            TimberLogger.d(TAG, "Password changed during background timeout, clearing old session");

                            // IMPORTANT: Clean up all resources from the old password session
                            cleanupOldSession();

                            // Set the new configure
                            ConfigureManager.getInstance().setConfigure(configure);

                            // Get DatabaseManager instance and set password name
                            DatabaseManager databaseManager = DatabaseManager.getInstance();
                            databaseManager.setCurrentPasswordName(passwordName);

                            // Launch ChooseCidActivity and wait for the result
                            Intent intent = new Intent(this, ChooseCidActivity.class);
                            startActivityForResult(intent, REQUEST_CODE_CHOOSE_CID);
                            return;
                        }
                        // Get DatabaseManager instance
                        DatabaseManager databaseManager = DatabaseManager.getInstance();
                        databaseManager.setCurrentPasswordName(passwordName);

                        // Store the Configure object in ConfigureManager for sharing across activities
                        ConfigureManager.getInstance().setConfigure(configure);

                        // If from background timeout, normally the process is still
                        // alive and the underlying HomeActivity (with its current
                        // setting + FidManager) can simply resume.
                        if (fromBackgroundTimeout) {
                            // But if the process was killed during the background sleep
                            // and only restored now, the symkey we just rebuilt is
                            // present while the current setting is gone. Resuming
                            // HomeActivity in that state leaves the FID card stuck on
                            // "Loading...", so re-select the identity to fully restore
                            // the session before continuing (the symkey is already valid,
                            // so no extra password prompt is needed).
                            // Locked while choosing an identity: that chooser is still
                            // underneath and has no setting yet by design.
                            boolean resumesChooser = getIntent().getBooleanExtra(
                                    com.fc.freer.utils.BackgroundTimeoutManager.RESUMES_CHOOSER, false);
                            if (!resumesChooser
                                    && com.fc.freer.initiate.SettingManager.getInstance().getCurrentSetting() == null) {
                                TimberLogger.d(TAG, "Session restored after process death; selecting CID to rebuild setting");
                                Intent intent = new Intent(this, ChooseCidActivity.class);
                                startActivityForResult(intent, REQUEST_CODE_CHOOSE_CID);
                                return;
                            }
                            TimberLogger.d(TAG, "Same password during background timeout, resuming to existing activity");
                            finish();
                            return;
                        }

                        // For normal flow (not from background timeout), return success result
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        // Verification failed - unlock so the user can retry.
                        isVerifying = false;
                        verifyButton.setEnabled(true);
                        ToastUtils.makeText(this, getString(R.string.incorrect_password));
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error verifying password: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    // Verification errored - unlock so the user can retry.
                    isVerifying = false;
                    verifyButton.setEnabled(true);
                    ToastUtils.makeText(this, getString(R.string.error_verifying_password));
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.fc.freer.utils.BackgroundTimeoutManager.onPasswordCheckDestroyed();
    }

    @Override
    public void onBackPressed() {
        // Check if back navigation is allowed (e.g., from ChangePasswordActivity)
        boolean allowBackNavigation = getIntent().getBooleanExtra("allow_back_navigation", false);
        if (allowBackNavigation) {
            // Allow user to cancel and return to previous activity
            setResult(RESULT_CANCELED);
            super.onBackPressed();
        } else {
            // Close the entire app to prevent bypassing password check
            finishAffinity();
        }
    }

    /**
     * Clean up all resources from the old password session
     * This includes FidManager, ApiCenter, CashManager, and SettingManager
     */
    private void cleanupOldSession() {
        TimberLogger.d(TAG, "Cleaning up old password session");

        try {
            // 1. Clean up ApiCenter FIRST (closes all API clients and resets singleton)
            // This must be done before FidManager cleanup to ensure all clients are properly closed
            com.fc.freer.utils.ApiCenter apiCenter = com.fc.freer.utils.ApiCenter.getInstance();
            if (apiCenter != null) {
                apiCenter.fullReset();
                TimberLogger.d(TAG, "ApiCenter fully reset (singleton cleared)");
            }

            // 2. Clean up FidManager (closes all managers for old mainFid)
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            if (fidManager != null) {
                fidManager.fullCleanup();
                TimberLogger.d(TAG, "FidManager cleaned up");
            }

            // 3. Clean up CashManager (close database and reset singleton)
            CashManager.cleanup();
            TimberLogger.d(TAG, "CashManager cleaned up");

            // 4. Clear current setting in SettingManager
            com.fc.freer.initiate.SettingManager settingManager = com.fc.freer.initiate.SettingManager.getInstance();
            if (settingManager != null) {
                settingManager.clearCurrentSetting();
                TimberLogger.d(TAG, "SettingManager current setting cleared");
            }

            // 5. Clear AvatarManager cache (optional, but good practice)
            com.fc.freer.manager.AvatarManager avatarManager = com.fc.freer.manager.AvatarManager.getInstance(this);
            if (avatarManager != null) {
                avatarManager.clearCache();
                TimberLogger.d(TAG, "AvatarManager cache cleared");
            }

            TimberLogger.d(TAG, "Old session cleanup completed successfully");

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error during old session cleanup: " + e.getMessage(), e);
            // Continue anyway - we want to proceed with the new password
        }
    }
} 