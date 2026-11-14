package io.github.imnotstable.msc.process;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The one reader and writer of a Minecraft server's {@code server.properties} in this repository (ADR 0033). It reads
 * the file as the server does and writes it so the server reads back exactly what was set.
 *
 * <p>The vanilla server (26.3, {@code net.minecraft.server.dedicated.Settings}) loads the file with
 * {@link Properties#load(java.io.Reader)} over a strict UTF-8 decoder and, when the bytes are not valid UTF-8, again as
 * ISO-8859-1. It stores it with {@link Properties#store(java.io.Writer, String)} over a UTF-8 writer, so non-ASCII text
 * is raw UTF-8 on disk once the server has started. {@link #read} does the same. {@link #set} edits the file line by
 * line, keeping comments, other keys and their order, and escapes what it writes as {@link Properties#store} does,
 * with non-ASCII as unicode escapes (backslash, u, four hex digits) so the line means the same under either charset.
 */
public final class ServerPropertiesFile {
    /** The file's name in a server directory. */
    public static final String NAME = "server.properties";

    private ServerPropertiesFile() {
    }

    /**
     * The keys and values of {@code file} as the server reads them, or an empty map when it does not exist. Throws
     * when it cannot be read or holds a malformed unicode escape.
     */
    public static Map<String, String> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        return parse(decode(Files.readAllBytes(file)));
    }

    /** The text of the file's bytes: UTF-8 when they are valid UTF-8, ISO-8859-1 otherwise, as the server decides. */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** The keys and values of decoded file text, by {@link Properties#load(java.io.Reader)}'s rules. */
    static Map<String, String> parse(String text) throws IOException {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IllegalArgumentException e) {
            throw new IOException("server.properties is malformed: " + e.getMessage(), e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            out.put(key, p.getProperty(key));
        }
        return out;
    }

    /**
     * Sets each key: its first line is replaced, later lines of the same key are dropped (the server takes the last
     * one), and a missing key is appended. Comments, other lines and their order stay, and every other key keeps the
     * value the server reads for it: the file is split into lines exactly as {@link Properties#load} splits it
     * ({@link #entries}), and the text is checked to read back that way before it is written (#407). Creates the file
     * and its folder when missing. The file is written as UTF-8, its line ends kept and added lines ended in
     * {@code \n}, whole or not at all ({@link AtomicFile}), keeping its permissions.
     */
    public static void set(Path file, Map<String, String> values) throws IOException {
        for (Map.Entry<String, String> e : values.entrySet()) {
            line(e.getKey(), e.getValue()); // refuses a bad key or value before anything is read or written
        }
        Path target = file.toAbsolutePath();
        String before = Files.exists(target) ? decode(Files.readAllBytes(target)) : "";
        String after = edit(before, values);
        Map<String, String> expected;
        try {
            expected = new LinkedHashMap<>(parse(before));
        } catch (IOException malformed) {
            // A bad unicode escape: the server cannot read the file either way, and the lines set here are right.
            expected = null;
        }
        if (expected != null) {
            expected.putAll(values);
            if (!parse(after).equals(expected)) {
                throw new IOException("server.properties was not changed: the edit would not read back as written");
            }
        }
        AtomicFile.write(target, to -> Files.writeString(to, after, StandardCharsets.UTF_8));
    }

    /**
     * The text of {@link #set}: {@code text} with {@code values} set. Line ends stay as they were (turning a
     * {@code \r\n} that ends the file into {@code \n} can change what a dangling backslash before it means); lines
     * added end in {@code \n}.
     */
    static String edit(String in, Map<String, String> values) {
        StringBuilder out = new StringBuilder(in.length() + 64);
        Map<String, String> left = new LinkedHashMap<>(values);
        int at = 0;
        for (Entry e : entries(in)) {
            String[] kv = keyValue(e.content());
            String replacement;
            if (kv != null && values.containsKey(kv[0])) {
                replacement = left.containsKey(kv[0]) ? line(kv[0], left.remove(kv[0])) : null;
            } else if (e.endsAtEof() && kv != null) {
                // A line the end of the file closed, perhaps inside a continuation or after a dangling backslash:
                // written out again, so a line end or line added after it cannot run into it.
                replacement = escape(kv[0], true) + "=" + escape(kv[1], false);
            } else {
                continue;
            }
            if (replacement != null) {
                out.append(in, at, e.start()).append(replacement);
                at = e.end();
            } else {
                // A later line of a key set here: dropped with its indent and its line end.
                int start = e.start();
                while (start > at && isBlank(in.charAt(start - 1))) {
                    start--;
                }
                boolean wholeLine = start == 0 || isLineEnd(in.charAt(start - 1));
                out.append(in, at, wholeLine ? start : e.start());
                at = e.end();
                if (wholeLine && !e.endsAtEof()) {
                    at += in.startsWith("\r\n", at) ? 2 : 1;
                }
            }
        }
        out.append(in, at, in.length());
        if (!out.isEmpty() && !isLineEnd(out.charAt(out.length() - 1))) {
            out.append('\n');
        }
        left.forEach((k, v) -> out.append(line(k, v)).append('\n'));
        return out.toString();
    }

    /** One {@code key=value} line, escaped so {@link Properties#load} reads back exactly {@code key} and {@code value}. */
    static String line(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("server.properties key and value must not be null (" + key + ")");
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("server.properties key must not be empty");
        }
        return escape(key, true) + "=" + escape(value, false);
    }

    /** As {@code Properties.saveConvert} with unicode escapes: what {@link Properties#store(java.io.OutputStream, String)} writes. */
    static String escape(String text, boolean key) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 61 && c < 127) {
                sb.append(c == '\\' ? "\\\\" : String.valueOf(c));
                continue;
            }
            switch (c) {
                case ' ' -> sb.append(i == 0 || key ? "\\ " : " ");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\f' -> sb.append("\\f");
                case '=', ':', '#', '!' -> sb.append('\\').append(c);
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append("\\u").append(HexFormat.of().withUpperCase().toHexDigits(c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * One logical line as {@link Properties#load} reads it: its text runs from {@code start} (its first character
     * that is not skipped) to {@code end} (before the line end that closes it, or the end of the text), and
     * {@code content} is what {@code load} parses into a key and value (continuations joined).
     */
    record Entry(int start, int end, boolean endsAtEof, String content) {
    }

    /**
     * The logical lines of {@code text}, found as {@code Properties.LineReader.readLine} of JDK 25 finds them, step
     * for step: leading blanks and blank lines are skipped, {@code #} or {@code !} where a line would begin (also
     * after a lone continuation backslash) is a comment to the line end, a line ending in an odd number of
     * backslashes continues on the next one without its leading blanks, and the end of the text closes a line, its
     * dangling backslash dropped.
     */
    static List<Entry> entries(String text) {
        List<Entry> out = new ArrayList<>();
        int n = text.length();
        int off = 0;
        while (true) {
            StringBuilder line = new StringBuilder();
            int start = -1;
            boolean skipWhiteSpace = true;
            boolean appendedLineBegin = false;
            boolean precedingBackslash = false;
            while (true) {
                if (off >= n) {
                    if (!line.isEmpty()) {
                        if (precedingBackslash) {
                            line.setLength(line.length() - 1);
                        }
                        out.add(new Entry(start, n, true, line.toString()));
                    }
                    return out;
                }
                char c = text.charAt(off++);
                if (skipWhiteSpace) {
                    if (isBlank(c) || (!appendedLineBegin && (c == '\r' || c == '\n'))) {
                        continue;
                    }
                    skipWhiteSpace = false;
                    appendedLineBegin = false;
                }
                if (line.isEmpty() && (c == '#' || c == '!')) {
                    while (off < n && text.charAt(off) != '\r' && text.charAt(off) != '\n') {
                        off++;
                    }
                    if (off >= n) {
                        return out;
                    }
                    off++;
                    skipWhiteSpace = true;
                    continue;
                }
                if (c != '\n' && c != '\r') {
                    if (start < 0) {
                        start = off - 1;
                    }
                    line.append(c);
                    precedingBackslash = c == '\\' && !precedingBackslash;
                    continue;
                }
                if (line.isEmpty()) {
                    skipWhiteSpace = true;
                    continue;
                }
                if (off >= n && precedingBackslash) {
                    // A dangling backslash on the last line: the end of the text closes the line, without it.
                    line.setLength(line.length() - 1);
                    out.add(new Entry(start, n, true, line.toString()));
                    return out;
                }
                if (!precedingBackslash) {
                    out.add(new Entry(start, off - 1, false, line.toString()));
                    break;
                }
                line.setLength(line.length() - 1);
                skipWhiteSpace = true;
                appendedLineBegin = true;
                precedingBackslash = false;
                if (c == '\r' && text.charAt(off) == '\n') {
                    off++;
                }
            }
        }
    }

    private static boolean isLineEnd(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    /** The key and value a logical line's content defines, or null when the server cannot read it. */
    private static String[] keyValue(String content) {
        if (content.isEmpty()) {
            return new String[] {"", ""};
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(content));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        if (p.size() != 1) {
            return null;
        }
        String key = p.stringPropertyNames().iterator().next();
        return new String[] {key, p.getProperty(key)};
    }
}
