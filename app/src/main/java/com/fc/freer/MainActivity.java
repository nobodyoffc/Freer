package com.fc.freer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

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
    public static final String TAG = "CryptoSign";
    private ActivityResultLauncher<Intent> cidLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        
        setupEdgeToEdge();
        
        // Initialize cidLauncher
        cidLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleCidResult
        );
        
        initiate();
    }

    private void setupEdgeToEdge() {
        try {
            EdgeToEdge.enable(this);
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Failed to enable EdgeToEdge: " + ex.getMessage(), ex);
        }
    }

    private void initiate() {
        ActivityResultLauncher<Intent> passwordLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handlePasswordResult
        );
        
        Intent intent;
        
        // Check if there are any Configure objects in ConfigureManager
        if (ConfigureManager.getInstance().isConfigEmpty(this)) {
            intent = new Intent(this, CreatePasswordActivity.class);
        } else {
            intent = new Intent(this, CheckPasswordActivity.class);
        }
        
        passwordLauncher.launch(intent);
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
            handleError(activityName + " failed with result code: " + result.getResultCode());
        }
    }

    private void handleCidResult(androidx.activity.result.ActivityResult result) {
        if (result.getResultCode() == RESULT_OK) {
            // Get the selected KeyInfo from the result
            Intent data = result.getData();
            if (data != null && data.hasExtra("selected_key_info_json")) {
                String keyInfoJson = data.getStringExtra("selected_key_info_json");
                KeyInfo selectedKeyInfo = KeyInfo.fromJson(keyInfoJson, KeyInfo.class);
                if (selectedKeyInfo != null) {
                    // Get or create Setting for the selected KeyInfo
                    try {
                        SettingManager settingManager = SettingManager.getInstance();
                        Setting setting = settingManager.getOrCreateSetting(this, selectedKeyInfo);
                        if (setting != null) {
                            TimberLogger.d(TAG, "Successfully got or created setting for FID: " + setting.getFid());
                            
                            // Set as current setting
                            settingManager.setCurrentSetting(this, setting);
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
            // User cancelled CID selection, show error
            handleError("No CID selected");
        }
    }


    private void handleError(String message) {
        TimberLogger.e(TAG, message);
        Toast.makeText(this, getString(R.string.failed_to_initiate), Toast.LENGTH_SHORT).show();
    }

    private void launchHomeActivity() {
        try {
            Intent intent = new Intent(this, HomeActivity.class);
            startActivity(intent);
            finish(); // Close MainActivity
        } catch (Exception ex) {
            TimberLogger.e(TAG, "Error launching HomeActivity: " + ex.getMessage(), ex);
            Toast.makeText(this, getString(R.string.error_launching_homeactivity) + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }



}