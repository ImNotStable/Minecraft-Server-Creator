package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Cutting and cleaning captured text (command output, what a test player saw, a value quoted in a message) for the
 * bench's records and messages: one place, so no cut can split a character (#462). A Java string counts UTF-16 units,
 * and an emoji or other character outside the Basic Multilingual Plane takes two (a surrogate pair); a cut between the
 * two leaves a lone surrogate, which no UTF-8 writer can encode. Every cut here moves to the nearest whole character
 * inside the limit, and {@link #writable} turns any lone surrogate a text still holds into U+FFFD.
 *
 * <p>msc-protocol's {@code Limits.cut} keeps a pair-safe head too, but only a head, ending in its own size marker and
 * with a 64-char minimum; the bench needs tails (the end of a build log is where the error is) and short limits, and
 * msc-core and msc-process have no shared cut.
 *
 * <p>{@link #read} and {@link #decode} are the only way the bench reads text that someone else wrote: a workspace file
 * the agent under test wrote, a child process's output (a check command, a player check's server, the agent's
 * stdout), a zip entry, msc-core's tool log. Such text is decoded leniently as UTF-8: a byte that is not UTF-8 (a
 * Cp1252 {@code é} from a Windows tool, a character cut by a kill) becomes U+FFFD instead of an exception, and a
 * byte-order mark (Notepad's or Windows PowerShell's UTF-8) at the start of the text or of any line is dropped, so a
 * correct answer matches the same with or without one. Line starts count too because a child's output is often
 * several writers in a row: the JVM's {@code Picked up JAVA_TOOL_OPTIONS} line, then a tool printing a file that
 * starts with a mark, or two such files joined; a U+FEFF there is never part of the text.
 *
 * <p>The bench's own files stay strict on purpose and are not read here: {@code task.json}, {@code prompt.md} and the
 * preambles, {@code bench.json}, the machine's {@code eula.json} and {@code results.jsonl} are refused with a clear
 * message when they are not UTF-8, since a garbled task, config or record must not run quietly.
 */
public final class Text {

    /** U+FEFF, the byte-order mark a Windows editor or PowerShell puts at the start of a UTF-8 file. */
    private static final char BOM = '\uFEFF';

    /** A byte-order mark at the start of the text or right after a line break. */
    private static final Pattern LINE_START_BOM = Pattern.compile("^\uFEFF", Pattern.MULTILINE);

    /** What stands for a character that cannot be written: U+FFFD, the Unicode replacement character. */
    public static final char REPLACEMENT = '\uFFFD';

    private Text() {
    }

    /** The first at most {@code max} chars of {@code text}, one fewer when the cut would split a surrogate pair. */
    public static String head(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** The last at most {@code max} chars of {@code text}, one fewer when the cut would split a surrogate pair. */
    public static String tail(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int start = text.length() - Math.max(0, max);
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) {
            start++;
        }
        return text.substring(start);
    }

    /** {@code text} cut to {@code max} chars and {@code "..."} when longer, the marker within the limit. */
    public static String headWithEllipsis(String text, int max) {
        return text.length() <= max ? text : head(text, max - 3) + "...";
    }

    /** {@code "..."} and the end of {@code text} when longer than {@code max} chars, the marker within the limit. */
    public static String tailWithEllipsis(String text, int max) {
        return text.length() <= max ? text : "..." + tail(text, max - 3);
    }

    /**
     * {@code text} as one bounded line: every run of line breaks, tabs and other control characters becomes one space,
     * the ends are stripped, and text over {@code max} chars is cut with a visible {@code "..."} within the limit.
     */
    public static String singleLine(String text, int max) {
        return headWithEllipsis(text.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029\\s]+", " ").strip(), max);
    }

    /**
     * {@code bytes} as UTF-8, every malformed byte as {@link #REPLACEMENT}, and a byte-order mark at the start of the
     * text or of a line dropped (see the class comment).
     */
    public static String decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.indexOf(BOM) < 0 ? text : LINE_START_BOM.matcher(text).replaceAll("");
    }

    /** The text of {@code file}, decoded as {@link #decode} does; only an I/O error throws, never its bytes. */
    public static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /** Whether {@code text} holds a surrogate that is not part of a pair, which UTF-8 cannot encode. */
    public static boolean hasLoneSurrogate(String text) {
        return loneSurrogate(text, 0) >= 0;
    }

    /** {@code text} with every lone surrogate replaced by {@link #REPLACEMENT}; the same string when it holds none. */
    public static String writable(String text) {
        int lone = loneSurrogate(text, 0);
        if (lone < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        while (lone >= 0) {
            out.setCharAt(lone, REPLACEMENT);
            lone = loneSurrogate(text, lone + 1);
        }
        return out.toString();
    }

    /** The index of the first lone surrogate at or after {@code from}, or -1. */
    private static int loneSurrogate(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return i;
            }
        }
        return -1;
    }
}
