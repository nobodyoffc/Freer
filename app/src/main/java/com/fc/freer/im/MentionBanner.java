package com.fc.freer.im;

import android.app.Activity;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.freer.R;

import java.util.List;
import java.util.Set;

/**
 * The banner a group list page (Square, Team, Room) puts above its threads: one row per group
 * of that kind where somebody {@code @}mentioned you in a message not yet read. A tap opens the
 * group; reading it clears the row. The pages share {@code activity_talk}, whose
 * {@code mentions_banner} this fills.
 */
public final class MentionBanner {

    private MentionBanner() {}

    /**
     * Fill the banner for {@code type}'s groups and return their conversation ids, for the
     * thread list's "@ you" chip.
     */
    public static Set<String> bind(Activity activity, ImManager imManager, String liveFid, ImType type) {
        LinearLayout banner = activity.findViewById(R.id.mentions_banner);
        List<MentionCenter.UnreadMention> mentions = MentionCenter.unread(imManager, liveFid, type);
        Set<String> ids = new java.util.HashSet<>();
        for (MentionCenter.UnreadMention m : mentions) ids.add(m.conversationId());
        if (banner == null) return ids;
        banner.removeAllViews();
        banner.setVisibility(mentions.isEmpty() ? View.GONE : View.VISIBLE);
        int pad = dp(activity, 10);
        for (MentionCenter.UnreadMention m : mentions) {
            TextView row = new TextView(activity);
            row.setText(m.count == 1
                    ? activity.getString(R.string.im_mentioned_once, m.groupName())
                    : activity.getString(R.string.im_mentioned_times, m.groupName(), m.count));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setTextColor(activity.getColor(R.color.red));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.container_outline);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(activity, 4);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                Intent intent = new Intent(activity, ChatActivity.class);
                intent.putExtra(ChatActivity.EXTRA_TYPE, m.conversation.getType().name());
                intent.putExtra(ChatActivity.EXTRA_TARGET_ID, m.conversation.getTargetId());
                intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, m.groupName());
                activity.startActivity(intent);
            });
            banner.addView(row);
        }
        return ids;
    }

    private static int dp(Activity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
