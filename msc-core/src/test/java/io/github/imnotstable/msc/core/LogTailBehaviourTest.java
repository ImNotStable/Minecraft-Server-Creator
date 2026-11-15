package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.tool.Budget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #611: every tool that shows the end of the server log keeps its newest line when the lines do not fit, and says at
 * the top how many earlier lines it left out.
 */
class LogTailBehaviourTest {
    @TempDir
    Path workspace;

    private static List<String> fakeServer() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeServerMain.class.getName());
    }

    private CoreHarness harness(String flag) throws Exception {
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run").resolve(flag), "");
        return new CoreHarness(workspace, c -> c.withServerCommand(fakeServer()));
    }

    @Test
    void logsAndWaitKeepTheNewestLine() throws Exception {
        try (CoreHarness h = harness("long-lines.flag")) {
            h.ok("server_start", "timeoutSeconds", 30);
            String tail = h.ok("server_logs", "mode", "tail");
            assertThat(tail).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).startsWith("[truncated: ")
                    .contains("step 9 s").endsWith("For help, type \"help\"");
            String grep = h.ok("server_logs", "mode", "grep", "pattern", "step", "lines", 5);
            assertThat(grep).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS)
                    .startsWith("10 matches, last 5:\n[truncated: ").endsWith("step 9 " + "s".repeat(2000));
            String wait = h.ok("server_wait", "until", "no such line", "timeoutSeconds", 1);
            assertThat(wait).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS)
                    .startsWith("no line matched in 1s; last lines:\n[truncated: ")
                    .endsWith("For help, type \"help\"");
            h.ok("server_stop");
        }
    }

    @Test
    void theConsolePathOfServerCommandKeepsTheNewestLine() throws Exception {
        try (CoreHarness h = harness("no-agent.flag")) {
            h.ok("server_start", "timeoutSeconds", 30);
            String out = h.ok("server_command", "command", "spam");
            assertThat(out).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).startsWith("[truncated: ")
                    .endsWith("spam 9 " + "s".repeat(1000));
            h.ok("server_stop");
        }
    }

    @Test
    void anEarlyExitKeepsTheLastLineBeforeIt() throws Exception {
        try (CoreHarness h = harness("long-exit.flag")) {
            String start = h.ok("server_start", "timeoutSeconds", 30);
            assertThat(start).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).startsWith("server exited during startup")
                    .contains("\nlast lines:\n[truncated: ").endsWith("step 9 " + "s".repeat(2000));
        }
    }
}
