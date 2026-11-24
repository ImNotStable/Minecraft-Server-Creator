package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Loads an area's chunks on Folia before a world job (ADR 0100): each chunk loads asynchronously and gets a plugin chunk
 * ticket on its own region thread, so the chunks stay loaded, Folia merges them into one region, and a job can touch them
 * all from that region's thread. Tickets are removed on the owning region thread too.
 */
final class FoliaChunks {
    private final Plugin plugin;
    private final Duration timeout;

    FoliaChunks(Plugin plugin, Duration timeout) {
        this.plugin = plugin;
        this.timeout = timeout;
    }

    /** Load and hold every chunk of the block area, blocking until they are; the action releases them. */
    Runnable hold(World world, int minX, int minZ, int maxX, int maxZ) {
        List<int[]> chunks = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                chunks.add(new int[] {cx, cz});
            }
        }
        List<CompletableFuture<Void>> loads = new ArrayList<>(chunks.size());
        for (int[] c : chunks) {
            CompletableFuture<Void> loaded = new CompletableFuture<>();
            world.getChunkAtAsync(c[0], c[1], true, chunk -> {
                world.addPluginChunkTicket(c[0], c[1], plugin);
                loaded.complete(null);
            });
            loads.add(loaded);
        }
        Runnable release = () -> {
            for (int[] c : chunks) {
                try {
                    Bukkit.getRegionScheduler().execute(plugin, world, c[0], c[1],
                            () -> world.removePluginChunkTicket(c[0], c[1], plugin));
                } catch (IllegalStateException | IllegalArgumentException e) {
                    return; // the plugin is disabled: Folia drops its tickets itself
                }
            }
        };
        try {
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            release.run();
            throw new OpException(ErrorCodes.TIMEOUT, chunks.size() + " chunks did not load within " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release.run();
            throw new OpException(ErrorCodes.SHUTDOWN, "interrupted");
        } catch (ExecutionException e) {
            release.run();
            throw new OpException(ErrorCodes.INTERNAL, String.valueOf(e.getCause()));
        }
        return release;
    }

    /**
     * As {@link #hold}, for an edit, whose caller has no hook for afterwards: the tickets go once the longest edit could
     * have finished. shortcut: an edit holds its chunks for the op timeout plus a minute; add a release hook to
     * AgentWorld.loadArea if many edits in a row keep too many chunks loaded.
     */
    void holdForEdit(World world, int minX, int minZ, int maxX, int maxZ) {
        Runnable release = hold(world, minX, minZ, maxX, maxZ);
        long ticks = (timeout.toSeconds() + 60) * 20;
        try {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> release.run(), ticks);
        } catch (IllegalStateException | IllegalArgumentException e) {
            release.run();
        }
    }
}
