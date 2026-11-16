package io.github.imnotstable.msc.core.texture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.PortPatience;
import io.github.imnotstable.msc.process.HttpStall;
import io.github.imnotstable.msc.textures.PackSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Serves built packs at {@code /pack-<sha1>.zip}. Runs inside the core, or as its own process
 * ({@link #main}) so the pack stays up for players after the core exits.
 */
public final class PackHost {
    /** What is being served: the zip and its SHA-1. */
    public record Served(byte[] data, String sha1) {
        static final Served NONE = new Served(new byte[0], "");
    }

    /**
     * Finds the build a request asks for by its SHA-1, or null (a 404). The in-process host has one build; the
     * standalone host has every build file in its folder ({@link #folder}).
     */
    @FunctionalInterface
    public interface Lookup {
        Served find(String sha1);
    }

    /** {@code /pack-<sha1>.zip}: the only paths a host answers with a pack. */
    private static final java.util.regex.Pattern PATH = java.util.regex.Pattern.compile("/pack-([0-9a-f]{40})\\.zip");

    /** The line the standalone host prints once it serves, followed by its port ({@link #main}). */
    public static final String READY = "pack host serving on port ";

    /**
     * Starts a host on a free port outside the ports the system hands out by itself (ADR 0053) and outside the ports
     * {@code reserved} names, the ones the workspace uses for anything else (ADR 0054): a port picked by a bind to port
     * 0, or as the local end of an outgoing connection, comes from the ephemeral range, where any process on the machine
     * may take it again the moment this host lets it go. The pack's port is remembered and taken again across sessions
     * and from the in-process host to the standalone one, so it is picked below that range, where only a bind that
     * names it can take it, and never on a port the server, the dev proxy or the core needs, which the pack host would
     * then keep from them (#755). Falls back to port 0 when that range holds nothing free.
     */
    static Host startOnFreePort(String host, Supplier<Served> source, IntPredicate reserved, String token)
            throws IOException {
        return startOnFreePort(host, one(source), reserved, java.util.concurrent.ThreadLocalRandom.current(), token);
    }

    /** {@link #startOnFreePort(String, Supplier, IntPredicate, String)} with the random source given (tests). */
    static Host startOnFreePort(String host, Lookup source, IntPredicate reserved, RandomGenerator random,
                                String token) throws IOException {
        int[] range = freePortRange(ephemeralStart());
        if (range != null) {
            for (int i = 0; i < 64; i++) {
                int port = pick(range, reserved, random);
                if (port < 0) {
                    break;
                }
                try {
                    return start(host, port, source, HttpStall.LIMIT, token);
                } catch (IOException e) {
                    // Taken, or not ours to bind: try another.
                }
            }
        }
        // The system's pick: from the ephemeral range, where a reserved port (a fixed agent port, say) may be too.
        for (int i = 0; i < 16; i++) {
            Host started = start(host, 0, source, HttpStall.LIMIT, token);
            if (!reserved.test(started.port())) {
                return started;
            }
            started.stop();
        }
        throw new java.net.BindException("no free port for the pack that the workspace does not use otherwise");
    }

    /**
     * A port in {@code range} (lower bound included, upper excluded) that {@code reserved} does not name: a random one,
     * or the next one up from it that is not reserved, wrapping around. -1 when every port in the range is reserved.
     */
    static int pick(int[] range, IntPredicate reserved, RandomGenerator random) {
        int size = range[1] - range[0];
        int first = random.nextInt(size);
        for (int i = 0; i < size; i++) {
            int port = range[0] + (first + i) % size;
            if (!reserved.test(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * Where {@link #startOnFreePort} picks: up to 12,768 ports below the start of the ephemeral range (32768 on Linux by
     * default, 49152 on Windows and macOS), never below 1024; null when that leaves fewer than 1,000.
     */
    static int[] freePortRange(int ephemeralStart) {
        int upper = Math.min(ephemeralStart, 32768);
        int lower = Math.max(1024, upper - 12768);
        return upper - lower < 1000 ? null : new int[] {lower, upper};
    }

    /** The first port of the system's ephemeral range: Linux says it, the others start at 49152 or above. */
    static int ephemeralStart() {
        // Read as a stream: a /proc file reports no size, which Files.readString cuts the read short by.
        try (java.io.BufferedReader r = Files.newBufferedReader(Path.of("/proc/sys/net/ipv4/ip_local_port_range"))) {
            return Integer.parseInt(r.readLine().trim().split("\\s+")[0]);
        } catch (IOException | RuntimeException e) {
            return 32768;
        }
    }

    /** How long a host waits for a port that was just let go. */
    static final Duration PORT_PATIENCE = PortPatience.PATIENCE;

    private PackHost() {
    }

    /**
     * At most this many downloads are sent at once; more wait their turn, each on its own connection. Only sending
     * takes a turn: a request is read on a thread of its own, so a client that never finishes one holds no turn.
     */
    static final int MAX_DOWNLOADS = 16;
    /** A download is written in pieces this big, so a stalled client is noticed piece by piece. */
    private static final int PIECE = 16 * 1024;

    /** A running host. {@link #stop} ends it and every download in flight. */
    public static final class Host {
        private final HttpServer server;
        private final ExecutorService workers;
        private final ScheduledExecutorService watchdog;
        private final AtomicInteger stalled = new AtomicInteger();
        private final AtomicInteger unfinished = new AtomicInteger();
        private final Semaphore sending = new Semaphore(MAX_DOWNLOADS, true);
        private final String token;

        private Host(HttpServer server, ExecutorService workers, ScheduledExecutorService watchdog, String token) {
            this.server = server;
            this.workers = workers;
            this.watchdog = watchdog;
            this.token = token;
        }

        /** The token this host answers on {@link #IDENTITY_PATH}, beside the pid of the process it runs in. */
        public String token() {
            return token;
        }

        public int port() {
            return server.getAddress().getPort();
        }

        /** How many connections were ended because their client took nothing for the stall limit. */
        int stalled() {
            return stalled.get();
        }

        /** How many connections were ended because their client did not finish its request within the stall limit. */
        int unfinished() {
            return unfinished.get();
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
        return start(host, port, one(source), HttpStall.LIMIT, null);
    }

    /**
     * Where every host says who it is (ADR 0054): {@code msc-pack-host pid=<pid> token=<token>}, the process it runs in
     * and the token it was started with. A standalone host's token is recorded with its pid file, so a core adopts the
     * host it finds there only when the host on the recorded port answers both: never another process that took the
     * port, and never its own in-process host (#756).
     */
    public static final String IDENTITY_PATH = "/msc-pack-host";

    /** A host's answer on {@link #IDENTITY_PATH}: the pid of the process it runs in, and its token. */
    public record Identity(long pid, String token) {
        private static final java.util.regex.Pattern TEXT =
                java.util.regex.Pattern.compile("msc-pack-host pid=(\\d+) token=([0-9a-f]+)");

        String text() {
            return "msc-pack-host pid=" + pid + " token=" + token;
        }

        static Identity parse(String text) {
            java.util.regex.Matcher m = TEXT.matcher(text.strip());
            return m.matches() ? new Identity(Long.parseLong(m.group(1)), m.group(2)) : null;
        }
    }

    /** A new random token: 16 bytes as hex. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /**
     * Asks whatever listens on {@code host:port} who it is: the {@link Identity} of the MSC pack host there, or null
     * when nothing answers or what answers is not one. Never follows a proxy; gives up after a second or two.
     */
    public static Identity identify(String host, int port) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("http://"
                    + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + IDENTITY_PATH).toURL()
                    .openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try {
                if (c.getResponseCode() != 200) {
                    return null;
                }
                try (java.io.InputStream in = c.getInputStream()) {
                    return Identity.parse(new String(in.readNBytes(256), java.nio.charset.StandardCharsets.UTF_8));
                }
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The one build {@code source} gives now, found by its SHA-1. */
    static Lookup one(Supplier<Served> source) {
        return sha1 -> {
            Served served = source.get();
            return served.sha1().equals(sha1) ? served : null;
        };
    }

    /**
     * Like {@link #start}, with the stall limit given: a client that takes no byte of the pack for {@code stall} has
     * its connection closed, the rule {@link HttpStall} applies to every download MSC itself makes. Each download is
     * sent on its own thread, so a slow or stalled player never holds up another one's. The same limit applies to
     * reading the request: the JDK server hands a connection to a thread at its first byte and reads the request
     * line and headers there without a limit of its own, so a client that sends part of one is cut after
     * {@code stall} (#512).
     */
    static Host start(String host, int port, Supplier<Served> source, Duration stall) throws IOException {
        return start(host, port, one(source), stall, null);
    }

    /** {@link #start(String, int, Supplier, Duration)} serving whatever build {@code source} finds. */
    static Host start(String host, int port, Lookup source, Duration stall) throws IOException {
        return start(host, port, source, stall, null);
    }

    /**
     * {@link #start(String, int, Supplier, Duration)} serving whatever build {@code source} finds, answering
     * {@code token} on {@link #IDENTITY_PATH} (a new one when null).
     */
    static Host start(String host, int port, Lookup source, Duration stall, String token) throws IOException {
        Identity identity = new Identity(ProcessHandle.current().pid(), token != null ? token : newToken());
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        // Virtual threads are daemon threads: serving never keeps a core alive; the standalone host's JVM stays up
        // on the server's own dispatcher thread. One per exchange, so a cut request's interrupt reaches no other.
        ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("msc-pack-", 0).factory());
        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1,
                Thread.ofVirtual().name("msc-pack-watchdog").factory());
        watchdog.setRemoveOnCancelPolicy(true);
        Host running = new Host(server, workers, watchdog, identity.token());
        server.setExecutor(exchange -> workers.execute(() -> untilHandled(running, stall, exchange)));
        server.createContext(IDENTITY_PATH, exchange -> {
            if (REQUEST.isBound() && !REQUEST.get().read()) {
                exchange.close();
                return;
            }
            byte[] text = (identity.text() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            boolean exact = exchange.getRequestURI().getPath().equals(IDENTITY_PATH);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(exact ? 200 : 404, exact ? text.length : -1);
            if (exact) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(text);
                }
            }
            exchange.close();
        });
        server.createContext("/", exchange -> {
            if (REQUEST.isBound() && !REQUEST.get().read()) {
                exchange.close();
                return;
            }
            java.util.regex.Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
            Served served = path.matches() ? source.find(path.group(1)) : null;
            boolean match = served != null;
            if (match && !turn(running)) {
                exchange.close();
                return;
            }
            try {
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
            } finally {
                if (match) {
                    running.sending.release();
                }
            }
        });
        server.start();
        return running;
    }

    /** The request being read on this thread, so the handler can say it was read in time. */
    private static final ScopedValue<Request> REQUEST = ScopedValue.newInstance();

    /**
     * Reading one request: the watchdog interrupts its thread once {@code stall} has passed and the handler has not
     * been reached. The thread reads from a blocking channel, which an interrupt closes, so the read ends.
     */
    private static final class Request {
        private final Thread thread = Thread.currentThread();
        private boolean reading = true;

        synchronized void cut(Host running) {
            if (reading) {
                reading = false;
                running.unfinished.incrementAndGet();
                thread.interrupt();
            }
        }

        /** Marks the request read; false when it was cut first. No interrupt from the watchdog comes after this. */
        synchronized boolean read() {
            boolean intime = reading;
            reading = false;
            return intime;
        }
    }

    /** Runs the JDK server's exchange (read the request, then the handler) with the request read bounded by stall. */
    private static void untilHandled(Host running, Duration stall, Runnable exchange) {
        Request request = new Request();
        ScheduledFuture<?> cut = running.watchdog.schedule(() -> request.cut(running), stall.toNanos(),
                TimeUnit.NANOSECONDS);
        try {
            ScopedValue.where(REQUEST, request).run(exchange);
        } finally {
            cut.cancel(false);
            request.read();
        }
    }

    /** Waits for one of the {@link #MAX_DOWNLOADS} turns; false when the host is stopping. */
    private static boolean turn(Host running) {
        try {
            running.sending.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        return startWithin(host, port, one(source), patience, null);
    }

    /** {@link #startWithin(String, int, Supplier, Duration)} answering {@code token} on {@link #IDENTITY_PATH}. */
    static Host startWithin(String host, int port, Supplier<Served> source, Duration patience, String token)
            throws IOException, InterruptedException {
        return startWithin(host, port, one(source), patience, token);
    }

    private static Host startWithin(String host, int port, Lookup source, Duration patience, String token)
            throws IOException, InterruptedException {
        return PortPatience.bind(port, patience, () -> start(host, port, source, HttpStall.LIMIT, token));
    }

    /**
     * Serves every build in {@code dir} by its name, {@code pack-<sha1>.zip} (ADR 0053): a build file is never
     * rewritten, so a later pack is picked up without a restart and an earlier one stays served while its file is
     * there, whoever built the later one. A file whose content does not match its name is not served. The last build
     * read is kept, as players of one server all ask for the same one.
     */
    static Lookup folder(Path dir) {
        return new Lookup() {
            private Served last = Served.NONE;

            @Override
            public synchronized Served find(String sha1) {
                if (last.sha1().equals(sha1)) {
                    return last;
                }
                try {
                    byte[] data = Files.readAllBytes(dir.resolve("pack-" + sha1 + ".zip"));
                    if (!PackSource.sha1(data).equals(sha1)) {
                        return null;
                    }
                    last = new Served(data, sha1);
                    return last;
                } catch (IOException e) {
                    return null;
                }
            }
        };
    }

    /**
     * {@code PackHost <host> <port> <dir> [<token>]}: the standalone host a detached session leaves running, serving
     * the build files in {@code dir} ({@link #folder}) and answering {@code token} on {@link #IDENTITY_PATH}, the token
     * the core records with its pid file (ADR 0054). A host that cannot serve prints why as its one line and exits, so
     * the core can quote it (#744).
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length != 3 && args.length != 4) {
            System.err.println("usage: PackHost <host> <port> <dir> [<token>]");
            System.exit(2);
        }
        try {
            // The core closed this port just before starting this process.
            int port = Integer.parseInt(args[1]);
            startWithin(args[0], port, folder(Path.of(args[2])), PORT_PATIENCE, args.length == 4 ? args[3] : null);
            // Only now does the core count it as up: whatever else listens on the port is not this host (#753).
            System.out.println(READY + port);
            System.out.flush();
        } catch (IOException | RuntimeException e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
            System.exit(1);
        }
    }
}
