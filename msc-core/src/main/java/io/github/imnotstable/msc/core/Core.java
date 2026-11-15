package io.github.imnotstable.msc.core;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.building.Builder;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.PathArg;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.tools.ApiTools;
import io.github.imnotstable.msc.core.tools.BackgroundWork;
import io.github.imnotstable.msc.core.tools.BuildTools;
import io.github.imnotstable.msc.core.tools.LifecycleTools;
import io.github.imnotstable.msc.core.tools.MetaTools;
import io.github.imnotstable.msc.core.tools.PlayerTools;
import io.github.imnotstable.msc.core.tools.TextureTools;
import io.github.imnotstable.msc.core.tools.WayArgs;
import io.github.imnotstable.msc.core.tools.WorldTools;
import io.github.imnotstable.msc.core.tools.WorldBackups;
import io.github.imnotstable.msc.core.world.WorldService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * The core without a transport: services plus the tool registry. The MCP server and tests both sit on this.
 * Everything that belongs to one workspace lives in a {@link Bench}; {@code msc_workspace} swaps it while the tool
 * list, which the MCP client fetched once, stays the same.
 *
 * <h2>Ownership model</h2>
 * Read this before changing a switch, the session end or the tool call path; SessionEndTest and
 * WorkspaceOwnershipTest check it per resource.
 *
 * <p>A {@link Bench} owns everything one workspace opens. {@link Bench#close} is the one place that closes it, once,
 * in this order:
 * <ol>
 * <li>background builds ({@link BackgroundWork}: project_scaffold warmups, dev_cycle's tests): interrupted, so
 * ProcessRunner kills their Gradle trees, and joined for a few seconds;</li>
 * <li>test player ({@link PlayerTools}): the bot's connection to the dev server;</li>
 * <li>server ({@link ServerManager#seal}): from here on nothing starts or detaches it, which settles whether it
 * outlives the session;</li>
 * <li>pack host ({@link ResourcePack}): the in-process HTTP server, and the standalone host process unless the
 * server is detached;</li>
 * <li>server process ({@link ServerManager#close}): stopped unless detached; its log pump thread ends with it;</li>
 * <li>agent connection ({@link AgentHub}): the agent session and the listening socket, and CoreServer's threads.</li>
 * </ol>
 * Each part marks itself closed under its own monitor first, and every method that opens something (a process, a
 * socket, a thread, a bot) checks that mark under the same monitor before opening it. So nothing opens after its
 * close, even from a call that was already past every earlier check when the close began (#282); such a call gets
 * {@link ToolException#workspaceClosed}. {@link Bench#open} closes what it opened when a later step fails.
 *
 * <p>Not owned by a workspace:
 * <ul>
 * <li>tool call threads: {@link #running}. {@link #close} waits up to {@link #lockWait} for them, interrupts the
 * ones still running, and then waits up to {@link #cleanupWait} for those to end: a call that runs a process on its
 * own thread (dev_cycle's jar build, server_setup's installer) kills its tree in ProcessRunner when interrupted, and
 * the JVM exits right after the close (#296);</li>
 * <li>temp folders (Scaffold, ServerSetup staging): the call that makes one deletes it in a finally;</li>
 * <li>caches: on disk under .msc/cache, kept across sessions on purpose, or plain heap objects of the bench; nothing
 * to close;</li>
 * <li>the tool log: no open file, it appends per call; a switch moves it;</li>
 * <li>the MCP transport and its executors: Main and McpBridge; the client closing stdin ends the session.</li>
 * </ul>
 *
 * <p>Lock order, outer to inner; nothing takes an outer lock while it holds an inner one:
 * <ol>
 * <li>{@link #lock}: a tool call holds the read lock for its whole run; a switch and {@link #close} take the write
 * lock, waiting at most {@link #lockWait}. A switch that does not get it fails; close interrupts the calls and goes
 * on without it.</li>
 * <li>{@link #benchGuard}: only to set {@link #closed} and hand over {@link #bench}; nothing is taken inside it.</li>
 * <li>the Bench's monitor ({@link Bench#close}); a second closer (a close during a switch) waits for the first.</li>
 * <li>one part's monitor at a time, in the close order above. A part holds its monitor only for bounded or
 * interruptible steps and never across a call's own long work (PlayerTools keeps its bot under a separate guard for
 * that). The one nesting: AgentHub's lock may ask {@link ServerManager#running}, so ServerManager reads the hub
 * before it takes its own monitor.</li>
 * </ol>
 */
public final class Core implements AutoCloseable {
    /** The build's project.version (a release's -Pversion), from the version.properties msc-core's build writes. */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (InputStream in = Core.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** The services of one workspace and the tools bound to them. */
    private record Bench(CoreConfig config, AgentHub hub, ServerManager server, ResourcePack pack,
                         PlayerTools players, ToolRegistry tools, BackgroundWork background, AtomicBoolean closed) {
        /** Opens a workspace's parts; when a step fails, closes those already opened (the agent socket). */
        static Bench open(CoreConfig config, ToolLog log, OpenCheck check) throws IOException {
            BackgroundWork background = new BackgroundWork();
            AgentHub hub = new AgentHub(config.agentPort(), config.token());
            try {
                Bench bench = wire(config, log, background, hub);
                check.check(config, hub);
                return bench;
            } catch (IOException | RuntimeException | Error e) {
                background.close();
                try {
                    hub.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }

        private static Bench wire(CoreConfig config, ToolLog log, BackgroundWork background, AgentHub hub) {
            ServerManager server = new ServerManager(config, hub);
            if (config.agentPort() == 0) {
                // No fixed address, so only a server this core starts can connect (MSC_CORE_ADDRESS attaches others).
                hub.agentExpected(server::running);
            }
            hub.noAgentReason(() -> server.platform().noWorldTools());
            ResourcePack pack = new ResourcePack(config.dataDir().resolve("pack"), config.dataDir().resolve("build"),
                    config.packHost(), config.packPort(), config.packFormat());
            ToolRegistry tools = new ToolRegistry(log);
            WorldService world = new WorldService(hub);
            ApiTools api = new ApiTools(config);
            MetaTools.register(tools, config, hub, server, log, api);
            WorldBackups backups = LifecycleTools.register(tools, config, hub, server, pack, new ProjectBuilder(config),
                    new PaperDownloader(config.downloadsUrl(), config.dataDir().resolve("cache"),
                            config.downloadStore()),
                    new FabricDownloader(config.fabricMetaUrl(), config.fabricMavenUrl(),
                            config.dataDir().resolve("cache")), background, world);
            api.register(tools);
            WorldTools.register(tools, config, world, backups);
            BuildTools.register(tools, config, world, new Builder(hub, world, backups::beforeBigEdit));
            TextureTools.register(tools, hub, pack, config);
            PlayerTools players = PlayerTools.register(tools, config, hub, world);
            // On a proxy every world, build and player tool answers one line before it sends, saves or stops anything,
            // whether or not it reaches the agent (ADR 0045, #591).
            tools.gate(WORLD_TOOLS, () -> server.platform().noWorldTools());
            return new Bench(config, hub, server, pack, players, tools, background, new AtomicBoolean());
        }

        /**
         * Same as a session ending: a detached server and its pack host keep running, anything else stops. Builds
         * first, so none outlives the core. Once only: a second caller (a close during a switch) waits for the first.
         */
        synchronized void close() throws IOException {
            if (closed.getAndSet(true)) {
                return;
            }
            // The order and the reasons are in the ownership model on Core.
            background.close();
            players.close();
            pack.close(server.seal());
            server.close();
            hub.close();
        }
    }

    /** A last step of opening a bench; tests use it to make a switch fail after the parts were opened. */
    @FunctionalInterface
    interface OpenCheck {
        void check(CoreConfig config, AgentHub hub) throws IOException;
    }

    /** The tools that need a world (to read, edit, save or roll back, or to join): refused on a proxy. */
    static final List<String> WORLD_TOOLS = List.of("world_slice", "world_heightmap", "world_render", "world_summary",
            "world_entities", "build_undo", "structure_save", "build_batch", "player_test");

    private final ToolLog log;
    private final ToolRegistry registry;
    /** Tool calls hold the read lock, a workspace switch the write lock, so no call sees half a switch. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    /** The threads inside a forwarded call and the tool each runs, so a switch can name them and close stop them. */
    private final Map<Thread, String> running = new ConcurrentHashMap<>();
    /**
     * How long a switch or close waits for running calls. Bounded: a long call (server_wait, dev_cycle) must not hold
     * up the switch, every call queued behind it, or the exit when the session ends.
     */
    private volatile Duration lockWait = Duration.ofSeconds(5);
    /**
     * How long close waits, after interrupting the calls, for them to clean up. Plenty: an interrupted call only has
     * to kill its process tree (ProcessRunner), which takes milliseconds; the bound is for a call that ignores the
     * interrupt.
     */
    private volatile Duration cleanupWait = Duration.ofSeconds(5);
    /** Guards the hand-over of {@link #bench} against {@link #close}, which may run without the write lock. */
    private final Object benchGuard = new Object();
    private volatile Bench bench;
    /** Set once by {@link #close}; a switch still under way then closes what it opened instead of installing it. */
    private volatile boolean closed;
    private volatile OpenCheck openCheck = (config, hub) -> {
    };

    public Core(CoreConfig config) throws IOException {
        this.log = new ToolLog(config.toolLog());
        this.bench = Bench.open(config, log, openCheck);
        this.registry = new ToolRegistry(log);
        for (Tool tool : bench.tools().tools()) {
            registry.add(new Tool(tool.name(), tool.description(), tool.inputSchema(), forward(tool.name())));
        }
        registry.add("msc_workspace",
                "Switch MSC to another project folder without a restart. Stops the old dev server unless detached. "
                        + "No path: show the current one.",
                Schema.of().opt("path", "string", "Folder, absolute or relative")
                        .opt("create", "boolean", "Create it if missing"),
                this::switchWorkspace);
        // Worked out for the workspace the first result is about: a first call that switches away from the MSC repo
        // gets no warning to switch (#633).
        registry.noticeUnlessPending(() -> String.join("\n", SetupCheck.warnings(bench.config())));
    }

    /** A handler that runs the current bench's tool of that name. */
    private Tool.Handler forward(String name) {
        return new Tool.Handler() {
            @Override
            public String call(Args args) throws Exception {
                return output(args).text();
            }

            @Override
            public Tool.Output output(Args args) throws Exception {
                lock.readLock().lock();
                running.put(Thread.currentThread(), name);
                try {
                    if (closed) {
                        throw new ToolException("the session has ended; " + name + " did not run");
                    }
                    return bench.tools().tools().stream().filter(t -> t.name().equals(name)).findFirst()
                            .orElseThrow().handler().output(args);
                } catch (InterruptedException e) {
                    if (!closed) {
                        throw e;
                    }
                    // Only close interrupts a call. The interrupt was for this call, which ends here with a reason.
                    throw new ToolException(name + " was stopped: the session ended while it ran");
                } finally {
                    running.remove(Thread.currentThread());
                    lock.readLock().unlock();
                }
            }
        };
    }

    /** For tests: how long a switch or close waits for running calls. */
    void lockWait(Duration wait) {
        this.lockWait = wait;
    }

    /** For tests: how long close waits for the calls it interrupted to clean up. */
    void cleanupWait(Duration wait) {
        this.cleanupWait = wait;
    }

    /** The write lock within {@link #lockWait}, or null when calls still run (also when interrupted). */
    private Lock writeLock() {
        Lock write = lock.writeLock();
        try {
            return write.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS) ? write : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** For tests: the last step of every later bench open (a switch, and its reopen of the old folder). */
    void openCheck(OpenCheck check) {
        this.openCheck = check;
    }

    /** For tests: the current bench's own handler of a tool, which skips the session checks of {@link #forward}. */
    Tool.Handler benchHandler(String name) {
        return bench.tools().tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow().handler();
    }

    /** For tests: the current bench's resource pack and test player. */
    ResourcePack pack() {
        return bench.pack();
    }

    PlayerTools players() {
        return bench.players();
    }

    /** For tests: add a tool to the current bench, forwarded like the others (a switch drops it). */
    void addBenchTool(Tool tool) {
        bench.tools().add(tool);
        registry.add(new Tool(tool.name(), tool.description(), tool.inputSchema(), forward(tool.name())));
    }

    /**
     * Make {@code next} the bench, unless the session ended meanwhile: then close it, so nothing opened by a switch
     * outlives the close that did not wait for the switch.
     */
    private void install(Bench next) throws IOException {
        synchronized (benchGuard) {
            if (!closed) {
                bench = next;
                return;
            }
        }
        next.close();
        throw new ToolException("did not switch: the session ended during the switch");
    }

    private String runningCalls() {
        return running.values().stream().sorted().collect(Collectors.joining(", "));
    }

    private String switchWorkspace(Args args) throws IOException {
        CoreConfig current = bench.config();
        WayArgs.check(args, java.util.Set.of(args.has("path") ? "switch" : "show"), WayArgs.WORKSPACE);
        if (!args.has("path")) {
            return "workspace: " + current.workspace();
        }
        Path target = PathArg.anywhere(current.workspace()).resolve("path", args.str("path").strip());
        if (!Files.isDirectory(target)) {
            if (Files.exists(target)) {
                throw new ToolException(target + " is a file, not a folder");
            }
            if (!args.bool("create", false)) {
                throw new ToolException("no folder " + target + ". Check the path, or pass create=true to make it.");
            }
            PathArg.createFolders(target, "folder");
        }
        target = target.toRealPath();
        if (target.equals(realOrSelf(current.workspace()))) {
            return "workspace is already " + target;
        }
        Lock write = writeLock();
        if (write == null) {
            String calls = runningCalls();
            throw new ToolException("did not switch: " + (calls.isEmpty() ? "another call" : calls)
                    + " is still running and a switch waits for it. Try again when it ends; still in "
                    + current.workspace());
        }
        try {
            if (closed) {
                throw new ToolException("did not switch: the session has ended");
            }
            // Another switch may have run while this one waited for the lock.
            if (target.equals(realOrSelf(bench.config().workspace()))) {
                return "workspace is already " + target;
            }
            Bench old = bench;
            boolean detached = old.server().detached();
            boolean wasRunning = old.server().running();
            // Close first: a fixed agent or pack port must be free before the new bench binds it.
            old.close();
            Bench next;
            try {
                next = Bench.open(old.config().withWorkspace(target), log, openCheck);
            } catch (IOException | RuntimeException e) {
                install(Bench.open(old.config(), log, openCheck));
                throw new ToolException("could not open " + target + " (" + e.getMessage() + "); still in "
                        + old.config().workspace());
            }
            install(next);
            log.moveTo(next.config().toolLog());
            // A switch into the MSC repo says so in this result (#633).
            registry.noticeUnlessPending(() -> SetupCheck.workspaceWarning(bench.config()));
            StringBuilder out = new StringBuilder("workspace: ").append(target);
            if (detached) {
                out.append("\nthe detached server of ").append(old.config().workspace()).append(" keeps running");
            } else if (wasRunning) {
                out.append("\nstopped the dev server of ").append(old.config().workspace());
            }
            out.append("\nserver folder: ").append(next.config().serverDir())
                    .append("; platform: ").append(next.server().platform().id());
            return out.toString();
        } finally {
            write.unlock();
        }
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    public CoreConfig config() {
        return bench.config();
    }

    public AgentHub hub() {
        return bench.hub();
    }

    public ServerManager server() {
        return bench.server();
    }

    public ToolRegistry registry() {
        return registry;
    }

    /**
     * Ends the session. Waits at most {@link #lockWait} for running calls, then interrupts them and closes anyway:
     * stopping the server also ends a server_wait or a dev_cycle start, and the bench stops the builds calls left
     * running, so nothing outlives the session, even a call that ignores the interrupt. Then it waits up to
     * {@link #cleanupWait} for the interrupted calls to end. The guarantee: when this returns, every interrupted call
     * that honours the interrupt has ended, and ProcessRunner has sent a forced kill (SIGKILL, TerminateProcess) to
     * each process of its tree, so none of them runs on after the JVM exits. The operating system may still list a
     * killed process for a moment (an orphan stays a zombie until init reaps it); it does no more work. Only a call
     * that ignores the interrupt for longer than {@link #cleanupWait} is not waited for. A switch under way closes
     * the bench it opened instead of installing it; one still waiting for the lock does not switch.
     */
    @Override
    public void close() throws IOException {
        // First, so no new call runs and no switch installs a bench while this waits.
        synchronized (benchGuard) {
            closed = true;
        }
        Lock write = writeLock();
        if (write == null) {
            running.keySet().forEach(Thread::interrupt);
        }
        Bench current = bench;
        try {
            // A switch closing this same bench is waited for; its server stop then counts for this close too.
            current.close();
        } finally {
            if (write != null) {
                write.unlock();
            } else {
                awaitInterruptedCalls();
            }
        }
    }

    /**
     * Waits up to {@link #cleanupWait} for the calls close interrupted to end: each holds the read lock until it has
     * cleaned up (ProcessRunner kills its process tree first), so getting the write lock means they all did. Call
     * threads may be pooled, so this waits for the calls, not for their threads to die.
     */
    private void awaitInterruptedCalls() {
        Lock write = lock.writeLock();
        try {
            if (write.tryLock(cleanupWait.toMillis(), TimeUnit.MILLISECONDS)) {
                write.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
