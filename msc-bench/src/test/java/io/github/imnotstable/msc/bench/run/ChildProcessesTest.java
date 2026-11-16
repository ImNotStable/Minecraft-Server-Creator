package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ChildProcessesTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    @Test
    void killAllKillsLiveProcessesAndRefusesLaterStarts() throws IOException, InterruptedException {
        ChildProcesses children = new ChildProcesses();
        // Waits on standard input, which stays open: alive until killed.
        Process process = children.start(new ProcessBuilder(JAVA, "-cp", System.getProperty("java.class.path"),
                "io.github.imnotstable.msc.bench.FakeServer"));

        children.killAll();

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(children.closed()).isTrue();
        assertThat(catchThrowable(() -> children.start(new ProcessBuilder(JAVA, "-version"))))
                .isInstanceOf(IOException.class).hasMessageContaining("shutting down");
    }

    @TempDir
    Path dir;

    /**
     * #358: an agent (or server, or check) that is forking when the bench kills it, on a timeout or through the
     * Ctrl-C hook, leaves no child behind, not even one forked during the kill. Each trial kills once the command has
     * forked some children, never after a fixed sleep; odd trials go through killAll, even ones through killTree.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void killingAForkingCommandLeavesNoChild() throws Exception {
        List<String> survivors = new ArrayList<>();
        for (int trial = 0; trial < 4; trial++) {
            Path pids = dir.resolve("pids-" + trial);
            Files.createFile(pids);
            ChildProcesses children = new ChildProcesses();
            Process sh = children.start(new ProcessBuilder("/bin/sh", "-c", "i=0; while [ $i -lt 300 ]; do sleep 121 & "
                    + "echo $! >> " + pids.getFileName() + "; i=$((i+1)); done; wait").directory(dir.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (Files.readAllLines(pids).size() < 20 && sh.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            if (trial % 2 == 0) {
                ChildProcesses.killTree(sh);
            } else {
                children.killAll();
            }
            assertThat(sh.waitFor(60, TimeUnit.SECONDS)).isTrue();
            long exitBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (String pid : Files.readAllLines(pids)) {
                ProcessHandle.of(Long.parseLong(pid.strip())).filter(ChildProcessesTest::sleeper).ifPresent(h -> {
                    if (!exits(h, exitBy)) {
                        survivors.add(pid);
                        h.destroyForcibly();
                    }
                });
            }
        }
        assertThat(survivors).isEmpty();
    }

    /** True when {@code h} is still one of the test's {@code sleep 121} children, not a process that reused its pid. */
    private static boolean sleeper(ProcessHandle h) {
        ProcessHandle.Info info = h.info();
        return info.command().map(c -> c.endsWith("sleep")).orElse(false)
                && info.arguments().map(a -> List.of(a).equals(List.of("121"))).orElse(false);
    }

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
