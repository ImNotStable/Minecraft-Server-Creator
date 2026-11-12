package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.AgentResult;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ToolStats;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportWriterTest {

    private static RunRecord record(String mode, boolean passed, long wallMs, long output, int calls) {
        return new RunRecord("build-hut", "build", mode, 1, "2026-11-10T13:00:00Z", passed, wallMs, false, 0, null,
                new AgentResult(true, false, 5, 1000, 1000, 0, 0, output, 0.25, "claude-opus-4-7"),
                new ToolStats(calls, 0, 50, 0, 200, 10),
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
                .contains("- build-hut (baseline #1): walls: 10/196 blocks match");
    }

    @Test
    void worksWithOneMode() {
        String md = ReportWriter.render(List.of(record("baseline", true, 1000, 10, 0)));

        assertThat(md).contains("| baseline | 1/1 (100%)").doesNotContain("Time change").doesNotContain("## Failures");
    }
}
