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
import java.util.Set;
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
 *
 * <p>A refusal shows a value only through {@link Quoted} (#662), so it stays on one line. A key its {@link Keys} mark
 * {@link Keys#secret secret}, and everything under it, is shown only by its type and length, never its content
 * (#661), whatever the read or the refusal.
 *
 * <p>An object read {@link #collecting} reports each problem instead of refusing at the first (#653, #663): an unknown
 * key, and each entry of a list or object that is refused (of the wrong type, no path), are added to its problems and
 * left out, so the other keys and entries are still read and checked.
 */
public final class UserJson {

    private final JsonObject tree;
    private final String at;
    private final List<String> allowed;
    /** The keys whose values are secret. */
    private final Set<String> secrets;
    /** Whether this whole object lies under a secret key, so every value in it is secret. */
    private final boolean secret;
    /** The placeholder values filled into every string read; empty when not filled. */
    private final Map<String, String> values;
    /** Where problems are reported, at this depth and below, instead of refused; null when they are refused. */
    private final List<String> problems;
    /** The key paths of the entries refused into {@link #problems}; null when problems are refused. */
    private final Set<String> refused;

    private UserJson(JsonObject tree, String at, Keys keys, boolean secret, List<String> problems,
            Set<String> refused) {
        this.tree = tree;
        this.at = at;
        this.secret = secret;
        Keys chosen = keys.of(this);
        this.allowed = chosen.names;
        this.secrets = chosen.secrets;
        this.values = Map.of();
        this.problems = problems;
        this.refused = refused;
        for (String field : tree.keySet()) {
            if (!allowed.contains(field)) {
                String problem = key(field) + ": unknown key; the keys are " + allowed;
                if (problems == null) {
                    throw new IllegalArgumentException(problem);
                }
                problems.add(problem);
            }
        }
    }

    private UserJson(UserJson keysFrom, JsonObject tree, String at, Map<String, String> values) {
        this.tree = tree;
        this.at = at;
        this.allowed = keysFrom.allowed;
        this.secrets = keysFrom.secrets;
        this.secret = keysFrom.secret;
        this.values = values;
        this.problems = keysFrom.problems;
        this.refused = keysFrom.refused;
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
        return new UserJson(tree, at, keys, false, null, null);
    }

    /**
     * {@link #of}, except that a problem {@link #of} would refuse at once, here or in any object read from it, is
     * added to {@code problems} and the rest is still read and checked (#653, #663): every unknown key, and every
     * entry of a list or object that is refused, such as {@code "PORT": 8080} in an {@code env} object of strings. A
     * refused entry is left out, or kept as {@code null} by the reads that keep null entries; its key path, such as
     * {@code agent.command[0]} or {@code paths.agentJar}, is added to {@code refused}, so a later rule neither names
     * it again (as null) nor checks what depends on it. A read that refuses the whole value, such as an {@code env}
     * that is no object, still throws, for the caller to report.
     */
    public static UserJson collecting(JsonObject tree, String at, Keys keys, List<String> problems,
            Set<String> refused) {
        return new UserJson(tree, at, keys, false, Objects.requireNonNull(problems), Objects.requireNonNull(refused));
    }

    /**
     * The keys an object of a bench file may have: a fixed set, or a set chosen by the object's own type key, as each
     * check type has its own.
     */
    public static final class Keys {

        private final List<String> names;
        private final String typeField;
        private final Map<String, Keys> types;
        private final Set<String> secrets;

        private Keys(List<String> names, String typeField, Map<String, Keys> types, Set<String> secrets) {
            this.names = names;
            this.typeField = typeField;
            this.types = types;
            this.secrets = secrets;
        }

        public static Keys of(String... names) {
            return of(List.of(names));
        }

        public static Keys of(Collection<String> names) {
            return new Keys(List.copyOf(new LinkedHashSet<>(names)), null, Map.of(), Set.of());
        }

        /**
         * These keys with {@code fields} marked secret (#661): a password, a token, an object of environment
         * variables. A refusal of such a value, or of anything inside it, shows only its JSON type and length
         * ({@link Quoted#masked}), and the name of an entry inside it is shown only up to an {@code =}
         * ({@link Quoted#secretName}).
         */
        public Keys secret(String... fields) {
            if (typeField != null) {
                throw new IllegalArgumentException("mark the secret keys of each type");
            }
            Set<String> marked = new LinkedHashSet<>(secrets);
            for (String field : fields) {
                if (!names.contains(field)) {
                    throw new IllegalArgumentException("secret key " + field + " is not one of " + names);
                }
                marked.add(field);
            }
            return new Keys(names, null, types, Collections.unmodifiableSet(marked));
        }

        /** The keys marked {@link #secret}. */
        public Set<String> secrets() {
            return secrets;
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
            return new Keys(List.of(typeField), typeField, Collections.unmodifiableMap(new LinkedHashMap<>(types)),
                    Set.of());
        }

        /** The fixed keys, or for keys chosen by type just the type key. */
        public List<String> names() {
            return names;
        }

        /** The keys of each type, in order; empty for fixed keys. */
        public Map<String, Keys> types() {
            return types;
        }

        /** The fixed keys {@code json} may have. */
        private Keys of(UserJson json) {
            if (typeField == null) {
                return this;
            }
            String type = requireString(json.tree.get(typeField) == null ? JsonNull.INSTANCE
                    : json.tree.get(typeField), json.key(typeField), true, json.secret);
            if (type == null) {
                throw new IllegalArgumentException(json.key(typeField) + ": is missing; the types are "
                        + types.keySet());
            }
            Keys keys = types.get(type);
            if (keys == null) {
                throw new IllegalArgumentException(json.key(typeField) + ": " + Quoted.value(new JsonPrimitive(type),
                        json.secret) + " is not one of " + types.keySet());
            }
            return keys;
        }
    }

    /**
     * How a refusal names {@code field} of this object, such as {@code task.json checks[0].width}; a key the user
     * chose is shown by {@link Quoted#name}, so it stays on one line.
     */
    public String key(String field) {
        return at + (secret ? Quoted.secretName(field) : Quoted.name(field));
    }

    /** Whether the value of {@code field} is secret: its key is marked so, or this object lies under one that is. */
    private boolean secret(String field) {
        return secret || secrets.contains(field);
    }

    /** How a refusal names entry {@code name} of the object {@code field}, such as {@code modes.m.env.TOKEN}. */
    private String entryKey(String field, String name) {
        return key(field) + "." + (secret(field) ? Quoted.secretName(name) : Quoted.name(name));
    }

    /**
     * Reports {@code refusal} of the entry {@code key} when collecting, so the caller leaves that entry out and reads
     * the next one; otherwise refuses it.
     */
    private void refuse(String key, IllegalArgumentException refusal) {
        if (problems == null) {
            throw refusal;
        }
        problems.add(refusal.getMessage());
        refused.add(key);
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
        return has(field) ? JsonNumbers.wholeNumber(tree.get(field), key(field), range, secret(field)) : absent;
    }

    /** The number {@code field} exactly as written, or {@code absent} when it is missing. */
    public double number(String field, JsonNumbers.Range range, double absent) {
        return has(field) ? JsonNumbers.number(tree.get(field), key(field), range, secret(field)) : absent;
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
            out[i] = JsonNumbers.wholeNumber(array.get(i), key(field) + "[" + i + "]", ranges[i], secret(field));
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
        return absent(field) ? null : requireString(tree.get(field), key(field), false, secret(field));
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
        throw new IllegalArgumentException(key(field) + ": " + Quoted.value(value, secret(field))
                + " is not true or false");
    }

    /** The whole number {@code field} as a {@code long} exactly as written, or {@code absent} when it is missing. */
    public long wholeLong(String field, JsonNumbers.Range range, long absent) {
        return has(field) ? JsonNumbers.wholeLong(tree.get(field), key(field), range, secret(field)) : absent;
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

    /**
     * The list of strings {@code field} as written, or null when absent; when collecting, a refused entry is left
     * out.
     */
    private List<String> rawStrings(String field) {
        if (absent(field)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        JsonArray array = exactArray(field);
        for (int i = 0; i < array.size(); i++) {
            String key = key(field) + "[" + i + "]";
            try {
                out.add(requireString(array.get(i), key, false, secret(field)));
            } catch (IllegalArgumentException e) {
                refuse(key, e);
            }
        }
        return out;
    }

    // Reads that convert a string (#508): each turns the library's refusal, such as PatternSyntaxException's
    // three-line message or InvalidPathException's, into the one-line refusal every other read gives, naming the key
    // path, the value and what the key takes. No reader compiles or parses a user string itself.

    /** The regex {@code field} compiled with {@code flags}, or null when absent; a string that is no regex is refused. */
    public Pattern pattern(String field, int flags) {
        String regex = rawString(field);
        return regex == null ? null : compile(regex, key(field), flags, secret(field));
    }

    /** The list of regexes {@code field} compiled with {@code flags}, empty when absent; entry {@code i} is {@code field[i]}. */
    public List<Pattern> patterns(String field, int flags) {
        List<String> regexes = Objects.requireNonNullElse(rawStrings(field), List.of());
        List<Pattern> out = new ArrayList<>();
        for (int i = 0; i < regexes.size(); i++) {
            String key = key(field) + "[" + i + "]";
            try {
                out.add(compile(regexes.get(i), key, flags, secret(field)));
            } catch (IllegalArgumentException e) {
                refuse(key, e);
            }
        }
        return out;
    }

    /** The path {@code field} as written, or {@code absent}; a string that is no path on this system is refused. */
    public String path(String field, String absent) {
        String path = exactString(field, absent);
        return path == null ? null : checkPath(path, key(field), secret(field));
    }

    /**
     * The list of paths {@code field}, empty when absent; entry {@code i} is {@code field[i]}. A null entry is
     * refused; when collecting, a refused entry is left out.
     */
    public List<String> paths(String field) {
        List<String> paths = strings(field, true, false);
        return paths == null ? new ArrayList<>() : paths;
    }

    /** {@link #stringsOrNulls} whose entries are paths; when collecting, an entry that is no path is kept as null. */
    public List<String> pathsOrNulls(String field) {
        return strings(field, true, true);
    }

    /** {@link #stringMapOrNulls} whose values are paths; when collecting, a value that is no path is kept as null. */
    public Map<String, String> pathMapOrNulls(String field) {
        return stringMap(field, true);
    }

    /** {@code regex} as written, filled with this object's values ({@link Placeholders#fillRegex}) and compiled. */
    private Pattern compile(String regex, String key, int flags, boolean secret) {
        try {
            return Pattern.compile(Placeholders.fillRegex(regex, values), flags);
        } catch (PatternSyntaxException e) {
            // The description is the library's own text, which may quote the regex: it is left out for a secret.
            throw new IllegalArgumentException(key + ": " + Quoted.value(new JsonPrimitive(regex), secret)
                    + " is not a regex" + (secret ? "" : ": " + firstLine(e.getDescription())
                    + (e.getIndex() >= 0 ? " at index " + e.getIndex() : "")));
        }
    }

    private static String checkPath(String path, String key, boolean secret) {
        try {
            Path.of(path);
            return path;
        } catch (InvalidPathException e) {
            // The reason is the library's own text, which may quote the path: it is left out for a secret.
            throw new IllegalArgumentException(key + ": " + Quoted.value(new JsonPrimitive(path), secret)
                    + " is not a path" + (secret ? "" : ": " + firstLine(e.getReason())));
        }
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.strip().lines().findFirst().orElse("");
    }

    /**
     * The list of strings {@code field}, or null when absent, with {@code null} entries kept as null for a later rule
     * to name; an entry of another type is refused.
     */
    public List<String> stringsOrNulls(String field) {
        return strings(field, false, true);
    }

    /**
     * The list of strings {@code field}, or null when absent. Each entry is read on its own: when collecting, a
     * refused entry is reported and kept as null ({@code keepNulls}) or left out, and the next is still read.
     */
    private List<String> strings(String field, boolean paths, boolean keepNulls) {
        if (absent(field)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        JsonArray array = exactArray(field);
        for (int i = 0; i < array.size(); i++) {
            String key = key(field) + "[" + i + "]";
            JsonElement entry = array.get(i);
            if (entry.isJsonNull() && keepNulls) {
                out.add(null);
                continue;
            }
            try {
                String text = fill(requireString(entry, key, false, secret(field)), values);
                out.add(paths ? checkPath(text, key, secret(field)) : text);
            } catch (IllegalArgumentException e) {
                refuse(key, e);
                if (keepNulls) {
                    out.add(null);
                }
            }
        }
        return out;
    }

    /**
     * The object {@code field} whose values are strings, by key in file order, or null when absent; {@code null}
     * values are kept as null for a later rule to name, a value of another type is refused.
     */
    public Map<String, String> stringMapOrNulls(String field) {
        return stringMap(field, false);
    }

    /**
     * {@link #stringMapOrNulls}, each value read on its own: when collecting, a refused value is reported and kept as
     * null under its name, so the other values are still read and a rule can still tell the name is there.
     */
    private Map<String, String> stringMap(String field, boolean paths) {
        if (absent(field)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : requireObject(tree.get(field), key(field), secret(field)).entrySet()) {
            String key = entryKey(field, e.getKey());
            if (e.getValue().isJsonNull()) {
                out.put(e.getKey(), null);
                continue;
            }
            try {
                String text = fill(requireString(e.getValue(), key, false, secret(field)), values);
                out.put(e.getKey(), paths ? checkPath(text, key, secret(field)) : text);
            } catch (IllegalArgumentException refusal) {
                refuse(key, refusal);
                out.put(e.getKey(), null);
            }
        }
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
        for (Map.Entry<String, JsonElement> e : requireObject(tree.get(field), key(field), secret(field)).entrySet()) {
            String key = entryKey(field, e.getKey());
            if (e.getValue().isJsonNull()) {
                out.put(e.getKey(), null);
                continue;
            }
            try {
                out.put(e.getKey(), child(e.getValue(), key, keys, secret(field)));
            } catch (IllegalArgumentException refusal) {
                refuse(key, refusal);
                out.put(e.getKey(), null);
            }
        }
        return out;
    }

    /** The object {@code field} with {@code keys}, or null when absent; any other JSON value is refused. */
    public UserJson exactObject(String field, Keys keys) {
        if (absent(field)) {
            return null;
        }
        return child(tree.get(field), key(field), keys, secret(field));
    }

    /**
     * The list of objects {@code field}, each with {@code keys}, empty when absent; an entry that is not an object,
     * null too, is refused.
     */
    public List<UserJson> exactObjects(String field, Keys keys) {
        JsonArray array = exactArray(field);
        List<UserJson> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String key = key(field) + "[" + i + "]";
            try {
                out.add(child(array.get(i), key, keys, secret(field)));
            } catch (IllegalArgumentException e) {
                refuse(key, e);
            }
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
            for (Map.Entry<String, JsonElement> e : requireObject(tree.get(field), key(field), secret(field))
                    .entrySet()) {
                String key = entryKey(field, e.getKey());
                try {
                    out.put(e.getKey(), child(e.getValue(), key, keys, secret(field)));
                } catch (IllegalArgumentException refusal) {
                    refuse(key, refusal);
                }
            }
        }
        return out;
    }

    private UserJson child(JsonElement value, String at, Keys keys, boolean secret) {
        UserJson child = new UserJson(requireObject(value, at, secret), at + ".", keys, secret, problems, refused);
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
            throw new IllegalArgumentException(key(field) + ": " + Quoted.value(value, secret(field))
                    + " is not a list");
        }
        return value.getAsJsonArray();
    }

    /** The string {@code value}, or null for a JSON null when {@code nullable}; a secret is refused masked. */
    private static String requireString(JsonElement value, String key, boolean nullable, boolean secret) {
        if (nullable && value.isJsonNull()) {
            return null;
        }
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            return primitive.getAsString();
        }
        throw new IllegalArgumentException(key + ": " + Quoted.value(value, secret) + " is not a string");
    }

    private static JsonObject requireObject(JsonElement value, String key, boolean secret) {
        if (value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        throw new IllegalArgumentException(key + ": " + Quoted.value(value, secret) + " is not an object");
    }

    /** The object as JSON, for messages. */
    @Override
    public String toString() {
        return tree.toString();
    }
}
