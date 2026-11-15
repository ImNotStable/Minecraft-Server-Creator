package io.github.imnotstable.msc.core.tool;

import io.github.imnotstable.msc.protocol.Limits;
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
    /** Smallest {@code maxChars} a caller may give. */
    public static final int MIN_CHARS = 200;

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

    /**
     * The caller's {@code maxChars} argument. {@link ArgCheck} refuses one outside {@link #MIN_CHARS} to {@link #MAX_CHARS}
     * before the tool runs; the clamp only bounds the cap of that refusal itself.
     */
    public static int of(Args args) {
        return Math.max(MIN_CHARS, Math.min(MAX_CHARS, args.integer("maxChars", DEFAULT_CHARS)));
    }

    /**
     * Cut text at a line boundary and say how much was dropped and what to do about it. The result, the closing
     * {@code [truncated: ...]} line included, is never longer than {@code maxChars} (0 when negative), for every
     * {@code maxChars} (#487). When the closing line with its hint does not fit, a short {@code [N chars cut]} line
     * takes its place; when even that does not fit, the result is the plain start of the text. Never splits a
     * surrogate pair.
     */
    public static String truncate(String text, int maxChars, String hint) {
        int max = Math.max(0, maxChars);
        if (text.length() <= max) {
            return text;
        }
        // Room for the closing line: its fixed words, two numbers of up to ten digits each, and the hint.
        int keep = max - (MARK_CHARS + hint.length());
        if (keep >= 0) {
            int cut = cutAt(text, keep);
            return text.substring(0, cut) + (cut > 0 ? "\n" : "") + "[truncated: " + droppedLines(text, cut)
                    + " more lines, " + (text.length() - cut) + " chars. " + hint + "]";
        }
        keep = max - (BRIEF_CHARS + String.valueOf(text.length()).length());
        if (keep >= 0) {
            int cut = cutAt(text, keep);
            return text.substring(0, cut) + (cut > 0 ? "\n" : "") + "[" + (text.length() - cut) + " chars cut]";
        }
        return Limits.head(text, max);
    }

    /** Length of the short cut line without its number: {@code "\n[ chars cut]"}. */
    private static final int BRIEF_CHARS = "\n[ chars cut]".length();

    /** Where to cut {@code text} to keep at most {@code keep} characters: the last line break, else a plain cut. */
    private static int cutAt(String text, int keep) {
        int cut = text.lastIndexOf('\n', keep);
        return cut > 0 ? cut : Limits.head(text, keep).length(); // never half an emoji
    }

    private static int droppedLines(String text, int cut) {
        int dropped = 0;
        for (int i = cut; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                dropped++;
            }
        }
        return Math.max(1, dropped);
    }

    /** Length of the opening line of a cut tail without its hint: the words, ten-digit numbers, the line break. */
    private static final int TAIL_MARK_CHARS = "[truncated:  earlier lines,  chars. ]\n".length() + 20;

    /**
     * The end of a log tail (#611): like {@link #truncate}, but keeps the end of the text, the newest lines, and says
     * at the top how much earlier text was dropped: {@code [truncated: N earlier lines, M chars. hint]}. Starts at a
     * line boundary when one is in reach. The result is never longer than {@code maxChars} (0 when negative): when
     * the opening line with its hint does not fit, a short {@code [M chars cut]} line takes its place; when even that
     * does not fit, the result is the plain end of the text. Never splits a surrogate pair.
     */
    public static String tail(String text, int maxChars, String hint) {
        int max = Math.max(0, maxChars);
        if (text.length() <= max) {
            return text;
        }
        int keep = max - (TAIL_MARK_CHARS + hint.length());
        if (keep >= 0) {
            int from = startAt(text, keep);
            return "[truncated: " + earlierLines(text, from) + " earlier lines, " + from + " chars. " + hint + "]"
                    + (from < text.length() ? "\n" : "") + text.substring(from);
        }
        keep = max - (BRIEF_CHARS + String.valueOf(text.length()).length());
        if (keep >= 0) {
            int from = startAt(text, keep);
            return "[" + from + " chars cut]" + (from < text.length() ? "\n" : "") + text.substring(from);
        }
        return text.substring(plainStart(text, text.length() - max));
    }

    /** Where to start {@code text} to keep at most {@code keep} characters of its end: a line start, else a plain cut. */
    private static int startAt(String text, int keep) {
        int from = text.length() - keep;
        if (from > 0 && text.charAt(from - 1) == '\n') {
            return from;
        }
        int line = text.indexOf('\n', from);
        return line >= 0 && line + 1 < text.length() ? line + 1 : plainStart(text, from);
    }

    /** {@code from}, or one later when it would start inside a surrogate pair (never half an emoji). */
    private static int plainStart(String text, int from) {
        return from > 0 && from < text.length() && Character.isLowSurrogate(text.charAt(from))
                && Character.isHighSurrogate(text.charAt(from - 1)) ? from + 1 : from;
    }

    /** Lines before {@code from}, wholly or partly; at least 1. */
    private static int earlierLines(String text, int from) {
        int lines = text.charAt(from - 1) == '\n' ? 0 : 1;
        for (int i = 0; i < from; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return Math.max(1, lines);
    }

    /** What {@link #raise} adds below the largest cap: "Or raise maxChars (at most 24000)." */
    public static final String RAISE = "Or raise maxChars (at most " + MAX_CHARS + ").";

    /**
     * The hint of a cut of a result capped at {@code maxChars} (#614): {@code narrow}, the tool's own way to narrow
     * the call, first, then {@link #RAISE} while a larger {@code maxChars} is allowed. Every cut hint that names
     * maxChars comes from here, so none says to raise it past {@link #MAX_CHARS}, which {@link ArgCheck} refuses.
     */
    public static String raise(int maxChars, String narrow) {
        return maxChars < MAX_CHARS ? narrow + " " + RAISE : narrow;
    }

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
         * The result in at most {@code cap} characters, {@code last} (or nothing, when null) as its last line, for
         * every cap (#487). The rule (#499):
         *
         * <ol>
         *   <li>The fixed text and {@code last} always stay. When they do not fit, the parts are left out and the
         *       fixed text before {@code last} is cut with {@code hint}; when {@code last} alone is longer than the
         *       cap, the result is {@code last} cut with {@code hint}.
         *   <li>Parts are shown whole, smallest first (the earlier one on a tie), as many as fit together with the
         *       short summary line {@code [N of M outputs cut]} the others would need.
         *   <li>The other parts share what is left: when each would get at least {@link #MIN_SHARE}, every one is
         *       cut to its share ({@link #split}) and carries its own cut line. Otherwise one line before
         *       {@code last} counts every part not shown whole, with {@code hint} when that fits, else short; the
         *       room after it is cut into useful shares for as many of them as it holds (smallest first) and the rest
         *       show {@link #LEFT_OUT} when every left-over part's marker fits, else nothing.
         * </ol>
         *
         * <p>So a part is never left out or cut while the unused room would have shown it whole, give or take the
         * short summary line: the room it would need beyond what it shows is more than the unused room minus that
         * line. When even the short summary line for every part does not fit beside the fixed text, there is none.
         */
        public String text(int cap, String last, String hint) {
            String closing = last == null ? "" : pieces.isEmpty() ? last : "\n" + last;
            if (closing.length() >= cap) {
                return truncate(last == null ? "" : last, cap, hint);
            }
            int fixed = closing.length();
            List<Piece> parts = new ArrayList<>();
            for (Piece piece : pieces) {
                if (piece.fixed() != null) {
                    fixed += piece.fixed().length();
                } else {
                    parts.add(piece);
                }
            }
            StringBuilder body = new StringBuilder();
            if (fixed > cap) {
                pieces.stream().filter(p -> p.fixed() != null).forEach(p -> body.append(p.fixed()));
                return truncate(body.toString(), cap - closing.length(), hint) + closing;
            }
            int[] wants = new int[parts.size()];
            for (int i = 0; i < wants.length; i++) {
                wants[i] = parts.get(i).render().apply(Integer.MAX_VALUE).length();
            }
            Plan plan = Plan.of(cap - fixed, wants, hint);
            int next = 0;
            for (Piece piece : pieces) {
                if (piece.fixed() != null) {
                    body.append(piece.fixed());
                } else {
                    int share = plan.shares()[next++];
                    if (share == Plan.MARKER) {
                        body.append(LEFT_OUT);
                    } else if (share >= 0) {
                        body.append(truncate(piece.render().apply(share), share, hint));
                    }
                }
            }
            return body.append(plan.note()).append(closing).toString();
        }
    }

    /** A cut part with less room than this is left out rather than shown as little more than its own cut line. */
    public static final int MIN_SHARE = 80;

    /** What a left-out part shows. */
    public static final String LEFT_OUT = "[cut]";

    /**
     * How {@link Parts} spends {@code room} on its parts (the rule in {@link Parts#text}): {@code shares[i]}
     * characters for part i, {@link #MARKER} when it shows {@link #LEFT_OUT}, {@link #HIDDEN} when it shows nothing;
     * {@code note} is the summary line, or empty.
     */
    private record Plan(int[] shares, String note) {
        static final int MARKER = -1;
        static final int HIDDEN = -2;

        static Plan of(int room, int[] wants, String hint) {
            int n = wants.length;
            Integer[] order = new Integer[n];
            Arrays.setAll(order, i -> i);
            Arrays.sort(order, (a, b) -> wants[a] != wants[b] ? Integer.compare(wants[a], wants[b]) : a - b);
            long[] sum = new long[n + 1]; // sum[k]: the k smallest parts whole
            for (int k = 0; k < n; k++) {
                sum[k + 1] = sum[k] + wants[order[k]];
            }
            boolean counted = note(n, n, null).length() <= room;
            int k = n;
            while (k > 0 && sum[k] + (counted && k < n ? note(n - k, n, null).length() : 0) > room) {
                k--;
            }
            int[] shares = new int[n];
            for (int i = 0; i < k; i++) {
                shares[order[i]] = wants[order[i]];
            }
            int left = n - k;
            int free = (int) (room - sum[k]);
            String note = "";
            int cut = left;
            boolean markers = false;
            if (left > 0 && (long) left * MIN_SHARE > free) {
                String full = note(left, n, hint);
                note = !counted ? "" : full.length() <= free ? full : note(left, n, null);
                free -= note.length();
                markers = LEFT_OUT.length() * left <= free;
                cut = markers ? (free - LEFT_OUT.length() * left) / (MIN_SHARE - LEFT_OUT.length())
                        : free / MIN_SHARE;
                cut = Math.max(0, Math.min(left, cut));
                free -= markers ? LEFT_OUT.length() * (left - cut) : 0;
            }
            int[] cutWants = new int[cut];
            for (int i = 0; i < cut; i++) {
                cutWants[i] = wants[order[k + i]];
            }
            int[] cutShares = split(free, cutWants);
            for (int i = 0; i < left; i++) {
                shares[order[k + i]] = i < cut ? cutShares[i] : markers ? MARKER : HIDDEN;
            }
            return new Plan(shares, note);
        }

        /** The line counting the parts not shown whole, with {@code hint} unless it is null or empty. */
        static String note(int notWhole, int of, String hint) {
            return "\n[" + notWhole + " of " + of + " outputs cut"
                    + (hint == null || hint.isEmpty() ? "]" : " to fit. " + hint + "]");
        }
    }

    public static int tokens(int chars) {
        return (chars + 3) / 4;
    }
}
