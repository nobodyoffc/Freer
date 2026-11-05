package com.fc.freer.image;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Image;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ImageManager;
import com.fc.freer.manager.TokenLogoManager;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * Publishes a FEIP24 Image, or a new edition of one ({@link ImageDetailActivity#EXTRA_IMAGE}).
 * The picture is uploaded to this FID's DISK; the carve names it by did, with that DISK in
 * locas. An edition that keeps its picture keeps its did and locas: an update replaces every
 * field, so nothing is left out.
 */
public class PublishImageActivity extends BaseCryptoActivity {
    private Image editing;
    private Uri picked;
    private boolean busy;

    private ImageView preview;
    private TextView fileNote;
    private TextView progress;
    private TextInputEditText titleInput;
    private TextInputEditText summaryInput;
    private TextInputEditText authorsInput;
    private TextInputEditText langInput;
    private TextInputEditText formatInput;

    private ActivityResultLauncher<String[]> pickLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_publish_image;
    }

    @Override
    protected String getActivityTitle() {
        return getString(getIntent().hasExtra(ImageDetailActivity.EXTRA_IMAGE)
                ? R.string.update_image : R.string.publish_image);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        pickLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
        String json = getIntent().getStringExtra(ImageDetailActivity.EXTRA_IMAGE);
        editing = json == null ? null : JsonUtils.fromJson(json, Image.class);
        super.onCreate(savedInstanceState);
        if (isSessionRedirected()) return;
        if (editing != null) fill(editing);
    }

    @Override
    protected void initializeViews() {
        preview = findViewById(R.id.publish_image_preview);
        fileNote = findViewById(R.id.publish_image_file_note);
        progress = findViewById(R.id.publish_image_progress);
        titleInput = findViewById(R.id.publish_image_title);
        summaryInput = findViewById(R.id.publish_image_summary);
        authorsInput = findViewById(R.id.publish_image_authors);
        langInput = findViewById(R.id.publish_image_lang);
        formatInput = findViewById(R.id.publish_image_format);
    }

    @Override
    protected void setupButtons() {
        Button pick = findViewById(R.id.publish_image_pick);
        pick.setOnClickListener(v -> {
            if (!busy) pickLauncher.launch(new String[]{"image/*"});
        });
        preview.setOnClickListener(v -> {
            if (!busy) pickLauncher.launch(new String[]{"image/*"});
        });
        findViewById(R.id.publishButton).setOnClickListener(v -> {
            hideKeyboard();
            publish();
        });
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // No scan fields here.
    }

    private void fill(Image image) {
        titleInput.setText(image.getTitle());
        summaryInput.setText(image.getSummary());
        authorsInput.setText(image.getAuthors() == null ? "" : String.join(", ", image.getAuthors()));
        langInput.setText(image.getLang());
        formatInput.setText(image.getFormat());
        fileNote.setText(R.string.image_keep_file);
        int sizePx = (int) (200 * getResources().getDisplayMetrics().density);
        TokenLogoManager.getInstance(this).loadImage(image, sizePx, bitmap -> {
            if (bitmap != null && picked == null && !isFinishing()) preview.setImageBitmap(bitmap);
        });
    }

    private void onPicked(Uri uri) {
        if (uri == null) return;
        picked = uri;
        fileNote.setText(R.string.image_file_note);
        String type = getContentResolver().getType(uri);
        if (type != null && type.startsWith("image/")) formatInput.setText(type);
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, source) -> {
                        int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                        int target = 1080;
                        if (longest > target) {
                            float scale = target / (float) longest;
                            decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                                    Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                        }
                    });
            preview.setImageBitmap(bitmap);
        } catch (Exception e) {
            preview.setImageResource(R.drawable.ic_gallery);
        }
    }

    private void publish() {
        if (busy) return;
        String title = text(titleInput);
        if (title.isEmpty()) {
            ToastUtils.makeText(this, R.string.image_title_required);
            return;
        }
        if (editing == null && picked == null) {
            ToastUtils.makeText(this, R.string.image_no_file);
            return;
        }
        String summary = text(summaryInput);
        List<String> authors = ImageManager.splitList(text(authorsInput));
        String lang = text(langInput);
        String typedFormat = text(formatInput);

        // Checked before anything is uploaded or paid for, with a did-sized placeholder.
        String probe = editing == null
                ? ImageManager.publishJson(title, placeholderDid(), lang, authors, typedFormat, summary, placeholderLocas())
                : ImageManager.updateJson(editing.getId(), title, placeholderDid(), lang, authors, typedFormat, summary,
                    picked == null ? editing.getLocas() : placeholderLocas());
        if (!ImageManager.fitsOpReturn(probe)) {
            ToastUtils.makeText(this, R.string.image_carve_too_large);
            return;
        }

        KeyInfo keyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (keyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(keyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }
        String fid = keyInfo.getId();
        setBusy(true);
        new Thread(() -> {
            String did;
            List<String> locas;
            String format = typedFormat;
            if (picked != null) {
                runOnUiThread(() -> progress.setText(R.string.image_uploading));
                try {
                    ImageManager.Upload upload = ImageManager.upload(this, fid, picked);
                    did = upload.did;
                    locas = upload.locas;
                    if (format.isEmpty() && upload.format != null) format = upload.format;
                } catch (ImageManager.UploadException e) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        ToastUtils.makeText(this, e.getMessage());
                    });
                    return;
                }
            } else {
                did = editing.getDid();
                locas = editing.getLocas();
            }
            String finalFormat = format;
            String json = editing == null
                    ? ImageManager.publishJson(title, did, lang, authors, finalFormat, summary, locas)
                    : ImageManager.updateJson(editing.getId(), title, did, lang, authors, finalFormat, summary, locas);
            if (!ImageManager.fitsOpReturn(json)) {
                runOnUiThread(() -> {
                    setBusy(false);
                    ToastUtils.makeText(this, R.string.image_carve_too_large);
                });
                return;
            }
            runOnUiThread(() -> progress.setText(R.string.image_carving));
            ImageManager.carve(this, fid, prikey, json, new ImageManager.CarveCallback() {
                @Override
                public void onCarved(String txId) {
                    Image record = editing == null ? new Image() : editing;
                    if (editing == null) {
                        record.setId(txId);
                        record.setPublisher(fid);
                        record.setVer("1");
                        record.setDeleted(false);
                    } else {
                        record.setVer(nextVer(editing.getVer()));
                    }
                    record.setTitle(title);
                    record.setDid(did);
                    record.setLang(lang.isEmpty() ? null : lang);
                    record.setAuthors(authors.isEmpty() ? null : authors);
                    record.setFormat(finalFormat.isEmpty() ? null : finalFormat);
                    record.setSummary(summary.isEmpty() ? null : summary);
                    record.setLocas(locas == null || locas.isEmpty() ? null : locas);
                    record.setLastTxId(txId);
                    ImageManager.noteCarved(PublishImageActivity.this, record);
                    ToastUtils.makeText(PublishImageActivity.this,
                            editing == null ? R.string.image_published : R.string.image_updated);
                    Intent data = new Intent();
                    data.putExtra(ImagesActivity.EXTRA_IMAGE_ID, record.getId());
                    data.putExtra(ImageDetailActivity.EXTRA_IMAGE, JsonUtils.toJson(record));
                    setResult(RESULT_OK, data);
                    finish();
                }

                @Override
                public void onFailed(String message) {
                    setBusy(false);
                    ToastUtils.makeText(PublishImageActivity.this,
                            getString(R.string.image_carve_failed) + ": " + message);
                }
            });
        }).start();
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        findViewById(R.id.publishButton).setEnabled(!value);
        findViewById(R.id.publish_image_pick).setEnabled(!value);
    }

    private static String nextVer(String ver) {
        try {
            return String.valueOf(Integer.parseInt(ver) + 1);
        } catch (Exception e) {
            return ver;
        }
    }

    private static String placeholderDid() {
        return "0".repeat(64);
    }

    /** One (sid) entry: what an upload to the default DISK names. */
    private static List<String> placeholderLocas() {
        return List.of("(sid)" + "0".repeat(64));
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
