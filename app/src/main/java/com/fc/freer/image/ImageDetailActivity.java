package com.fc.freer.image;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

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

import java.util.Collections;
import java.util.List;

/**
 * One FEIP24 Image: the picture (fetched from its DISK locations and shown only when it
 * hashes to the record's did), its record, and for the publisher update, delete and recover.
 */
public class ImageDetailActivity extends BaseCryptoActivity {
    public static final String EXTRA_IMAGE = "image_json";

    private Image image;
    private String fid;
    private boolean changed;

    private ActivityResultLauncher<Intent> updateLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_image_detail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.menu_image);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        updateLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String json = result.getData().getStringExtra(EXTRA_IMAGE);
            if (json != null) {
                image = JsonUtils.fromJson(json, Image.class);
                markChanged();
                render();
            }
        });
        String json = getIntent().getStringExtra(EXTRA_IMAGE);
        image = json == null ? null : JsonUtils.fromJson(json, Image.class);
        super.onCreate(savedInstanceState);
        if (isSessionRedirected()) return;
        if (image == null) {
            finish();
            return;
        }
        fid = FidManager.getInstance().getLiveFid();
        render();
    }

    @Override
    protected void initializeViews() {
        // Filled by render().
    }

    @Override
    protected void setupButtons() {
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.image_copy_button).setOnClickListener(v -> copy(image.getId()));
        findViewById(R.id.image_edit_button).setOnClickListener(v -> {
            Intent intent = new Intent(this, PublishImageActivity.class);
            intent.putExtra(EXTRA_IMAGE, JsonUtils.toJson(image));
            updateLauncher.launch(intent);
        });
        findViewById(R.id.image_delete_button).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.image_delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> setDeleted(true))
                .setNegativeButton(R.string.cancel, null)
                .show());
        findViewById(R.id.image_recover_button).setOnClickListener(v -> setDeleted(false));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // No scan fields here.
    }

    private void render() {
        boolean owner = ImageManager.isOwner(image, fid);
        boolean deleted = Boolean.TRUE.equals(image.getDeleted());
        ImageButton edit = findViewById(R.id.image_edit_button);
        ImageButton delete = findViewById(R.id.image_delete_button);
        ImageButton recover = findViewById(R.id.image_recover_button);
        edit.setVisibility(owner && !deleted ? View.VISIBLE : View.GONE);
        delete.setVisibility(owner && !deleted ? View.VISIBLE : View.GONE);
        recover.setVisibility(owner && deleted ? View.VISIBLE : View.GONE);

        LinearLayout fields = findViewById(R.id.image_fields);
        fields.removeAllViews();
        field(fields, R.string.image_field_title, image.getTitle(), false);
        field(fields, R.string.image_field_id, image.getId(), true);
        field(fields, R.string.image_field_ver, image.getVer(), false);
        field(fields, R.string.image_field_publisher, image.getPublisher(), true);
        field(fields, R.string.image_field_summary, image.getSummary(), false);
        field(fields, R.string.image_field_authors, join(image.getAuthors()), false);
        field(fields, R.string.image_field_format, image.getFormat(), false);
        field(fields, R.string.image_field_lang, image.getLang(), false);
        field(fields, R.string.image_field_did, image.getDid(), true);
        locas(fields, image.getLocas());
        field(fields, R.string.image_field_height,
                image.getBirthHeight() == null ? null : String.valueOf(image.getBirthHeight()), false);
        if (image.gettCdd() != null && image.gettCdd() > 0 && image.gettRate() != null) {
            field(fields, R.string.image_field_rate,
                    String.format(java.util.Locale.ROOT, "%.2f / 5 (%d cd)", image.gettRate(), image.gettCdd()), false);
        }
        if (deleted) field(fields, R.string.image_field_deleted, getString(R.string.confirm), false);

        loadPicture();
    }

    private void loadPicture() {
        ImageView picture = findViewById(R.id.image_picture);
        TextView status = findViewById(R.id.image_picture_status);
        String did = image.getDid();
        if (did == null || !did.toLowerCase().matches("[0-9a-f]{64}")) {
            picture.setVisibility(View.GONE);
            status.setText(R.string.image_no_did);
            status.setVisibility(View.VISIBLE);
            return;
        }
        status.setText(R.string.image_fetching);
        status.setVisibility(View.VISIBLE);
        int width = getResources().getDisplayMetrics().widthPixels;
        String expected = image.getId() + "@" + did;
        picture.setTag(expected);
        TokenLogoManager.getInstance(this).loadImage(image, width, bitmap -> {
            if (isFinishing() || !expected.equals(picture.getTag())) return;
            if (bitmap == null) {
                picture.setVisibility(View.GONE);
                status.setText(R.string.image_not_verified);
                return;
            }
            picture.setImageBitmap(bitmap);
            picture.setVisibility(View.VISIBLE);
            status.setVisibility(View.GONE);
        });
    }

    private void field(LinearLayout parent, int label, String value, boolean copyable) {
        if (value == null || value.isEmpty()) return;
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(ContextCompat.getColor(this, R.color.field_name));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setPadding(0, dp(8), 0, 0);
        parent.addView(name);

        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextIsSelectable(true);
        if (copyable) {
            text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.setSingleLine(true);
            text.setTextColor(ContextCompat.getColor(this, R.color.accent));
            text.setOnClickListener(v -> copy(value));
        }
        parent.addView(text);
    }

    /** DISK entries are listed; https ones are offered as links, never fetched unasked (rule 4). */
    private void locas(LinearLayout parent, List<String> locas) {
        if (locas == null || locas.isEmpty()) return;
        TextView name = new TextView(this);
        name.setText(R.string.image_field_locas);
        name.setTextColor(ContextCompat.getColor(this, R.color.field_name));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setPadding(0, dp(8), 0, 0);
        parent.addView(name);
        for (String loca : locas) {
            TextView text = new TextView(this);
            text.setText(loca);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.setSingleLine(true);
            String lower = loca.toLowerCase();
            if (lower.startsWith("https://") || lower.startsWith("http://")) {
                text.setTextColor(ContextCompat.getColor(this, R.color.accent));
                text.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setMessage(getString(R.string.image_web_loca_open, loca))
                        .setPositiveButton(R.string.confirm, (d, w) -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(loca)));
                            } catch (Exception ignored) {
                                // No browser.
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());
            } else {
                text.setOnClickListener(v -> copy(loca));
            }
            parent.addView(text);
        }
    }

    private void setDeleted(boolean delete) {
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
        List<String> ids = Collections.singletonList(image.getId());
        String json = delete ? ImageManager.deleteJson(ids) : ImageManager.recoverJson(ids);
        new Thread(() -> ImageManager.carve(this, keyInfo.getId(), prikey, json, new ImageManager.CarveCallback() {
            @Override
            public void onCarved(String txId) {
                image.setDeleted(delete);
                ImageManager.noteCarved(ImageDetailActivity.this, image);
                ToastUtils.makeText(ImageDetailActivity.this,
                        delete ? R.string.image_deleted_done : R.string.image_recovered_done);
                markChanged();
                render();
            }

            @Override
            public void onFailed(String message) {
                ToastUtils.makeText(ImageDetailActivity.this, getString(R.string.image_carve_failed) + ": " + message);
            }
        })).start();
    }

    private void markChanged() {
        if (changed) return;
        changed = true;
        setResult(RESULT_OK);
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || value == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("image", value));
        ToastUtils.makeText(this, R.string.copied_to_clipboard);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static String join(List<String> list) {
        return list == null || list.isEmpty() ? null : TextUtils.join(", ", list);
    }
}
