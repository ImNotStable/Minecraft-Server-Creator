package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.AgentResult;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ToolStats;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReportWriterTest {

    private static RunRecord record(String mode, boolean passed, long wallMs, long output, int calls) {
        return new RunRecord("build-hut", "build", mode, 1, "2026-11-10T13:00:00Z", passed, wallMs, false, 0, null,
                new AgentResult(true, false, 5, 1000, 1000, 0, 0, output, 0.25, "claude-opus-4-7", List.of()),
                new ToolStats(calls, 0, 50, 0, 200, 10,
                        calls == 0 ? Map.of() : Map.of("world_render", new ToolStats.PerTool(calls, 0, 3000, 1200))),
                List.of(new CheckResult("walls", passed, passed ? "196/196 blocks match" : "10/196 blocks match")));
    }

    @Test
    void summarisesModesAndComparesWithBaseline() {
        String md = ReportWriter.render(List.of(
                record("baseline", false, 200_000, 9000, 0),
                record("toolset", true, 100_000, 4000, 12)));

        assertThat(md)
                .contains("Models: claude-opus-4-7.")
                .contains("| baseline | 0/1 (0%) | 200s | 5 | 10k | 9000 | $0.25 | 0.0 |")
                .contains("| toolset | 1/1 (100%) | 100s | 5 | 5000 | 4000 | $0.25 | 12.0 |")
                .contains("| build-hut | build | 0/1, 200s, 10k | 1/1, 100s, 5000 | -50% | -50% |")
                .contains("| world_render | 12 | 0 | 3000 | 250 | 1s | 0.1s |")
                .contains("- build-hut (baseline #1): walls: 10/196 blocks match");
    }

    /** A run whose agent left no usage report: timed out (wallMs set) or never started (wallMs 0, error). */
    private static RunRecord unmeasured(String mode, long wallMs, boolean timedOut, String error) {
        return new RunRecord("build-hut", "build", mode, 2, "2026-11-10T13:00:00Z", false, wallMs, timedOut, -1, error,
                AgentResult.UNKNOWN, ToolStats.NONE, List.of());
    }

    @Test
    void leavesUnmeasuredRunsOutOfUsageFigures() {
        String md = ReportWriter.render(List.of(
                record("baseline", true, 200_000, 9000, 0),
                record("toolset", true, 100_000, 4000, 12),
                unmeasured("toolset", 300_000, true, null),
                unmeasured("toolset", 0, false, "setup failed")));

        assertThat(md)
                .contains("| toolset | 1/3 (33%) | 200s (2 of 3) | 5 (1 of 3) | 5000 (1 of 3) | 4000 (1 of 3)"
                        + " | $0.25 (1 of 3) | 6.0 (2 of 3) |")
                .contains("| build-hut | build | 1/1, 200s, 10k | 1/3, 200s (2 of 3), 5000 (1 of 3) | +0% | -50% |");
    }

    @Test
    void showsDashWhenNoRunMeasuredAFigure() {
        String md = ReportWriter.render(List.of(
                record("baseline", true, 200_000, 9000, 0),
                unmeasured("toolset", 0, false, "setup failed")));

        assertThat(md)
                .contains("| toolset | 0/1 (0%) | - | - | - | - | - | - |")
                .contains("| build-hut | build | 1/1, 200s, 10k | 0/1, -, - | - | - |");
    }

    /**
     * An error-free record with wallMs 0 (written before wallMs had a floor of 1): the Summary and the Failures line
     * agree that its agent never ran (#556).
     */
    @Test
    void summaryAndFailuresAgreeOnWhetherTheAgentRan() {
        String md = ReportWriter.render(List.of(
                record("baseline", true, 200_000, 9000, 0),
                record("toolset", true, 100_000, 4000, 12),
                unmeasured("toolset", 0, false, null)));

        assertThat(md)
                .contains("| toolset | 1/2 (50%) | 100s (1 of 2) |")
                .contains(" | 12.0 (1 of 2) |")
                .contains("- build-hut (toolset #2): ")
                .doesNotContain("agent output not parsed");
    }

    /** One tool's totals in one run: calls, errors, tokens and milliseconds. */
    private record ToolUse(String name, ToolStats.PerTool totals) {
    }

    private static ToolUse tool(String name, int calls, int errors, long tokens, long ms) {
        return new ToolUse(name, new ToolStats.PerTool(calls, errors, tokens, ms));
    }

    /**
     * A run of {@code task} whose agent reported its usage when {@code turns} is above 0 (else it timed out); its
     * tool totals are the sums of {@code tools}, and its one check {@code "name: detail"} passes or fails with it.
     */
    private static RunRecord run(String task, String mode, int repeat, boolean passed, long wallMs, int turns,
            long input, long output, double cost, String check, ToolUse... tools) {
        AgentResult agent = turns == 0 ? AgentResult.UNKNOWN
                : new AgentResult(true, false, turns, 0, input, 0, 0, output, cost, "m-1", List.of());
        Map<String, ToolStats.PerTool> perTool = Arrays.stream(tools)
                .collect(Collectors.toMap(ToolUse::name, ToolUse::totals));
        ToolStats stats = new ToolStats(perTool.values().stream().mapToInt(ToolStats.PerTool::calls).sum(),
                perTool.values().stream().mapToInt(ToolStats.PerTool::errors).sum(),
                perTool.values().stream().mapToLong(ToolStats.PerTool::tokens).sum(), 0, 0,
                perTool.values().stream().mapToLong(ToolStats.PerTool::durationMs).sum(), perTool);
        List<CheckResult> checks = check == null ? List.of()
                : List.of(new CheckResult(check.substring(0, check.indexOf(':')), passed,
                        check.substring(check.indexOf(':') + 2)));
        return new RunRecord(task, task.equals("alpha") ? "build" : "code", mode, repeat, "2026-11-10T13:00:00Z",
                passed, wallMs, turns == 0, turns == 0 ? -1 : 0, null, agent, stats, checks);
    }

    /**
     * #616: every figure of a report over several runs per mode (4 each; all measured in baseline, 3 of 4 in toolset,
     * so medians of even and odd counts) and several tools spread over several runs. The expected report is worked
     * out by hand: the median of an even count is the mean of the middle two, Total cost is the sum over the measured
     * runs, a tool's totals are summed over every run that called it, and Tool cost lists most tokens first.
     */
    @Test
    void everyFigureOverSeveralRunsAndTools() {
        String md = ReportWriter.render(List.of(
                run("alpha", "baseline", 1, true, 100_000, 5, 20_000, 2_000, 1.10, "walls: 196/196 blocks match"),
                run("alpha", "toolset", 1, true, 50_000, 6, 10_000, 800, 0.90, "walls: 196/196 blocks match",
                        tool("world_render", 2, 0, 3000, 2000), tool("build_batch", 1, 0, 200, 500)),
                run("alpha", "baseline", 2, false, 300_000, 9, 40_000, 6_000, 2.20, "walls: 10/196 blocks match"),
                run("alpha", "toolset", 2, true, 70_000, 8, 14_000, 1_500, 1.30, "walls: 196/196 blocks match",
                        tool("world_render", 1, 0, 1500, 1000), tool("build_batch", 3, 1, 900, 3000)),
                run("beta", "baseline", 1, true, 200_000, 7, 8_000, 1_200, 0.40, "log: ready"),
                run("beta", "toolset", 1, false, 40_000, 4, 5_000, 700, 0.50, "log: no line says ready",
                        tool("server_logs", 4, 0, 400, 800)),
                run("beta", "baseline", 2, true, 150_000, 3, 11_000, 3_000, 0.60, "log: ready"),
                run("beta", "toolset", 2, false, 600_000, 0, 0, 0, 0, null,
                        tool("build_batch", 3, 0, 600, 1500))));

        assertThat(md).isEqualTo("""
                # Benchmark report

                Runs: 8. Tasks: 2. Modes: baseline, toolset. Models: m-1.

                ## Summary

                | Mode | Pass rate | Median time | Median turns | Median tokens | Median output tokens | Total cost | Tool calls/run |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | baseline | 3/4 (75%) | 175s | 6 | 18k | 2500 | $4.30 | 0.0 |
                | toolset | 2/4 (50%) | 60s | 6 (3 of 4) | 11k (3 of 4) | 800 (3 of 4) | $2.70 (3 of 4) | 3.5 |

                ## Per task

                Each cell: passes/runs, median time, median tokens. (k of n) marks a figure from the k runs that \
                measured it; - means none did. Change columns compare toolset with baseline (negative is better).

                | Task | Category | baseline | toolset | Time change | Token change |
                | --- | --- | --- | --- | --- | --- |
                | alpha | build | 1/2, 200s, 34k | 2/2, 60s, 13k | -70% | -61% |
                | beta | code | 2/2, 175s, 12k | 0/2, 320s, 5700 (1 of 2) | +83% | -51% |

                ## Tool cost

                All runs together, most tokens first. Tokens are what the tool returned to the model.

                | Tool | Calls | Errors | Tokens | Tokens/call | Time | Time/call |
                | --- | --- | --- | --- | --- | --- | --- |
                | world_render | 3 | 0 | 4500 | 1500 | 3s | 1.0s |
                | build_batch | 7 | 1 | 1700 | 242 | 5s | 0.7s |
                | server_logs | 4 | 0 | 400 | 100 | 1s | 0.2s |

                ## Failures

                - alpha (baseline #2): walls: 10/196 blocks match
                - beta (toolset #1): log: no line says ready
                - beta (toolset #2): timed out after 600s; agent output not parsed
                """);
    }

    @Test
    void worksWithOneMode() {
        String md = ReportWriter.render(List.of(record("baseline", true, 1000, 10, 0)));

        assertThat(md).contains("| baseline | 1/1 (100%)").doesNotContain("Time change").doesNotContain("## Failures").doesNotContain("## Tool cost");
    }
}
