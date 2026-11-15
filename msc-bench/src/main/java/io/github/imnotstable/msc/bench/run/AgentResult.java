package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.JsonNumbers.Range;
import java.util.List;

/**
 * What the agent reported about its own run, taken from Claude Code's {@code --output-format json}
 * result. All counts are 0 when the output could not be parsed.
 *
 * @param parsed              whether a result object was found
 * @param isError             the agent ended in an error
 * @param turns               agent turns
 * @param apiDurationMs       time spent waiting on the model
 * @param inputTokens         uncached input tokens
 * @param cacheCreationTokens input tokens written to the prompt cache
 * @param cacheReadTokens     input tokens read from the prompt cache
 * @param outputTokens        output tokens
 * @param costUsd             cost as reported by the agent
 * @param model               model id(s) the agent used, comma-separated, or empty
 * @param mcpConnected        MCP servers the agent's init event reported as connected
 * @param mcpNotConnected     the other MCP servers the init event listed, each as {@code name (status)}, such as
 *                            {@code msc (failed)} or {@code msc (pending)}
 */
public record AgentResult(
        boolean parsed,
        boolean isError,
        int turns,
        long apiDurationMs,
        long inputTokens,
        long cacheCreationTokens,
        long cacheReadTokens,
        long outputTokens,
        double costUsd,
        String model,
        List<String> mcpConnected,
        List<String> mcpNotConnected) {

    public static final AgentResult UNKNOWN = new AgentResult(false, false, 0, 0, 0, 0, 0, 0, 0, "", List.of());

    /*
     * The values results.jsonl keeps, the one rule its writer (ClaudeResultParser) and its reader (ResultsFile) share
     * (#602): an agent number outside them makes the output unparsed, never a line report refuses.
     */
    /** The range of {@link #turns}. */
    public static final Range TURNS = JsonNumbers.COUNT;
    /** The range of {@link #apiDurationMs} and of each token count. */
    public static final Range AMOUNT = JsonNumbers.AMOUNT;
    /** The range of {@link #costUsd}. */
    public static final Range COST = JsonNumbers.COST;

    public AgentResult {
        model = model == null ? "" : model;
        mcpConnected = mcpConnected == null ? List.of() : List.copyOf(mcpConnected);
        mcpNotConnected = mcpNotConnected == null ? List.of() : List.copyOf(mcpNotConnected);
    }

    /** A result whose init event listed no server that was not connected. */
    public AgentResult(boolean parsed, boolean isError, int turns, long apiDurationMs, long inputTokens,
            long cacheCreationTokens, long cacheReadTokens, long outputTokens, double costUsd, String model,
            List<String> mcpConnected) {
        this(parsed, isError, turns, apiDurationMs, inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens,
                costUsd, model, mcpConnected, List.of());
    }

    /**
     * Why the MCP server {@code required} was not there when the agent started, or null when the init event reported
     * it connected. Names the status the init event gave it, or says that the output reported no MCP server at all:
     * Claude Code's {@code --output-format json} in some versions prints only the result, without the init event,
     * and {@code stream-json --verbose} always has it (ADR 0044).
     */
    public String mcpProblem(String required) {
        if (mcpConnected.contains(required)) {
            return null;
        }
        for (String server : mcpNotConnected) {
            if (server.startsWith(required + " (")) {
                return "MCP server " + server.replace(" (", " was ").replace(")", "") + " when the agent started";
            }
        }
        if (mcpConnected.isEmpty() && mcpNotConnected.isEmpty()) {
            return "MCP server " + required + " was not reported: the agent's output names no MCP server (run "
                    + "Claude Code with --output-format stream-json --verbose, whose init event lists them)";
        }
        return "MCP server " + required + " was not among the agent's MCP servers when it started";
    }

    /** Every token the model read or wrote, cached or not. */
    public long totalTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens + outputTokens;
    }
}
