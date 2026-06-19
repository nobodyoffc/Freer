package com.fc.freer.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.FileUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;

/**
 * Generic import activity that can import data from QR codes, text input, or files.
 * The actual parsing and validation logic is handled by the caller.
 */
public class ImportActivity extends BaseCryptoActivity {
    private static final String TAG = "ImportActivity";
    private static final int QR_SCAN_REQUEST_CODE = 1001;

    // Result extras
    public static final String EXTRA_IMPORT_RESULT = "extra_import_result";
    public static final String EXTRA_IS_FILE_MODE = "extra_is_file_mode";
    public static final String EXTRA_FILE_PATH = "extra_file_path";

    // Intent extras for configuration
    private static final String EXTRA_TITLE = "extra_title";
    private static final String EXTRA_HINT = "extra_hint";
    private static final String EXTRA_ALLOW_FILE = "extra_allow_file";
    private static final String EXTRA_INITIAL_DIRECTORY = "extra_initial_directory";

    private LinearLayout inputContainer;
    private LinearLayout buttonContainer;
    private TextInputEditText textInput;
    private ImageButton clearButton;
    private ImageButton importButton;

    private String title;
    private String hint;
    private boolean allowFile;
    private String initialDirectory;

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private String currentFilePath;
    private boolean isFileMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        TimberLogger.i(TAG, "onCreate started");

        // Get configuration from intent
        title = getIntent().getStringExtra(EXTRA_TITLE);
        hint = getIntent().getStringExtra(EXTRA_HINT);
        allowFile = getIntent().getBooleanExtra(EXTRA_ALLOW_FILE, true);
        initialDirectory = getIntent().getStringExtra(EXTRA_INITIAL_DIRECTORY);
    }

    private void initViews() {
        inputContainer = findViewById(R.id.inputContainer);
        buttonContainer = findViewById(R.id.buttonContainer);

        View inputLayout = findViewById(R.id.textInputLayout);
        textInput = inputLayout.findViewById(R.id.textInput);

        if (hint != null) {
            textInput.setHint(hint);
        }

        clearButton = findViewById(R.id.clearButton);
        importButton = findViewById(R.id.importButton);

        // Initialize file picker launcher
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            handleFileSelection(uri);
                        }
                    }
                });

        // Setup icons based on configuration
        if (allowFile) {
            setupIoIconsView(R.id.textInputLayout, R.id.scanIcon, false, false, true, true,
                    true, null, null, () -> pasteFromClipboard(textInput), this::openFilePicker, ()-> startQrScan(QR_SCAN_REQUEST_CODE));
        } else {
            setupTextIcons(R.id.textInputLayout, R.id.scanIcon, QR_SCAN_REQUEST_CODE);
        }
    }

    private void handleFileSelection(Uri uri) {
        String filePath = FileUtils.getPathFromUri(this, uri);
        String fileName = FileUtils.getFileNameFromUri(this, uri);

        if (filePath == null || fileName == null) {
            showToast(getString(R.string.failed_to_load_file));
            return;
        }

        currentFilePath = filePath;
        isFileMode = true;
        textInput.setText(getString(R.string.file_loaded_import_it));
        textInput.setEnabled(false);
        textInput.setTextColor(Color.GRAY);
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        // Set initial directory if specified
        if (initialDirectory != null && !initialDirectory.isEmpty()) {
            if ("downloads".equals(initialDirectory)) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"));
            }
        }

        filePickerLauncher.launch(intent);
    }

    private void setupListeners() {

        clearButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }

            textInput.setText("");
            textInput.setEnabled(true);
            textInput.setTextColor(getColor(R.color.text));
            isFileMode = false;
            currentFilePath = null;
        });

        importButton.setOnClickListener(v -> {
            TimberLogger.i(TAG, "importButton clicked");

            try {
                Intent resultIntent = new Intent();
                resultIntent.putExtra(EXTRA_IS_FILE_MODE, isFileMode);

                if (isFileMode && currentFilePath != null) {
                    // Return file path and content
                    File file = new File(currentFilePath);
                    if (!file.exists()) {
                        showToast(getString(R.string.file_not_found));
                        return;
                    }

                    // Read file content
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[(int) file.length()];
                        fis.read(buffer);
                        String content = new String(buffer);

                        resultIntent.putExtra(EXTRA_FILE_PATH, currentFilePath);
                        resultIntent.putExtra(EXTRA_IMPORT_RESULT, content);
                    }
                } else {
                    // Return text input
                    String text = textInput.getText() != null ? textInput.getText().toString().trim() : "";

                    if (text.isEmpty()) {
                        TimberLogger.e(TAG, "Input text is empty");
                        showToast(getString(R.string.please_input_text));
                        return;
                    }

                    resultIntent.putExtra(EXTRA_IMPORT_RESULT, text);
                }

                setResult(RESULT_OK, resultIntent);
                finish();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error reading input: " + e.getMessage());
                showToast(getString(R.string.operation_failed_with_message, e.getMessage()));
            }
        });
    }

    private void paste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            CharSequence pasteData = item.getText();
            if (pasteData != null) {
                textInput.setText(pasteData.toString());
                textInput.setEnabled(true);
                textInput.setTextColor(getColor(R.color.text));
                isFileMode = false;
                currentFilePath = null;
            } else {
                showToast(getString(R.string.clipboard_is_empty));
            }
        } else {
            showToast(getString(R.string.clipboard_is_empty));
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import;
    }

    @Override
    protected String getActivityTitle() {
        return title != null ? title : getString(R.string.import_data);
    }

    @Override
    protected void initializeViews() {
        initViews();
    }

    @Override
    protected void setupButtons() {
        setupListeners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_REQUEST_CODE) {
            textInput.setText(qrContent);
            textInput.setEnabled(true);
            textInput.setTextColor(getColor(R.color.text));
            isFileMode = false;
            currentFilePath = null;
        }
    }

    /**
     * Create intent to launch ImportActivity
     *
     * @param context Current context
     * @param title Activity title
     * @param hint Input field hint text
     * @param allowFile Whether to allow file import
     * @param initialDirectory Initial directory for file picker (e.g., "downloads")
     * @return Intent to launch ImportActivity
     */
    public static Intent createIntent(Context context, String title, String hint, boolean allowFile, String initialDirectory) {
        Intent intent = new Intent(context, ImportActivity.class);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_HINT, hint);
        intent.putExtra(EXTRA_ALLOW_FILE, allowFile);
        intent.putExtra(EXTRA_INITIAL_DIRECTORY, initialDirectory);
        return intent;
    }

    /**
     * Create intent to launch ImportActivity (with file support and downloads directory)
     *
     * @param context Current context
     * @param title Activity title
     * @param hint Input field hint text
     * @return Intent to launch ImportActivity
     */
    public static Intent createIntent(Context context, String title, String hint) {
        return createIntent(context, title, hint, true, "downloads");
    }

    /**
     * Create intent to launch ImportActivity (simple version without file support)
     *
     * @param context Current context
     * @param title Activity title
     * @param hint Input field hint text
     * @param allowFile Whether to allow file import
     * @return Intent to launch ImportActivity
     */
    public static Intent createIntent(Context context, String title, String hint, boolean allowFile) {
        return createIntent(context, title, hint, allowFile, null);
    }
}
