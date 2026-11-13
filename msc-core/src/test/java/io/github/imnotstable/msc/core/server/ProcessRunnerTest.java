package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.Utf8PrintMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {
    @TempDir
    Path dir;

    @Test
    void multiByteCharactersSplitAcrossReadsAreDecodedWhole() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-cp", System.getProperty("java.class.path"), Utf8PrintMain.class.getName()), dir,
                Duration.ofSeconds(60));
        assertThat(r.ok()).isTrue();
        // Only the characters are checked: the JVM may print a JAVA_TOOL_OPTIONS note around them.
        assertThat(r.output()).doesNotContain("�").contains("€".repeat(30_000));
    }

    /** Children each trial's command forks at most; each records its pid, so the test checks (and kills) only those. */
    private static final int FORKS = 300;

    /**
     * A command that forks as fast as it can is interrupted while forking: no child it made may outlive the run,
     * not even one forked between the descendants snapshot and its parent's kill (#339). Several trials, since the
     * race is narrow; each interrupt comes once the command has forked some children, never after a fixed sleep.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void anInterruptStopsEveryChildOfACommandThatIsForking() throws Exception {
        List<String> survivors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int trial = i;
            Path pids = dir.resolve("pids-" + trial);
            Files.createFile(pids);
            String script = "i=0; while [ $i -lt " + FORKS + " ]; do sleep 120 & echo $! >> " + pids.getFileName()
                    + "; i=$((i+1)); done; wait";
            Thread runner = Thread.ofPlatform().start(() -> {
                try {
                    ProcessRunner.run(List.of("/bin/sh", "-c", script), dir, Duration.ofMinutes(2));
                } catch (Exception expected) {
                    // Interrupted, as intended.
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (Files.readAllLines(pids).size() < 20 && runner.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            runner.interrupt();
            runner.join(TimeUnit.SECONDS.toMillis(60));
            // One generous wait for the whole trial: a killed child exits at once, a missed one sleeps on.
            long exitBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (String pid : Files.readAllLines(pids)) {
                ProcessHandle.of(Long.parseLong(pid.strip())).filter(ProcessRunnerTest::sleeper).ifPresent(h -> {
                    if (!exits(h, exitBy)) {
                        survivors.add("trial " + trial + ": " + pid);
                        h.destroyForcibly();
                    }
                });
            }
        }
        assertThat(survivors).isEmpty();
    }

    /** True when {@code h} is still one of the test's {@code sleep 120} children, not a process that reused its pid. */
    private static boolean sleeper(ProcessHandle h) {
        ProcessHandle.Info info = h.info();
        return info.command().map(c -> c.endsWith("sleep")).orElse(false)
                && info.arguments().map(a -> List.of(a).equals(List.of("120"))).orElse(false);
    }

    /** True when {@code h}, a child the test's command forked, has exited or exits by {@code exitBy}. */
    private static boolean exits(ProcessHandle h, long exitBy) {
        try {
            h.onExit().get(Math.max(0, exitBy - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException e) {
            return !h.isAlive();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
