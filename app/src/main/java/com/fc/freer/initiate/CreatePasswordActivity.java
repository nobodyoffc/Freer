package com.fc.freer.initiate;

import static com.fc.freer.utils.BackgroundTimeoutManager.FROM_BACKGROUND_TIMEOUT;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.fc.freer.utils.ToastUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.fc.freer.model.Configure;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.qr.QrCodeActivity;
import com.fc.freer.ui.RemindDialog;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Map;
import java.util.Objects;

public class CreatePasswordActivity extends AppCompatActivity {

    public static final String FROM_NEW_PASSWORD = "from_new_password";
    private EditText passwordInput;
    private EditText confirmPasswordInput;
    private TextView errorText;
    private TextInputLayout passwordInputLayout;
    private TextInputLayout confirmPasswordInputLayout;
    private static final String TAG = "CryptoSign";
    private static final int MIN_PASSWORD_LENGTH = 4;
    private static final int QR_CODE_REQUEST_CODE = 1001;
    private static final int QR_CODE_REQUEST_CODE_CONFIRM = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_password);

        // Set up toolbar
        ToolbarUtils.setupToolbar(this, getString(R.string.create_password));

        initializeViews();
        setupClickListeners();
        
        // Request focus for the password input
        passwordInput.requestFocus();
    }

    private void initializeViews() {
        passwordInputLayout = findViewById(R.id.passwordInputLayout);
        confirmPasswordInputLayout = findViewById(R.id.confirmPasswordInputLayout);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        errorText = findViewById(R.id.errorText);
    }

    private void setupClickListeners() {
        MaterialButton createButton = findViewById(R.id.createButton);
        MaterialButton cancelButton = findViewById(R.id.cancelButton);
        MaterialButton clearButton = findViewById(R.id.clearButton);

        createButton.setOnClickListener(v -> validateAndCreatePassword());
        cancelButton.setOnClickListener(v -> handleCancel());
        clearButton.setOnClickListener(v -> {
            passwordInput.setText("");
            confirmPasswordInput.setText("");
            errorText.setVisibility(View.GONE);
            // Hide keyboard when clear button is clicked
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(passwordInput.getWindowToken(), 0);
            }
        });
        
        // Add click listeners for the scan icons
        passwordInputLayout.setEndIconOnClickListener(v -> startQrCodeScanner(QR_CODE_REQUEST_CODE));
        confirmPasswordInputLayout.setEndIconOnClickListener(v -> startQrCodeScanner(QR_CODE_REQUEST_CODE_CONFIRM));
    }

    private void startQrCodeScanner(int requestCode) {
        Intent intent = new Intent(this, QrCodeActivity.class);
        intent.putExtra("is_return_string", true);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            String scannedText = data.getStringExtra("qr_content");
            if (scannedText != null && !scannedText.isEmpty()) {
                if (requestCode == QR_CODE_REQUEST_CODE) {
                    passwordInput.setText(scannedText);
                    confirmPasswordInput.requestFocus();
                } else if (requestCode == QR_CODE_REQUEST_CODE_CONFIRM) {
                    confirmPasswordInput.setText(scannedText);
                }
            }
        }
    }

    private void handleCancel() {
        if (isTaskRoot()) {
            finishAffinity();
        } else {
            finish();
        }
    }

    private void validateAndCreatePassword() {
        String password = Objects.requireNonNull(passwordInput.getText()).toString();
        String confirmPassword = Objects.requireNonNull(confirmPasswordInput.getText()).toString();

        if (!isPasswordValid(password, confirmPassword)) {
            return;
        }

        try {
            savePassword();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating password: " + e.getMessage(), e);
            showError(getString(R.string.error_creating_password));
        }
    }

    private boolean isPasswordValid(String password, String confirmPassword) {
        if (password.isEmpty()) {
            showError(getString(R.string.please_enter_password));
            return false;
        }

        if (confirmPassword.isEmpty()) {
            showError("Please confirm your password");
            return false;
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            showError(getString(R.string.password_must_be_at_least_d_characters_long,MIN_PASSWORD_LENGTH));
            return false;
        }

        if (!password.equals(confirmPassword)) {
            showError(getString(R.string.passwords_do_not_match));
            return false;
        }

        return true;
    }

    private void savePassword() {
        String password = passwordInput.getText().toString();
        if (password.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_password));
            return;
        }

        byte[] passwordBytes = password.getBytes();
        String passwordName = IdNameUtils.makePasswordHashName(passwordBytes);

        ConfigureManager configureManager = ConfigureManager.getInstance();
        Map<String, Configure> configMap = configureManager.loadConfigMap(this);
        if(configMap.get(passwordName)!=null){
            ToastUtils.makeText(this,getString(R.string.this_password_existed));
            return;
        }

        // No existing password/config means this is a genuine first-time user.
        // Capture it before we store the new config below.
        boolean isFirstUser = configMap.isEmpty();

        // Create new Configure object
        Configure configure = new Configure();
        configure.makeSymkeyFromPassword(passwordBytes);
        configure.setPasswordName(passwordName);

        // Get DatabaseManager instance
        DatabaseManager databaseManager = DatabaseManager.getInstance();
        databaseManager.setCurrentPasswordName(passwordName);

        // Store the Configure object in ConfigureManager
        ConfigureManager.getInstance().setConfigure(configure);
        ConfigureManager.getInstance().storeConfigure(this, configure);

        // Brand-new user (no prior password/config): auto-create a random FID and
        // drop them straight onto Home, skipping the otherwise-empty identity chooser.
        // A first-time user is never in the background-timeout flow, so handle it here.
        if (isFirstUser) {
            bootstrapFirstIdentityAndLaunchHome(configure);
            return;
        }

        // Check if this is from background timeout
        boolean fromBackgroundTimeout = getIntent().getBooleanExtra(FROM_BACKGROUND_TIMEOUT, false);

        if (fromBackgroundTimeout) {
            // Clean up old session before proceeding
            cleanupOldSession();

            // Launch ChooseCidActivity directly
            Intent intent = new Intent(this, ChooseCidActivity.class);
            intent.putExtra(FROM_NEW_PASSWORD, true);
            startActivity(intent);

            // Close CheckPasswordActivity and CreatePasswordActivity, leaving only ChooseCidActivity
            finishAffinity();
        } else {
            setResult(RESULT_OK);
            finish();
        }
    }

    /**
     * Bootstraps a brand-new user: generates a random FID, creates its Setting,
     * sets it as the current identity and launches HomeActivity directly, clearing
     * the auth stack. This mirrors ChooseCidActivity's from-new-password path but
     * skips the (empty) identity chooser entirely. On any failure it falls back to
     * the normal flow so the user can still create an identity manually.
     */
    private void bootstrapFirstIdentityAndLaunchHome(Configure configure) {
        try {
            byte[] symkey = configure.getSymkey();
            KeyInfo keyInfo = KeyInfo.createNew(symkey);

            // getOrCreateSetting also adds the KeyInfo to the configure's
            // mainCidInfoMap and persists it.
            SettingManager settingManager = SettingManager.getInstance();
            Setting setting = settingManager.getOrCreateSetting(this, keyInfo);
            if (setting == null) {
                throw new IllegalStateException("Setting creation returned null");
            }

            settingManager.setCurrentSetting(setting);
            TimberLogger.d(TAG, "Bootstrapped first identity for new user: %s", keyInfo.getId());

            Intent homeIntent = new Intent(this, com.fc.freer.home.HomeActivity.class);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(homeIntent);
            finish();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to bootstrap first identity, falling back to chooser: " + e.getMessage(), e);
            // Fall back to the normal flow: return OK so the caller opens ChooseCidActivity.
            setResult(RESULT_OK);
            finish();
        }
    }

    /**
     * Clean up all resources from the old password session
     * This includes FidManager, ApiCenter, and SettingManager
     */
    private void cleanupOldSession() {
        TimberLogger.d(TAG, "Cleaning up old password session");

        try {
            // 1. Clean up FidManager (closes all managers for old mainFid)
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            if (fidManager != null) {
                fidManager.fullCleanup();
                TimberLogger.d(TAG, "FidManager cleaned up");
            }

            // 2. Clean up ApiCenter (closes all API clients)
            com.fc.freer.utils.ApiCenter apiCenter = com.fc.freer.utils.ApiCenter.getInstance();
            if (apiCenter != null) {
                apiCenter.closeAllClients();
                TimberLogger.d(TAG, "ApiCenter cleaned up");
            }

            // 3. Clear current setting in SettingManager
            com.fc.freer.initiate.SettingManager settingManager = com.fc.freer.initiate.SettingManager.getInstance();
            if (settingManager != null) {
                settingManager.clearCurrentSetting();
                TimberLogger.d(TAG, "SettingManager current setting cleared");
            }

            // 4. Clear AvatarManager cache (optional, but good practice)
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

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }
} 