package io.github.imnotstable.msc.core.texture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.process.HttpStall;
import io.github.imnotstable.msc.textures.PackSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Serves the built pack at {@code /pack-<sha1>.zip}. Runs inside the core, or as its own process
 * ({@link #main}) so the pack stays up for players after the core exits.
 */
public final class PackHost {
    /** What is being served: the zip and its SHA-1. */
    public record Served(byte[] data, String sha1) {
        static final Served NONE = new Served(new byte[0], "");
    }

    /** How long a host waits for a port that was just let go. */
    static final Duration PORT_PATIENCE = Duration.ofSeconds(5);

    private PackHost() {
    }

    /** At most this many downloads are sent at once; more wait their turn, each on its own connection. */
    static final int MAX_DOWNLOADS = 16;
    /** A download is written in pieces this big, so a stalled client is noticed piece by piece. */
    private static final int PIECE = 16 * 1024;

    /** A running host. {@link #stop} ends it and every download in flight. */
    public static final class Host {
        private final HttpServer server;
        private final ExecutorService workers;
        private final ScheduledExecutorService watchdog;
        private final AtomicInteger stalled = new AtomicInteger();

        private Host(HttpServer server, ExecutorService workers, ScheduledExecutorService watchdog) {
            this.server = server;
            this.workers = workers;
            this.watchdog = watchdog;
        }

        public int port() {
            return server.getAddress().getPort();
        }

        /** How many connections were ended because their client took nothing for the stall limit. */
        int stalled() {
            return stalled.get();
        }

        /** Stop listening, close every connection and end the threads that served them. */
        public void stop() {
            server.stop(0);
            workers.shutdownNow();
            watchdog.shutdownNow();
        }
    }

    /** Start serving on {@code host:port} (0 picks a free port). */
    public static Host start(String host, int port, Supplier<Served> source) throws IOException {
        return start(host, port, source, HttpStall.LIMIT);
    }

    /**
     * Like {@link #start}, with the stall limit given: a client that takes no byte of the pack for {@code stall} has
     * its connection closed, the rule {@link HttpStall} applies to every download MSC itself makes. Each download is
     * sent on its own thread, so a slow or stalled player never holds up another one's.
     */
    static Host start(String host, int port, Supplier<Served> source, Duration stall) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        // Virtual threads are daemon threads: serving never keeps a core alive; the standalone host's JVM stays up
        // on the server's own dispatcher thread.
        ExecutorService workers = new ThreadPoolExecutor(MAX_DOWNLOADS, MAX_DOWNLOADS, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), Thread.ofVirtual().name("msc-pack-", 0).factory());
        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1,
                Thread.ofVirtual().name("msc-pack-watchdog").factory());
        watchdog.setRemoveOnCancelPolicy(true);
        Host running = new Host(server, workers, watchdog);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            Served served = source.get();
            boolean match = !served.sha1().isEmpty()
                    && exchange.getRequestURI().getPath().equals("/pack-" + served.sha1() + ".zip");
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(match ? 200 : 404, match ? served.data().length : -1);
            if (match) {
                try (OutputStream out = exchange.getResponseBody()) {
                    byte[] data = served.data();
                    for (int off = 0; off < data.length; off += PIECE) {
                        int from = off;
                        int length = Math.min(PIECE, data.length - from);
                        guarded(running, exchange, stall, () -> out.write(data, from, length));
                    }
                    guarded(running, exchange, stall, out::flush);
                }
            }
            exchange.close();
        });
        server.start();
        return running;
    }

    private interface Write {
        void run() throws IOException;
    }

    /**
     * Runs one write; when it has not finished after {@code stall} the client took nothing for that long, and the
     * connection is closed, which also ends the blocked write.
     */
    private static void guarded(Host running, HttpExchange exchange, Duration stall, Write write) throws IOException {
        ScheduledFuture<?> cut = running.watchdog.schedule(() -> {
            running.stalled.incrementAndGet();
            exchange.close();
        }, stall.toNanos(), TimeUnit.NANOSECONDS);
        try {
            write.run();
        } finally {
            cut.cancel(false);
        }
    }

    /**
     * Like {@link #start}, but waits up to {@code patience} for {@code port} to come free. The port is often one
     * that was just let go (by this core when it hands serving to a standalone host, or by a host that was just
     * stopped), and Windows can refuse it for a moment after it was closed.
     */
    public static Host startWithin(String host, int port, Supplier<Served> source, Duration patience)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            try {
                return start(host, port, source);
            } catch (BindException e) {
                if (port == 0 || System.nanoTime() >= deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    /** Serves whatever {@code zip} holds now, so a later pack is picked up without a restart. */
    static Supplier<Served> file(Path zip) {
        return new Supplier<>() {
            private long stamp = Long.MIN_VALUE;
            private Served served = Served.NONE;

            @Override
            public synchronized Served get() {
                try {
                    long now = Files.getLastModifiedTime(zip).toMillis() ^ Files.size(zip);
                    if (now != stamp) {
                        byte[] data = Files.readAllBytes(zip);
                        served = new Served(data, PackSource.sha1(data));
                        stamp = now;
                    }
                } catch (IOException e) {
                    served = Served.NONE;
                    stamp = Long.MIN_VALUE;
                }
                return served;
            }
        };
    }

    /** {@code PackHost <host> <port> <zip>}: the standalone host a detached session leaves running. */
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println("usage: PackHost <host> <port> <zip>");
            System.exit(2);
        }
        // The core closed this port just before starting this process.
        startWithin(args[0], Integer.parseInt(args[1]), file(Path.of(args[2])), PORT_PATIENCE);
    }
}
