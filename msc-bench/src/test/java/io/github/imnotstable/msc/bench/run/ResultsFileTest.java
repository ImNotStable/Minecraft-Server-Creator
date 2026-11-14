package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The strict reader takes back every record the writer writes, every field set; refusals are in CommandLineTest. */
class ResultsFileTest {

    @TempDir
    Path dir;

    @Test
    void aWrittenRecordReadsBackEqual() throws IOException {
        RunRecord full = new RunRecord("build-hut", "build", "toolset", 2, "2026-11-14T10:00:00Z", false, 61_000,
                true, -1, "server did not start \u2014 caf\u00e9",
                new AgentResult(true, true, 12, 50_000, 1_000, 2_000, 300_000, 4_000, 1.25,
                        "claude-model", List.of("msc")),
                new ToolStats(5, 1, 7_000, 1_500, 28_000, 900,
                        Map.of("view", new ToolStats.PerTool(3, 1, 8_000, 600))),
                List.of(CheckResult.pass("file", "ok"), CheckResult.fail("rcon", "no answer")));
        RunRecord bare = new RunRecord("a", "code", "baseline", 1, null, true, 0, false, 0, null, null, null, null);
        Path file = dir.resolve(ResultsFile.NAME);

        ResultsFile.append(file, full);
        ResultsFile.append(file, bare);

        assertThat(ResultsFile.read(file)).containsExactly(full,
                new RunRecord("a", "code", "baseline", 1, "", true, 0, false, 0, null, null, null, null));
    }
}
