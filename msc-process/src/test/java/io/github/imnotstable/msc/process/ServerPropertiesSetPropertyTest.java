package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ServerPropertiesFile#set} on random files built from the hard pieces of the format (#407): whatever the file
 * holds, the server then reads every set key with its set value and every other key with the value it had before.
 * Comments stay too. Seeded, so a failure names a file that fails again.
 */
class ServerPropertiesSetPropertyTest {
    private static final long SEED = 0x5E7_407L;
    private static final int FILES = 3000;

    /** Keys as written on disk: plain, escaped separators and spaces, unicode escapes, non-ASCII, empty. */
    private static final List<String> RAW_KEYS = List.of("motd", "gamemode", "difficulty", "rcon.password",
            "rcon.port", "a", "b", "a\\=b", "a\\:b", "a\\ b", "\\u0061", "k\\\\", "\u00e4", "\u5bc6", "\\#k", "\\!k", "");
    /** Keys the test sets, as {@code set} receives them; several are also in {@link #RAW_KEYS} in escaped form. */
    private static final List<String> SET_KEYS = List.of("motd", "gamemode", "difficulty", "rcon.password",
            "rcon.port", "a", "b", "a=b", "a:b", "a b", "k\\", "\u00e4", "\u5bc6", "#k", "!k", "new.key");
    private static final List<String> SEPARATORS = List.of("=", ":", " ", " = ", "\t", "  :  ", "=\\", "");
    private static final List<String> VALUE_PIECES = List.of("x", "Hello", " ", "\\\\", "\\", "\\u00e9", "\u00e9",
            "\u20ac", "\uD83D\uDE00", "#", "!", "=", ":", "C:\\\\games", "\\t", "\\n", "\\ ", "\u00df");
    private static final List<String> SET_VALUES = List.of("", "creative", "peaceful", "C:\\games\\", "\\",
            " lead", "trail\\", "p\u00e4ss", "\u5bc6\u7801", "a=b:c", "#x", "line\nbreak", "\r", "\\u0041");
    private static final List<String> LINE_ENDS = List.of("\n", "\r\n", "\r");
    /** A comment's text starts with a numbered mark, so the test can tell whether it is still in the file. */
    private static final Pattern MARK = Pattern.compile("kept(\\d+)-");

    @TempDir
    Path dir;

    @Test
    void theServerReadsEverySetValueAndEveryOtherValueUnchanged() throws IOException {
        Random random = new Random(SEED);
        Path file = dir.resolve(ServerPropertiesFile.NAME);
        for (int i = 0; i < FILES; i++) {
            boolean latin1 = random.nextInt(4) == 0;
            String text = file(random, latin1);
            byte[] bytes = text.getBytes(latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (int n = 1 + random.nextInt(3); n > 0; n--) {
                values.put(pick(random, SET_KEYS), pick(random, SET_VALUES));
            }
            Files.write(file, bytes);
            Properties before = ServerReads.load(bytes);
            List<String> comments = comments(ServerPropertiesFile.decode(bytes));
            String which = "file " + i + " (" + (latin1 ? "ISO-8859-1" : "UTF-8") + ") " + show(text) + ", set " + values;
            try {
                ServerPropertiesFile.set(file, values);
            } catch (IOException e) {
                throw new AssertionError(which + ": " + e.getMessage(), e);
            }
            byte[] written = Files.readAllBytes(file);

            Map<String, String> expected = new HashMap<>();
            before.stringPropertyNames().forEach(k -> expected.put(k, before.getProperty(k)));
            expected.putAll(values);
            Properties after = ServerReads.load(written);
            Map<String, String> actual = new HashMap<>();
            after.stringPropertyNames().forEach(k -> actual.put(k, after.getProperty(k)));
            assertThat(actual).as("%s, wrote %s", which, show(new String(written, StandardCharsets.UTF_8)))
                    .isEqualTo(expected);
            assertThat(ServerPropertiesFile.read(file)).as("read() of %s", which).isEqualTo(expected);
            String writtenText = ServerPropertiesFile.decode(written);
            assertThat(comments).as("comments of %s, wrote %s", which, show(writtenText))
                    .allMatch(writtenText::contains);
        }
    }

    private static String file(Random random, boolean latin1) {
        StringBuilder sb = new StringBuilder();
        if (!latin1 && random.nextInt(8) == 0) {
            sb.append('\uFEFF');
        }
        int lines = random.nextInt(8);
        for (int i = 0; i < lines; i++) {
            sb.append(line(random, latin1));
            if (i < lines - 1 || random.nextBoolean()) {
                sb.append(pick(random, LINE_ENDS));
            }
        }
        return sb.toString();
    }

    private static String line(Random random, boolean latin1) {
        String indent = random.nextInt(4) == 0 ? pick(random, List.of(" ", "\t", "  \f")) : "";
        return switch (random.nextInt(10)) {
            case 0 -> "";
            case 1 -> indent + (random.nextBoolean() ? "#" : "!") + mark() + value(random, latin1);
            case 2 -> "\\".repeat(1 + random.nextInt(3));
            // A line of only a backslash, then a comment: the comment is still one (Properties.load), and stays.
            case 4 -> "\\" + pick(random, LINE_ENDS) + indent + "#" + mark() + value(random, latin1);
            case 3 -> {
                // A continuation: the value goes on over indented lines, which may look like comments or keys.
                StringBuilder sb = new StringBuilder(indent + pick(random, RAW_KEYS) + "=" + value(random, latin1));
                for (int n = 1 + random.nextInt(2); n > 0; n--) {
                    sb.append('\\').append(pick(random, LINE_ENDS)).append(pick(random, List.of("", "  ", "\t")))
                            .append(pick(random, List.of("#not a comment", "gamemode=survival", "more", "")));
                }
                yield sb.toString();
            }
            default -> {
                String key = pick(random, RAW_KEYS);
                if (latin1 && (key.contains("\u5bc6"))) {
                    key = "a";
                }
                yield indent + key + pick(random, SEPARATORS) + value(random, latin1)
                        + "\\".repeat(random.nextInt(4) == 0 ? random.nextInt(4) : 0);
            }
        };
    }

    private static int marks;

    private static String mark() {
        return "kept" + marks++ + "-";
    }

    /**
     * The marks of the comments the server sees in {@code text}: those in no key or value of any line it reads, a
     * later line of the same key included. A mark inside a continued value is part of it, and {@code set} may
     * legitimately replace that line.
     */
    private static List<String> comments(String text) throws IOException {
        AllLines lines = new AllLines();
        lines.load(new StringReader(text));
        Matcher m = MARK.matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            if (!lines.text.toString().contains(m.group())) {
                out.add(m.group());
            }
        }
        return out;
    }

    /** Collects the key and value of every line {@link Properties#load} reads. */
    private static final class AllLines extends Properties {
        private static final long serialVersionUID = 1L;
        private final transient StringBuilder text = new StringBuilder();

        @Override
        public synchronized Object put(Object key, Object value) {
            text.append(key).append('\u0000').append(value).append('\u0000');
            return super.put(key, value);
        }
    }

    private static String value(Random random, boolean latin1) {
        StringBuilder sb = new StringBuilder();
        for (int n = random.nextInt(4); n > 0; n--) {
            String piece = pick(random, VALUE_PIECES);
            if (latin1 && piece.chars().anyMatch(c -> c > 0xFF)) {
                piece = "\u00e9";
            }
            sb.append(piece);
        }
        return sb.toString();
    }

    private static <T> T pick(Random random, List<T> from) {
        return from.get(random.nextInt(from.size()));
    }

    /** The text with line ends and other control characters visible, for a failure message. */
    private static String show(String text) {
        StringBuilder sb = new StringBuilder("\"");
        text.chars().forEach(c -> sb.append(c == '\n' ? "<LF>" : c == '\r' ? "<CR>" : c == '\t' ? "<TAB>"
                : c == '\f' ? "<FF>" : c == '\uFEFF' ? "<BOM>" : String.valueOf((char) c)));
        return sb.append('"').toString();
    }
}
