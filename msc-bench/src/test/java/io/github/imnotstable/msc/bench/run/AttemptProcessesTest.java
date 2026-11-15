package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #577, #587: the bench kills only what this run started for an attempt, proven by the marks in the process's
 * environment. Real stand-in processes: what must survive and what must die.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AttemptProcessesTest {

    @TempDir
    Path dir;

    private final AttemptProcesses run = AttemptProcesses.newRun();
    private final List<ProcessHandle> started = new ArrayList<>();
    private Path attempts;
    private Path attempt;
    private Path workspace;

    @BeforeEach
    void layOut() throws IOException {
        Assumptions.assumeTrue(AttemptProcesses.canProve(), "this system shows no process's environment");
        attempts = dir.resolve("attempts");
        attempt = Files.createDirectories(attempts.resolve("t/toolset-1"));
        workspace = Files.createDirectories(attempt.resolve("workspace"));
        Files.writeString(attempt.resolve("agent-stdout.json"), "{}\n");
    }

    @AfterEach
    void stopWhatTheTestStarted() {
        // Only the processes this test started, each by its pid.
        started.forEach(p -> {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        });
    }

    @Test
    void killsOnlyWhatThisRunStartedForTheAttempt() throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        // Must survive: none of them was started by this run for toolset-1.
        ProcessHandle shell = start(elsewhere, Map.of(), "sh", "-c", "cd \"$0\" && exec sleep 300",
                workspace.toString()).toHandle();
        ProcessHandle tail = start(elsewhere, Map.of(), "tail", "-f", attempt.resolve("agent-stdout.json").toString())
                .toHandle();
        ProcessHandle otherRun = start(elsewhere, AttemptProcesses.newRun().marks(attempt), "sleep", "300").toHandle();
        ProcessHandle oldMarker = start(workspace, Map.of(), "sh", "-c", "sleep 300; true", "x",
                "-Dmsc.bench.attempt=" + attempt).toHandle();
        Path retryDir = Files.createDirectories(attempts.resolve("t/toolset-1-try2"));
        ProcessHandle retry = start(elsewhere, run.marks(retryDir), "sleep", "300").toHandle();
        // A Gradle daemon the attempt started, with no build running: waits on its input, no child of its own.
        ProcessHandle daemon = start(elsewhere, run.marks(attempt), "sh", "-c", "read x",
                AttemptProcesses.GRADLE_DAEMON).toHandle();
        // Must die: started by this run for toolset-1, whatever folder they run in and however they left the tree.
        ProcessHandle server = start(elsewhere, run.marks(attempt), "sleep", "300").toHandle();
        ProcessHandle detached = ProcessHandle.of(firstLine(start(elsewhere, run.marks(attempt), "sh", "-c",
                "{ command -v setsid >/dev/null && exec setsid sleep 300 || exec sleep 300; } "
                        + "</dev/null >/dev/null 2>&1 & echo $!"))).orElseThrow();
        started.add(detached);
        Process wrapperProcess = start(elsewhere, run.marks(attempt), "sh", "-c", "env -i sleep 300 & echo $!; wait");
        ProcessHandle unmarkedChild = ProcessHandle.of(firstLine(wrapperProcess)).orElseThrow();
        ProcessHandle wrapper = wrapperProcess.toHandle();

        assertThat(run.in(attempt)).extracting(ProcessHandle::pid)
                .containsExactlyInAnyOrder(server.pid(), detached.pid(), wrapper.pid());
        assertThat(run.kill(attempt)).isEqualTo(3);
        for (ProcessHandle dead : List.of(server, detached, wrapper, unmarkedChild)) {
            assertThat(dead.onExit().get(10, TimeUnit.SECONDS).isAlive()).isFalse();
        }
        for (ProcessHandle alive : List.of(shell, tail, otherRun, oldMarker, retry, daemon)) {
            assertThat(alive.isAlive()).as(alive.info().commandLine().orElse("pid " + alive.pid())).isTrue();
        }

        assertThat(run.kill(attempts)).as("the whole run: toolset-1-try2, never the daemon").isEqualTo(1);
        assertThat(retry.onExit().get(10, TimeUnit.SECONDS).isAlive()).isFalse();
        assertThat(daemon.isAlive()).isTrue();
    }

    @Test
    void neverTheBenchItselfAndNothingWhoseEnvironmentIsHidden() {
        assertThat(run.in(Path.of("/"))).extracting(ProcessHandle::pid).doesNotContain(ProcessHandle.current().pid());
        assertThat(AttemptProcesses.environment(Long.MAX_VALUE)).isEmpty();
    }

    private Process start(Path cwd, Map<String, String> marks, String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(marks);
        Process process = builder.start();
        started.add(process.toHandle());
        return process;
    }

    /** The pid {@code process} printed first. */
    private static long firstLine(Process process) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8));
        return Long.parseLong(reader.readLine().strip());
    }
}
