package {{packageName}};

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.network.player.GameProfile;

/**
 * Hands Minestom events to {@link Game}. No rules here beyond deciding which events matter.
 * Minestom has no vanilla death drops, mob spawning or weather, so keep-inventory and "no natural spawns"
 * need no code: the server only does what this project tells it to.
 */
final class GameEvents {
    private GameEvents() {
    }

    /**
     * The UUID vanilla gives a player in offline mode. Without Mojang auth Minestom gives a random UUID at every
     * login, so progress saved by UUID would be lost each time a player reconnects.
     */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    static void register(EventNode<Event> events, Game game) {
        events.addListener(AsyncPlayerPreLoginEvent.class, event -> {
            if (MinecraftServer.process() == null || MinecraftServer.process().auth() instanceof Auth.Offline) {
                GameProfile p = event.getGameProfile();
                event.setGameProfile(new GameProfile(offlineUuid(p.name()), p.name(), p.properties()));
            }
        });
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(game.world());
            event.getPlayer().setRespawnPoint(game.spawn(event.getPlayer()));
        });
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                game.join(event.getPlayer());
            }
        });
        events.addListener(PlayerRespawnEvent.class, event -> event.setRespawnPosition(game.spawn(event.getPlayer())));
        events.addListener(PlayerMoveEvent.class, event -> {
            if (!event.getNewPosition().sameBlock(event.getPlayer().getPosition())) {
                game.moved(event.getPlayer(), event.getNewPosition());
            }
        });
        // Minestom fires this on the network thread for a player who left outside of play, so hop to the tick.
        events.addListener(PlayerDisconnectEvent.class,
                event -> game.world().scheduleNextTick(w -> game.quit(event.getPlayer())));
    }
}
