package com.fc.freer.ui;

import android.content.Context;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * A TextView that, when its text overflows maxLines, cuts the middle out and
 * puts "…" there, so the first and last characters of an FID stay visible.
 * The platform's ellipsize="middle" does nothing once maxLines is above 1.
 * A leading ReplacementSpan (the Nobody chip) is never cut.
 */
public class MiddleEllipsizeTextView extends AppCompatTextView {
    private static final String ELLIPSIS = "…";

    private CharSequence fullText;
    private boolean cutting;
    private final Runnable fitRunnable = this::fit;

    public MiddleEllipsizeTextView(Context context) {
        super(context);
    }

    public MiddleEllipsizeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MiddleEllipsizeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (!cutting) fullText = text;
        super.setText(text, type);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // A wider view may fit more: start again from the whole text
        if (w != oldw && fullText != null) setText(fullText);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Changing the text inside a layout pass would request another; do it after
        removeCallbacks(fitRunnable);
        post(fitRunnable);
    }

    private void fit() {
        Layout layout = getLayout();
        CharSequence shownText = getText();
        int maxLines = getMaxLines();
        if (layout == null || shownText == null || fullText == null
                || maxLines <= 0 || maxLines == Integer.MAX_VALUE) return;
        int last = Math.min(layout.getLineCount(), maxLines) - 1;
        if (last < 0) return;
        int shown = layout.getLineEnd(last) - layout.getEllipsisCount(last);
        if (shown >= shownText.length()) return;

        // Keep as many characters as were shown, one of them now the ellipsis
        int keep = protectedPrefixLength(fullText);
        int room = shown - keep - ELLIPSIS.length();
        if (room < 2) return;
        int head = (room + 1) / 2;
        int tail = room / 2;
        SpannableStringBuilder cut = new SpannableStringBuilder(fullText);
        cut.replace(keep + head, fullText.length() - tail, ELLIPSIS);

        cutting = true;
        try {
            setText(cut);
        } finally {
            cutting = false;
        }
    }

    private static int protectedPrefixLength(CharSequence text) {
        if (!(text instanceof Spanned)) return 0;
        Spanned spanned = (Spanned) text;
        int end = 0;
        for (ReplacementSpan span : spanned.getSpans(0, spanned.length(), ReplacementSpan.class)) {
            if (spanned.getSpanStart(span) == 0) end = Math.max(end, spanned.getSpanEnd(span));
        }
        return end;
    }
}
