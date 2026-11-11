package io.github.imnotstable.msc.bench.run;

/**
 * Totals from msc-core's tool log for one run (see ADR 0004). All 0 in baseline mode.
 *
 * @param calls         tool calls
 * @param errors        calls that returned an error
 * @param resultTokens  estimated tokens of the text the tools returned to the model
 * @param imageTokens   estimated tokens of images the tools returned (rendered views)
 * @param resultChars   characters the tools returned to the model
 * @param durationMs    time spent inside the core
 */
public record ToolStats(int calls, int errors, long resultTokens, long imageTokens, long resultChars, long durationMs) {

    public static final ToolStats NONE = new ToolStats(0, 0, 0, 0, 0, 0);
}
