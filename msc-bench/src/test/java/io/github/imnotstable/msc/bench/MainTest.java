package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.imnotstable.msc.bench.run.RunIsolation;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MainTest {

    @TempDir
    Path dir;

    @Test
    void checkStartsTheWorkspacesServerForRconChecksAndStopsIt() throws IOException {
        Files.createDirectories(dir.resolve("tasks/world"));
        Files.writeString(dir.resolve("tasks/world/task.json"), """
                {"category":"world","title":"needs a server",
                 "checks":[{"type":"rcon","command":"list","expect":"players"}]}
                """);
        Files.writeString(dir.resolve("tasks/world/prompt.md"), "look");
        TaskDef task = TaskLoader.loadAll(dir.resolve("tasks")).getFirst();

        Path server = Files.createDirectories(dir.resolve("workspace/server"));
        Files.writeString(server.resolve("paper.jar"), "stands in for paper");
        Files.writeString(server.resolve("eula.txt"), "eula=true");
        Path home = Files.createDirectories(dir.resolve("home/.msc"));
        Files.writeString(home.resolve("eula.json"), """
                {"accepted":true,"eula":"https://aka.ms/MinecraftEULA","acceptedBy":"owner","acceptedAt":"2026-11-12T00:00:00Z"}
                """);
        try (HandedPort rconPort = HandedPort.reserve(); ClosedPort gamePort = ClosedPort.reserve()) {
            checkWithServer(task, home, rconPort.port(), gamePort.port());
        }
    }

    private void checkWithServer(TaskDef task, Path home, int port, int gamePort) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
        String classpath = System.getProperty("java.class.path").replace("\\", "\\\\");
        Files.writeString(dir.resolve("bench.json"), """
                {"server":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeRconMain","%d","pw"],
                 "rconPort":%d,"serverPort":%d,"rconPassword":"pw","startTimeoutSeconds":30}}
                """.formatted(java, classpath, port, port, gamePort));
        BenchConfig config = BenchConfig.load(dir.resolve("bench.json"), home.getParent());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = Main.check(task, dir.resolve("workspace"), config, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("PASS");
        assertThat(exit).isZero();
        assertThat(ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains("FakeRconMain"))).isEmpty();
    }

    @Test
    void checkResetsWhatTheTaskDeclaresAfterStoppingTheServerThatHoldsIt() throws IOException {
        // #495: re-checking an attempt's workspace, whose saved motd is the one its last checks set, with that
        // server still running: check stops it, deletes the config, and starts the workspace's server on the default.
        Files.createDirectories(dir.resolve("tasks/motd"));
        Files.writeString(dir.resolve("tasks/motd/task.json"), """
                {"category":"code","title":"motd","resetBeforeChecks":{"delete":["server/%s"]},
                 "checks":[{"type":"rcon","command":"benchmotd","expect":"^Welcome to MSC$"}]}
                """.formatted(FakeMotdMain.CONFIG));
        Files.writeString(dir.resolve("tasks/motd/prompt.md"), "motd");
        TaskDef task = TaskLoader.loadAll(dir.resolve("tasks")).getFirst();
        Path server = Files.createDirectories(dir.resolve("workspace/server"));
        Files.writeString(server.resolve("paper.jar"), "stands in for paper");
        Files.writeString(server.resolve("eula.txt"), "eula=true");
        Path saved = server.resolve(FakeMotdMain.CONFIG);
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "motd: Hello builders\n");
        Path home = Files.createDirectories(dir.resolve("home/.msc"));
        Files.writeString(home.resolve("eula.json"), """
                {"accepted":true,"eula":"https://aka.ms/MinecraftEULA","acceptedBy":"owner","acceptedAt":"2026-11-12T00:00:00Z"}
                """);
        String[] inMemory = {"Hello builders"};
        FakeRconServer[] holder = new FakeRconServer[1];
        holder[0] = new FakeRconServer("pw", command -> {
            if (!command.equals("stop")) {
                return FakeMotdMain.reply(command, inMemory, dir.resolve("running-config.yml"));
            }
            try {
                holder[0].close();
            } catch (IOException e) {
                // test ends either way
            }
            return "Stopping the server";
        });
        String java = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
        String classpath = System.getProperty("java.class.path").replace("\\", "\\\\");
        try (FakeRconServer running = holder[0]; ClosedPort gamePort = ClosedPort.reserve()) {
            Files.writeString(dir.resolve("bench.json"), """
                    {"server":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeMotdMain","%d","pw"],
                     "rconPort":%d,"serverPort":%d,"rconPassword":"pw","startTimeoutSeconds":60}}
                    """.formatted(java, classpath, running.port(), running.port(), gamePort.port()));
            BenchConfig config = BenchConfig.load(dir.resolve("bench.json"), home.getParent());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            int exit = Main.check(task, dir.resolve("workspace"), config, new PrintStream(out, true, StandardCharsets.UTF_8));

            assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("reset before checks: deleted server/"
                    + FakeMotdMain.CONFIG).contains("PASS");
            assertThat(exit).isZero();
            assertThat(running.commands()).containsExactly("stop");
            assertThat(saved).content().contains("Welcome to MSC");
        }
        assertThat(ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains("FakeMotdMain"))).isEmpty();
    }

    @Test
    void checkWithoutBenchJsonFillsTheRunsPlaceholdersFromTheDefaults() throws IOException {
        // #554: without bench.json, check still fills every placeholder a run would, from bench.json's defaults.
        Files.createDirectories(dir.resolve("tasks/t"));
        Files.writeString(dir.resolve("tasks/t/task.json"), """
                {"category":"code","title":"t","checks":[{"type":"command","outputMatches":"by default","run":[
                  "{java}","-cp","%s","io.github.imnotstable.msc.bench.CatFile",
                  "{attemptDir}/workspace/{minecraftVersion}-{rconPort}-{serverPort}-{corePort}.txt"]}]}
                """.formatted(System.getProperty("java.class.path").replace("\\", "\\\\")));
        Files.writeString(dir.resolve("tasks/t/prompt.md"), "p");
        TaskDef task = TaskLoader.loadAll(dir.resolve("tasks")).getFirst();
        Path workspace = Files.createDirectories(dir.resolve("attempt/workspace"));
        Files.writeString(workspace.resolve("26.3-25576-25566-25590.txt"), "by default");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = Main.check(task, workspace, null, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(exit).as(out.toString(StandardCharsets.UTF_8)).isZero();
    }

    @Test
    void runRefusesARunFolderInsideAProject() throws IOException {
        Files.writeString(dir.resolve("CLAUDE.md"), "project instructions");
        Path runs = dir.resolve("msc-bench/runs");
        Files.createDirectories(dir.resolve("tasks/t"));
        Files.writeString(dir.resolve("tasks/t/task.json"), """
                {"category":"code","title":"t","checks":[{"type":"file","path":"a"}]}
                """);
        Files.writeString(dir.resolve("tasks/t/prompt.md"), "p");
        Files.writeString(dir.resolve("bench.json"), "{}");

        Throwable refused = catchThrowable(() -> Main.run(new String[] {"run", "--out", runs.toString(), "--tasks-dir",
                dir.resolve("tasks").toString(), "--config", dir.resolve("bench.json").toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));

        assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("CLAUDE.md");
        assertThat(runs).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "two", "''"})
    void runRefusesARepeatBelowOneLikeOtherBadArguments(String repeat) {
        Path runs = dir.resolve("runs");

        // IllegalArgumentException is what main turns into exit code 2.
        Throwable refused = catchThrowable(() -> Main.run(new String[] {"run", "--repeat", repeat, "--out",
                runs.toString()}, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));

        assertThat(refused).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("--repeat " + repeat);
        assertThat(runs).doesNotExist();
        assertThat(Main.repeat(" 3 ")).isEqualTo(3);
    }

    @Test
    void reportOnAFolderWithoutResultsSaysSo() {
        // An IOException is what main turns into exit code 2.
        Throwable refused = catchThrowable(() -> Main.run(new String[] {"report", dir.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));

        assertThat(refused).isInstanceOf(IOException.class)
                .hasMessageContaining("has no " + BenchRunner.RESULTS_FILE, "the run folder that `run` prints");
        assertThat(dir.resolve("report.md")).doesNotExist();
    }

    @Test
    void modeAndTaskNamesAreReadWithoutSurroundingSpaces() {
        // "--modes baseline, baseline" passed the repeat check as two different names.
        assertThat(RunSelection.names("--modes", "baseline , toolset", "mode")).containsExactly("baseline", "toolset");
    }

    @ParameterizedTest
    @CsvSource({"~, ''", "~/.msc/bench-runs/x, .msc/bench-runs/x", "~\\.msc\\x, .msc/x", "~/, ''"})
    void aLeadingTildeIsTheHomeFolder(String arg, String underHome) {
        // Gradle's --args="report ~/.msc/..." passes the tilde unexpanded; Path.of read it as msc-bench/~/.msc/...
        Path home = dir.resolve("home");
        Path expected = underHome.isEmpty() ? home : home.resolve(underHome);

        assertThat(Main.expandHome(arg, home).toString().replace('\\', '/'))
                .isEqualTo(expected.toString().replace('\\', '/'));
    }

    @ParameterizedTest
    @CsvSource({"~user/x", "runs/~/x", "x~"})
    void otherTildesAreLeftAlone(String arg) {
        assertThat(Main.expandHome(arg, dir.resolve("home"))).isEqualTo(Path.of(arg));
    }

    @Test
    void theUsageNamesTheDefaultRunFolder() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Main.run(new String[0], new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("--out DIR, default " + RunIsolation.DEFAULT_RUNS_SHOWN);
    }

    /** Every doc that names the run folder names the code's default, and none the old one inside the repository. */
    @Test
    void theDocsNameTheDefaultRunFolder() throws IOException {
        Pattern runFolder = Pattern.compile("[~\\w$./-]*(?:bench-runs|msc-bench/runs)\\b");
        // The docs are the build's runFolderDocs set, which makes them inputs of this test too.
        assertThat(RepoFiles.list("runFolderDocs")).contains("msc-bench/README.md", "docs/wiki/Benchmark.md");
        for (String doc : RepoFiles.list("runFolderDocs")) {
            String text = Files.readString(RepoFiles.path("runFolderDocs", doc));
            assertThat(text).as(doc).contains(RunIsolation.DEFAULT_RUNS_SHOWN).contains(RunIsolation.TEMP_RUNS_NAME);
            Matcher named = runFolder.matcher(text);
            while (named.find()) {
                String mention = named.group();
                boolean history = text.substring(Math.max(0, named.start() - 40), named.start()).contains("the default was");
                assertThat(mention.startsWith(RunIsolation.DEFAULT_RUNS_SHOWN) || mention.endsWith(RunIsolation.TEMP_RUNS_NAME)
                        || history).as(doc + ": " + mention).isTrue();
            }
        }
    }
}
