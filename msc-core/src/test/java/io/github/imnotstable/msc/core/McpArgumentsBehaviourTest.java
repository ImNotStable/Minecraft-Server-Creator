package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.tool.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #510, over the real transport: every tool call that arrives over MCP reaches ToolRegistry.call, so a bad argument
 * gets MSC's one-line {@code error: ...}, is logged, and changes nothing. For every registered tool, from its schema:
 * each required argument left out, each argument of the wrong JSON type, string forms of numbers and booleans, a
 * number no double holds (1e400), and an unknown extra argument.
 */
class McpArgumentsBehaviourTest {
    @TempDir
    Path dir;

    @Test
    void everyBadArgumentOfEveryToolGetsMscsOneLineErrorAndIsLogged() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        Path log = dir.resolve("log.jsonl");
        List<String> failures = new ArrayList<>();
        int calls = 0;
        try (CoreHarness h = new CoreHarness(ws, c -> c.withToolLog(log)).withAgent();
             McpPipe mcp = new McpPipe(h.core.registry())) {
            Map<String, String> before = TreeSnapshot.of(ws);
            for (Tool tool : h.core.registry().tools()) {
                Map<String, String> expected = new LinkedHashMap<>();
                JsonObject schema = tool.inputSchema();
                JsonObject properties = schema.getAsJsonObject("properties");
                List<String> required = new ArrayList<>();
                if (schema.has("required")) {
                    schema.getAsJsonArray("required").forEach(e -> required.add(e.getAsString()));
                }
                for (String name : required) {
                    expected.put(arguments(properties, required, name, null), "error: missing argument: " + name);
                }
                for (String name : properties.keySet()) {
                    JsonObject p = properties.getAsJsonObject(name);
                    String type = p.get("type").getAsString();
                    String what = switch (type) {
                        case "integer" -> "an integer";
                        case "number" -> "a number";
                        case "boolean" -> "true or false";
                        case "array" -> "an array of strings";
                        default -> "a string";
                    };
                    String bad = "error: argument " + name + " must be " + what;
                    expected.put(arguments(properties, required, name, "{\"a\":1}"), bad);
                    switch (type) {
                        case "integer" -> {
                            expected.put(arguments(properties, required, name, "1e400"), bad);
                            expected.put(arguments(properties, required, name, "2.5"), bad);
                            expected.put(arguments(properties, required, name, "\"2.5\""), bad);
                            expected.put(arguments(properties, required, name, "true"), bad);
                        }
                        case "number" -> {
                            expected.put(arguments(properties, required, name, "1e400"), bad);
                            expected.put(arguments(properties, required, name, "\"x\""), bad);
                        }
                        case "boolean" -> {
                            expected.put(arguments(properties, required, name, "\"maybe\""), bad);
                            expected.put(arguments(properties, required, name, "1"), bad);
                        }
                        case "array" -> expected.put(arguments(properties, required, name, "[[\"x\"]]"), bad);
                        default -> expected.put(arguments(properties, required, name, "[\"x\"]"), bad);
                    }
                    if (p.has("minimum")) {
                        // The string form of a number is read as the number: below the range, refused with the range.
                        double low = p.get("minimum").getAsDouble() - 1;
                        String shown = type.equals("integer") ? Long.toString((long) low) : Double.toString(low);
                        expected.put(arguments(properties, required, name, "\"" + shown + "\""), "error: argument "
                                + name + " must be " + io.github.imnotstable.msc.core.tool.ArgCheck.range(p)
                                + ", not " + shown);
                    }
                }
                expected.put(arguments(properties, required, "bogus", "1"), "error: unknown argument bogus; "
                        + tool.name() + (properties.isEmpty() ? " takes no arguments"
                        : " takes " + String.join(", ", properties.keySet())));
                for (Map.Entry<String, String> call : expected.entrySet()) {
                    McpPipe.Answer a = mcp.call(tool.name(), call.getKey());
                    calls++;
                    String got = a.rpcError() != null ? "rpc " + a.rpcError() : a.text();
                    if (!a.error() || !got.equals(call.getValue())) {
                        failures.add(tool.name() + " " + call.getKey() + " -> " + got + " (want " + call.getValue() + ")");
                    }
                }
            }
            assertThat(failures).as("calls answered other than by MSC's one-line error").isEmpty();
            assertThat(TreeSnapshot.of(ws)).as("refused calls change nothing").isEqualTo(before);
            assertThat(Files.readAllLines(log)).as("every call is in the tool log").hasSize(calls);
            assertThat(calls).isGreaterThan(200);

            // The lenient string forms work over MCP too, and stats count every refused call.
            McpPipe.Answer stats = mcp.call("msc_status", "{\"stats\":\"true\"}");
            assertThat(stats.error()).isFalse();
            assertThat(stats.text()).contains("msc_status").contains("world_render");
            McpPipe.Answer slice = mcp.call("world_slice", "{\"region\":\"0,60,0 1,61,1\",\"maxChars\":\"500\"}");
            assertThat(slice.error()).as(slice.text()).isFalse();
        }
    }

    @Test
    void whatTheSdkStillAnswersItselfIsAProtocolErrorAndTheServerGoesOn(@TempDir Path ws) throws Exception {
        try (CoreHarness h = new CoreHarness(ws); McpPipe mcp = new McpPipe(h.core.registry())) {
            // An unknown tool name and an arguments value that is not an object are JSON-RPC errors, as the MCP spec
            // has them: the client only offers listed tools and always sends an object.
            assertThat(mcp.call("no_such_tool", "{}").rpcError()).isNotNull();
            assertThat(mcp.call("msc_status", "\"stats\"").rpcError()).isNotNull();
            assertThat(mcp.call("msc_status", "null").text()).startsWith("msc-core ");
            assertThat(mcp.request("ping", "{}").has("result")).isTrue();
        }
    }

    /**
     * The arguments JSON: every required argument with a valid string value, {@code name} set to {@code value}
     * (raw JSON), or left out when {@code value} is null.
     */
    private static String arguments(JsonObject properties, List<String> required, String name, String value) {
        List<String> parts = new ArrayList<>();
        for (String r : required) {
            if (!r.equals(name)) {
                boolean array = properties.getAsJsonObject(r).get("type").getAsString().equals("array");
                parts.add("\"" + r + "\":" + (array ? "[\"x\"]" : "\"x\""));
            }
        }
        if (value != null) {
            parts.add("\"" + name + "\":" + value);
        }
        return "{" + String.join(",", parts) + "}";
    }
}
