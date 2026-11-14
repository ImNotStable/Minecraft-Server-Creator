package io.github.imnotstable.msc.bench;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
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

    /** The string {@code field}, which the caller knows is there; Gson's refusal of anything but a string stands. */
    public String string(String field) {
        return tree.get(field).getAsString();
    }

    /** The string {@code field}, or {@code fallback} when it is missing or {@code null}. */
    public String string(String field, String fallback) {
        return has(field) && !tree.get(field).isJsonNull() ? string(field) : fallback;
    }

    /** The boolean {@code field}, which the caller knows is there. */
    public boolean bool(String field) {
        return tree.get(field).getAsBoolean();
    }

    /** The list of strings {@code field}, which the caller knows is there. */
    public List<String> strings(String field) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : tree.getAsJsonArray(field)) {
            out.add(e.getAsString());
        }
        return out;
    }

    /** The object {@code field}, which the caller knows is there. */
    public UserJson object(String field) {
        return new UserJson(tree.getAsJsonObject(field), key(field) + ".");
    }

    /** The list of objects {@code field}, which the caller knows is there; each is named {@code field[i]}. */
    public List<UserJson> objects(String field) {
        JsonArray array = tree.getAsJsonArray(field);
        List<UserJson> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(new UserJson(array.get(i).getAsJsonObject(), key(field) + "[" + i + "]."));
        }
        return out;
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

    /**
     * Binds this object to {@code type} with Gson, which reads an {@code int} field with {@code getAsInt}. So first
     * each key of {@code numbers} the file gives ({@code server.rconPort}, one level of nesting at most) is read
     * exactly as written and written back as the checked {@code int}, and the type binds the very number checked. A
     * refused key goes to {@code problems} and is removed, so the rest of the file still binds and is checked.
     */
    public <T> T bind(Class<T> type, Map<String, JsonNumbers.Range> numbers, List<String> problems) {
        numbers.forEach((key, range) -> {
            int dot = key.indexOf('.');
            JsonObject holder = tree;
            if (dot >= 0) {
                JsonElement inner = tree.get(key.substring(0, dot));
                if (inner == null || !inner.isJsonObject()) {
                    return;
                }
                holder = inner.getAsJsonObject();
            }
            String field = key.substring(dot + 1);
            if (!holder.has(field)) {
                return;
            }
            try {
                holder.add(field, new JsonPrimitive(JsonNumbers.wholeNumber(holder.get(field), at + key, range)));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
                holder.remove(field);
            }
        });
        return new Gson().fromJson(tree, type);
    }

    /** The object as JSON, for messages. */
    @Override
    public String toString() {
        return tree.toString();
    }
}
