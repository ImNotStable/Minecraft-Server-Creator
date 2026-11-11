package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the final result object Claude Code prints with {@code --output-format json} (one object)
 * or {@code stream-json} (one object per line; the last one with {@code "type":"result"} wins).
 */
public final class ClaudeResultParser {

    private ClaudeResultParser() {
    }

    public static AgentResult parse(String stdout) {
        JsonObject result = null;
        String trimmed = stdout.strip();
        JsonObject whole = tryObject(trimmed);
        if (whole != null && isResult(whole)) {
            result = whole;
        } else {
            for (String line : trimmed.split("\\R")) {
                JsonObject o = tryObject(line.strip());
                if (o != null && isResult(o)) {
                    result = o;
                }
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
                result.has("total_cost_usd") ? result.get("total_cost_usd").getAsDouble() : 0);
    }

    private static boolean isResult(JsonObject o) {
        return o.has("type") && "result".equals(o.get("type").getAsString());
    }

    private static JsonObject tryObject(String text) {
        if (!text.startsWith("{")) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
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
