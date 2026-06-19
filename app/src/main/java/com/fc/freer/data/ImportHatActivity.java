package com.fc.freer.data;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ExportMeta;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.FileUtils;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.fc.fc_ajdk.utils.JsonUtils.readOneJsonFromInputStream;

/**
 * Activity for importing HATs from text input, file, or QR code.
 * Supports JSONL format: ExportMeta JSON (optional first line) followed by Hat JSONs.
 * No encryption is used for HAT import/export.
 */
public class ImportHatActivity extends BaseCryptoActivity {
    private static final String TAG = "ImportHatActivity";
    private static final int QR_SCAN_JSON_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_STORAGE = 1001;

    private HatManager hatManager;

    // UI Elements
    private LinearLayout hatJsonInputContainer;
    private LinearLayout hatButtonContainer;
    private TextInputEditText hatJsonInput;
    private ImageButton hatClearButton;
    private ImageButton hatImportButton;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private String currentFilePath;
    private boolean isFileMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        initializeManagers();
        checkStoragePermission();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_STORAGE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
            } else {
                ToastUtils.makeText(this, getString(R.string.storage_permission_is_required_to_read_backup_files));
            }
        }
    }

    private void initializeManagers() {
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                hatManager = HatManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null");
                ToastUtils.showError(this, getString(R.string.error_no_live_fid));
                finish();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing managers: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import_hat;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.import_hats);
    }

    @Override
    protected void initializeViews() {
        hatJsonInputContainer = findViewById(R.id.hatJsonInputContainer);
        hatButtonContainer = findViewById(R.id.hatButtonContainer);

        hatJsonInput = hatJsonInputContainer.findViewById(R.id.hatJsonInput).findViewById(R.id.textInput);
        hatJsonInput.setHint(R.string.input_hat_json);

        hatClearButton = findViewById(R.id.hatClearButton);
        hatImportButton = findViewById(R.id.hatImportButton);

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

        // Setup IoIconsView: show paste, file, and scan icons
        setupIoIconsView(R.id.hatJsonInput, R.id.scanIcon, false, false, true, true,
                true, null, null, () -> pasteFromClipboard(hatJsonInput), () -> startQrScan(QR_SCAN_JSON_REQUEST_CODE), this::openFilePicker);
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
        hatJsonInput.setText(R.string.file_loaded_import_it);
        hatJsonInput.setEnabled(false);
        hatJsonInput.setTextColor(Color.GRAY);
    }

    private void openFilePicker() {
        hideKeyboard();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"));
        filePickerLauncher.launch(intent);
    }

    @Override
    protected void setupButtons() {
        hatClearButton.setOnClickListener(v -> {
            hideKeyboard();
            hatJsonInput.setText("");
            hatJsonInput.setEnabled(true);
            hatJsonInput.setTextColor(getResources().getColor(R.color.text, getTheme()));
            isFileMode = false;
            currentFilePath = null;
        });

        hatImportButton.setOnClickListener(v -> {
            hideKeyboard();
            try {
                if (isFileMode && currentFilePath != null) {
                    File file = new File(currentFilePath);
                    if (!file.exists()) {
                        showToast(getString(R.string.file_not_found));
                        return;
                    }
                    try (FileInputStream fis = new FileInputStream(file)) {
                        importFromStream(fis);
                    }
                } else {
                    String jsonText = hatJsonInput.getText() != null ? hatJsonInput.getText().toString() : "";
                    if (jsonText.isEmpty()) {
                        showToast(getString(R.string.no_hat_found));
                        return;
                    }
                    importFromText(jsonText);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error importing: " + e.getMessage());
                showToast(getString(R.string.no_hat_found));
            }
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_JSON_REQUEST_CODE) {
            hatJsonInput.setText(qrContent);
            hatJsonInput.setEnabled(true);
            hatJsonInput.setTextColor(getResources().getColor(R.color.text, getTheme()));
            isFileMode = false;
            currentFilePath = null;
        }
    }

    /**
     * Imports HATs from a text string.
     * Parses ExportMeta (if present) and Hat JSON objects from the text.
     */
    private void importFromText(String jsonText) {
        byte[] jsonBytes = jsonText.getBytes(StandardCharsets.UTF_8);
        try (InputStream is = new java.io.ByteArrayInputStream(jsonBytes)) {
            importFromStream(is);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing text input: " + e.getMessage());
            showToast(getString(R.string.no_hat_found));
        }
    }

    /**
     * Imports HATs from an input stream.
     * Reads JSON objects one by one using readOneJsonFromInputStream.
     * The first JSON may be an ExportMeta, the rest are Hat objects.
     */
    private void importFromStream(InputStream is) {
        List<Hat> hatsToImport = new ArrayList<>();
        ExportMeta exportMeta = null;
        boolean isFirstJson = true;

        try {
            while (true) {
                byte[] jsonBytes = readOneJsonFromInputStream(is);
                if (jsonBytes == null) break;
                String json = new String(jsonBytes, StandardCharsets.UTF_8);

                if (isFirstJson) {
                    // Try to parse as ExportMeta
                    try {
                        ExportMeta meta = ExportMeta.fromJson(json);
                        if (meta != null && meta.getVersion() != null) {
                            exportMeta = meta;
                            isFirstJson = false;
                            continue;
                        }
                    } catch (Exception e) {
                        // Not ExportMeta, try as Hat below
                    }
                    isFirstJson = false;
                }

                // Parse as Hat
                try {
                    Hat hat = JsonUtils.fromJson(json, Hat.class);
                    if (hat != null) {
                        if (hat.getId() == null) {
                            hat.checkIdWithCreate();
                        }
                        hatsToImport.add(hat);
                    }
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to parse Hat JSON: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reading stream: " + e.getMessage());
        }

        if (hatsToImport.isEmpty()) {
            showToast(getString(R.string.no_hat_found));
            return;
        }

        // Use HatManager's sequential processing with duplicate handling
        hatManager.processHatsSequentially(this, hatsToImport, 0, 0);
    }
}
