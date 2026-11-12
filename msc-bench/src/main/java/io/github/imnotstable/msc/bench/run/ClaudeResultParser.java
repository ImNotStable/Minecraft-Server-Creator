package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the final result Claude Code prints. Handles {@code --output-format json} as one result
 * object or as an array of events (Claude Code 2.1+), and {@code stream-json} (one event per line).
 * The last event with {@code "type":"result"} wins; the model comes from the result's
 * {@code modelUsage} or the {@code system/init} event.
 */
public final class ClaudeResultParser {

    private ClaudeResultParser() {
    }

    public static AgentResult parse(String stdout) {
        List<JsonObject> events = events(stdout.strip());
        JsonObject result = null;
        String model = "";
        for (JsonObject o : events) {
            if (isResult(o)) {
                result = o;
            } else if (model.isEmpty() && o.has("model") && o.get("model").isJsonPrimitive()) {
                model = o.get("model").getAsString();
            }
        }
        if (result == null) {
            return AgentResult.UNKNOWN;
        }
        JsonObject usage = result.has("usage") && result.get("usage").isJsonObject()
                ? result.getAsJsonObject("usage")
                : new JsonObject();
        return new AgentResult(
                true,
                bool(result, "is_error"),
                (int) number(result, "num_turns"),
                number(result, "duration_api_ms"),
                number(usage, "input_tokens"),
                number(usage, "cache_creation_input_tokens"),
                number(usage, "cache_read_input_tokens"),
                number(usage, "output_tokens"),
                result.has("total_cost_usd") ? result.get("total_cost_usd").getAsDouble() : 0,
                result.has("modelUsage") && result.get("modelUsage").isJsonObject()
                        && !result.getAsJsonObject("modelUsage").isEmpty()
                        ? String.join(",", result.getAsJsonObject("modelUsage").keySet())
                        : model);
    }

    private static List<JsonObject> events(String text) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement whole = tryParse(text);
        if (whole != null && whole.isJsonObject()) {
            out.add(whole.getAsJsonObject());
        } else if (whole != null && whole.isJsonArray()) {
            for (JsonElement e : whole.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        } else {
            for (String line : text.split("\\R")) {
                JsonElement e = tryParse(line.strip());
                if (e != null && e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static boolean isResult(JsonObject o) {
        return o.has("type") && o.get("type").isJsonPrimitive() && "result".equals(o.get("type").getAsString());
    }

    private static JsonElement tryParse(String text) {
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long number(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
