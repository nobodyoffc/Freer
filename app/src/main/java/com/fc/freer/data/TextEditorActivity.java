package com.fc.freer.data;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.KeyboardUtils;
import com.fc.freer.utils.ToastUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple text editor activity for editing txt/md files.
 * Updates HAT metadata and optionally re-uploads to DISK.
 */
public class TextEditorActivity extends BaseCryptoActivity {
    private static final String TAG = "TextEditorActivity";

    public static final String EXTRA_HAT_ID = "hatId";

    private HatManager hatManager;
    private DataSyncManager dataSyncManager;
    private Hat hat;
    private String originalContent;
    private boolean isModified = false;

    // UI Elements
    private TextView fileNameText;
    private TextView modifiedIndicator;
    private EditText textEditor;
    private ImageButton saveButton;
    private ImageButton saveUploadButton;
    private ImageButton cancelButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        initializeManagers();
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        loadFile();
        setupTextWatcher();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_text_editor;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.text_editor);
    }

    private void initializeManagers() {
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                hatManager = HatManager.getInstance(this, liveFid);
                dataSyncManager = new DataSyncManager(this, hatManager);
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
    protected void initializeViews() {
        fileNameText = findViewById(R.id.file_name_text);
        modifiedIndicator = findViewById(R.id.modified_indicator);
        textEditor = findViewById(R.id.text_editor);
        saveButton = findViewById(R.id.save_button);
        saveUploadButton = findViewById(R.id.save_upload_button);
        cancelButton = findViewById(R.id.cancel_button);
    }

    @Override
    protected void setupButtons() {
        saveButton.setOnClickListener(v -> saveFile(false));
        saveUploadButton.setOnClickListener(v -> saveFile(true));
        cancelButton.setOnClickListener(v -> cancelEdit());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadFile() {
        String hatId = getIntent().getStringExtra(EXTRA_HAT_ID);
        if (hatId == null) {
            ToastUtils.showError(this, getString(R.string.error_no_hat_id));
            finish();
            return;
        }

        hat = hatManager.getHatById(hatId);
        if (hat == null) {
            ToastUtils.showError(this, getString(R.string.hat_not_found));
            finish();
            return;
        }

        // Display file name
        String name = hat.getName();
        fileNameText.setText(name != null ? name : getString(R.string.unnamed));

        // Find local file
        String localPath = findLocalPath();
        if (localPath == null) {
            ToastUtils.showError(this, getString(R.string.file_not_available_locally));
            finish();
            return;
        }

        File file = new File(localPath);
        if (!file.exists()) {
            ToastUtils.showError(this, getString(R.string.file_not_found));
            finish();
            return;
        }

        // Read file content
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            originalContent = new String(data, StandardCharsets.UTF_8);
            textEditor.setText(originalContent);

            // Update last access time
            hat.setLast(System.currentTimeMillis());
            hatManager.updateHat(hat);
            hatManager.commit();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error reading file: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.file_not_found));
            finish();
        }
    }

    private void setupTextWatcher() {
        // Track modifications
        textEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                boolean modified = !s.toString().equals(originalContent);
                if (modified != isModified) {
                    isModified = modified;
                    modifiedIndicator.setVisibility(isModified ? android.view.View.VISIBLE : android.view.View.GONE);
                }
            }
        });
    }

    private void saveFile(boolean uploadAfterSave) {
        String content = textEditor.getText().toString();
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        // Compute new DID
        byte[] didBytes = Hash.sha256x2(data);
        String newDid = Hex.toHex(didBytes);

        // Check if content changed (DID changed)
        boolean didChanged = !newDid.equals(hat.getId());

        try {
            // Save to local file
            String localPath = findLocalPath();
            if (localPath == null) {
                // Create new local path
                localPath = new File(getFilesDir(), "data/" + newDid).getAbsolutePath();
            }

            File file = new File(localPath);
            file.getParentFile().mkdirs();

            // If DID changed, we need to create a new file
            if (didChanged) {
                File newFile = new File(getFilesDir(), "data/" + newDid);
                newFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                    fos.write(data);
                }

                // Update HAT
                hat.setPreDid(hat.getId()); // Store previous DID
                hat.setId(newDid);
                hat.setSize((long) data.length);

                // Update locations
                List<String> locas = new ArrayList<>();
                locas.add("local://" + newFile.getAbsolutePath());
                hat.setLocas(locas);
                hat.setkCipher(null); // Clear old encryption since content changed

            } else {
                // Just update the existing file
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
            }

            hat.setLast(System.currentTimeMillis());
            hatManager.updateHat(hat);
            hatManager.commit();

            originalContent = content;
            isModified = false;
            modifiedIndicator.setVisibility(android.view.View.GONE);

            KeyboardUtils.hideKeyboard(this);
            ToastUtils.makeText(this, getString(R.string.file_saved));

            if (uploadAfterSave) {
                uploadFile(data);
            } else {
                setResult(RESULT_OK);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving file: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.error_saving_file));
        }
    }

    private void uploadFile(byte[] data) {
        new Thread(() -> {
            com.fc.fc_ajdk.data.fcData.DiskItem result = dataSyncManager.uploadData(data, hat, false);
            runOnUiThread(() -> {
                if (result != null) {
                    ToastUtils.makeText(this, getString(R.string.upload_successful));
                    setResult(RESULT_OK);
                } else {
                    ToastUtils.showError(this, getString(R.string.upload_failed) + ": " + dataSyncManager.getLastError());
                }
            });
        }).start();
    }

    private void cancelEdit() {
        if (isModified) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.discard_changes)
                    .setMessage(R.string.discard_changes_message)
                    .setPositiveButton(R.string.discard, (dialog, which) -> {
                        setResult(RESULT_CANCELED);
                        finish();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private String findLocalPath() {
        List<String> locas = hat.getLocas();
        if (locas == null) return null;

        for (String loca : locas) {
            if (loca != null && loca.startsWith("local://")) {
                return loca.substring("local://".length());
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        cancelEdit();
    }
}
