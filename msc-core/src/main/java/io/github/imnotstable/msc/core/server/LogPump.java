package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * Reads one server process's output into a {@link LogBuffer}, and answers the one question every reader that says
 * "the server stopped" or shows the last lines needs: is the server gone and its log complete? A process is dead
 * before this pump has read the rest of its pipe, so {@link #awaitEnd} waits for the pump to reach the end of the
 * output (#533). That wait is bounded, once per process, since a child the server left running may hold the pipe
 * open (#324).
 */
final class LogPump {
    private final Process process;
    private final Thread thread;
    private final Duration drain;
    /** When the bounded drain ends, set by the first {@link #awaitEnd} that finds the process dead. */
    private long drainDeadline;
    private boolean draining;

    private LogPump(Process process, Thread thread, Duration drain) {
        this.process = process;
        this.thread = thread;
        this.drain = drain;
    }

    /** Start reading {@code process}'s output into {@code log}; {@code onReady} runs at the server's "Done" line. */
    static LogPump start(Process process, LogBuffer log, Runnable onReady, Duration drain) {
        Thread thread = Thread.ofVirtual().name("msc-server-log").start(() -> {
            try {
                LogReader.pump(process.getInputStream(), log, onReady);
            } catch (IOException ignored) {
                // Stream closed with the process.
            }
        });
        return new LogPump(process, thread, drain);
    }

    /**
     * False at once while the process runs. Otherwise waits until the pump has read the whole output, or until
     * {@code drain} has passed since a call first found the process dead, and returns true: the log is as complete
     * as it will get.
     */
    boolean awaitEnd() throws InterruptedException {
        if (process.isAlive()) {
            return false;
        }
        long left;
        synchronized (this) {
            if (!draining) {
                draining = true;
                drainDeadline = System.nanoTime() + drain.toNanos();
            }
            left = drainDeadline - System.nanoTime();
        }
        if (left > 0) {
            thread.join(Duration.ofNanos(left));
        }
        return true;
    }

    /** True once the pump has read the whole output. */
    boolean finished() {
        return !thread.isAlive();
    }

    /**
     * {@link LogBuffer#await} while the process runs. When it exits first, the log is completed ({@link #awaitEnd})
     * and checked once more, so a line printed just before the exit is found.
     */
    LogBuffer.Match await(LogBuffer log, long mark, Predicate<String> test, Duration timeout)
            throws InterruptedException {
        LogBuffer.Match m = log.await(mark, test, timeout, process::isAlive);
        if (m == null && awaitEnd()) {
            m = log.await(mark, test, Duration.ZERO, () -> false);
        }
        return m;
    }
}
