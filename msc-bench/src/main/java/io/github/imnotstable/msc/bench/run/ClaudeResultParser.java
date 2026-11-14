package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
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
                    if (string(server, "name") != null && "connected".equals(string(server, "status"))) {
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
        } catch (NotANumber e) {
            // A count or cost that is not a JSON number ("num_turns":"abc", null, true, {}): the output is as
            // unreadable as invalid JSON.
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
                number(result, "num_turns").intValue(),
                number(result, "duration_api_ms").longValue(),
                number(usage, "input_tokens").longValue(),
                number(usage, "cache_creation_input_tokens").longValue(),
                number(usage, "cache_read_input_tokens").longValue(),
                number(usage, "output_tokens").longValue(),
                number(result, "total_cost_usd").doubleValue(),
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
            status = number(result, "api_error_status").longValue();
        } catch (NotANumber e) {
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

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    /**
     * The one reader of every number in the agent's output: 0 when {@code key} is absent, its value when it is a JSON
     * number, and {@link NotANumber} for anything else (null, boolean, string, object, array) or one beyond a long, so a
     * wrong type always means unparsed output and never an exception that fails the attempt.
     */
    static BigDecimal number(JsonObject o, String key) {
        if (!o.has(key)) {
            return BigDecimal.ZERO;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new NotANumber();
        }
        try {
            BigDecimal value = e.getAsBigDecimal();
            if (value.abs().compareTo(LONG_MAX) > 0) {
                throw new NotANumber();
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new NotANumber();
        }
    }

    /** A string value, or null when absent or not a string. */
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString()
                ? o.get(key).getAsString()
                : null;
    }

    /** A value read as a number that is not one. */
    static final class NotANumber extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotANumber() {
            super(null, null, false, false);
        }
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
