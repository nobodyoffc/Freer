package com.fc.freer;

import static com.fc.freer.initiate.ChooseCidActivity.SELECTED_KEY_INFO_JSON;

import android.content.Intent;
import android.os.Bundle;

import com.fc.freer.ui.RemindDialog;
import com.fc.freer.utils.ToastUtils;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.home.HomeActivity;
import com.fc.freer.initiate.CheckPasswordActivity;
import com.fc.freer.initiate.CreatePasswordActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.ChooseCidActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "MainActivity";
    private static final String KEY_HAS_LAUNCHED_PASSWORD = "has_launched_password";
    private ActivityResultLauncher<Intent> cidLauncher;
    private ActivityResultLauncher<Intent> passwordLauncher;
    private boolean hasLaunchedPasswordActivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        setupEdgeToEdge();

        // Restore the launch guard so a recreated MainActivity (process death during
        // a long background sleep, or a configuration change, while CheckPassword /
        // ChooseCid is on top) does not start a second CheckPasswordActivity. The
        // pending activity result from the original auth screen is redelivered after
        // recreation and drives the flow forward on its own; launching again here
        // stacks duplicate password/chooser screens.
        if (savedInstanceState != null) {
            hasLaunchedPasswordActivity =
                    savedInstanceState.getBoolean(KEY_HAS_LAUNCHED_PASSWORD, false);
        }

        // Initialize passwordLauncher
        passwordLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handlePasswordResult
        );

        // Initialize cidLauncher
        cidLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleCidResult
        );

        initiate();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_HAS_LAUNCHED_PASSWORD, hasLaunchedPasswordActivity);
    }

    private void setupEdgeToEdge() {
        try {
            EdgeToEdge.enable(this);
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Failed to enable EdgeToEdge: " + ex.getMessage(), ex);
        }
    }

    private void initiate() {
        if (!hasLaunchedPasswordActivity) {
            hasLaunchedPasswordActivity = true;
            Intent checkPasswordIntent = new Intent(this, CheckPasswordActivity.class);
            checkPasswordIntent.putExtra("allow_back_navigation", false);

            passwordLauncher.launch(checkPasswordIntent);
        }
    }

    private void handlePasswordResult(androidx.activity.result.ActivityResult result) {
        String activityName = result.getData() != null ?
            Objects.requireNonNull(result.getData().getComponent()).getClassName() : "Unknown";

        if (result.getResultCode() == RESULT_OK) {
            Configure configure = ConfigureManager.getInstance().getConfigure();
            if (configure == null) {
                handleError("Configure object not found in ConfigureManager");
                return;
            }

            // Launch ChooseCidActivity to select an identity
            Intent cidIntent = new Intent(this, ChooseCidActivity.class);
            cidLauncher.launch(cidIntent);
        } else {
            // User cancelled password verification during initial authentication
            // Close the app as authentication is required to proceed
            TimberLogger.d(TAG, "Password verification cancelled, closing app");
            finishAffinity();
        }
    }

    private void handleCidResult(androidx.activity.result.ActivityResult result) {
        if (result.getResultCode() == RESULT_OK) {
            // Get the selected KeyInfo from the result
            Intent data = result.getData();
            if (data != null && data.hasExtra(SELECTED_KEY_INFO_JSON)) {
                String keyInfoJson = data.getStringExtra(SELECTED_KEY_INFO_JSON);
                KeyInfo selectedKeyInfo = KeyInfo.fromJson(keyInfoJson, KeyInfo.class);
                if (selectedKeyInfo != null) {
                    // Get or create Setting for the selected KeyInfo
                    try {
                        SettingManager settingManager = SettingManager.getInstance();
                        Setting setting = settingManager.getOrCreateSetting(this, selectedKeyInfo);
                        if (setting != null) {
                            TimberLogger.d(TAG, "Successfully got or created setting for FID: " + setting.getFid());

                            // Set as current setting
                            settingManager.setCurrentSetting(setting);
                            TimberLogger.d(TAG, "Set current setting to FID: " + setting.getFid());

                            // Launch HomeActivity immediately - it will load modules there
                            launchHomeActivity();
                        } else {
                            handleError("Failed to get or create setting for selected CID");
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error getting or creating settings: " + e.getMessage(), e);
                        handleError("Error processing selected CID: " + e.getMessage());
                    }
                } else {
                    handleError("Selected KeyInfo is null");
                }
            } else {
                handleError("No KeyInfo data received from CID selection");
            }
        } else {
            // User cancelled CID selection, return to CheckPasswordActivity
            TimberLogger.d(TAG, "CID selection cancelled, returning to password verification");
            Intent intent = new Intent(this, CheckPasswordActivity.class);
            // Allow back navigation from CheckPasswordActivity
            intent.putExtra("allow_back_navigation", true);
            passwordLauncher.launch(intent);
        }
    }


    private void handleError(String message) {
        TimberLogger.e(TAG, message);
        ToastUtils.makeText(this, getString(R.string.failed_to_initiate));
    }

    private void launchHomeActivity() {
        try {
            Intent intent = new Intent(this, HomeActivity.class);
            // Clear the entire activity stack when launching HomeActivity
            // This ensures a clean state and prevents back navigation to authentication screens
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish(); // Close MainActivity
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error launching HomeActivity: " + ex.getMessage(), ex);
            ToastUtils.makeText(this, getString(R.string.error_launching_homeactivity) + ex.getMessage());
        }
    }



}