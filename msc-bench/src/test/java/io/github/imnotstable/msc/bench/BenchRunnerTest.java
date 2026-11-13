package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                new PrintStream(log, true, StandardCharsets.UTF_8)).run(taskDefs, List.of("baseline", "toolset"), 1);

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

        List<RunRecord> reread = ReportWriter.readResults(runDir.resolve(BenchRunner.RESULTS_FILE));
        assertThat(reread).isEqualTo(records);
        assertThat(log.toString(StandardCharsets.UTF_8)).contains("[toolset] write-file #1 PASS");
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
                .run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 1);

        assertThat(config.server().acceptEula()).as("bench.json cannot accept the EULA").isFalse();
        assertThat(records).isEmpty();
        assertThat(log.toString(StandardCharsets.UTF_8)).contains("skipped world", "EULA is not accepted");
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
                .run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 1);

        assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).isFalse());
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
                    .run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 1);

            assertThat(orphan.commands()).as("the orphan gets only a stop, no setup").containsExactly("stop");
            assertThat(records).singleElement().satisfies(r -> assertThat(r.passed()).as(r.toString()).isTrue());
        }
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

        List<RunRecord> records = runner.run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 3);

        assertThat(records).isEmpty();
        assertThat(runner.stoppedBy()).contains("429").contains("out of extra usage");
        assertThat(dir.resolve("run/attempts/first/baseline-2")).doesNotExist();
        assertThat(dir.resolve("run/attempts/second")).doesNotExist();
        assertThat(dir.resolve("run").resolve(BenchRunner.RESULTS_FILE)).doesNotExist();
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
                .run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 1);

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
                .run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 1);

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
                result.add(runner.run(taskDefs, List.of("baseline"), 2));
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

        RunRecord retried = runner.run(taskDefs, List.of("toolset"), 1).getFirst();
        assertThat(retried.passed()).isTrue();
        assertThat(retried.agent().mcpConnected()).containsExactly("msc");
        assertThat(dir.resolve("run/attempts/write-file/toolset-1-try2/workspace/done.txt")).exists();

        RunRecord lateButUsed = runner.run(taskDefs, List.of("late"), 1).getFirst();
        assertThat(lateButUsed.passed()).as("init listed no server, but the tool log shows calls").isTrue();
        assertThat(dir.resolve("run/attempts/write-file/late-1-try2")).doesNotExist();

        RunRecord gaveUp = runner.run(taskDefs, List.of("other"), 1).getFirst();
        assertThat(gaveUp.passed()).isFalse();
        assertThat(gaveUp.error()).contains("MCP server missing was not connected").contains("3 tries");
        assertThat(dir.resolve("run/attempts/write-file/other-1-try3")).isDirectory();

        RunRecord silent = runner.run(taskDefs, List.of("silent"), 1).getFirst();
        assertThat(silent.agent().parsed()).isFalse();
        assertThat(silent.error()).as("no init event read, so nothing says MCP was missing").isNull();
        assertThat(dir.resolve("run/attempts/write-file/silent-1-try2")).doesNotExist();
    }
}
