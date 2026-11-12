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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchRunnerTest {

    @TempDir
    Path dir;

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

        BenchConfig config = BenchConfig.load(configFile);
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

    @Test
    void usesTheCachedPaperJarAndRefusesToRunWithoutTheEula() throws IOException {
        Path tasks = Files.createDirectories(dir.resolve("tasks/world/fixture/server"));
        Files.writeString(dir.resolve("tasks/world/task.json"), """
                {"category":"world","title":"needs a server","server":{"startBefore":true},
                 "checks":[{"type":"rcon","command":"list","expect":"players"}]}
                """);
        Files.writeString(dir.resolve("tasks/world/prompt.md"), "look");
        Path configFile = dir.resolve("bench.json");
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.writeString(cache.resolve("paper-26.3-248.jar"), "not really a jar");
        Files.writeString(configFile, "{\"server\":{\"rconPort\":1,\"paperCacheDirs\":[\"cache\"]}}");

        List<RunRecord> records = new BenchRunner(BenchConfig.load(configFile), dir.resolve("run"), "", 
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(TaskLoader.loadAll(dir.resolve("tasks")), List.of("baseline"), 1);

        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.passed()).isFalse();
            assertThat(r.error()).contains("EULA");
        });
        assertThat(tasks.resolve("server.properties")).doesNotExist();
        assertThat(Files.readString(dir.resolve("run/attempts/world/baseline-1/workspace/server/server.properties")))
                .contains("enable-rcon=true", "rcon.port=1");
        assertThat(dir.resolve("run/attempts/world/baseline-1/workspace/server/paper.jar")).hasContent("not really a jar");
        assertThat(dir.resolve("run/attempts/world/baseline-1/workspace/server/eula.txt")).doesNotExist();
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
                          "other":{"requireMcp":"missing","env":{"FAKE_MCP_COUNTER":"%s"}}}}
                """.formatted(java, classpath, counter, counter));
        BenchRunner runner = new BenchRunner(BenchConfig.load(configFile), dir.resolve("run"), "",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        List<TaskDef> taskDefs = TaskLoader.loadAll(dir.resolve("tasks"));

        RunRecord retried = runner.run(taskDefs, List.of("toolset"), 1).getFirst();
        assertThat(retried.passed()).isTrue();
        assertThat(retried.agent().mcpConnected()).containsExactly("msc");
        assertThat(dir.resolve("run/attempts/write-file/toolset-1-try2/workspace/done.txt")).exists();

        RunRecord gaveUp = runner.run(taskDefs, List.of("other"), 1).getFirst();
        assertThat(gaveUp.passed()).isFalse();
        assertThat(gaveUp.error()).contains("MCP server missing was not connected").contains("3 tries");
        assertThat(dir.resolve("run/attempts/write-file/other-1-try3")).isDirectory();
    }
}
