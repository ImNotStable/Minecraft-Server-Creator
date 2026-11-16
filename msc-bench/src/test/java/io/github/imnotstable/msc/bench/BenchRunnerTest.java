package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.run.Disk;
import io.github.imnotstable.msc.bench.run.FullDisk;
import io.github.imnotstable.msc.bench.run.ResultsFile;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BenchRunnerTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
    private static final String CLASSPATH = System.getProperty("java.class.path").replace("\\", "\\\\");

    /** Marks this test class's hanging agents on their command lines; unique per test JVM. */
    private static final String HANG_MARKER = "hang-" + ProcessHandle.current().pid();

    @TempDir
    Path dir;

    /** Server ports held closed for each test, so whatever runs on the machine cannot answer on them. */
    private ClosedPort rconPort;
    private ClosedPort gamePort;

    @BeforeEach
    void reservePorts() throws IOException {
        rconPort = ClosedPort.reserve();
        gamePort = ClosedPort.reserve();
    }

    @AfterEach
    void releasePorts() throws IOException {
        rconPort.close();
        gamePort.close();
    }

    @Test
    void runsEachModeChecksTheWorkspaceAndWritesResults() throws IOException {
        Path tasks = Files.createDirectories(dir.resolve("tasks"));
        Path task = Files.createDirectories(tasks.resolve("write-file/fixture"));
        Files.writeString(task.resolve("seed.txt"), "from fixture");
        Files.writeString(tasks.resolve("write-file/task.json"), """
                {"category":"code","title":"Write a file","checks":[
                  {"type":"file","path":"done.txt","contains":"Write done.txt in"},
                  {"type":"file","path":"seed.txt","contains":"from fixture"}]}
                """);
        Files.writeString(tasks.resolve("write-file/prompt.md"), "Write done.txt in {workspace}.");
        Files.writeString(dir.resolve("mcp.template.json"), "{\"log\":\"{toolLog}\",\"ws\":\"{workspace}\"}");

        String java = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path").replace("\\", "\\\\");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"],"timeoutSeconds":60},
                 "modes":{"baseline":{},
                          "toolset":{"mcpConfigTemplate":"mcp.template.json","env":{"MSC_TOOL_LOG":"{toolLog}"}}}}
                """.formatted(java.replace("\\", "\\\\"), classpath));

        BenchConfig config = BenchConfig.load(configFile, dir);
        List<TaskDef> taskDefs = TaskLoader.loadAll(tasks);
        Path runDir = dir.resolve("run");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        List<RunRecord> records = new BenchRunner(config, runDir, "Bench preamble.",
                new PrintStream(log, true, StandardCharsets.UTF_8)).run(new RunSelection(taskDefs, List.of("baseline", "toolset")), 1);

        assertThat(records).hasSize(2).allSatisfy(r -> {
            assertThat(r.passed()).as(r.toString()).isTrue();
            assertThat(r.agent().turns()).isEqualTo(4);
        });
        RunRecord baseline = records.getFirst();
        RunRecord toolset = records.getLast();
        assertThat(baseline.tools().calls()).isZero();
        assertThat(toolset.tools().calls()).isEqualTo(1);
        assertThat(toolset.agent().totalTokens()).isEqualTo(4300);

        Path attempt = runDir.resolve("attempts/write-file/toolset-1");
        assertThat(Files.readString(attempt.resolve("workspace/done.txt"))).startsWith("Bench preamble.\n\nWrite done.txt in");
        assertThat(Files.readString(attempt.resolve("mcp.json"))).contains("tool-calls.jsonl").doesNotContain("{workspace}");

        List<RunRecord> reread = ResultsFile.read(runDir.resolve(BenchRunner.RESULTS_FILE), w -> {
            throw new AssertionError(w);
        });
        assertThat(reread).isEqualTo(records);
        assertThat(log.toString(StandardCharsets.UTF_8)).contains("[toolset] write-file #1 PASS");
    }

    /**
     * #624: an agent command without {prompt} gets the whole prompt on stdin, quotes, backslashes and line breaks as
     * written, as claude -p reads it; prompt.md holds the same text.
     */
    @Test
    void anAgentCommandWithoutPromptGetsThePromptOnStdin() throws IOException {
        String prompt = "Turn it into \"Sky Sprint\", a race.\nPaths end in C:\\dir\\ and \\\"; 100% ^done & more";
        Path task = Files.createDirectories(dir.resolve("tasks/quoted"));
        Files.writeString(task.resolve("task.json"),
                "{\"category\":\"code\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(task.resolve("prompt.md"), prompt);
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent"]},
                 "modes":{"baseline":{}}, "server":{"rconPort":%d,"serverPort":%d}}
                """.formatted(JAVA, CLASSPATH, rconPort.port(), gamePort.port()));
        BenchConfig config = BenchConfig.load(configFile, dir);

        List<RunRecord> records = new BenchRunner(config, dir.resolve("run"), "Preamble \"quoted\".",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

        assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        Path attempt = dir.resolve("run/attempts/quoted/baseline-1");
        String expected = "Preamble \"quoted\".\n\n" + prompt;
        assertThat(attempt.resolve("workspace/done.txt")).hasContent(expected);
        assertThat(attempt.resolve("prompt.md")).hasContent(expected);
    }

    @Test
    void checkFillsTheSamePlaceholdersAsTheRun() throws IOException {
        // #554: check filled 5 of the run's placeholders and left {minecraftVersion} and the rest as written, so a
        // command check that passed in the run failed when the kept attempt was checked again.
        Path task = Files.createDirectories(dir.resolve("tasks/placeholders/fixture"));
        Files.writeString(task.resolve("27.1-%d-%d.txt".formatted(rconPort.port(), gamePort.port())), "named by values");
        Files.writeString(dir.resolve("marker.txt"), "a paths key");
        Files.writeString(dir.resolve("tasks/placeholders/task.json"), """
                {"category":"code","title":"placeholders","checks":[
                  {"type":"command","name":"values","outputMatches":"named by values","run":["{java}","-cp","%s",
                   "io.github.imnotstable.msc.bench.CatFile","{attemptDir}/workspace/{minecraftVersion}-{rconPort}-{serverPort}.txt"]},
                  {"type":"command","name":"paths","outputMatches":"a paths key","run":["{java}","-cp","%s",
                   "io.github.imnotstable.msc.bench.CatFile","{marker}"]}]}
                """.formatted(CLASSPATH, CLASSPATH));
        Files.writeString(dir.resolve("tasks/placeholders/prompt.md"), "p");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "minecraftVersion":"27.1","paths":{"marker":"marker.txt"},
                 "server":{"rconPort":%d,"serverPort":%d}}
                """.formatted(JAVA, CLASSPATH, rconPort.port(), gamePort.port()));
        BenchConfig config = BenchConfig.load(configFile, dir);
        List<TaskDef> tasks = TaskLoader.loadAll(dir.resolve("tasks"));

        List<RunRecord> records = new BenchRunner(config, dir.resolve("run"), "",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new RunSelection(tasks, List.of("baseline")), 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = Main.check(tasks.getFirst(), dir.resolve("run/attempts/placeholders/baseline-1/workspace"), config,
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.failureReason()).isTrue());
        assertThat(exit).as(out.toString(StandardCharsets.UTF_8)).isZero();
    }

    private Path worldTask() throws IOException {
        Files.createDirectories(dir.resolve("tasks/world/fixture/server"));
        Files.writeString(dir.resolve("tasks/world/task.json"), """
                {"category":"world","title":"needs a server","server":{"startBefore":true},
                 "checks":[{"type":"rcon","command":"list","expect":"players"}]}
                """);
        Files.writeString(dir.resolve("tasks/world/prompt.md"), "look");
        Path configFile = dir.resolve("bench.json");
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.writeString(cache.resolve("paper-26.3-248.jar"), "not really a jar");
        Files.writeString(configFile, """
                {"server":{"rconPort":%d,"serverPort":%d,"paperCacheDirs":["cache"],"startTimeoutSeconds":5,"acceptEula":true}}
                """.formatted(rconPort.port(), gamePort.port()));
        return configFile;
    }

    @Test
    void skipsPaperServerTasksWithoutTheMachinesEulaAcceptance() throws IOException {
        Path configFile = worldTask();
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        BenchConfig config = BenchConfig.load(configFile, Files.createDirectories(dir.resolve("home")));
        List<RunRecord> records = new BenchRunner(config, dir.resolve("run"), "",
                new PrintStream(log, true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

        assertThat(config.server().acceptEula()).as("bench.json cannot accept the EULA").isFalse();
        assertThat(records).isEmpty();
        assertThat(log.toString(StandardCharsets.UTF_8)).contains("skipped world", "EULA is not accepted",
                "Nothing ran: every selected task was skipped");
        assertThat(dir.resolve("run/attempts")).doesNotExist();
    }

    @Test
    void preparesThePaperServerWhenTheOwnerAcceptedTheEula() throws IOException {
        Path configFile = worldTask();
        Path home = Files.createDirectories(dir.resolve("home/.msc"));
        Files.writeString(home.resolve("eula.json"), """
                {"accepted":true,"eula":"https://aka.ms/MinecraftEULA","acceptedBy":"owner","acceptedAt":"2026-11-12T00:00:00Z"}
                """);

        List<RunRecord> records = new BenchRunner(BenchConfig.load(configFile, dir.resolve("home")), dir.resolve("run"), "",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

        // The fake jar never starts, and the task has no setup commands: the failure names the start (#553).
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.passed()).isFalse();
            assertThat(r.error()).startsWith("starting the server: ");
        });
        Path server = dir.resolve("run/attempts/world/baseline-1/workspace/server");
        assertThat(Files.readString(server.resolve("server.properties"))).contains("enable-rcon=true", "rcon.port=" + rconPort.port());
        assertThat(server.resolve("paper.jar")).hasContent("not really a jar");
        assertThat(server.resolve("eula.txt")).hasContent("eula=true");
    }

    @Test
    void aServerLeftOnTheBenchPortsIsStoppedBeforeTheSetupRuns() throws IOException {
        // An earlier run cut short left its server answering on the RCON port; this run must not set up or check
        // that world.
        FakeRconServer[] holder = new FakeRconServer[1];
        holder[0] = new FakeRconServer("pw", command -> {
            if (command.equals("stop")) {
                try {
                    holder[0].close();
                } catch (IOException e) {
                    // test ends either way
                }
            }
            return "There are 9 players online, in another workspace";
        });
        try (FakeRconServer orphan = holder[0]) {
            Files.createDirectories(dir.resolve("tasks/world/fixture/server"));
            Files.writeString(dir.resolve("tasks/world/task.json"), """
                    {"category":"world","title":"needs a server","server":{"startBefore":true,"setup":["say setup"]},
                     "checks":[{"type":"rcon","command":"list","expect":"There are 0 players"}]}
                    """);
            Files.writeString(dir.resolve("tasks/world/prompt.md"), "look");
            Path cache = Files.createDirectories(dir.resolve("cache"));
            Files.writeString(cache.resolve("paper-26.3-248.jar"), "stands in for paper");
            Path home = Files.createDirectories(dir.resolve("home/.msc"));
            Files.writeString(home.resolve("eula.json"), """
                    {"accepted":true,"eula":"https://aka.ms/MinecraftEULA","acceptedBy":"owner","acceptedAt":"2026-11-12T00:00:00Z"}
                    """);
            int port = orphan.port();
            Files.writeString(dir.resolve("bench.json"), """
                    {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                     "modes":{"baseline":{}},
                     "server":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeRconMain","%d","pw"],
                               "rconPort":%d,"serverPort":%d,"rconPassword":"pw","paperCacheDirs":["cache"],
                               "startTimeoutSeconds":30}}
                    """.formatted(JAVA, CLASSPATH, JAVA, CLASSPATH, port, port, gamePort.port()));

            List<RunRecord> records = new BenchRunner(BenchConfig.load(dir.resolve("bench.json"), dir.resolve("home")),
                    dir.resolve("run"), "", new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                    .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

            assertThat(orphan.commands()).as("the orphan gets only a stop, no setup").containsExactly("stop");
            assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        }
    }

    /**
     * #495: the agent tried {@code benchmotd set} while testing its correct plugin, and the saved config (and, when it
     * left its server running, the plugin's memory) still held that value when the checks asked for the default. Each
     * row: whether the task declares the reset of that config, and whether the agent's server is still running. A
     * correct plugin passes exactly when the bench resets the config, whichever server the checks then reach.
     */
    @ParameterizedTest(name = "reset {0}, agent server running {1}")
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void aDeclaredResetPutsBackWhatTheAgentsTestingChanged(boolean reset, boolean agentServerRunning)
            throws IOException {
        Path config = Path.of("server").resolve(FakeMotdMain.CONFIG);
        String[] inMemory = {"left by testing"};
        FakeRconServer[] holder = new FakeRconServer[1];
        holder[0] = new FakeRconServer("pw", command -> {
            if (!command.equals("stop")) {
                return FakeMotdMain.reply(command, inMemory, dir.resolve("agent-server-config.yml"));
            }
            try {
                holder[0].close(); // a real server lets go of its port on stop
            } catch (IOException e) {
                // test ends either way
            }
            return "Stopping the server";
        });
        try (FakeRconServer agentServer = holder[0]) {
            int port = agentServer.port();
            if (!agentServerRunning) {
                holder[0].close(); // the agent stopped its server
            }
            Files.createDirectories(dir.resolve("tasks/motd/fixture/server"));
            Files.writeString(dir.resolve("tasks/motd/task.json"), """
                    {"category":"code","title":"motd",%s
                     "checks":[
                      {"type":"rcon","name":"default message","command":"benchmotd","expect":"^Welcome to MSC$"},
                      {"type":"rcon","name":"set message","command":"benchmotd set Hello builders","expect":".*"},
                      {"type":"rcon","name":"new message","command":"benchmotd","expect":"^Hello builders$"},
                      {"type":"file","name":"saved to config","path":"%s","contains":"Hello builders"}]}
                    """.formatted(reset ? "\"resetBeforeChecks\":{\"delete\":[\"" + slashes(config) + "\"]}," : "",
                    slashes(config)));
            Files.writeString(dir.resolve("tasks/motd/prompt.md"), "Add /benchmotd.");
            Files.writeString(Files.createDirectories(dir.resolve("cache")).resolve("paper-26.3-248.jar"), "paper");
            Files.writeString(Files.createDirectories(dir.resolve("home/.msc")).resolve("eula.json"), """
                    {"accepted":true,"eula":"https://aka.ms/MinecraftEULA","acceptedBy":"owner","acceptedAt":"2026-11-12T00:00:00Z"}
                    """);
            Files.writeString(dir.resolve("bench.json"), """
                    {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                     "modes":{"baseline":{"env":{"FAKE_LEAVE":"%s=motd: left by testing"}}},
                     "server":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeMotdMain","%d","pw"],
                               "rconPort":%d,"serverPort":%d,"rconPassword":"pw","paperCacheDirs":["cache"],
                               "startTimeoutSeconds":60}}
                    """.formatted(JAVA, CLASSPATH, slashes(config), JAVA, CLASSPATH, port, port, gamePort.port()));

            List<RunRecord> records = new BenchRunner(BenchConfig.load(dir.resolve("bench.json"), dir.resolve("home")),
                    dir.resolve("run"), "", new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                    .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

            assertThat(records).singleElement().satisfies(r -> {
                assertThat(r.error()).as(r.toString()).isNull();
                assertThat(r.checks().getFirst().passed()).as(r.checks().getFirst().toString()).isEqualTo(reset);
                assertThat(r.passed()).isEqualTo(reset);
            });
            if (agentServerRunning) {
                assertThat(agentServer.commands()).as("the agent's server is stopped either way").endsWith("stop");
            }
        }
        assertThat(ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains("FakeMotdMain"))).isEmpty();
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    void stopsAtTheFirstUsageLimitWithoutRecordingIt() throws IOException {
        for (String id : List.of("first", "second")) {
            Path task = Files.createDirectories(dir.resolve("tasks/" + id));
            Files.writeString(task.resolve("task.json"),
                    "{\"category\":\"code\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
            Files.writeString(task.resolve("prompt.md"), "Write done.txt.");
        }
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "modes":{"baseline":{"env":{"FAKE_USAGE_LIMIT":"1"}}}}
                """.formatted(JAVA, CLASSPATH));
        BenchRunner runner = new BenchRunner(BenchConfig.load(configFile, dir), dir.resolve("run"), "",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        List<RunRecord> records = runner.run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 3);

        assertThat(records).isEmpty();
        assertThat(runner.stoppedBy()).contains("429").contains("out of extra usage");
        assertThat(dir.resolve("run/attempts/first/baseline-2")).doesNotExist();
        assertThat(dir.resolve("run/attempts/second")).doesNotExist();
        assertThat(dir.resolve("run").resolve(BenchRunner.RESULTS_FILE)).doesNotExist();
    }

    private BenchRunner fileTaskRunner(Path runDir) throws IOException {
        return fileTaskRunner(runDir, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Disk.REAL);
    }

    private BenchRunner fileTaskRunner(Path runDir, PrintStream log, Disk disk) throws IOException {
        Path task = Files.createDirectories(dir.resolve("tasks/write"));
        Files.writeString(task.resolve("task.json"),
                "{\"category\":\"code\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(task.resolve("prompt.md"), "Write done.txt.");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "modes":{"baseline":{},"toolset":{}}}
                """.formatted(JAVA, CLASSPATH));
        return new BenchRunner(BenchConfig.load(configFile, dir), runDir, Map.of(TaskDef.PAPER, ""), log, disk);
    }

    /** Two tasks: a-emoji, whose command fails with an emoji at the 200-char cut of its output, then b-pass. */
    private BenchRunner emojiThenPassRunner(Path runDir, ByteArrayOutputStream log) throws IOException {
        Path emoji = Files.createDirectories(dir.resolve("tasks/a-emoji"));
        Files.writeString(emoji.resolve("task.json"), """
                {"category":"code","title":"x","checks":[{"type":"command",
                 "run":["{java}","-cp","%s","io.github.imnotstable.msc.bench.PrintsEmoji","199"]}]}
                """.formatted(CLASSPATH));
        Files.writeString(emoji.resolve("prompt.md"), "Fail loudly.");
        Path pass = Files.createDirectories(dir.resolve("tasks/b-pass"));
        Files.writeString(pass.resolve("task.json"),
                "{\"category\":\"code\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(pass.resolve("prompt.md"), "Write done.txt.");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "modes":{"baseline":{}}}
                """.formatted(JAVA, CLASSPATH));
        return new BenchRunner(BenchConfig.load(configFile, dir), runDir, "",
                new PrintStream(log, true, StandardCharsets.UTF_8));
    }

    @Test
    void anEmojiAtTheCutOfACheckDetailIsRecordedAndTheRunGoesOn() throws IOException {
        // #462: the cut left half the emoji, the UTF-8 writer refused the record and the whole run ended there.
        Path runDir = dir.resolve("run");
        BenchRunner runner = emojiThenPassRunner(runDir, new ByteArrayOutputStream());

        List<RunRecord> records = runner.run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")),
                List.of("baseline")), 1);

        assertThat(records).extracting(RunRecord::task, RunRecord::passed)
                .containsExactly(tuple("a-emoji", false), tuple("b-pass", true));
        String detail = records.getFirst().checks().getFirst().detail();
        assertThat(detail).startsWith("exit 1: ").endsWith("0".repeat(199));
        assertThat(Text.hasLoneSurrogate(detail)).isFalse();
        assertThat(ResultsFile.read(runDir.resolve(BenchRunner.RESULTS_FILE), w -> {
            throw new AssertionError(w);
        })).isEqualTo(records);
        assertThat(runner.notRecorded()).isNull();
    }

    @Test
    void anAttemptTheFileSystemWillNotRecordStopsTheRunWithWhatIsRecorded() throws IOException {
        // A results.jsonl that cannot be written (here a folder in its place, as a full disk or a locked file):
        // later attempts would be lost the same way, so the run stops and returns what the file holds.
        Path runDir = dir.resolve("run");
        Files.createDirectories(runDir.resolve(BenchRunner.RESULTS_FILE));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        BenchRunner runner = emojiThenPassRunner(runDir, log);

        List<RunRecord> records = runner.run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")),
                List.of("baseline")), 1);

        assertThat(records).isEmpty();
        assertThat(runner.notRecorded()).isNotNull();
        assertThat(log.toString(StandardCharsets.UTF_8)).contains("[baseline] a-emoji #1: stopped, "
                + runDir.resolve(BenchRunner.RESULTS_FILE) + " could not be written (");
        assertThat(runDir.resolve("attempts/b-pass")).doesNotExist();
    }

    /**
     * #615: the one rule for an IO failure in an attempt, at each write. Each row: what fails (a file or folder of the
     * run's own that the disk refuses after {@code room} writes of that name, or the workspace the task's fixtures
     * build); the exit code, and how many of the two attempts (baseline, then toolset with an MCP config) are
     * recorded. A refused write of the run's own files stops the run with exit 5 and its report, as a refused
     * results.jsonl append does; a failed workspace is that attempt's failure and the run goes on. No row leaves
     * {@code run} with an exception, which {@code Main} would turn into exit 2 with no report.
     */
    @ParameterizedTest
    @CsvSource({
            "toolset-1, 0, 5, 1",
            "mcp.json, 0, 5, 1",
            "agent-stdout.json, 1, 5, 1",
            "agent-stderr.log, 1, 5, 1",
            "prompt.md, 1, 5, 1",
            "results.jsonl, 1, 5, 1",
            "report.md, 0, 0, 2",
            "workspace, 0, 3, 2"})
    void everyWriteOfAnAttemptFailsOneWay(String fails, int room, int exit, int recorded) throws IOException {
        Path tasks = Files.createDirectories(dir.resolve("tasks"));
        Path task = Files.createDirectories(tasks.resolve("write"));
        Files.writeString(task.resolve("prompt.md"), "Write done.txt.");
        if (fails.equals("workspace")) {
            // Two fixtures that cannot both be copied: a file and a folder of the same name.
            Files.writeString(Files.createDirectories(tasks.resolve("_shared")).resolve("clash"), "a file");
            Files.writeString(Files.createDirectories(task.resolve("fixture/clash")).resolve("in.txt"), "a folder");
        }
        Files.writeString(task.resolve("task.json"), "{\"category\":\"code\",\"title\":\"x\","
                + (fails.equals("workspace") ? "\"fixtures\":[\"_shared\"]," : "")
                + "\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(dir.resolve("mcp.template.json"), "{\"log\":\"{toolLog}\"}");
        Path configFile = dir.resolve("bench.json");
        Path runDir = dir.resolve("run");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(log, true, StandardCharsets.UTF_8);
        try (ClosedPort corePort = ClosedPort.reserve()) {
            Files.writeString(configFile, """
                    {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                     "modes":{"baseline":{},"toolset":{"mcpConfigTemplate":"mcp.template.json"}},
                     "corePort":%d}
                    """.formatted(JAVA, CLASSPATH, corePort.port()));
            FullDisk disk = new FullDisk(fails.equals("workspace") ? Set.of() : Set.of(fails), room, false);
            BenchRunner runner = new BenchRunner(BenchConfig.load(configFile, dir), runDir, Map.of(TaskDef.PAPER, ""),
                    out, disk);

            List<RunRecord> records = runner.run(new RunSelection(TaskLoader.loadAll(tasks),
                    List.of("baseline", "toolset")), 1);
            int code = Main.endRun(records, runner.notRecorded(), runner.stoppedBy(), runDir, out, disk);

            assertThat(code).as(fails).isEqualTo(exit);
            assertThat(records).hasSize(recorded);
            assertThat(ResultsFile.read(runDir.resolve(ResultsFile.NAME), w -> {
                throw new AssertionError(w);
            })).isEqualTo(records);
            List<String> lines = log.toString(StandardCharsets.UTF_8).lines().toList();
            if (exit == Main.NOT_WRITTEN) {
                Path file = fails.equals("toolset-1") ? runDir.resolve("attempts/write/toolset-1")
                        : fails.equals(ResultsFile.NAME) ? runDir.resolve(fails)
                        : runDir.resolve("attempts/write/toolset-1").resolve(fails);
                String stop = file + " could not be written (No space left on device)";
                assertThat(lines).contains("[toolset] write #1: stopped, " + stop);
                assertThat(lines.get(lines.size() - 2)).isEqualTo("Stopped early: " + stop + "; results.jsonl holds "
                        + "the 1 attempts recorded. Fix the cause, run the rest into a new folder and put the "
                        + "results.jsonl files together before `report`.");
            }
            if (fails.equals("workspace")) {
                assertThat(records).allSatisfy(r -> assertThat(r.error()).startsWith("preparing the workspace: "));
            }
            if (fails.equals(Main.REPORT)) {
                assertThat(lines.getLast()).startsWith("warning: " + runDir.resolve(Main.REPORT).toAbsolutePath()
                        + " could not be written (No space left on device)");
            } else {
                assertThat(lines.getLast()).startsWith("Report: ");
                assertThat(Files.readString(runDir.resolve(Main.REPORT))).contains("Runs: " + recorded + ".");
            }
        }
    }

    /**
     * #476, #477: a disk that fills while a run writes. Each row: whether results.jsonl and report.md fill, and after how
     * many writes results.jsonl does; the run's exit code, and how many of its two attempts results.jsonl holds.
     */
    @ParameterizedTest
    @CsvSource({
            "append fails first, true, false, 0, 5, 0",
            "append fails second, true, false, 1, 5, 1",
            "both fail, true, true, 1, 5, 1",
            "report fails, false, true, 0, 0, 2"})
    void aFullDiskEndsTheRunWithItsVerdictAndWholeLines(String name, boolean appendFails, boolean reportFails,
            int room, int exit, int recorded) throws IOException {
        Path runDir = dir.resolve("run");
        Set<String> full = new HashSet<>();
        if (appendFails) {
            full.add(ResultsFile.NAME);
        }
        if (reportFails) {
            full.add(Main.REPORT);
        }
        FullDisk disk = new FullDisk(full, room, false);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(log, true, StandardCharsets.UTF_8);
        BenchRunner runner = fileTaskRunner(runDir, out, disk);
        List<RunRecord> records = runner.run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")),
                List.of("baseline", "toolset")), 1);

        int code = Main.endRun(records, runner.notRecorded(), runner.stoppedBy(), runDir, out, disk);

        List<String> lines = log.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> !l.startsWith("[")).toList();
        assertThat(code).as(name).isEqualTo(exit);
        assertThat(records).hasSize(recorded);
        Path results = runDir.resolve(ResultsFile.NAME);
        if (recorded == 0) {
            assertThat(results).isEmptyFile();
        } else {
            assertThat(ResultsFile.read(results, w -> {
                throw new AssertionError(w);
            })).isEqualTo(records);
        }
        // The stop comes first, then the report or the one warning that says how to rebuild it.
        if (exit == Main.NOT_WRITTEN) {
            assertThat(lines.getFirst()).startsWith("Stopped early: " + results + " could not be written "
                    + "(No space left on device); results.jsonl holds the " + recorded + " attempts recorded.");
        }
        String last = lines.getLast();
        if (reportFails) {
            assertThat(last).startsWith("warning: " + runDir.resolve(Main.REPORT).toAbsolutePath()
                    + " could not be written (No space left on device); once that is fixed, `report "
                    + runDir.toAbsolutePath() + "` writes it");
            assertThat(runDir.resolve(Main.REPORT)).doesNotExist();
        } else {
            assertThat(last).startsWith("Report: ");
            assertThat(Files.readString(runDir.resolve(Main.REPORT))).contains("# ");
        }
        assertThat(lines).hasSize(exit == Main.NOT_WRITTEN ? 2 : 1);
        try (var files = Files.list(runDir)) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(f -> f.endsWith(".part"));
        }
    }

    @Test
    void anAttemptNeverRunsInAFolderAnEarlierAttemptLeft() throws IOException {
        Path runDir = dir.resolve("run");
        assertThat(fileTaskRunner(runDir).run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1))
                .singleElement().satisfies(r -> assertThat(r.passed()).isTrue());

        BenchRunner again = fileTaskRunner(runDir);
        List<RunRecord> records = again.run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")),
                List.of("baseline")), 1);

        // The run's own folder cannot be made: the run stops as on a full disk (#615), with its report.
        assertThat(records).isEmpty();
        assertThat(again.notRecorded()).hasMessage(runDir.resolve("attempts/write/baseline-1").toAbsolutePath()
                + " could not be written (it already exists, with an earlier attempt's work in it)");
        assertThat(Files.readAllLines(runDir.resolve(BenchRunner.RESULTS_FILE))).hasSize(1);
    }

    @Test
    void minestomTasksStartFromTheScaffoldWithTheirOwnPreamble() throws IOException {
        Path task = Files.createDirectories(dir.resolve("tasks/game"));
        Files.writeString(task.resolve("task.json"), """
                {"category":"game","title":"x","platform":"minestom",
                 "scaffold":{"template":"minestom-game","name":"Sprint","package":"bench.sprint"},
                 "checks":[{"type":"file","path":"src/main/java/bench/sprint/SprintServer.java","contains":"25566"}]}
                """);
        Files.writeString(task.resolve("prompt.md"), "Build it.");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "modes":{"baseline":{}}}
                """.formatted(JAVA, CLASSPATH));

        List<RunRecord> records = new BenchRunner(BenchConfig.load(configFile, dir), dir.resolve("run"),
                Map.of(TaskDef.PAPER, "Paper preamble.", TaskDef.MINESTOM, "Minestom {scaffoldVersion} on {serverPort}."),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

        assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        Path workspace = dir.resolve("run/attempts/game/baseline-1/workspace");
        assertThat(workspace.resolve("done.txt")).hasContent("Minestom 1.0.0 on 25566.\n\nBuild it.");
        assertThat(workspace.resolve("libs/msc-agent-minestom.jar")).exists();
        assertThat(workspace.resolve("server")).doesNotExist();
    }

    @Test
    void paperTasksWithoutAServerGetTheNoServerPreamble() throws IOException {
        Path task = Files.createDirectories(dir.resolve("tasks/texture"));
        Files.writeString(task.resolve("task.json"),
                "{\"category\":\"texture\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(task.resolve("prompt.md"), "Draw it.");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "modes":{"baseline":{}}}
                """.formatted(JAVA, CLASSPATH));

        List<RunRecord> records = new BenchRunner(BenchConfig.load(configFile, dir), dir.resolve("run"),
                Map.of(TaskDef.PAPER, "Server in server/.", BenchRunner.PAPER_NO_SERVER, "No server."),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline")), 1);

        assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        Path workspace = dir.resolve("run/attempts/texture/baseline-1/workspace");
        assertThat(workspace.resolve("done.txt")).hasContent("No server.\n\nDraw it.");
        assertThat(workspace.resolve("server")).doesNotExist();
    }

    /** A run whose agent hangs with a child, as the runner's thread; returns once the agent and child are up. */
    private Thread hangingRun(BenchRunner runner, List<List<RunRecord>> result) throws IOException, InterruptedException {
        Path task = Files.createDirectories(dir.resolve("tasks/hang"));
        Files.writeString(task.resolve("task.json"),
                "{\"category\":\"code\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(task.resolve("prompt.md"), "Hang.");
        List<TaskDef> taskDefs = TaskLoader.loadAll(dir.resolve("tasks"));
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                result.add(runner.run(new RunSelection(taskDefs, List.of("baseline")), 2));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (fakeAgents() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(fakeAgents()).as("the agent and its child are running").isEqualTo(2);
        return thread;
    }

    private static long fakeAgents() {
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains("-Dmsc.fakeAgent=" + HANG_MARKER)).count();
    }

    private BenchRunner hangingRunner() throws IOException {
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-Dmsc.fakeAgent=%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent",
                                     "{prompt}"],"timeoutSeconds":600},
                 "modes":{"baseline":{"env":{"FAKE_HANG":"1"}}}}
                """.formatted(JAVA, HANG_MARKER, CLASSPATH));
        return new BenchRunner(BenchConfig.load(configFile, dir), dir.resolve("run"), "",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void anAbortedRunKillsTheAgentWithItsChildrenAndStartsNoMoreAttempts() throws Exception {
        BenchRunner runner = hangingRunner();
        List<List<RunRecord>> result = new CopyOnWriteArrayList<>();
        Thread thread = hangingRun(runner, result);

        runner.abort(); // what the shutdown hook does on Ctrl-C
        thread.join(Duration.ofSeconds(30));

        assertThat(thread.isAlive()).isFalse();
        assertThat(fakeAgents()).as("no agent or agent child outlives the run").isZero();
        assertThat(result).singleElement().satisfies(records -> assertThat(records).isEmpty());
        assertThat(dir.resolve("run/attempts/hang/baseline-2")).doesNotExist();
        assertThat(dir.resolve("run").resolve(BenchRunner.RESULTS_FILE)).doesNotExist();
    }

    @Test
    void anInterruptedRunKillsTheAgentsChildrenToo() throws Exception {
        BenchRunner runner = hangingRunner();
        List<List<RunRecord>> result = new CopyOnWriteArrayList<>();
        Thread thread = hangingRun(runner, result);

        thread.interrupt();
        thread.join(Duration.ofSeconds(30));

        assertThat(thread.isAlive()).isFalse();
        assertThat(fakeAgents()).as("the child is killed with the agent, not only the agent").isZero();
        assertThat(result).singleElement().satisfies(records -> assertThat(records).isEmpty());
        assertThat(dir.resolve("run/attempts/hang/baseline-2")).doesNotExist();
    }

    @Test
    void retriesWhenTheRequiredMcpServerWasNotConnectedAtStart() throws IOException {
        Path tasks = Files.createDirectories(dir.resolve("tasks/write-file"));
        Files.writeString(tasks.resolve("task.json"),
                "{\"category\":\"code\",\"title\":\"x\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        Files.writeString(tasks.resolve("prompt.md"), "Write done.txt.");
        String java = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
        String classpath = System.getProperty("java.class.path").replace("\\", "\\\\");
        String counter = dir.resolve("counter.txt").toString().replace("\\", "\\\\");
        Path configFile = dir.resolve("bench.json");
        Files.writeString(configFile, """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"]},
                 "modes":{"toolset":{"requireMcp":"msc","env":{"FAKE_MCP_COUNTER":"%s"}},
                          "other":{"requireMcp":"missing","env":{"FAKE_MCP_COUNTER":"%s"}},
                          "late":{"requireMcp":"missing","env":{"MSC_TOOL_LOG":"{toolLog}"}},
                          "silent":{"requireMcp":"msc","env":{"FAKE_SILENT":"1"}}}}
                """.formatted(java, classpath, counter, counter));
        BenchRunner runner = new BenchRunner(BenchConfig.load(configFile, dir), dir.resolve("run"), "",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        List<TaskDef> taskDefs = TaskLoader.loadAll(dir.resolve("tasks"));

        RunRecord retried = runner.run(new RunSelection(taskDefs, List.of("toolset")), 1).getFirst();
        assertThat(retried.passed()).isTrue();
        assertThat(retried.agent().mcpConnected()).containsExactly("msc");
        assertThat(dir.resolve("run/attempts/write-file/toolset-1-try2/workspace/done.txt")).exists();

        RunRecord lateButUsed = runner.run(new RunSelection(taskDefs, List.of("late")), 1).getFirst();
        assertThat(lateButUsed.passed()).as("init listed no server, but the tool log shows calls").isTrue();
        assertThat(dir.resolve("run/attempts/write-file/late-1-try2")).doesNotExist();

        RunRecord gaveUp = runner.run(new RunSelection(taskDefs, List.of("other")), 1).getFirst();
        assertThat(gaveUp.passed()).isFalse();
        assertThat(gaveUp.error()).contains("MCP server missing was not connected").contains("3 tries");
        assertThat(dir.resolve("run/attempts/write-file/other-1-try3")).isDirectory();

        RunRecord silent = runner.run(new RunSelection(taskDefs, List.of("silent")), 1).getFirst();
        assertThat(silent.agent().parsed()).isFalse();
        assertThat(silent.error()).as("no init event read, so nothing says MCP was missing").isNull();
        assertThat(dir.resolve("run/attempts/write-file/silent-1-try2")).doesNotExist();
    }

    /** A Minestom task whose agent leaves a server it started on the game port running when it exits (#577). */
    private Path minestomTaskLeavingAServer() throws IOException {
        Path task = Files.createDirectories(dir.resolve("tasks/game"));
        Files.writeString(task.resolve("task.json"), """
                {"category":"game","title":"a Minestom game","platform":"minestom",
                 "checks":[{"type":"file","name":"this attempt's server bound the game port","path":"left-server.port"}]}
                """);
        Files.writeString(task.resolve("prompt.md"), "make a game");
        return task.getParent();
    }

    private BenchConfig minestomConfig(int serverPort) throws IOException {
        Files.writeString(dir.resolve("bench.json"), """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"],"timeoutSeconds":120},
                 "modes":{"baseline":{"env":{"FAKE_LEAVE_SERVER":"{serverPort}"}},
                          "toolset":{"env":{"FAKE_LEAVE_SERVER":"{serverPort}"}}},
                 "server":{"rconPort":%d,"serverPort":%d}}
                """.formatted(JAVA, CLASSPATH, rconPort.port(), serverPort));
        return BenchConfig.load(dir.resolve("bench.json"), dir.resolve("home"));
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * #577: a Minestom server the baseline agent left on the game port held it into the toolset attempt (and every
     * later Paper task), whose own server could not bind it. Each attempt that uses the bench's ports now finds them
     * free, and nothing the run started outlives it.
     */
    @Test
    void aServerTheAgentLeftOnTheGamePortIsGoneBeforeTheNextAttempt() throws IOException {
        Path tasks = minestomTaskLeavingAServer();
        int serverPort = gamePort.port();
        gamePort.close(); // the agents' servers bind it
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Path runDir = dir.resolve("run");

        List<RunRecord> records = new BenchRunner(minestomConfig(serverPort), runDir, "",
                new PrintStream(log, true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(tasks), List.of("baseline", "toolset")), 1);

        assertThat(records).as("the toolset attempt's server bound the port too").hasSize(2)
                .allSatisfy(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        assertThat(listening(serverPort)).as("no server outlives the run").isFalse();
        assertThat(ProcessHandle.allProcesses().filter(p -> p.info().commandLine().orElse("")
                .contains(PortHolder.class.getName())).filter(p -> p.info().commandLine().orElse("")
                .contains(runDir.toString()))).isEmpty();
    }

    /**
     * #577: a port held by a program the bench cannot prove its own (it answers no RCON stop and runs outside the
     * run's attempt folders) fails the attempt at once, naming the port, before the agent works for nothing.
     */
    @Test
    void aPortTheBenchCannotFreeFailsAMinestomAttemptBeforeItsAgent() throws IOException {
        Path tasks = minestomTaskLeavingAServer();
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<RunRecord> records = new BenchRunner(minestomConfig(taken.getLocalPort()), dir.resolve("run"), "",
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                    .run(new RunSelection(TaskLoader.loadAll(tasks), List.of("baseline")), 1);

            assertThat(records).singleElement().satisfies(r -> {
                assertThat(r.passed()).isFalse();
                assertThat(r.error()).startsWith("freeing the bench's ports: port " + taken.getLocalPort());
                assertThat(r.wallMs()).as("the agent never started").isZero();
            });
        }
    }

    /** #577: whatever an attempt left running in its folder is stopped when it ends, even for a task without ports. */
    @Test
    void whatAnAttemptLeftRunningInItsFolderEndsWithIt() throws IOException {
        Path task = Files.createDirectories(dir.resolve("tasks/texture"));
        Files.writeString(task.resolve("task.json"), """
                {"category":"texture","title":"a texture","checks":[{"type":"file","path":"left-server.port"}]}
                """);
        Files.writeString(task.resolve("prompt.md"), "draw");
        Files.writeString(dir.resolve("bench.json"), """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"],"timeoutSeconds":120},
                 "modes":{"baseline":{"env":{"FAKE_LEAVE_SERVER":"0"}}},
                 "server":{"rconPort":%d,"serverPort":%d}}
                """.formatted(JAVA, CLASSPATH, rconPort.port(), gamePort.port()));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Path workspace = dir.resolve("run/attempts/texture/baseline-1/workspace");

        List<RunRecord> records = new BenchRunner(BenchConfig.load(dir.resolve("bench.json"), dir.resolve("home")),
                dir.resolve("run"), "", new PrintStream(log, true, StandardCharsets.UTF_8))
                .run(new RunSelection(TaskLoader.loadAll(task.getParent()), List.of("baseline")), 1);

        assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        assertThat(log.toString(StandardCharsets.UTF_8))
                .contains("[baseline] texture #1: stopped 1 process(es) the attempt left running");
        assertThat(listening(Integer.parseInt(Files.readString(workspace.resolve("left-server.port")).strip())))
                .isFalse();
    }
}
