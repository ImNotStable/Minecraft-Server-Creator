package io.github.imnotstable.msc.core;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.tools.ApiTools;
import io.github.imnotstable.msc.core.tools.LifecycleTools;
import io.github.imnotstable.msc.core.tools.MetaTools;
import io.github.imnotstable.msc.core.tools.TextureTools;
import io.github.imnotstable.msc.core.tools.WorldTools;
import io.github.imnotstable.msc.core.world.WorldService;
import java.io.IOException;

/** The core without a transport: services plus the tool registry. The MCP server and tests both sit on this. */
public final class Core implements AutoCloseable {
    public static final String VERSION = "0.1.0";

    private final CoreConfig config;
    private final AgentHub hub;
    private final ServerManager server;
    private final ResourcePack pack;
    private final ToolLog log;
    private final ToolRegistry registry;

    public Core(CoreConfig config) throws IOException {
        this.config = config;
        this.hub = new AgentHub(config.agentPort(), config.token());
        this.server = new ServerManager(config, hub);
        if (config.agentPort() == 0) {
            // No fixed address, so only a server this core starts can connect (MSC_CORE_ADDRESS attaches others).
            hub.agentExpected(server::running);
        }
        this.pack = new ResourcePack(config.dataDir().resolve("pack"), config.dataDir().resolve("build"),
                config.packHost(), config.packFormat());
        this.log = new ToolLog(config.toolLog());
        this.registry = new ToolRegistry(log);
        WorldService world = new WorldService(hub);
        ApiTools api = new ApiTools(config);
        MetaTools.register(registry, config, hub, server, log, api);
        LifecycleTools.register(registry, config, hub, server, new ProjectBuilder(config),
                new PaperDownloader(config.downloadsUrl(), config.dataDir().resolve("cache")),
                new FabricDownloader(config.fabricMetaUrl(), config.fabricMavenUrl(), config.dataDir().resolve("cache")));
        api.register(registry);
        WorldTools.register(registry, config, world);
        TextureTools.register(registry, hub, pack);
    }

    public CoreConfig config() {
        return config;
    }

    public AgentHub hub() {
        return hub;
    }

    public ServerManager server() {
        return server;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public ToolLog log() {
        return log;
    }

    @Override
    public void close() throws IOException {
        pack.close();
        server.close();
        hub.close();
    }
}
