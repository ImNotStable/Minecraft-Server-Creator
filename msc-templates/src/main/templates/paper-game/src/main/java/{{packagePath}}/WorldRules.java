package {{packageName}};

import org.bukkit.GameRules;
import org.bukkit.World;

/** Applies the world-wide rules from config: fixed time, clear weather, difficulty, spawns and keep inventory. */
final class WorldRules {
    private WorldRules() {
    }

    static void apply(World world, GameConfig config) {
        world.setDifficulty(config.difficulty());
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setTime(config.time());
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRules.KEEP_INVENTORY, config.keepInventory());
        boolean spawns = config.naturalSpawns();
        world.setGameRule(GameRules.SPAWN_MOBS, spawns);
        world.setGameRule(GameRules.SPAWN_PATROLS, spawns);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, spawns);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, spawns);
    }
}
