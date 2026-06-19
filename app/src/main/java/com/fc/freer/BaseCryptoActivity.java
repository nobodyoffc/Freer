package com.fc.freer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.fc.freer.utils.ToastUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.utils.FidCardHelper;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.qr.QrCodeActivity;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.KeyboardUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.freer.manager.CashManager;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.im.ImManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;

import android.graphics.Bitmap;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.List;


public abstract class BaseCryptoActivity extends AppCompatActivity {
    public static final String KEY_INFO = "key_info";
    protected ActivityResultLauncher<Intent> qrScanLauncher;
    protected ActivityResultLauncher<Intent> chooseKeyLauncher;
    protected TextView resultTextView;
    protected ImageButton clearButton;
    protected ImageButton copyButton;
    protected ImageButton convertButton;
    protected ImageButton backButton;
    protected String convertedData;
    private static final String TAG = "BaseCryptoActivity";
    private UserConfirmDialog activeDialog;
    private boolean sessionRedirected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Guard against process death while backgrounded: if the in-memory
        // session was lost, redirect to re-authentication before touching any
        // session-dependent state (which would otherwise NPE on resume).
        if (!com.fc.freer.utils.SessionGuard.ensureSession(this)) {
            sessionRedirected = true;
            return;
        }

        // Configure logging to filter GMS logs

        setContentView(getLayoutId());
        
        // Initialize activity result launchers
        setupActivityResultLaunchers();
        
        // Set up toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());
        
        // Set up FID/CID display in toolbar
        setupToolbarFidDisplay();
        
        // Set up keyboard hiding
        setupKeyboardHiding();
        
        // Initialize views
        initializeViews();

        // Setup buttons
        setupButtons();

        // Setup back button (if present in layout)
        setupBackButton();
    }

    /**
     * @return true if {@link #onCreate(Bundle)} aborted setup because the
     *         session was lost and the activity is being redirected to
     *         re-authentication. Subclasses that perform their own work in
     *         onCreate AFTER calling {@code super.onCreate(...)} must check this
     *         and return early, because views and session state were not
     *         initialized.
     */
    protected boolean isSessionRedirected() {
        return sessionRedirected;
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

                // Set the FID text
                toolbarFidText.setText(id);
                
                // Set up avatar
                setupToolbarAvatar(toolbarAvatar, liveKeyInfo.getId());
                
                // Set up click listener for the avatar to finish current activity and go to HomeActivity
                toolbarAvatar.setOnClickListener(v -> {
                    Intent intent = new Intent(this, com.fc.freer.home.HomeActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                });
                
                // Set up click listener for the container
                toolbarFidContainer.setOnClickListener(v -> {
                    // Copy the live FID ID to clipboard when clicked
                    copyToClipboard(liveKeyInfo.getId(), "FID");
                });
                
                // Make the container visible
                toolbarFidContainer.setVisibility(View.VISIBLE);
                
                TimberLogger.d(TAG, "Toolbar FID display set up for FID: " + id);
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
     * Sets up the back button (if present in layout)
     * The back button hides the keyboard and finishes the activity
     */
    protected void setupBackButton() {
        backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
            TimberLogger.d(TAG, "Back button set up successfully");
        }
    }

    /**
     * Sets up the avatar in the toolbar
     */
    private void setupToolbarAvatar(ImageView avatarView, String fid) {
        try {
            TimberLogger.d(TAG, "Setting up toolbar avatar for FID: %s", fid);
            
            // Set initial background while avatar loads
            avatarView.setBackgroundColor(getResources().getColor(R.color.accent, getTheme()));
            
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
                    // Only call performClick for non-clickable views to avoid double-clicks
                    if (!v.isClickable() && !v.hasOnClickListeners()) {
                        v.performClick();
                    }
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
                                 boolean showScan, boolean showPaste, boolean showFile,
                                 IoIconsView.OnMakeQrClickListener makeQrListener,
                                 IoIconsView.OnPeopleClickListener peopleListener,
                                 IoIconsView.OnPasteClickListener pasteListener, IoIconsView.OnScanClickListener scanListener, IoIconsView.OnFileClickListener fileListener) {
        View container = findViewById(containerId);
        IoIconsView icons = container.findViewById(iconId);
        if (icons != null) {
            icons.init(this, showMakeQr, showPeople, showScan, showPaste, showFile);
            if (makeQrListener != null) icons.setOnMakeQrClickListener(makeQrListener);
            if (peopleListener != null) icons.setOnPeopleClickListener(peopleListener);
            if (scanListener != null) icons.setOnScanClickListener(scanListener);
            if (pasteListener != null) icons.setOnPasteClickListener(pasteListener);
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

    /**
     * Get text from clipboard
     * @return The text from clipboard, or null if clipboard is empty or doesn't contain text
     */
    protected String getTextFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                ClipData.Item item = clipData.getItemAt(0);
                CharSequence text = item.getText();
                if (text != null) {
                    return text.toString();
                }
            }
        }
        return null;
    }

    /**
     * Paste text from clipboard to an EditText
     * @param editText The EditText to paste into
     */
    public void pasteFromClipboard(EditText editText) {
        String clipboardText = getTextFromClipboard();
        if (clipboardText != null && !clipboardText.isEmpty()) {
            editText.setText(clipboardText);
            showToast(getString(R.string.pasted));
        } else {
            showToast(getString(R.string.clipboard_is_empty));
        }
    }

    /**
     * Paste text from clipboard to a TextInputEditText
     * @param editText The TextInputEditText to paste into
     */
    protected void pasteFromClipboard(TextInputEditText editText) {
        String clipboardText = getTextFromClipboard();
        if (clipboardText != null && !clipboardText.isEmpty()) {
            editText.setText(clipboardText);
            showToast(getString(R.string.pasted));
        } else {
            showToast(getString(R.string.clipboard_is_empty));
        }
    }

    protected void showToast(String message) {
        ToastUtils.makeText(this, message);
    }

    protected void clearInput(TextInputEditText input) {
        input.setText("");
        input.setEnabled(true);
        input.setTextColor(getResources().getColor(R.color.text, getTheme()));
        input.setTag(null);
    }

    protected void setupButton(ImageButton button, View.OnClickListener listener) {
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
            if (copyButton != null) {
                copyButton.setEnabled(false);
            }
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
        setupIoIconsView(resultView, resultIcons, true, false, false,false ,
                false, makeQrListener, null, null, null, null);
    }

    protected void finishWithKeyInfo(KeyInfo keyInfo) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(KEY_INFO, keyInfo.toJson());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    protected void saveAndFinishWithKeyInfo(KeyInfo keyInfo) {
        try {
            ConfigureManager configureManager = ConfigureManager.getInstance();
            com.fc.freer.model.Configure configure = configureManager.getConfigure();
            
            if (configure == null) {
                ToastUtils.makeText(this, "Configuration not found");
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
            ToastUtils.makeText(this, "Error saving key: " + e.getMessage());
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
            
            ToastUtils.makeText(this, getString(R.string.key_saved_successfully));
            
            Intent resultIntent = new Intent();
            resultIntent.putExtra(KEY_INFO, keyInfo.toJson());
            setResult(RESULT_OK, resultIntent);
            finish();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving KeyInfo: %s", e.getMessage());
            ToastUtils.makeText(this, "Error saving key: " + e.getMessage());
        }
    }

    protected void saveToConfigureAndFinish(List<KeyInfo> keyInfoList) {
        try {
            // Get current configure
            ConfigureManager configureManager = ConfigureManager.getInstance();
            com.fc.freer.model.Configure configure = configureManager.getConfigure();

            if (configure == null) {
                ToastUtils.makeText(this, "Configuration not found");
                return;
            }

            // Add all selected keys to configure's mainCidInfoMap
            if(configure.getMainCidInfoMap()==null)configure.setMainCidInfoMap(new HashMap<>());
            for (KeyInfo keyInfo : keyInfoList) {
                configure.getMainCidInfoMap().put(keyInfo.getId(), keyInfo);
            }

            // Save configure
            configureManager.storeConfigure(this, configure);

            ToastUtils.makeText(this, getString(R.string.key_saved_successfully));

            // Return result based on number of selected keys
            Intent resultIntent = new Intent();
            if (keyInfoList.size() == 1) {
                // If only one key selected, return it as key_info
                resultIntent.putExtra(KEY_INFO, keyInfoList.get(0).toJson());
            }
            // If multiple keys, just return RESULT_OK without extras
            setResult(RESULT_OK, resultIntent);
            finish();

        } catch (Exception e) {
            ToastUtils.makeText(this, "Error saving key: " + e.getMessage());
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
     * Creates a FID card for a multisig ID (when you only have the ID, not full KeyInfo)
     * @param multisignId The multisig ID to display
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
     * Creates a default click listener for multisig cards that provides basic functionality
     */
    protected FidCardHelper.MultisigCardClickListener createDefaultMultisignCardClickListener() {
        return new FidCardHelper.MultisigCardClickListener() {
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
        void onUnbroadcasted(String signedTxHex);
    }

    /**
     * Shared method to send a transaction using the private key of the sender
     * @param rawTxInfo The transaction information to send
     * @param senderKeyInfo The sender's key information
     * @param callback Callback to handle the result
     */
    protected void sendTransaction(RawTxInfo rawTxInfo, KeyInfo senderKeyInfo, TxSendCallback callback) {
        byte[] priKey = SecurePrikeyManager.fetchPrikeySilent(senderKeyInfo.getPrikeyCipher());
        // Get required instances
        CashManager cashManager = CashManager.getInstance();
        TxHandler txHandler = new TxHandler();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

        // Use the TxSender to sign and send the transaction
        TxSender txSender = new TxSender();
        txSender.sendTx(this, rawTxInfo, priKey, cashManager, txHandler, fapiClient, new TxSender.TxCallback() {
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

            @Override
            public void onUnbroadcasted(String signedTxHex) {
                runOnUiThread(() -> callback.onUnbroadcasted(signedTxHex));
            }

        });

        // Show progress message
        showToast(getString(R.string.sending_transaction));
    }

    // ==================== SEARCH FUNCTIONALITY ====================

    // Search control fields
    protected EditText searchEditText;
    protected ImageView searchIcon;
    protected ImageView clearSearchIcon;
    protected Spinner searchFieldSpinner;
    protected Spinner sortFieldSpinner;
    protected Spinner sortOrderSpinner;
    protected LinearLayout searchOptionsContainer;

    // Search state
    protected boolean isSearchMode = false;
    protected String currentSearchQuery = null;
    protected String currentSearchField = null;
    protected String currentSortField = null;
    protected String currentSortOrder = null;

    /**
     * Sets up the search controls if they exist in the layout
     * Subclasses can override this to customize behavior
     */
    protected void setupSearchControls() {
        searchEditText = findViewById(R.id.search_edit_text);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        searchFieldSpinner = findViewById(R.id.search_field_spinner);
        sortFieldSpinner = findViewById(R.id.sort_field_spinner);
        sortOrderSpinner = findViewById(R.id.sort_order_spinner);
        searchOptionsContainer = findViewById(R.id.search_options_container);

        if (searchEditText != null && searchIcon != null && clearSearchIcon != null) {
            setupSearchEditText();
        }
    }

    /**
     * Sets up the search EditText with listeners
     */
    protected void setupSearchEditText() {
        // Set up search icon click listener
        searchIcon.setOnClickListener(v -> performSearchAction());

        // Set up clear icon click listener
        clearSearchIcon.setOnClickListener(v -> {
            searchEditText.setText("");
            clearSearchIcon.setVisibility(View.GONE);

            // Exit search mode and reload initial data
            if (isSearchMode) {
                exitSearchMode();
            }
        });

        // Set up text change listener to show/hide clear icon
        searchEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) {
                    clearSearchIcon.setVisibility(View.VISIBLE);
                } else {
                    clearSearchIcon.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // Set up search EditText listeners
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                performSearchAction();
                return true;
            }
            return false;
        });
    }

    /**
     * Clears all search controls and exits search mode
     */
    protected void clearSearchControls() {
        // Clear search input box
        if (searchEditText != null) {
            searchEditText.setText("");
        }
        if (clearSearchIcon != null) {
            clearSearchIcon.setVisibility(View.GONE);
        }

        // Reset spinners to first position (default/none)
        if (searchFieldSpinner != null) {
            searchFieldSpinner.setSelection(0);
        }
        if (sortFieldSpinner != null) {
            sortFieldSpinner.setSelection(0);
        }
        if (sortOrderSpinner != null) {
            sortOrderSpinner.setSelection(0);
        }

        // Exit search mode if active
        if (isSearchMode) {
            exitSearchMode();
        }

        // Hide keyboard
        hideKeyboard();
    }

    /**
     * Exits search mode and reloads initial data
     * Subclasses should override this to implement their specific logic
     */
    protected void exitSearchMode() {
        isSearchMode = false;
        currentSearchQuery = null;
        currentSearchField = null;
        currentSortField = null;
        currentSortOrder = null;

        // Subclasses should override to reload data
        onExitSearchMode();
    }

    /**
     * Called when exiting search mode
     * Subclasses should override this to reload their initial data
     */
    protected void onExitSearchMode() {
        // Override in subclasses
    }

    /**
     * Performs the search action
     * Subclasses should override this to implement their specific search logic
     */
    protected void performSearchAction() {
        if (searchEditText == null) {
            return;
        }

        String query = searchEditText.getText().toString().trim();

        // Hide keyboard
        hideKeyboard();

        if (query.isEmpty()) {
            showToast(getString(R.string.please_enter_search_query));
            return;
        }

        // Get spinner values - use null if first item (position 0) is selected
        String searchField = null;
        if (searchFieldSpinner != null && searchFieldSpinner.getSelectedItemPosition() > 0) {
            searchField = searchFieldSpinner.getSelectedItem().toString();
        }

        String sortField = null;
        if (sortFieldSpinner != null && sortFieldSpinner.getSelectedItemPosition() > 0) {
            sortField = sortFieldSpinner.getSelectedItem().toString();
        }

        String sortOrder = null;
        if (sortOrderSpinner != null && sortOrderSpinner.getSelectedItemPosition() > 0) {
            sortOrder = sortOrderSpinner.getSelectedItem().toString();
        }

        TimberLogger.i(TAG, "Search triggered: query=%s, field=%s, sort=%s, order=%s",
            query, searchField, sortField, sortOrder);

        // Store current search parameters
        currentSearchQuery = query;
        currentSearchField = searchField;
        currentSortField = sortField;
        currentSortOrder = sortOrder;
        isSearchMode = true;

        // Call subclass implementation
        onPerformSearch(query, searchField, sortField, sortOrder);
    }

    /**
     * Called when a search is performed
     * Subclasses should override this to implement their specific search logic
     *
     * @param query The search query
     * @param searchField The field to search in (null if not specified)
     * @param sortField The field to sort by (null if not specified)
     * @param sortOrder The sort order (null if not specified)
     */
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        // Override in subclasses
    }

    /**
     * Sets up spinner with options and color changing behavior
     *
     * @param spinner The spinner to set up
     * @param options Array of options (first item should be placeholder)
     * @param promptResId Resource ID for the prompt text
     */
    protected void setupSpinner(Spinner spinner, String[] options, int promptResId) {
        if (spinner == null || options == null || options.length == 0) {
            return;
        }

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            this,
            R.layout.spinner_item,
            options
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setPrompt(getString(promptResId));

        // Change text color when selection changes
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) {
                    TextView textView = (TextView) view;
                    if (position == 0) {
                        textView.setTextColor(getResources().getColor(R.color.hint, null));
                    } else {
                        textView.setTextColor(getResources().getColor(R.color.accent, null));
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    // ==================== IM READINESS BANNER ====================
    //
    // Shared plumbing for IM screens (conversation lists + chat) so they never
    // sit blank while ImManager is still initializing. The data itself lives in
    // a local DB, so once the manager is ready the page can load it even with no
    // network; the banner only covers the brief "not ready yet" window and any
    // failure to build the manager (with a Retry).

    /** Delivered when ImManager is initialized and ready to read the local DB. */
    public interface ImReadyCallback {
        void onReady(ImManager imManager);
    }

    private View imReadyBanner;
    private TextView imReadyBannerText;
    private TextView imReadyBannerRetry;
    private ProgressBar imReadyBannerProgress;
    private boolean imReadyInProgress;

    /**
     * Ensure the current Setting's ImManager is ready, showing a non-blocking
     * "Connecting…" banner while it is built off the UI thread and a Retry
     * banner if the build fails. The callback fires on the UI thread once the
     * manager is ready. Any content already on screen stays visible throughout.
     */
    protected void ensureImReadyWithBanner(ImReadyCallback callback) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) {
            showImReadyRetry(callback);
            return;
        }

        ImManager existing = setting.getImManager();
        if (existing != null && existing.isReady()) {
            hideImReadyBanner();
            callback.onReady(existing);
            return;
        }

        if (imReadyInProgress) return;
        imReadyInProgress = true;
        showImReadyConnecting();

        new Thread(() -> {
            FapiClient fapiClient = null;
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter != null) {
                    fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Could not get FapiClient for ImManager: %s", e.getMessage());
            }

            ImManager built = null;
            try {
                built = setting.getOrCreateImManager(getApplicationContext(), fapiClient);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to build ImManager: %s", e.getMessage());
            }

            final ImManager result = built;
            runOnUiThread(() -> {
                imReadyInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (result != null && result.isReady()) {
                    hideImReadyBanner();
                    callback.onReady(result);
                } else {
                    showImReadyRetry(callback);
                }
            });
        }).start();
    }

    /**
     * Inflate the banner once and insert it just below the toolbar in the
     * activity's root vertical layout. No-op if the layout shape is unexpected.
     */
    private void ensureImReadyBannerInflated() {
        if (imReadyBanner != null) return;
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        View first = ((ViewGroup) content).getChildAt(0);
        if (!(first instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) first;

        imReadyBanner = LayoutInflater.from(this)
                .inflate(R.layout.view_im_not_ready_banner, root, false);
        // Insert directly under the toolbar (the first child) when possible.
        int index = root.getChildCount() > 0 ? 1 : 0;
        root.addView(imReadyBanner, index);

        imReadyBannerText = imReadyBanner.findViewById(R.id.im_banner_text);
        imReadyBannerRetry = imReadyBanner.findViewById(R.id.im_banner_retry);
        imReadyBannerProgress = imReadyBanner.findViewById(R.id.im_banner_progress);
    }

    private void showImReadyConnecting() {
        ensureImReadyBannerInflated();
        if (imReadyBanner == null) return;
        imReadyBannerText.setText(R.string.im_connecting);
        imReadyBannerProgress.setVisibility(View.VISIBLE);
        imReadyBannerRetry.setVisibility(View.GONE);
        imReadyBannerRetry.setOnClickListener(null);
        imReadyBanner.setVisibility(View.VISIBLE);
    }

    private void showImReadyRetry(ImReadyCallback callback) {
        ensureImReadyBannerInflated();
        if (imReadyBanner == null) return;
        imReadyBannerText.setText(R.string.im_not_ready_retry);
        imReadyBannerProgress.setVisibility(View.GONE);
        imReadyBannerRetry.setVisibility(View.VISIBLE);
        imReadyBannerRetry.setOnClickListener(v -> ensureImReadyWithBanner(callback));
        imReadyBanner.setVisibility(View.VISIBLE);
    }

    protected void hideImReadyBanner() {
        if (imReadyBanner != null) {
            imReadyBanner.setVisibility(View.GONE);
        }
    }

}