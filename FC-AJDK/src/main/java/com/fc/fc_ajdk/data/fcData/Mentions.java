package com.fc.fc_ajdk.data.fcData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code @somebody} in a group's messages.
 *
 * <p><b>A mention is text, nothing more.</b> The wire has no field for one, so a mention is
 * {@code @} and the person's CID — or their FID, when they have no CID — written into the
 * message, which every client shows as it is. Whoever reads it as themselves is the one
 * mentioned: the receiver looks for its own CID or FID after an {@code @}.
 *
 * <p>An {@code @} counts only at the start of the text or after a space, so an address like
 * {@code a@b} is not read as a mention of {@code b}; a name ends at a space, the next
 * {@code @}, or punctuation — which in Chinese follows it with no space: {@code @张三，你好}.
 *
 * <p>The rules are FreerForMac's {@code Mentions} too, case for case — see
 * {@code MentionsTest} and {@code MentionsTests}: a mention must be found the same way on
 * every device.
 */
public final class Mentions {

    private Mentions() {}

    /** Punctuation that ends a name: {@code @Alice,} mentions Alice. */
    private static final String TRAILING = ",.;:!?)]}>\"'，。；：！？、）】》」』";

    /** One {@code @name} in a text: {@code start} is the {@code @}, {@code end} exclusive. */
    public static final class Token {
        public final int start;
        public final int end;
        public final String name;

        Token(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean endsName(char c) {
        return isSpace(c) || c == '@' || TRAILING.indexOf(c) >= 0;
    }

    /** Every {@code @name} in {@code text}, in order. */
    public static List<Token> tokens(String text) {
        List<Token> found = new ArrayList<>();
        if (text == null) return found;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (text.charAt(i) != '@' || (i > 0 && !isSpace(text.charAt(i - 1)))) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < n && !endsName(text.charAt(end))) end++;
            if (end > i + 1) found.add(new Token(i, end, text.substring(i + 1, end)));
            i = Math.max(end, i + 1);
        }
        return found;
    }

    /** Whether {@code text} mentions the identity whose FID and CID these are. */
    public static boolean mentions(String text, String fid, String cid) {
        if (text == null || text.indexOf('@') < 0) return false;
        Set<String> names = new HashSet<>();
        if (fid != null && !fid.isEmpty()) names.add(fid);
        if (cid != null && !cid.isEmpty()) names.add(cid);
        for (Token t : tokens(text)) {
            if (names.contains(t.name)) return true;
        }
        return false;
    }

    /** Whether {@code message} is a group's text that mentions the identity. */
    public static boolean mentions(ImMessage message, String fid, String cid) {
        return message != null && message.getType() != null && message.getType() != ImType.P2P
                && message.getContentType() == ContentType.TEXT
                && mentions(message.getContent(), fid, cid);
    }

    /**
     * The name being typed after an {@code @} that ends {@code draft} — "" right after the
     * {@code @} itself — or null when the draft does not end in one.
     */
    public static String pendingQuery(String draft) {
        if (draft == null) return null;
        int at = draft.lastIndexOf('@');
        if (at < 0) return null;
        if (at > 0 && !isSpace(draft.charAt(at - 1))) return null;
        String typed = draft.substring(at + 1);
        for (int i = 0; i < typed.length(); i++) {
            if (isSpace(typed.charAt(i))) return null;
        }
        return typed;
    }

    /**
     * {@code draft} with the {@code @name} being typed at its end — or, when none is, nothing —
     * replaced by {@code @handle }.
     */
    public static String complete(String draft, String handle) {
        String base = draft != null ? draft : "";
        if (pendingQuery(base) != null) {
            base = base.substring(0, base.lastIndexOf('@'));
        } else if (!base.isEmpty() && !isSpace(base.charAt(base.length() - 1))) {
            base = base + " ";
        }
        return base + "@" + handle + " ";
    }
}
