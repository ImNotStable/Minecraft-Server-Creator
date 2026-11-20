package com.example.verifyme;

import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

    /** The NMS handles CLAUDE.md names; a test that reaches them under MockBukkit fails with NoClassDefFoundError. */
    static Object[] handles(Player player, World world, Entity entity) {
        try {
            return new Object[] {((CraftPlayer) player).getHandle(), ((CraftWorld) world).getHandle(),
                    ((CraftEntity) entity).getHandle()};
        } catch (NoClassDefFoundError e) {
            return new Object[0];
        }
    }
}
