package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.LongOutput;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.check.Checks;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #535: a check that fails with long multi-line output keeps the output's last line, the one that names the failure,
 * in the CheckResult stored in results.jsonl, on the terminal's progress line and in report.md.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class LongCheckOutputTest {

    private static final String RUN = "[\"{java}\",\"-cp\",\"{classpath}\",\"io.github.imnotstable.msc.bench.LongOutput\",";
    private static final AgentResult OK = new AgentResult(true, false, 3, 1000, 10, 0, 0, 5, 0.1, "m", List.of());

    @TempDir
    Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"command\",\"run\":" + RUN + "\"exit\"]}",
        "{\"type\":\"command\",\"name\":\"a check name long enough to have pushed the end out of the old budget before"
                + " #535\",\"run\":" + RUN + "\"exit\"]}",
        "{\"type\":\"player\",\"jar\":\"game.jar\",\"command\":" + RUN + "\"exit\"],\"expect\":[\"x\"],"
                + "\"timeoutSeconds\":120}",
        "{\"type\":\"player\",\"jar\":\"game.jar\",\"command\":" + RUN + "\"server\"],\"console\":[\"go\"],"
                + "\"expect\":[\"never printed\"],\"timeoutSeconds\":120}"})
    void longOutputKeepsItsLastLineEverywhere(String json) throws IOException {
        Files.writeString(workspace.resolve("game.jar"), "stands in for the server jar");
        CheckResult result = Checks.parse(UserJson.of(JsonParser.parseString(json).getAsJsonObject(), "check.",
                Checks.KEYS), workspace).run(context());

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).endsWith(LongOutput.LAST).hasSizeLessThanOrEqualTo(CheckResult.MAX_DETAIL);
        RunRecord stored = stored(record(result));
        assertThat(stored.checks().getFirst().detail()).isEqualTo(result.detail());
        assertThat(BenchRunner.progressLine(stored)).endsWith(LongOutput.LAST + ")");
        assertThat(ReportWriter.render(List.of(stored))).contains(LongOutput.LAST + "\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"exit 1: BUILD FAILED", "server stopped: Failed to start the minecraft server"})
    void shortDetailIsUnchanged(String detail) throws IOException {
        RunRecord stored = stored(record(CheckResult.fail("check", detail)));

        assertThat(stored.checks().getFirst().detail()).isEqualTo(detail);
        assertThat(BenchRunner.progressLine(stored)).endsWith("; check: " + detail + ")");
        assertThat(ReportWriter.render(List.of(stored))).contains("; check: " + detail + "\n");
    }

    private static RunRecord record(CheckResult check) {
        return new RunRecord("t", "c", "b", 1, "2026-01-01T00:00:00Z", false, 5000, false, 1, null, OK,
                ToolStats.NONE, List.of(check));
    }

    /** {@code record} written to results.jsonl and read back, as the report sees it. */
    private RunRecord stored(RunRecord record) throws IOException {
        Path file = workspace.resolve(ResultsFile.NAME);
        Files.deleteIfExists(file);
        ResultsFile.append(file, record);
        return ResultsFile.read(file, w -> {
        }).getFirst();
    }

    private CheckContext context() {
        return new CheckContext() {
            @Override
            public Path workspace() {
                return workspace;
            }

            @Override
            public Map<String, String> placeholders() {
                return Map.of("java", ProcessHandle.current().info().command().orElse("java"),
                        "classpath", System.getProperty("java.class.path"), "serverPort", "1");
            }

            @Override
            public RconClient rcon() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
