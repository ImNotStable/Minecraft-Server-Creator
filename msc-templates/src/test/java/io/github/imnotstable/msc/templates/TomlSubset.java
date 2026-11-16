package io.github.imnotstable.msc.templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A strict reader for the TOML the templates write (mods.toml, libs.versions.toml): comments, {@code [table]} and
 * {@code [[array]]} headers, and one-line {@code key = value} pairs whose value is a string, integer, boolean,
 * array or inline table. Anything else, or a repeated key, is an error, so a file it accepts is valid TOML.
 */
final class TomlSubset {
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern INTEGER = Pattern.compile("[+-]?(0|[1-9][0-9_]*)");

    private final String text;
    private int pos;
    private int line = 1;

    private TomlSubset(String text) {
        this.text = text;
    }

    /** Parses {@code text} into nested maps (tables) and lists (arrays, arrays of tables). */
    static Map<String, Object> parse(String text) {
        return new TomlSubset(text).document();
    }

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipBlankAndComments();
            if (pos >= text.length()) {
                return root;
            }
            if (peek() == '[') {
                boolean array = text.startsWith("[[", pos);
                pos += array ? 2 : 1;
                List<String> key = key();
                expect(array ? "]]" : "]");
                current = array ? appendTable(root, key) : table(root, key, true);
            } else {
                List<String> key = key();
                expect("=");
                Object value = value();
                Map<String, Object> target = table(current, key.subList(0, key.size() - 1), false);
                if (target.put(key.getLast(), value) != null) {
                    throw error("duplicate key " + key);
                }
            }
            endOfLine();
        }
    }

    private Map<String, Object> table(Map<String, Object> from, List<String> key, boolean header) {
        Map<String, Object> t = from;
        for (String k : key) {
            Object next = t.computeIfAbsent(k, x -> new LinkedHashMap<String, Object>());
            if (next instanceof List<?> && !header) {
                throw error("dotted key into an array: " + key);
            }
            t = descend(next, key);
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> descend(Object next, List<String> key) {
        if (next instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (next instanceof List<?> list && !list.isEmpty() && list.getLast() instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw error("key " + key + " is already a value");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> appendTable(Map<String, Object> root, List<String> key) {
        Map<String, Object> parent = table(root, key.subList(0, key.size() - 1), true);
        Object existing = parent.computeIfAbsent(key.getLast(), x -> new ArrayList<Object>());
        if (!(existing instanceof List<?>)) {
            throw error("key " + key + " is already a table or value");
        }
        Map<String, Object> t = new LinkedHashMap<>();
        ((List<Object>) existing).add(t);
        return t;
    }

    private List<String> key() {
        List<String> parts = new ArrayList<>();
        do {
            skipSpaces();
            if (peek() == '"') {
                parts.add(basicString());
            } else {
                int start = pos;
                while (pos < text.length() && BARE_KEY.matcher(String.valueOf(text.charAt(pos))).matches()) {
                    pos++;
                }
                if (start == pos) {
                    throw error("expected a key");
                }
                parts.add(text.substring(start, pos));
            }
            skipSpaces();
        } while (consume('.'));
        return parts;
    }

    private Object value() {
        skipSpaces();
        char c = peek();
        if (c == '"') {
            return basicString();
        }
        if (c == '\'') {
            int end = text.indexOf('\'', pos + 1);
            int nl = text.indexOf('\n', pos);
            if (end < 0 || (nl >= 0 && nl < end)) {
                throw error("unterminated literal string");
            }
            String s = text.substring(pos + 1, end);
            pos = end + 1;
            return s;
        }
        if (c == '[') {
            pos++;
            List<Object> list = new ArrayList<>();
            skipSpaces();
            while (!consume(']')) {
                list.add(value());
                skipSpaces();
                if (!consume(',')) {
                    expect("]");
                    break;
                }
                skipSpaces();
            }
            return list;
        }
        if (c == '{') {
            pos++;
            Map<String, Object> table = new LinkedHashMap<>();
            skipSpaces();
            if (consume('}')) {
                return table;
            }
            do {
                List<String> key = key();
                expect("=");
                Object v = value();
                if (table(table, key.subList(0, key.size() - 1), false).put(key.getLast(), v) != null) {
                    throw error("duplicate key " + key);
                }
                skipSpaces();
            } while (consume(','));
            expect("}");
            return table;
        }
        int start = pos;
        while (pos < text.length() && " \t,]}\r\n#".indexOf(text.charAt(pos)) < 0) {
            pos++;
        }
        String word = text.substring(start, pos);
        if (word.equals("true") || word.equals("false")) {
            return Boolean.parseBoolean(word);
        }
        if (INTEGER.matcher(word).matches()) {
            return Long.parseLong(word.replace("_", ""));
        }
        throw error("unsupported value '" + word + "'");
    }

    private String basicString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\n' || (c < 0x20 && c != '\t') || c == 0x7f) {
                throw error("control character in string");
            }
            if (c == '\\') {
                char e = pos < text.length() ? text.charAt(pos++) : ' ';
                switch (e) {
                    case '"', '\\' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private void endOfLine() {
        skipSpaces();
        if (pos < text.length() && peek() == '#') {
            while (pos < text.length() && peek() != '\n') {
                pos++;
            }
        }
        if (pos < text.length()) {
            consume('\r');
            if (!consume('\n')) {
                throw error("expected end of line");
            }
            line++;
        }
    }

    private void skipBlankAndComments() {
        while (pos < text.length()) {
            skipSpaces();
            if (pos < text.length() && peek() == '#') {
                while (pos < text.length() && peek() != '\n') {
                    pos++;
                }
            }
            consume('\r');
            if (!consume('\n')) {
                return;
            }
            line++;
        }
    }

    private void skipSpaces() {
        while (pos < text.length() && (peek() == ' ' || peek() == '\t')) {
            pos++;
        }
    }

    private void expect(String s) {
        skipSpaces();
        if (!text.startsWith(s, pos)) {
            throw error("expected '" + s + "'");
        }
        pos += s.length();
    }

    private boolean consume(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("TOML line " + line + ": " + message);
    }
}
