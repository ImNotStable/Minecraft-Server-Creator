package io.github.imnotstable.msc.core.tool;

import io.github.imnotstable.msc.protocol.Limits;

/** Size limits for tool results. One token is roughly four characters. */
public final class Budget {
    /** Default cap for a tool result, about 1,500 tokens. */
    public static final int DEFAULT_CHARS = 6000;

    /** Largest cap a caller may ask for, about 6,000 tokens. */
    public static final int MAX_CHARS = 24000;

    private Budget() {
    }

    /** The caller's {@code maxChars} argument, clamped to a sane range. */
    public static int of(Args args) {
        return Math.max(200, Math.min(MAX_CHARS, args.integer("maxChars", DEFAULT_CHARS)));
    }

    /** Cut text at a line boundary and say how much was dropped and what to do about it. */
    public static String truncate(String text, int maxChars, String hint) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf('\n', maxChars);
        if (cut <= 0) {
            cut = Limits.head(text, maxChars).length(); // never half an emoji
        }
        int droppedLines = 0;
        for (int i = cut; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                droppedLines++;
            }
        }
        return text.substring(0, cut) + "\n[truncated: " + Math.max(1, droppedLines) + " more lines, "
                + (text.length() - cut) + " chars. " + hint + "]";
    }

    public static int tokens(int chars) {
        return (chars + 3) / 4;
    }
}
