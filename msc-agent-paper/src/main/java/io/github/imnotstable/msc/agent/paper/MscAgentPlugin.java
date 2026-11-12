package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.AgentDefaults;
import io.github.imnotstable.msc.agent.common.AgentLink;
import io.github.imnotstable.msc.agent.common.AgentLog;
import io.github.imnotstable.msc.agent.common.AgentRoutes;
import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.util.logging.Logger;
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
    private ServerOps serverOps;
    private AgentLink link;

    @Override
    public void onEnable() {
        MainThread main = new BukkitMainThread(this, AgentDefaults.TICK_BUDGET, AgentDefaults.OP_TIMEOUT);
        PaperServer server = new PaperServer(getServer(), new PaperServerFacts(getServer()));
        serverOps = new ServerOps(server, main);
        WorldOps<?> worldOps = new WorldOps<>(new PaperWorlds(getServer()), main, new EditHistory<>(AgentDefaults.UNDO_BLOCKS));
        OpRouter router = AgentRoutes.router(serverOps, worldOps);
        AgentInfo info = new AgentInfo(PaperServer.PLATFORM, server.platformVersion(), server.minecraftVersion(),
                router.operations());
        getServer().getPluginManager().registerEvents(this, this);
        link = new AgentLink(info, router, log(getLogger()));
        link.start();
    }

    @Override
    public void onDisable() {
        if (link != null) {
            link.stop();
        }
    }

    /** The open session, or {@code null} when not connected. */
    public AgentClient.Session session() {
        return link == null ? null : link.session();
    }

    private static AgentLog log(Logger logger) {
        return new AgentLog() {
            @Override
            public void debug(String message) {
                logger.fine(message);
            }

            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warning(message);
            }

            @Override
            public void error(String message) {
                logger.severe(message);
            }
        };
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        link.serverLoaded();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        serverOps.currentPack().ifPresent(pack -> event.getPlayer().sendResourcePacks(PaperServer.request(pack)));
        link.playerJoined(event.getPlayer().getName(), event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        link.playerQuit(event.getPlayer().getName(), event.getPlayer().getUniqueId());
    }
}
