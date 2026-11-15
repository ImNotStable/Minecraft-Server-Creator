package io.github.imnotstable.msc.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one way a refusal shows something a user wrote (#652, #661, #662): a string value, any JSON value, or a name
 * inside a key path such as the {@code a b} of {@code modes."a b".env}. Every refusal of a bench file goes through here,
 * so each problem stays on one line and a secret never reaches a terminal or a CI log.
 *
 * <ul>
 * <li>A string is shown in JSON quotes, so a leading or trailing space is visible inside them ({@code "../x "}).
 * The quote and the backslash are escaped as in JSON, and so is every character that would split the line or hide
 * itself: control characters (a line break is shown as backslash n, a tab as backslash t), the line and paragraph
 * separators, every space but the plain one (a no-break space), invisible format characters (a zero-width space, a
 * right-to-left override), unassigned and private-use characters and a lone surrogate, each as a JSON unicode
 * escape.</li>
 * <li>A value is cut after {@link #MAX} characters, with {@code ...} after it; an escape is never cut in two.</li>
 * <li>A secret, such as {@code server.rconPassword} or a value under {@code modes.<name>.env}, is never shown: only
 * its JSON type and length ({@code a number of 8 characters}, {@code a list of 1 entry}).</li>
 * </ul>
 */
public final class Quoted {

    /** The most characters of a value a refusal shows. */
    public static final int MAX = 60;
    /** The most characters of a library's or an exception's own text a refusal shows ({@link #message}). */
    public static final int MESSAGE_MAX = 200;

    /** A name shown in a key path as it is: nothing a reader could take for a separator, a quote or a blank. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[\\p{L}\\p{N}_.$-]+");

    private Quoted() {
    }

    /** {@code text} as a refusal shows a string value: in JSON quotes, escaped onto one line, cut when long. */
    public static String text(String text) {
        Units units = new Units(MAX);
        string(units, text);
        return units.toString();
    }

    /**
     * {@code value} as a refusal shows it: a number as written, a string as {@link #text}, a list or object as
     * one-line JSON with its strings escaped the same way; when {@code secret}, only its type and length
     * ({@link #masked}).
     */
    public static String value(JsonElement value, boolean secret) {
        if (secret) {
            return masked(value);
        }
        Units units = new Units(MAX);
        json(units, value);
        return units.toString();
    }

    /**
     * What a refusal shows of a secret {@code value}: its JSON type and length, never its content, such as
     * {@code a number of 8 characters}, {@code a string of 31 characters}, {@code a list of 1 entry} or
     * {@code an object of 2 keys}.
     */
    public static String masked(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonArray()) {
            int size = value.getAsJsonArray().size();
            return "a list of " + size + (size == 1 ? " entry" : " entries");
        }
        if (value.isJsonObject()) {
            int size = value.getAsJsonObject().size();
            return "an object of " + size + (size == 1 ? " key" : " keys");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return "a boolean";
        }
        return (primitive.isNumber() ? "a number of " : "a string of ") + characters(primitive.getAsString());
    }

    /**
     * {@code path}, a path a refusal shows whole, such as the absolute path a missing file was looked for at: as it
     * is when no character of it needs an escape (a backslash, as Windows writes paths, does not) and it neither
     * starts nor ends with a space, else as {@link #text} without the cut.
     */
    public static String path(String path) {
        boolean plain = !path.isEmpty() && !path.startsWith(" ") && !path.endsWith(" ")
                && path.codePoints().allMatch(c -> c == '\\' || c != '"' && !hidden(c));
        if (plain) {
            return path;
        }
        Units units = new Units(Integer.MAX_VALUE);
        string(units, path);
        return units.toString();
    }

    /**
     * {@code message}, free text that a library or an exception wrote, such as a regex error's description, as a
     * refusal appends it (#672): without quotes, since it is no value, but with every character that would split the
     * line or hide itself escaped as {@link #text} escapes it (a line break as backslash n, a right-to-left override as
     * a unicode escape), so it stays on the problem's line and can be seen. A quote and a backslash are shown as they
     * are. Cut after {@link #MESSAGE_MAX} characters, with {@code ...} after it; null is empty.
     */
    public static String message(String message) {
        if (message == null) {
            return "";
        }
        String text = message.strip();
        Units units = new Units(MESSAGE_MAX);
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            units.add(c == '"' || c == '\\' ? Character.toString(c) : escaped(c));
            i += Character.charCount(c);
        }
        return units.toString();
    }

    /**
     * {@code name}, a key a user chose (a mode, a {@code paths} entry, an unknown key), as one step of a key path:
     * as it is when it is a plain name of letters, digits, {@code _ . - $}, else as {@link #text}, so
     * {@code modes."a\nb".env} stays on one line.
     */
    public static String name(String name) {
        return name.length() <= MAX && PLAIN_NAME.matcher(name).matches() ? name : text(name);
    }

    /**
     * {@code name}, the name of an entry of a secret object such as {@code env}, as {@link #name} shows it; a name
     * holding {@code =} is shown only up to it, since {@code "API_KEY=sk-..."} holds its value: {@code "API_KEY="} and
     * then the length of the rest, {@code "API_KEY="<12 hidden characters>}.
     */
    public static String secretName(String name) {
        int equals = name.indexOf('=');
        if (equals < 0) {
            return name(name);
        }
        String rest = name.substring(equals + 1);
        int count = rest.codePointCount(0, rest.length());
        return text(name.substring(0, equals + 1)) + "<" + count + " hidden character" + (count == 1 ? "" : "s") + ">";
    }

    private static String characters(String text) {
        int count = text.codePointCount(0, text.length());
        return count + (count == 1 ? " character" : " characters");
    }

    private static void json(Units units, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            units.add("null");
        } else if (value instanceof JsonArray array) {
            units.add("[");
            for (int i = 0; i < array.size(); i++) {
                if (units.full()) {
                    units.markCut();
                    break;
                }
                if (i > 0) {
                    units.add(",");
                }
                json(units, array.get(i));
            }
            units.add("]");
        } else if (value instanceof JsonObject object) {
            units.add("{");
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (units.full()) {
                    units.markCut();
                    break;
                }
                if (!first) {
                    units.add(",");
                }
                first = false;
                string(units, entry.getKey());
                units.add(":");
                json(units, entry.getValue());
            }
            units.add("}");
        } else {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) {
                string(units, primitive.getAsString());
            } else {
                // a number or true/false: written with no character that needs an escape
                primitive.getAsString().codePoints().forEach(c -> units.add(Character.toString(c)));
            }
        }
    }

    /** {@code text} in quotes, each character a unit; the quotes are added even past the limit, so a cut is closed. */
    private static void string(Units units, String text) {
        units.always("\"");
        for (int i = 0; i < text.length(); ) {
            if (units.full()) {
                units.markCut();
                break;
            }
            int c = text.codePointAt(i);
            units.add(escaped(c));
            i += Character.charCount(c);
        }
        units.always("\"");
    }

    private static String escaped(int c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            default -> hidden(c) ? unicode(c) : Character.toString(c);
        };
    }

    /** Whether {@code c} would split the line, or could not be seen or told from a plain space. */
    private static boolean hidden(int c) {
        if (c == ' ') {
            return false;
        }
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE -> true;
            default -> false;
        };
    }

    private static String unicode(int c) {
        StringBuilder out = new StringBuilder();
        for (char unit : Character.toChars(c)) {
            out.append(String.format("\\u%04x", (int) unit));
        }
        return out.toString();
    }

    /**
     * Text built from units, each a character or a whole escape, of which at most {@code max} are kept;
     * {@code ...} marks the cut.
     */
    private static final class Units {

        private final StringBuilder out = new StringBuilder();
        private final int max;
        private int count;
        private boolean cut;

        Units(int max) {
            this.max = max;
        }

        boolean full() {
            return count >= max;
        }

        void markCut() {
            cut = true;
        }

        void add(String unit) {
            if (full()) {
                cut = true;
                return;
            }
            out.append(unit);
            count++;
        }

        /** Adds {@code unit}, a quote, without counting it and even past the limit. */
        void always(String unit) {
            out.append(unit);
        }

        @Override
        public String toString() {
            return cut ? out + "..." : out.toString();
        }
    }
}
