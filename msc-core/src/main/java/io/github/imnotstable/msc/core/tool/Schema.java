package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Small builder for a tool's argument schema. Types: string, integer, number, boolean, strings (array of string). A
 * number's range is declared here once ({@link #range}), as the schema's {@code minimum} and {@code maximum}: the
 * client sees it there, tools.md renders it from there, and {@link ArgCheck} refuses a value outside it before the
 * tool runs, so the stated range and the checked one cannot differ (#511).
 */
public final class Schema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    private Schema() {
    }

    public static Schema of() {
        return new Schema();
    }

    public Schema req(String name, String type, String description) {
        required.add(name);
        return opt(name, type, description);
    }

    public Schema opt(String name, String type, String description) {
        JsonObject p = new JsonObject();
        if (type.equals("strings")) {
            p.addProperty("type", "array");
            JsonObject items = new JsonObject();
            items.addProperty("type", "string");
            p.add("items", items);
        } else {
            p.addProperty("type", type);
        }
        p.addProperty("description", description);
        properties.add(name, p);
        return this;
    }

    /**
     * An optional integer from {@code min} to {@code max} ({@link Integer#MAX_VALUE}: no upper bound). The description
     * must not repeat the range; it comes from these numbers.
     */
    public Schema range(String name, int min, int max, String description) {
        opt(name, "integer", description);
        JsonObject p = properties.getAsJsonObject(name);
        p.addProperty("minimum", min);
        if (max != Integer.MAX_VALUE) {
            p.addProperty("maximum", max);
        }
        return this;
    }

    /** An optional number from {@code min} to {@code max}; see {@link #range(String, int, int, String)}. */
    public Schema range(String name, double min, double max, String description) {
        opt(name, "number", description);
        JsonObject p = properties.getAsJsonObject(name);
        p.addProperty("minimum", min);
        p.addProperty("maximum", max);
        return this;
    }

    /** The {@code maxChars} argument of a tool whose result size the caller may choose ({@link Budget}). */
    public Schema maxChars() {
        return range("maxChars", Budget.MIN_CHARS, Budget.MAX_CHARS, "Default " + Budget.DEFAULT_CHARS);
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        return schema;
    }
}
