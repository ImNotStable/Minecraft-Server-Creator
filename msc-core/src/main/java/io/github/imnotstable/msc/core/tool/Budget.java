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
         * The result in at most {@code cap} characters, {@code last} (or nothing, when null) as its last line, for
         * every cap (#487).
         *
         * <p>When the fixed text and {@code last} fit, every fixed piece is kept whole and the parts share what is
         * left ({@link #split}). A part whose share is below {@link #MIN_SHARE} and below what it wants would show
         * little more than its own cut line, so the largest such part (the later one on a tie) is left out, shown as
         * {@code [cut]}, and the others share again until every part shown fits whole or has a useful share. One line
         * before {@code last} then says how many outputs were left out, with {@code hint}; without the hint, or
         * without that line and the markers, when the room left is too small for them. Every part is given
         * room for at most its share; a part that renders longer is cut to it.
         *
         * <p>When the fixed text and {@code last} do not fit, the parts are left out and the fixed text before
         * {@code last} is cut with {@code hint}; when {@code last} alone is longer than the cap, the result is
         * {@code last} cut with {@code hint}.
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
                } else if (plan.shares()[next] < 0) {
                    body.append(plan.marker());
                    next++;
                } else {
                    int share = plan.shares()[next++];
                    body.append(truncate(piece.render().apply(share), share, hint));
                }
            }
            String text = body.append(plan.note()).toString();
            // Never fires: kept so a wrong sum can only cut, not overflow.
            return (text.length() + closing.length() <= cap ? text : truncate(text, cap - closing.length(), hint))
                    + closing;
        }
    }

    /** A cut part with less room than this is left out rather than shown as little more than its own cut line. */
    public static final int MIN_SHARE = 80;

    /** What a left-out part shows. */
    public static final String LEFT_OUT = "[cut]";

    /**
     * How {@link Parts} spends {@code room} on its parts: {@code shares[i]} characters for part i, or -1 when it is
     * left out and shows {@code marker}; {@code note} is the line saying how many were left out.
     */
    private record Plan(int[] shares, String marker, String note) {
        static Plan of(int room, int[] wants, String hint) {
            int[] shares = split(room, wants);
            int[] active = wants.clone();
            int dropped = 0;
            // The line that counts left-out parts, sized for the largest count: with the hint, short, or none.
            String full = note(wants.length, wants.length, hint);
            String brief = note(wants.length, wants.length, null);
            boolean withHint = full.length() <= room;
            int noteChars = withHint ? full.length() : brief.length() <= room ? brief.length() : 0;
            boolean markers = true;
            while (true) {
                int worst = -1;
                for (int i = 0; i < wants.length; i++) {
                    if (active[i] > 0 && shares[i] < wants[i] && shares[i] < MIN_SHARE
                            && (worst < 0 || wants[i] >= wants[worst])) {
                        worst = i;
                    }
                }
                if (worst < 0) {
                    break;
                }
                active[worst] = 0;
                dropped++;
                int left = room - noteChars - LEFT_OUT.length() * dropped;
                markers = left >= 0;
                shares = split(markers ? left : room - noteChars, active);
            }
            if (dropped == 0) {
                return new Plan(shares, "", "");
            }
            for (int i = 0; i < wants.length; i++) {
                if (active[i] == 0 && wants[i] > 0) {
                    shares[i] = -1;
                }
            }
            String note = noteChars == 0 ? "" : note(dropped, wants.length, withHint ? hint : null);
            return new Plan(shares, markers ? LEFT_OUT : "", note);
        }

        /** The line saying how many outputs were left out, with {@code hint} unless it is null. */
        private static String note(int dropped, int of, String hint) {
            return "\n[" + dropped + " of " + of + " outputs cut" + (hint == null ? "]" : " to fit. " + hint + "]");
        }
    }

    public static int tokens(int chars) {
        return (chars + 3) / 4;
    }
}
