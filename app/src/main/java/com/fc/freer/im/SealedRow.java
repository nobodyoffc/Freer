package com.fc.freer.im;

import android.content.Context;

import com.fc.freer.R;

/**
 * The placeholder standing in for a message this device cannot open, and how it reads.
 *
 * <p>What is <b>stored</b> is a stable marker carrying the version, never a sentence: a
 * transcript row keeps its meaning across a language change, and the sentence a person sees is
 * built at render time by {@link #text}. The {@code [Encrypted} prefix is what every "is this
 * still sealed?" check in the app looks for, so it is kept exactly.
 */
public final class SealedRow {

    private static final String PREFIX = "[Encrypted";

    private SealedRow() {}

    /** What is written into the message's content while its key is missing. */
    public static String placeholder(long version) {
        return PREFIX + ":" + version + "]";
    }

    /** Whether this content is a sealed-row placeholder rather than a message. */
    public static boolean isPlaceholder(String content) {
        return content != null && content.startsWith(PREFIX);
    }

    /**
     * The version named by a placeholder, or null.
     *
     * <p>Rows written before this format exist in the field, so the older
     * {@code [Encrypted - missing symkey(v3)]} is read too rather than being left blank.
     */
    public static Long versionOf(String content) {
        if (!isPlaceholder(content)) return null;
        int colon = content.indexOf(':');
        if (colon > 0) {
            String tail = content.substring(colon + 1).replace("]", "").trim();
            try {
                return Long.parseLong(tail);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int vAt = content.indexOf("(v");
        if (vAt > 0) {
            String tail = content.substring(vAt + 2).replace(")", "").replace("]", "").trim();
            try {
                return Long.parseLong(tail);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * What the row says: when the key was minted, and that it is not held here.
     *
     * <p>{@code Sealed with the symkey from 19 Sep — not held here}. Never {@code v1789813689},
     * which names nothing a person can act on.
     */
    public static String text(Context context, String content, Long messageVersion) {
        Long version = versionOf(content);
        if (version == null) version = messageVersion;
        if (version == null) return context.getString(R.string.symkey_sealed_unknown);
        return context.getString(R.string.symkey_sealed_from,
                SymkeyVersionText.prose(context, version));
    }
}
