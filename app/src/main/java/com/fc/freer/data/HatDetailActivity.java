package com.fc.freer.data;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.KeyboardUtils;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.fcData.KeyInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Activity for viewing and editing HAT details.
 * Provides:
 * - View/edit HAT metadata
 * - Open file with appropriate app
 * - Upload to DISK
 * - Download from DISK
 */
public class HatDetailActivity extends BaseCryptoActivity {
    private static final String TAG = "HatDetailActivity";

    public static final String EXTRA_HAT_ID = "hatId";

    private HatManager hatManager;
    private DataSyncManager dataSyncManager;
    private Hat hat;
    private SimpleDateFormat dateFormat;

    // UI Elements
    private EditText nameEdit;
    private EditText descEdit;
    private TextView idText;
    private TextView sizeText;
    private TextView typeText;
    private TextView bornText;
    private TextView lastText;
    private TextView locationsText;
    private ImageView encryptIcon;
    private TextView encryptStatus;

    private ImageButton openButton;
    private ImageButton uploadButton;
    private ImageButton downloadButton;
    private ImageButton saveButton;
    private ImageButton backButton;

    private ActivityResultLauncher<Intent> textEditorLauncher;
    private HatFileOpener hatFileOpener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        initializeActivityResultLaunchers();
        initializeManagers();
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        loadHat();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_hat_detail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.hat_detail);
    }

    private void initializeActivityResultLaunchers() {
        textEditorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        loadHat(); // Refresh after editing
                    }
                }
        );
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
        nameEdit = findViewById(R.id.hat_name_edit);
        descEdit = findViewById(R.id.hat_desc_edit);
        idText = findViewById(R.id.hat_id_text);
        sizeText = findViewById(R.id.hat_size_text);
        typeText = findViewById(R.id.hat_type_text);
        bornText = findViewById(R.id.hat_born_text);
        lastText = findViewById(R.id.hat_last_text);
        locationsText = findViewById(R.id.hat_locations_text);
        encryptIcon = findViewById(R.id.hat_encrypt_icon);
        encryptStatus = findViewById(R.id.hat_encrypt_status);

        openButton = findViewById(R.id.open_button);
        uploadButton = findViewById(R.id.upload_button);
        downloadButton = findViewById(R.id.download_button);
        saveButton = findViewById(R.id.save_button);
        backButton = findViewById(R.id.back_button);

        hatFileOpener = new HatFileOpener(this, hatManager);
        hatFileOpener.setTextEditorLauncher(textEditorLauncher);
    }

    @Override
    protected void setupButtons() {
        openButton.setOnClickListener(v -> {
            KeyboardUtils.hideKeyboard(this);
            hatFileOpener.open(hat);
        });
        openButton.setOnLongClickListener(v -> {
            KeyboardUtils.hideKeyboard(this);
            openFileWithChooser();
            return true;
        });
        uploadButton.setOnClickListener(v -> {
            KeyboardUtils.hideKeyboard(this);
            uploadFile();
        });
        downloadButton.setOnClickListener(v -> {
            KeyboardUtils.hideKeyboard(this);
            downloadFile();
        });
        saveButton.setOnClickListener(v -> saveChanges());
        backButton.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });

        findViewById(android.R.id.content).setOnClickListener(v -> KeyboardUtils.hideKeyboard(this));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadHat() {
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

        displayHat();
    }

    private void displayHat() {
        nameEdit.setText(hat.getName());
        descEdit.setText(hat.getDesc());
        idText.setText(hat.getId());

        // Size
        Long size = hat.getSize();
        if (size != null) {
            sizeText.setText(formatSize(size));
        }

        // Type
        List<String> types = hat.getTypes();
        if (types != null && !types.isEmpty()) {
            typeText.setText(String.join(", ", types));
        }

        // Dates
        Long born = hat.getBorn();
        if (born != null) {
            bornText.setText(dateFormat.format(new Date(born)));
        }

        Long last = hat.getLast();
        if (last != null) {
            lastText.setText(dateFormat.format(new Date(last)));
        }

        // Locations
        List<String> locas = hat.getLocas();
        if (locas != null && !locas.isEmpty()) {
            locationsText.setText(String.join("\n", locas));
        } else {
            locationsText.setText(R.string.no_locations);
        }

        // Encryption status - check both kCipher (legacy) and cipherIds (two-HAT model)
        List<String> cipherIds = hat.getCipherIds();
        boolean hasCipherIds = cipherIds != null && !cipherIds.isEmpty();
        String kCipher = hat.getkCipher();
        boolean hasKCipher = kCipher != null && !kCipher.isEmpty();
        
        if (hasCipherIds || hasKCipher) {
            encryptIcon.setVisibility(android.view.View.VISIBLE);
            if (hasCipherIds) {
                encryptStatus.setText(getString(R.string.encrypted) + " (" + cipherIds.size() + " cipher" + (cipherIds.size() > 1 ? "s" : "") + ")");
            } else {
                encryptStatus.setText(R.string.encrypted);
            }
        } else {
            encryptIcon.setVisibility(android.view.View.GONE);
            encryptStatus.setText(R.string.not_encrypted);
        }

        // Show download button if data is available on DISK (via locas or cipherIds)
        boolean isDownloadable = false;
        if (locas != null) {
            for (String loca : locas) {
                if (loca != null && (loca.startsWith(DataSyncManager.FUDP_LOCATION_PREFIX) || loca.startsWith(DataSyncManager.SID_LOCATION_PREFIX))) {
                    isDownloadable = true;
                    break;
                }
            }
        }
        if (!isDownloadable && hasCipherIds) {
            isDownloadable = true;
        }
        downloadButton.setVisibility(isDownloadable ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /**
     * Long-press handler: always show system chooser, bypassing the in-app text editor.
     */
    private void openFileWithChooser() {
        String localPath = findLocalPath();
        if (localPath == null) {
            ToastUtils.showError(this, getString(R.string.file_not_available_locally));
            return;
        }

        File file = new File(localPath);
        if (!file.exists()) {
            ToastUtils.showError(this, getString(R.string.file_not_found));
            return;
        }

        String mimeType = null;
        List<String> types = hat.getTypes();
        if (types != null && !types.isEmpty()) mimeType = types.get(0);
        if (mimeType == null) mimeType = FileTypeHandler.getMimeType(hat.getName());
        openWithSystemApp(file, mimeType);
    }

    private void openWithSystemApp(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(viewIntent, getString(R.string.open_with));
            startActivity(chooser);

            hat.setLast(System.currentTimeMillis());
            hatManager.updateHat(hat);
            hatManager.commit();
        } catch (android.content.ActivityNotFoundException e) {
            ToastUtils.showError(this, getString(R.string.no_app_to_open_file));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error opening file: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.error_opening_file));
        }
    }

    private void uploadFile() {
        String localPath = findLocalPath();
        if (localPath == null) {
            ToastUtils.showError(this, getString(R.string.file_not_available_locally));
            return;
        }

        File file = new File(localPath);
        if (!file.exists()) {
            ToastUtils.showError(this, getString(R.string.file_not_found));
            return;
        }

        // Upload with encryption using two-HAT model (file-based, stream-friendly)
        new Thread(() -> {
            com.fc.fc_ajdk.data.fcData.DiskItem result = dataSyncManager.uploadData(file, hat, false);
            runOnUiThread(() -> {
                if (result != null) {
                    ToastUtils.makeText(this, getString(R.string.upload_successful));
                    loadHat(); // Refresh display
                } else {
                    ToastUtils.showError(this, getString(R.string.upload_failed) + ": " + dataSyncManager.getLastError());
                }
            });
        }).start();
    }

    private void downloadFile() {
        // If plaintext key is available (shared via IM), no password needed
        String plainKey = hat.getKey();
        if (plainKey != null && !plainKey.isEmpty()) {
            doDownload(null);
            return;
        }

        // Otherwise need prikey to decrypt kCipher
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) {
            ToastUtils.showError(this, getString(R.string.private_key_not_available));
            return;
        }
        
        KeyInfo keyInfo = fidManager.getLiveKeyInfo();
        if (keyInfo == null || keyInfo.getPrikeyCipher() == null) {
            ToastUtils.showError(this, getString(R.string.private_key_not_available));
            return;
        }

        SecurePrikeyManager.requestPrikey(this, getString(R.string.download), keyInfo.getPrikeyCipher(), 
            new SecurePrikeyManager.PrikeyCallback() {
                @Override
                public void onPrikeyDecrypted(byte[] prikey) {
                    doDownload(prikey);
                }

                @Override
                public void onError(String errorMessage) {
                    ToastUtils.showError(HatDetailActivity.this, errorMessage);
                }

                @Override
                public void onUserDenied() {
                    // User cancelled
                }
            });
    }

    private void doDownload(byte[] prikey) {
        new Thread(() -> {
            File localFile = new File(getFilesDir(), "data/" + hat.getId());
            if (localFile.getParentFile() != null) {
                localFile.getParentFile().mkdirs();
            }

            boolean success = dataSyncManager.downloadData(hat, prikey, localFile);
            runOnUiThread(() -> {
                if (success) {
                    hatManager.addHatLocation(hat.getId(), "local://" + localFile.getAbsolutePath());
                    hatManager.commit();

                    ToastUtils.makeText(HatDetailActivity.this, getString(R.string.download_successful));
                    loadHat();
                } else {
                    if (localFile.exists()) {
                        localFile.delete();
                    }
                    ToastUtils.showError(HatDetailActivity.this, getString(R.string.download_failed, dataSyncManager.getLastError()));
                }
            });
        }).start();
    }

    private void saveChanges() {
        String name = nameEdit.getText().toString().trim();
        String desc = descEdit.getText().toString().trim();

        hat.setName(name.isEmpty() ? null : name);
        hat.setDesc(desc.isEmpty() ? null : desc);
        hat.setLast(System.currentTimeMillis());

        hatManager.updateHat(hat);
        hatManager.commit();

        KeyboardUtils.hideKeyboard(this);
        ToastUtils.makeText(this, getString(R.string.changes_saved));
        setResult(RESULT_OK);
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

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }
}
