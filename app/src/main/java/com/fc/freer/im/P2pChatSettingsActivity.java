package com.fc.freer.im;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

/**
 * Activity for P2P chat settings: the stranger policy, the blacklist, and the
 * opt-in check of the first-FCH board at login.
 * These settings are stored locally. For home setup (carved onchain), use ServerSetupActivity.
 */
public class P2pChatSettingsActivity extends BaseCryptoActivity {

    private static final String PREFS_NAME = "p2p_chat_settings";
    private static final String KEY_REJECT_ALL_STRANGERS = "reject_all_strangers_";
    private static final String KEY_AUTO_ACCEPT_CONTACTS = "auto_accept_contacts_";

    private CheckBox cbRejectAllStrangers;
    private CheckBox cbAutoAcceptContacts;
    private CheckBox cbCheckBoardAtLogin;
    private TextView linkManageBlacklist;

    private String liveFid;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_p2p_chat_settings;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.p2p_chat_settings);
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

        ScrollView scrollView = findViewById(R.id.scroll_view);
        if (scrollView != null) {
            scrollView.setOnTouchListener((v, event) -> {
                hideKeyboard();
                return false;
            });
        }
    }

    @Override
    protected void initializeViews() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            liveFid = setting.getMainFid();
        }

        cbRejectAllStrangers = findViewById(R.id.cb_reject_all_strangers);
        cbAutoAcceptContacts = findViewById(R.id.cb_auto_accept_contacts);
        cbCheckBoardAtLogin = findViewById(R.id.cb_check_board_at_login);
        linkManageBlacklist = findViewById(R.id.link_manage_blacklist);

        loadStrangerPolicySettings();
        setupStrangerPolicyListeners();
        cbCheckBoardAtLogin.setChecked(NewcomerBoard.isAutoCheckAtLogin(this, liveFid));
        // Saved on the spot, like the policy boxes: the tick button saves
        // everything again, but leaving the screen by Back must not lose it.
        cbCheckBoardAtLogin.setOnClickListener(v -> {
            hideKeyboard();
            saveCheckBoardAtLogin(cbCheckBoardAtLogin.isChecked());
        });
    }

    private void loadStrangerPolicySettings() {
        if (liveFid == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean rejectAll = prefs.getBoolean(KEY_REJECT_ALL_STRANGERS + liveFid, false);
        boolean autoAccept = prefs.getBoolean(KEY_AUTO_ACCEPT_CONTACTS + liveFid, true);

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting != null) {
            ImManager imManager = setting.getImManager();
            if (imManager != null) {
                ContactPolicy policy = imManager.getContactPolicy();
                if (policy != null) {
                    rejectAll = policy.getStrategy() == ContactPolicy.Strategy.CONTACTS_ONLY
                            || policy.getStrategy() == ContactPolicy.Strategy.ACCEPT_NONE;
                }
            }
        }

        cbRejectAllStrangers.setChecked(rejectAll);
        cbAutoAcceptContacts.setChecked(autoAccept);
    }

    private void setupStrangerPolicyListeners() {
        cbRejectAllStrangers.setOnClickListener(v -> {
            hideKeyboard();
            boolean checked = cbRejectAllStrangers.isChecked();
            saveRejectAllStrangers(checked);
            applyStrangerPolicy();
        });

        cbAutoAcceptContacts.setOnClickListener(v -> {
            hideKeyboard();
            boolean checked = cbAutoAcceptContacts.isChecked();
            saveAutoAcceptContacts(checked);
        });

        linkManageBlacklist.setOnClickListener(v -> {
            hideKeyboard();
            startActivity(new android.content.Intent(this, BlacklistActivity.class));
        });
    }

    private void saveRejectAllStrangers(boolean reject) {
        if (liveFid == null) return;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REJECT_ALL_STRANGERS + liveFid, reject)
                .apply();
    }

    private void saveAutoAcceptContacts(boolean autoAccept) {
        if (liveFid == null) return;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_ACCEPT_CONTACTS + liveFid, autoAccept)
                .apply();
    }

    private void saveCheckBoardAtLogin(boolean enabled) {
        NewcomerBoard.setAutoCheckAtLogin(this, liveFid, enabled);
    }

    private void applyStrangerPolicy() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return;
        ImManager imManager = setting.getImManager();
        if (imManager == null) return;
        ContactPolicy policy = imManager.getContactPolicy();
        if (policy == null) return;

        if (cbRejectAllStrangers.isChecked()) {
            policy.setStrategy(ContactPolicy.Strategy.CONTACTS_ONLY);
            ToastUtils.showInfo(this, getString(R.string.all_strangers_blocked));
        } else {
            policy.setStrategy(ContactPolicy.Strategy.ACCEPT_ALL);
            ToastUtils.showInfo(this, getString(R.string.strangers_accepted));
        }
    }

    public static boolean isAutoAcceptContacts(Context context, String liveFid) {
        if (liveFid == null) return true;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_ACCEPT_CONTACTS + liveFid, true);
    }

    @Override
    protected void setupButtons() {
        ImageButton saveButton = findViewById(R.id.save_button);
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> {
                hideKeyboard();
                saveAllSettings();
                ToastUtils.showInfo(this, getString(R.string.setting_saved));
            });
        }

        setupBackButton();
    }

    private void saveAllSettings() {
        boolean rejectAll = cbRejectAllStrangers.isChecked();
        boolean autoAccept = cbAutoAcceptContacts.isChecked();
        saveRejectAllStrangers(rejectAll);
        saveAutoAcceptContacts(autoAccept);
        saveCheckBoardAtLogin(cbCheckBoardAtLogin.isChecked());
        applyStrangerPolicy();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in P2P chat settings
    }
}
