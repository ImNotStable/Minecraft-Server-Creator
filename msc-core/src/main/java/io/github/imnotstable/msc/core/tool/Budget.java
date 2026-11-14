package io.github.imnotstable.msc.core.tool;

import io.github.imnotstable.msc.protocol.Limits;

/** Size limits for tool results. One token is roughly four characters. */
public final class Budget {
    /** Default cap for a tool result, about 1,500 tokens. */
    public static final int DEFAULT_CHARS = 6000;

    /** Largest cap a caller may ask for, about 6,000 tokens. */
    public static final int MAX_CHARS = 24000;

    /** Length of the closing line without its hint: {@code "\n[truncated: "}, the words, ten-digit numbers. */
    private static final int MARK_CHARS = "\n[truncated:  more lines,  chars. ]".length() + 20;

    /**
     * The cap on a result of {@code tool} called with {@code args}: the caller's {@code maxChars} when the tool takes
     * one, else {@link #DEFAULT_CHARS}. {@link ToolRegistry} applies it to every result, so no tool can exceed it.
     */
    public static int cap(Tool tool, Args args) {
        return takesMaxChars(tool) ? of(args) : DEFAULT_CHARS;
    }

    /** True when the tool's schema has a {@code maxChars} argument. */
    public static boolean takesMaxChars(Tool tool) {
        return tool.inputSchema().getAsJsonObject("properties").has("maxChars");
    }

    private Budget() {
    }

    /** The caller's {@code maxChars} argument, clamped to a sane range. */
    public static int of(Args args) {
        return Math.max(200, Math.min(MAX_CHARS, args.integer("maxChars", DEFAULT_CHARS)));
    }

    /**
     * Cut text at a line boundary and say how much was dropped and what to do about it. The result, the closing
     * {@code [truncated: ...]} line included, is never longer than {@code maxChars}.
     */
    public static String truncate(String text, int maxChars, String hint) {
        if (text.length() <= maxChars) {
            return text;
        }
        // Room for the closing line: its fixed words, two numbers of up to ten digits each, and the hint.
        int keep = Math.max(0, maxChars - (MARK_CHARS + hint.length()));
        int cut = text.lastIndexOf('\n', keep);
        if (cut <= 0) {
            cut = Limits.head(text, keep).length(); // never half an emoji
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
