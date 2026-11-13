package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
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
        int port = freePort();
        try (ClosedPort gamePort = ClosedPort.reserve()) {
            checkWithServer(task, home, port, gamePort.port());
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
    void runRefusesARunFolderInsideAProject() throws IOException {
        Files.writeString(dir.resolve("CLAUDE.md"), "project instructions");
        Path runs = dir.resolve("msc-bench/runs");

        Throwable refused = catchThrowable(() -> Main.run(new String[] {"run", "--out", runs.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));

        assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("CLAUDE.md");
        assertThat(runs).doesNotExist();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
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
