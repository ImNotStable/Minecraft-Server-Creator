package io.github.imnotstable.msc.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.bench.check.Placeholders;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A JSON object from a file a user writes ({@code bench.json}, each {@code task.json}), as the readers of those files
 * see it (#416, #423). Its only number reads are {@link JsonNumbers}' exact ones, and it hands out no Gson tree, so a
 * reader cannot reach Gson's {@code getAsInt}, which cuts 0.5 to 0 and wraps 4294992872 to 25576. Each object knows
 * its key path, such as {@code task.json checks[2].}, so a refusal names the file and the key.
 *
 * <p>Every object is closed (#502): it is made only with the {@link Keys} it may have, and a key outside them, such as
 * a misspelt {@code "contain"} whose check would then pass for any file, is refused when the object is made, at any
 * depth, before any of its values is read. A reader that reads a key its {@link Keys} leave out fails at once
 * ({@link IllegalStateException}), so the key set and the reads cannot drift apart.
 *
 * <p>An object {@link #filled} with placeholder values fills {@code {name}} in every string it reads, at any depth
 * (#578): a regex gets each value quoted, so it matches the value literally, in one pass that leaves escape sequences
 * such as {@code \p{L}} as written (#588). Since every string read goes through
 * here, no reader can leave a key unfilled.
 */
public final class UserJson {

    private final JsonObject tree;
    private final String at;
    private final List<String> allowed;
    /** The placeholder values filled into every string read; empty when not filled. */
    private final Map<String, String> values;
    /** Where unknown keys are reported, at this depth and below, instead of refused; null when they are refused. */
    private final List<String> unknown;

    private UserJson(JsonObject tree, String at, Keys keys, List<String> unknown) {
        this.tree = tree;
        this.at = at;
        this.allowed = keys.of(this);
        this.values = Map.of();
        this.unknown = unknown;
        for (String field : tree.keySet()) {
            if (!allowed.contains(field)) {
                String problem = key(field) + ": unknown key; the keys are " + allowed;
                if (unknown == null) {
                    throw new IllegalArgumentException(problem);
                }
                unknown.add(problem);
            }
        }
    }

    private UserJson(UserJson keysFrom, JsonObject tree, String at, Map<String, String> values) {
        this.tree = tree;
        this.at = at;
        this.allowed = keysFrom.allowed;
        this.values = values;
        this.unknown = keysFrom.unknown;
    }

    /**
     * This object read with {@code values} filled in: every string read from it or from an object inside it has each
     * {@code {name}} of {@code values} replaced, and a regex has it replaced by the value quoted. Names {@code values}
     * lacks stay as written.
     */
    public UserJson filled(Map<String, String> values) {
        return new UserJson(this, tree, at, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /**
     * The object {@code tree}, which may have only {@code keys}, whose keys are named {@code at} followed by the key:
     * {@code "task.json "} names {@code task.json timeoutSeconds}, {@code ""} names {@code agent.timeoutSeconds}.
     * A key outside {@code keys} is refused here, naming its path and the keys there are.
     */
    public static UserJson of(JsonObject tree, String at, Keys keys) {
        return new UserJson(tree, at, keys, null);
    }

    /**
     * {@link #of}, except that a key outside its {@link Keys}, here or in any object read from it, is added to
     * {@code unknown} (as {@link #of} would refuse it) and left unread, so the object's other keys are still read and
     * checked (#653). Every unknown key is reported, not only the first.
     */
    public static UserJson collectingUnknownKeys(JsonObject tree, String at, Keys keys, List<String> unknown) {
        return new UserJson(tree, at, keys, Objects.requireNonNull(unknown));
    }

    /**
     * The keys an object of a bench file may have: a fixed set, or a set chosen by the object's own type key, as each
     * check type has its own.
     */
    public static final class Keys {

        private final List<String> names;
        private final String typeField;
        private final Map<String, Keys> types;

        private Keys(List<String> names, String typeField, Map<String, Keys> types) {
            this.names = names;
            this.typeField = typeField;
            this.types = types;
        }

        public static Keys of(String... names) {
            return of(List.of(names));
        }

        public static Keys of(Collection<String> names) {
            return new Keys(List.copyOf(new LinkedHashSet<>(names)), null, Map.of());
        }

        /**
         * The keys chosen by the string {@code typeField}: those of {@code types.get(type)}, each of which must hold
         * {@code typeField}. A missing type, or one {@code types} does not name, is refused.
         */
        public static Keys byType(String typeField, Map<String, Keys> types) {
            types.forEach((type, keys) -> {
                if (keys.typeField != null || !keys.names.contains(typeField)) {
                    throw new IllegalArgumentException("the keys of type " + type + " must be fixed and hold "
                            + typeField);
                }
            });
            return new Keys(List.of(typeField), typeField, Collections.unmodifiableMap(new LinkedHashMap<>(types)));
        }

        /** The fixed keys, or for keys chosen by type just the type key. */
        public List<String> names() {
            return names;
        }

        /** The keys of each type, in order; empty for fixed keys. */
        public Map<String, Keys> types() {
            return types;
        }

        /** The keys {@code json} may have. */
        private List<String> of(UserJson json) {
            if (typeField == null) {
                return names;
            }
            String type = requireString(json.tree.get(typeField) == null ? JsonNull.INSTANCE
                    : json.tree.get(typeField), json.key(typeField), true);
            if (type == null) {
                throw new IllegalArgumentException(json.key(typeField) + ": is missing; the types are "
                        + types.keySet());
            }
            Keys keys = types.get(type);
            if (keys == null) {
                throw new IllegalArgumentException(json.key(typeField) + ": \"" + type + "\" is not one of "
                        + types.keySet());
            }
            return keys.names;
        }
    }

    /** How a refusal names {@code field} of this object, such as {@code task.json checks[0].width}. */
    public String key(String field) {
        return at + field;
    }

    public boolean has(String field) {
        return tree.has(known(field));
    }

    /** {@code field}, which the reader must have listed in this object's {@link Keys}. */
    private String known(String field) {
        if (!allowed.contains(field)) {
            throw new IllegalStateException("a reader reads " + key(field) + ", which is not one of its keys "
                    + allowed);
        }
        return field;
    }

    /** The whole number {@code field} exactly as written, or {@code absent} when it is missing. */
    public int wholeNumber(String field, JsonNumbers.Range range, int absent) {
        return has(field) ? JsonNumbers.wholeNumber(tree.get(field), key(field), range) : absent;
    }

    /** The number {@code field} exactly as written, or {@code absent} when it is missing. */
    public double number(String field, JsonNumbers.Range range, double absent) {
        return has(field) ? JsonNumbers.number(tree.get(field), key(field), range) : absent;
    }

    /**
     * The list {@code field} of exactly one whole number per range, such as a block position; anything else is
     * refused as {@code key: shape}. Element {@code i} is named {@code field[i]}.
     */
    public int[] wholeNumbers(String field, String shape, JsonNumbers.Range... ranges) {
        JsonElement value = tree.get(known(field));
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != ranges.length) {
            throw new IllegalArgumentException(key(field) + ": " + shape);
        }
        JsonArray array = value.getAsJsonArray();
        int[] out = new int[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            out[i] = JsonNumbers.wholeNumber(array.get(i), key(field) + "[" + i + "]", ranges[i]);
        }
        return out;
    }

    // Exact reads, the only reads there are (#462): each refuses a value of the wrong JSON type with an
    // IllegalArgumentException that names the key, shows the value and says the type it takes, where Gson's getAs*
    // would convert it ("1" as 1, 1 as "1", "yes" as false) or throw a ClassCastException. A missing key or a JSON
    // null is absent, except to the number reads, which refuse null.

    /** Refuses each of {@code fields} that is missing or {@code null}. */
    public void require(String... fields) {
        for (String field : fields) {
            if (absent(field)) {
                throw new IllegalArgumentException(key(field) + ": is missing");
            }
        }
    }

    /** The string {@code field}, or {@code absent}; any other JSON value is refused. */
    public String exactString(String field, String absent) {
        String raw = rawString(field);
        // A default stands for what the file could have written, so it is filled as well.
        String out = raw == null ? absent : raw;
        return out == null ? null : fill(out, values);
    }

    /** The string {@code field} as written, or null when absent. */
    private String rawString(String field) {
        return absent(field) ? null : requireString(tree.get(field), key(field));
    }

    private static String fill(String text, Map<String, String> values) {
        return values.isEmpty() ? text : Placeholders.fill(text, values);
    }

    /** The boolean {@code field}, or {@code absent}; any other JSON value, such as {@code "true"}, is refused. */
    public boolean exactBool(String field, boolean absent) {
        if (absent(field)) {
            return absent;
        }
        JsonElement value = tree.get(field);
        if (value instanceof JsonPrimitive primitive && primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        throw new IllegalArgumentException(key(field) + ": " + JsonNumbers.written(value) + " is not true or false");
    }

    /** The whole number {@code field} as a {@code long} exactly as written, or {@code absent} when it is missing. */
    public long wholeLong(String field, JsonNumbers.Range range, long absent) {
        return has(field) ? JsonNumbers.wholeLong(tree.get(field), key(field), range) : absent;
    }

    /** The list of strings {@code field}, empty when absent; a list entry that is not a string, null too, is refused. */
    public List<String> exactStrings(String field) {
        List<String> out = exactStrings(field, null);
        return out == null ? new ArrayList<>() : out;
    }

    /** The list of strings {@code field}, or {@code absent}; a list entry that is not a string, null too, is refused. */
    public List<String> exactStrings(String field, List<String> absent) {
        List<String> raw = rawStrings(field);
        List<String> out = raw == null ? absent : raw;
        return out == null ? null : new ArrayList<>(out.stream().map(s -> fill(s, values)).toList());
    }

    /** The list of strings {@code field} as written, or null when absent. */
    private List<String> rawStrings(String field) {
        if (absent(field)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        JsonArray array = exactArray(field);
        for (int i = 0; i < array.size(); i++) {
            out.add(requireString(array.get(i), key(field) + "[" + i + "]"));
        }
        return out;
    }

    // Reads that convert a string (#508): each turns the library's refusal, such as PatternSyntaxException's
    // three-line message or InvalidPathException's, into the one-line refusal every other read gives, naming the key
    // path, the value and what the key takes. No reader compiles or parses a user string itself.

    /** The regex {@code field} compiled with {@code flags}, or null when absent; a string that is no regex is refused. */
    public Pattern pattern(String field, int flags) {
        String regex = rawString(field);
        return regex == null ? null : compile(regex, key(field), flags);
    }

    /** The list of regexes {@code field} compiled with {@code flags}, empty when absent; entry {@code i} is {@code field[i]}. */
    public List<Pattern> patterns(String field, int flags) {
        List<String> regexes = Objects.requireNonNullElse(rawStrings(field), List.of());
        List<Pattern> out = new ArrayList<>();
        for (int i = 0; i < regexes.size(); i++) {
            out.add(compile(regexes.get(i), key(field) + "[" + i + "]", flags));
        }
        return out;
    }

    /** The path {@code field} as written, or {@code absent}; a string that is no path on this system is refused. */
    public String path(String field, String absent) {
        String path = exactString(field, absent);
        return path == null ? null : checkPath(path, key(field));
    }

    /** The list of paths {@code field}, empty when absent; entry {@code i} is {@code field[i]}. */
    public List<String> paths(String field) {
        List<String> paths = exactStrings(field);
        for (int i = 0; i < paths.size(); i++) {
            checkPath(paths.get(i), key(field) + "[" + i + "]");
        }
        return paths;
    }

    /** {@link #stringsOrNulls} whose entries are paths. */
    public List<String> pathsOrNulls(String field) {
        List<String> paths = stringsOrNulls(field);
        for (int i = 0; paths != null && i < paths.size(); i++) {
            if (paths.get(i) != null) {
                checkPath(paths.get(i), key(field) + "[" + i + "]");
            }
        }
        return paths;
    }

    /** {@link #stringMapOrNulls} whose values are paths. */
    public Map<String, String> pathMapOrNulls(String field) {
        Map<String, String> paths = stringMapOrNulls(field);
        if (paths != null) {
            paths.forEach((name, path) -> {
                if (path != null) {
                    checkPath(path, key(field) + "." + name);
                }
            });
        }
        return paths;
    }

    /** {@code regex} as written, filled with this object's values ({@link Placeholders#fillRegex}) and compiled. */
    private Pattern compile(String regex, String key, int flags) {
        try {
            return Pattern.compile(Placeholders.fillRegex(regex, values), flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(key + ": " + quoted(regex)
                    + " is not a regex: " + firstLine(e.getDescription())
                    + (e.getIndex() >= 0 ? " at index " + e.getIndex() : ""));
        }
    }

    private static String checkPath(String path, String key) {
        try {
            Path.of(path);
            return path;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(key + ": " + quoted(path)
                    + " is not a path: " + firstLine(e.getReason()));
        }
    }

    /** {@code text} as a refusal shows a string value: in JSON quotes and escapes, so on one line, and cut when long. */
    public static String quoted(String text) {
        return JsonNumbers.written(new JsonPrimitive(text));
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.strip().lines().findFirst().orElse("");
    }

    /**
     * The list of strings {@code field}, or null when absent, with {@code null} entries kept as null for a later rule
     * to name; an entry of another type is refused.
     */
    public List<String> stringsOrNulls(String field) {
        if (absent(field)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        JsonArray array = exactArray(field);
        for (int i = 0; i < array.size(); i++) {
            out.add(array.get(i).isJsonNull() ? null
                    : fill(requireString(array.get(i), key(field) + "[" + i + "]"), values));
        }
        return out;
    }

    /**
     * The object {@code field} whose values are strings, by key in file order, or null when absent; {@code null}
     * values are kept as null for a later rule to name, a value of another type is refused.
     */
    public Map<String, String> stringMapOrNulls(String field) {
        if (absent(field)) {
            return null;
        }
        String at = key(field) + ".";
        Map<String, String> out = new LinkedHashMap<>();
        requireObject(tree.get(field), key(field)).entrySet().forEach(e -> out.put(e.getKey(),
                e.getValue().isJsonNull() ? null : fill(requireString(e.getValue(), at + e.getKey()), values)));
        return out;
    }

    /**
     * The object {@code field} whose values are objects with {@code keys}, by key in file order (the names are the
     * file's own), or null when absent; {@code null} values are kept as null for a later rule to name, a value of
     * another type is refused.
     */
    public Map<String, UserJson> objectMapOrNulls(String field, Keys keys) {
        if (absent(field)) {
            return null;
        }
        Map<String, UserJson> out = new LinkedHashMap<>();
        requireObject(tree.get(field), key(field)).entrySet().forEach(e -> out.put(e.getKey(),
                e.getValue().isJsonNull() ? null : child(e.getValue(), key(field) + "." + e.getKey(), keys)));
        return out;
    }

    /** The object {@code field} with {@code keys}, or null when absent; any other JSON value is refused. */
    public UserJson exactObject(String field, Keys keys) {
        if (absent(field)) {
            return null;
        }
        return child(tree.get(field), key(field), keys);
    }

    /**
     * The list of objects {@code field}, each with {@code keys}, empty when absent; an entry that is not an object,
     * null too, is refused.
     */
    public List<UserJson> exactObjects(String field, Keys keys) {
        JsonArray array = exactArray(field);
        List<UserJson> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(child(array.get(i), key(field) + "[" + i + "]", keys));
        }
        return out;
    }

    /**
     * The object {@code field} whose values are all objects with {@code keys}, by key in file order (the names are
     * the file's own); empty when absent.
     */
    public Map<String, UserJson> exactObjectEntries(String field, Keys keys) {
        Map<String, UserJson> out = new LinkedHashMap<>();
        if (!absent(field)) {
            requireObject(tree.get(field), key(field)).entrySet().forEach(e -> out.put(e.getKey(),
                    child(e.getValue(), key(field) + "." + e.getKey(), keys)));
        }
        return out;
    }

    private UserJson child(JsonElement value, String at, Keys keys) {
        UserJson child = new UserJson(requireObject(value, at), at + ".", keys, unknown);
        return values.isEmpty() ? child : child.filled(values);
    }

    private boolean absent(String field) {
        return !tree.has(known(field)) || tree.get(field).isJsonNull();
    }

    private JsonArray exactArray(String field) {
        if (absent(field)) {
            return new JsonArray();
        }
        JsonElement value = tree.get(field);
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(key(field) + ": " + JsonNumbers.written(value) + " is not a list");
        }
        return value.getAsJsonArray();
    }

    private static String requireString(JsonElement value, String key) {
        return requireString(value, key, false);
    }

    /** The string {@code value}, or null for a JSON null when {@code nullable}. */
    private static String requireString(JsonElement value, String key, boolean nullable) {
        if (nullable && value.isJsonNull()) {
            return null;
        }
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            return primitive.getAsString();
        }
        throw new IllegalArgumentException(key + ": " + JsonNumbers.written(value) + " is not a string");
    }

    private static JsonObject requireObject(JsonElement value, String key) {
        if (value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        throw new IllegalArgumentException(key + ": " + JsonNumbers.written(value) + " is not an object");
    }

    /** The object as JSON, for messages. */
    @Override
    public String toString() {
        return tree.toString();
    }
}
