package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns the dev server process: start it with the agent's connection details, watch its log, stop it. */
public final class ServerManager implements AutoCloseable {

    /**
     * Outcome of a start. {@code ready} false means it exited or timed out; the log says why. A server that has exited
     * is never ready, also when it printed its "Done" line or its agent connected first (#337): {@code sawDone} tells
     * that case apart. {@code agentMillis} is when the agent connected, or -1 when the server was ready (its "Done"
     * line) without one.
     */
    public record StartResult(boolean ready, boolean exited, long millis, long logMark, long agentMillis,
            boolean sawDone) {
    }

    private final CoreConfig config;
    private final AgentHub hub;
    /** At most 20,000 lines of at most {@link LogReader#MAX_LINE_CHARS} chars, and 4M chars in all. */
    private final LogBuffer log = new LogBuffer(20_000, 4_000_000);
    private Process process;
    private volatile boolean sawDone;
    private volatile boolean detached;
    /** Set by {@link #close()} under this monitor; {@link #start} checks it under the same monitor (#282). */
    private boolean closed;
    /** Clock time just before the running server's process was launched: files written later are not loaded. */
    private java.time.Instant launchedAt;
    private volatile Leftovers leftovers = Leftovers.REAL;

    public ServerManager(CoreConfig config, AgentHub hub) {
        this.config = config;
        this.hub = hub;
    }

    /**
     * How a server an earlier core left running is found, killed and waited for. Tests swap in a fake process and
     * short waits (#408); {@link #REAL} reads {@code .msc/server.pid} and kills the process tree (ADR 0031).
     *
     * @param find    the live process the pid file names, its start time matching (never a reused pid)
     * @param kill    kills the process and every process proven to descend from it
     * @param rcon    how long it may take to exit after a {@code stop} over RCON
     * @param destroy how long it may take to exit after it is asked to end
     * @param tree    how long it may take to exit after it is killed with its children
     * @param again   how long a start waits after killing again a leftover that already survived all of that
     */
    record Leftovers(Function<Path, Optional<ProcessHandle>> find, Consumer<ProcessHandle> kill, Duration rcon,
                     Duration destroy, Duration tree, Duration again) {
        static final Leftovers REAL = new Leftovers(PidFile::peek, ProcessRunner::destroyTree, Duration.ofSeconds(60),
                Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }

    /** For tests: how leftovers are found, killed and waited for. */
    void leftovers(Leftovers leftovers) {
        this.leftovers = leftovers;
    }

    public LogBuffer log() {
        return log;
    }

    public Path serverDir() {
        return config.serverDir();
    }

    public synchronized boolean running() {
        return process != null && process.isAlive();
    }

    /** The platform this workspace targets. */
    public Platform platform() {
        return Platform.current(config);
    }

    /** The jar the server starts from ({@code server_setup} or, for Minestom, {@code dev_cycle} put it there), or null. */
    public Path serverJar() {
        Path jar = config.serverDir().resolve(platform().serverJar());
        return Files.exists(jar) ? jar : null;
    }

    private List<String> command() {
        if (config.serverCommand() != null) {
            return config.serverCommand();
        }
        Platform platform = platform();
        checkStartable(platform, false);
        if (platform.needsEula()) {
            // The EULA is the user's to accept: eula.txt in the server directory decides, or the owner's own
            // one-time acceptance for this machine (ADR 0020), never a flag set here.
            Path eula = eulaFile();
            try {
                config.machineEula().applyTo(eula);
            } catch (IOException e) {
                throw new ToolException("could not write " + eula + ": " + e.getMessage());
            }
            if (!eulaAccepted(eula)) {
                throw eulaRefusal();
            }
        }
        return platform.launchCommand(config.javaCommand());
    }

    /**
     * Refuses a start of {@code platform} that cannot go ahead: no server jar, or (unless {@code acceptingEula}, the
     * user's yes on this very call) the EULA neither in eula.txt nor accepted for this machine. Reads only, so a tool
     * decides its refusals before its first side effect (the Steps rule). A configured server command skips both.
     */
    public void checkStartable(Platform platform, boolean acceptingEula) {
        if (config.serverCommand() != null) {
            return;
        }
        if (!Files.exists(config.serverDir().resolve(platform.serverJar()))) {
            throw new ToolException("no " + platform.id() + " server in " + config.serverDir() + ". "
                    + (platform == Platform.MINESTOM ? "Run dev_cycle to build the project and put its jar there."
                    : "Run server_setup first."));
        }
        if (platform.needsEula() && !acceptingEula && !eulaAccepted(eulaFile())
                && config.machineEula().read().isEmpty()) {
            throw eulaRefusal();
        }
    }

    /** The server's eula.txt. */
    public Path eulaFile() {
        return config.serverDir().resolve("eula.txt");
    }

    private ToolException eulaRefusal() {
        return new ToolException("the Minecraft EULA is not accepted (no eula=true in " + eulaFile() + "): once the "
                + "user agrees to https://aka.ms/MinecraftEULA, run server_start with acceptEula=true (that "
                + "also covers later servers on this machine), or the user runs once in their own terminal: "
                + MachineEula.acceptCommand(config.installDir()));
    }

    /** True when {@code eulaFile} exists and has an {@code eula=true} line. */
    public static boolean eulaAccepted(Path eulaFile) {
        try {
            return Files.readAllLines(eulaFile).stream().anyMatch(l -> l.strip().equals("eula=true"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Start the server and wait until it is ready, it exits, or the timeout passes. */
    public StartResult start(Duration timeout) throws IOException, InterruptedException {
        long begin = System.nanoTime();
        long mark;
        // Read from the hub before taking this monitor: the hub's lock holder may be waiting for it (running()).
        String address = hub.address();
        String token = hub.token();
        long readyBefore = hub.readyCount();
        Process started;
        Thread pump;
        synchronized (this) {
            if (closed) {
                // A call that was already running when the workspace closed: nothing would stop this server.
                throw ToolException.workspaceClosed("the server did not start");
            }
            if (running()) {
                throw new ToolException("server is already running. Use server_stop or dev_cycle.");
            }
            // A server an earlier core left running holds the world and the port: a new one would die on them, and
            // its pid would overwrite the only record of the one still running (#408).
            ProcessHandle left = leftovers.find().apply(pidFile()).orElse(null);
            if (left != null) {
                throw new ToolException(LeftoverServer.stillRunning(left.pid(), PidFile.resisting(pidFile())
                        ? LeftoverServer.RESISTED : LeftoverServer.NOT_STOPPED));
            }
            List<String> command = new ArrayList<>(command());
            Files.createDirectories(config.serverDir());
            ProcessBuilder pb = new ProcessBuilder(command).directory(config.serverDir().toFile())
                    .redirectErrorStream(true);
            pb.environment().put(Limits.ENV_CORE_ADDRESS, address);
            pb.environment().put(Limits.ENV_TOKEN, token);
            mark = log.mark();
            sawDone = false;
            // Surviving the session is asked for per server (ADR 0014): a detached server that exited on its own
            // must not pass that on to the next one.
            detached = false;
            launchedAt = java.time.Instant.now();
            started = pb.start();
            process = started;
            PidFile.write(pidFile(), started.toHandle());
            pump = Thread.ofVirtual().name("msc-server-log").start(() -> pump(started));
        }
        long deadline = begin + timeout.toNanos();
        boolean ready = false;
        long agentMillis = -1;
        while (System.nanoTime() < deadline && started.isAlive()) {
            if (hub.awaitReady(readyBefore, Duration.ofMillis(200))) {
                agentMillis = (System.nanoTime() - begin) / 1_000_000;
                ready = true;
                break;
            }
            if (sawDone) {
                ready = true;
                // On Minestom the agent is part of the server and connects just after "Done": a report sent now
                // would say "no agent connected". Wait for it a little, where an agent is expected at all.
                if (expectsAgent()) {
                    long agentDeadline = Math.min(deadline, System.nanoTime() + AGENT_GRACE.toNanos());
                    while (System.nanoTime() < agentDeadline && started.isAlive()) {
                        if (hub.awaitReady(readyBefore, Duration.ofMillis(100))) {
                            agentMillis = (System.nanoTime() - begin) / 1_000_000;
                            break;
                        }
                    }
                }
                break;
            }
        }
        boolean exited = !started.isAlive();
        if (exited) {
            // A dead server is not ready, whatever it printed first (#337). The report reads the log next: let the
            // pump reach the end of the output, which comes as soon as the pipe closes. Bounded, since a child the
            // server left running may hold the pipe open (#324).
            ready = false;
            pump.join(PUMP_DRAIN);
        }
        return new StartResult(ready, exited, (System.nanoTime() - begin) / 1_000_000, mark, agentMillis, sawDone);
    }

    /** How long a start waits for the log pump to reach the end of an exited server's output. */
    static final Duration PUMP_DRAIN = Duration.ofSeconds(10);

    /** How long a start waits after "Done" for an agent that has not connected yet. */
    static final Duration AGENT_GRACE = Duration.ofSeconds(10);

    /** Minestom servers have the agent built in; the others have one when it is installed in the addon folder. */
    private boolean expectsAgent() {
        Platform platform = platform();
        return platform.addonDir() == null
                || Files.exists(config.serverDir().resolve(platform.addonDir()).resolve(platform.agentJar()));
    }

    private void pump(Process p) {
        try {
            LogReader.pump(p.getInputStream(), log, () -> sawDone = true);
        } catch (IOException ignored) {
            // Stream closed with the process.
        }
    }

    /** Clock time just before this core launched the running server, or null when none is running. */
    public synchronized java.time.Instant startedAt() {
        return running() ? launchedAt : null;
    }

    /**
     * The pid of a server an earlier core left running (detached, or orphaned by a killed core), without stopping
     * it, or -1. {@link #stopLeftover()} stops it.
     */
    public synchronized long leftoverPid() {
        if (running()) {
            return -1;
        }
        return leftovers.find().apply(pidFile()).map(ProcessHandle::pid).orElse(-1L);
    }

    /** Type a line into the server console. */
    public synchronized void console(String line) throws IOException {
        if (!running()) {
            throw new ToolException("server is not running");
        }
        OutputStream in = process.getOutputStream();
        in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    /** {@code .msc/server.pid}: the running server's pid and start time, so a later core can find it. */
    Path pidFile() {
        return config.dataDir().resolve("server.pid");
    }

    /**
     * Stop a server that an earlier core started and left running (on Windows a core killed with its Claude Code
     * session cannot stop its server, which then holds the world lock), before a start or a deploy. It is asked to
     * stop over RCON so it saves the world, and killed only if that fails. The pid file's start time must match, so a
     * reused pid is never touched. The pid file is kept until the server is known to be gone, and nothing thrown
     * while asking it to stop skips the kill (#394). Returns a one-line note, or null when there was none.
     *
     * <p>A leftover that survives the kill is a refusal ({@link ToolException}): nothing may start while it holds the
     * world and the port (#408). The pid file then records that it resisted, so the next start only kills it again
     * and waits {@link Leftovers#again} instead of the whole chain; {@link #stopLeftover(boolean)} with {@code true}
     * (server_stop) tries the whole chain again.
     */
    public String stopLeftover() throws InterruptedException {
        return stopLeftover(false);
    }

    /** {@link #stopLeftover()}; {@code whole} tries every way again, also on a leftover that resisted before. */
    public synchronized String stopLeftover(boolean whole) throws InterruptedException {
        if (running()) {
            return null;
        }
        Leftovers l = leftovers;
        ProcessHandle h = l.find().apply(pidFile()).orElse(null);
        if (h == null) {
            PidFile.take(pidFile()); // a stale file: its process is gone or the pid was reused
            return null;
        }
        long pid = h.pid();
        boolean saved = false;
        String tried;
        if (PidFile.resisting(pidFile()) && !whole) {
            // It already survived every way below: kill it once more, briefly, rather than wait it all out again.
            l.kill().accept(h);
            exits(h, l.again());
            tried = LeftoverServer.RESISTED;
        } else {
            // Its console belonged to the dead core, so ask over RCON first: "stop" saves the world. Then kill.
            saved = askToStop() && exits(h, l.rcon());
            if (!saved) {
                h.destroy();
                if (!exits(h, l.destroy())) {
                    l.kill().accept(h);
                    exits(h, l.tree());
                }
            }
            tried = LeftoverServer.TRIED;
        }
        if (h.isAlive()) {
            PidFile.markResisting(pidFile());
            throw new ToolException(LeftoverServer.stillRunning(pid, tried));
        }
        PidFile.take(pidFile());
        return "stopped a server left running by an earlier session (pid " + pid + ", "
                + (saved ? "saved over RCON)" : "killed: world changes since its last autosave may be lost)");
    }

    /** Asks the leftover server over RCON to stop; false when there is no RCON target or anything goes wrong. */
    private boolean askToStop() {
        try {
            RconTarget rcon = RconTarget.of(config.serverDir());
            return rcon != null && rcon.askToStop();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean exits(ProcessHandle h, Duration timeout) throws InterruptedException {
        try {
            h.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return !h.isAlive();
        }
    }

    /** Ask the server to stop, and kill it if it does not within the timeout. Returns false if it was not running. */
    public boolean stop(Duration timeout) throws InterruptedException {
        Process p;
        synchronized (this) {
            if (!running()) {
                return false;
            }
            p = process;
            detached = false;
            try {
                console("stop");
            } catch (IOException e) {
                p.destroy();
            }
        }
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            ProcessRunner.destroyTree(p.toHandle());
            p.waitFor(10, TimeUnit.SECONDS);
        }
        released(p);
        return true;
    }

    /**
     * Delete the pid file of {@code p}, which has exited, unless a newer server already replaced it: a start that ran
     * while a stop waited for the old process writes its own pid file, which must stay (#323).
     */
    synchronized void released(Process p) {
        if (process != p) {
            return;
        }
        try {
            Files.deleteIfExists(pidFile());
        } catch (IOException ignored) {
            // A stale file is checked against the process start time anyway.
        }
    }

    /** The current or last server process, for tests. */
    synchronized Process process() {
        return process;
    }

    /**
     * Leave the running server up when this core exits, so the user can keep playing. The pid file stays, so the
     * next core's start, dev_cycle or server_stop stops it (saved over RCON) before doing anything else.
     */
    public synchronized boolean detach() {
        if (closed) {
            throw ToolException.workspaceClosed("the server was not detached");
        }
        detached = running();
        return detached;
    }

    /** True while a detached server is still running: {@link #close()} leaves it alone. */
    public synchronized boolean detached() {
        return detached && running();
    }

    /**
     * The first half of {@link #close()}: from now on nothing starts or detaches a server, so whether the server
     * stays up after the session (detached) is settled. Returns that answer; the pack host's close goes by it.
     */
    public synchronized boolean seal() {
        closed = true;
        return detached();
    }

    /** Stop the server unless detached. Nothing starts after this, even from a call already running. */
    @Override
    public void close() {
        if (seal()) {
            return;
        }
        try {
            stop(Duration.ofSeconds(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
