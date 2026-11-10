package io.github.imnotstable.msc.agent.paper;

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
import java.time.Duration;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Connects to msc-core when the core started this server ({@code MSC_CORE_ADDRESS} and
 * {@code MSC_TOKEN} are set) and otherwise stays idle. Connecting happens off the main thread so
 * it never delays startup.
 */
// Not final: MockBukkit loads plugins through a generated subclass.
public class MscAgentPlugin extends JavaPlugin implements Listener {
    /** Main-thread time a sliced job may use per tick. 20 ms of a 50 ms tick keeps the server at 20 TPS. */
    static final Duration TICK_BUDGET = Duration.ofMillis(20);
    static final Duration OP_TIMEOUT = Duration.ofMinutes(2);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Blocks of undo history kept, about 8 full-size edits. */
    static final long UNDO_BLOCKS = 8 * Limits.MAX_REGION_VOLUME;

    private ServerOps serverOps;
    private OpRouter router;
    private AgentInfo info;
    private volatile AgentClient.Session session;
    private volatile boolean loaded;
    private volatile boolean readySent;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        MainThread main = new BukkitMainThread(this, TICK_BUDGET, OP_TIMEOUT);
        ServerFacts facts = new PaperServerFacts(getServer());
        serverOps = new ServerOps(getServer(), main, facts);
        WorldOps worldOps = new WorldOps(getServer(), main, new EditHistory(UNDO_BLOCKS));
        router = AgentRoutes.router(serverOps, worldOps);
        info = new AgentInfo("paper", facts.platformVersion(), facts.minecraftVersion(), router.operations());
        getServer().getPluginManager().registerEvents(this, this);
        Thread.ofVirtual().name("msc-agent-connect").start(this::connect);
    }

    @Override
    public void onDisable() {
        stopping = true;
        AgentClient.Session s = session;
        if (s != null) {
            send(Events.SERVER_STOPPING, new JsonObject());
            s.connection().close(ErrorCodes.SHUTDOWN, "server stopping");
            session = null;
        }
    }

    /** The open session, or {@code null} when not connected. */
    public AgentClient.Session session() {
        return session;
    }

    private void connect() {
        long backoff = 500;
        for (int attempt = 1; attempt <= 8 && !stopping; attempt++) {
            try {
                CoreAddress address = CoreAddress.resolve().orElse(null);
                if (address == null) {
                    getLogger().info(Limits.ENV_CORE_ADDRESS + " is not set; agent is idle.");
                    return;
                }
                AgentClient.Session s = AgentClient.connect(address.host(), address.port(), address.token(), info,
                        router, new CloseListener(), CONNECT_TIMEOUT);
                session = s;
                getLogger().info("Connected to msc-core, session " + s.sessionId());
                maybeSendReady();
                return;
            } catch (OpException e) {
                getLogger().severe("msc-core refused the connection: " + e.getMessage());
                return;
            } catch (IllegalArgumentException e) {
                getLogger().severe("Bad msc-core address: " + e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not reach msc-core (attempt " + attempt + "): " + e.getMessage());
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
                getLogger().warning("Lost connection to msc-core" + (cause == null ? "" : ": " + cause.getMessage())
                        + "; reconnecting.");
                readySent = false;
                Thread.ofVirtual().name("msc-agent-reconnect").start(MscAgentPlugin.this::connect);
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
            getLogger().fine("Could not send " + event + ": " + e.getMessage());
        }
    }

    private static JsonObject player(Player player) {
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        return data;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        loaded = true;
        maybeSendReady();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        serverOps.onJoin(event.getPlayer());
        send(Events.PLAYER_JOIN, player(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        send(Events.PLAYER_QUIT, player(event.getPlayer()));
    }
}
