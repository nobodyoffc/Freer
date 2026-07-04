package com.fc.freer.im;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JoinTeamActivity extends BaseCryptoActivity {
    private static final String TAG = "JoinTeamActivity";
    private static final String MMKV_PREFIX = "join_team_";
    private static final String IGNORED_KEY = "ignored";
    private static final String PENDING_ACCEPTED_KEY = "pending_accepted";
    private static final long PENDING_ACCEPTED_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private EditText searchEditText;
    private View clearSearchIcon;
    private ImageView searchIcon;
    private ImageButton clearButton;
    private LinearLayout searchResultsLayout;
    private CheckBox allTeamsCheckbox;
    private CheckBox includingIgnoredCheckbox;

    private String liveFid;
    private MMKV mmkv;
    private final Gson gson = new Gson();

    private final List<Team> invitedTeams = new ArrayList<>();
    private final List<Team> transferTeams = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_join_team;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.join_team);
    }

    @Override
    protected void initializeViews() {
        searchEditText = findViewById(R.id.search_edit_text);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        searchIcon = findViewById(R.id.search_icon);
        clearButton = findViewById(R.id.clearButton);
        searchResultsLayout = findViewById(R.id.search_results_layout);
        allTeamsCheckbox = findViewById(R.id.all_teams_checkbox);
        includingIgnoredCheckbox = findViewById(R.id.including_ignored_checkbox);

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo != null) {
            liveFid = liveKeyInfo.getId();
            mmkv = MMKV.mmkvWithID(MMKV_PREFIX + liveFid, MMKV.SINGLE_PROCESS_MODE);
        }

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (!allTeamsCheckbox.isChecked()) {
                    displayInvitationsAndTransfers(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    && allTeamsCheckbox.isChecked()) {
                hideKeyboard();
                performOnChainSearch();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void setupButtons() {
        searchIcon.setOnClickListener(v -> {
            hideKeyboard();
            if (allTeamsCheckbox.isChecked()) {
                performOnChainSearch();
            } else {
                fetchInvitationsAndTransfers();
            }
        });

        clearSearchIcon.setOnClickListener(v -> {
            searchEditText.setText("");
            hideKeyboard();
        });

        clearButton.setOnClickListener(v -> {
            searchEditText.setText("");
            searchResultsLayout.removeAllViews();
            invitedTeams.clear();
            transferTeams.clear();
            hideKeyboard();
            if (!allTeamsCheckbox.isChecked()) {
                fetchInvitationsAndTransfers();
            }
        });

        allTeamsCheckbox.setOnCheckedChangeListener((btn, checked) -> {
            includingIgnoredCheckbox.setVisibility(checked ? View.GONE : View.VISIBLE);
            searchResultsLayout.removeAllViews();
            invitedTeams.clear();
            transferTeams.clear();
            searchEditText.setText("");
            if (!checked) {
                fetchInvitationsAndTransfers();
            }
        });

        includingIgnoredCheckbox.setOnCheckedChangeListener((btn, checked) ->
                displayInvitationsAndTransfers(searchEditText.getText().toString()));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    @Override
    protected void onResume() {
        super.onResume();
        if (!allTeamsCheckbox.isChecked()) {
            fetchInvitationsAndTransfers();
        }
    }

    // ========== Fetch ==========

    private void fetchInvitationsAndTransfers() {
        if (liveFid == null) return;
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                .getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.im_not_ready_try_later));
            return;
        }

        cleanUpPendingAccepted();
        searchResultsLayout.removeAllViews();
        invitedTeams.clear();
        transferTeams.clear();

        new Thread(() -> {
            try {
                Fcdsl invFcdsl = new Fcdsl();
                invFcdsl.addNewFilter().addNewTerms().addNewFields("invitees").addNewValues(liveFid);
                invFcdsl.addSize(50);
                List<Team> invited = fapiClient.entitySearch("team", invFcdsl, Team.class);

                Fcdsl tfrFcdsl = new Fcdsl();
                tfrFcdsl.addNewFilter().addNewTerms().addNewFields("transferee").addNewValues(liveFid);
                tfrFcdsl.addSize(20);
                List<Team> transfers = fapiClient.entitySearch("team", tfrFcdsl, Team.class);

                runOnUiThread(() -> {
                    if (invited != null) invitedTeams.addAll(invited);
                    if (transfers != null) transferTeams.addAll(transfers);
                    displayInvitationsAndTransfers(searchEditText.getText().toString());
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Fetch failed: %s", e.getMessage());
                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.failed)));
            }
        }).start();
    }

    // ========== Display ==========

    private void displayInvitationsAndTransfers(String query) {
        searchResultsLayout.removeAllViews();
        boolean showIgnored = includingIgnoredCheckbox.isChecked();
        Set<String> ignored = loadIgnored();
        Set<String> pendingAccepted = loadPendingAccepted().keySet();
        String lower = (query != null && !query.isEmpty()) ? query.toLowerCase() : null;

        List<Team> filteredInvited = applyFilter(invitedTeams, lower, ignored, showIgnored, pendingAccepted);
        addSectionHeader(getString(R.string.invitations));
        if (filteredInvited.isEmpty()) {
            addNoResultsText(R.string.no_team_invitations);
        } else {
            for (Team team : filteredInvited) {
                addTeamCard(team, false, ignored.contains(team.getId()));
            }
        }

        List<Team> filteredTransfers = applyFilter(transferTeams, lower, ignored, showIgnored, pendingAccepted);
        addSectionHeader(getString(R.string.pending_transfers));
        if (filteredTransfers.isEmpty()) {
            addNoResultsText(R.string.no_pending_transfers);
        } else {
            for (Team team : filteredTransfers) {
                addTeamCard(team, true, ignored.contains(team.getId()));
            }
        }
    }

    private List<Team> applyFilter(List<Team> src, String lower, Set<String> ignored,
                                   boolean showIgnored, Set<String> pendingAccepted) {
        List<Team> result = new ArrayList<>();
        for (Team t : src) {
            if (pendingAccepted.contains(t.getId())) continue;
            if (!showIgnored && ignored.contains(t.getId())) continue;
            if (lower != null) {
                boolean nameMatch = t.getStdName() != null && t.getStdName().toLowerCase().contains(lower);
                boolean idMatch = t.getId() != null && t.getId().toLowerCase().contains(lower);
                if (!nameMatch && !idMatch) continue;
            }
            result.add(t);
        }
        return result;
    }

    // ========== Card building ==========

    private void addSectionHeader(String title) {
        float d = getResources().getDisplayMetrics().density;
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(getResources().getColor(R.color.hint, null));
        header.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(8 * d);
        lp.bottomMargin = (int)(4 * d);
        header.setLayoutParams(lp);
        searchResultsLayout.addView(header);
    }

    private void addNoResultsText(int resId) {
        TextView tv = new TextView(this);
        tv.setText(resId);
        tv.setTextColor(getResources().getColor(R.color.hint, null));
        tv.setPadding(16, 8, 16, 8);
        searchResultsLayout.addView(tv);
    }

    private void addTeamCard(Team team, boolean isTransfer, boolean isIgnored) {
        float d = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding((int)(12*d), (int)(8*d), (int)(8*d), (int)(8*d));
        card.setBackgroundResource(R.drawable.container_outline);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, (int)(8*d));
        card.setLayoutParams(cardLp);
        if (isIgnored) card.setAlpha(0.5f);

        // Avatar
        ImageView avatar = new ImageView(this);
        int avatarSize = (int)(48*d);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd((int)(12*d));
        avatar.setLayoutParams(avatarLp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        avatar.setBackgroundResource(R.drawable.avatar_background);
        if (team.getOwner() != null) {
            Bitmap bmp = AvatarManager.getInstance(this).getAvatarBitmap(team.getOwner());
            if (bmp != null) { avatar.setImageBitmap(bmp); avatar.setImageTintList(null); }
            else setDefaultAvatar(avatar, d);
        } else { setDefaultAvatar(avatar, d); }
        card.addView(avatar);

        // Content column
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameView = new TextView(this);
        nameView.setText(team.getStdName() != null ? team.getStdName() : team.getId());
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTextColor(getResources().getColor(R.color.accent, null));
        nameView.setTypeface(nameView.getTypeface(), Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(nameView);

        TextView idView = new TextView(this);
        idView.setText(team.getId() != null ? team.getId() : "");
        idView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        idView.setTextColor(getResources().getColor(R.color.hint, null));
        idView.setMaxLines(1);
        idView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        content.addView(idView);

        // Metrics row
        LinearLayout metricsRow = new LinearLayout(this);
        metricsRow.setOrientation(LinearLayout.HORIZONTAL);
        metricsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metricsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metricsLp.topMargin = (int)(4*d);
        metricsRow.setLayoutParams(metricsLp);

        int iconSize = (int)(16*d);
        int hintColor = getResources().getColor(R.color.hint, null);
        int accentColor = getResources().getColor(R.color.accent, null);

        ImageView personIcon = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd((int)(2*d));
        personIcon.setLayoutParams(iconLp);
        personIcon.setImageResource(R.drawable.ic_person);
        personIcon.setImageTintList(ColorStateList.valueOf(accentColor));
        metricsRow.addView(personIcon);

        TextView memberNumText = new TextView(this);
        memberNumText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        memberNumText.setTextColor(hintColor);
        long memberNum = team.getMemberNum() != null ? team.getMemberNum() : 0;
        memberNumText.setText(String.valueOf(memberNum));
        LinearLayout.LayoutParams memberLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        memberLp.setMarginEnd((int)(8*d));
        memberNumText.setLayoutParams(memberLp);
        metricsRow.addView(memberNumText);

        if (isTransfer) {
            TextView badge = new TextView(this);
            badge.setText(R.string.status_pending_transfer);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTextColor(getResources().getColor(R.color.colorAccent, null));
            metricsRow.addView(badge);
        } else if (isIgnored) {
            TextView badge = new TextView(this);
            badge.setText(R.string.ignored);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTextColor(hintColor);
            metricsRow.addView(badge);
        }
        content.addView(metricsRow);
        card.addView(content);

        // Ignore / un-ignore button
        boolean isMember = team.getMembers() != null && liveFid != null
                && team.getMembers().contains(liveFid);
        if (!isMember) {
            ImageView ignoreBtn = new ImageView(this);
            int btnSize = (int)(36*d);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSize, btnSize);
            ignoreBtn.setLayoutParams(btnLp);
            ignoreBtn.setImageResource(R.drawable.ic_clear);
            ignoreBtn.setImageTintList(ColorStateList.valueOf(isIgnored ? accentColor : hintColor));
            ignoreBtn.setPadding((int)(6*d), (int)(6*d), (int)(6*d), (int)(6*d));
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            ignoreBtn.setBackgroundResource(tv.resourceId);
            ignoreBtn.setClickable(true);
            ignoreBtn.setFocusable(true);
            // Accent tint = currently ignored (click to un-ignore); hint = not ignored (click to ignore)
            ignoreBtn.setOnClickListener(v -> {
                if (isIgnored) {
                    removeFromIgnored(team.getId());
                } else {
                    addToIgnored(team.getId());
                }
                displayInvitationsAndTransfers(searchEditText.getText().toString());
            });
            card.addView(ignoreBtn);
        }

        // Card click → join or take-over
        boolean alreadyMember = isMember;
        card.setOnClickListener(v -> {
            hideKeyboard();
            if (alreadyMember) {
                openChat(team.getId(), team.getStdName());
            } else if (isTransfer) {
                takeOverTeam(team);
            } else {
                joinTeam(team);
            }
        });

        searchResultsLayout.addView(card);
    }

    private void setDefaultAvatar(ImageView view, float d) {
        view.setImageResource(R.drawable.ic_person);
        int pad = (int)(8*d);
        view.setPadding(pad, pad, pad, pad);
        view.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.hint, null)));
    }

    // ========== On-chain search (All Teams mode) ==========

    private void performOnChainSearch() {
        String query = searchEditText.getText().toString().trim();
        if (query.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_search_query));
            return;
        }

        searchResultsLayout.removeAllViews();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance()
                .getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.im_not_ready_try_later));
            return;
        }

        new Thread(() -> {
            try {
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields("stdName").addNewValue(query);
                fcdsl.addSize(20);
                List<Team> teams = fapiClient.entitySearch("team", fcdsl, Team.class);

                runOnUiThread(() -> {
                    if (teams == null || teams.isEmpty()) {
                        addNoResultsText(R.string.no_conversations);
                    } else {
                        for (Team team : teams) {
                            addSearchResultCard(team);
                        }
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Search failed: %s", e.getMessage());
                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.failed)));
            }
        }).start();
    }

    private void addSearchResultCard(Team team) {
        boolean isMember = team.getMembers() != null && liveFid != null
                && team.getMembers().contains(liveFid);
        boolean isInvited = team.getInvitees() != null && liveFid != null
                && team.getInvitees().contains(liveFid);
        boolean isTransferee = liveFid != null && liveFid.equals(team.getTransferee());

        // Reuse the card builder — treat as invite if invited, transfer if transferee
        if (isTransferee) {
            addTeamCard(team, true, false);
        } else if (isInvited || isMember) {
            addTeamCard(team, false, false);
        } else {
            // Not invited — show read-only card
            addReadOnlyCard(team);
        }
    }

    private void addReadOnlyCard(Team team) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding((int)(12*d), (int)(8*d), (int)(12*d), (int)(8*d));
        card.setBackgroundResource(R.drawable.container_outline);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, (int)(8*d));
        card.setLayoutParams(cardLp);
        card.setAlpha(0.7f);

        ImageView avatar = new ImageView(this);
        int avatarSize = (int)(48*d);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd((int)(12*d));
        avatar.setLayoutParams(avatarLp);
        setDefaultAvatar(avatar, d);
        card.addView(avatar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameView = new TextView(this);
        nameView.setText(team.getStdName() != null ? team.getStdName() : team.getId());
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTextColor(getResources().getColor(R.color.accent, null));
        nameView.setTypeface(nameView.getTypeface(), Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(nameView);

        TextView statusView = new TextView(this);
        statusView.setText(R.string.not_invited);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(getResources().getColor(R.color.hint, null));
        content.addView(statusView);
        card.addView(content);

        card.setOnClickListener(v -> {
            hideKeyboard();
            ToastUtils.makeText(this, getString(R.string.not_invited));
        });
        searchResultsLayout.addView(card);
    }

    // ========== TX Actions ==========

    /**
     * Before joining, download and show the team's public consensus document so the user
     * can read the principles they are about to agree to on-chain. The document is fetched
     * from the team's DISK (resolved from the on-chain {@code Team.home}) and integrity-verified
     * against {@code Team.consensusId}. Legacy teams without a consensusId join directly.
     */
    private void joinTeam(Team team) {
        if (team.getConsensusId() == null || team.getConsensusId().isEmpty()) {
            doJoin(team);
            return;
        }

        ToastUtils.makeText(this, getString(R.string.downloading_consensus_document));
        new Thread(() -> {
            HatManager hatManager = HatManager.getInstance(this, liveFid);
            ConsensusDocHelper helper = new ConsensusDocHelper(this, hatManager);
            File out = null;
            try {
                out = File.createTempFile("consensus_view_", ".tmp", getCacheDir());
                boolean ok = helper.downloadConsensusDoc(team, out);
                if (!ok) {
                    final String err = helper.getLastError();
                    runOnUiThread(() -> ToastUtils.makeText(this,
                            getString(R.string.failed_to_download_consensus_document)
                                    + (err != null ? ": " + err : "")));
                    return;
                }
                byte[] bytes = readAll(out);
                final String text = toText(bytes);
                runOnUiThread(() -> showConsensusDialog(team, text));
            } catch (Exception e) {
                TimberLogger.e(TAG, "Consensus review failed: %s", e.getMessage());
                runOnUiThread(() -> ToastUtils.makeText(this,
                        getString(R.string.failed_to_download_consensus_document)));
            } finally {
                if (out != null && out.exists()) out.delete();
            }
        }).start();
    }

    private void showConsensusDialog(Team team, String text) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView body = new TextView(this);
        body.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
        body.setTextIsSelectable(true);
        body.setText(text != null ? text : getString(R.string.consensus_not_text));
        scroll.addView(body);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(team.getStdName() != null ? team.getStdName() : getString(R.string.consensus_document))
                .setView(scroll)
                .setPositiveButton(R.string.accept_and_join, (dlg, w) -> doJoin(team))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private byte[] readAll(File file) throws java.io.IOException {
        byte[] data = new byte[(int) file.length()];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int total = 0;
            while (total < data.length) {
                int r = fis.read(data, total, data.length - total);
                if (r == -1) break;
                total += r;
            }
        }
        return data;
    }

    /** Decode bytes as UTF-8 text, or return null if they look binary (contain NUL bytes). */
    private String toText(byte[] bytes) {
        if (bytes == null) return null;
        for (byte b : bytes) {
            if (b == 0) return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void doJoin(Team team) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        ImManager imManager = setting != null ? setting.getImManager() : null;

        TeamTxHelper.sendJoinTx(this, team.getId(), imManager, new TeamTxHelper.TxResultCallback() {
            @Override
            public void onSuccess() {
                addToPendingAccepted(team.getId());
                runOnUiThread(JoinTeamActivity.this::finish);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> ToastUtils.makeText(JoinTeamActivity.this, message));
            }
        });
    }

    private void takeOverTeam(Team team) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        ImManager imManager = setting != null ? setting.getImManager() : null;

        TeamTxHelper.sendTakeOverTx(this, team.getId(), imManager, new TeamTxHelper.TxResultCallback() {
            @Override
            public void onSuccess() {
                addToPendingAccepted(team.getId());
                runOnUiThread(JoinTeamActivity.this::finish);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> ToastUtils.makeText(JoinTeamActivity.this, message));
            }
        });
    }

    private void openChat(String teamId, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.TEAM.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, teamId);
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, displayName);
        startActivity(intent);
    }

    // ========== Ignore set (MMKV) ==========

    private Set<String> loadIgnored() {
        if (mmkv == null) return new HashSet<>();
        try {
            String json = mmkv.decodeString(IGNORED_KEY);
            if (json != null) {
                Set<String> set = gson.fromJson(json, new TypeToken<Set<String>>(){}.getType());
                return set != null ? set : new HashSet<>();
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to load ignored set: %s", e.getMessage());
        }
        return new HashSet<>();
    }

    private void addToIgnored(String teamId) {
        if (mmkv == null || teamId == null) return;
        try {
            Set<String> set = loadIgnored();
            set.add(teamId);
            mmkv.encode(IGNORED_KEY, gson.toJson(set));
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to save ignored set: %s", e.getMessage());
        }
    }

    private void removeFromIgnored(String teamId) {
        if (mmkv == null || teamId == null) return;
        try {
            Set<String> set = loadIgnored();
            if (set.remove(teamId)) {
                mmkv.encode(IGNORED_KEY, gson.toJson(set));
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to update ignored set: %s", e.getMessage());
        }
    }

    // ========== Pending accepted set (MMKV) ==========

    private Map<String, Long> loadPendingAccepted() {
        if (mmkv == null) return new HashMap<>();
        try {
            String json = mmkv.decodeString(PENDING_ACCEPTED_KEY);
            if (json != null) {
                Map<String, Long> map = gson.fromJson(json,
                        new TypeToken<Map<String, Long>>(){}.getType());
                return map != null ? map : new HashMap<>();
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to load pending accepted: %s", e.getMessage());
        }
        return new HashMap<>();
    }

    private void addToPendingAccepted(String teamId) {
        if (mmkv == null || teamId == null) return;
        try {
            Map<String, Long> map = loadPendingAccepted();
            map.put(teamId, System.currentTimeMillis());
            mmkv.encode(PENDING_ACCEPTED_KEY, gson.toJson(map));
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to save pending accepted: %s", e.getMessage());
        }
    }

    private void cleanUpPendingAccepted() {
        if (mmkv == null) return;
        try {
            Map<String, Long> map = loadPendingAccepted();
            long cutoff = System.currentTimeMillis() - PENDING_ACCEPTED_TTL_MS;
            Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
            boolean changed = false;
            while (it.hasNext()) {
                if (it.next().getValue() < cutoff) { it.remove(); changed = true; }
            }
            if (changed) mmkv.encode(PENDING_ACCEPTED_KEY, gson.toJson(map));
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to clean pending accepted: %s", e.getMessage());
        }
    }
}
