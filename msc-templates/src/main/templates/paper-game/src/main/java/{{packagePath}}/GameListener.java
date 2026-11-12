package {{packageName}};

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Hands Bukkit events to {@link Game}. No rules here beyond deciding which events matter. */
public final class GameListener implements Listener {
    /** Spawns the world makes on its own. Plugin, command, egg and spawner spawns are not in this list. */
    private static final Set<SpawnReason> NATURAL = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.PATROL, SpawnReason.REINFORCEMENTS,
            SpawnReason.VILLAGE_INVASION, SpawnReason.TRAP);

    private final Game game;

    public GameListener(Game game) {
        this.game = game;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        game.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (game.config().keepInventory()) {
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(game.spawn(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            var to = event.getTo();
            game.moved(event.getPlayer(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!game.config().naturalSpawns() && NATURAL.contains(event.getSpawnReason())) {
            event.setCancelled(true);
        }
    }
}
