package com.fc.freer.ui;

import android.content.Intent;
import android.os.Bundle;
import com.fc.freer.utils.ToastUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.utils.ToolbarUtils;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.AvatarManager;

import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.view.View;
import android.graphics.Bitmap;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

public class DetailActivity extends AppCompatActivity {
    public final static String TAG = "DetailActivity";
    public static final String EXTRA_ENTITY_JSON = "extra_entity_json";
    public static final String EXTRA_ENTITY_CLASS = "extra_entity_class";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Guard against process death while backgrounded: if the in-memory
        // session was lost, redirect to re-authentication before touching any
        // session-dependent state (which would otherwise NPE on resume).
        if (!com.fc.freer.utils.SessionGuard.ensureSession(this)) {
            return;
        }

        setContentView(R.layout.activity_detail);

        Intent intent = getIntent();
        String entityJson = intent.getStringExtra(EXTRA_ENTITY_JSON);
        String className = intent.getStringExtra(EXTRA_ENTITY_CLASS);
        FcEntity entity = null;
        Class<? extends FcEntity> entityClass = null;
        try {
            if (className != null) {
                entityClass = (Class<? extends FcEntity>) Class.forName(className);
            }
            if (entityJson != null && entityClass != null) {
                entity = FcEntity.fromJson(entityJson, entityClass);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "DetailActivity onCreate: Error loading entity: " + e.getMessage());
        }

        if (entity == null) {
            ToastUtils.makeText(this, getString(R.string.toast_error_no_entity_data));
            finish();
            return;
        }

        ToolbarUtils.setupToolbar(this, entityClass.getSimpleName().replace("Detail","") + " "+getString(R.string.detail));

        // Set up FID/CID display in toolbar
        setupToolbarFidDisplay();

        TimberLogger.d(TAG, "DetailActivity onCreate: Creating DetailFragment");
        DetailFragment detailFragment = DetailFragment.newInstance(entity, entityClass);
        if (findViewById(R.id.fragment_container) == null) {
            TimberLogger.e(TAG, "DetailActivity onCreate: fragment_container not found in layout");
        } else {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, detailFragment);
            transaction.commit();
        }
    }

    /**
     * Sets up the FID/CID display in the toolbar
     */
    private void setupToolbarFidDisplay() {
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

                // Set the FID text
                toolbarFidText.setText(id);

                // Set up avatar (grayscale marks a nobody FID)
                boolean isNobody = Boolean.TRUE.equals(liveKeyInfo.getIsNobody());
                setupToolbarAvatar(toolbarAvatar, liveKeyInfo.getId(), isNobody);

                // Set up click listener for the avatar to finish current activity and go to HomeActivity
                toolbarAvatar.setOnClickListener(v -> {
                    Intent intent = new Intent(this, com.fc.freer.home.HomeActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
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
     * Sets up the avatar in the toolbar
     */
    private void setupToolbarAvatar(ImageView avatarView, String fid, boolean isNobody) {
        try {
            TimberLogger.d(TAG, "Setting up toolbar avatar for FID: %s", fid);

            // Set initial background while avatar loads
            avatarView.setBackgroundColor(getResources().getColor(R.color.accent, getTheme()));

            // Grayscale marks a nobody FID
            if (isNobody) {
                com.fc.freer.im.NobodyBoard.applyNobodyMark(avatarView);
            } else {
                com.fc.freer.im.NobodyBoard.clearNobodyMark(avatarView);
            }

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

    private void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(this, getString(R.string.copied));
    }
} 