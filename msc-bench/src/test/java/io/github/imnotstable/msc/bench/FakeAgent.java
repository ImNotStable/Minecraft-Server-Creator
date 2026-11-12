package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for Claude Code in runner tests: writes the prompt to {@code done.txt}, logs one tool
 * call when {@code MSC_TOOL_LOG} is set, and prints a Claude-style JSON result (a usage-limit error when
 * {@code FAKE_USAGE_LIMIT} is set).
 */
public final class FakeAgent {

    private FakeAgent() {
    }

    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of("done.txt"), args[0]);
        String toolLog = System.getenv("MSC_TOOL_LOG");
        if (toolLog != null) {
            Files.writeString(Path.of(toolLog),
                    "{\"v\":1,\"tool\":\"world.fill\",\"ok\":true,\"durationMs\":5,\"resultChars\":40,\"resultTokens\":10}\n");
        }
        // FAKE_MCP_COUNTER: count runs in that file and report the msc server connected from the second run on.
        String counter = System.getenv("FAKE_MCP_COUNTER");
        String servers = "[]";
        if (counter != null) {
            Path file = Path.of(counter);
            int runs = (Files.exists(file) ? Integer.parseInt(Files.readString(file).strip()) : 0) + 1;
            Files.writeString(file, Integer.toString(runs));
            servers = runs >= 2 ? "[{\"name\":\"msc\",\"status\":\"connected\"}]" : "[]";
        }
        if (System.getenv("FAKE_USAGE_LIMIT") != null) {
            // UTF-8 like Claude Code, whatever the platform's console charset is.
            new PrintStream(System.out, true, StandardCharsets.UTF_8).println("[{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":1,"
                    + "\"result\":\"API Error: 429 You're out of extra usage · resets 2:50am\"}]");
            return;
        }
        int output = toolLog != null ? 100 : 300;
        System.out.println("[{\"type\":\"system\",\"subtype\":\"init\",\"mcp_servers\":" + servers + "},");
        System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":4,"
                + "\"duration_api_ms\":1200,\"total_cost_usd\":0.05,\"usage\":{\"input_tokens\":1000,"
                + "\"cache_creation_input_tokens\":200,\"cache_read_input_tokens\":3000,\"output_tokens\":" + output + "}}]");
    }
}
