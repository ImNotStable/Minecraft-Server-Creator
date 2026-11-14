package io.github.imnotstable.msc.core.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

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
            cut = keep;
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

    /** The hint of a cut a larger {@code maxChars} undoes: "Raise maxChars (at most 24000) to see more." */
    public static final String RAISE = "Raise maxChars (at most " + MAX_CHARS + ") to see more.";

    /**
     * Splits {@code total} characters between parts that want {@code wants}: a part that wants no more than an even
     * share gets all it wants, and what it leaves goes to the others. The shares never add up to more than
     * {@code total}.
     */
    public static int[] split(int total, int... wants) {
        Integer[] order = new Integer[wants.length];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (a, b) -> Integer.compare(wants[a], wants[b]));
        int[] shares = new int[wants.length];
        int left = Math.max(0, total);
        for (int i = 0; i < order.length; i++) {
            int share = Math.min(wants[order[i]], left / (order.length - i));
            shares[order[i]] = share;
            left -= share;
        }
        return shares;
    }

    /**
     * A result built from parts that share one cap (#473): fixed text kept whole, parts that cut themselves to the
     * share they are given ({@link #split}), and a closing line that is always last. A tool that builds its result
     * this way never reaches {@link ToolRegistry}'s cut: when even the fixed text is longer than the cap, the text
     * before the closing line is cut, with its hint, and the closing line stays.
     */
    public static final class Parts {
        /** Fixed text, or a part that cuts itself when {@code fixed} is null. */
        private record Piece(String fixed, IntFunction<String> render) {
        }

        private final List<Piece> pieces = new ArrayList<>();

        /** Text kept whole; the caller puts in the line breaks. */
        public Parts add(String fixed) {
            if (fixed != null && !fixed.isEmpty()) {
                pieces.add(new Piece(fixed, null));
            }
            return this;
        }

        /**
         * A part that cuts itself: {@code render.apply(n)} is the part in at most {@code n} characters (its own cut
         * line included), and {@code render.apply(Integer.MAX_VALUE)} the whole part.
         */
        public Parts cut(IntFunction<String> render) {
            pieces.add(new Piece(null, render));
            return this;
        }

        public boolean isEmpty() {
            return pieces.isEmpty();
        }

        public Parts add(Parts other) {
            pieces.addAll(other.pieces);
            return this;
        }

        /**
         * The result in at most {@code cap} characters, {@code last} (or nothing, when null) as its last line. When the
         * fixed text alone does not fit, the text before {@code last} is cut with {@code hint}.
         */
        public String text(int cap, String last, String hint) {
            String closing = last == null ? "" : pieces.isEmpty() ? last : "\n" + last;
            int fixed = closing.length();
            List<Piece> parts = new ArrayList<>();
            for (Piece piece : pieces) {
                if (piece.fixed() != null) {
                    fixed += piece.fixed().length();
                } else {
                    parts.add(piece);
                }
            }
            int[] wants = new int[parts.size()];
            for (int i = 0; i < wants.length; i++) {
                wants[i] = parts.get(i).render().apply(Integer.MAX_VALUE).length();
            }
            int[] shares = split(cap - fixed, wants);
            StringBuilder body = new StringBuilder();
            int next = 0;
            for (Piece piece : pieces) {
                body.append(piece.fixed() != null ? piece.fixed() : piece.render().apply(shares[next++]));
            }
            String text = body.toString();
            if (closing.length() >= cap) {
                return truncate(last, cap, hint);
            }
            return (text.length() + closing.length() <= cap ? text : truncate(text, cap - closing.length(), hint))
                    + closing;
        }
    }

    public static int tokens(int chars) {
        return (chars + 3) / 4;
    }
}
