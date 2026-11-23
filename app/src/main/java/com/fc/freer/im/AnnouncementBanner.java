package com.fc.freer.im;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.freer.R;

import java.util.List;

/**
 * The banner the Team and Room pages put above their threads: one row per group of that kind
 * with announcements not yet confirmed. A tap reads them, one at a time; confirming the last
 * clears the row. Fills {@code activity_talk}'s {@code announcements_banner}.
 */
public final class AnnouncementBanner {

    private AnnouncementBanner() {}

    public static void bind(Activity activity, ImManager imManager, String liveFid, ImType type) {
        LinearLayout banner = activity.findViewById(R.id.announcements_banner);
        if (banner == null) return;
        List<List<AnnouncementCenter.Item>> groups =
                AnnouncementCenter.unconfirmedByGroup(imManager, liveFid, type);
        banner.removeAllViews();
        banner.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        int pad = AnnouncementDialogs.dp(activity, 10);
        for (List<AnnouncementCenter.Item> items : groups) {
            String name = items.get(0).groupName();
            TextView row = new TextView(activity);
            row.setText(items.size() == 1
                    ? activity.getString(R.string.announcement_to_confirm_one, name)
                    : activity.getString(R.string.announcement_to_confirm_many, name, items.size()));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setTextColor(activity.getColor(R.color.accent));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundResource(R.drawable.container_outline);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_campaign, 0, R.drawable.ic_chevron_right, 0);
            row.setCompoundDrawablePadding(AnnouncementDialogs.dp(activity, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = AnnouncementDialogs.dp(activity, 4);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> AnnouncementDialogs.showRead(activity, imManager, liveFid, items,
                    () -> bind(activity, imManager, liveFid, type)));
            banner.addView(row);
        }
    }
}
