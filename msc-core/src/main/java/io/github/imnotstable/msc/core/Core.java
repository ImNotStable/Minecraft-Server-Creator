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
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.tools.ApiTools;
import io.github.imnotstable.msc.core.tools.BuildTools;
import io.github.imnotstable.msc.core.tools.LifecycleTools;
import io.github.imnotstable.msc.core.tools.MetaTools;
import io.github.imnotstable.msc.core.tools.PlayerTools;
import io.github.imnotstable.msc.core.tools.TextureTools;
import io.github.imnotstable.msc.core.tools.WorldTools;
import io.github.imnotstable.msc.core.world.WorldService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The core without a transport: services plus the tool registry. The MCP server and tests both sit on this.
 * Everything that belongs to one workspace lives in a {@link Bench}; {@code msc_workspace} swaps it while the tool
 * list, which the MCP client fetched once, stays the same.
 */
public final class Core implements AutoCloseable {
    public static final String VERSION = "0.1.0";

    /** The services of one workspace and the tools bound to them. */
    private record Bench(CoreConfig config, AgentHub hub, ServerManager server, ResourcePack pack,
                         PlayerTools players, ToolRegistry tools) {
        static Bench open(CoreConfig config, ToolLog log) throws IOException {
            AgentHub hub = new AgentHub(config.agentPort(), config.token());
            ServerManager server = new ServerManager(config, hub);
            if (config.agentPort() == 0) {
                // No fixed address, so only a server this core starts can connect (MSC_CORE_ADDRESS attaches others).
                hub.agentExpected(server::running);
            }
            ResourcePack pack = new ResourcePack(config.dataDir().resolve("pack"), config.dataDir().resolve("build"),
                    config.packHost(), config.packPort(), config.packFormat());
            ToolRegistry tools = new ToolRegistry(log);
            WorldService world = new WorldService(hub);
            ApiTools api = new ApiTools(config);
            MetaTools.register(tools, config, hub, server, log, api);
            LifecycleTools.register(tools, config, hub, server, pack, new ProjectBuilder(config),
                    new PaperDownloader(config.downloadsUrl(), config.dataDir().resolve("cache")),
                    new FabricDownloader(config.fabricMetaUrl(), config.fabricMavenUrl(),
                            config.dataDir().resolve("cache")));
            api.register(tools);
            WorldTools.register(tools, config, world);
            BuildTools.register(tools, config, world, new Builder(hub, world));
            TextureTools.register(tools, hub, pack, config);
            PlayerTools players = PlayerTools.register(tools, config, hub, world);
            return new Bench(config, hub, server, pack, players, tools);
        }

        /** Same as a session ending: a detached server and its pack host keep running, anything else stops. */
        void close() throws IOException {
            players.close();
            pack.close(server.detached());
            server.close();
            hub.close();
        }
    }

    private final ToolLog log;
    private final ToolRegistry registry;
    /** Tool calls hold the read lock, a workspace switch the write lock, so no call sees half a switch. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Bench bench;

    public Core(CoreConfig config) throws IOException {
        this.log = new ToolLog(config.toolLog());
        this.bench = Bench.open(config, log);
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
        List<String> warnings = SetupCheck.warnings(config);
        if (!warnings.isEmpty()) {
            registry.noticeOnce(String.join("\n", warnings));
        }
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
                try {
                    return bench.tools().tools().stream().filter(t -> t.name().equals(name)).findFirst()
                            .orElseThrow().handler().output(args);
                } finally {
                    lock.readLock().unlock();
                }
            }
        };
    }

    private String switchWorkspace(Args args) throws IOException {
        CoreConfig current = bench.config();
        if (!args.has("path")) {
            return "workspace: " + current.workspace();
        }
        Path target = current.resolve(args.str("path").strip());
        if (!Files.isDirectory(target)) {
            if (Files.exists(target)) {
                throw new ToolException(target + " is a file, not a folder");
            }
            if (!args.bool("create", false)) {
                throw new ToolException("no folder " + target + ". Check the path, or pass create=true to make it.");
            }
            Files.createDirectories(target);
        }
        target = target.toRealPath();
        if (target.equals(realOrSelf(current.workspace()))) {
            return "workspace is already " + target;
        }
        lock.writeLock().lock();
        try {
            Bench old = bench;
            boolean detached = old.server().detached();
            boolean wasRunning = old.server().running();
            // Close first: a fixed agent or pack port must be free before the new bench binds it.
            old.close();
            Bench next;
            try {
                next = Bench.open(old.config().withWorkspace(target), log);
            } catch (IOException | RuntimeException e) {
                bench = Bench.open(old.config(), log);
                throw new ToolException("could not open " + target + " (" + e.getMessage() + "); still in "
                        + old.config().workspace());
            }
            bench = next;
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
            lock.writeLock().unlock();
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

    public ToolLog log() {
        return log;
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            bench.close();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
