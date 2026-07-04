package com.fc.freer.secret;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.model.BackupHeader;
import com.fc.freer.utils.QRCodeGenerator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.widget.ImageButton;
public class BackupSecretsActivity extends BaseCryptoActivity {
    private static final String TAG = "BackupSecrets";

    private ImageButton exportButton;
    private ImageButton makeQrButton;
    private ImageButton copyButton;
    private View resultView;
    private TextInputEditText resultTextBox;
    private final List<String> jsonList = new ArrayList<>();
    List<List<Bitmap>> bitmapListList = new ArrayList<>();

    private BackupHeader backupHeader = null;
    private static final int PERMISSION_REQUEST_STORAGE = 1001;
    private static final int BATCH_SIZE = 50; // Process secrets in batches of 50
    private long totalSecrets = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if we have any secrets
        totalSecrets = SecretManager.getInstance().getSecretDBSize();
        if (totalSecrets == 0) {
            ToastUtils.makeText(this, getString(R.string.secret_list_is_empty));
            finish();
            return;
        }

        KeyInfo keyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (keyInfo == null || keyInfo.getPrikeyCipher()==null) {
            ToastUtils.makeText(this, getString(R.string.failed_to_get_live_key_prikey_cipher));
            finish();
            return;
        }

        checkStoragePermission();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
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
                ToastUtils.makeText(this, getString(R.string.toast_storage_permission_required));
            }
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_export_secret;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.export_secrets); // Using string resource
    }

    @Override
    protected void initializeViews() {
        exportButton = findViewById(R.id.export_button);
        makeQrButton = findViewById(R.id.make_qr_button);
        copyButton = findViewById(R.id.copy_button);
        resultView = findViewById(R.id.resultView);
        resultTextBox = resultView.findViewById(R.id.textBoxWithMakeQrLayout);

        setupIoIconsView(R.id.resultView, R.id.makeQrIcon, true, false, false, false,
                false, this::makeQr, null, null, null, null);
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean enabled = jsonList != null && !jsonList.isEmpty();
        makeQrButton.setEnabled(enabled);
        makeQrButton.setAlpha(enabled ? 1f : 0.5f);
        copyButton.setEnabled(enabled);
        copyButton.setAlpha(enabled ? 1f : 0.5f);
    }

    private void makeQr() {
        if (!jsonList.isEmpty()) {
            bitmapListList = QRCodeGenerator.makeQRBitmapsList(jsonList,backupHeader);
            List<Bitmap> flattenedBitmaps = new ArrayList<>();
            for (List<Bitmap> bitmapList : bitmapListList) {
                flattenedBitmaps.addAll(bitmapList);
            }
            QRCodeGenerator.showQRDialog(this, flattenedBitmaps);
        } else {
            ToastUtils.makeText(this, getString(R.string.toast_no_data_make_qr));
        }
    }

    @Override
    protected void setupButtons() {
        exportButton.setOnClickListener(v -> {
            jsonList.clear();
            doExport();
        });

        makeQrButton.setOnClickListener(v -> makeQr());

        copyButton.setOnClickListener(v -> {
            String textToCopy = JsonUtils.makeJsonListString(jsonList);
            if (textToCopy != null && !textToCopy.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Exported Secrets", textToCopy);
                clipboard.setPrimaryClip(clip);
                ToastUtils.makeText(this, getString(R.string.toast_copied_to_clipboard));
            } else {
                ToastUtils.makeText(this, getString(R.string.toast_nothing_to_copy));
            }
        });
    }

    private void doExport() {
        String result = generateExportResult();
        if (result != null && !result.isEmpty()) {
            String filePath = saveResultToFile(result);
            if (filePath != null) {
                displayFilePath(filePath);
                ToastUtils.makeText(this, getString(R.string.toast_exported_to, filePath));
            }
        }
        updateButtonStates();
    }

    private String saveResultToFile(String result) {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            String fileName = "backup_secrets_" + System.currentTimeMillis() + ".txt";
            File file = new File(downloadDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(result.getBytes());
                fos.flush();
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            TimberLogger.e(TAG, "Failed to save file: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.toast_failed_save_file));
            return null;
        }
    }

    private void displayFilePath(String filePath) {
        if (resultTextBox != null && resultView != null) {
            resultTextBox.setText(filePath);
            resultView.setVisibility(View.VISIBLE);
        }
    }

    private String generateExportResult() {
        backupHeader = new BackupHeader();
        backupHeader.setKeyName(FidManager.getInstance().getLiveFid());
        backupHeader.setTime(System.currentTimeMillis());
        backupHeader.setItems((int) totalSecrets);
        backupHeader.settClass(Secret.class.getSimpleName());

        String headerNiceJson = backupHeader.toNiceJson();
        jsonList.add(headerNiceJson);

        // Process secrets in batches to avoid memory issues
        String lastID = null;
        int processedCount = 0;

        while (processedCount < totalSecrets) {
            List<Secret> batch = SecretManager.getInstance().getPaginatedSecrets(BATCH_SIZE, lastID, false);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            for (Secret secret : batch) {
                if (secret == null) continue;
                processSecretInBatch(secret);
                lastID = secret.getId();
                processedCount++;
            }

            // Clear processed batch from memory
            batch.clear();

            // Optional: Force garbage collection for large datasets
            if (processedCount % (BATCH_SIZE * 4) == 0) {
                System.gc();
            }
        }

        return JsonUtils.makeJsonListString(jsonList);
    }

    private void processSecretInBatch(Secret secret) {
        if(Boolean.TRUE.equals(secret.getOnChain()))return;

        String secretJson;
        secret.setContent(null);
        try {
            secretJson = JsonUtils.toNiceJson(secret);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing secret: %s", secret.getTitle());
            ToastUtils.makeText(this, getString(R.string.toast_error_processing_secret, secret.getTitle()));
            return;
        }

        jsonList.add(secretJson);
    }

    private String decryptSecretContent(Secret secret) {
        if (secret.getContentCipher() == null) {
            return secret.getContent();
        }

        try {
            KeyInfo keyInfo = FidManager.getInstance().getLiveKeyInfo();
            if (keyInfo == null || keyInfo.getPrikeyCipher() == null) {
                return null;
            }

            byte[] prikey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(keyInfo.getPrikeyCipher());
            if (prikey == null) {
                return null;
            }

            try {
                CryptoDataByte cryptoDataByte = CryptoDataByte.fromJson(secret.getContentCipher());
                byte[] symKey = ConfigureManager.getInstance().getSymkey();
                if (symKey == null) {
                    return null;
                }

                Decryptor.decryptByAsyKey(cryptoDataByte, prikey);
                if (cryptoDataByte.getData() != null) {
                    return new String(cryptoDataByte.getData());
                }
            } finally {
                SecurePrikeyManager.erasePrikey(prikey);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting secret content: %s", e.getMessage());
        }
        return null;
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this context
    }
} 