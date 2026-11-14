package io.github.imnotstable.msc.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON object from a file a user writes ({@code bench.json}, each {@code task.json}), as the readers of those files
 * see it (#416, #423). Its only number reads are {@link JsonNumbers}' exact ones, and it hands out no Gson tree, so a
 * reader cannot reach Gson's {@code getAsInt}, which cuts 0.5 to 0 and wraps 4294992872 to 25576. Each object knows
 * its key path, such as {@code task.json checks[2].}, so a refusal names the file and the key.
 */
public final class UserJson {

    private final JsonObject tree;
    private final String at;

    private UserJson(JsonObject tree, String at) {
        this.tree = tree;
        this.at = at;
    }

    /**
     * The object {@code tree}, whose keys are named {@code at} followed by the key: {@code "task.json "} names
     * {@code task.json timeoutSeconds}, {@code ""} names {@code agent.timeoutSeconds}.
     */
    public static UserJson of(JsonObject tree, String at) {
        return new UserJson(tree, at);
    }

    /** How a refusal names {@code field} of this object, such as {@code task.json checks[0].width}. */
    public String key(String field) {
        return at + field;
    }

    public boolean has(String field) {
        return tree.has(field);
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
        JsonElement value = tree.get(field);
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

    /** Refuses a key outside {@code known}, such as a misspelt one whose value would be silently left out. */
    public void onlyKeys(Collection<String> known) {
        for (String field : tree.keySet()) {
            if (!known.contains(field)) {
                throw new IllegalArgumentException(key(field) + ": unknown key; the keys are " + known);
            }
        }
    }

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
        if (absent(field)) {
            return absent;
        }
        JsonElement value = tree.get(field);
        return requireString(value, key(field));
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
        if (absent(field)) {
            return absent;
        }
        List<String> out = new ArrayList<>();
        JsonArray array = exactArray(field);
        for (int i = 0; i < array.size(); i++) {
            out.add(requireString(array.get(i), key(field) + "[" + i + "]"));
        }
        return out;
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
            out.add(array.get(i).isJsonNull() ? null : requireString(array.get(i), key(field) + "[" + i + "]"));
        }
        return out;
    }

    /**
     * The object {@code field} whose values are strings, by key in file order, or null when absent; {@code null}
     * values are kept as null for a later rule to name, a value of another type is refused.
     */
    public Map<String, String> stringMapOrNulls(String field) {
        UserJson holder = exactObject(field);
        if (holder == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        holder.tree.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().isJsonNull() ? null
                : requireString(e.getValue(), holder.key(e.getKey()))));
        return out;
    }

    /**
     * The object {@code field} whose values are objects, by key in file order, or null when absent; {@code null}
     * values are kept as null for a later rule to name, a value of another type is refused.
     */
    public Map<String, UserJson> objectMapOrNulls(String field) {
        UserJson holder = exactObject(field);
        if (holder == null) {
            return null;
        }
        Map<String, UserJson> out = new LinkedHashMap<>();
        holder.tree.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().isJsonNull() ? null
                : new UserJson(requireObject(e.getValue(), holder.key(e.getKey())), holder.key(e.getKey()) + ".")));
        return out;
    }

    /** The object {@code field}, or null when absent; any other JSON value is refused. */
    public UserJson exactObject(String field) {
        if (absent(field)) {
            return null;
        }
        return new UserJson(requireObject(tree.get(field), key(field)), key(field) + ".");
    }

    /** The list of objects {@code field}, empty when absent; an entry that is not an object, null too, is refused. */
    public List<UserJson> exactObjects(String field) {
        JsonArray array = exactArray(field);
        List<UserJson> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String at = key(field) + "[" + i + "]";
            out.add(new UserJson(requireObject(array.get(i), at), at + "."));
        }
        return out;
    }

    /** The object {@code field} whose values are all objects, by key in file order; empty when absent. */
    public Map<String, UserJson> exactObjectEntries(String field) {
        UserJson holder = exactObject(field);
        Map<String, UserJson> out = new LinkedHashMap<>();
        if (holder != null) {
            holder.tree.entrySet().forEach(e -> out.put(e.getKey(),
                    new UserJson(requireObject(e.getValue(), holder.key(e.getKey())), holder.key(e.getKey()) + ".")));
        }
        return out;
    }

    private boolean absent(String field) {
        return !tree.has(field) || tree.get(field).isJsonNull();
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
