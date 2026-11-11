package io.github.imnotstable.msc.agent.minestom;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.ExecutionType;
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
 * happens on a virtual thread and never delays startup.
 */
public final class MscAgent {
    /** Time a sliced job may use per tick. 20 ms of a 50 ms tick keeps the server at 20 TPS. */
    static final Duration TICK_BUDGET = Duration.ofMillis(20);
    static final Duration OP_TIMEOUT = Duration.ofMinutes(2);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Blocks of undo history kept, about 8 full-size edits. */
    static final long UNDO_BLOCKS = 8 * Limits.MAX_REGION_VOLUME;

    private static final System.Logger LOG = System.getLogger("msc-agent");

    private final ServerOps serverOps;
    private final OpRouter router;
    private final AgentInfo info;
    private final EventNode<PlayerEvent> events = EventNode.type("msc-agent", EventFilter.PLAYER);
    private volatile AgentClient.Session session;
    private volatile boolean loaded;
    private volatile boolean readySent;
    private volatile boolean stopping;

    private MscAgent() {
        TickRate ticks = new TickRate(20);
        MainThread main = new SchedulerMainThread(MinecraftServer.getSchedulerManager(), TICK_BUDGET, OP_TIMEOUT);
        ServerFacts facts = new MinestomServerFacts(ticks);
        Worlds worlds = new Worlds(() -> MinecraftServer.getInstanceManager().getInstances());
        serverOps = new ServerOps(MinecraftServer.getCommandManager(), worlds,
                () -> MinecraftServer.getConnectionManager().getOnlinePlayers(), main, facts);
        WorldOps worldOps = new WorldOps(worlds, main, new EditHistory(UNDO_BLOCKS), OP_TIMEOUT);
        router = AgentRoutes.router(serverOps, worldOps);
        info = new AgentInfo("minestom", facts.platformVersion(), facts.minecraftVersion(), router.operations());

        events.addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) {
                serverOps.onJoin(e.getPlayer());
                send(Events.PLAYER_JOIN, player(e.getPlayer()));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, e -> send(Events.PLAYER_QUIT, player(e.getPlayer())));
        MinecraftServer.getGlobalEventHandler().addChild(events);

        // Ticks only run once the server has started, so the first one marks it ready.
        MinecraftServer.getSchedulerManager().submitTask(() -> {
            ticks.tick(System.nanoTime());
            if (!loaded) {
                loaded = true;
                Thread.ofVirtual().name("msc-agent-ready").start(this::maybeSendReady);
            }
            return stopping ? TaskSchedule.stop() : TaskSchedule.nextTick();
        }, ExecutionType.TICK_START);
        MinecraftServer.getSchedulerManager().buildShutdownTask(this::stop);
    }

    /**
     * Starts the agent. Call once, after {@code MinecraftServer.init()}; before or after
     * {@code server.start(...)} both work.
     */
    public static MscAgent start() {
        MscAgent agent = new MscAgent();
        Thread.ofVirtual().name("msc-agent-connect").start(agent::connect);
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
        return session;
    }

    /** Tells the core the server is stopping and disconnects. Runs on server shutdown by itself. */
    public void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        MinecraftServer.getGlobalEventHandler().removeChild(events);
        AgentClient.Session s = session;
        if (s != null) {
            send(Events.SERVER_STOPPING, new JsonObject());
            s.connection().close(ErrorCodes.SHUTDOWN, "server stopping");
            session = null;
        }
    }

    private void connect() {
        long backoff = 500;
        for (int attempt = 1; attempt <= 8 && !stopping; attempt++) {
            try {
                CoreAddress address = CoreAddress.resolve().orElse(null);
                if (address == null) {
                    LOG.log(Level.INFO, Limits.ENV_CORE_ADDRESS + " is not set; msc agent is idle.");
                    return;
                }
                AgentClient.Session s = AgentClient.connect(address.host(), address.port(), address.token(), info,
                        router, new CloseListener(), CONNECT_TIMEOUT);
                session = s;
                LOG.log(Level.INFO, "Connected to msc-core, session " + s.sessionId());
                maybeSendReady();
                return;
            } catch (OpException e) {
                LOG.log(Level.ERROR, "msc-core refused the connection: " + e.getMessage());
                return;
            } catch (IllegalArgumentException e) {
                LOG.log(Level.ERROR, "Bad msc-core address: " + e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not reach msc-core (attempt " + attempt + "): " + e.getMessage());
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, 5000);
        }
    }

    private final class CloseListener implements Connection.Listener {
        @Override
        public void onClose(Throwable cause) {
            session = null;
            if (!stopping) {
                LOG.log(Level.WARNING, "Lost connection to msc-core" + (cause == null ? "" : ": " + cause.getMessage())
                        + "; reconnecting.");
                readySent = false;
                Thread.ofVirtual().name("msc-agent-reconnect").start(MscAgent.this::connect);
            }
        }
    }

    private synchronized void maybeSendReady() {
        if (loaded && !readySent && session != null) {
            readySent = true;
            send(Events.SERVER_READY, new JsonObject());
        }
    }

    private void send(String event, JsonObject data) {
        AgentClient.Session s = session;
        if (s == null) {
            return;
        }
        try {
            s.connection().sendEvent(event, data);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not send " + event + ": " + e.getMessage());
        }
    }

    private static JsonObject player(Player player) {
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getUsername());
        data.addProperty("uuid", player.getUuid().toString());
        return data;
    }
}
