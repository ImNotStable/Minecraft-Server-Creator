package io.github.imnotstable.msc.bench.check;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fills {@code {name}} placeholders. Unknown names are left as they are. Both fills make one pass over the text as
 * written and never look at a value they put in again, so a value that holds {@code {name}} stays as it is (#588).
 */
public final class Placeholders {

    private Placeholders() {
    }

    public static String fill(String text, Map<String, String> values) {
        if (values.isEmpty() || text.indexOf('{') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int end = braceEnd(text, i, text.length());
            String value = end < 0 ? null : values.get(text.substring(i + 1, end - 1));
            if (value != null) {
                out.append(value);
                i = end;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    public static List<String> fill(List<String> texts, Map<String, String> values) {
        return texts.stream().map(t -> fill(t, values)).toList();
    }

    /**
     * The regex {@code regex} with its placeholders filled so each value matches literally (#578). Every {@code {name}}
     * of the regex's own text becomes a quoted literal: the value of a known name, the text {@code {name}} itself for
     * an unknown one (a repetition takes digits, so it is no regex syntax). Escape sequences are copied as written,
     * each with its argument ({@link #escapeEnd}); inside {@code \Q...\E} the text is literal already, and a known name
     * there is filled as a literal too (#588).
     */
    public static String fillRegex(String regex, Map<String, String> values) {
        StringBuilder out = new StringBuilder(regex.length() + 16);
        int i = 0;
        int n = regex.length();
        while (i < n) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = regex.charAt(i + 1);
                if (next == 'Q') {
                    i = quotedSection(regex, i + 2, values, out);
                } else {
                    int end = escapeEnd(regex, i);
                    out.append(regex, i, end);
                    i = end;
                }
                continue;
            }
            int end = braceEnd(regex, i, n);
            String value = end < 0 ? null : values.get(regex.substring(i + 1, end - 1));
            if (value == null && nameEnd(regex, i) >= 0) {
                end = nameEnd(regex, i);
                value = regex.substring(i, end);
            }
            if (value != null) {
                out.append(Pattern.quote(value));
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * The index after the escape sequence whose backslash is at {@code i} (any but {@code \Q}), with its argument, so
     * no placeholder is read inside it (#595). {@code \c} takes the next character, a brace included (the control
     * character {@code ;}), but not a backslash. After a letter, a {@code {...}} belongs to the escape: an argument ({@code \p{L}},
     * {@code \P{Lu}}, {@code \x{FF}}, {@code \N{...}}, {@code \b{g}}, and any escape Java adds later, since it
     * reserves every other letter for escapes) or a repetition ({@code \d{2}}), never a placeholder. After anything
     * else ({@code \\}, an escaped brace, {@code \.}, a digit) the escape is those two characters, and a {@code {name}} after
     * it is filled.
     */
    private static int escapeEnd(String regex, int i) {
        int n = regex.length();
        char next = regex.charAt(i + 1);
        if (next == 'c') {
            // A backslash after \c starts an escape of its own, as Java's \Q...\E pass reads it.
            return i + 2 < n && regex.charAt(i + 2) != '\\' ? i + 2 + Character.charCount(regex.codePointAt(i + 2))
                    : i + 2;
        }
        if (asciiLetter(next) && i + 2 < n && regex.charAt(i + 2) == '{') {
            int close = regex.indexOf('}', i + 3);
            return close < 0 ? n : close + 1;
        }
        return i + 2;
    }

    private static boolean asciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * Copies a {@code \Q...\E} section whose text starts at {@code from} (after {@code \Q}), filling known names as
     * literals by closing the section around them; returns the index after its {@code \E}, or the end.
     */
    private static int quotedSection(String regex, int from, Map<String, String> values, StringBuilder out) {
        int close = regex.indexOf("\\E", from);
        int stop = close < 0 ? regex.length() : close;
        out.append("\\Q");
        int i = from;
        while (i < stop) {
            int end = braceEnd(regex, i, stop);
            String value = end < 0 ? null : values.get(regex.substring(i + 1, end - 1));
            if (value != null) {
                out.append("\\E").append(Pattern.quote(value)).append("\\Q");
                i = end;
            } else {
                out.append(regex.charAt(i++));
            }
        }
        out.append("\\E");
        return close < 0 ? stop : close + 2;
    }

    /** The index after the {@code {...}} without a nested brace that starts at {@code i} and ends by {@code stop}, or -1. */
    private static int braceEnd(String text, int i, int stop) {
        if (text.charAt(i) != '{') {
            return -1;
        }
        for (int j = i + 1; j < stop; j++) {
            char c = text.charAt(j);
            if (c == '}') {
                return j + 1;
            }
            if (c == '{') {
                return -1;
            }
        }
        return -1;
    }

    /** The index after the {@code {name}} (a placeholder's syntax) that starts at {@code i}, or -1 when none does. */
    private static int nameEnd(String text, int i) {
        if (text.charAt(i) != '{' || i + 1 >= text.length() || !nameStart(text.charAt(i + 1))) {
            return -1;
        }
        for (int j = i + 2; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '}') {
                return j + 1;
            }
            if (!namePart(c)) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean nameStart(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean namePart(char c) {
        return nameStart(c) || (c >= '0' && c <= '9') || c == '.' || c == '-';
    }
}
