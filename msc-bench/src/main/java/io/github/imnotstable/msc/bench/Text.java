package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
 * <p>The bench's own files stay strict on purpose: {@code task.json}, {@code prompt.md} and the preambles,
 * {@code bench.json} and a mode's MCP config template are read by {@link #readOwn}, which refuses one that is not
 * UTF-8 naming the file and the first bad byte (#555), since a garbled task or config must not run quietly. The
 * machine's {@code eula.json} is read by {@link #readOwn} too, but one that is not UTF-8 counts as no acceptance,
 * not a refusal, as in msc-core's {@code MachineEula.read} ({@code MachineEula}, #571). {@code results.jsonl} has its
 * own strict reader, which also takes the UTF-16 a Windows join writes ({@code ResultsFile}).
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

    /**
     * {@code "..."} and the end of {@code text} when longer than {@code max} chars, the marker within the limit (a
     * limit of 3 or less has no room for it and keeps the plain tail).
     */
    public static String tailWithEllipsis(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return max <= 3 ? tail(text, max) : "..." + tail(text, max - 3);
    }

    /** What {@link #cut} puts where it took text out. */
    public static final String CUT_MARK = " ... ";

    /**
     * The one cut for a detail that says why something failed (#535): {@code text} unchanged when at most {@code max}
     * chars, otherwise its head and its tail with {@link #CUT_MARK} between them, {@code max} chars at most. The tail
     * gets three quarters of the room because the end of a log is what names the failure, and the head says what was
     * running. Neither cut splits a surrogate pair. A limit below the mark's length keeps only a tail.
     */
    public static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int keep = max - CUT_MARK.length();
        if (keep <= 0) {
            return tail(text, max);
        }
        int head = keep / 4;
        return head(text, head) + CUT_MARK + tail(text, keep - head);
    }

    /**
     * {@code text} as one bounded line a reader is shown: the ends are stripped, text over {@code max} characters is
     * cut like {@link #cut}, keeping its head and its tail, and every character that would split the line or hide
     * itself is escaped by {@link Quoted#visible(String, int)} (#677), a line break as backslash n, a zero-width space
     * or a right-to-left override as a unicode escape. An escape counts as one character, so a text {@link #cut}
     * already fitted to {@code max} is not cut again.
     */
    public static String singleLine(String text, int max) {
        return Quoted.visible(text.strip(), max);
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

    /**
     * The text of one of the bench's own files ({@code task.json}, {@code bench.json}...), which must be UTF-8: a file
     * that is not is refused with an {@link IOException} naming it and the offset of its first bad byte, never the bare
     * {@code Input length = 1} of {@link Files#readString} (#555).
     */
    public static String readOwn(Path file) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(file));
        try {
            CharBuffer text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(in);
            return text.toString();
        } catch (CharacterCodingException e) {
            throw new IOException(file + ": the byte at offset " + in.position() + " is not UTF-8 text; save the file "
                    + "as UTF-8", e);
        }
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
