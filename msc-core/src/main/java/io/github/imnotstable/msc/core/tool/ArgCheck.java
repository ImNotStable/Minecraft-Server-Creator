package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The arguments of a call, checked against the tool's schema before the tool runs, so a refused call changes nothing
 * (#510): no unknown argument, every required one there, each of its type and, for a number, inside its declared
 * range ({@link Schema#range}). Types are read as {@link Args} reads them, so the string forms {@code "5"} and
 * {@code "true"} pass and the messages are Args' own. The MCP SDK's own schema check is off: this is the only one.
 */
public final class ArgCheck {
    private ArgCheck() {
    }

    /** Throws a {@link ToolException} naming the first problem. */
    public static void check(Tool tool, JsonObject json) {
        JsonObject properties = tool.inputSchema().getAsJsonObject("properties");
        List<String> names = new ArrayList<>(properties.keySet());
        for (String name : json.keySet()) {
            if (!properties.has(name)) {
                throw new ToolException("unknown argument " + name + "; " + tool.name()
                        + (names.isEmpty() ? " takes no arguments" : " takes " + String.join(", ", names)));
            }
        }
        Args args = new Args(json);
        JsonArray required = tool.inputSchema().getAsJsonArray("required");
        if (required != null) {
            for (JsonElement name : required) {
                if (!args.has(name.getAsString())) {
                    throw new ToolException("missing argument: " + name.getAsString());
                }
            }
        }
        for (String name : names) {
            if (args.has(name)) {
                check(name, properties.getAsJsonObject(name), args, json.get(name));
            }
        }
    }

    private static void check(String name, JsonObject property, Args args, JsonElement value) {
        switch (property.get("type").getAsString()) {
            case "string" -> {
                if (!value.isJsonPrimitive()) {
                    throw new ToolException("argument " + name + " must be a string");
                }
            }
            case "boolean" -> args.bool(name, false);
            case "array" -> args.strings(name);
            case "integer" -> inRange(name, property, args.integer(name), Integer.toString(args.integer(name)));
            case "number" -> inRange(name, property, args.number(name), Double.toString(args.number(name)));
            default -> throw new IllegalStateException("schema type " + property.get("type"));
        }
    }

    private static void inRange(String name, JsonObject property, double value, String shown) {
        boolean low = property.has("minimum") && value < property.get("minimum").getAsDouble();
        boolean high = property.has("maximum") && value > property.get("maximum").getAsDouble();
        if (low || high) {
            throw new ToolException("argument " + name + " must be " + range(property) + ", not " + shown);
        }
    }

    /** "1 to 50", or "at least 1" without a maximum; how tools.md and refusals state a declared range. */
    public static String range(JsonObject property) {
        String min = number(property.get("minimum"));
        return property.has("maximum") ? min + " to " + number(property.get("maximum")) : "at least " + min;
    }

    private static String number(JsonElement e) {
        double d = e.getAsDouble();
        return d == Math.rint(d) ? Long.toString((long) d) : e.getAsString();
    }
}
