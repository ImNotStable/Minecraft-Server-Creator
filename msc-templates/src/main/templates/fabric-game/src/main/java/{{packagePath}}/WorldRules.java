package {{packageName}};

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;

/** Applies the world-wide rules from {@link GameConfig}: fixed time, clear weather, difficulty, spawns and keep inventory. */
final class WorldRules {
    private WorldRules() {
    }

    static void apply(MinecraftServer server, GameConfig config) {
        server.setDifficulty(config.difficulty(), true);
        GameRules rules = server.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.KEEP_INVENTORY, config.keepInventory(), server);
        boolean spawns = config.naturalSpawns();
        rules.set(GameRules.SPAWN_MOBS, spawns, server);
        rules.set(GameRules.SPAWN_PATROLS, spawns, server);
        rules.set(GameRules.SPAWN_PHANTOMS, spawns, server);
        rules.set(GameRules.SPAWN_WANDERING_TRADERS, spawns, server);
        // Clear weather for a long time; with ADVANCE_WEATHER off it stays that way.
        server.setWeatherParameters(1_000_000, 0, false, false);
        // Time in 26.x lives on world clocks; the vanilla command sets the overworld's.
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "time set " + config.time());
    }
}
