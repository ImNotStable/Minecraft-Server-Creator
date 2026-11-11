package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for Claude Code in runner tests: writes the prompt to {@code done.txt}, logs one tool
 * call when {@code MSC_TOOL_LOG} is set, and prints a Claude-style JSON result.
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
        int output = toolLog != null ? 100 : 300;
        System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":4,"
                + "\"duration_api_ms\":1200,\"total_cost_usd\":0.05,\"usage\":{\"input_tokens\":1000,"
                + "\"cache_creation_input_tokens\":200,\"cache_read_input_tokens\":3000,\"output_tokens\":" + output + "}}");
    }
}
