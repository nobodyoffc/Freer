package com.fc.freer.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.utils.FidCardHelper;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.qr.QrCodeActivity;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.KeyboardUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.freer.manager.CashManager;
import com.fc.freer.tx.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.network.ApipClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.utils.StringUtils;
import android.graphics.Bitmap;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.List;


public abstract class BaseCryptoActivity extends AppCompatActivity {
    protected ActivityResultLauncher<Intent> qrScanLauncher;
    protected ActivityResultLauncher<Intent> chooseKeyLauncher;
    protected TextView resultTextView;
    protected Button clearButton;
    protected Button copyButton;
    protected Button convertButton;
    protected String convertedData;
    private static final String TAG = "BaseCryptoActivity";
    private UserConfirmDialog activeDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Configure logging to filter GMS logs
//        TimberLogger.configureLogging(true);

        setContentView(getLayoutId());
        
        // Initialize activity result launchers
        setupActivityResultLaunchers();
        
        // Set up toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());
        
        // Set up FID/CID display in toolbar
        setupToolbarFidDisplay();
        
        // Set up keyboard hiding
        setupKeyboardHiding();
        
        // Set up back button handling
        setupBackButton();
        
        // Initialize views
        initializeViews();
        
        // Setup buttons
        setupButtons();
    }

    @Override
    protected void onDestroy() {
        // Dismiss any active dialog to prevent window leaks
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        super.onDestroy();
    }

    /**
     * Sets up the FID/CID display in the toolbar
     */
    protected void setupToolbarFidDisplay() {
        LinearLayout toolbarFidContainer = findViewById(R.id.toolbar_fid_container);
        TextView toolbarFidText = findViewById(R.id.toolbar_fid_text);
        ImageView toolbarAvatar = findViewById(R.id.toolbar_avatar);
        
        if (toolbarFidContainer == null || toolbarFidText == null || toolbarAvatar == null) {
            // Views not found, skip setup
            return;
        }
        
        try {
            // Get living KeyInfo from FidManager
            FidManager fidManager = FidManager.getInstance();
            KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
            
            if (liveKeyInfo != null && liveKeyInfo.getId() != null) {
                String id;
                id = liveKeyInfo.getCid();
                if(id==null)id = liveKeyInfo.getId();
                
                // Constrain FID to 15 characters using StringUtils.omitMiddle
                String displayFid = id;
                if(id.length()>15) displayFid = StringUtils.omitMiddle(id, 15);
                
                // Set the FID text
                toolbarFidText.setText(displayFid);
                
                // Set up avatar
                setupToolbarAvatar(toolbarAvatar, liveKeyInfo.getId());
                
                // Set up click listener for the container
                String finalId = id;
                toolbarFidContainer.setOnClickListener(v -> {
                    // Show avatar dialog when clicked
                    AvatarManager.showAvatarDialog(this, liveKeyInfo.getId());
                });
                
                // Make the container visible
                toolbarFidContainer.setVisibility(View.VISIBLE);
                
                TimberLogger.d(TAG, "Toolbar FID display set up for FID: " + displayFid);
            } else {
                // No live KeyInfo, hide the container
                toolbarFidContainer.setVisibility(View.GONE);
                TimberLogger.d(TAG, "No live KeyInfo available, hiding toolbar FID display");
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up toolbar FID display: %s", e.getMessage());
            // Hide container on error
            toolbarFidContainer.setVisibility(View.GONE);
        }
    }
    
    /**
     * Sets up the avatar in the toolbar
     */
    private void setupToolbarAvatar(ImageView avatarView, String fid) {
        try {
            TimberLogger.d(TAG, "Setting up toolbar avatar for FID: %s", fid);
            
            // Set initial background while avatar loads
            avatarView.setBackgroundColor(getResources().getColor(R.color.colorAccent, getTheme()));
            
            // Get avatar manager instance
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            
            // Get avatar bitmap in background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    TimberLogger.d(TAG, "Generating avatar bitmap for FID: %s", fid);
                    Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
                    
                    // Update UI on main thread
                    runOnUiThread(() -> {
                        if (avatarBitmap != null && !isFinishing() && !isDestroyed()) {
                            TimberLogger.d(TAG, "Successfully set avatar bitmap for FID: %s", fid);
                            avatarView.setImageBitmap(avatarBitmap);
                            // Clear background color when bitmap is set
                            avatarView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                        } else {
                            TimberLogger.w(TAG, "Avatar bitmap is null for FID: %s", fid);
                        }
                    });
                    
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error generating avatar for toolbar: %s", e.getMessage());
                }
            }).start();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up toolbar avatar: %s", e.getMessage());
        }
    }

    protected abstract int getLayoutId();
    protected abstract String getActivityTitle();
    protected abstract void initializeViews();

    protected void handleQrGeneration(String resultText) {
        if (resultText == null) {
            showToast(getString(R.string.please_input_text));
            return;
        }

        if (!TextUtils.isEmpty(resultText)) {
            IoIconsView.launchQrGenerator(this, resultText);
        } else {
            showToast(getString(R.string.no_content_to_generate_qr_code));
        }
    }

    protected abstract void setupButtons();

    private void setupBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void setupKeyboardHiding() {
        // Use the existing KeyboardUtils method for the root layout
        KeyboardUtils.setupKeyboardHiding(this);

        // Set up keyboard hiding for all container views
        ViewGroup rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            setupKeyboardHidingForViewGroup(rootView);
        }
    }

    private void setupKeyboardHidingForViewGroup(ViewGroup viewGroup) {
        // Set up touch listener for the current view group
        viewGroup.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Only hide keyboard if the view is not an EditText or TextInputEditText
                if (!(v instanceof EditText)) {
                    hideKeyboard();
                    v.performClick();
                }
            }
            return false;
        });

        // Recursively set up touch listeners for all child views
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                setupKeyboardHidingForViewGroup((ViewGroup) child);
            } else if (!(child instanceof EditText)) {
                child.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        hideKeyboard();
                        // Only call performClick for non-clickable views to avoid double-clicks
                        if (!v.isClickable() && !v.hasOnClickListeners()) {
                            v.performClick();
                        }
                    }
                    return false;
                });
            }
        }
    }

    protected void setupActivityResultLaunchers() {
        // QR scan launcher
        qrScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String qrContent = result.getData().getStringExtra("qr_content");
                    if (qrContent != null) {
                        handleQrScanResult(result.getData().getIntExtra("request_code", 0), qrContent);
                    }
                }
            }
        );

        // Choose key launcher
        chooseKeyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleChooseKeyResult(result.getData());
                }
            }
        );
    }

    protected abstract void handleQrScanResult(int requestCode, String qrContent);

    protected void handleChooseKeyResult(Intent data) {
        // This method can be overridden by subclasses to handle the key selection result
        TimberLogger.i(TAG, "Key selection result received");
    }

    public void setupIoIconsView(int containerId, int iconId, boolean showMakeQr, boolean showPeople,
                                  boolean showScan, boolean showFile,
                                 IoIconsView.OnMakeQrClickListener makeQrListener,
                                  IoIconsView.OnPeopleClickListener peopleListener,
                                  IoIconsView.OnScanClickListener scanListener,
                                  IoIconsView.OnFileClickListener fileListener) {
        View container = findViewById(containerId);
        IoIconsView icons = container.findViewById(iconId);
        if (icons != null) {
            icons.init(this, showMakeQr, showPeople, showScan, showFile);
            if (makeQrListener != null) icons.setOnMakeQrClickListener(makeQrListener);
            if (peopleListener != null) icons.setOnPeopleClickListener(peopleListener);
            if (scanListener != null) icons.setOnScanClickListener(scanListener);
            if (fileListener != null) icons.setOnFileClickListener(fileListener);
        }
    }

    public void startQrScan(int requestCode) {
        Intent intent = new Intent(this, QrCodeActivity.class);
        intent.putExtra("is_return_string", true);
        intent.putExtra("request_code", requestCode);
        qrScanLauncher.launch(intent);
    }

    protected void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast(getString(R.string.copied));
    }

    protected void showToast(String message) {
        Toast.makeText(this, message, FreerApplication.TOAST_LASTING).show();
    }

    protected void clearInput(TextInputEditText input) {
        input.setText("");
        input.setEnabled(true);
        input.setTextColor(getResources().getColor(R.color.text_color, getTheme()));
        input.setTag(null);
    }

    protected void setupButton(Button button, View.OnClickListener listener) {
        button.setOnClickListener(listener);
    }

    protected void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    protected void updateResultText(String result) {
        resultTextView.setVisibility(View.VISIBLE);

        if (result == null) {
            resultTextView.setText("");
            copyButton.setEnabled(false);
            convertedData = null;
            return;
        }

        convertedData = result;
        resultTextView.setText(convertedData);
    }

    protected void copyConvertedDataToClipboard() {
        if (convertedData != null) {
            copyToClipboard(convertedData, "Converted Data");
        }
    }


    protected void setupTextIcons(int textView, int textIcons, int QR_SCAN_TEXT_REQUEST_CODE) {
        TextIconsUtils.setupTextIcons(this, textView, textIcons, QR_SCAN_TEXT_REQUEST_CODE);
    }


    protected void setupResultIcons(int resultView, int resultIcons, IoIconsView.OnMakeQrClickListener makeQrListener) {
        setupIoIconsView(resultView, resultIcons, true, false, false, false,
                makeQrListener, null, null, null);
    }

    protected void finishWithKeyInfo(KeyInfo keyInfo) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("key-info", keyInfo.toJson());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    protected void saveAndFinishWithKeyInfo(KeyInfo keyInfo) {
        try {
            ConfigureManager configureManager = ConfigureManager.getInstance();
            com.fc.freer.model.Configure configure = configureManager.getConfigure();
            
            if (configure == null) {
                Toast.makeText(this, "Configuration not found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Ensure mainCidInfoMap is initialized
            if (configure.getMainCidInfoMap() == null) {
                configure.setMainCidInfoMap(new HashMap<>());
            }
            
            // Check if keyInfo already exists
            if (configure.getMainCidInfoMap().containsKey(keyInfo.getId())) {
                // Key already exists, show confirmation dialog
                showKeyExistsDialog(keyInfo, configure, configureManager);
            } else {
                // Key doesn't exist, save directly
                performSaveAndFinish(keyInfo, configure, configureManager);
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving KeyInfo: %s", e.getMessage());
            Toast.makeText(this, "Error saving key: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void showKeyExistsDialog(KeyInfo keyInfo, com.fc.freer.model.Configure configure, ConfigureManager configureManager) {
        // Dismiss any existing dialog first
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        
        // Use runOnUiThread to ensure dialog is shown on UI thread after any pending operations
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                activeDialog = new UserConfirmDialog(
                    this,
                    getString(R.string.key_already_exists_title),
                    getString(R.string.key_already_exists_message),
                    choice -> {
                        activeDialog = null; // Clear reference
                        switch (choice) {
                            case YES:
                                // User confirmed replacement
                                performSaveAndFinish(keyInfo, configure, configureManager);
                                break;
                            case NO:
                            case STOP:
                                // User cancelled, do nothing
                                TimberLogger.d(TAG, "User cancelled key replacement");
                                break;
                        }
                    },
                    true // Show as warning
                );
                
                // Show dialog with a slight delay to ensure activity is fully ready
                activeDialog.getWindow().getDecorView().post(() -> {
                    if (!isFinishing() && !isDestroyed() && activeDialog != null) {
                        activeDialog.show();
                        TimberLogger.d(TAG, "Key exists dialog shown for key: " + keyInfo.getId());
                    }
                });
            }
        });
    }
    
    private void performSaveAndFinish(KeyInfo keyInfo, com.fc.freer.model.Configure configure, ConfigureManager configureManager) {
        try {
            // Ensure mainCidInfoMap is initialized
            if (configure.getMainCidInfoMap() == null) {
                configure.setMainCidInfoMap(new HashMap<>());
            }
            configure.getMainCidInfoMap().put(keyInfo.getId(), keyInfo);
            configureManager.storeConfigure(this, configure);
            
            Toast.makeText(this, getString(R.string.key_saved_successfully), Toast.LENGTH_SHORT).show();
            
            Intent resultIntent = new Intent();
            resultIntent.putExtra("key-info", keyInfo.toJson());
            setResult(RESULT_OK, resultIntent);
            finish();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving KeyInfo: %s", e.getMessage());
            Toast.makeText(this, "Error saving key: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    protected void saveToConfigureAndFinish(List<KeyInfo> keyInfoList) {
        try {
            // Get current configure
            ConfigureManager configureManager = ConfigureManager.getInstance();
            com.fc.freer.model.Configure configure = configureManager.getConfigure();

            if (configure == null) {
                Toast.makeText(this, "Configuration not found", Toast.LENGTH_SHORT).show();
                return;
            }

            // Add all selected keys to configure's mainCidInfoMap
            if(configure.getMainCidInfoMap()==null)configure.setMainCidInfoMap(new HashMap<>());
            for (KeyInfo keyInfo : keyInfoList) {
                configure.getMainCidInfoMap().put(keyInfo.getId(), keyInfo);
            }

            // Save configure
            configureManager.storeConfigure(this, configure);

            Toast.makeText(this, getString(R.string.key_saved_successfully), Toast.LENGTH_SHORT).show();

            // Return result based on number of selected keys
            Intent resultIntent = new Intent();
            if (keyInfoList.size() == 1) {
                // If only one key selected, return it as key_info
                resultIntent.putExtra("key_info", keyInfoList.get(0).toJson());
            }
            // If multiple keys, just return RESULT_OK without extras
            setResult(RESULT_OK, resultIntent);
            finish();

        } catch (Exception e) {
            Toast.makeText(this, "Error saving key: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Creates and configures a FID card view using the reusable layout
     * @param keyInfo The KeyInfo object to display
     * @param parent The parent view to add the card to (can be null if you want to handle adding manually)
     * @param enableClickListeners Whether to enable click listeners for copying and avatar display
     * @return The configured card view
     */
    protected View createFidCard(KeyInfo keyInfo, ViewGroup parent, boolean enableClickListeners) {
        return FidCardHelper.createFidCard(this, keyInfo, parent, enableClickListeners, createDefaultFidCardClickListener());
    }

    /**
     * Creates a FID card for a multisign ID (when you only have the ID, not full KeyInfo)
     * @param multisignId The multisign ID to display
     * @param parent The parent view to add the card to (can be null if you want to handle adding manually)
     * @param enableClickListeners Whether to enable click listeners for copying and avatar display
     * @return The configured card view
     */
    protected View createMultisignFidCard(String multisignId, ViewGroup parent, boolean enableClickListeners) {
        return FidCardHelper.createMultisignFidCard(this, multisignId, parent, enableClickListeners, createDefaultMultisignCardClickListener());
    }

    /**
     * Creates a default click listener for FID cards that provides basic functionality
     */
    protected FidCardHelper.FidCardClickListener createDefaultFidCardClickListener() {
        return new FidCardHelper.FidCardClickListener() {
            @Override
            public void onAvatarClick(String fid) {
                com.fc.freer.manager.AvatarManager.showAvatarDialog(BaseCryptoActivity.this, fid);
            }

            @Override
            public void onNameClick(String fid) {
                copyToClipboard(fid, "fid");
            }
        };
    }

    /**
     * Creates a default click listener for multisign cards that provides basic functionality
     */
    protected FidCardHelper.MultisignCardClickListener createDefaultMultisignCardClickListener() {
        return new FidCardHelper.MultisignCardClickListener() {
            @Override
            public void onAvatarClick(String multisignId) {
                com.fc.freer.manager.AvatarManager.showAvatarDialog(BaseCryptoActivity.this, multisignId);
            }

            @Override
            public void onNameClick(String multisignId) {
                copyToClipboard(multisignId, "multisign_id");
            }
        };
    }

    /**
     * Callback interface for transaction sending operations
     */
    public interface TxSendCallback {
        void onSuccess(String txId);
        void onError(String errorMessage);
        void onUnsignedTx(RawTxInfo rawTxInfo);
    }

    /**
     * Shared method to send a transaction using the private key of the sender
     * @param rawTxInfo The transaction information to send
     * @param senderKeyInfo The sender's key information
     * @param callback Callback to handle the result
     */
    protected void sendTransaction(RawTxInfo rawTxInfo, KeyInfo senderKeyInfo, TxSendCallback callback) {
        byte[] priKey = SecurePrikeyManager.fetchPrikeySilent(senderKeyInfo.getPrikeyCipher());
        if (priKey != null) {
            // Get required instances
            CashManager cashManager = CashManager.getInstance();
            TxHandler txHandler = new TxHandler();
            ApipClient apipClient = (ApipClient) ApiCenter.getInstance().getClient(Service.ServiceType.APIP);

            // Use the TxSender to sign and send the transaction
            TxSender.sendTx(this, rawTxInfo, priKey, cashManager, txHandler, apipClient, new TxSender.TxCallback() {
                @Override
                public void onSuccess(String txId) {
                    runOnUiThread(() -> callback.onSuccess(txId));
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> callback.onError(errorMessage));
                }

                @Override
                public void onUnsignedTx(RawTxInfo rawTxInfo) {
                    runOnUiThread(() -> callback.onUnsignedTx(rawTxInfo));
                }
            });

            // Show progress message
            showToast(getString(R.string.sending_transaction));

        } else {
            callback.onError(getString(R.string.failed_to_get_private_key));
        }
    }

    /**
     * Shared method to broadcast an already signed transaction
     * @param signedTxHex The signed transaction in hex format
     * @param callback Callback to handle the result
     */
    protected void broadcastTransaction(String signedTxHex, TxSendCallback callback) {
        ApipClient apipClient = (ApipClient) ApiCenter.getInstance().getClient(Service.ServiceType.APIP);
        
        // Show progress message
        showToast(getString(R.string.sending_transaction));

        // Broadcast in background thread
        new Thread(() -> {
            try {
                String result = apipClient.broadcastTx(signedTxHex, com.fc.fc_ajdk.utils.http.RequestMethod.POST, 
                    com.fc.fc_ajdk.utils.http.AuthType.FC_SIGN_BODY, this);
                
                runOnUiThread(() -> {
                    if (com.fc.fc_ajdk.utils.Hex.isHex32(result)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError("Transaction broadcast failed: " + result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> callback.onError("Failed to broadcast transaction: " + e.getMessage()));
            }
        }).start();
    }
}