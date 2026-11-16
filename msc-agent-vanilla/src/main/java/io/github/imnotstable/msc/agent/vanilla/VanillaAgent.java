package io.github.imnotstable.msc.agent.vanilla;

import io.github.imnotstable.msc.agent.common.AgentDefaults;
import io.github.imnotstable.msc.agent.common.AgentLink;
import io.github.imnotstable.msc.agent.common.AgentLog;
import io.github.imnotstable.msc.agent.common.AgentRoutes;
import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent's life on one server. A loader's entry point forwards its server events here: {@link #starting} when the
 * server starts, {@link #tick} at the end of every tick, and so on. When the server starts it connects to msc-core if
 * the core started this server ({@code MSC_CORE_ADDRESS} and {@code MSC_TOKEN} are set) and otherwise stays idle.
 * Connecting happens off the server thread so it never delays startup.
 */
public final class VanillaAgent {
    private static final Logger LOG = LoggerFactory.getLogger("msc-agent");

    private final Loader loader;
    private TickMainThread main;
    private ServerOps serverOps;
    private AgentLink link;

    public VanillaAgent(Loader loader) {
        this.loader = loader;
    }

    /** The server is starting: build the operations and connect to the core. */
    public void starting(MinecraftServer server) {
        main = new TickMainThread(server::isSameThread, server, AgentDefaults.TICK_BUDGET, AgentDefaults.OP_TIMEOUT);
        VanillaLevels levels = new VanillaLevels(server);
        VanillaServer access = new VanillaServer(server, levels, loader);
        serverOps = new ServerOps(access, main);
        WorldOps<?> worldOps = new WorldOps<>(levels, main, new EditHistory<>(AgentDefaults.UNDO_BLOCKS));
        OpRouter router = AgentRoutes.router(serverOps, worldOps);
        AgentInfo info = new AgentInfo(access.platform(), access.platformVersion(), access.minecraftVersion(),
                router.operations());
        link = new AgentLink(info, router, log());
        link.start();
    }

    /** The worlds are loaded and the server takes players. */
    public void started() {
        if (link != null) {
            link.serverLoaded();
        }
    }

    /** The server is stopping: disconnect and fail what is still queued. */
    public void stopping() {
        if (link != null) {
            link.stop();
        }
        if (main != null) {
            main.stop();
        }
    }

    /** End of a server tick, on the main thread. */
    public void tick() {
        if (main != null) {
            main.tick();
        }
    }

    public void joined(ServerPlayer player) {
        if (link == null) {
            return;
        }
        serverOps.currentPack().ifPresent(pack -> VanillaServer.send(player, pack));
        link.playerJoined(player.getPlainTextName(), player.getUUID());
    }

    public void quit(ServerPlayer player) {
        if (link != null) {
            link.playerQuit(player.getPlainTextName(), player.getUUID());
        }
    }

    private static AgentLog log() {
        return new AgentLog() {
            @Override
            public void debug(String message) {
                LOG.debug(message);
            }

            @Override
            public void info(String message) {
                LOG.info(message);
            }

            @Override
            public void warn(String message) {
                LOG.warn(message);
            }

            @Override
            public void error(String message) {
                LOG.error(message);
            }
        };
    }
}
