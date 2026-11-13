package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.ProcessTable.Proc;
import io.github.imnotstable.msc.process.ProcessTable.Row;
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

/** The real table and tree kill on this machine's processes. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessTreeTest {
    @TempDir
    Path dir;

    /** Children each trial's command forks at most; each records its pid, so the test checks (and kills) only those. */
    private static final int FORKS = 300;

    /**
     * A command that forks as fast as it can is killed while forking: no child it made may outlive the kill, not
     * even one forked between a snapshot and its parent's kill (#339). Several trials, since the race is narrow; each
     * kill comes once the command has forked some children, never after a fixed sleep.
     */
    @Test
    void killsEveryChildOfACommandThatIsForking() throws Exception {
        List<String> survivors = new ArrayList<>();
        for (int trial = 0; trial < 5; trial++) {
            Path pids = dir.resolve("pids-" + trial);
            Files.createFile(pids);
            Process sh = new ProcessBuilder("/bin/sh", "-c", "i=0; while [ $i -lt " + FORKS + " ]; do sleep 120 & echo $! >> "
                    + pids.getFileName() + "; i=$((i+1)); done; wait").directory(dir.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (Files.readAllLines(pids).size() < 20 && sh.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            ProcessTree.destroy(sh.toHandle());
            assertThat(sh.waitFor(60, TimeUnit.SECONDS)).isTrue();
            long exitBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (String pid : Files.readAllLines(pids)) {
                int t = trial;
                ProcessHandle.of(Long.parseLong(pid.strip())).filter(ProcessTreeTest::sleeper).ifPresent(h -> {
                    if (!exits(h, exitBy)) {
                        survivors.add("trial " + t + ": " + pid);
                        h.destroyForcibly();
                    }
                });
            }
        }
        assertThat(survivors).isEmpty();
    }

    /** #357: the real table never asks a dead process for its descendants, and finds a live one's with parent pids. */
    @Test
    void snapshotsOnlyLiveSeeds() throws Exception {
        LiveProcessTable table = new LiveProcessTable();
        Process done = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        Proc dead = table.add(done.toHandle()).orElse(null);
        done.waitFor();
        Process sh = new ProcessBuilder("/bin/sh", "-c", "sleep 120 & wait").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Proc live = table.add(sh.toHandle()).orElseThrow();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (sh.children().findAny().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            if (dead != null) {
                assertThat(table.snapshot(List.of(dead))).isEmpty();
                assertThat(table.alive(dead)).isFalse();
            }
            List<Row> rows = table.snapshot(List.of(live));
            assertThat(rows).anyMatch(r -> r.proc().equals(live));
            assertThat(rows).anyMatch(r -> r.parent() == sh.pid() && !r.proc().equals(live));
        } finally {
            ProcessTree.destroy(sh.toHandle());
            sh.waitFor(60, TimeUnit.SECONDS);
        }
    }

    /** True when {@code h} is still one of the test's {@code sleep 120} children, not a process that reused its pid. */
    private static boolean sleeper(ProcessHandle h) {
        ProcessHandle.Info info = h.info();
        return info.command().map(c -> c.endsWith("sleep")).orElse(false)
                && info.arguments().map(a -> List.of(a).equals(List.of("120"))).orElse(false);
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
