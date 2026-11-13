package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The threads tool calls run beside themselves: a {@code project_scaffold} warmup, the tests {@code dev_cycle} runs
 * while the server restarts. They belong to the workspace, not to the call: closing it (session end, {@code
 * msc_workspace}) interrupts them, so {@code ProcessRunner} kills their Gradle builds, and waits briefly until they
 * did, because after a session ends the JVM exits and would otherwise leave the build running. Nothing starts after.
 */
public final class BackgroundWork implements AutoCloseable {
    /** Plenty: an interrupted build only has to kill its process tree. */
    private static final Duration JOIN = Duration.ofSeconds(5);

    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private boolean closed;

    /** Run {@code task} on a new virtual thread that {@link #close} stops. */
    synchronized Thread start(String name, Runnable task) {
        if (closed) {
            throw ToolException.workspaceClosed(name + " did not start");
        }
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                task.run();
            } finally {
                threads.remove(Thread.currentThread());
            }
        });
        threads.add(thread);
        thread.start();
        return thread;
    }

    /** Interrupt every thread still running and wait up to a few seconds for them to end. */
    @Override
    public void close() {
        List<Thread> stopping;
        synchronized (this) {
            closed = true;
            stopping = List.copyOf(threads);
        }
        stopping.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + JOIN.toNanos();
        try {
            for (Thread thread : stopping) {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    thread.join(Duration.ofNanos(left));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
