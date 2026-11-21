package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.FakeAgent;
import io.github.imnotstable.msc.process.ClosedPort;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The parts of a run that let it work headless on a Linux box or in a cloud container (ADR 0044). */
class HeadlessRunTest {

    @Test
    void theAgentDoesNotInheritTheClaudeCodeSessionItRunsIn() {
        Map<String, String> env = new HashMap<>(Map.of("CLAUDECODE", "1", "CLAUDE_CODE_ENTRYPOINT", "remote",
                "CLAUDE_CODE_REMOTE", "true", "CCR_AUTO_MODE_ALLOW", "x", "CLAUDE_CODE_OAUTH_TOKEN", "t",
                "CLAUDE_CODE_USE_BEDROCK", "1", "CLAUDE_CONFIG_DIR", "/c", "ANTHROPIC_BASE_URL", "u", "PATH", "/bin"));

        AgentEnvironment.dropParentSession(env);

        assertThat(env).containsOnlyKeys("CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CONFIG_DIR",
                "ANTHROPIC_BASE_URL", "PATH");
    }

    @Test
    void refusesACorePortAnotherProgramHolds() throws IOException {
        // Room for every probe: nothing accepts, and Windows refuses a connection a full backlog has no room for.
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int[] kills = new int[1];
            assertThatThrownBy(() -> CoreProcesses.requireFreePort(taken.getLocalPort(), () -> {
                kills[0]++;
                return 0;
            }, p -> false))
                    .hasMessageContaining("core port " + taken.getLocalPort() + " is in use")
                    .hasMessageContaining("set corePort in bench.json");
            assertThat(kills[0]).as("the run's own leftovers go first").isEqualTo(1);
        }
        // Bound but never listening: free for the check, and no other process can take it meanwhile (#580).
        try (ClosedPort free = ClosedPort.reserve()) {
            CoreProcesses.requireFreePort(free.port(), () -> {
                throw new AssertionError("nothing to kill on a free port");
            }, p -> false);
        }
    }

    @Test
    void theRunMarksEveryMcpServerOfTheConfig() {
        List<String> args = List.of("-Dmsc.bench.run=r", "-Dmsc.bench.attempt=/a");
        String json = BenchRunner.withMarks("""
                {"mcpServers":{"msc":{"command":"java","env":{"MSC_TOKEN":"t"}},"other":{"command":"x"}}}
                """, Map.of(AttemptProcesses.RUN, "r", AttemptProcesses.ATTEMPT, "/a"), args);
        assertThat(json).contains("\"env\":{\"MSC_TOKEN\":\"t\",").contains("\"MSC_BENCH_RUN\":\"r\"")
                .contains("\"other\":{\"command\":\"x\",\"env\":{");
        assertThat(BenchRunner.withMarks("{}", Map.of(AttemptProcesses.RUN, "r"), args)).isEqualTo("{}");
        assertThat(BenchRunner.withMarks("{not json", Map.of(AttemptProcesses.RUN, "r"), args))
                .isEqualTo("{not json");
    }

    /** #595: a Java server gets the marks first on its command line too, where a system shows no environment. */
    @Test
    void theRunMarksTheCommandLineOfEveryJavaMcpServer() {
        List<String> marks = List.of("-Dmsc.bench.run=r", "-Dmsc.bench.attempt=C:\\runs\\a b");
        JsonObject servers = JsonParser.parseString(BenchRunner.withMarks("""
                {"mcpServers":{
                  "msc":{"command":"C:\\\\jdk\\\\bin\\\\java.EXE","args":["-cp","lib/*","Main"]},
                  "bare":{"command":"java"},
                  "javaw":{"command":"/opt/jdk/bin/javaw","args":[]},
                  "node":{"command":"node","args":["s.js"]},
                  "javascript":{"command":"/usr/bin/java-tool","args":["x"]}}}
                """, Map.of(AttemptProcesses.RUN, "r"), marks)).getAsJsonObject().getAsJsonObject("mcpServers");
        assertThat(args(servers, "msc")).containsExactly("-Dmsc.bench.run=r", "-Dmsc.bench.attempt=C:\\runs\\a b",
                "-cp", "lib/*", "Main");
        assertThat(args(servers, "bare")).containsExactlyElementsOf(marks);
        assertThat(args(servers, "javaw")).containsExactlyElementsOf(marks);
        assertThat(args(servers, "node")).containsExactly("s.js");
        assertThat(args(servers, "javascript")).containsExactly("x");
        // The shipped template starts core with {java}: the filled config carries the marks.
        assertThat(BenchRunner.isJava(ProcessHandle.current().info().command().orElse("java"))).isTrue();
    }

    private static List<String> args(JsonObject servers, String name) {
        List<String> args = new ArrayList<>();
        servers.getAsJsonObject(name).getAsJsonArray("args").forEach(a -> args.add(a.getAsString()));
        return args;
    }

    @Test
    void theModelOptionReplacesOrAddsTheModel() {
        BenchConfig pinned = new BenchConfig(new BenchConfig.Agent(List.of("claude", "-p", "{prompt}", "--model",
                "big"), 60), Map.of(), null, null, Map.of(), 25590, "26.3");
        assertThat(pinned.withModel("small").agent().command()).containsExactly("claude", "-p", "{prompt}",
                "--model", "small");
        assertThat(BenchConfig.defaults().withModel("small").agent().command())
                .containsExactly("claude", "-p", "--output-format", "stream-json", "--verbose",
                        "--model", "small");
        assertThatThrownBy(() -> pinned.withModel(" ")).hasMessageContaining("is no model name");
    }

    @Test
    void anIsolatedAgentSeesOnlyItsOwnProcessesAndTakesThemAlong() throws Exception {
        assertThat(PidNamespace.prefix(true)).containsExactly("unshare", "--pid", "--fork", "--mount-proc",
                "--kill-child");
        assertThat(PidNamespace.prefix(false)).startsWith("unshare", "--user", "--map-current-user");
        List<String> prefix;
        try {
            prefix = PidNamespace.require();
        } catch (IllegalArgumentException unsupported) {
            Assumptions.abort(unsupported.getMessage());
            return;
        }
        AttemptProcesses run = AttemptProcesses.newRun();
        Path attempt = Path.of("/runs/" + UUID.randomUUID());
        String java = ProcessHandle.current().info().command().orElse("java");
        // Like an agent: starts a core that outlives it, and counts the processes it can see.
        List<String> command = new ArrayList<>(prefix);
        command.addAll(List.of("sh", "-c", "\"$0\" -cp \"$1\" \"$2\" \"$3\" >/dev/null 2>&1 & "
                + "sleep 1; ls /proc | grep -c '^[0-9]'", java, System.getProperty("java.class.path"),
                FakeAgent.class.getName(), FakeAgent.SLEEP));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        run.mark(builder.environment(), attempt);
        Process agent = builder.start();
        String seen = new String(agent.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(agent.waitFor(30, TimeUnit.SECONDS)).isTrue();

        assertThat(Integer.parseInt(seen)).as("sh, the core, ls and grep at most").isLessThanOrEqualTo(5);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!run.in(attempt).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(run.kill(attempt)).as("the core ended with the agent's namespace").isZero();
    }
}
