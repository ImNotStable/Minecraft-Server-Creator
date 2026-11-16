package io.github.imnotstable.msc.bench.report;

import io.github.imnotstable.msc.bench.Quoted;

/**
 * The one escape for text {@code report.md} takes from a record or a task (#678): a task id, a category, a mode, a
 * model, a tool name or a failure reason, in a table cell, a list item or a sentence.
 *
 * <p>The text is first shown as run text is everywhere ({@link Quoted#visible(String)}, #677): one line, with a line
 * break, a control character, a zero-width space, a right-to-left override or a bidi isolate escaped, so a row cannot
 * split and nothing in it is invisible. Then every character Markdown could read as structure is backslash-escaped:
 * the backslash itself, {@code |} (a new table cell), {@code `} (code), {@code * ~} and {@code _} but between two
 * letters or digits (emphasis), {@code [ ] !}
 * (links and images), {@code < >} (HTML, autolinks, quotes), {@code &} (an entity such as {@code &amp;} or
 * {@code &#x202E;}, which would be decoded instead of shown), {@code #} (a heading) and {@code $} (math). A
 * {@code - + =} or a number and {@code .} or {@code )} at the start, which would begin a list, is escaped too. Every
 * escape is a backslash before ASCII punctuation, which CommonMark shows as the character itself, so the reader sees
 * exactly the visible text.
 *
 * <p>That holds only outside a link: GitHub's Markdown (cmark-gfm's autolink extension) and markdown-it's linkify
 * turn a bare {@code https://...} or {@code www....} into a link straight from the source, keeping its backslashes
 * (#685). So no such link may start: the {@code :} of every {@code ://} and the {@code .} of every {@code www.} are
 * escaped too, and the URL is shown as plain text, exactly as written.
 */
public final class Markdown {

    /** The characters escaped wherever they stand; {@code _} is escaped unless inside a word. */
    private static final String SPECIAL = "\\`*~[]!<>|&#$";

    private Markdown() {
    }

    /** {@code text} as report.md shows it, in a table cell or inline; null is empty. */
    public static String text(String text) {
        String shown = Quoted.visible(text == null ? "" : text.strip());
        StringBuilder out = new StringBuilder(shown.length() + 8);
        for (int i = 0; i < shown.length(); i++) {
            char c = shown.charAt(i);
            if (SPECIAL.indexOf(c) >= 0 || c == '_' && !insideWord(shown, i) || startsLink(shown, i)) {
                out.append('\\');
            }
            out.append(c);
        }
        return escapeListStart(out).toString();
    }

    /**
     * Whether the {@code _} at {@code i} stands between two letters or digits, as in {@code world_render}: there no
     * Markdown reads it as emphasis, so it is left as it is and a tool name reads the same in the file.
     */
    private static boolean insideWord(String text, int i) {
        return i > 0 && i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i - 1))
                && Character.isLetterOrDigit(text.charAt(i + 1));
    }

    /**
     * Whether the character at {@code i} is the {@code :} of a {@code ://} or the {@code .} of a {@code www.}, where
     * an autolink would start; escaped, it starts none (#685).
     */
    private static boolean startsLink(String text, int i) {
        return text.charAt(i) == ':' && text.startsWith("//", i + 1)
                || text.charAt(i) == '.' && i >= 3 && text.regionMatches(true, i - 3, "www", 0, 3);
    }

    /** {@code out} with the marker escaped when it starts like a list item or a setext underline. */
    private static StringBuilder escapeListStart(StringBuilder out) {
        if (out.isEmpty()) {
            return out;
        }
        char first = out.charAt(0);
        if (first == '-' || first == '+' || first == '=') {
            return out.insert(0, '\\');
        }
        int digits = 0;
        while (digits < out.length() && out.charAt(digits) >= '0' && out.charAt(digits) <= '9') {
            digits++;
        }
        if (digits > 0 && digits < out.length() && (out.charAt(digits) == '.' || out.charAt(digits) == ')')) {
            out.insert(digits, '\\');
        }
        return out;
    }
}
