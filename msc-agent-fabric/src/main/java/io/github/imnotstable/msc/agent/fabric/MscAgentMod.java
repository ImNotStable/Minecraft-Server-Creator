package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.agent.common.AgentDefaults;
import io.github.imnotstable.msc.agent.common.AgentLink;
import io.github.imnotstable.msc.agent.common.AgentLog;
import io.github.imnotstable.msc.agent.common.AgentRoutes;
import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. When the server starts, connects to msc-core if the core started this server
 * ({@code MSC_CORE_ADDRESS} and {@code MSC_TOKEN} are set) and otherwise stays idle. Connecting
 * happens off the server thread so it never delays startup.
 */
public final class MscAgentMod implements DedicatedServerModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("msc-agent");

    private TickMainThread main;
    private ServerOps serverOps;
    private AgentLink link;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::starting);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> link.serverLoaded());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            link.stop();
            main.stop();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> main.tick());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            serverOps.currentPack().ifPresent(pack -> FabricServerAccess.send(player, pack));
            link.playerJoined(player.getPlainTextName(), player.getUUID());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                link.playerQuit(handler.getPlayer().getPlainTextName(), handler.getPlayer().getUUID()));
    }

    private void starting(MinecraftServer server) {
        main = new TickMainThread(server::isSameThread, AgentDefaults.TICK_BUDGET, AgentDefaults.OP_TIMEOUT);
        FabricLevels levels = new FabricLevels(server);
        FabricServerAccess access = new FabricServerAccess(server, levels);
        serverOps = new ServerOps(access, main);
        WorldOps<?> worldOps = new WorldOps<>(levels, main, new EditHistory<>(AgentDefaults.UNDO_BLOCKS));
        OpRouter router = AgentRoutes.router(serverOps, worldOps);
        AgentInfo info = new AgentInfo(access.platform(), access.platformVersion(), access.minecraftVersion(),
                router.operations());
        link = new AgentLink(info, router, log());
        link.start();
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
