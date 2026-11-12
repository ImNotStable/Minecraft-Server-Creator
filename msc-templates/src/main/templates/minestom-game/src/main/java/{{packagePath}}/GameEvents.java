package {{packageName}};

import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

/**
 * Hands Minestom events to {@link Game}. No rules here beyond deciding which events matter.
 * Minestom has no vanilla death drops, mob spawning or weather, so keep-inventory and "no natural spawns"
 * need no code: the server only does what this project tells it to.
 */
final class GameEvents {
    private GameEvents() {
    }

    static void register(EventNode<Event> events, Game game) {
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
        events.addListener(PlayerDisconnectEvent.class, event -> game.quit(event.getPlayer()));
    }
}
