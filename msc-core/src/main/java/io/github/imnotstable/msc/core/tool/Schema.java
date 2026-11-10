package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small builder for a tool's argument schema. Types: string, integer, number, boolean, strings (array of string). */
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
