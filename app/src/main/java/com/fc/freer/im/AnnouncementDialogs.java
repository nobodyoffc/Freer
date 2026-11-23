package com.fc.freer.im;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.data.fcData.Announcements;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.freer.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A team's or a room's announcements: posting one, reading and confirming those not yet
 * confirmed, and the group's history of them. The rules are {@link Announcements}; the
 * bookkeeping is {@link AnnouncementCenter}.
 */
public final class AnnouncementDialogs {

    private AnnouncementDialogs() {}

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /**
     * Write an announcement; {@code post} is handed its wire text to send to every member, the
     * way the chat page sends anything, so it lands in the thread at once.
     */
    public static void showCompose(Activity activity, Consumer<String> post) {
        LinearLayout box = column(activity);
        TextView hint = new TextView(activity);
        hint.setText(R.string.announcement_hint);
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(hint);
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.announcement_new)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.announcement_post, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button postButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            postButton.setOnClickListener(v -> {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) return;
                try {
                    post.accept(Announcements.text(text));
                    dialog.dismiss();
                } catch (Exception e) {
                    Toast.makeText(activity, activity.getString(R.string.announcement_post_failed,
                            String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    /**
     * The announcements waiting for this identity, one at a time, oldest first. Confirming one
     * moves to the next; the dialog closes on the last.
     */
    public static void showRead(Activity activity, ImManager im, String liveFid,
                                List<AnnouncementCenter.Item> items, Runnable onChanged) {
        if (items == null || items.isEmpty()) return;
        showRead(activity, im, liveFid, items, 0, onChanged);
    }

    private static void showRead(Activity activity, ImManager im, String liveFid,
                                 List<AnnouncementCenter.Item> items, int index, Runnable onChanged) {
        AnnouncementCenter.Item item = items.get(index);
        boolean manyGroups = false;
        for (AnnouncementCenter.Item i : items) {
            if (!i.conversationId().equals(items.get(0).conversationId())) { manyGroups = true; break; }
        }
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = column(activity);
        box.addView(card(activity, item, manyGroups));
        scroll.addView(box);

        String title = activity.getString(R.string.announcement);
        if (items.size() > 1) title += "  (" + activity.getString(R.string.announcement_of, index + 1, items.size()) + ")";
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(R.string.announcement_later, null)
                .setPositiveButton(R.string.announcement_confirm, (d, w) -> {
                    AnnouncementCenter.confirm(liveFid, item);
                    if (onChanged != null) onChanged.run();
                    if (index + 1 < items.size()) showRead(activity, im, liveFid, items, index + 1, onChanged);
                })
                .show();
    }

    /** Every announcement a group has had, newest first; one still waiting can be confirmed. */
    public static void showHistory(Activity activity, ImManager im, String liveFid,
                                   Conversation conversation, Consumer<String> post, Runnable onChanged) {
        // A history may hold announcements this device never indexed — ones that came in an
        // imported history — so the thread is read once here.
        AnnouncementCenter.indexConversation(im, liveFid, conversation.getType(), conversation.getTargetId());
        List<AnnouncementCenter.Item> items = AnnouncementCenter.history(im, liveFid, conversation);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = column(activity);
        scroll.addView(box);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.announcement_history)
                .setView(scroll)
                .setPositiveButton(R.string.announcement_close, null);
        boolean mayPost = AnnouncementCenter.mayAnnounce(im, liveFid, conversation.getType(), conversation.getTargetId());
        if (mayPost && post != null) {
            builder.setNeutralButton(R.string.announcement_new, (d, w) -> showCompose(activity, post));
        }
        if (items.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.announcement_none);
            none.setTextColor(activity.getColor(R.color.text_secondary));
            none.setPadding(0, dp(activity, 16), 0, dp(activity, 16));
            box.addView(none);
        }
        for (AnnouncementCenter.Item item : items) {
            box.addView(card(activity, item, false));
            TextView state = new TextView(activity);
            state.setGravity(Gravity.END);
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            state.setPadding(0, dp(activity, 4), 0, dp(activity, 10));
            if (item.entry.isConfirmed()) {
                state.setText(R.string.announcement_confirmed);
                state.setTextColor(activity.getColor(R.color.text_secondary));
            } else {
                state.setText(R.string.announcement_confirm);
                state.setTextColor(activity.getColor(R.color.accent));
                state.setTypeface(Typeface.DEFAULT_BOLD);
                state.setOnClickListener(v -> {
                    AnnouncementCenter.confirm(liveFid, item);
                    state.setText(R.string.announcement_confirmed);
                    state.setTextColor(activity.getColor(R.color.text_secondary));
                    state.setTypeface(Typeface.DEFAULT);
                    state.setOnClickListener(null);
                    if (onChanged != null) onChanged.run();
                });
            }
            box.addView(state);
        }
        builder.show();
    }

    /** One announcement: who posted it, when, and what it says. */
    static View card(Activity activity, AnnouncementCenter.Item item, boolean showGroup) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundResource(R.drawable.container_outline);
        if (showGroup) {
            TextView group = new TextView(activity);
            group.setText(item.groupName());
            group.setTypeface(Typeface.DEFAULT_BOLD);
            group.setTextColor(activity.getColor(R.color.accent));
            group.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            card.addView(group);
        }
        TextView head = new TextView(activity);
        String sender = item.message.getSenderId();
        String cid = MentionCenter.cidOf(sender);
        String who = cid != null ? cid : shortFid(sender);
        Long t = item.message.getTimestamp();
        head.setText(t != null ? who + "  ·  " + TIME.format(new Date(t)) : who);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTextColor(activity.getColor(R.color.text_secondary));
        head.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_campaign, 0, 0, 0);
        head.setCompoundDrawablePadding(dp(activity, 6));
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head);
        TextView body = new TextView(activity);
        body.setText(item.body());
        body.setTextIsSelectable(true);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setTextColor(activity.getColor(R.color.text));
        body.setPadding(0, dp(activity, 8), 0, 0);
        card.addView(body);
        return card;
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        box.setPadding(pad, dp(activity, 8), pad, 0);
        return box;
    }

    private static String shortFid(String fid) {
        if (fid == null) return "";
        return fid.length() > 12 ? fid.substring(0, 6) + "…" + fid.substring(fid.length() - 6) : fid;
    }

    static int dp(Activity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
