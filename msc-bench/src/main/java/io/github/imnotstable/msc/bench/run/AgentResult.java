package io.github.imnotstable.msc.bench.run;

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
        double costUsd) {

    public static final AgentResult UNKNOWN = new AgentResult(false, false, 0, 0, 0, 0, 0, 0, 0);

    /** Every token the model read or wrote, cached or not. */
    public long totalTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens + outputTokens;
    }
}
