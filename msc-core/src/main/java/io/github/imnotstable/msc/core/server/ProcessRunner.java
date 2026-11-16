package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.ProcessTree;
import io.github.imnotstable.msc.process.ServerJava;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
        return run(command, dir, timeout, Map.of());
    }

    /** {@link #run(List, Path, Duration)} with {@code env} added to the inherited environment. */
    public static Result run(List<String> command, Path dir, Duration timeout, Map<String, String> env)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        // Read as UTF-8, so a Java installer or a Gradle build is started to write UTF-8 (#478).
        ProcessBuilder pb = ServerJava.processBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
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

    /** Kills {@code root} and every process proven to descend from it (#339, #357); see {@link ProcessTree}. */
    static void destroyTree(ProcessHandle root) {
        ProcessTree.destroy(root);
    }
}
