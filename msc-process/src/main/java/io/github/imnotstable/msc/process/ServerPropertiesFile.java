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
    public static String decode(byte[] bytes) {
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
    public static Map<String, String> parse(String text) throws IOException {
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
     * one), and a missing key is appended. Comments, other lines and their order stay. Creates the file and its folder
     * when missing. The file is written as UTF-8 with {@code \n} line ends on every OS.
     */
    public static void set(Path file, Map<String, String> values) throws IOException {
        for (Map.Entry<String, String> e : values.entrySet()) {
            line(e.getKey(), e.getValue()); // refuses a bad key or value before anything is read or written
        }
        List<String> out = new ArrayList<>();
        Map<String, String> left = new LinkedHashMap<>(values);
        if (Files.exists(file)) {
            for (List<String> logical : logicalLines(decode(Files.readAllBytes(file)))) {
                String key = key(logical);
                if (key != null && values.containsKey(key)) {
                    if (left.containsKey(key)) {
                        out.add(line(key, left.remove(key)));
                    }
                } else {
                    out.addAll(logical);
                }
            }
        }
        left.forEach((k, v) -> out.add(line(k, v)));
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
    }

    /** One {@code key=value} line, escaped so {@link Properties#load} reads back exactly {@code key} and {@code value}. */
    public static String line(String key, String value) {
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
     * The text's lines grouped as {@link Properties#load} groups them: a line that is not a comment and ends in an odd
     * number of backslashes continues on the next one. Blank and comment lines are groups of their own.
     */
    static List<List<String>> logicalLines(String text) {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = null;
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (current == null) {
                current = new ArrayList<>();
                groups.add(current);
                current.add(line);
                if (isComment(line) || !continues(line)) {
                    current = null;
                }
            } else {
                current.add(line);
                if (!continues(line)) {
                    current = null;
                }
            }
        }
        // split keeps the empty text after a final line end: drop it, so a rewrite does not grow a blank line.
        if (!groups.isEmpty() && groups.getLast().equals(List.of(""))) {
            groups.removeLast();
        }
        return groups;
    }

    private static boolean isComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t' && c != '\f') {
                return c == '#' || c == '!';
            }
        }
        return false;
    }

    private static boolean continues(String line) {
        int slashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    /** The key a logical line defines, or null for a blank or comment line or one the server cannot read. */
    private static String key(List<String> logical) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(String.join("\n", logical)));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return p.size() == 1 ? p.stringPropertyNames().iterator().next() : null;
    }
}
