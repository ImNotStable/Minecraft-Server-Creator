package io.github.imnotstable.msc.agent.fabric;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.time.Duration;
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
    /** Main-thread time sliced jobs may use per tick. 20 ms of a 50 ms tick keeps the server at 20 TPS. */
    static final Duration TICK_BUDGET = Duration.ofMillis(20);
    static final Duration OP_TIMEOUT = Duration.ofMinutes(2);
    /** Blocks of undo history kept, about 8 full-size edits. */
    static final long UNDO_BLOCKS = 8 * Limits.MAX_REGION_VOLUME;

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
            link.send(Events.PLAYER_JOIN, player(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                link.send(Events.PLAYER_QUIT, player(handler.getPlayer())));
    }

    private void starting(MinecraftServer server) {
        main = new TickMainThread(server::isSameThread, TICK_BUDGET, OP_TIMEOUT);
        Levels levels = new FabricLevels(server);
        ServerAccess access = new FabricServerAccess(server, levels);
        serverOps = new ServerOps(access, main);
        WorldOps worldOps = new WorldOps(levels, main, new EditHistory(UNDO_BLOCKS));
        OpRouter router = AgentRoutes.router(serverOps, worldOps);
        AgentInfo info = new AgentInfo(ServerOps.PLATFORM, access.platformVersion(), access.minecraftVersion(),
                router.operations());
        link = new AgentLink(info, router, CoreAddress::resolve, LOG);
        link.start();
    }

    private static JsonObject player(ServerPlayer player) {
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getPlainTextName());
        data.addProperty("uuid", player.getUUID().toString());
        return data;
    }
}
