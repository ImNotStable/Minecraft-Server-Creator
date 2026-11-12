package {{packageName}};

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/** Hands Fabric API events to {@link Game}. No rules here beyond deciding which events matter. */
final class GameEvents {
    private GameEvents() {
    }

    static void register(Game game, Runnable onStop) {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> WorldRules.apply(server, game.config()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> onStop.run());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> game.join(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> game.quit(handler.getPlayer()));
        // Death keeps the items through the KEEP_INVENTORY gamerule; the respawn goes back to the current stage.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> game.sendToSpawn(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                game.tick(player);
            }
        });
    }
}
