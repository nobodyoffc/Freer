package com.fc.freer.mail;

import static android.view.View.GONE;

import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.CIPHER;
import static com.fc.fc_ajdk.constants.FieldNames.NOTICE_FEE;
import static com.fc.fc_ajdk.constants.FieldNames.ON_CHAIN;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class ReadMailActivity extends BaseCryptoActivity {
    private static final String TAG = "ReadMailActivity";
    public static final String EXTRA_MAIL_JSON = "extra_mail_json";
    public static final String EXTRA_READ_MAIL_ID = "extra_read_mail_id";
    public static final int RESULT_REPLIED = 1001;
    public static final int CONTENT_BRIEF_LENGTH = 48;

    private Mail mail;
    private ImageButton replyButton;
    private TextView mailContentTextView;
    private LinearLayout detailsContainer;

    // From card components
    private ImageView fromAvatar;
    private TextView fromCid;
    private TextView fromLabel;
    private TextView fromId;

    // To card components
    private ImageView toAvatar;
    private TextView toCid;
    private TextView toLabel;
    private TextView toId;
    private String myFid;

    private ActivityResultLauncher<Intent> createMailLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize activity result launcher
        initializeActivityResultLauncher();

        // Load mail from intent
        String mailJson = getIntent().getStringExtra(EXTRA_MAIL_JSON);
        if (mailJson == null) {
            ToastUtils.makeText(this, getString(R.string.error_no_mail_data_provided));
            finish();
            return;
        }

        try {
            mail = JsonUtils.fromJson(mailJson, Mail.class);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing mail JSON: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.error_invalid_mail_data));
            finish();
            return;
        }

        if (mail == null) {
            ToastUtils.makeText(this, getString(R.string.error_invalid_mail_data));
            finish();
            return;
        }

        if(mail.getFromName()==null)mail.makeName();

        myFid = FidManager.getInstance().getLiveFid();

        // Load mail content and display
        loadMailContent();
    }

    private void initializeActivityResultLauncher() {
        createMailLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // Reply was sent successfully — include mail ID so caller can mark it as read
                    Intent resultIntent = new Intent();
                    if (mail != null && mail.getId() != null) {
                        resultIntent.putExtra(EXTRA_READ_MAIL_ID, mail.getId());
                    }
                    setResult(RESULT_REPLIED, resultIntent);
                    finish();
                }
            }
        );
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_read_mail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.read_mail);
    }

    @Override
    protected void initializeViews() {
        // Initialize buttons
        replyButton = findViewById(R.id.reply_button);

        // Initialize content view
        mailContentTextView = findViewById(R.id.mail_content);
        detailsContainer = findViewById(R.id.details_container);

        // Initialize from card components
        View fromCard = findViewById(R.id.from_key_card);
        fromCard.setBackgroundColor(getColor(R.color.transparent));
        fromAvatar = fromCard.findViewById(R.id.key_avatar);
        fromCid = fromCard.findViewById(R.id.key_cid);
        fromLabel = fromCard.findViewById(R.id.key_label);
        fromId = fromCard.findViewById(R.id.key_id);

        // Initialize to card components
        View toCard = findViewById(R.id.to_key_card);
        toCard.setBackgroundColor(getColor(R.color.transparent));
        toAvatar = toCard.findViewById(R.id.key_avatar);
        toCid = toCard.findViewById(R.id.key_cid);
        toLabel = toCard.findViewById(R.id.key_label);
        toId = toCard.findViewById(R.id.key_id);
    }

    @Override
    protected void setupButtons() {
        replyButton.setOnClickListener(v -> replyToMail());
    }

    @Override
    protected void setupBackButton() {
        backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> {
            // Return the mail ID so the calling activity can mark it as read
            Intent resultIntent = new Intent();
            if (mail != null && mail.getId() != null) {
                resultIntent.putExtra(EXTRA_READ_MAIL_ID, mail.getId());
            }
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        Intent resultIntent = new Intent();
        if (mail != null && mail.getId() != null) {
            resultIntent.putExtra(EXTRA_READ_MAIL_ID, mail.getId());
        }
        setResult(RESULT_OK, resultIntent);
        super.onBackPressed();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadMailContent() {
        // Setup from card
        setupKeyCard(fromAvatar, fromCid, fromLabel, fromId,
                    mail.getFrom(), mail.getFromName(), "From");

        // Setup to card
        setupKeyCard(toAvatar, toCid, toLabel, toId,
                    mail.getTo(), mail.getToName(), "To");

        bindSenderNobodyBanner();

        // Setup content with markdown-like formatting
        if (mail.getContent() != null) {
            mailContentTextView.setText(formatContentAsMarkdown(mail.getContent()));
        } else {
            mailContentTextView.setText(getString(R.string.content_not_available));
        }

        // Setup other fields
        setupDetailsSection();
    }

    /** Warn when the mail comes from a nobody; look the sender up if still unknown. */
    private void bindSenderNobodyBanner() {
        String from = mail.getFrom();
        if (from == null || from.equals(myFid)) return;
        TextView banner = findViewById(R.id.nobodyBanner);
        NobodyUi.bindBanner(banner, from, R.string.nobody_sender_warning);
        NobodyUi.resolveAsync(java.util.Collections.singletonList(from), false, ok -> {
            if (isFinishing() || isDestroyed() || !NobodyUi.isNobody(from)) return;
            NobodyUi.bindBanner(banner, from, R.string.nobody_sender_warning);
            setupKeyCard(fromAvatar, fromCid, fromLabel, fromId, from, mail.getFromName(), "From");
        });
    }

    private void setupKeyCard(ImageView avatar, TextView cidText, TextView labelText,
                            TextView idText, String fid, String name, String type) {
        if (fid != null) {
            // Hide CID and label
            cidText.setVisibility(GONE);
            labelText.setVisibility(GONE);

            // Set full ID
            if(fid.equals(myFid)){
                idText.setText(R.string.me);
            }
            else NobodyUi.setName(idText, fid, name);

            // Setup avatar
            setupAvatar(avatar, fid);

            // Set click listener to copy FID
            View cardParent = (View) avatar.getParent();
            cardParent.setOnClickListener(v -> copyToClipboard(fid, type + " FID"));
        } else {
            cidText.setVisibility(GONE);
            labelText.setVisibility(GONE);
            idText.setText("N/A");
        }
    }

    private void setupAvatar(ImageView avatarView, String fid) {
        if (fid == null || fid.equals(myFid)) {
            avatarView.setVisibility(GONE);
            return;
        }

        try {
            // Set initial background
            avatarView.setBackgroundColor(getResources().getColor(R.color.accent, getTheme()));

            // Get avatar in background thread
            new Thread(() -> {
                try {
                    AvatarManager avatarManager = AvatarManager.getInstance(this);
                    Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);

                    runOnUiThread(() -> {
                        if (avatarBitmap != null && !isFinishing() && !isDestroyed()) {
                            avatarView.setImageBitmap(avatarBitmap);
                            avatarView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                        }
                    });
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error loading avatar for FID %s: %s", fid, e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up avatar: %s", e.getMessage());
        }
    }

    private SpannableStringBuilder formatContentAsMarkdown(String content) {
        SpannableStringBuilder builder = new SpannableStringBuilder(content);

        // Simple markdown formatting
        String text = content;
        int start = 0;

        // Bold formatting (**text**)
        while (true) {
            int boldStart = text.indexOf("**", start);
            if (boldStart == -1) break;
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd == -1) break;

            builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                           boldStart, boldEnd + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = boldEnd + 2;
        }

        // Italic formatting (*text*)
        start = 0;
        while (true) {
            int italicStart = text.indexOf("*", start);
            if (italicStart == -1) break;
            // Skip if it's part of **
            if (italicStart > 0 && text.charAt(italicStart - 1) == '*') {
                start = italicStart + 1;
                continue;
            }
            if (italicStart < text.length() - 1 && text.charAt(italicStart + 1) == '*') {
                start = italicStart + 1;
                continue;
            }

            int italicEnd = text.indexOf("*", italicStart + 1);
            if (italicEnd == -1) break;

            builder.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC),
                           italicStart, italicEnd + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = italicEnd + 1;
        }

        // Underline formatting (_text_)
        start = 0;
        while (true) {
            int underlineStart = text.indexOf("_", start);
            if (underlineStart == -1) break;
            int underlineEnd = text.indexOf("_", underlineStart + 1);
            if (underlineEnd == -1) break;

            builder.setSpan(new UnderlineSpan(),
                           underlineStart, underlineEnd + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = underlineEnd + 1;
        }

        return builder;
    }

    private void setupDetailsSection() {
        detailsContainer.removeAllViews();

        LinkedHashMap<String, Map<String, String>> fieldMap = Mail.getFieldNameMap();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        // Determine language based on locale
        String language = Locale.getDefault().getLanguage().equals("zh") ? "zh" : "en";

        for (Map.Entry<String, Map<String, String>> entry : fieldMap.entrySet()) {
            String fieldName = entry.getKey();
            Map<String, String> languageMap = entry.getValue();

            // Skip from, to, and content as they are already displayed
            if ("from".equals(fieldName) || "to".equals(fieldName) || "content".equals(fieldName)) {
                continue;
            }

            String displayName = languageMap.get(language);
            Object value = getFieldValue(fieldName);

            if (value != null) {
                addDetailRow(displayName, formatFieldValue(fieldName, value, dateFormat));
            }
        }
    }

    private Object getFieldValue(String fieldName) {
        switch (fieldName) {
            case "birthTime":
                return mail.getBirthTime();
            case "lastHeight":
                return mail.getLastHeight();
            case "onChain":
                return mail.getOnChain();
            case "cipher":
                return mail.getCipher();
            case "noticeFee":
                return mail.getNoticeFee();
            default:
                return null;
        }
    }

    private String formatFieldValue(String fieldName, Object value, SimpleDateFormat dateFormat) {
        if (value == null) return "N/A";

        switch (fieldName) {
            case BIRTH_TIME:
                if (value instanceof Long) {
                    Long timestamp = (Long) value;
                    if (timestamp > 0) {
                        return dateFormat.format(new Date(timestamp * 1000));
                    }
                }
                return value.toString();
            case ON_CHAIN:
                if (value instanceof Boolean) {
                    return ((Boolean) value) ? "Yes" : "No";
                }
                return value.toString();
            case CIPHER:
                String cipher = value.toString();
                return cipher.length() > 50 ? cipher.substring(0, 50) + "..." : cipher;
            case NOTICE_FEE:
                String fee = value.toString();
                try {
                    long satoshis = Long.parseLong(fee);
                    return FchUtils.formatSatoshiToCash(satoshis) +"c "+"("+FchUtils.formatSatoshiToCoin(satoshis)+"F)";
                }catch (Exception ignore){
                    return fee;
                }
            default:
                return value.toString();
        }
    }

    private void addDetailRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        TextView labelView = new TextView(this);

        labelView.setText(label + ":");
        labelView.setTextSize(14);
        labelView.setTextColor(getResources().getColor(R.color.hint, getTheme()));
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(getResources().getColor(R.color.text, getTheme()));
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        valueView.setTextIsSelectable(true);

        row.addView(labelView);
        row.addView(valueView);
        detailsContainer.addView(row);
    }

    private void replyToMail() {
        if (mail == null) {
            ToastUtils.makeText(this, getString(R.string.invalid_mail));
            return;
        }


        if (myFid == null) {
            ToastUtils.makeText(this, getString(R.string.no_live_fid_available));
            return;
        }

        // Determine recipient FID (the FID different from live FID)
        String recipientFid;
        if (myFid.equals(mail.getFrom())) {
            // If current user is sender, reply to the original recipient
            recipientFid = mail.getTo();
        } else {
            // If current user is recipient, reply to the original sender
            recipientFid = mail.getFrom();
        }

        if (recipientFid == null || recipientFid.trim().isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.cannot_determine_reply_recipient));
            return;
        }

        // Create reply content with mail ID reference
        String replyContent = MailActivity.makeReplyHead(mail);

        // Launch CreateMailActivity with pre-filled data
        Intent intent = new Intent(this, CreateMailActivity.class);
        intent.putExtra("recipientFid", recipientFid);
        intent.putExtra("replyContent", replyContent);
        createMailLauncher.launch(intent);
    }

    public void copyToClipboard(String text, String label) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(this, getString(R.string.copied));
    }
}