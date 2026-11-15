package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stringified NBT, the text {@code /setblock} and {@code /data} use (ADR 0042), read into the Java values {@link Nbt}
 * writes (Byte, Short, Integer, Long, Float, Double, byte[], String, List, Map, int[], long[]) and written back
 * compactly. Every platform's agent answers in its own spelling; the core writes one, so a chest reads the same on
 * every platform and its data fits a schematic.
 */
public final class Snbt {
    /** Deepest nesting read, as in {@link Nbt}. */
    static final int MAX_DEPTH = Nbt.MAX_DEPTH;

    /** Keys of a block entity that are not its data but its type and position, which the block decides. */
    static final Set<String> METADATA = Set.of("id", "x", "y", "z");

    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_.+-]+");
    private static final Pattern INTEGER = Pattern.compile("[-+]?(?:0|[1-9][0-9_]*)([bBsSlLiI]?)");
    private static final Pattern DECIMAL =
            Pattern.compile("[-+]?(?:[0-9][0-9_]*\\.?[0-9_]*|\\.[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)?([fFdD]?)");

    private Snbt() {
    }

    /**
     * Block entity data as the core keeps it: compact, without id and position, without empty lists and compounds at
     * the top (an empty chest says {@code Items:[]}, which a merge never needs). Null when nothing is left.
     *
     * @throws ToolException when the text is not an SNBT compound
     */
    public static String blockData(String snbt) {
        if (!(parse(snbt) instanceof Map<?, ?> map)) {
            throw new ToolException("block entity data must be a compound {...}, got " + snbt);
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            String key = (String) k;
            boolean empty = v instanceof List<?> l && l.isEmpty() || v instanceof Map<?, ?> m && m.isEmpty();
            if (!METADATA.contains(key) && !empty) {
                kept.put(key, v);
            }
        });
        return kept.isEmpty() ? null : write(kept);
    }

    /** Read one SNBT value; the whole text must be that value. */
    public static Object parse(String text) {
        Reader r = new Reader(text);
        Object value = r.value(0);
        r.skipSpace();
        if (r.pos < text.length()) {
            throw r.error("unexpected text after the value");
        }
        return value;
    }

    /** Compact SNBT: {@code {Items:[{Slot:0b,count:3,id:"minecraft:diamond"}]}}. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder out) {
        switch (v) {
            case Byte b -> out.append(b).append('b');
            case Short s -> out.append(s).append('s');
            case Integer i -> out.append(i);
            case Long l -> out.append(l).append('L');
            case Float f -> out.append(f).append('f');
            case Double d -> out.append(d).append('d');
            case String s -> quote(s, out);
            case byte[] bytes -> {
                out.append("[B;");
                for (int i = 0; i < bytes.length; i++) {
                    out.append(i == 0 ? "" : ",").append(bytes[i]).append('b');
                }
                out.append(']');
            }
            case int[] ints -> {
                out.append("[I;");
                for (int i = 0; i < ints.length; i++) {
                    out.append(i == 0 ? "" : ",").append(ints[i]);
                }
                out.append(']');
            }
            case long[] longs -> {
                out.append("[L;");
                for (int i = 0; i < longs.length; i++) {
                    out.append(i == 0 ? "" : ",").append(longs[i]).append('L');
                }
                out.append(']');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    if (BARE.matcher(e.getKey()).matches()) {
                        out.append(e.getKey());
                    } else {
                        quote(e.getKey(), out);
                    }
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            default -> throw new IllegalArgumentException("not an NBT value: " + v.getClass());
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\\' -> out.append('\\').append(c);
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        out.append('"');
    }

    /** A cursor over the text. */
    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        ToolException error(String what) {
            return new ToolException("bad SNBT at character " + (pos + 1) + ": " + what + " in "
                    + io.github.imnotstable.msc.protocol.Limits.shorten(text, 80));
        }

        void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean take(char c) {
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (!take(c)) {
                throw error("expected '" + c + "'");
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH);
            }
            skipSpace();
            if (pos >= text.length()) {
                throw error("expected a value");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return compound(depth);
            }
            if (c == '[') {
                return list(depth);
            }
            if (c == '"' || c == '\'') {
                return quoted();
            }
            return scalar(bare());
        }

        Map<String, Object> compound(int depth) {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            if (take('}')) {
                return map;
            }
            do {
                skipSpace();
                String key = pos < text.length() && (text.charAt(pos) == '"' || text.charAt(pos) == '\'') ? quoted() : bare();
                expect(':');
                map.put(key, value(depth + 1));
            } while (take(','));
            expect('}');
            return map;
        }

        Object list(int depth) {
            expect('[');
            skipSpace();
            if (pos + 1 < text.length() && text.charAt(pos + 1) == ';' && "BIL".indexOf(text.charAt(pos)) >= 0) {
                char kind = text.charAt(pos);
                pos += 2;
                return array(kind);
            }
            List<Object> list = new ArrayList<>();
            if (take(']')) {
                return list;
            }
            do {
                list.add(value(depth + 1));
            } while (take(','));
            expect(']');
            return list;
        }

        Object array(char kind) {
            List<Number> values = new ArrayList<>();
            if (!take(']')) {
                do {
                    if (!(scalar(bare()) instanceof Number n)) {
                        throw error("an array holds numbers");
                    }
                    values.add(n);
                } while (take(','));
                expect(']');
            }
            return switch (kind) {
                case 'B' -> {
                    byte[] out = new byte[values.size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = values.get(i).byteValue();
                    }
                    yield out;
                }
                case 'I' -> values.stream().mapToInt(Number::intValue).toArray();
                default -> values.stream().mapToLong(Number::longValue).toArray();
            };
        }

        String quoted() {
            char q = text.charAt(pos++);
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == q) {
                    return out.toString();
                }
                if (c == '\\' && pos < text.length()) {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 's' -> out.append(' ');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("short \\u escape");
                            }
                            try {
                                out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad \\u escape");
                            }
                            pos += 4;
                        }
                        default -> out.append(e);
                    }
                } else {
                    out.append(c);
                }
            }
            throw error("unclosed string");
        }

        String bare() {
            skipSpace();
            int start = pos;
            while (pos < text.length() && isBare(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error(pos < text.length() ? "unexpected '" + text.charAt(pos) + "'" : "expected a value");
            }
            return text.substring(start, pos);
        }

        private static boolean isBare(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "_-.+".indexOf(c) >= 0;
        }

        /** A number by its suffix, true and false as bytes, anything else an unquoted string. */
        Object scalar(String word) {
            if (word.equals("true") || word.equals("false")) {
                return (byte) (word.equals("true") ? 1 : 0);
            }
            try {
                var m = INTEGER.matcher(word);
                if (m.matches()) {
                    String digits = word.substring(0, word.length() - m.group(1).length()).replace("_", "");
                    return switch (m.group(1).toLowerCase(java.util.Locale.ROOT)) {
                        case "b" -> Byte.parseByte(digits);
                        case "s" -> Short.parseShort(digits);
                        case "l" -> Long.parseLong(digits);
                        default -> Integer.parseInt(digits);
                    };
                }
                m = DECIMAL.matcher(word);
                if (m.matches() && word.chars().anyMatch(Character::isDigit)) {
                    String digits = word.substring(0, word.length() - m.group(1).length()).replace("_", "");
                    return m.group(1).equalsIgnoreCase("f") ? (Object) Float.parseFloat(digits) : (Object) Double.parseDouble(digits);
                }
            } catch (NumberFormatException e) {
                throw error("number out of range: " + word);
            }
            return word;
        }
    }
}
