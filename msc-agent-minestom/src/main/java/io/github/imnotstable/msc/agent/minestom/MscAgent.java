package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.AgentDefaults;
import io.github.imnotstable.msc.agent.common.AgentLink;
import io.github.imnotstable.msc.agent.common.AgentLog;
import io.github.imnotstable.msc.agent.common.AgentRoutes;
import io.github.imnotstable.msc.agent.common.CoreAddress;
import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SavedPack;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

/**
 * Embeds the msc agent in a Minestom server. Call {@link #start()} once after
 * {@code MinecraftServer.init()}, and name the instances the AI should see with
 * {@link #name(Instance, String)}:
 *
 * <pre>{@code
 * MinecraftServer server = MinecraftServer.init();
 * InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
 * MscAgent.name(world, "world");
 * MscAgent.start();
 * server.start("127.0.0.1", 25565);
 * }</pre>
 *
 * <p>The agent connects to msc-core when the core started this server ({@code MSC_CORE_ADDRESS} and
 * {@code MSC_TOKEN} are set, or the {@code msc.core.address} and {@code msc.token} system
 * properties) and otherwise stays idle, so it is safe to leave in a production build. Connecting
 * happens on a virtual thread and never delays startup. While the core runs the server, the agent also adds a
 * console on standard input (msc-core types {@code stop} and server_command there) and a
 * {@code /tp <player> <x> <y> <z>} command on the first tick when the server has none by then, so the AI's test player can move as on other
 * platforms.
 */
public final class MscAgent {
    private final ServerOps serverOps;
    private final AgentLink link;
    private final EventNode<PlayerEvent> events = EventNode.type("msc-agent", EventFilter.PLAYER);
    private volatile boolean loaded;

    private MscAgent() {
        TickRate ticks = new TickRate(20);
        MainThread main = new SchedulerMainThread(MinecraftServer.getSchedulerManager(), AgentDefaults.TICK_BUDGET,
                AgentDefaults.OP_TIMEOUT);
        Worlds worlds = new Worlds(() -> MinecraftServer.getInstanceManager().getInstances(), AgentDefaults.OP_TIMEOUT);
        MinestomServer server = new MinestomServer(MinecraftServer.getCommandManager(), worlds,
                () -> MinecraftServer.getConnectionManager().getOnlinePlayers(), new MinestomServerFacts(ticks));
        serverOps = new ServerOps(server, main);
        WorldOps<?> worldOps = new WorldOps<>(worlds, main, new EditHistory<>(AgentDefaults.UNDO_BLOCKS));
        OpRouter router = AgentRoutes.router(serverOps, worldOps);
        AgentInfo info = new AgentInfo(server.platform(), server.platformVersion(), server.minecraftVersion(),
                router.operations());
        link = new AgentLink(info, router, AgentLog.of(System.getLogger("msc-agent")));

        events.addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) {
                Player player = e.getPlayer();
                serverOps.currentPack().ifPresent(pack -> player.sendResourcePacks(MinestomServer.request(pack)));
                link.playerJoined(player.getUsername(), player.getUuid());
            }
        });
        events.addListener(PlayerDisconnectEvent.class, e -> link.playerQuit(e.getPlayer().getUsername(), e.getPlayer().getUuid()));
        MinecraftServer.getGlobalEventHandler().addChild(events);
        // msc-core stops the server and runs server_command through standard input. The test player moves with /tp,
        // which Minestom does not have: give the dev server one once it runs, so a tp the server registers after
        // start() wins instead of failing to register.
        if (CoreAddress.resolve().isPresent()) {
            Console.start();
            DevTeleport.registerOnFirstTick(MinecraftServer.getSchedulerManager(), MinecraftServer.getCommandManager(),
                    MinecraftServer.getConnectionManager()::getOnlinePlayerByUsername);
        }

        // Ticks only run once the server has started, so the first one marks it ready.
        SchedulerMainThread.fromNextTick(MinecraftServer.getSchedulerManager(), () -> {
            ticks.tick(System.nanoTime());
            if (!loaded) {
                loaded = true;
                Thread.ofVirtual().name("msc-agent-ready").start(link::serverLoaded);
            }
            return link.stopping() ? TaskSchedule.stop() : TaskSchedule.nextTick();
        });
        MinecraftServer.getSchedulerManager().buildShutdownTask(this::stop);
    }

    /**
     * Starts the agent. Call once, after {@code MinecraftServer.init()}; before or after
     * {@code server.start(...)} both work.
     */
    public static MscAgent start() {
        MscAgent agent = new MscAgent();
        agent.link.start();
        // Started by hand, with no MSC session: serve the pack MSC saved beside the server (ADR 0076).
        SavedPack.serveIfIdle(java.nio.file.Path.of(""), agent.serverOps, AgentLog.of(System.getLogger("msc-agent")));
        return agent;
    }

    /**
     * Gives an instance the world name the AI uses for it (for example {@code "world"}). Unnamed
     * instances are known by their UUID.
     */
    public static void name(Instance instance, String name) {
        instance.setTag(Worlds.NAME, name);
    }

    /** The open session, or {@code null} when not connected. */
    public AgentClient.Session session() {
        return link.session();
    }

    /** Tells the core the server is stopping and disconnects. Runs on server shutdown by itself. */
    public void stop() {
        if (link.stopping()) {
            return;
        }
        MinecraftServer.getGlobalEventHandler().removeChild(events);
        link.stop();
    }
}
