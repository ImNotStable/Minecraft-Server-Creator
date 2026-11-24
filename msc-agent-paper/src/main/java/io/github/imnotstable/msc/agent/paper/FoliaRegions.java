package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.ChunkArea;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** {@link RegionThreads.Regions} on Folia's region scheduler (ADR 0100). */
final class FoliaRegions implements RegionThreads.Regions {
    private final Plugin plugin;

    FoliaRegions(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void schedule(String world, int cx, int cz, long delayTicks, Runnable task) {
        World w = world(world);
        if (delayTicks <= 0) {
            Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, task);
        } else {
            Bukkit.getRegionScheduler().runDelayed(plugin, w, cx, cz, t -> task.run(), delayTicks);
        }
    }

    @Override
    public boolean owns(ChunkArea area) {
        World w = world(area.world());
        return Bukkit.isOwnedByCurrentRegion(w, area.minChunkX(), area.minChunkZ(), area.maxChunkX(), area.maxChunkZ());
    }

    private static World world(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            NamespacedKey key = NamespacedKey.fromString(name);
            w = key == null ? null : Bukkit.getWorld(key);
        }
        if (w == null) {
            throw new IllegalArgumentException("no world named " + name);
        }
        return w;
    }
}
