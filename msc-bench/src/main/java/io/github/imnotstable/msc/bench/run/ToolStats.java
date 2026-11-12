package io.github.imnotstable.msc.bench.run;

import java.util.Map;
import java.util.TreeMap;

/**
 * Totals from msc-core's tool log for one run (see ADR 0004). All 0 in baseline mode.
 *
 * @param calls         tool calls
 * @param errors        calls that returned an error
 * @param resultTokens  estimated tokens of the text the tools returned to the model
 * @param imageTokens   estimated tokens of images the tools returned (rendered views)
 * @param resultChars   characters the tools returned to the model
 * @param durationMs    time spent inside the core
 * @param perTool       the same totals per MCP tool name
 */
public record ToolStats(
        int calls,
        int errors,
        long resultTokens,
        long imageTokens,
        long resultChars,
        long durationMs,
        Map<String, PerTool> perTool) {

    public static final ToolStats NONE = new ToolStats(0, 0, 0, 0, 0, 0, Map.of());

    /**
     * Totals for one tool.
     *
     * @param tokens text and image tokens the tool returned
     */
    public record PerTool(int calls, int errors, long tokens, long durationMs) {

        PerTool plus(PerTool other) {
            return new PerTool(calls + other.calls, errors + other.errors, tokens + other.tokens,
                    durationMs + other.durationMs);
        }
    }

    public ToolStats {
        perTool = perTool == null ? Map.of() : Map.copyOf(perTool);
    }

    /** Adds per-tool totals of several runs into one map. */
    public static Map<String, PerTool> sumPerTool(Iterable<ToolStats> stats) {
        Map<String, PerTool> out = new TreeMap<>();
        for (ToolStats s : stats) {
            s.perTool().forEach((tool, t) -> out.merge(tool, t, PerTool::plus));
        }
        return out;
    }
}
