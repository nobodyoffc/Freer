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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.Arrays;
import java.util.List;

public class JoinTeamActivity extends BaseCryptoActivity {
    private static final String TAG = "JoinTeamActivity";

    private EditText searchEditText;
    private View clearSearchIcon;
    private ImageView searchIcon;
    private ImageButton clearButton;
    private LinearLayout searchResultsLayout;

    private String liveFid;

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

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo != null) {
            liveFid = liveKeyInfo.getId();
        }

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                performSearch();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void setupButtons() {
        searchIcon.setOnClickListener(v -> {
            hideKeyboard();
            performSearch();
        });

        clearSearchIcon.setOnClickListener(v -> {
            searchEditText.setText("");
            hideKeyboard();
        });

        clearButton.setOnClickListener(v -> {
            searchEditText.setText("");
            searchResultsLayout.removeAllViews();
            hideKeyboard();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void performSearch() {
        String query = searchEditText.getText().toString().trim();
        if (query.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.please_enter_search_query));
            return;
        }

        searchResultsLayout.removeAllViews();
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
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
                        TextView noResults = new TextView(this);
                        noResults.setText(R.string.no_conversations);
                        noResults.setTextColor(getResources().getColor(R.color.hint, null));
                        noResults.setPadding(16, 16, 16, 16);
                        searchResultsLayout.addView(noResults);
                    } else {
                        for (Team team : teams) {
                            addTeamCard(team);
                        }
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Search failed: %s", e.getMessage());
                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.failed)));
            }
        }).start();
    }

    private void addTeamCard(Team team) {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding((int)(12 * density), (int)(8 * density), (int)(12 * density), (int)(8 * density));
        card.setBackgroundResource(R.drawable.container_outline);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, (int)(8 * density));
        card.setLayoutParams(cardLp);

        ImageView avatarView = new ImageView(this);
        int avatarSize = (int)(48 * density);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd((int)(12 * density));
        avatarView.setLayoutParams(avatarLp);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setClipToOutline(true);
        avatarView.setBackgroundResource(R.drawable.avatar_background);

        if (team.getOwner() != null) {
            AvatarManager avatarManager = AvatarManager.getInstance(this);
            Bitmap avatarBitmap = avatarManager.getAvatarBitmap(team.getOwner());
            if (avatarBitmap != null) {
                avatarView.setImageBitmap(avatarBitmap);
                avatarView.setImageTintList(null);
            } else {
                setDefaultAvatar(avatarView, density);
            }
        } else {
            setDefaultAvatar(avatarView, density);
        }
        card.addView(avatarView);

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameView = new TextView(this);
        nameView.setText(team.getStdName() != null ? team.getStdName() : team.getId());
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTextColor(getResources().getColor(R.color.accent, null));
        nameView.setTypeface(nameView.getTypeface(), Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        contentLayout.addView(nameView);

        TextView idView = new TextView(this);
        idView.setText(team.getId() != null ? team.getId() : "");
        idView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        idView.setTextColor(getResources().getColor(R.color.hint, null));
        idView.setMaxLines(1);
        idView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        contentLayout.addView(idView);

        LinearLayout metricsRow = new LinearLayout(this);
        metricsRow.setOrientation(LinearLayout.HORIZONTAL);
        metricsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metricsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metricsLp.topMargin = (int)(4 * density);
        metricsRow.setLayoutParams(metricsLp);

        int iconSize = (int)(16 * density);
        int hintColor = getResources().getColor(R.color.hint, null);
        int accentColor = getResources().getColor(R.color.accent, null);

        ImageView personIcon = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd((int)(2 * density));
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
        memberLp.setMarginEnd((int)(8 * density));
        memberNumText.setLayoutParams(memberLp);
        metricsRow.addView(memberNumText);

        contentLayout.addView(metricsRow);

        boolean isMember = team.getMembers() != null && liveFid != null
                && Arrays.asList(team.getMembers()).contains(liveFid);
        boolean isInvited = team.getInvitees() != null && liveFid != null
                && Arrays.asList(team.getInvitees()).contains(liveFid);

        if (isMember) {
            TextView statusView = new TextView(this);
            statusView.setText(R.string.already_a_member);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            statusView.setTextColor(getResources().getColor(R.color.colorAccent, null));
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = (int)(2 * density);
            statusView.setLayoutParams(statusLp);
            contentLayout.addView(statusView);
        } else if (isInvited) {
            TextView statusView = new TextView(this);
            statusView.setText(R.string.invited);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            statusView.setTextColor(getResources().getColor(R.color.colorAccent, null));
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = (int)(2 * density);
            statusView.setLayoutParams(statusLp);
            contentLayout.addView(statusView);
        } else {
            TextView statusView = new TextView(this);
            statusView.setText(R.string.not_invited);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            statusView.setTextColor(getResources().getColor(R.color.hint, null));
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = (int)(2 * density);
            statusView.setLayoutParams(statusLp);
            contentLayout.addView(statusView);
        }

        card.addView(contentLayout);

        card.setOnClickListener(v -> {
            hideKeyboard();
            if (isMember) {
                openChat(team.getId(), team.getStdName());
            } else if (isInvited) {
                joinTeam(team);
            } else {
                ToastUtils.makeText(this, getString(R.string.not_invited));
            }
        });

        searchResultsLayout.addView(card);
    }

    private void setDefaultAvatar(ImageView avatarView, float density) {
        avatarView.setImageResource(R.drawable.ic_person);
        int pad = (int)(8 * density);
        avatarView.setPadding(pad, pad, pad, pad);
        avatarView.setImageTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.hint, null)));
    }

    private void joinTeam(Team team) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        TeamOpData opData = TeamOpData.makeJoin(team.getId(), team.getConsensusId());
        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(JoinTeamActivity.this, getString(R.string.team_joined_successfully));
                                openChat(team.getId(), team.getStdName());
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(JoinTeamActivity.this, errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void openChat(String teamId, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.TEAM.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, teamId);
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, displayName);
        startActivity(intent);
    }
}
