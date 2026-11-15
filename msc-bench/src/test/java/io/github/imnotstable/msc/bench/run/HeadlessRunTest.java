package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.FakeAgent;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    void stopsOnlyTheCoresOfItsOwnAttempt() throws Exception {
        String dir = "/runs/" + UUID.randomUUID() + "/toolset-1";
        Process own = sleeper(CoreProcesses.marker(dir));
        Process retry = sleeper(CoreProcesses.marker(dir + "-try2"));
        try {
            assertThat(CoreProcesses.stop(CoreProcesses.marker(dir))).isEqualTo(1);
            assertThat(own.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(retry.isAlive()).as("toolset-1 is a prefix of toolset-1-try2, not its marker").isTrue();
        } finally {
            own.destroyForcibly();
            retry.destroyForcibly();
        }
    }

    @Test
    void refusesACorePortAnotherProgramHolds() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // No earlier bench cores: the real lookup would stop those of a bench running on this machine.
            assertThatThrownBy(() -> CoreProcesses.requireFreePort(taken.getLocalPort(), List::of))
                    .hasMessageContaining("core port " + taken.getLocalPort() + " is in use")
                    .hasMessageContaining("set corePort in bench.json");
        }
        int free;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            free = probe.getLocalPort();
        }
        CoreProcesses.requireFreePort(free);
    }

    @Test
    void theModelOptionReplacesOrAddsTheModel() {
        BenchConfig pinned = new BenchConfig(new BenchConfig.Agent(List.of("claude", "-p", "{prompt}", "--model",
                "big"), 60), Map.of(), null, null, Map.of(), 25590, "26.3");
        assertThat(pinned.withModel("small").agent().command()).containsExactly("claude", "-p", "{prompt}",
                "--model", "small");
        assertThat(BenchConfig.defaults().withModel("small").agent().command())
                .containsExactly("claude", "-p", "{prompt}", "--output-format", "stream-json", "--verbose",
                        "--model", "small");
        assertThatThrownBy(() -> pinned.withModel(" ")).hasMessageContaining("is no model name");
    }

    private static Process sleeper(String marker) throws IOException {
        // The marker comes first, as in the toolset MCP config template.
        return new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), marker, "-cp",
                System.getProperty("java.class.path"), FakeAgent.class.getName(), FakeAgent.SLEEP)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
    }
}
