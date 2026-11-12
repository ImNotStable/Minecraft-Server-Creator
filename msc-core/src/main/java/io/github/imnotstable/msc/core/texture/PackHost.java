package io.github.imnotstable.msc.core.texture;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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

    /** Start serving on {@code host:port} (0 picks a free port). */
    public static HttpServer start(String host, int port, Supplier<Served> source) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", exchange -> {
            Served served = source.get();
            boolean match = !served.sha1().isEmpty()
                    && exchange.getRequestURI().getPath().equals("/pack-" + served.sha1() + ".zip");
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(match ? 200 : 404, match ? served.data().length : -1);
            if (match) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(served.data());
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * Like {@link #start}, but waits up to {@code patience} for {@code port} to come free. The port is often one
     * that was just let go (by this core when it hands serving to a standalone host, or by a host that was just
     * stopped), and Windows can refuse it for a moment after it was closed.
     */
    public static HttpServer startWithin(String host, int port, Supplier<Served> source, Duration patience)
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

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves whatever {@code zip} holds now, so a later pack_build is picked up without a restart. */
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
                        served = new Served(data, sha1(data));
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
