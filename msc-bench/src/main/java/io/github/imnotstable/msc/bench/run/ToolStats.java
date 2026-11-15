package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.JsonNumbers.Range;
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

    /*
     * The values results.jsonl keeps, the one rule its writer (ToolLogReader) and its reader (ResultsFile) share
     * (#602): a tool log line that would take a total outside them is left out, never written for report to refuse.
     */
    /** The range of {@link #calls} and {@link #errors}, and of a tool's {@link PerTool#errors}. */
    public static final Range COUNT = JsonNumbers.COUNT;
    /** The range of the tokens, characters and milliseconds, in all and per tool. */
    public static final Range AMOUNT = JsonNumbers.AMOUNT;

    /**
     * Totals for one tool.
     *
     * @param tokens text and image tokens the tool returned
     */
    public record PerTool(int calls, int errors, long tokens, long durationMs) {

        /** The range of {@link #calls}: a tool is listed only once it was called. */
        public static final Range CALLS = JsonNumbers.CALLS;

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
