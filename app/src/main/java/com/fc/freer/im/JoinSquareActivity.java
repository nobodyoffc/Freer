package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.NAME;
import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.SquareOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.im.handler.SquareHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import java.util.List;

/**
 * Activity for searching and joining an on-chain square.
 */
public class JoinSquareActivity extends BaseCryptoActivity {
    private static final String TAG = "JoinSquareActivity";

    private EditText searchEditText;
    private View clearSearchIcon;
    private ImageView searchIcon;
    private ImageButton clearButton;
    private LinearLayout searchResultsLayout;

    private String liveFid;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_join_group;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.join_square);
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
                fcdsl.addNewQuery().addNewPart().addNewFields(NAME).addNewValue(query);
                fcdsl.addSize(20);
                List<Square> squares = fapiClient.entitySearch(SQUARE, fcdsl, Square.class);

                runOnUiThread(() -> {
                    if (squares == null || squares.isEmpty()) {
                        TextView noResults = new TextView(this);
                        noResults.setText(R.string.no_conversations);
                        noResults.setTextColor(getResources().getColor(R.color.hint, null));
                        noResults.setPadding(16, 16, 16, 16);
                        searchResultsLayout.addView(noResults);
                    } else {
                        for (Square square : squares) {
                            addSquareCard(square);
                        }
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Search failed: %s", e.getMessage());
                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.failed)));
            }
        }).start();
    }

    private void addSquareCard(Square square) {
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

        // Avatar
        ImageView avatarView = new ImageView(this);
        int avatarSize = (int)(48 * density);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd((int)(12 * density));
        avatarView.setLayoutParams(avatarLp);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // No outline clip and no background: a group tile carries its own
        // rounded-square corners, and the oval would clip them off.
        avatarView.setClipToOutline(false);
        avatarView.setBackground(null);

        String avatarFid = null;
        if (square.getNamers() != null && !square.getNamers().isEmpty()) {
            avatarFid = square.getNamers().get(square.getNamers().size() - 1);
        }
        // The tile comes from the square's id; the last namer is only the
        // badge on it, and an absent one simply leaves the tile unbadged.
        Bitmap avatarBitmap = AvatarManager.getInstance(this)
                .getGroupAvatarBitmap(square.getId(), avatarFid, avatarSize);
        if (avatarBitmap != null) {
            avatarView.setImageBitmap(avatarBitmap);
            avatarView.setImageTintList(null);
        } else {
            setDefaultAvatar(avatarView, density);
        }
        card.addView(avatarView);

        // Right content
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Group name
        TextView nameView = new TextView(this);
        nameView.setText(square.getName() != null ? square.getName() : square.getId());
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTextColor(getResources().getColor(R.color.accent, null));
        nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        contentLayout.addView(nameView);

        // Group ID
        TextView idView = new TextView(this);
        String squareId = square.getId() != null ? square.getId() : "";
        idView.setText(squareId);
        idView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        idView.setTextColor(getResources().getColor(R.color.hint, null));
        idView.setMaxLines(1);
        idView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        contentLayout.addView(idView);

        // Metrics row
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
        int redColor = getResources().getColor(R.color.red, null);

        // ic_person + memberNum
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
        long memberNum = square.getMemberNum() != null ? square.getMemberNum() : 0;
        memberNumText.setText(String.valueOf(memberNum));
        LinearLayout.LayoutParams memberLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        memberLp.setMarginEnd((int)(8 * density));
        memberNumText.setLayoutParams(memberLp);
        metricsRow.addView(memberNumText);

        // ic_hot + tCdd
        ImageView hotIcon = new ImageView(this);
        LinearLayout.LayoutParams hotIconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        hotIconLp.setMarginEnd((int)(2 * density));
        hotIcon.setLayoutParams(hotIconLp);
        hotIcon.setImageResource(R.drawable.ic_hot);
        hotIcon.setImageTintList(ColorStateList.valueOf(redColor));
        metricsRow.addView(hotIcon);

        TextView tCddText = new TextView(this);
        tCddText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tCddText.setTextColor(hintColor);
        long tCdd = square.gettCdd() != null ? square.gettCdd() : 0;
        tCddText.setText(String.valueOf(tCdd));
        metricsRow.addView(tCddText);

        contentLayout.addView(metricsRow);

        // Already a member indicator
        boolean isMember = square.getMembers() != null && liveFid != null
                && square.getMembers().contains(liveFid);
        if (isMember) {
            TextView alreadyMember = new TextView(this);
            alreadyMember.setText(R.string.already_a_member);
            alreadyMember.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            alreadyMember.setTextColor(getResources().getColor(R.color.colorAccent, null));
            LinearLayout.LayoutParams memberTagLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            memberTagLp.topMargin = (int)(2 * density);
            alreadyMember.setLayoutParams(memberTagLp);
            contentLayout.addView(alreadyMember);
        }

        card.addView(contentLayout);

        card.setOnClickListener(v -> {
            hideKeyboard();
            if (isMember) {
                openChat(square.getId(), square.getName());
            } else {
                joinSquare(square);
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

    private void joinSquare(Square square) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        SquareOpData opData = SquareOpData.makeJoin(square.getId());
        Feip feip = Feip.fromName("Square");
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
                            Setting setting = SettingManager.getInstance().getCurrentSetting();
                            if (setting != null && setting.getImManager() != null) {
                                ImManager im = setting.getImManager();
                                SquareHandler squareHandler = im.getSquareHandler();
                                if (squareHandler != null) {
                                    squareHandler.savePendingSquare(square);
                                }
                                // Create the conversation immediately so the joined square is
                                // visible right away, independent of the cursor-based sync
                                // rediscovering it (which can permanently skip squares whose
                                // lastHeight sits below the local sync cursor).
                                createSquareConversation(im, square);
                            }
                            runOnUiThread(() -> finish());
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(JoinSquareActivity.this, errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    /**
     * Persist a Conversation row for a freshly-joined square so it appears in the
     * Square list without waiting for {@link SquareSyncManager} to rediscover it.
     */
    private void createSquareConversation(ImManager im, Square square) {
        if (im == null || square == null || square.getId() == null) return;
        LocalDB<Conversation> convDb = im.getConversationsDb();
        if (convDb == null) return;

        String convId = ImType.SQUARE.name() + "_" + square.getId();
        if (convDb.get(convId) != null) return;

        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setType(ImType.SQUARE);
        conv.setTargetId(square.getId());
        conv.setLeftGroup(false);
        conv.setCreatedAt(square.getBirthTime());
        conv.setDisplayName(square.getName());
        if (square.getNamers() != null && !square.getNamers().isEmpty()) {
            conv.setAvatarDid(square.getNamers().get(square.getNamers().size() - 1));
        }
        conv.setMemberNum(square.getMemberNum());
        conv.settCdd(square.gettCdd());
        convDb.put(convId, conv);

        if (im.getDockRegistry() != null && im.getFapiClient() != null) {
            im.getDockRegistry().registerSquare(square, im.getFapiClient());
        }
    }

    private void openChat(String groupId, String displayName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.SQUARE.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, groupId);
        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, displayName);
        startActivity(intent);
    }
}
