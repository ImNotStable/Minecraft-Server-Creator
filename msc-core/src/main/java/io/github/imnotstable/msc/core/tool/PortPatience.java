package io.github.imnotstable.msc.core.tool;

import java.io.IOException;
import java.net.BindException;
import java.time.Duration;

/**
 * Binds a fixed port that may have been let go a moment ago: the agent port and the pack port across a workspace
 * switch (#721), and the pack port a standalone host takes over. Windows can refuse such a port for a moment after it
 * was closed, and another core that is just exiting may still hold it.
 */
public final class PortPatience {
    /** How long a bind waits for a port that was just let go. */
    public static final Duration PATIENCE = Duration.ofSeconds(5);

    /** Binds something to a port; throws {@link BindException} while the port is taken. */
    @FunctionalInterface
    public interface Bind<T> {
        T bind() throws IOException;
    }

    private PortPatience() {
    }

    /**
     * Runs {@code bind} until it does not throw a {@link BindException}, for up to {@code patience}. Port 0 (any free
     * port) is tried once: waiting would not help. The exception it gives up with names the port, so a user knows which
     * one to free (#727).
     */
    public static <T> T bind(int port, Duration patience, Bind<T> bind) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            try {
                return bind.bind();
            } catch (BindException e) {
                if (port == 0 || System.nanoTime() >= deadline) {
                    BindException named = new BindException("port " + port + " is taken: " + e.getMessage());
                    named.initCause(e);
                    throw named;
                }
                Thread.sleep(100);
            }
        }
    }
}
