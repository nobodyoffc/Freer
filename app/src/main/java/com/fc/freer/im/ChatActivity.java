package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.SquareOpData;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.im.adapter.MessageAdapter;
import com.fc.freer.im.emoji.EmojiAdapter;
import com.fc.freer.im.emoji.EmojiData;
import com.fc.freer.im.handler.TeamHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.mail.CreateMailActivity;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.data.DataSyncManager;
import com.fc.freer.data.HatFileOpener;
import com.fc.freer.manager.HatManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.im.dock.DockServiceRegistry;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ServicePickerUtils;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.im.voice.VoiceMessageHelper;
import com.fc.freer.im.voice.VoicePlayer;
import com.fc.freer.im.voice.VoiceRecorder;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatActivity extends BaseCryptoActivity
        implements ImManager.ImListener, MessageAdapter.MessageInteractionListener {
    private static final String TAG = "ChatActivity";
    
    public static final String EXTRA_TYPE = "im_type";
    public static final String EXTRA_TARGET_ID = "target_id";
    public static final String EXTRA_DISPLAY_NAME = "display_name";

    private static final String DRAFT_KEY_PREFIX = "chat_draft_";
    
    private RecyclerView messagesRecyclerView;
    private EditText messageInput;
    private ImageButton sendButton;
    private ImageButton attachButton;
    private ImageButton emojiButton;
    private ImageButton btnMore;
    private ImageButton btnClearInput;
    private ImageButton btnMail;
    private ImageButton btnBack;
    private View inputArea;
    
    private TextView chatTypeInfo;

    /**
     * The outstanding-symkey-ask banner (§7): who was asked, how long ago, and the two things
     * a person can do about it. An ask with nothing on screen reads as a request that went
     * nowhere, which is exactly what it used to be.
     */
    private LinearLayout symkeyAskBanner;
    private TextView symkeyAskSummary;
    private TextView symkeyAskWho;

    private LinearLayout channelInfoLayout;
    private TextView channelInfoLeft;
    private TextView channelInfoRight;
    
    private LinearLayout emojiPanel;
    private RecyclerView emojiRecyclerView;
    private LinearLayout emojiCategoryTabs;
    private EmojiAdapter emojiAdapter;
    private boolean isEmojiPanelVisible = false;
    private EmojiData.EmojiCategory selectedCategory;
    private TextView selectedCategoryTab;
    
    private MessageAdapter adapter;
    private List<ImMessage> messages = new ArrayList<>();

    /** Active HAT file downloads by hatId, for card progress state and cancellation. */
    private final java.util.Map<String, HatFileOpener.DownloadHandle> activeHatDownloads = new java.util.HashMap<>();
    /** Last UI refresh time per downloading hatId, to throttle progress rebinds. */
    private final java.util.Map<String, Long> hatProgressUiUpdate = new java.util.HashMap<>();
    private AlertDialog cancelDownloadDialog;
    private String cancelDownloadHatId;

    private ImManager imManager;
    private String liveFid;
    /** History issues already prompted on this screen, so onResume does not re-ask. */
    private final java.util.Set<String> promptedHistoryRequests = new java.util.HashSet<>();
    private ImType imType;
    private String targetId;
    private String displayName;
    private boolean groupHasDock = true;
    /** No own DOCK: sending works (direct put to recipient's DOCK), receiving doesn't. */
    private boolean sendOnlyMode = false;
    private ImageView toolbarAvatarView;
    private TextView toolbarTitleView;
    private CharSequence toolbarTitleText;
    private boolean partnerNobodyNoticeShown;
    private boolean strangerConfirmShown = false;
    private WaitingDialog leaveTeamWaitingDialog;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> appointManagerLauncher;
    private ActivityResultLauncher<Intent> cancelAppointmentLauncher;
    private ActivityResultLauncher<Intent> dismissMemberLauncher;
    private ActivityResultLauncher<Intent> transferTeamLauncher;
    private ActivityResultLauncher<Intent> addRoomMemberLauncher;
    private ActivityResultLauncher<Intent> removeRoomMemberLauncher;
    private ActivityResultLauncher<Intent> shareRoomInfoLauncher;
    private ActivityResultLauncher<Intent> shareRoomSymkeyLauncher;
    private ActivityResultLauncher<Intent> chooseRoomDockLauncher;
    /** Dock input of the currently open update-room dialog; target of {@link #chooseRoomDockLauncher}. */
    private EditText updateRoomDockInput;

    // Voice message
    private ImageButton micButton;
    private View recordingOverlay;
    private TextView recordingTimer;
    private VoiceRecorder voiceRecorder;
    private VoicePlayer voicePlayer;
    private ActivityResultLauncher<String> recordAudioPermissionLauncher;
    private boolean isRecording = false;
    private boolean isCancelSlide = false;
    private static final float CANCEL_SLIDE_THRESHOLD = -100f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        imType = ImType.fromString(getIntent().getStringExtra(EXTRA_TYPE));
        targetId = getIntent().getStringExtra(EXTRA_TARGET_ID);
        displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        
        initFilePickerLauncher();
        initTeamOperationLaunchers();
        initRoomOperationLaunchers();
        initRecordAudioPermissionLauncher();

        super.onCreate(savedInstanceState);
        
        if (imType == null || targetId == null) {
            TimberLogger.e(TAG, "Missing required extras");
            finish();
            return;
        }
        
        initManager();
        setupToolbarWithAvatar();
    }

    /**
     * When opening a P2P chat that already has an unresolved stranger request
     * (e.g. the first message arrived while this screen was not in foreground,
     * so it sits quarantined and hidden), surface the confirmation prompt.
     */
    private void checkPendingStrangerOnEntry() {
        if (imType != ImType.P2P || imManager == null || targetId == null) return;
        PendingIssueManager mgr = imManager.getPendingIssueManager();
        if (mgr == null) return;
        PendingIssue issue = mgr.getIssue("STRANGER_PEER_" + targetId);
        if (issue != null && issue.getStatus() == PendingIssue.IssueStatus.PENDING) {
            onStrangerConfirmationRequired(targetId);
        }
    }
    
    @Override
    protected int getLayoutId() {
        return R.layout.activity_chat;
    }
    
    @Override
    protected String getActivityTitle() {
        return "";
    }
    
    @Override
    protected void initializeViews() {
        messagesRecyclerView = findViewById(R.id.messages_recycler_view);
        messageInput = findViewById(R.id.message_input);
        sendButton = findViewById(R.id.send_button);
        attachButton = findViewById(R.id.attach_button);
        emojiButton = findViewById(R.id.emoji_button);
        
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messagesRecyclerView.setLayoutManager(layoutManager);
        
        adapter = new MessageAdapter(messages, null, imType, this);
        messagesRecyclerView.setAdapter(adapter);
        NobodyUi.observe(this, this::onNobodiesLearned);
        
        micButton = findViewById(R.id.mic_button);
        recordingOverlay = findViewById(R.id.recording_overlay);
        recordingTimer = findViewById(R.id.recording_timer);

        voiceRecorder = new VoiceRecorder(this);
        voicePlayer = VoicePlayer.getInstance();
        voicePlayer.init(this);

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s.length() > 0;
                sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                micButton.setVisibility(hasText ? View.GONE : View.VISIBLE);
                if (messageInput.isEnabled()) {
                    sendButton.setEnabled(hasText);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        messageInput.setOnClickListener(v -> {
            if (isEmojiPanelVisible) {
                hideEmojiPanel();
            }
        });

        sendButton.setVisibility(View.GONE);
        micButton.setVisibility(View.VISIBLE);
        sendButton.setEnabled(false);
        
        btnMore = findViewById(R.id.btn_more);
        btnClearInput = findViewById(R.id.btn_clear_input);
        btnMail = findViewById(R.id.btn_mail);
        btnBack = findViewById(R.id.btn_back);
        inputArea = findViewById(R.id.input_area);
        chatTypeInfo = findViewById(R.id.chat_type_info);
        symkeyAskBanner = findViewById(R.id.symkey_ask_banner);
        symkeyAskSummary = findViewById(R.id.symkey_ask_summary);
        symkeyAskWho = findViewById(R.id.symkey_ask_who);
        channelInfoLayout = findViewById(R.id.channel_info_layout);
        channelInfoLeft = findViewById(R.id.channel_info_left);
        channelInfoRight = findViewById(R.id.channel_info_right);
        
        setupChatTypeInfo();
        setupSymkeyAskBanner();
        setupEmojiPanel();
    }
    
    @Override
    protected void setupButtons() {
        sendButton.setOnClickListener(v -> sendMessage());

        micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    isCancelSlide = false;
                    requestRecordingPermissionAndStart();
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (isRecording && event.getX() < CANCEL_SLIDE_THRESHOLD) {
                        isCancelSlide = true;
                        TextView hint = findViewById(R.id.recording_slide_hint);
                        if (hint != null) hint.setTextColor(Color.RED);
                    } else if (isRecording) {
                        isCancelSlide = false;
                        TextView hint = findViewById(R.id.recording_slide_hint);
                        if (hint != null) hint.setTextColor(ContextCompat.getColor(this, R.color.hint));
                    }
                }
                case MotionEvent.ACTION_UP -> {
                    if (isRecording) {
                        if (isCancelSlide) {
                            cancelRecording();
                        } else {
                            stopRecordingAndSend();
                        }
                    }
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) cancelRecording();
                }
            }
            return true;
        });

        attachButton.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            showAttachOptions();
        });
        
        emojiButton.setOnClickListener(v -> toggleEmojiPanel());
        
        btnMore.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            if (imType == ImType.P2P) {
                showP2pChatMenu(v);
            } else if (imType == ImType.TEAM) {
                showTeamChatMenu(v);
            } else if (imType == ImType.ROOM) {
                showRoomChatMenu(v);
            } else if (imType == ImType.SQUARE) {
                showGroupChatMenu(v);
            }
        });

        btnClearInput.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            messageInput.setText("");
            sendButton.setEnabled(false);
        });

        btnMail.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            openCreateMail();
        });
        
        btnBack.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            finish();
        });
    }
    
    // ========== Symkey asks (docs/SYMKEY_IDENTITY_SPEC.md §7) ==========

    private void setupSymkeyAskBanner() {
        if (symkeyAskBanner == null) return;

        View askMore = findViewById(R.id.symkey_ask_more);
        if (askMore != null) {
            askMore.setOnClickListener(v -> {
                hideKeyboard();
                openAskSymkey(null);
            });
        }

        View giveUp = findViewById(R.id.symkey_ask_give_up);
        if (giveUp != null) {
            giveUp.setOnClickListener(v -> {
                hideKeyboard();
                KeyAskStore asks = imManager != null ? imManager.getKeyAskStore() : null;
                if (asks != null) asks.giveUpAll(targetId);
                ToastUtils.makeText(this, getString(R.string.symkey_ask_gave_up));
                refreshSymkeyAskBanner();
            });
        }
    }

    /** Repaint the banner from what is actually outstanding. Safe to call from anywhere. */
    private void refreshSymkeyAskBanner() {
        if (symkeyAskBanner == null) return;
        if (imType != ImType.TEAM && imType != ImType.ROOM) {
            symkeyAskBanner.setVisibility(View.GONE);
            return;
        }

        KeyAskStore asks = imManager != null ? imManager.getKeyAskStore() : null;
        List<KeyAskStore.KeyAsk> outstanding = asks != null ? asks.outstanding(targetId) : null;
        if (outstanding == null || outstanding.isEmpty()) {
            symkeyAskBanner.setVisibility(View.GONE);
            return;
        }

        Set<String> asked = new java.util.LinkedHashSet<>();
        long lastAskedAt = 0;
        Set<Long> versions = new HashSet<>();
        for (KeyAskStore.KeyAsk ask : outstanding) {
            asked.addAll(ask.getAskedFids());
            lastAskedAt = Math.max(lastAskedAt, ask.lastAskedAt());
            if (ask.getVersion() != null && ask.getVersion() != KeyAskStore.CURRENT) {
                versions.add(ask.getVersion());
            }
        }
        String when = lastAskedAt > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(
                        lastAskedAt, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                : "";

        // One version names itself by its mint time; several are only a count, because a list
        // of timestamps in a banner is noise nobody reads.
        if (outstanding.size() == 1 && versions.size() == 1) {
            long version = versions.iterator().next();
            symkeyAskSummary.setText(getString(R.string.symkey_ask_banner_one,
                    SymkeyVersionText.prose(this, version, versions), when));
        } else {
            symkeyAskSummary.setText(getString(R.string.symkey_ask_banner_many,
                    outstanding.size(), when));
        }

        if (asked.isEmpty()) {
            symkeyAskWho.setVisibility(View.GONE);
        } else {
            StringBuilder names = new StringBuilder();
            for (String fid : asked) {
                if (names.length() > 0) names.append(", ");
                names.append(displayFid(fid));
            }
            symkeyAskWho.setVisibility(View.VISIBLE);
            symkeyAskWho.setText(getString(R.string.symkey_ask_banner_who, names.toString()));
        }

        symkeyAskBanner.setVisibility(View.VISIBLE);
    }

    /** CID when we know one, else the FID elided in the middle. */
    private String displayFid(String fid) {
        if (fid == null) return "";
        CidFidManager cidFidManager = CidFidManager.getInstance();
        String cid = cidFidManager != null ? cidFidManager.getCidByFid(fid) : null;
        if (cid != null && !cid.isEmpty()) return cid;
        if (fid.length() <= 14) return fid;
        return fid.substring(0, 6) + "…" + fid.substring(fid.length() - 6);
    }

    /** The member picker, optionally scoped to the one version a row needs. */
    private void openAskSymkey(Long version) {
        if (imType != ImType.TEAM && imType != ImType.ROOM) return;
        Intent intent = new Intent(this, AskSymkeyActivity.class);
        intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_ID, targetId);
        intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_TYPE, imType == ImType.TEAM ? "team" : "room");
        if (version != null) intent.putExtra(AskSymkeyActivity.EXTRA_VERSION, version);
        startActivity(intent);
    }

    /**
     * The "Ask for this symkey…" on a sealed row -- §7. Scoped to that row's version, because
     * that is the one key that would open it; asking for "the symkey" fetches the current one,
     * which is usually the one this device already holds.
     */
    @Override
    public void onAskForSymkey(ImMessage message, long version) {
        if (imManager == null) return;
        String senderFid = message != null ? message.getSenderId() : null;

        // The sender provably holds this key. The owner may have rotated past it, or be gone.
        if (senderFid != null && !senderFid.equals(liveFid)) {
            imManager.requestSymkey(targetId, version, senderFid);
        } else {
            imManager.requestSymkey(targetId, version, null);
        }
        ToastUtils.makeText(this, getString(R.string.symkey_ask_which_version,
                SymkeyVersionText.prose(this, version)));
        messagesRecyclerView.postDelayed(() -> {
            refreshSymkeyAskBanner();
            adapter.notifyDataSetChanged();
        }, 400);
    }

    private void setupChatTypeInfo() {
        if (chatTypeInfo == null || imType == null) return;
        
        chatTypeInfo.setVisibility(View.VISIBLE);
        switch (imType) {
            case SQUARE:
                chatTypeInfo.setText(R.string.chat_info_square);
                chatTypeInfo.setTextColor(Color.RED);
                break;
            case TEAM:
                chatTypeInfo.setText(R.string.chat_info_team);
                chatTypeInfo.setTextColor(getResources().getColor(R.color.hint, getTheme()));
                break;
            case ROOM:
                chatTypeInfo.setText(R.string.chat_info_room);
                chatTypeInfo.setTextColor(getResources().getColor(R.color.hint, getTheme()));
                break;
            case P2P:
                chatTypeInfo.setText(R.string.chat_info_p2p);
                chatTypeInfo.setTextColor(getResources().getColor(R.color.hint, getTheme()));
                break;
            default:
                chatTypeInfo.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }
    
    // ── Toolbar setup ──────────────────────────────────────────────────

    private void setupToolbarWithAvatar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null || getSupportActionBar() == null) return;
        
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        
        LinearLayout titleLayout = new LinearLayout(this);
        titleLayout.setOrientation(LinearLayout.HORIZONTAL);
        titleLayout.setGravity(Gravity.CENTER_VERTICAL);
        
        float density = getResources().getDisplayMetrics().density;
        int avatarSize = (int) (42 * density);
        int paddingStart = (int) (8 * density);
        int paddingEnd = (int) (4 * density);
        FrameLayout avatarContainer = new FrameLayout(this);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarContainer.setLayoutParams(containerParams);
        
        ImageView avatarView = new ImageView(this);
        FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(avatarSize, avatarSize);
        avatarView.setLayoutParams(avatarParams);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setBackgroundColor(getResources().getColor(R.color.accent, getTheme()));
        avatarContainer.addView(avatarView);

        titleLayout.addView(avatarContainer);
        
        int textWidth = (int) (130 * density);

        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textContainerParams = new LinearLayout.LayoutParams(
                textWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
        textContainer.setLayoutParams(textContainerParams);
        textContainer.setPadding(paddingStart, 0, paddingEnd, 0);

        if (imType == ImType.SQUARE || imType == ImType.TEAM || imType == ImType.ROOM) {
            TextView typeLabel = new TextView(this);
            typeLabel.setTextSize(14);
            typeLabel.setMaxLines(1);
            typeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String idBrief = targetId.substring(targetId.length() - 4);
            switch (imType) {
                case SQUARE:
                    typeLabel.setText(getString(R.string.square) + " " + idBrief);
                    typeLabel.setTextColor(Color.RED);
                    break;
                case TEAM:
                    typeLabel.setText(getString(R.string.team) + " " + idBrief);
                    typeLabel.setTextColor(android.graphics.Color.parseColor("#FF8800"));
                    break;
                case ROOM:
                    typeLabel.setText(getString(R.string.room) + " " + idBrief);
                    typeLabel.setTextColor(Color.WHITE);
                    break;
            }
            textContainer.addView(typeLabel);
        }

        TextView titleView = new TextView(this);
        titleView.setTextColor(getResources().getColor(R.color.main_background, getTheme()));
        titleView.setTextSize(14);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

        String title = displayName != null ? displayName : targetId;
        titleView.setText(StringUtils.omitMiddle(title, 20));
        textContainer.addView(titleView);

        titleLayout.addView(textContainer);
        
        Toolbar.LayoutParams layoutParams = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        toolbar.addView(titleLayout, layoutParams);
        
        if (imType == ImType.P2P) {
            setupP2pToolbar(avatarContainer, avatarView, titleView);
        } else if (imType == ImType.SQUARE || imType == ImType.TEAM || imType == ImType.ROOM) {
            setupGroupToolbar(avatarContainer, avatarView, titleView);
            updateChannelInfo();
        } else {
            loadToolbarAvatar(avatarView, targetId);
        }
    }

    private void setupP2pToolbar(FrameLayout avatarContainer, ImageView avatarView, TextView titleView) {
        avatarContainer.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            showFreerDetail(targetId);
        });
        
        titleView.setOnClickListener(v -> {
            hideKeyboard();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("FID", targetId);
            clipboard.setPrimaryClip(clip);
            ToastUtils.makeText(this, getString(R.string.fid_copied_to_clipboard));
        });
        
        titleView.setOnLongClickListener(v -> {
            hideKeyboard();
            FreerApplication.addFid(targetId);
            ToastUtils.makeText(this, getString(R.string.fid_added_to_list));
            return true;
        });

        toolbarAvatarView = avatarView;
        toolbarTitleView = titleView;
        toolbarTitleText = titleView.getText();
        NobodyUi.setName(titleView, targetId, toolbarTitleText);
        loadToolbarAvatar(avatarView, targetId);
    }

    /** Re-draw the P2P toolbar once the partner is learned to be a nobody. */
    private void refreshPartnerNobodyMark() {
        if (toolbarTitleView != null) NobodyUi.setName(toolbarTitleView, targetId, toolbarTitleText);
        if (toolbarAvatarView != null) loadToolbarAvatar(toolbarAvatarView, targetId);
    }

    private void setupGroupToolbar(FrameLayout avatarContainer,
                                    ImageView avatarView, TextView titleView) {
        // Who the tile badges. Null until a record says otherwise — an
        // unknown owner draws no badge rather than falling back to the
        // group id, which is not a person.
        String avatarFid = null;

        if (imType == ImType.TEAM) {
            Team team = imManager != null ? imManager.getTeam(targetId) : null;
            if (team != null) {
                if (team.getOwner() != null) {
                    avatarFid = team.getOwner();
                }
                if (team.getStdName() != null) {
                    titleView.setText(StringUtils.omitMiddle(team.getStdName(), 20));
                }
            }
        } else if (imType == ImType.ROOM) {
            Room room = imManager != null ? imManager.getRoom(targetId) : null;
            if (room != null) {
                if (room.getOwner() != null) {
                    avatarFid = room.getOwner();
                }
                if (room.getName() != null) {
                    titleView.setText(StringUtils.omitMiddle(room.getName(), 20));
                }
            }
        } else {
                Square square = imManager != null ? imManager.getSquare(targetId) : null;
            if (square != null) {
                if (square.getNamers() != null && !square.getNamers().isEmpty()) {
                    avatarFid = square.getNamers().get(square.getNamers().size() - 1);
                }
                if (square.getName() != null) {
                    titleView.setText(StringUtils.omitMiddle(square.getName(), 20));
                }
            }
        }

        loadToolbarGroupAvatar(avatarView, targetId, avatarFid);

        avatarContainer.setOnClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            showEntityDetail();
        });

        avatarContainer.setOnLongClickListener(v -> {
            hideKeyboard();
            hideEmojiPanel();
            openMemberList();
            return true;
        });

        titleView.setOnClickListener(v -> {
            hideKeyboard();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("SID", targetId);
            clipboard.setPrimaryClip(clip);
            ToastUtils.makeText(this, getString(R.string.sid_copied_to_clipboard));
        });
    }
    
    private void updateChannelInfo() {
        if (channelInfoLayout == null || imType == null) return;
        if (imType == ImType.P2P) return;
        if (imManager == null) return;

        Map<String, String> home = null;
        if (imType == ImType.TEAM) {
            Team team = imManager.getTeam(targetId);
            if (team != null) home = team.getHome();
        } else if (imType == ImType.ROOM) {
            Room room = imManager.getRoom(targetId);
            if (room != null) home = room.getHome();
        } else if (imType == ImType.SQUARE) {
            Square square = imManager.getSquare(targetId);
            if (square != null) home = square.getHome();
        }

        if (home == null || home.isEmpty()) return;

        String dockInfo = null;
        String channelInfo = null;
        String dockHomeValue = null;

        for (Map.Entry<String, String> entry : home.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (key == null || val == null || val.isEmpty()) continue;

            if (key.startsWith("DOCK") && dockInfo == null) {
                dockInfo = getString(R.string.channel_dock, val);
                dockHomeValue = val;
            } else if (key.startsWith("FUDP") && channelInfo == null) {
                channelInfo = getString(R.string.channel_fudp, val);
            } else if (key.startsWith("ROAD") && channelInfo == null) {
                channelInfo = getString(R.string.channel_road, val);
            }
        }

        if (dockInfo != null || channelInfo != null) {
            channelInfoLayout.setVisibility(View.VISIBLE);
            if (channelInfoLeft != null) {
                channelInfoLeft.setText(dockInfo != null ? dockInfo : "");
            }
            if (channelInfoRight != null) {
                channelInfoRight.setText(channelInfo != null ? channelInfo : "");
            }
        }

        if (dockHomeValue != null) {
            String days = imManager.getDockRegistry() != null
                    ? imManager.getDockRegistry().getDockExpiryDays(dockHomeValue) : null;
            if (days != null && !days.isEmpty()) {
                appendExpiryInfo(channelInfoLeft, days);
                appendExpiryInfo(channelInfoRight, days);
            }
        }
    }

    private void appendExpiryInfo(TextView textView, String days) {
        if (textView == null) return;
        CharSequence existing = textView.getText();
        if (existing == null || existing.length() == 0) return;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(existing);
        sb.append("  ");

        Drawable timerDrawable = ContextCompat.getDrawable(this, R.drawable.ic_timer);
        if (timerDrawable != null) {
            int size = (int) textView.getTextSize();
            timerDrawable.setBounds(0, 0, size, size);
            sb.append(" ");
            sb.setSpan(new ImageSpan(timerDrawable, ImageSpan.ALIGN_BASELINE),
                    sb.length() - 1, sb.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append(days).append("d");
        textView.setText(sb);
    }

    /**
     * The toolbar avatar for a group: the tile drawn from the group's own
     * id, badged with {@code ownerFid}. The plain {@link #loadToolbarAvatar}
     * must not be used here — it would read a room id or a txid as a FID
     * and put a stranger's face on the group.
     */
    private void loadToolbarGroupAvatar(ImageView avatarView, String groupId, String ownerFid) {
        // The accent fill would otherwise show through the tile's rounded
        // corners.
        avatarView.setBackgroundColor(Color.TRANSPARENT);
        int sizePx = avatarView.getLayoutParams() != null
                ? avatarView.getLayoutParams().width
                : (int) (42 * getResources().getDisplayMetrics().density);
        new Thread(() -> {
            try {
                Bitmap bitmap = AvatarManager.getInstance(this)
                        .getGroupAvatarBitmap(groupId, ownerFid, sizePx);
                runOnUiThread(() -> {
                    if (bitmap != null && !isFinishing() && !isDestroyed()) {
                        avatarView.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading toolbar group avatar for %s: %s",
                        groupId, e.getMessage());
            }
        }).start();
    }

    private void loadToolbarAvatar(ImageView avatarView, String fid) {
        new Thread(() -> {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(this);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
                
                runOnUiThread(() -> {
                    if (avatarBitmap != null && !isFinishing() && !isDestroyed()) {
                        avatarView.setImageBitmap(avatarBitmap);
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading toolbar avatar for FID %s: %s", fid, e.getMessage());
            }
        }).start();
    }
    
    // ── Manager & state ────────────────────────────────────────────────

    private void initManager() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) {
            TimberLogger.e(TAG, "Current setting is null, cannot initialize ImManager");
            ToastUtils.makeText(this, getString(R.string.current_setting_not_available));
            return;
        }

        FidManager fidManager = FidManager.getInstance();
        String activeLiveFid = (fidManager != null) ? fidManager.getLiveFid() : null;
        String mainFid = (fidManager != null) ? fidManager.getMainFid() : setting.getMainFid();
        if (activeLiveFid != null && mainFid != null && !activeLiveFid.equals(mainFid)) {
            TimberLogger.w(TAG, "IM disabled: liveFid=%s != mainFid=%s", activeLiveFid, mainFid);
            disableSending(R.string.im_disabled_subfid);
            addSystemMessage(getString(R.string.im_disabled_subfid));
            return;
        }

        liveFid = setting.getMainFid();
        restoreDraft();

        // Build ImManager off the UI thread, showing a "Connecting…"/Retry
        // banner meanwhile. Everything that reads the local message DB runs once
        // the manager is ready, so the chat never sits permanently blank.
        ensureImReadyWithBanner(im -> {
            imManager = im;
            imManager.addListener(this);
            imManager.setActiveChatDock(imType, targetId);
            adapter.setLiveFid(liveFid);
            // So a sealed row can show the cooldown rather than a button that does nothing.
            adapter.setSymkeyAsks(imManager.getKeyAskStore(), targetId);
            TimberLogger.d(TAG, "ImManager ready for fid=%s, target=%s, type=%s",
                    liveFid, targetId, imType);

            // Send-only mode: no own DOCK means replies can't be received,
            // but sending still works via a direct put into the recipient's
            // DOCK. Keep the input enabled so newcomers can ask for their
            // first FCH before they can afford a DOCK registration.
            sendOnlyMode = imType == ImType.P2P
                    && !imManager.isChannelConfigured()
                    && !hasChannelInLocalHome();

            // Now that the manager is ready, populate the screen from local data.
            setupGroupState();
            loadMessages();
            addChatNoticesIfNeeded();
            checkPendingStrangerOnEntry();
        });
    }

    /**
     * Notices appended after loadMessages() (which clears the list): the
     * send-only hint, and the nobody warnings — the request-board education for
     * the default nobody, a trust warning for any other nobody partner.
     */
    private void addChatNoticesIfNeeded() {
        resolveSpeakerNobodies(messages);
        if (imType != ImType.P2P) return;
        if (sendOnlyMode) {
            addSystemMessage(getString(R.string.im_send_only_no_dock));
        }
        if (NobodyBoard.isDefaultNobody(targetId)) {
            partnerNobodyNoticeShown = true;
            addSystemMessage(getString(R.string.nobody_board_education));
        } else if (NobodyBoard.isKnownNobody(targetId)) {
            showPartnerNobodyNotice();
        } else {
            NobodyUi.resolveAsync(Collections.singletonList(targetId));
        }
    }

    /** The trust warning for a nobody P2P partner, once per screen. */
    private void showPartnerNobodyNotice() {
        if (partnerNobodyNoticeShown) return;
        partnerNobodyNoticeShown = true;
        addSystemMessage(getString(R.string.nobody_partner_warning));
        refreshPartnerNobodyMark();
    }

    /**
     * Look up whether the speakers are nobodies. Whatever is learned — from here or
     * any other lookup — reaches {@link #onNobodiesLearned}, which hides their
     * actionable content and marks them.
     */
    private void resolveSpeakerNobodies(List<ImMessage> list) {
        Set<String> speakers = new HashSet<>();
        String me = myFid();
        for (ImMessage message : list) {
            String senderId = message.getSenderId();
            if (senderId != null && !senderId.isEmpty() && !senderId.equals(me)) {
                speakers.add(senderId);
            }
        }
        if (imType == ImType.P2P && targetId != null) speakers.add(targetId);
        NobodyUi.resolveAsync(speakers);
    }

    private String myFid() {
        FidManager fidManager = FidManager.getInstance();
        return fidManager != null ? fidManager.getLiveFid() : null;
    }

    private void onNobodiesLearned(Set<String> fids) {
        if (isFinishing() || isDestroyed()) return;
        if (imType == ImType.P2P && fids.contains(targetId)
                && !NobodyBoard.isDefaultNobody(targetId)) {
            showPartnerNobodyNotice();
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private boolean hasChannelInLocalHome() {
        com.fc.fc_ajdk.data.fcData.KeyInfo mainKeyInfo = FidManager.getInstance().getMainKeyInfo();
        if (mainKeyInfo == null || mainKeyInfo.getHome() == null) return false;
        for (Map.Entry<String, String> entry : mainKeyInfo.getHome().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.trim().isEmpty()) continue;
            if (key.startsWith("DOCK")) return true;
            if (key.equals("FUDP") || key.startsWith("FUDP@")) return true;
        }
        return false;
    }

    private void setupGroupState() {
        if (imType != ImType.SQUARE && imType != ImType.TEAM && imType != ImType.ROOM) return;

        if (btnMail != null) {
            btnMail.setVisibility(View.GONE);
        }

        if (!isOwnerOrMember()) {
            hideInputForNonMember();
            return;
        }

        groupHasDock = checkGroupHasDock();

        if (!groupHasDock) {
            int hintRes;
            if (imType == ImType.TEAM) hintRes = R.string.team_no_dock_hint;
            else if (imType == ImType.ROOM) hintRes = R.string.room_no_dock_hint;
            else hintRes = R.string.square_no_dock_hint;
            disableSending(hintRes);
        } else if (isGroupDockFailed()) {
            addSystemMessage(getString(R.string.dock_service_unavailable));
        }

        if (isSymkeyRequired() && groupHasDock && !hasSymkey()) {
            if (isEntityOwner()) {
                showOwnerNoSymkeyDialog();
            } else {
                disableSending(R.string.no_symkey_available);
                addSystemMessage(getString(R.string.requesting_symkey_from_owner));
                imManager.requestTeamSymkey(targetId);
            }
        } else if (isSymkeyRequired() && groupHasDock) {
            // Holding *a* key is not holding *every* key. Messages sealed
            // before a rotation this device missed stay unreadable, and
            // nothing above notices because hasSymkey() is satisfied. Ask
            // for the versions those messages actually name.
            imManager.requestMissingSymkeys(targetId);
        }
    }

    private void hideInputForNonMember() {
        if (inputArea != null) inputArea.setVisibility(View.GONE);
        if (emojiPanel != null) emojiPanel.setVisibility(View.GONE);
        if (btnMore != null) btnMore.setVisibility(View.GONE);
        if (btnClearInput != null) btnClearInput.setVisibility(View.GONE);
        if (btnMail != null) btnMail.setVisibility(View.GONE);
    }

    private void addSystemMessage(String text) {
        ImMessage sysMsg = new ImMessage();
        sysMsg.setType(imType);
        sysMsg.setSenderId(null);
        sysMsg.setTargetId(targetId);
        sysMsg.setContentType(ContentType.TEXT);
        sysMsg.setContent(text);
        sysMsg.setTimestamp(System.currentTimeMillis());

        messages.add(sysMsg);
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();
    }

    private boolean checkGroupHasDock() {
        if (imManager == null) return false;

        Map<String, String> home = null;
        if (imType == ImType.TEAM) {
            Team team = imManager.getTeam(targetId);
            if (team != null) home = team.getHome();
        } else if (imType == ImType.ROOM) {
            Room room = imManager.getRoom(targetId);
            if (room != null) home = room.getHome();
        } else {
            Square square = imManager.getSquare(targetId);
            if (square != null) home = square.getHome();
        }

        if (home == null || home.isEmpty()) return false;
        for (String key : home.keySet()) {
            if (key != null && key.startsWith("DOCK")) {
                String val = home.get(key);
                if (val != null && !val.isEmpty()) return true;
            }
        }
        return false;
    }

    private boolean isGroupDockFailed() {
        if (imManager == null || imType == ImType.P2P) return false;
        DockServiceRegistry registry = imManager.getDockRegistry();
        if (registry == null) return false;
        String entityType;
        if (imType == ImType.TEAM) entityType = "team";
        else if (imType == ImType.ROOM) entityType = "room";
        else entityType = "square";
        String dockUrl = registry.getDockUrl(entityType, targetId);
        return dockUrl != null && registry.isFailed(dockUrl);
    }

    private boolean isEntityOwner() {
        if (imManager == null || liveFid == null) return false;

        if (imType == ImType.TEAM) {
            Team team = imManager.getTeam(targetId);
            return team != null && liveFid.equals(team.getOwner());
        } else if (imType == ImType.ROOM) {
            Room room = imManager.getRoom(targetId);
            return room != null && room.isOwner(liveFid);
        }
        return false;
    }

    private boolean isOwnerOrMember() {
        if (imManager == null || liveFid == null) return false;

        if (imType == ImType.SQUARE) {
            Square square = imManager.getSquare(targetId);
            if (square == null) return false;
            List<String> members = square.getMembers();
            return members != null && members.contains(liveFid);
        } else if (imType == ImType.TEAM) {
            Team team = imManager.getTeam(targetId);
            if (team == null) return false;
            if (liveFid.equals(team.getOwner())) return true;
            List<String> members = team.getMembers();
            return members != null && members.contains(liveFid);
        } else if (imType == ImType.ROOM) {
            Room room = imManager.getRoom(targetId);
            if (room == null) return false;
            return room.isOwner(liveFid) || room.isMember(liveFid);
        }
        return false;
    }

    private void showOwnerNoSymkeyDialog() {
        disableSending(R.string.no_symkey_available);

        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.owner_no_symkey_title)
                .setMessage(R.string.owner_no_symkey_message)
                .setPositiveButton(R.string.generate_new_symkey, (d, which) -> {
                    hideKeyboard();
                    autoCreateAndShareSymkey();
                })
                .setNegativeButton(R.string.request_from_members, (d, which) -> {
                    hideKeyboard();
                    String entityType = (imType == ImType.TEAM) ? "team" : "room";
                    Intent intent = new Intent(this, AskSymkeyActivity.class);
                    intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_ID, targetId);
                    intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_TYPE, entityType);
                    startActivity(intent);
                })
                .setNeutralButton(R.string.cancel, (d, which) -> {
                    hideKeyboard();
                })
                .setCancelable(false)
                );
    }

    private void autoCreateAndShareSymkey() {
        disableSending(R.string.auto_creating_symkey);
        addSystemMessage(getString(R.string.auto_creating_symkey));

        new Thread(() -> {
            boolean success = false;

            if (imType == ImType.TEAM) {
                com.fc.freer.im.handler.TeamHandler teamHandler =
                        imManager.getTeamHandler();
                if (teamHandler != null) {
                    success = teamHandler.createSymkey(targetId);
                }
            } else if (imType == ImType.ROOM) {
                com.fc.freer.im.handler.RoomHandler roomHandler =
                        imManager.getRoomHandler();
                if (roomHandler != null) {
                    success = roomHandler.createSymkey(targetId);
                }
            }

            boolean finalSuccess = success;
            runOnUiThread(() -> {
                if (finalSuccess && hasSymkey()) {
                    addSystemMessage(getString(R.string.auto_symkey_created));
                    enableSending();
                } else {
                    addSystemMessage(getString(R.string.auto_symkey_failed));
                }
            });
        }).start();
    }

    // ── Messages ───────────────────────────────────────────────────────

    private void loadMessages() {
        if (imManager == null) return;
        
        messages.clear();
        List<ImMessage> loaded = imManager.getMessages(imType, targetId, 100, null, null);
        
        Collections.reverse(loaded);
        messages.addAll(loaded);
        
        adapter.notifyDataSetChanged();
        scrollToBottom();
        refreshSymkeyAskBanner();

        imManager.markConversationAsRead(imType, targetId);
    }
    
    private boolean isSymkeyRequired() {
        return imType == ImType.TEAM || imType == ImType.ROOM;
    }

    private boolean hasSymkey() {
        if (imManager == null) return false;
        SymkeyStore store = imManager.getSymkeyStore();
        return store != null && store.hasSymkey(targetId);
    }

    private void disableSending(int hintResId) {
        if (messageInput != null) {
            messageInput.setEnabled(false);
            messageInput.setHint(hintResId);
        }
        if (sendButton != null) {
            sendButton.setEnabled(false);
        }
        if (attachButton != null) {
            attachButton.setEnabled(false);
        }
    }

    private void enableSending() {
        if (messageInput != null) {
            messageInput.setEnabled(true);
            messageInput.setHint(R.string.type_message);
        }
        if (sendButton != null) {
            sendButton.setEnabled(messageInput != null && messageInput.getText().length() > 0);
        }
        if (attachButton != null) {
            attachButton.setEnabled(true);
        }
    }

    // ── Draft persistence ────────────────────────────────────────────────

    private String getDraftKey() {
        return DRAFT_KEY_PREFIX + (liveFid != null ? liveFid : "default") + "_" + imType + "_" + targetId;
    }

    private void restoreDraft() {
        if (messageInput == null) return;
        String draft = Hawk.get(getDraftKey(), "");
        if (!draft.isEmpty()) {
            messageInput.setText(draft);
            messageInput.setSelection(draft.length());
        }
    }

    private void saveDraft() {
        if (messageInput == null || targetId == null) return;
        String text = messageInput.getText().toString();
        String key = getDraftKey();
        if (text.isEmpty()) {
            Hawk.delete(key);
        } else {
            Hawk.put(key, text);
        }
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        
        hideKeyboard();
        hideEmojiPanel();
        
        if (imManager == null) {
            TimberLogger.e(TAG, "Cannot send: ImManager is null");
            ToastUtils.makeText(this, getString(R.string.im_manager_init_failed));
            return;
        }

        if (isSymkeyRequired() && !hasSymkey()) {
            ToastUtils.makeText(this, getString(R.string.no_symkey_available));
            return;
        }
        
        // Messages to the first-FCH board are always posted as templated requests
        // so the helpers' viewer (and any spam filter) can parse them.
        if (imType == ImType.P2P && NobodyBoard.isDefaultNobody(targetId)
                && !text.startsWith(NobodyBoard.FIRST_FCH_REQUEST_PREFIX)) {
            text = NobodyBoard.buildFirstFchRequest(liveFid, text);
            ToastUtils.makeText(this, getString(R.string.first_fch_request_sent_as, liveFid));
        }

        TimberLogger.d(TAG, "Sending message: type=%s, target=%s, text=%s", imType, targetId, text);

        ImMessage message = ImMessage.createText(imType, liveFid, targetId, text);
        
        messages.add(message);
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();
        
        messageInput.setText("");
        Hawk.delete(getDraftKey());

        imManager.send(message);
    }
    
    // ========== Voice Message ==========

    private void initRecordAudioPermissionLauncher() {
        recordAudioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startRecording();
                    } else {
                        ToastUtils.makeText(this, getString(R.string.recording_permission_required));
                    }
                }
        );
    }

    private void requestRecordingPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startRecording() {
        if (imManager == null) {
            ToastUtils.makeText(this, getString(R.string.im_manager_init_failed));
            return;
        }
        if (isSymkeyRequired() && !hasSymkey()) {
            ToastUtils.makeText(this, getString(R.string.no_symkey_available));
            return;
        }

        hideKeyboard();
        hideEmojiPanel();

        voiceRecorder.setTimerCallback(new VoiceRecorder.TimerCallback() {
            @Override
            public void onTimerUpdate(long elapsedMs) {
                if (recordingTimer != null) {
                    recordingTimer.setText(VoiceMessageHelper.formatDuration(elapsedMs));
                }
            }

            @Override
            public void onMaxDurationReached() {
                runOnUiThread(() -> stopRecordingAndSend());
            }
        });

        if (voiceRecorder.start()) {
            isRecording = true;
            inputArea.setVisibility(View.GONE);
            recordingOverlay.setVisibility(View.VISIBLE);
            recordingTimer.setText("0:00");
        } else {
            ToastUtils.makeText(this, getString(R.string.voice_too_short));
        }
    }

    private void stopRecordingAndSend() {
        if (!isRecording) return;
        isRecording = false;
        recordingOverlay.setVisibility(View.GONE);
        inputArea.setVisibility(View.VISIBLE);

        VoiceRecorder.VoiceRecordResult result = voiceRecorder.stop();
        if (result == null) {
            ToastUtils.makeText(this, getString(R.string.voice_too_short));
            return;
        }

        // The budget belongs to the destination DOCK, which publishes it as
        // `maxDataSize` in its on-chain service record. Until that is resolved
        // here, assume the server's own default rather than a larger guess —
        // guessing high is what made v1's 900 KB constant fail in the field.
        ImMessage message = VoiceMessageHelper.buildVoiceMessage(
                result, imType, liveFid, targetId, ImMessage.ASSUMED_DOCK_ITEM_LIMIT);
        if (message == null) {
            // Large file — would need HAT upload; for now show a message
            ToastUtils.makeText(this, getString(R.string.toast_voice_message_too_large));
            return;
        }

        messages.add(message);
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();

        imManager.send(message);
    }

    private void cancelRecording() {
        if (!isRecording) return;
        isRecording = false;
        voiceRecorder.cancel();
        recordingOverlay.setVisibility(View.GONE);
        inputArea.setVisibility(View.VISIBLE);
    }

    private void showAttachOptions() {
        if (isSymkeyRequired() && !hasSymkey()) {
            ToastUtils.makeText(this, getString(R.string.no_symkey_available));
            return;
        }
        TimberLogger.d(TAG, "Show attach options");
        hideKeyboard();

        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_attach_options, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        menuView.findViewById(R.id.option_share_file).setOnClickListener(v -> {
            popup.dismiss();
            launchFilePicker();
        });

        popup.showAsDropDown(attachButton);
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void initFilePickerLauncher() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri fileUri = result.getData().getData();
                        if (fileUri != null) {
                            shareFileFromUri(fileUri);
                        }
                    }
                }
        );
    }

    private void shareFileFromUri(Uri fileUri) {
        if (imManager == null || liveFid == null) {
            ToastUtils.showError(this, getString(R.string.im_manager_init_failed));
            return;
        }

        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            ToastUtils.showError(this, getString(R.string.error_no_live_fid));
            return;
        }

        HatManager hatManager = HatManager.getInstance(this, fidManager.getLiveFid());
        FileShareHelper helper = new FileShareHelper(this, hatManager);

        // Track the pending outgoing bubble so upload progress can be rendered on it
        // (same percent-ring mechanism as incoming downloads), keyed by hatId.
        final ImMessage[] pendingMsg = new ImMessage[1];
        final String[] pendingHatId = new String[1];
        final long[] lastUiUpdate = {0};

        new Thread(() -> {
            ImMessage msg = helper.shareUri(fileUri, imType, liveFid, targetId, null,
                    (prepared, hatId, fileSize) -> runOnUiThread(() -> {
                        pendingMsg[0] = prepared;
                        pendingHatId[0] = hatId;
                        messages.add(prepared);
                        adapter.notifyItemInserted(messages.size() - 1);
                        adapter.setHatDownloadProgress(hatId, 0, fileSize);
                        adapter.notifyHatChanged(hatId);
                        scrollToBottom();
                    }),
                    bytesUploaded -> {
                        final String hatId = pendingHatId[0];
                        if (hatId == null) return;
                        runOnUiThread(() -> {
                            adapter.setHatDownloadProgress(hatId, bytesUploaded, 0);
                            long now = System.currentTimeMillis();
                            if (now - lastUiUpdate[0] > 200) {
                                lastUiUpdate[0] = now;
                                adapter.notifyHatChanged(hatId);
                            }
                        });
                    });

            runOnUiThread(() -> {
                String hatId = pendingHatId[0];
                if (hatId != null) adapter.clearHatDownload(hatId);
                if (msg != null) {
                    if (hatId != null) adapter.notifyHatChanged(hatId);
                    imManager.send(msg);
                } else {
                    // Upload failed: remove the preliminary bubble.
                    ImMessage failed = pendingMsg[0];
                    if (failed != null) {
                        int idx = messages.indexOf(failed);
                        if (idx >= 0) {
                            messages.remove(idx);
                            adapter.notifyItemRemoved(idx);
                        }
                    }
                    ToastUtils.showError(this, getString(R.string.file_share_failed)
                            + ": " + helper.getLastError());
                }
            });
        }).start();
    }
    
    // ── Emoji ──────────────────────────────────────────────────────────

    private void setupEmojiPanel() {
        emojiPanel = findViewById(R.id.emoji_panel);
        emojiRecyclerView = findViewById(R.id.emoji_recycler_view);
        emojiCategoryTabs = findViewById(R.id.emoji_category_tabs);
        
        emojiAdapter = new EmojiAdapter(new String[0], this::onEmojiSelected);
        emojiRecyclerView.setLayoutManager(new GridLayoutManager(this, 7));
        emojiRecyclerView.setAdapter(emojiAdapter);
        
        buildCategoryTabs();
    }
    
    private void buildCategoryTabs() {
        emojiCategoryTabs.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        boolean first = true;
        
        for (Map.Entry<EmojiData.EmojiCategory, String[]> entry : EmojiData.EMOJI_MAP.entrySet()) {
            EmojiData.EmojiCategory category = entry.getKey();
            String[] emojis = entry.getValue();
            
            TextView tab = new TextView(this);
            tab.setText(getString(category.labelResId));
            tab.setTextSize(13);
            tab.setPadding((int) (12 * density), (int) (6 * density),
                    (int) (12 * density), (int) (6 * density));
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(getResources().getDrawable(
                    android.R.drawable.list_selector_background, getTheme()));
            
            tab.setOnClickListener(v -> selectCategory(category, emojis, tab));
            emojiCategoryTabs.addView(tab);
            
            if (first) {
                selectCategory(category, emojis, tab);
                first = false;
            }
        }
    }
    
    private void selectCategory(EmojiData.EmojiCategory category, String[] emojis, TextView tab) {
        if (selectedCategoryTab != null) {
            selectedCategoryTab.setTypeface(Typeface.DEFAULT);
            selectedCategoryTab.setTextColor(getResources().getColor(R.color.hint, getTheme()));
        }
        
        selectedCategory = category;
        selectedCategoryTab = tab;
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setTextColor(getResources().getColor(R.color.accent, getTheme()));
        
        emojiAdapter.setEmojis(emojis);
        emojiRecyclerView.scrollToPosition(0);
    }
    
    private void onEmojiSelected(String emoji) {
        int start = Math.max(messageInput.getSelectionStart(), 0);
        int end = Math.max(messageInput.getSelectionEnd(), 0);
        messageInput.getText().replace(Math.min(start, end), Math.max(start, end), emoji);
    }
    
    private void toggleEmojiPanel() {
        if (isEmojiPanelVisible) {
            hideEmojiPanel();
            messageInput.requestFocus();
        } else {
            showEmojiPanel();
        }
    }
    
    private void showEmojiPanel() {
        hideKeyboard();
        emojiPanel.postDelayed(() -> {
            emojiPanel.setVisibility(View.VISIBLE);
            isEmojiPanelVisible = true;
            emojiButton.setImageResource(R.drawable.ic_keyboard);
        }, 100);
    }
    
    private void hideEmojiPanel() {
        if (!isEmojiPanelVisible) return;
        emojiPanel.setVisibility(View.GONE);
        isEmojiPanelVisible = false;
        emojiButton.setImageResource(R.drawable.ic_emoji);
    }
    
    private void scrollToBottom() {
        if (!messages.isEmpty()) {
            messagesRecyclerView.scrollToPosition(messages.size() - 1);
        }
    }
    
    // ── ImListener callbacks ───────────────────────────────────────────

    @Override
    public void onMessageSent(ImMessage message) {
        if (message.getContentType() == ContentType.RECEIPT) return;
        
        if (isMessageForThisChat(message)) {
            TimberLogger.d(TAG, "Message sent callback: id=%s, method=%s, status=%s",
                    message.getId(), message.getDeliveryMethod(), message.getStatus());
            runOnUiThread(() -> {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if (messages.get(i).getId() != null
                            && messages.get(i).getId().equals(message.getId())) {
                        messages.set(i, message);
                        adapter.notifyItemChanged(i);
                        return;
                    }
                }
                // Fix 3: Don't add as new entry — message was pre-added in sendMessage()
                TimberLogger.d(TAG, "onMessageSent: message id=%s not found in UI list, skipping",
                        message.getId());
            });
        }
    }
    
    @Override
    public void onConversationUpdated(Conversation conversation) {
    }
    
    @Override
    public void onPresenceChanged(String fid, boolean online) {
        // Presence indicator removed; ImManager still tracks peer state for the send path.
    }
    
    @Override
    public void onMessageReceived(ImMessage message) {
        if (message.getContentType() == ContentType.RECEIPT) return;
        
        if (isMessageForThisChat(message)) {
            // Except a meeting card: the meeting posts it, not this screen, so it is not here yet.
            if ((imType == ImType.SQUARE || imType == ImType.TEAM || imType == ImType.ROOM)
                    && liveFid != null && liveFid.equals(message.getSenderId())
                    && message.getContentType() != ContentType.CALL) {
                TimberLogger.d(TAG, "Ignoring self-sent group message: id=%s", message.getId());
                return;
            }
            
            TimberLogger.d(TAG, "Message received: id=%s, from=%s", 
                    message.getId(), message.getSenderId());
            runOnUiThread(() -> {
                if (message.getId() != null) {
                    for (ImMessage existing : messages) {
                        if (message.getId().equals(existing.getId())) {
                            return;
                        }
                    }
                }
                
                messages.add(message);
                adapter.notifyItemInserted(messages.size() - 1);
                scrollToBottom();
                resolveSpeakerNobodies(Collections.singletonList(message));
                
                if (imManager != null) {
                    imManager.markConversationAsRead(imType, targetId);
                }
            });
        }
    }
    
    @Override
    public void onStrangerConfirmationRequired(String senderFid) {
        if (imType != ImType.P2P || targetId == null || !targetId.equals(senderFid)) return;
        runOnUiThread(() -> {
            if (strangerConfirmShown || isFinishing() || isDestroyed()) return;
            strangerConfirmShown = true;
            StrangerPeerDialog.show(this, senderFid, new StrangerPeerDialog.DialogCallback() {
                @Override
                public void onAccepted(String peerFid) {
                    PendingIssueManager mgr = imManager != null ? imManager.getPendingIssueManager() : null;
                    if (mgr != null) mgr.accept("STRANGER_PEER_" + peerFid);
                    // accept() promotes quarantined messages, which arrive via onMessageReceived.
                    enableSending();
                }

                @Override
                public void onRejected(String peerFid) {
                    PendingIssueManager mgr = imManager != null ? imManager.getPendingIssueManager() : null;
                    if (mgr != null) mgr.reject("STRANGER_PEER_" + peerFid);
                    finish();
                }

                @Override
                public void onBlacklisted(String peerFid) {
                    PendingIssueManager mgr = imManager != null ? imManager.getPendingIssueManager() : null;
                    if (mgr != null) mgr.reject("STRANGER_PEER_" + peerFid);
                    finish();
                }

                @Override
                public void onBlockAllStrangers(String peerFid) {
                    PendingIssueManager mgr = imManager != null ? imManager.getPendingIssueManager() : null;
                    if (mgr != null) mgr.reject("STRANGER_PEER_" + peerFid);
                    if (imManager != null && imManager.getContactPolicy() != null) {
                        imManager.getContactPolicy().setStrategy(ContactPolicy.Strategy.CONTACTS_ONLY);
                    }
                    finish();
                }

                @Override
                public void onDismissed(String peerFid) {
                    // Allow the prompt to reappear on the next message if not resolved.
                    strangerConfirmShown = false;
                }
            });
        });
    }

    @Override
    public void onSymkeyReceived(String entityId, long version) {
        if (targetId == null || !targetId.equals(entityId)) return;
        runOnUiThread(() -> {
            // A version is a time, so this says when the key was minted, not its number.
            addSystemMessage(getString(R.string.symkey_received_version,
                    SymkeyVersionText.prose(this, version)));
            if (isSymkeyRequired()) enableSending();
            refreshSymkeyAskBanner();
        });
    }

    @Override
    public void onRoomInfoReceived(String roomId, long symkeyVersion) {
        if (targetId == null || !targetId.equals(roomId)) return;
        runOnUiThread(() -> {
            addSystemMessage(getString(R.string.room_info_received_symkey,
                    SymkeyVersionText.prose(this, symkeyVersion)));
            if (isSymkeyRequired()) enableSending();
            refreshSymkeyAskBanner();
        });
    }

    @Override
    public void onMessagesRedecrypted(String entityId, List<ImMessage> redecryptedMessages) {
        if (!targetId.equals(entityId) || redecryptedMessages == null) return;

        runOnUiThread(() -> {
            for (ImMessage updated : redecryptedMessages) {
                if (updated.getId() == null) continue;
                for (int i = 0; i < messages.size(); i++) {
                    ImMessage existing = messages.get(i);
                    if (updated.getId().equals(existing.getId())) {
                        messages.set(i, updated);
                        adapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void onError(String error) {
        TimberLogger.e(TAG, "IM error: %s", error);
        runOnUiThread(() -> ToastUtils.makeText(this, error));
    }

    @Override
    public void onHistoryRequestReceived(PendingIssue issue) {
        runOnUiThread(() -> promptHistoryRequest(issue));
    }

    @Override
    public void onHistoryImportFailed(PendingIssue issue) {
        runOnUiThread(this::promptOpenHistoryRequests);
    }

    /**
     * Ask about this chat's open history issues — requests still undecided and shared history
     * that could not be added — including ones that arose while it was closed. Each is prompted
     * once per screen; Later leaves it in the pending issues list.
     */
    private void promptOpenHistoryRequests() {
        if (imManager == null || imManager.getPendingIssueManager() == null
                || imType == null || targetId == null) return;
        for (PendingIssue issue : imManager.getPendingIssueManager()
                .getOpenHistoryIssues(imType, targetId)) {
            if (!promptedHistoryRequests.add(issue.getId())) continue;
            if (issue.getIssueType() == PendingIssue.IssueType.HISTORY_IMPORT_FAILED) {
                HistoryRequestPrompt.showImportFailure(this, imManager, issue, null);
            } else {
                HistoryRequestPrompt.show(this, imManager, issue, null);
            }
        }
    }

    private void promptHistoryRequest(PendingIssue issue) {
        if (issue == null || imManager == null || isFinishing() || isDestroyed()) return;
        PendingIssue.HistoryRequestData data = issue.getDataAs(PendingIssue.HistoryRequestData.class);
        if (data == null || targetId == null || data.imType != imType
                || !targetId.equals(data.targetId)) return;
        if (!promptedHistoryRequests.add(issue.getId())) return;
        hideKeyboard();
        HistoryRequestPrompt.show(this, imManager, issue, null);
    }

    @Override
    public void onHistoryImported(String fromFid, int count) {
        runOnUiThread(() -> {
            String msg = getString(R.string.history_imported, count, fromFid);
            ToastUtils.makeText(this, msg);
            loadMessages();
        });
    }

    private boolean isMessageForThisChat(ImMessage message) {
        if (message.getType() != imType) return false;
        
        if (imType == ImType.P2P) {
            String partnerId = message.getConversationPartnerId(liveFid);
            return targetId.equals(partnerId);
        } else {
            return targetId.equals(message.getTargetId());
        }
    }
    
    // ── MessageInteractionListener callbacks ───────────────────────────

    @Override
    public void onOpenHat(com.fc.fc_ajdk.data.fcData.Hat hat) {
        startHatTransfer(hat, true);
    }

    @Override
    public void onDownloadHat(com.fc.fc_ajdk.data.fcData.Hat hat) {
        startHatTransfer(hat, false);
    }

    /**
     * Shared entry point for the two HAT card actions: {@code open == true} plays/opens the file,
     * {@code false} only downloads it to the public Downloads folder. Both share the same in-card
     * progress-ring and cancellation UI.
     */
    private void startHatTransfer(com.fc.fc_ajdk.data.fcData.Hat hat, boolean open) {
        String hatId = hat.getId();
        if (activeHatDownloads.containsKey(hatId)) {
            showCancelDownloadDialog(hatId);
            return;
        }
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        if (liveFid == null) return;
        HatManager hm = HatManager.getInstance(this, liveFid);
        // The HAT rides in from the wire: any local:// loca on it is a path on the sender's
        // device, so drop it before it reaches our DB, where findLocalPath() would treat it
        // as our own copy of the file.
        DataSyncManager.stripLocalLocas(hat);
        com.fc.fc_ajdk.data.fcData.Hat dbHat = hm.getHatById(hat.getId());
        if (dbHat == null) {
            hm.addHat(hat);
            hm.commit();
            dbHat = hat;
        } else {
            // DB hat has up-to-date local:// locas from previous downloads.
            // Merge download credentials from the IM hat in case the DB copy predates them.
            mergeImHatCredentials(hat, dbHat);
        }
        HatFileOpener.DownloadObserver observer = new HatFileOpener.DownloadObserver() {
            @Override
            public void onDownloadStarted(HatFileOpener.DownloadHandle handle, long totalBytes) {
                activeHatDownloads.put(hatId, handle);
                adapter.setHatDownloadProgress(hatId, 0, totalBytes);
                adapter.notifyHatChanged(hatId);
            }

            @Override
            public void onProgress(long bytes) {
                if (!activeHatDownloads.containsKey(hatId)) return; // cancelled
                adapter.setHatDownloadProgress(hatId, bytes, 0);
                long now = System.currentTimeMillis();
                Long last = hatProgressUiUpdate.get(hatId);
                if (last == null || now - last >= 200) {
                    hatProgressUiUpdate.put(hatId, now);
                    adapter.notifyHatChanged(hatId);
                }
            }

            @Override
            public void onFinished(boolean success, String error) {
                activeHatDownloads.remove(hatId);
                hatProgressUiUpdate.remove(hatId);
                adapter.clearHatDownload(hatId);
                adapter.notifyHatChanged(hatId);
                if (cancelDownloadDialog != null && hatId.equals(cancelDownloadHatId)) {
                    cancelDownloadDialog.dismiss();
                }
                if (!success) {
                    ToastUtils.showError(ChatActivity.this,
                            getString(R.string.download_failed, error));
                }
            }
        };
        HatFileOpener opener = new HatFileOpener(this, hm);
        if (open) {
            opener.open(dbHat, observer);
        } else {
            opener.download(dbHat, observer);
        }
    }

    private void showCancelDownloadDialog(String hatId) {
        cancelDownloadHatId = hatId;
        cancelDownloadDialog = DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.cancel_download_title)
                .setMessage(R.string.cancel_download_message)
                .setPositiveButton(R.string.keep_downloading, null)
                .setNegativeButton(R.string.cancel_download, (d, w) -> {
                    HatFileOpener.DownloadHandle handle = activeHatDownloads.remove(hatId);
                    if (handle != null) handle.cancel();
                    hatProgressUiUpdate.remove(hatId);
                    adapter.clearHatDownload(hatId);
                    adapter.notifyHatChanged(hatId);
                })
                .setOnDismissListener(d -> {
                    cancelDownloadDialog = null;
                    cancelDownloadHatId = null;
                })
                );
    }

    private void mergeImHatCredentials(com.fc.fc_ajdk.data.fcData.Hat src,
                                       com.fc.fc_ajdk.data.fcData.Hat dst) {
        if (dst.getKey() == null && src.getKey() != null) dst.setKey(src.getKey());
        if ((dst.getCipherIds() == null || dst.getCipherIds().isEmpty())
                && src.getCipherIds() != null) dst.setCipherIds(src.getCipherIds());
        if (src.getLocas() != null) {
            java.util.List<String> merged = dst.getLocas() != null
                    ? new java.util.ArrayList<>(dst.getLocas()) : new java.util.ArrayList<>();
            for (String loca : src.getLocas()) {
                // Only remote locations are portable; a local:// path from the sender says
                // nothing about where the file lives here.
                if (loca != null && !DataSyncManager.isLocalLoca(loca) && !merged.contains(loca)) {
                    merged.add(loca);
                }
            }
            dst.setLocas(merged);
        }
    }

    @Override
    public void onMessageLongPress(ImMessage message, View anchorView) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_message_actions, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        boolean isOutgoing = message.isOutgoing(liveFid);
        boolean isText = message.getContentType() == ContentType.TEXT;
        boolean isFailed = message.getStatus() == MessageStatus.FAILED;

        TextView copyItem = menuView.findViewById(R.id.action_copy);
        TextView resendItem = menuView.findViewById(R.id.action_resend);
        TextView deleteItem = menuView.findViewById(R.id.action_delete);

        if (isText && message.getContent() != null) {
            copyItem.setVisibility(View.VISIBLE);
            copyItem.setOnClickListener(v -> {
                popup.dismiss();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("message", message.getContent()));
                ToastUtils.makeText(this, getString(R.string.copied_to_clipboard));
            });
        } else {
            copyItem.setVisibility(View.GONE);
        }

        if (isOutgoing && isFailed) {
            resendItem.setVisibility(View.VISIBLE);
            resendItem.setOnClickListener(v -> {
                popup.dismiss();
                resendMessage(message);
            });
        } else {
            resendItem.setVisibility(View.GONE);
        }

        deleteItem.setOnClickListener(v -> {
            popup.dismiss();
            DialogUtils.show(new AlertDialog.Builder(this)
                    .setMessage(R.string.confirm_delete_message)
                    .setPositiveButton(R.string.delete, (d, w) -> deleteMessageLocally(message))
                    .setNegativeButton(R.string.cancel, null));
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int yOff = -(anchorView.getHeight() + menuView.getMeasuredHeight());
        popup.showAsDropDown(anchorView, 0, yOff);
    }

    private void deleteMessageLocally(ImMessage message) {
        for (int i = 0; i < messages.size(); i++) {
            ImMessage m = messages.get(i);
            if (m == message || (message.getId() != null && message.getId().equals(m.getId()))) {
                messages.remove(i);
                adapter.notifyItemRemoved(i);
                return;
            }
        }
    }

    private void resendMessage(ImMessage message) {
        if (imManager == null) return;
        message.setStatus(MessageStatus.PENDING);
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == message
                    || (message.getId() != null && message.getId().equals(messages.get(i).getId()))) {
                adapter.notifyItemChanged(i);
                break;
            }
        }
        imManager.send(message);
    }

    @Override
    public void onSpeakerAvatarClick(String fid) {
        hideKeyboard();
        hideEmojiPanel();
        showFreerDetail(fid);
    }

    @Override
    public void onSpeakerNameClick(String fid) {
        hideKeyboard();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("FID", fid);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(this, getString(R.string.fid_copied_to_clipboard));
    }

    @Override
    public void onSpeakerNameLongClick(String fid) {
        hideKeyboard();
        FreerApplication.addFid(fid);
        ToastUtils.makeText(this, getString(R.string.fid_added_to_list));
    }

    // ── Detail / navigation helpers ────────────────────────────────────

    private void showFreerDetail(String fid) {
        if (fid == null || fid.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.invalid_fid));
            return;
        }
        
        WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.loading_fid_info));
        waitingDialog.show();
        
        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) {
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        ToastUtils.makeText(this, getString(R.string.toast_api_center_unavailable));
                    });
                    return;
                }
                
                FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) {
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        ToastUtils.makeText(this, getString(R.string.toast_fapi_client_unavailable));
                    });
                    return;
                }
                
                Freer freerInfo = fapiClient.getFreer(fid);
                
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    if (freerInfo != null) {
                        Intent intent = new Intent(this, DetailActivity.class);
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, freerInfo.toJson());
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Freer.class.getName());
                        startActivity(intent);
                    } else {
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching Freer info for FID %s: %s", fid, e.getMessage());
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                });
            }
        }).start();
    }

    private void showEntityDetail() {
        if (imType == ImType.TEAM) {
            showTeamDetail();
        } else if (imType == ImType.ROOM) {
            showRoomDetail();
        } else {
            showGroupDetail();
        }
    }

    private void showGroupDetail() {
        WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.loading_square_info));
        waitingDialog.show();

        new Thread(() -> {
            try {
                Square square = imManager != null ? imManager.getSquare(targetId) : null;

                if (square == null) {
                    ApiCenter apiCenter = ApiCenter.getInstance();
                    if (apiCenter != null) {
                        FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                        if (fapiClient != null) {
                            square = fapiClient.entityById(IndicesNames.SQUARE, Square.class, targetId);
                        }
                    }
                }

                Square finalSquare = square;
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    if (finalSquare != null) {
                        Intent intent = new Intent(this, DetailActivity.class);
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, finalSquare.toJson());
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Square.class.getName());
                        startActivity(intent);
                    } else {
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching group detail: %s", e.getMessage());
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                });
            }
        }).start();
    }

    private void showTeamDetail() {
        WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.loading_team_info));
        waitingDialog.show();

        new Thread(() -> {
            try {
                Team team = imManager != null ? imManager.getTeam(targetId) : null;

                if (team == null) {
                    ApiCenter apiCenter = ApiCenter.getInstance();
                    if (apiCenter != null) {
                        FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                        if (fapiClient != null) {
                            team = fapiClient.entityById(IndicesNames.TEAM, Team.class, targetId);
                        }
                    }
                }

                Team finalTeam = team;
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    if (finalTeam != null) {
                        Intent intent = new Intent(this, DetailActivity.class);
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, finalTeam.toJson());
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Team.class.getName());
                        startActivity(intent);
                    } else {
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching team detail: %s", e.getMessage());
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                });
            }
        }).start();
    }

    private void openMemberList() {
        Intent intent = new Intent(this, MemberListActivity.class);
        intent.putExtra(MemberListActivity.EXTRA_GROUP_ID, targetId);
        if (imType == ImType.TEAM) {
            intent.putExtra(MemberListActivity.EXTRA_ENTITY_TYPE, "team");
        } else if (imType == ImType.ROOM) {
            intent.putExtra(MemberListActivity.EXTRA_ENTITY_TYPE, "room");
        } else if (imType == ImType.SQUARE) {
            intent.putExtra(MemberListActivity.EXTRA_ENTITY_TYPE, "square");
        }
        startActivity(intent);
    }

    private void showGroupChatMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_group_chat_menu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        menuView.findViewById(R.id.menu_group_members).setOnClickListener(v -> {
            popup.dismiss();
            openMemberList();
        });

        menuView.findViewById(R.id.menu_update_group).setOnClickListener(v -> {
            popup.dismiss();
            Intent intent = new Intent(this, UpdateSquareActivity.class);
            intent.putExtra(UpdateSquareActivity.EXTRA_SQUARE_ID, targetId);
            startActivity(intent);
        });

        menuView.findViewById(R.id.menu_request_history).setOnClickListener(v -> {
            popup.dismiss();
            showRequestHistoryMemberPicker();
        });

        menuView.findViewById(R.id.menu_leave_group).setOnClickListener(v -> {
            popup.dismiss();
            showQuitGroupConfirmation();
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    private void showQuitGroupConfirmation() {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.quit_square)
                .setMessage(R.string.confirm_quit_square)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    quitGroupDirectly();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                );
    }

    private void quitGroupDirectly() {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        ArrayList<String> groupIds = new ArrayList<>();
        groupIds.add(targetId);
        SquareOpData opData = SquareOpData.makeLeave(groupIds);
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
                            markGroupConversationAsLeft(targetId);
                            runOnUiThread(() -> {
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(ChatActivity.this, errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void markGroupConversationAsLeft(String groupId) {
        if (imManager == null) return;
        String convId = ImType.SQUARE.name() + "_" + groupId;
        Conversation conv = imManager.getConversationsDb().get(convId);
        if (conv != null) {
            conv.setLeftGroup(true);
            imManager.getConversationsDb().put(convId, conv);
        }
        if (imManager.getDockRegistry() != null) {
            imManager.getDockRegistry().unregister(SQUARE, groupId);
        }
    }

    // ── Room chat menu ────────────────────────────────────────────────

    private void showRoomChatMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_room_chat_menu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        Room room = imManager != null ? imManager.getRoom(targetId) : null;
        boolean isOwner = room != null && liveFid != null && room.isOwner(liveFid);

        menuView.findViewById(R.id.menu_room_members).setOnClickListener(v -> {
            popup.dismiss();
            openMemberList();
        });
        menuView.findViewById(R.id.menu_start_meeting).setOnClickListener(v -> {
            popup.dismiss();
            askMeetingTitle();
        });

        View ownerMenuItem = menuView.findViewById(R.id.menu_room_owner_menu);
        if (isOwner) {
            ownerMenuItem.setVisibility(View.VISIBLE);
            ownerMenuItem.setOnClickListener(v -> {
                popup.dismiss();
                showRoomOwnerSubMenu(anchor);
            });
        }

        menuView.findViewById(R.id.menu_ask_room_info).setOnClickListener(v -> {
            popup.dismiss();
            addSystemMessage(getString(R.string.asking_room_info));
            Intent intent = new Intent(this, AskRoomInfoActivity.class);
            intent.putExtra(AskRoomInfoActivity.EXTRA_ENTITY_ID, targetId);
            startActivity(intent);
        });

        menuView.findViewById(R.id.menu_ask_symkey).setOnClickListener(v -> {
            popup.dismiss();
            addSystemMessage(getString(R.string.asking_symkey));
            Intent intent = new Intent(this, AskSymkeyActivity.class);
            intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_ID, targetId);
            intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_TYPE, "room");
            startActivity(intent);
        });

        menuView.findViewById(R.id.menu_symkey_ledger).setOnClickListener(v -> {
            popup.dismiss();
            Intent ledger = new Intent(this, SymkeyLedgerActivity.class);
            ledger.putExtra(SymkeyLedgerActivity.EXTRA_ENTITY_ID, targetId);
            startActivity(ledger);
        });

        menuView.findViewById(R.id.menu_request_history).setOnClickListener(v -> {
            popup.dismiss();
            showRequestHistoryMemberPicker();
        });

        menuView.findViewById(R.id.menu_leave_room).setOnClickListener(v -> {
            popup.dismiss();
            showLeaveRoomConfirmation();
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    private void showRoomOwnerSubMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_room_owner_submenu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        menuView.findViewById(R.id.submenu_update_room).setOnClickListener(v -> {
            popup.dismiss();
            showUpdateRoomDialog();
        });

        menuView.findViewById(R.id.submenu_add_member).setOnClickListener(v -> {
            popup.dismiss();
            launchAddRoomMember();
        });

        menuView.findViewById(R.id.submenu_remove_member).setOnClickListener(v -> {
            popup.dismiss();
            launchRemoveRoomMember();
        });

        menuView.findViewById(R.id.submenu_share_room_info).setOnClickListener(v -> {
            popup.dismiss();
            launchShareRoomInfo();
        });

        menuView.findViewById(R.id.submenu_share_symkey).setOnClickListener(v -> {
            popup.dismiss();
            launchShareRoomSymkey();
        });

        menuView.findViewById(R.id.submenu_create_symkey).setOnClickListener(v -> {
            popup.dismiss();
            createRoomSymkey();
        });

        menuView.findViewById(R.id.submenu_close_room).setOnClickListener(v -> {
            popup.dismiss();
            showCloseRoomConfirmation();
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    // ── Room operations ─────────────────────────────────────────────

    private void showRoomDetail() {
        Room room = imManager != null ? imManager.getRoom(targetId) : null;
        if (room != null) {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, room.toJson());
            intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Room.class.getName());
            startActivity(intent);
        } else {
            ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
        }
    }

    private void showUpdateRoomDialog() {
        Room room = imManager != null ? imManager.getRoom(targetId) : null;
        if (room == null) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_room, null);
        EditText nameInput = dialogView.findViewById(R.id.room_name_input);
        EditText descInput = dialogView.findViewById(R.id.room_desc_input);
        EditText dockInput = dialogView.findViewById(R.id.room_dock_input);
        nameInput.setText(room.getName());
        descInput.setText(room.getDesc());
        if (room.getHome() != null) {
            String dockUrl = room.getHome().get("DOCK@No1_NrC7");
            if (dockUrl != null) dockInput.setText(dockUrl);
        }

        updateRoomDockInput = dockInput;
        dialogView.findViewById(R.id.choose_dock_button).setOnClickListener(v -> {
            hideKeyboard();
            chooseRoomDockLauncher.launch(ServicePickerUtils.pickerIntent(this,
                    Constants.DOCK_NO1_NRC7, getString(R.string.server_setup_dock_label)));
        });

        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.update_room)
                .setView(dialogView)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    hideKeyboard();
                    String name = nameInput.getText().toString().trim();
                    String desc = descInput.getText().toString().trim();
                    String dock = dockInput.getText().toString().trim();
                    if (!name.isEmpty() && imManager != null) {
                        java.util.Map<String, String> home = null;
                        if (!dock.isEmpty()) {
                            home = new java.util.HashMap<>();
                            home.put("DOCK@No1_NrC7", dock);
                        }
                        java.util.Map<String, String> finalHome = home;
                        new Thread(() -> {
                            boolean success = imManager.updateRoom(targetId, name, desc, finalHome);
                            runOnUiThread(() -> {
                                if (success) {
                                    ToastUtils.makeText(this, getString(R.string.room_updated));
                                    setupToolbarWithAvatar();
                                }
                            });
                        }).start();
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> hideKeyboard())
                );
    }

    private void launchAddRoomMember() {
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_TITLE, getString(R.string.add_member));
        addRoomMemberLauncher.launch(intent);
    }

    private void launchRemoveRoomMember() {
        Room room = imManager != null ? imManager.getRoom(targetId) : null;
        if (room == null || room.getMembers() == null) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        ArrayList<String> candidates = new ArrayList<>();
        for (String member : room.getMembers()) {
            if (!member.equals(room.getOwner())) {
                candidates.add(member);
            }
        }

        if (candidates.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            return;
        }

        Intent intent = new Intent(this, ChooseFidActivity.class);
        intent.putStringArrayListExtra(ChooseFidActivity.EXTRA_CANDIDATE_FIDS, candidates);
        intent.putExtra(ChooseFidActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(ChooseFidActivity.EXTRA_TITLE, getString(R.string.remove_member));
        removeRoomMemberLauncher.launch(intent);
    }

    private void launchShareRoomInfo() {
        Room room = imManager != null ? imManager.getRoom(targetId) : null;
        if (room == null || room.getMembers() == null) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        ArrayList<String> candidates = new ArrayList<>(room.getMembers());

        if (candidates.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            return;
        }

        Intent intent = new Intent(this, ChooseFidActivity.class);
        intent.putStringArrayListExtra(ChooseFidActivity.EXTRA_CANDIDATE_FIDS, candidates);
        intent.putExtra(ChooseFidActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(ChooseFidActivity.EXTRA_TITLE, getString(R.string.share_room_info));
        shareRoomInfoLauncher.launch(intent);
    }

    private void launchShareRoomSymkey() {
        Room room = imManager != null ? imManager.getRoom(targetId) : null;
        if (room == null || room.getMembers() == null) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        ArrayList<String> candidates = new ArrayList<>(room.getMembers());

        if (candidates.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            return;
        }

        Intent intent = new Intent(this, ChooseFidActivity.class);
        intent.putStringArrayListExtra(ChooseFidActivity.EXTRA_CANDIDATE_FIDS, candidates);
        intent.putExtra(ChooseFidActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(ChooseFidActivity.EXTRA_TITLE, getString(R.string.share_symkey));
        shareRoomSymkeyLauncher.launch(intent);
    }

    private void createRoomSymkey() {
        if (imManager == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        com.fc.freer.im.handler.RoomHandler roomHandler = imManager.getRoomHandler();
        if (roomHandler == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        boolean success = roomHandler.createSymkey(targetId);
        if (success) {
            ToastUtils.makeText(this, getString(R.string.symkey_created_successfully));
            addSystemMessage(getString(R.string.symkey_created_successfully));
        } else {
            ToastUtils.makeText(this, getString(R.string.failed_to_create_symkey));
        }
    }

    private void showCloseRoomConfirmation() {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.close_room)
                .setMessage(R.string.confirm_close_room)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    if (imManager != null && imManager.closeRoom(targetId)) {
                        markRoomConversationAsLeft(targetId);
                        ToastUtils.makeText(this, getString(R.string.room_closed));
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                );
    }

    private void showLeaveRoomConfirmation() {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.leave_room)
                .setMessage(R.string.confirm_leave_room)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    if (imManager != null && imManager.leaveRoom(targetId)) {
                        markRoomConversationAsLeft(targetId);
                        ToastUtils.makeText(this, getString(R.string.left_room));
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                );
    }

    private void markRoomConversationAsLeft(String roomId) {
        if (imManager == null) return;
        imManager.markRoomConversationAsLeft(roomId);
    }

    private void showTeamChatMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_team_chat_menu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        Team team = imManager != null ? imManager.getTeam(targetId) : null;
        boolean isOwner = team != null && liveFid != null && liveFid.equals(team.getOwner());
        boolean isManager = false;
        if (team != null && team.getManagers() != null && liveFid != null) {
            for (String m : team.getManagers()) {
                if (liveFid.equals(m)) { isManager = true; break; }
            }
        }

        menuView.findViewById(R.id.menu_team_members).setOnClickListener(v -> {
            popup.dismiss();
            openMemberList();
        });
        menuView.findViewById(R.id.menu_start_meeting).setOnClickListener(v -> {
            popup.dismiss();
            askMeetingTitle();
        });

        // Reading the consensus is offered to every member, not gated on owner or manager:
        // it is the one part of the team they are on chain as having agreed to, and reading
        // costs no carve. The team record read when the menu was built is what the viewer
        // gets, so it cannot drift onto something else while the download runs.
        menuView.findViewById(R.id.menu_consensus_doc).setOnClickListener(v -> {
            popup.dismiss();
            openConsensusDoc(team);
        });

        View ownerMenuItem = menuView.findViewById(R.id.menu_owner_menu);
        if (isOwner) {
            ownerMenuItem.setVisibility(View.VISIBLE);
            ownerMenuItem.setOnClickListener(v -> {
                popup.dismiss();
                showOwnerSubMenu(anchor);
            });
        }

        View managerMenuItem = menuView.findViewById(R.id.menu_manager_menu);
        if (isOwner || isManager) {
            managerMenuItem.setVisibility(View.VISIBLE);
            managerMenuItem.setOnClickListener(v -> {
                popup.dismiss();
                showManagerSubMenu(anchor);
            });
        }

        menuView.findViewById(R.id.menu_ask_symkey).setOnClickListener(v -> {
            popup.dismiss();
            addSystemMessage(getString(R.string.asking_symkey));
            Intent intent = new Intent(this, AskSymkeyActivity.class);
            intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_ID, targetId);
            intent.putExtra(AskSymkeyActivity.EXTRA_ENTITY_TYPE, "team");
            startActivity(intent);
        });

        menuView.findViewById(R.id.menu_symkey_ledger).setOnClickListener(v -> {
            popup.dismiss();
            Intent ledger = new Intent(this, SymkeyLedgerActivity.class);
            ledger.putExtra(SymkeyLedgerActivity.EXTRA_ENTITY_ID, targetId);
            startActivity(ledger);
        });

        menuView.findViewById(R.id.menu_request_history).setOnClickListener(v -> {
            popup.dismiss();
            showRequestHistoryMemberPicker();
        });

        menuView.findViewById(R.id.menu_leave_team).setOnClickListener(v -> {
            popup.dismiss();
            showLeaveTeamConfirmation();
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    /**
     * Show the team's consensus document, read-only. Writing a new one is a transaction and
     * stays in {@link UpdateTeamActivity}.
     * <p>
     * A team with no consensus id says so rather than offering the template a new team starts
     * from: boilerplate is the wrong thing to show in the one place a team's own words belong.
     */
    private void openConsensusDoc(Team team) {
        String consensusId = team != null ? team.getConsensusId() : null;
        if (consensusId == null || consensusId.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.consensus_doc_none_named));
            return;
        }
        if (liveFid == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_fid));
            return;
        }
        ConsensusDocViewer.fetchAndShow(this, HatManager.getInstance(this, liveFid),
                getString(R.string.consensus_document), consensusId,
                Collections.singletonList(ConsensusDocHelper.resolveTeamDiskSid(team)),
                0, null);
    }

    // ── Owner sub-menu ──────────────────────────────────────────────────

    private void showOwnerSubMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_owner_submenu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        menuView.findViewById(R.id.submenu_update_team).setOnClickListener(v -> {
            popup.dismiss();
            Intent intent = new Intent(this, UpdateTeamActivity.class);
            intent.putExtra(UpdateTeamActivity.EXTRA_TEAM_ID, targetId);
            startActivity(intent);
        });

        menuView.findViewById(R.id.submenu_appoint_manager).setOnClickListener(v -> {
            popup.dismiss();
            launchAppointManager();
        });

        menuView.findViewById(R.id.submenu_cancel_appointment).setOnClickListener(v -> {
            popup.dismiss();
            launchCancelAppointment();
        });

        menuView.findViewById(R.id.submenu_transfer_team).setOnClickListener(v -> {
            popup.dismiss();
            launchTransferTeam();
        });

        menuView.findViewById(R.id.submenu_create_symkey).setOnClickListener(v -> {
            popup.dismiss();
            createTeamSymkey();
        });

        menuView.findViewById(R.id.submenu_disband_team).setOnClickListener(v -> {
            popup.dismiss();
            showDisbandTeamConfirmation();
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    // ── Manager sub-menu ────────────────────────────────────────────────

    private void showManagerSubMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_manager_submenu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        menuView.findViewById(R.id.submenu_invite_member).setOnClickListener(v -> {
            popup.dismiss();
            Intent intent = new Intent(this, InviteTeamMemberActivity.class);
            intent.putExtra(InviteTeamMemberActivity.EXTRA_TEAM_ID, targetId);
            startActivity(intent);
        });

        menuView.findViewById(R.id.submenu_dismiss_member).setOnClickListener(v -> {
            popup.dismiss();
            launchDismissMember();
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    // ── Team owner operations ───────────────────────────────────────────

    private void initTeamOperationLaunchers() {
        appointManagerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                ChooseFidActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty()) {
                            NobodyGuard.confirm(this, fids, R.string.nobody_consequence_team,
                                    () -> broadcastTeamOp(
                                            TeamOpData.makeAppoint(targetId, fids.toArray(new String[0])),
                                            R.string.managers_appointed_successfully,
                                            R.string.failed_to_appoint_managers));
                        }
                    }
                });

        cancelAppointmentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                ChooseFidActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty()) {
                            broadcastTeamOp(
                                    TeamOpData.makeCancelAppointment(targetId, fids.toArray(new String[0])),
                                    R.string.appointment_cancelled_successfully,
                                    R.string.failed_to_cancel_appointment);
                        }
                    }
                });

        dismissMemberLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                ChooseFidActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty()) {
                            broadcastTeamOp(
                                    TeamOpData.makeDismiss(targetId, fids.toArray(new String[0])),
                                    R.string.members_dismissed_successfully,
                                    R.string.failed_to_dismiss_members);
                        }
                    }
                });

        transferTeamLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty()) {
                            String transferee = fids.get(0);
                            NobodyGuard.confirm(this, Collections.singletonList(transferee),
                                    R.string.nobody_consequence_team_owner,
                                    () -> broadcastTeamOpWithNotification(
                                            TeamOpData.makeTransfer(targetId, transferee),
                                            R.string.team_transferred_successfully,
                                            R.string.failed_to_transfer_team,
                                            transferee));
                        }
                    }
                });
    }

    private void initRoomOperationLaunchers() {
        chooseRoomDockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && updateRoomDockInput != null) {
                        ServicePickerUtils.applySelectedService(this, result.getData(),
                                updateRoomDockInput);
                    }
                });

        addRoomMemberLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty() && imManager != null) {
                            NobodyGuard.confirm(this, fids, R.string.nobody_consequence_room, () -> new Thread(() -> {
                                int addedCount = imManager.addRoomMembers(targetId, fids);
                                runOnUiThread(() -> {
                                    if (addedCount > 0) {
                                        ToastUtils.makeText(this,
                                                getString(R.string.members_added_count, addedCount));
                                    } else {
                                        ToastUtils.makeText(this, getString(R.string.failed_to_add_member));
                                    }
                                });
                            }).start());
                        }
                    }
                });

        removeRoomMemberLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                ChooseFidActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty() && imManager != null) {
                            new Thread(() -> {
                                boolean anyRemoved = false;
                                for (String fid : fids) {
                                    if (imManager.removeRoomMember(targetId, fid)) {
                                        anyRemoved = true;
                                    }
                                }
                                boolean finalAnyRemoved = anyRemoved;
                                runOnUiThread(() -> {
                                    if (finalAnyRemoved) {
                                        ToastUtils.makeText(this, getString(R.string.member_removed));
                                    } else {
                                        ToastUtils.makeText(this, getString(R.string.failed_to_remove_member));
                                    }
                                });
                            }).start();
                        }
                    }
                });

        shareRoomInfoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                ChooseFidActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty() && imManager != null) {
                            NobodyGuard.confirm(this, fids, R.string.nobody_consequence_room, () -> new Thread(() -> {
                                int count = imManager.shareRoomInfoToMembers(targetId, fids);
                                runOnUiThread(() -> {
                                    if (count > 0) {
                                        ToastUtils.makeText(this, getString(R.string.room_info_shared, count));
                                    } else {
                                        ToastUtils.makeText(this, getString(R.string.room_info_share_failed));
                                    }
                                });
                            }).start());
                        }
                    }
                });

        shareRoomSymkeyLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                ChooseFidActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty() && imManager != null) {
                            NobodyGuard.confirm(this, fids, R.string.nobody_consequence_room, () -> new Thread(() -> {
                                int count = imManager.shareRoomSymkeyToMembers(targetId, fids);
                                runOnUiThread(() -> {
                                    if (count > 0) {
                                        String msg = getString(R.string.symkey_shared, count);
                                        ToastUtils.makeText(this, msg);
                                        addSystemMessage(msg);
                                    } else {
                                        ToastUtils.makeText(this, getString(R.string.symkey_share_failed));
                                    }
                                });
                            }).start());
                        }
                    }
                });
    }

    private void showLeaveTeamConfirmation() {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.leave_team)
                .setMessage(R.string.confirm_leave_team)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    leaveTeamDirectly();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                );
    }

    private void leaveTeamDirectly() {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        ArrayList<String> teamIds = new ArrayList<>();
        teamIds.add(targetId);
        TeamOpData opData = TeamOpData.makeLeave(teamIds);
        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        leaveTeamWaitingDialog = new WaitingDialog(this, getString(R.string.leaving_team));
        leaveTeamWaitingDialog.show();

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            markConversationAsLeft();
                            runOnUiThread(() -> {
                                dismissLeaveTeamDialog();
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                dismissLeaveTeamDialog();
                                ToastUtils.makeText(ChatActivity.this,
                                        getString(R.string.failed_to_leave_team) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> dismissLeaveTeamDialog());
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> dismissLeaveTeamDialog());
                        }
                    });
        }).start();
    }

    private void dismissLeaveTeamDialog() {
        if (leaveTeamWaitingDialog != null && leaveTeamWaitingDialog.isShowing()) {
            leaveTeamWaitingDialog.dismiss();
        }
        leaveTeamWaitingDialog = null;
    }

    private void markConversationAsLeft() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) return;

        ImManager imMgr = setting.getImManager();
        String convId = ImType.TEAM.name() + "_" + targetId;
        Conversation conv = imMgr.getConversationsDb().get(convId);
        if (conv != null) {
            conv.setLeftGroup(true);
            imMgr.getConversationsDb().put(convId, conv);
        }
        if (imMgr.getDockRegistry() != null) {
            imMgr.getDockRegistry().unregister("team", targetId);
        }
    }

    private void launchAppointManager() {
        Team team = imManager != null ? imManager.getTeam(targetId) : null;
        if (team == null || team.getMembers() == null) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        ArrayList<String> candidates = new ArrayList<>(team.getMembers());
        Intent intent = new Intent(this, ChooseFidActivity.class);
        intent.putStringArrayListExtra(ChooseFidActivity.EXTRA_CANDIDATE_FIDS, candidates);
        intent.putExtra(ChooseFidActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(ChooseFidActivity.EXTRA_TITLE, getString(R.string.appoint_manager));
        appointManagerLauncher.launch(intent);
    }

    private void launchCancelAppointment() {
        Team team = imManager != null ? imManager.getTeam(targetId) : null;
        if (team == null || team.getManagers() == null || team.getManagers().isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            return;
        }

        ArrayList<String> candidates = new ArrayList<>();
        for (String m : team.getManagers()) {
            if (!m.equals(team.getOwner())) {
                candidates.add(m);
            }
        }

        if (candidates.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            return;
        }

        Intent intent = new Intent(this, ChooseFidActivity.class);
        intent.putStringArrayListExtra(ChooseFidActivity.EXTRA_CANDIDATE_FIDS, candidates);
        intent.putExtra(ChooseFidActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(ChooseFidActivity.EXTRA_TITLE, getString(R.string.cancel_appointment));
        cancelAppointmentLauncher.launch(intent);
    }

    private void launchDismissMember() {
        Team team = imManager != null ? imManager.getTeam(targetId) : null;
        if (team == null || team.getMembers() == null) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        ArrayList<String> candidates = new ArrayList<>();
        for (String member : team.getMembers()) {
            if (!member.equals(team.getOwner())) {
                candidates.add(member);
            }
        }

        if (candidates.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_candidates));
            return;
        }

        Intent intent = new Intent(this, ChooseFidActivity.class);
        intent.putStringArrayListExtra(ChooseFidActivity.EXTRA_CANDIDATE_FIDS, candidates);
        intent.putExtra(ChooseFidActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        intent.putExtra(ChooseFidActivity.EXTRA_TITLE, getString(R.string.dismiss_members));
        dismissMemberLauncher.launch(intent);
    }

    private void launchTransferTeam() {
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_TITLE, getString(R.string.transfer_team));
        transferTeamLauncher.launch(intent);
    }

    private void showDisbandTeamConfirmation() {
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.disband_team)
                .setMessage(R.string.confirm_disband_team)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    hideKeyboard();
                    broadcastTeamOp(
                            TeamOpData.makeDisband(java.util.Collections.singletonList(targetId)),
                            R.string.team_disbanded_successfully,
                            R.string.failed_to_disband_team);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> hideKeyboard())
                );
    }

    private void createTeamSymkey() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        TeamHandler teamHandler = setting.getImManager().getTeamHandler();
        if (teamHandler == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        boolean success = teamHandler.createSymkey(targetId);
        if (success) {
            ToastUtils.makeText(this, getString(R.string.symkey_created_successfully));
            addSystemMessage(getString(R.string.symkey_created_successfully));
        } else {
            ToastUtils.makeText(this, getString(R.string.failed_to_create_symkey));
        }
    }

    private void broadcastTeamOp(TeamOpData opData, int successMsgId, int failMsgId) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

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
                    new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(ChatActivity.this,
                                    getString(failMsgId) + ": " + errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void broadcastTeamOpWithNotification(TeamOpData opData, int successMsgId, int failMsgId,
                                                  String targetFid) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

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
                    new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            sendTeamTransferNotification(targetFid, txId);
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> ToastUtils.makeText(ChatActivity.this,
                                    getString(failMsgId) + ": " + errorMessage));
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {}

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {}
                    });
        }).start();
    }

    private void sendTeamTransferNotification(String targetFid, String txId) {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;
            ImManager imMgr = setting.getImManager();
            if (imMgr == null) return;

            String teamName = null;
            TeamHandler teamHandler = imMgr.getTeamHandler();
            if (teamHandler != null) {
                Team team = teamHandler.getTeam(targetId);
                if (team != null) teamName = team.getStdName();
            }

            imMgr.sendTeamTransferNotification(targetFid, targetId, teamName);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to send transfer notification: %s", e.getMessage());
        }
    }

    
    private void openCreateMail() {
        Intent intent = new Intent(this, CreateMailActivity.class);
        intent.putExtra("recipientFid", targetId);
        startActivity(intent);
    }

    // ── P2P chat menu ───────────────────────────────────────────────────

    private void showP2pChatMenu(View anchor) {
        View menuView = LayoutInflater.from(this).inflate(R.layout.popup_p2p_chat_menu, null);
        PopupWindow popup = new PopupWindow(menuView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8f);

        menuView.findViewById(R.id.menu_voice_call).setOnClickListener(v -> {
            popup.dismiss();
            startVoiceCall();
        });

        menuView.findViewById(R.id.menu_request_history).setOnClickListener(v -> {
            popup.dismiss();
            showRequestHistoryDatePicker(targetId);
        });

        menuView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuView.getMeasuredHeight()));
    }

    // ── Voice call (VOICE_SPEC §10) ─────────────────────────────────────

    private final androidx.activity.result.ActivityResultLauncher<String[]> callPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                if (Boolean.TRUE.equals(granted.get(Manifest.permission.RECORD_AUDIO))) placeVoiceCall();
                else ToastUtils.makeText(this, getString(R.string.call_need_mic));
            });

    /** A nobody's key is public, so calling one asks first (NOBODY_SPEC §3). */
    private void startVoiceCall() {
        if (targetId == null) return;
        if (NobodyBoard.isKnownNobody(targetId)) {
            DialogUtils.show(new AlertDialog.Builder(this)
                    .setMessage(R.string.call_nobody_confirm)
                    .setPositiveButton(R.string.menu_voice_call, (d, w) -> requestCallPermissions())
                    .setNegativeButton(R.string.cancel, null));
        } else {
            requestCallPermissions();
        }
    }

    private void requestCallPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        needed.add(Manifest.permission.RECORD_AUDIO);
        if (android.os.Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        callPermissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void placeVoiceCall() {
        com.fc.freer.call.CallManager.getInstance(this).placeCall(targetId);
        startActivity(new android.content.Intent(this, com.fc.freer.call.CallActivity.class));
    }

    // ── Meetings (VOICE_SPEC §8) ────────────────────────────────────────

    /** What to do once the microphone is allowed: start or join a meeting. */
    private Runnable afterMeetingMic;

    private final androidx.activity.result.ActivityResultLauncher<String[]> meetingPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                Runnable next = afterMeetingMic;
                afterMeetingMic = null;
                if (Boolean.TRUE.equals(granted.get(Manifest.permission.RECORD_AUDIO))) {
                    if (next != null) next.run();
                } else {
                    ToastUtils.makeText(this, getString(R.string.call_need_mic));
                }
            });

    private final com.fc.freer.call.MeetingManager.CardListener meetingCards = () -> {
        if (adapter != null) adapter.notifyDataSetChanged(); // a card opened or ended
    };

    /**
     * Back from a meeting: this screen was paused while the meeting posted its
     * card or it ended, and heard of neither. Reload if an open meeting of this
     * chat has no card here; redraw the cards either way.
     */
    private void catchUpMeetingCards() {
        com.fc.freer.call.MeetingBoard board = com.fc.freer.call.MeetingManager.getInstance(this).board();
        if (board == null || targetId == null || adapter == null) return;
        for (com.fc.freer.call.MeetingBoard.Meeting m : board.open(targetId)) {
            boolean shown = false;
            for (ImMessage x : messages) {
                if (com.fc.freer.call.CallText.isCall(x) && x.getContent() != null && x.getContent().contains(m.meetingId)) {
                    shown = true;
                    break;
                }
            }
            if (!shown) {
                loadMessages();
                return;
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void withMeetingMic(Runnable next) {
        afterMeetingMic = next;
        java.util.List<String> needed = new java.util.ArrayList<>();
        needed.add(Manifest.permission.RECORD_AUDIO);
        if (android.os.Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        meetingPermissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void askMeetingTitle() {
        if (targetId == null || imType == null) return;
        android.widget.EditText title = new android.widget.EditText(this);
        title.setHint(R.string.meeting_start_title_hint);
        title.setSingleLine(true);
        // Everyone, keyed by the chat's key; or chosen people, under a key of the meeting's own (Decision 20).
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.meeting_start)
                .setView(title)
                .setPositiveButton(R.string.meeting_everyone,
                        (d, w) -> withMeetingMic(() -> startMeeting(title.getText().toString().trim(), null)))
                .setNeutralButton(R.string.meeting_choose_people, (d, w) -> com.fc.freer.call.MemberPicker.show(this,
                        com.fc.freer.call.MemberPicker.members(imType.name(), targetId, java.util.Set.of(liveFid)),
                        chosen -> withMeetingMic(() -> startMeeting(title.getText().toString().trim(), chosen))))
                .setNegativeButton(R.string.cancel, null));
    }

    private void startMeeting(String title, java.util.List<String> invitees) {
        com.fc.freer.call.MeetingManager.getInstance(this).start(imType.name(), targetId, title, invitees, error -> {
            if (error != null) {
                ToastUtils.makeText(this, error);
                return;
            }
            startActivity(new android.content.Intent(this, com.fc.freer.call.MeetingActivity.class));
        });
    }

    @Override
    public void onJoinMeeting(ImMessage card, String meetingId) {
        withMeetingMic(() -> {
            com.fc.freer.call.MeetingManager meetings = com.fc.freer.call.MeetingManager.getInstance(this);
            Runnable join = () -> meetings.join(meetingId, error -> {
                if (error != null) {
                    ToastUtils.makeText(this, error);
                    return;
                }
                startActivity(new android.content.Intent(this, com.fc.freer.call.MeetingActivity.class));
            });
            com.fc.freer.call.MeetingBoard board = meetings.board();
            if (board != null && board.get(meetingId) == null && imManager != null) {
                // A card decrypted after its meeting signal went by: learn it now, off the main thread.
                new Thread(() -> {
                    imManager.noteMeetingCard(card);
                    runOnUiThread(join);
                }, "meeting-card").start();
            } else {
                join.run();
            }
        });
    }

    // ── Request history ─────────────────────────────────────────────────

    private void showRequestHistoryMemberPicker() {
        List<String> members = getMembersForCurrentChat();
        if (members == null || members.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        members.remove(liveFid);
        if (members.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_members_found));
            return;
        }

        String[] items = members.toArray(new String[0]);
        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.select_member_for_history)
                .setItems(items, (d, which) -> {
                    hideKeyboard();
                    showRequestHistoryDatePicker(items[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                );
    }

    private List<String> getMembersForCurrentChat() {
        if (imManager == null) return null;

        if (imType == ImType.TEAM) {
            Team team = imManager.getTeam(targetId);
            return team != null && team.getMembers() != null
                    ? new ArrayList<>(team.getMembers()) : null;
        } else if (imType == ImType.SQUARE) {
            Square square = imManager.getSquare(targetId);
            return square != null && square.getMembers() != null
                    ? new ArrayList<>(square.getMembers()) : null;
        } else if (imType == ImType.ROOM) {
            Room room = imManager.getRoom(targetId);
            return room != null && room.getMembers() != null
                    ? new ArrayList<>(room.getMembers()) : null;
        }
        return null;
    }

    private void showRequestHistoryDatePicker(String selectedFid) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_history_range, null);
        android.widget.DatePicker dpSince = dialogView.findViewById(R.id.date_picker_since);
        android.widget.DatePicker dpBefore = dialogView.findViewById(R.id.date_picker_before);

        java.util.Calendar calSince = java.util.Calendar.getInstance();
        calSince.add(java.util.Calendar.MONTH, -1);
        dpSince.updateDate(calSince.get(java.util.Calendar.YEAR),
                calSince.get(java.util.Calendar.MONTH),
                calSince.get(java.util.Calendar.DAY_OF_MONTH));

        DialogUtils.show(new AlertDialog.Builder(this)
                .setTitle(R.string.select_history_range)
                .setView(dialogView)
                .setPositiveButton(R.string.request, (d, w) -> {
                    hideKeyboard();

                    java.util.Calendar since = java.util.Calendar.getInstance();
                    since.set(dpSince.getYear(), dpSince.getMonth(), dpSince.getDayOfMonth(), 0, 0, 0);
                    since.set(java.util.Calendar.MILLISECOND, 0);

                    java.util.Calendar before = java.util.Calendar.getInstance();
                    before.set(dpBefore.getYear(), dpBefore.getMonth(), dpBefore.getDayOfMonth(), 23, 59, 59);
                    before.set(java.util.Calendar.MILLISECOND, 999);

                    if (imManager != null) {
                        imManager.requestHistory(imType, targetId, selectedFid,
                                since.getTimeInMillis(), before.getTimeInMillis());
                        ToastUtils.makeText(this, getString(R.string.history_request_sent, selectedFid));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                );
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        dismissLeaveTeamDialog();

        com.fc.freer.call.MeetingManager.getInstance(this).addCardListener(meetingCards);
        catchUpMeetingCards();
        if (imManager != null) {
            imManager.addListener(this);
            imManager.setActiveChatDock(imType, targetId);

            retryFailedDocksForCurrentChat();
            promptOpenHistoryRequests();
            refreshSymkeyAskBanner();

            if (isSymkeyRequired() && groupHasDock && hasSymkey()) {
                if (messageInput != null && !messageInput.isEnabled()) {
                    enableSending();
                    loadMessages();
                }
            }
        }
    }

    private void retryFailedDocksForCurrentChat() {
        if (imManager == null || imType == null) return;
        String entityType;
        if (imType == ImType.TEAM) entityType = "team";
        else if (imType == ImType.ROOM) entityType = "room";
        else if (imType == ImType.SQUARE) entityType = "square";
        else return;
        if (!imManager.hasFailedDocks(entityType)) return;
        imManager.retryFailedDocks(entityType, reconnected -> runOnUiThread(() -> {
            if (reconnected != null && !reconnected.isEmpty()) {
                imManager.setActiveChatDock(imType, targetId);
            }
        }));
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        saveDraft();
        if (isRecording) cancelRecording();
        if (voicePlayer != null) voicePlayer.stop();
        com.fc.freer.call.MeetingManager.getInstance(this).removeCardListener(meetingCards);
        if (imManager != null) {
            imManager.removeListener(this);
            imManager.clearActiveChatDock();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceRecorder != null) voiceRecorder.release();
    }
}
