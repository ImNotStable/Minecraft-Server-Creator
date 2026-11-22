package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.Executor;

/** {@link Scheduler} on Bukkit's scheduler. shortcut: not Folia-aware (no region threads); add when a Folia project needs it. */
public final class PaperScheduler implements Scheduler {
    private final Plugin plugin;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Executor sync() {
        return r -> {
            if (plugin.getServer().isPrimaryThread()) {
                r.run();
            } else {
                plugin.getServer().getScheduler().runTask(plugin, r);
            }
        };
    }

    @Override
    public Executor async() {
        return r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
    }

    @Override
    public Task later(long delayTicks, Runnable task) {
        return wrap(plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public Task repeat(long delayTicks, long periodTicks, Runnable task) {
        return wrap(plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    private static Task wrap(BukkitTask t) {
        return new Task() {
            @Override
            public void cancel() {
                t.cancel();
            }

            @Override
            public boolean cancelled() {
                return t.isCancelled();
            }
        };
    }
}
