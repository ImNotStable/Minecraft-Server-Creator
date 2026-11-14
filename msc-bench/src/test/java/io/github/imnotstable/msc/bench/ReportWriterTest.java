package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.AgentResult;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ToolStats;
import java.util.List;
import java.util.Map;
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

    @Test
    void worksWithOneMode() {
        String md = ReportWriter.render(List.of(record("baseline", true, 1000, 10, 0)));

        assertThat(md).contains("| baseline | 1/1 (100%)").doesNotContain("Time change").doesNotContain("## Failures").doesNotContain("## Tool cost");
    }
}
