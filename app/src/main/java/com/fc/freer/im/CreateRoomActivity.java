package com.fc.freer.im;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.MenuItem;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreateRoomActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateRoomActivity";

    private TextInputEditText nameInput;
    private TextInputEditText descInput;
    private TextInputEditText dockInput;

    private LinearLayout membersContainer;
    private ImageButton addMemberButton;
    private KeyCardContainer keyCardContainer;

    private ImageButton clearButton;
    private ImageButton createButton;
    private ImageButton backButton;
    private ImageButton chooseDockButton;

    private ImManager imManager;
    private ActivityResultLauncher<Intent> addMemberLauncher;
    private ActivityResultLauncher<Intent> chooseDockLauncher;
    private WaitingDialog waitingDialog;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_room;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_room);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
    }

    @Override
    protected void initializeViews() {
        nameInput = findViewById(R.id.room_name_input);
        descInput = findViewById(R.id.room_desc_input);
        dockInput = findViewById(R.id.room_dock_input);

        membersContainer = findViewById(R.id.members_container);
        addMemberButton = findViewById(R.id.add_member_button);

        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(KeyCardContainer.createDeleteMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, membersContainer,
                ChooseMode.WITHOUT_CHOOSE_WITH_DELETE, menuItems);
        keyCardContainer.setShowDefaultMenuItems(false);

        clearButton = findViewById(R.id.clearButton);
        createButton = findViewById(R.id.createButton);
        backButton = findViewById(R.id.back_button);
        chooseDockButton = findViewById(R.id.choose_dock_button);

        chooseDockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(), dockInput);
                    }
                });

        initAddMemberLauncher();
        initManager();
    }

    @Override
    protected void setupButtons() {
        addMemberButton.setOnClickListener(v -> {
            hideKeyboard();
            launchAddMembers();
        });

        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        createButton.setOnClickListener(v -> {
            hideKeyboard();
            createRoom();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });

        chooseDockButton.setOnClickListener(v -> {
            hideKeyboard();
            chooseDockLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.DOCK_NO1_NRC7, getString(R.string.server_setup_dock_label)));
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void initManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            imManager = setting.getImManager();
        }
    }

    private void initAddMemberLauncher() {
        addMemberLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        List<String> selectedFids = result.getData()
                                .getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                        if (selectedFids != null) {
                            // Declining keeps the picked members that aren't nobodies
                            NobodyGuard.confirm(this, selectedFids, R.string.nobody_consequence_room,
                                    () -> addMembersToContainer(selectedFids),
                                    () -> addMembersToContainer(withoutNobodies(selectedFids)));
                        }
                    }
                });
    }

    private void launchAddMembers() {
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_TITLE, getString(R.string.select_room_members));
        addMemberLauncher.launch(intent);
    }

    private static List<String> withoutNobodies(List<String> fids) {
        List<String> kept = new java.util.ArrayList<>();
        for (String fid : fids) {
            if (!NobodyUi.isNobody(fid)) kept.add(fid);
        }
        return kept;
    }

    private void addMembersToContainer(List<String> fids) {
        List<KeyInfo> existing = keyCardContainer.getKeyInfoList();
        for (String fid : fids) {
            boolean alreadyAdded = false;
            for (KeyInfo ki : existing) {
                if (fid.equals(ki.getId())) {
                    alreadyAdded = true;
                    break;
                }
            }
            if (!alreadyAdded) {
                keyCardContainer.addKeyCard(new KeyInfo(fid));
            }
        }
    }

    private void clearInputs() {
        nameInput.setText("");
        descInput.setText("");
        dockInput.setText("");
        keyCardContainer.clearAll();
    }

    private void createRoom() {
        String name = getText(nameInput);
        String desc = getText(descInput);
        String dock = getText(dockInput);

        if (name.isEmpty()) {
            ToastUtils.makeText(this, R.string.room_name_required);
            return;
        }

        if (imManager == null) {
            ToastUtils.makeText(this, R.string.im_not_ready_try_later);
            return;
        }

        Map<String, String> home = null;
        if (!dock.isEmpty()) {
            home = new HashMap<>();
            home.put("DOCK@No1_NrC7", dock);
        }

        List<String> memberFids = null;
        List<KeyInfo> memberKeys = keyCardContainer.getKeyInfoList();
        if (!memberKeys.isEmpty()) {
            memberFids = new ArrayList<>();
            for (KeyInfo ki : memberKeys) {
                memberFids.add(ki.getId());
            }
        }

        Map<String, String> finalHome = home;
        List<String> finalMemberFids = memberFids;

        createButton.setEnabled(false);
        waitingDialog = new WaitingDialog(this, getString(R.string.please_wait));
        waitingDialog.show();

        new Thread(() -> {
            Room room = imManager.createRoom(name, desc, finalMemberFids, finalHome);
            runOnUiThread(() -> {
                if (waitingDialog != null && waitingDialog.isShowing()) {
                    waitingDialog.dismiss();
                }
                createButton.setEnabled(true);
                if (room != null) {
                    ToastUtils.makeText(this, R.string.room_created);
                    setResult(RESULT_OK);
                    finish();
                } else {
                    ToastUtils.makeText(this, R.string.failed);
                }
            });
        }).start();
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }
}
