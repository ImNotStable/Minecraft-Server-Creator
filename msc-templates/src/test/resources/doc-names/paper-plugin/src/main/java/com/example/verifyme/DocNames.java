package com.example.verifyme;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses the names CLAUDE.md lists, so one the pinned Paper API does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static void offTheMainThread(JavaPlugin plugin) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                plugin.getServer().getScheduler().runTask(plugin, () -> { }));
    }

    static void keepLoaded(World world, JavaPlugin plugin, int chunkX, int chunkZ) {
        world.addPluginChunkTicket(chunkX, chunkZ, plugin);
    }
}
