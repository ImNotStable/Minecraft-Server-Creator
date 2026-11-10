package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Typed access to a tool call's arguments, with error messages a model can act on. */
public final class Args {
    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public JsonObject json() {
        return json;
    }

    public boolean has(String name) {
        return json.has(name) && !json.get(name).isJsonNull();
    }

    public String str(String name) {
        if (!has(name)) {
            throw new ToolException("missing argument: " + name);
        }
        JsonElement e = json.get(name);
        if (!e.isJsonPrimitive()) {
            throw new ToolException("argument " + name + " must be a string");
        }
        return e.getAsString();
    }

    public String str(String name, String fallback) {
        return has(name) ? str(name) : fallback;
    }

    public int integer(String name) {
        try {
            String s = str(name).trim();
            double d = Double.parseDouble(s);
            if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
                throw new NumberFormatException(s);
            }
            return (int) d;
        } catch (NumberFormatException e) {
            throw new ToolException("argument " + name + " must be an integer");
        }
    }

    public int integer(String name, int fallback) {
        return has(name) ? integer(name) : fallback;
    }

    public boolean bool(String name, boolean fallback) {
        if (!has(name)) {
            return fallback;
        }
        String s = str(name);
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        throw new ToolException("argument " + name + " must be true or false");
    }

    /** An array of strings; a single string is accepted as a one-element list. Missing means empty. */
    public List<String> strings(String name) {
        List<String> out = new ArrayList<>();
        if (!has(name)) {
            return out;
        }
        JsonElement e = json.get(name);
        if (e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                if (!item.isJsonPrimitive()) {
                    throw new ToolException("argument " + name + " must be an array of strings");
                }
                out.add(item.getAsString());
            }
        } else if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
        } else {
            throw new ToolException("argument " + name + " must be an array of strings");
        }
        return out;
    }
}
