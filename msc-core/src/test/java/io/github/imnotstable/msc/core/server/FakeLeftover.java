package io.github.imnotstable.msc.core.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A server an earlier core left running, as a fake process: it survives every way of stopping it until
 * {@link #diesWhenKilled} says otherwise, as one started elevated on Windows or stuck in disk IO does (#408). Tests in
 * other packages install it with {@link #install}.
 */
public final class FakeLeftover implements ProcessHandle {
    /** Far above any real pid, so nothing real is ever named. */
    private static final AtomicInteger PIDS = new AtomicInteger(1_900_000_000);

    private final long pid = PIDS.incrementAndGet();
    private final CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();
    private volatile boolean diesWhenKilled;
    /** How often it was asked to end ({@link #destroy}), and how often killed with its children. */
    public final AtomicInteger destroys = new AtomicInteger();
    public final AtomicInteger kills = new AtomicInteger();
    /** How often a stop waited for it to exit. */
    public final AtomicInteger waits = new AtomicInteger();

    /** Writes its pid file into {@code server}'s data folder and makes {@code server} find and kill only it. */
    public static FakeLeftover install(ServerManager server, Duration wait) throws Exception {
        FakeLeftover fake = new FakeLeftover();
        Files.createDirectories(server.pidFile().getParent());
        Files.writeString(server.pidFile(), fake.pid + " 1\n");
        fake.waitFor(server, wait);
        return fake;
    }

    /** Every stage of a stop waits {@code wait} for it: a short one where a stage runs, a long one where none may. */
    public void waitFor(ServerManager server, Duration wait) {
        server.leftovers(new ServerManager.Leftovers(
                file -> Files.exists(file) && isAlive() ? Optional.of(this) : Optional.empty(),
                h -> kill(), wait, wait, wait, Duration.ofMillis(1)));
    }

    /** From now on a kill ends it. */
    public void diesWhenKilled() {
        diesWhenKilled = true;
    }

    /** The pid file {@code server} reads and writes. */
    public static Path pidFile(ServerManager server) {
        return server.pidFile();
    }

    private void kill() {
        kills.incrementAndGet();
        if (diesWhenKilled) {
            exit.complete(this);
        }
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public Optional<ProcessHandle> parent() {
        return Optional.empty();
    }

    @Override
    public Stream<ProcessHandle> children() {
        return Stream.empty();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return Stream.empty();
    }

    @Override
    public Info info() {
        return ProcessHandle.current().info();
    }

    @Override
    public CompletableFuture<ProcessHandle> onExit() {
        waits.incrementAndGet();
        return exit;
    }

    @Override
    public boolean supportsNormalTermination() {
        return true;
    }

    @Override
    public boolean destroy() {
        destroys.incrementAndGet();
        return true;
    }

    @Override
    public boolean destroyForcibly() {
        kill();
        return true;
    }

    @Override
    public boolean isAlive() {
        return !exit.isDone();
    }

    @Override
    public int compareTo(ProcessHandle other) {
        return Long.compare(pid, other.pid());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FakeLeftover f && f.pid == pid;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(pid);
    }
}
