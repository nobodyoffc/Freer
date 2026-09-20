package com.fc.freer.im;

import android.content.Context;
import android.text.format.DateUtils;

import com.fc.freer.R;

import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;

/**
 * How a symkey version is shown to a person -- §6 of {@code docs/SYMKEY_IDENTITY_SPEC.md}.
 *
 * <p><b>A version is a time, so prose shows the time and never the number.</b> The number is
 * worth its width only where two ids are being compared, and it is what goes into a log or a
 * message to another member, so it is what a tap copies.
 *
 * <p>The id genuinely <i>is</i> a time, and a time tells a person which era of the conversation
 * they are missing, and therefore who to ask. A hash tag would tell them nothing of the sort.
 * The content hash is never shown at all: it exists so the store and the ledger can be exact,
 * and a person comparing two keys compares times.
 */
public final class SymkeyVersionText {

    private SymkeyVersionText() {}

    /** A pre-spec counter shows as the counter it is, marked. */
    public static boolean isLegacy(long version) {
        return SymkeyStore.isLegacyVersion(version);
    }

    private static long millisOf(long version) {
        return version * 1000L;
    }

    private static boolean thisYear(long version) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millisOf(version));
        return then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
    }

    /**
     * The mint time in prose, for a sealed row, a banner or a toast.
     *
     * @param among the other versions shown beside this one; the clock appears only when a date
     *              alone would name two of them. See {@link #needsTime(long, Collection)}.
     */
    public static String prose(Context context, long version, Collection<Long> among) {
        if (isLegacy(version)) return context.getString(R.string.symkey_version_legacy, version);

        String pattern = needsTime(version, among)
                ? (thisYear(version) ? "d MMM HH:mm" : "d MMM yyyy HH:mm")
                : (thisYear(version) ? "d MMM" : "d MMM yyyy");
        return new java.text.SimpleDateFormat(pattern, Locale.getDefault())
                .format(new Date(millisOf(version)));
    }

    public static String prose(Context context, long version) {
        return prose(context, version, null);
    }

    /**
     * Year-first and fixed-width, for a column that has to diff by eye -- a key list, a ledger
     * row, an ask row. The current year's {@code 2026-} is dropped, because every row in the
     * column shares it.
     *
     * <p>Year-first rather than {@code 26-09-19}: {@code 26} reads as a day in half the world,
     * and this app has users in both halves. Seconds, because two keys of one entity are minted
     * seconds apart in exactly the case where telling them apart matters.
     */
    public static String compact(Context context, long version) {
        if (isLegacy(version)) return context.getString(R.string.symkey_version_legacy, version);
        String pattern = thisYear(version) ? "MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss";
        return new java.text.SimpleDateFormat(pattern, Locale.getDefault())
                .format(new Date(millisOf(version)));
    }

    /** A secondary line, never the only rendering. */
    public static String age(long version) {
        if (isLegacy(version)) return "";
        return DateUtils.getRelativeTimeSpanString(
                millisOf(version), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    /** What a tap copies: the raw integer, which is what goes into a log or a message. */
    public static String raw(long version) {
        return String.valueOf(version);
    }

    /**
     * Whether a date alone would fail to name this version among the ones shown beside it.
     *
     * <p>A date is the right rendering almost always, and two keys minted on one day are the
     * case where it stops being one. The caller passes what it is showing, so the clock appears
     * only when it has to.
     */
    public static boolean needsTime(long version, Collection<Long> among) {
        if (among == null || isLegacy(version)) return false;
        for (Long other : among) {
            if (other == null || other == version || isLegacy(other)) continue;
            if (sameDay(version, other)) return true;
        }
        return false;
    }

    private static boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(millisOf(a));
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(millisOf(b));
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }
}
