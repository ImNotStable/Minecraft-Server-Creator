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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    private static final RunRecord A = new RunRecord("a", "code", "baseline", 1, "", true, 0, false, 0, null, null,
            null, null);
    private static final RunRecord B = new RunRecord("b", "code", "toolset", 2, "", false, 5, false, 1, "x", null,
            new ToolStats(1, 0, 0, 0, 0, 0, Map.of("view", new ToolStats.PerTool(1, 0, 0, 0))),
            List.of(CheckResult.fail("file", "{\"task\":\"b\"}")));

    @Test
    void anAppendAFullDiskCutsShortLeavesTheFileAsItWas() throws IOException {
        // #477: the disk took part of the line and refused the rest, leaving a half line with no line break.
        Path file = dir.resolve(ResultsFile.NAME);
        ResultsFile.append(file, A);
        byte[] before = Files.readAllBytes(file);

        assertThatThrownBy(() -> ResultsFile.append(file, B, new FullDisk(Set.of(ResultsFile.NAME), 0, false)))
                .isInstanceOf(IOException.class).hasMessage("No space left on device");

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void anAppendThatCannotBeCutBackSaysSo() throws IOException {
        Path file = dir.resolve(ResultsFile.NAME);
        ResultsFile.append(file, A);

        assertThatThrownBy(() -> ResultsFile.append(file, B, new FullDisk(Set.of(ResultsFile.NAME), 0, true)))
                .isInstanceOf(IOException.class)
                .hasMessage("No space left on device; " + file + " could not be cut back to its last whole line either "
                        + "(Input/output error), so its last line is half-written");

        List<String> warnings = new ArrayList<>();
        assertThat(ResultsFile.read(file, warnings::add)).containsExactly(A);
        assertThat(warnings).singleElement().asString().contains("line 2 is half-written");
    }

    @Test
    void aLineAlwaysStartsOnANewLine() throws IOException {
        // A file a kill cut mid-line does not end with a line break: the next line still starts on its own.
        Path file = dir.resolve(ResultsFile.NAME);
        ResultsFile.append(file, A);
        String whole = Files.readString(file);
        Files.writeString(file, whole + "{\"task\":\"cut");

        ResultsFile.append(file, A);

        assertThat(Files.readString(file)).isEqualTo(whole + "{\"task\":\"cut\n" + whole);
    }

    @Test
    void aHalfLineJoinedStraightToTheNextFilesFirstRunIsLeftOutWithAWarning() throws IOException {
        // #477: `cat a b` of a file that ends on a half line glues it to b's first run; every cut of a line, with and
        // without b's byte-order mark, and with B's check detail holding JSON text of its own.
        Path file = dir.resolve(ResultsFile.NAME);
        ResultsFile.append(file, A);
        String first = Files.readString(file);
        Files.delete(file);
        ResultsFile.append(file, B);
        String line = Files.readString(file).strip();
        for (String bom : List.of("", "\uFEFF")) {
            for (int cut = 1; cut < line.length(); cut++) {
                Files.writeString(file, first + line.substring(0, cut) + bom + line + "\n" + line + "\n");
                List<String> warnings = new ArrayList<>();

                assertThat(ResultsFile.read(file, warnings::add)).as("cut %d", cut).containsExactly(A, B, B);
                assertThat(warnings).as("cut %d", cut).singleElement().asString()
                        .contains(file + " line 2 starts with a half-written run");
            }
        }
    }

    @Test
    void twoWholeRunsOnOneLineAreStillRefused() throws IOException {
        Path file = dir.resolve(ResultsFile.NAME);
        ResultsFile.append(file, B);
        String line = Files.readString(file).strip();
        for (String text : List.of(line + line, line + "\uFEFF" + line, "[" + line, "x" + line)) {
            Files.writeString(file, text + "\n");
            assertThatThrownBy(() -> ResultsFile.read(file, w -> {
                throw new AssertionError(w);
            })).as(text).isInstanceOf(IOException.class).hasMessageContaining("line 1: is not valid JSON");
        }
    }

    @Test
    void appendsFromManyThreadsAtOnceKeepEveryLineWhole() throws Exception {
        // #621: each append wrote at the size it had read, so two at once wrote at one offset and a line was lost.
        Path file = dir.resolve(ResultsFile.NAME);
        int threads = 8;
        int each = 25;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                String prefix = "t" + t;
                writers.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < each; i++) {
                        ResultsFile.append(file, record(prefix + "-" + i));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> writer : writers) {
                writer.get();
            }
        }

        assertThat(tasks(file)).hasSize(threads * each).doesNotHaveDuplicates()
                .contains("t0-0", "t7-24");
    }

    @Test
    void appendsFromTwoProcessesAtOnceKeepEveryLineWhole() throws Exception {
        // Two bench runs are two JVMs: the lock must hold between processes too.
        Path file = dir.resolve(ResultsFile.NAME);
        int each = 200;
        String java = ProcessHandle.current().info().command().orElse("java");
        List<Process> processes = new ArrayList<>();
        for (String prefix : List.of("a", "b")) {
            processes.add(new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    AppendRecords.class.getName(), file.toString(), prefix, String.valueOf(each))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        }
        // Meanwhile this process appends too.
        for (int i = 0; i < each; i++) {
            ResultsFile.append(file, record("c-" + i));
        }
        for (Process process : processes) {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        }

        assertThat(tasks(file)).hasSize(3 * each).doesNotHaveDuplicates().contains("a-199", "b-199", "c-199");
    }

    private List<String> tasks(Path file) throws IOException {
        return ResultsFile.read(file, w -> {
            throw new AssertionError(w);
        }).stream().map(RunRecord::task).toList();
    }

    static RunRecord record(String task) {
        return new RunRecord(task, "code", "baseline", 1, "2026-11-15T08:12:30Z", true, 1_000, false, 0, null, null,
                null, List.of(CheckResult.pass("file", "a line long enough to make writes take a while ".repeat(20))));
    }
}
