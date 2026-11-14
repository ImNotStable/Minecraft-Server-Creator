package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.check.CheckResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

        assertThat(ResultsFile.read(file, w -> {
            throw new AssertionError(w);
        })).containsExactly(full,
                new RunRecord("a", "code", "baseline", 1, "", true, 0, false, 0, null, null, null, null));
    }

    @Test
    void loneSurrogatesInAnyStringAreWrittenAsReplacementCharacters() throws IOException {
        // #462: a cut through an emoji left a lone surrogate, and the UTF-8 writer refused the whole record.
        RunRecord odd = new RunRecord("t\uD83D", "code", "baseline", 1, "", false, 0, false, 1, "x\uDE00",
                null, new ToolStats(1, 0, 0, 0, 0, 0, Map.of("tool\uD800", new ToolStats.PerTool(1, 0, 0, 0))),
                List.of(CheckResult.fail("c\uDBFF", "out \uD83D\uDE00 \uD83D")));
        Path file = dir.resolve(ResultsFile.NAME);

        ResultsFile.append(file, odd);

        RunRecord back = ResultsFile.read(file, w -> {
            throw new AssertionError(w);
        }).getFirst();
        assertThat(back.task()).isEqualTo("t\uFFFD");
        assertThat(back.error()).isEqualTo("x\uFFFD");
        assertThat(back.tools().perTool()).containsOnlyKeys("tool\uFFFD");
        assertThat(back.checks().getFirst()).isEqualTo(CheckResult.fail("c\uFFFD", "out \uD83D\uDE00 \uFFFD"));
    }

    @Test
    void aHalfWrittenLastLineIsLeftOutWithAWarningNamingIt() throws IOException {
        // A run killed while appending leaves the last line cut: in its JSON, or inside a character's bytes.
        RunRecord run = new RunRecord("a", "code", "baseline", 1, "", true, 0, false, 0, null, null, null, null);
        Path file = dir.resolve(ResultsFile.NAME);
        ResultsFile.append(file, run);
        byte[] whole = Files.readAllBytes(file);
        String second = "{\"task\":\"caf\u00E9\u20AC";
        byte[] secondBytes = second.getBytes(StandardCharsets.UTF_8);
        for (int cut : new int[] {1, 9, secondBytes.length - 1, secondBytes.length}) {
            byte[] bytes = new byte[whole.length + cut];
            System.arraycopy(whole, 0, bytes, 0, whole.length);
            System.arraycopy(secondBytes, 0, bytes, whole.length, cut);
            Files.write(file, bytes);
            List<String> warnings = new ArrayList<>();

            assertThat(ResultsFile.read(file, warnings::add)).as("cut %d", cut).containsExactly(run);
            assertThat(warnings).singleElement().asString().contains(file + " line 2 is half-written");
        }
    }

    @Test
    void aBrokenLineAnywhereElseIsStillRefused() throws IOException {
        String run = "{\"task\":\"a\",\"category\":\"code\",\"mode\":\"baseline\",\"repeat\":1,\"passed\":true}";
        Path file = dir.resolve(ResultsFile.NAME);
        for (String text : List.of(run + "\n{\"task\":\n" + run + "\n", run + "\n{\"task\":\n", "{\"task\":",
                run + "\n{\"task\":\"b\"}")) {
            Files.writeString(file, text);
            assertThatThrownBy(() -> ResultsFile.read(file, w -> {
                throw new AssertionError(w);
            })).as(text).isInstanceOf(IOException.class).hasMessageContaining(" line ");
        }
    }
}
