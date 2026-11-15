package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ServerJava;
import io.github.imnotstable.msc.protocol.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
    /** Changed under this monitor; volatile for {@link #serverRun} and {@link #lastRun}. */
    private volatile Process process;
    /** Reads {@link #process}'s output; null before the first start. */
    private LogPump pump;
    private volatile boolean sawDone;
    private volatile boolean detached;
    /** {@link #stop} asked the running server to stop; a launch clears it. Under this monitor. */
    private boolean stopping;
    /** Set by {@link #close()} under this monitor; {@link #start} checks it under the same monitor (#282). */
    private boolean closed;
    /** Extra environment for the server process: tests set a locale here. */
    private Map<String, String> childEnvironment = Map.of();
    /** Clock time just before the running server's process was launched: files written later are not loaded. */
    private java.time.Instant launchedAt;
    private volatile Leftovers leftovers = Leftovers.REAL;
    /** This core, as its pid file records it ({@link PidFile.Identity}); tests stand in another core here. */
    private volatile PidFile.Identity self = PidFile.Identity.current();
    /** {@code .msc/server.pid}: every change to it goes through its lock ({@link ServerPidFile#locked}). */
    private final ServerPidFile record;
    /**
     * Held for a whole {@link #stopLeftover}, so two stops of one leftover in this core run one after the other. Taken
     * before this monitor, never inside it: the wait for the leftover to exit holds only this lock (#712).
     */
    private final ReentrantLock leftoverStop = new ReentrantLock();

    public ServerManager(CoreConfig config, AgentHub hub) {
        this.config = config;
        this.hub = hub;
        this.record = new ServerPidFile(config.dataDir());
    }

    /**
     * For tests: how long a call waits for another core's start or stop of the server before it refuses, instead of
     * {@link ServerPidFile#WAIT} ({@code ServerLockAccess} outside this package).
     */
    void lockWait(Duration wait) {
        record.waitLimit(wait);
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

    /** For tests: which core this manager stands for, so two managers can be two cores on one workspace. */
    void self(PidFile.Identity self) {
        this.self = self;
    }

    /**
     * A server another session's core started on this workspace and still runs (ADR 0037): its pid and that core's.
     * It belongs to that session while the core lives, so this one neither stops nor replaces it.
     */
    public record OtherSession(long serverPid, long corePid) {
        /** The refusal of a call that would stop or replace it: whose it is, what to do, and that nothing changed. */
        public String refusal() {
            return "the server (pid " + serverPid + ") belongs to another session that is still running (MSC core "
                    + "pid " + corePid + "): use that session, or end it (a server it detached then becomes this "
                    + "session's to stop or restart). Nothing was changed.";
        }
    }

    /**
     * The server another live core started on this workspace, or empty: also when this core's own server runs, when
     * the pid file names no live server, and when it names no owner or a dead one (a leftover, ADR 0013 point 8).
     * Owners are matched by pid and start time like the server, never by pid alone.
     */
    public synchronized Optional<OtherSession> otherSession() {
        if (running()) {
            return Optional.empty();
        }
        ProcessHandle server = leftovers.find().apply(pidFile()).orElse(null);
        if (server == null) {
            return Optional.empty();
        }
        PidFile.Identity me = self;
        return PidFile.owner(pidFile()).filter(o -> !o.equals(me) && o.live().isPresent())
                .map(o -> new OtherSession(server.pid(), o.pid()));
    }

    /**
     * Refuses ({@link OtherSession#refusal()}) while another live session's server runs on this workspace. A start
     * or stop another core is in the middle of is waited for first, so a core that is launching its server right now
     * is refused as that server's owner; one that takes over {@link ServerPidFile#WAIT} is a refusal of its own.
     * Either way nothing is changed.
     */
    public void checkNoOtherSession() throws InterruptedException {
        record.awaitIdle();
        refuseOtherSession();
    }

    /** {@link #checkNoOtherSession()} without the wait: inside the pid file's critical section. */
    private void refuseOtherSession() {
        Optional<OtherSession> other = otherSession();
        if (other.isPresent()) {
            throw new ToolException(other.get().refusal());
        }
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

    /**
     * Whether an agent of this session's server can still connect: its server runs and was not asked to stop (#703).
     * The hub is woken when either changes ({@link AgentHub#serverChanged}).
     */
    public synchronized boolean awaitsAgent() {
        return running() && !stopping;
    }

    /**
     * Why this session has no server to send to, the same answer as msc_status's server line (#705): this session's
     * server is stopping, another live session runs one, an earlier session left one running, or none runs. A running
     * server is asked this only once no agent of it is awaited: it was asked to stop, by {@link #stop} or by a
     * {@code stop} its agent announced ({@link AgentHub#refusal}), and "stopping" ends when its process does (#711).
     */
    public synchronized String notRunning() {
        if (running()) {
            return AgentHub.STOPPING;
        }
        Optional<OtherSession> other = otherSession();
        if (other.isPresent()) {
            return other.get().refusal();
        }
        long leftover = leftoverPid();
        if (leftover >= 0) {
            return "the server running here (pid " + leftover + ") was left by an earlier session and has no "
                    + "connection to this one: server_start or dev_cycle restarts it under this session, server_stop "
                    + "stops it";
        }
        return AgentHub.NOT_RUNNING;
    }

    /**
     * The server process this core started, while it runs; else null. Every start makes a new one and a stop, a crash
     * or an exit of its own ends it, so build_undo forgets the edits of an earlier one (#699). Takes no lock.
     */
    public Object serverRun() {
        Process p = process;
        return p != null && p.isAlive() ? p : null;
    }

    /**
     * The server process this core launched last, running or not, or null: the run a {@code server.stopping} belongs to
     * ({@link AgentHub#lastRun}). It changes in the critical section that launches the next one (#715). Takes no lock.
     */
    public Object lastRun() {
        return process;
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
                    + (platform.needsServerSetup() ? "Run server_setup first."
                    : startStep()));
        }
        if (platform.needsEula() && !acceptingEula && !eulaAccepted(eulaFile())
                && config.machineEula().read().isEmpty()) {
            throw eulaRefusal();
        }
    }

    /** What builds the Minestom server jar, which is the project's own: the next step names the project (#679, #680). */
    private String startStep() {
        WorkspaceProjects.Next next = WorkspaceProjects.next(config);
        return "Run " + next.call() + " to build the project and put its jar there" + next.note() + ".";
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

    /**
     * True when {@code eulaFile} says {@code eula=true} the way the server reads it: as a properties file, so
     * {@code eula = true} and {@code eula=TRUE} count too (#551).
     */
    public static boolean eulaAccepted(Path eulaFile) {
        try (InputStream in = Files.newInputStream(eulaFile)) {
            Properties p = new Properties();
            p.load(in);
            return Boolean.parseBoolean(p.getProperty("eula", "false"));
        } catch (IOException | IllegalArgumentException e) {
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
        LogPump startedPump;
        synchronized (this) {
            if (closed) {
                // A call that was already running when the workspace closed: nothing would stop this server.
                throw ToolException.workspaceClosed("the server did not start");
            }
            if (running()) {
                throw new ToolException("server is already running. Use server_stop or dev_cycle.");
            }
            // Check, launch and record in one critical section across cores (ADR 0037 point 3): a core starting at
            // the same moment waits, then finds this server and its live owner, and is refused (#490).
            mark = log.mark();
            started = record.locked(held -> launch(held, address, token));
            process = started;
            startedPump = LogPump.start(started, log, () -> sawDone = true, PUMP_DRAIN);
            pump = startedPump;
        }
        // A caller waiting for its agent learns of the exit at once, not when the wait runs out (#703).
        started.onExit().thenRun(hub::serverChanged);
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
            // pump reach the end of the output (#324, #533).
            ready = false;
            startedPump.awaitEnd();
        }
        return new StartResult(ready, exited, (System.nanoTime() - begin) / 1_000_000, mark, agentMillis, sawDone);
    }

    /** The checks that need the pid file's lock, then the launch and its record. Under this monitor. */
    private Process launch(ServerPidFile.Held held, String address, String token) throws IOException {
        refuseOtherSession();
        // A server an earlier core left running holds the world and the port: a new one would die on them, and
        // its pid would overwrite the only record of the one still running (#408).
        ProcessHandle left = leftovers.find().apply(pidFile()).orElse(null);
        if (left != null) {
            throw new ToolException(LeftoverServer.stillRunning(left.pid(), PidFile.resisting(pidFile())
                    ? LeftoverServer.RESISTED : LeftoverServer.NOT_STOPPED));
        }
        Files.createDirectories(config.serverDir());
        // MSC reads the output and writes commands as UTF-8; so must the server's Java (#466, #478).
        ProcessBuilder pb = ServerJava.processBuilder(command()).directory(config.serverDir().toFile())
                .redirectErrorStream(true);
        pb.environment().put(Limits.ENV_CORE_ADDRESS, address);
        pb.environment().put(Limits.ENV_TOKEN, token);
        pb.environment().putAll(childEnvironment);
        sawDone = false;
        // Surviving the session is asked for per server (ADR 0014): a detached server that exited on its own
        // must not pass that on to the next one.
        detached = false;
        stopping = false;
        launchedAt = java.time.Instant.now();
        Process p = pb.start();
        held.write(p.toHandle(), self);
        return p;
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

    /**
     * The one answer to "is the server gone and its log complete" (#533): false at once while a server runs, else
     * true once its log pump has read the whole output, bounded by {@link #PUMP_DRAIN} ({@link LogPump#awaitEnd}).
     */
    public boolean awaitLogEnd() throws InterruptedException {
        LogPump p;
        synchronized (this) {
            p = pump;
        }
        return p == null ? !running() : p.awaitEnd();
    }

    /**
     * Wait for the first log line from {@code mark} that {@code test} accepts. Null when {@code timeout} passes or
     * the server stops first; a server that stops has its log completed and checked once more, so a line it printed
     * just before it exited is found (#533).
     */
    public LogBuffer.Match awaitLine(long mark, Predicate<String> test, Duration timeout)
            throws InterruptedException {
        LogPump p;
        synchronized (this) {
            p = pump;
        }
        return p == null ? log.await(mark, test, Duration.ZERO, () -> false) : p.await(log, mark, test, timeout);
    }

    /** Clock time just before this core launched the running server, or null when none is running. */
    public synchronized java.time.Instant startedAt() {
        return running() ? launchedAt : null;
    }

    /** Clock time just before this core last launched a server, running or not, or null when it never did. */
    public synchronized java.time.Instant lastLaunch() {
        return launchedAt;
    }

    /**
     * The pid of a server an earlier core left running (detached, or orphaned by a killed core), without stopping
     * it, or -1. {@link #stopLeftover()} stops it. A server whose core still runs is no leftover ({@link #otherSession}).
     */
    public synchronized long leftoverPid() {
        if (running() || otherSession().isPresent()) {
            return -1;
        }
        return leftovers.find().apply(pidFile()).map(ProcessHandle::pid).orElse(-1L);
    }

    /** Adds {@code env} to the environment of servers started from now on (tests: a C locale). */
    synchronized void childEnvironment(Map<String, String> env) {
        childEnvironment = Map.copyOf(env);
    }

    /** Type a line into the server console. */
    public synchronized void console(String line) throws IOException {
        if (!running()) {
            throw new ToolException(notRunning());
        }
        OutputStream in = process.getOutputStream();
        in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    /**
     * {@code .msc/server.pid}: the running server's pid and start time, and the starting core's, so a later core can
     * find it and tell whether it is a leftover (ADR 0037).
     */
    Path pidFile() {
        return record.path();
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
     *
     * <p>A server whose core still runs is not a leftover: that is a refusal too, before anything is touched
     * ({@link #checkNoOtherSession}, ADR 0037).
     */
    public String stopLeftover() throws InterruptedException {
        return stopLeftover(false);
    }

    /**
     * {@link #stopLeftover()}; {@code whole} tries every way again, also on a leftover that resisted before. The
     * leftover is claimed under this monitor, so no start of this core's own server comes in between; the wait for it
     * to exit (up to 90 s) is outside it, so msc_status and the refusals answer meanwhile (#712). The pid file still
     * names it then, so a start in that window refuses ({@link #launch}).
     */
    public String stopLeftover(boolean whole) throws InterruptedException {
        leftoverStop.lockInterruptibly();
        try {
            return stopLeftoverHeld(whole);
        } finally {
            leftoverStop.unlock();
        }
    }

    private String stopLeftoverHeld(boolean whole) throws InterruptedException {
        Leftovers l = leftovers;
        ProcessHandle h;
        synchronized (this) {
            if (running() || !Files.exists(pidFile())) {
                return null;
            }
            // Claimed in the pid file's critical section: whose it is, and a stale file removed, with no other core's
            // start in between (#490). The wait for it to exit is outside: the file names it until it is gone.
            h = record.locked(held -> {
                refuseOtherSession();
                ProcessHandle found = l.find().apply(pidFile()).orElse(null);
                if (found == null) {
                    held.delete(); // a stale file: its process is gone or the pid was reused
                }
                return found;
            });
        }
        if (h == null) {
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
        // Another core that waited for the same leftover may have recorded its own server meanwhile: only a file
        // still naming this one is changed. The stop has happened by now, so a lock that cannot be had (another core
        // holds it too long) only leaves the record as it was, and the answer says what was done (#497).
        boolean alive = h.isAlive();
        boolean recorded = updateRecord(held -> {
            if (alive) {
                held.markResisting(pid);
            } else {
                held.deleteIfNames(pid);
            }
        });
        if (alive) {
            throw new ToolException(LeftoverServer.stillRunning(pid, tried));
        }
        return "stopped a server left running by an earlier session (pid " + pid + ", "
                + (saved ? "saved over RCON)" : "killed: world changes since its last autosave may be lost)")
                + (recorded ? "" : "; its record in " + pidFile() + " was not cleared, as another session held "
                + "the file's lock: the next start replaces it");
    }

    /** Changes the pid file in its critical section; false when the lock could not be had (nothing changed). */
    private boolean updateRecord(Consumer<ServerPidFile.Held> change) throws InterruptedException {
        try {
            record.locked(held -> {
                change.accept(held);
                return null;
            });
            return true;
        } catch (ToolException e) {
            return false;
        }
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
            stopping = true;
            try {
                console("stop");
            } catch (IOException e) {
                p.destroy();
            }
        }
        // Its agent leaves before the process exits: a call waiting for one fails now, not after the wait (#703).
        hub.serverChanged();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            ProcessRunner.destroyTree(p.toHandle());
            p.waitFor(10, TimeUnit.SECONDS);
        }
        released(p);
        return true;
    }

    /**
     * Delete the pid file of {@code p}, which has exited, unless a newer server already replaced it: a start that ran
     * while a stop waited for the old process writes its own pid file, which must stay (#323), also when another
     * core's start wrote it (ADR 0037).
     */
    synchronized void released(Process p) throws InterruptedException {
        if (process != p) {
            return;
        }
        // Checked and deleted in the pid file's critical section, so a start in between is never undone. When another
        // core holds the lock too long the file stays: a stale one is checked against the process start time anyway.
        updateRecord(held -> held.deleteIfNames(p.pid()));
    }

    /** The current or last server process, for tests. */
    synchronized Process process() {
        return process;
    }

    /**
     * Leave the running server up when this core exits, so the user can keep playing. The pid file stays, so the
     * next core's start, dev_cycle or server_stop stops it (saved over RCON) before doing anything else. Its owner
     * stays this core: while this core runs, other sessions refuse to touch it; once it ends, it is a leftover
     * (ADR 0037).
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
