package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the final result Claude Code prints. Handles {@code --output-format json} as one result
 * object or as an array of events (Claude Code 2.1+), and {@code stream-json} (one event per line).
 * The last event with {@code "type":"result"} wins; the model comes from the result's
 * {@code modelUsage} or the {@code system/init} event, which also lists the MCP servers that were
 * connected when the session started.
 */
public final class ClaudeResultParser {

    private ClaudeResultParser() {
    }

    public static AgentResult parse(String stdout) {
        List<JsonObject> events = events(stdout.strip());
        JsonObject result = null;
        String model = "";
        List<String> mcpConnected = new ArrayList<>();
        for (JsonObject o : events) {
            if (o.has("mcp_servers") && o.get("mcp_servers").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("mcp_servers")) {
                    JsonObject server = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
                    if (server.has("name") && server.has("status")
                            && "connected".equals(server.get("status").getAsString())) {
                        mcpConnected.add(server.get("name").getAsString());
                    }
                }
            }
            if (isResult(o)) {
                result = o;
            } else if (model.isEmpty() && o.has("model") && o.get("model").isJsonPrimitive()) {
                model = o.get("model").getAsString();
            }
        }
        if (result == null) {
            return AgentResult.UNKNOWN;
        }
        try {
            return agentResult(result, model, mcpConnected);
        } catch (NumberFormatException e) {
            // A count that is not a number ("num_turns":"abc"): the output is as unreadable as invalid JSON.
            return AgentResult.UNKNOWN;
        }
    }

    private static AgentResult agentResult(JsonObject result, String model, List<String> mcpConnected) {
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
                        : model,
                mcpConnected);
    }

    private static final Pattern USAGE_LIMIT = Pattern.compile(
            "(?i)\\b429\\b|usage limit|out of (extra )?usage|rate.?limit");

    /**
     * The agent's error text when its run ended on an account usage or rate limit (API error 429), else null. Every
     * later attempt would fail the same way in a second, so the bench stops instead of recording them.
     */
    public static String usageLimit(String stdout) {
        JsonObject result = null;
        for (JsonObject o : events(stdout.strip())) {
            if (isResult(o)) {
                result = o;
            }
        }
        if (result == null) {
            return null;
        }
        long status;
        try {
            status = number(result, "api_error_status");
        } catch (NumberFormatException e) {
            status = 0;
        }
        if (!(bool(result, "is_error") || status == 429)) {
            return null;
        }
        String text = result.has("result") && result.get("result").isJsonPrimitive()
                ? result.get("result").getAsString().strip()
                : "";
        if (status == 429 || USAGE_LIMIT.matcher(text).find()) {
            return text.isEmpty() ? "API error 429" : text.lines().findFirst().orElse(text);
        }
        return null;
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
