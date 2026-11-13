package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a command to completion and captures what it printed. An interrupt stops the command and its children. */
public final class ProcessRunner {
    public record Result(int exitCode, String output, boolean timedOut, long millis) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private ProcessRunner() {
    }

    public static Result run(List<String> command, Path dir, Duration timeout) throws IOException, InterruptedException {
        long start = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        Process process = pb.start();
        process.getOutputStream().close();
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            // A Reader keeps the bytes of a character that a read cut in two until the rest arrives.
            try (Reader in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        if (out.length() < 4_000_000) {
                            out.append(buf, 0, n);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process went away; what we have is what we report.
            }
        });
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // The caller gave up on the result: stop the command instead of leaving it running unseen.
            destroyTree(process);
            throw e;
        }
        if (!finished) {
            destroyTree(process);
        }
        reader.join(2000);
        String text;
        synchronized (out) {
            text = out.toString();
        }
        return new Result(finished ? process.exitValue() : -1, text, !finished,
                (System.nanoTime() - start) / 1_000_000);
    }

    private static void destroyTree(Process process) {
        destroyTree(process.toHandle());
    }

    /** Rounds of freezing new descendants, and of the snapshot sweep: far more than any real tree is deep. */
    private static final int ROUNDS = 64;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    /**
     * Sends a forced kill to {@code root} and every process below it, including a child the tree forks while this
     * runs (#339). A single snapshot of the descendants misses a child forked between the snapshot and its parent's
     * kill, and on Unix that child is then reparented to init and runs on.
     *
     * <p>On Unix the tree is frozen top-down first: SIGSTOP to the root, then to each descendant not yet stopped,
     * until a snapshot finds none. On Linux a fork that has not finished when SIGSTOP is queued is restarted after
     * the stop, so once {@code kill} returns that process makes no new child; elsewhere the repeated snapshot covers
     * the delivery delay. Only then is everything killed (SIGKILL ends a stopped process), and the frozen pids are sent
     * SIGCONT, so a pid reused by another process meanwhile is not left stopped.
     *
     * <p>Windows has no stop signal. There, and wherever the freeze could not run, it is the documented best effort:
     * kill the descendants of a snapshot and the root, and sweep again until a snapshot is empty. A Windows process
     * keeps its parent's id after the parent dies, so a child forked in the gap is found by the next sweep.
     */
    static void destroyTree(ProcessHandle root) {
        List<ProcessHandle> frozen = WINDOWS ? List.of() : freeze(root);
        frozen.forEach(ProcessHandle::destroyForcibly);
        if (!frozen.isEmpty()) {
            signal("CONT", frozen);
        }
        for (int round = 0; round < ROUNDS; round++) {
            List<ProcessHandle> left = root.descendants().toList();
            left.forEach(ProcessHandle::destroyForcibly);
            root.destroyForcibly();
            if (left.isEmpty()) {
                break;
            }
        }
    }

    /** Stops {@code root} and its descendants top-down; returns the processes it stopped, root first. */
    private static List<ProcessHandle> freeze(ProcessHandle root) {
        Map<Long, ProcessHandle> frozen = new LinkedHashMap<>();
        List<ProcessHandle> next = List.of(root);
        for (int round = 0; round < ROUNDS && !next.isEmpty(); round++) {
            if (!signal("STOP", next)) {
                break;
            }
            next.forEach(h -> frozen.put(h.pid(), h));
            next = root.descendants().filter(h -> !frozen.containsKey(h.pid())).toList();
        }
        return List.copyOf(frozen.values());
    }

    /** Sends {@code SIG<name>} to the processes with the shell's {@code kill}; false when that could not run. */
    private static boolean signal(String name, List<ProcessHandle> processes) {
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", "kill -" + name + " \"$@\"", "sh"));
        processes.forEach(h -> command.add(Long.toString(h.pid())));
        try {
            Process kill = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            kill.getOutputStream().close();
            if (kill.waitFor(10, TimeUnit.SECONDS)) {
                return true;
            }
            kill.destroyForcibly();
            return false;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
