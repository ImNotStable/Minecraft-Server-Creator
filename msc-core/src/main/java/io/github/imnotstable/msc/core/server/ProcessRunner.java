package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        if (out.length() < 4_000_000) {
                            out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
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
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
