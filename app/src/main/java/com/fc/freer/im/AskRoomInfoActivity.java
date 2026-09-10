package com.fc.freer.im;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

public class AskRoomInfoActivity extends BaseCryptoActivity {
    private static final String TAG = "AskRoomInfoActivity";
    public static final String EXTRA_ENTITY_ID = "extra_entity_id";

    private LinearLayout membersLayout;
    private ImageButton requestButton;
    private String entityId;
    private String liveFid;
    private final List<CheckBox> memberCheckBoxes = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ask_symkey;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.ask_room_info);
    }

    @Override
    protected void initializeViews() {
        membersLayout = findViewById(R.id.teams_list_layout);
        requestButton = findViewById(R.id.send_button);

        entityId = getIntent().getStringExtra(EXTRA_ENTITY_ID);
        if (entityId == null) {
            finish();
            return;
        }

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) {
            finish();
            return;
        }
        liveFid = setting.getMainFid();

        TextView headerView = new TextView(this);
        headerView.setText(getString(R.string.select_members_to_ask_room_info));
        headerView.setTextSize(14);
        headerView.setTextColor(getResources().getColor(R.color.hint, null));
        headerView.setPadding(0, 0, 0, 16);
        membersLayout.addView(headerView);

        loadMembers();
    }

    @Override
    protected void setupButtons() {
        requestButton.setOnClickListener(v -> {
            hideKeyboard();
            sendRoomInfoRequests();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void loadMembers() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) {
            ToastUtils.makeText(this, getString(R.string.im_manager_init_failed));
            return;
        }

        ImManager imManager = setting.getImManager();
        Room room = imManager.getRoom(entityId);
        if (room == null || room.getMembers() == null || room.getMembers().isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        for (String memberFid : room.getMembers()) {
            addMemberRow(memberFid);
        }
    }

    private void addMemberRow(String fid) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(24, 12, 24, 12);
        item.setBackgroundResource(R.drawable.container_outline);
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 8);
        item.setLayoutParams(lp);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setTag(fid);
        item.addView(checkBox);
        memberCheckBoxes.add(checkBox);

        ImageView avatar = new ImageView(this);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(40, 40);
        avatarLp.setMarginStart(8);
        avatar.setLayoutParams(avatarLp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageResource(R.drawable.ic_person);
        avatar.setImageTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.hint, null)));
        avatar.setPadding(4, 4, 4, 4);
        avatar.setTag(fid);
        item.addView(avatar);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(12);
        textColumn.setLayoutParams(textLp);

        TextView cidView = new TextView(this);
        cidView.setTextSize(14);
        cidView.setTextColor(getResources().getColor(R.color.accent, null));
        cidView.setSingleLine(true);

        CidFidManager cidFidManager = CidFidManager.getInstance();
        String cid = cidFidManager != null ? cidFidManager.getCidByFid(fid) : null;
        if (cid != null && !cid.isEmpty()) {
            cidView.setText(cid);
            cidView.setVisibility(android.view.View.VISIBLE);
        } else {
            cidView.setVisibility(android.view.View.GONE);
        }
        textColumn.addView(cidView);

        TextView fidView = new TextView(this);
        fidView.setText(fid);
        fidView.setTextSize(13);
        fidView.setTextColor(getResources().getColor(R.color.text, null));
        fidView.setSingleLine(true);
        fidView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textColumn.addView(fidView);

        // Our own FID is a real target here: another device signed in as this
        // identity is often the only holder of the room info. Say so, so the
        // row does not read as a pointless "ask yourself".
        if (fid.equals(liveFid)) {
            TextView selfHint = new TextView(this);
            selfHint.setText(getString(R.string.self_other_devices));
            selfHint.setTextSize(12);
            selfHint.setTextColor(getResources().getColor(R.color.hint, null));
            textColumn.addView(selfHint);
        }

        item.addView(textColumn);

        item.setOnClickListener(v -> {
            hideKeyboard();
            checkBox.setChecked(!checkBox.isChecked());
        });

        membersLayout.addView(item);

        new Thread(() -> {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                Bitmap bitmap = avatarManager.getAvatarBitmap(fid);
                if (bitmap != null) {
                    avatar.post(() -> {
                        if (fid.equals(avatar.getTag())) {
                            avatar.setImageTintList(null);
                            avatar.setImageBitmap(bitmap);
                            avatar.setPadding(0, 0, 0, 0);
                        }
                    });
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to load avatar for %s: %s", fid, e.getMessage());
            }
        }).start();
    }

    private void sendRoomInfoRequests() {
        List<String> selectedFids = new ArrayList<>();
        for (CheckBox cb : memberCheckBoxes) {
            if (cb.isChecked()) {
                selectedFids.add((String) cb.getTag());
            }
        }

        if (selectedFids.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_members_selected);
            return;
        }

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) {
            ToastUtils.makeText(this, getString(R.string.im_manager_init_failed));
            return;
        }

        requestButton.setEnabled(false);
        ImManager imManager = setting.getImManager();
        imManager.requestRoomInfoFromMembers(entityId, selectedFids);

        ToastUtils.makeText(this, getString(R.string.room_info_requests_sent, selectedFids.size()));
        finish();
    }
}
