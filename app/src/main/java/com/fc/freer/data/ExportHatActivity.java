package com.fc.freer.data;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageButton;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ExportMeta;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Activity for exporting HATs without encryption.
 * The exported format is JSONL: ExportMeta JSON on the first line, followed by Hat JSONs one per line.
 */
public class ExportHatActivity extends BaseCryptoActivity {
    private static final String TAG = "ExportHat";

    private List<Hat> hatList;
    private ImageButton exportButton;
    private ImageButton copyButton;
    private View resultView;
    private TextInputEditText resultTextBox;
    private final List<String> jsonList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get and parse the hat list from intent
        String hatListJson = getIntent().getStringExtra("hatList");
        if (hatListJson == null) {
            ToastUtils.showError(this, R.string.hat_list_is_null);
            finish();
            return;
        }
        hatList = JsonUtils.listFromJson(hatListJson, Hat.class);
        if (hatList == null || hatList.isEmpty()) {
            ToastUtils.showError(this, R.string.hat_list_is_empty);
            finish();
            return;
        }

        // Generate and display content immediately
        doExport();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_export_hat;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.export_hats);
    }

    @Override
    protected void initializeViews() {
        exportButton = findViewById(R.id.export_button);
        copyButton = findViewById(R.id.copy_button);
        resultView = findViewById(R.id.resultView);
        resultTextBox = resultView.findViewById(R.id.textBoxWithMakeQrLayout);

        setupIoIconsView(R.id.resultView, R.id.makeQrIcon, true, false, false, false,
                false, this::makeQr, null, null, null, null);
    }

    private void makeQr() {
        if (!jsonList.isEmpty()) {
            List<List<Bitmap>> bitmapListList = QRCodeGenerator.makeQRBitmapsList(jsonList, null);

            List<Bitmap> flattenedBitmaps = new ArrayList<>();
            for (List<Bitmap> bitmapList : bitmapListList) {
                flattenedBitmaps.addAll(bitmapList);
            }
            QRCodeGenerator.showQRDialog(this, flattenedBitmaps);
        } else {
            ToastUtils.showWarning(this, getString(R.string.no_data_to_make_qr_code));
        }
    }

    @Override
    protected void setupButtons() {
        exportButton.setOnClickListener(v -> {
            hideKeyboard();
            saveToFile();
        });

        copyButton.setOnClickListener(v -> {
            hideKeyboard();
            String textToCopy = JsonUtils.makeJsonListString(jsonList);
            if (textToCopy != null && !textToCopy.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Exported HATs", textToCopy);
                clipboard.setPrimaryClip(clip);
            } else {
                ToastUtils.showWarning(this, getString(R.string.nothing_to_copy));
            }
        });
    }

    private void doExport() {
        jsonList.clear();

        String result = generateExportResult();
        if (result != null && !result.isEmpty()) {
            displayResult(result);
        }
    }

    private String generateExportResult() {
        // Create ExportMeta
        String liveFid = null;
        FidManager fidManager = FidManager.getInstance();
        if (fidManager != null) {
            liveFid = fidManager.getLiveFid();
        }

        ExportMeta exportMeta = ExportMeta.create(
                liveFid,
                hatList.size(),
                getString(R.string.app_name),
                Hat.class.getSimpleName()
        );

        // Add ExportMeta as the first JSON
        jsonList.add(JsonUtils.toNiceJson(exportMeta));

        // Add each Hat JSON
        for (Hat hat : hatList) {
            if (hat == null) continue;
            try {
                String hatJson = JsonUtils.toNiceJson(hat);
                jsonList.add(hatJson);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error serializing hat: %s", hat.getId());
            }
        }

        return JsonUtils.makeJsonListString(jsonList);
    }

    private void saveToFile() {
        String content = JsonUtils.makeJsonListString(jsonList);
        if (content == null || content.isEmpty()) {
            ToastUtils.showWarning(this, getString(R.string.nothing_to_export));
            return;
        }

        String liveFid = "";
        FidManager fidManager = FidManager.getInstance();
        if (fidManager != null && fidManager.getLiveFid() != null) {
            liveFid = fidManager.getLiveFid();
        }

        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "hats_" + liveFid + "_" + time + ".json";

        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            File file = new File(downloadDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes());
                fos.flush();
            }
            ToastUtils.makeText(this, getString(R.string.file_saved) + ": " + file.getAbsolutePath());
        } catch (IOException e) {
            TimberLogger.e(TAG, "Failed to save file: %s", e.getMessage());
            ToastUtils.showError(this, getString(R.string.failed_to_save_file));
        }
    }

    private void displayResult(String result) {
        if (resultTextBox != null && resultView != null) {
            resultTextBox.setText(result);
            resultView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in export activity
    }
}
