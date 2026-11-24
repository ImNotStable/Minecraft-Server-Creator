package io.github.imnotstable.msc.stdlib.bungeecord;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.api.scheduler.TaskScheduler;

/** {@link Scheduler} on BungeeCord's: a proxy has no main thread, so sync and async both run on its task pool. */
public final class BungeeScheduler implements Scheduler {
    private final Plugin plugin;
    private final TaskScheduler tasks;

    public BungeeScheduler(Plugin plugin, TaskScheduler tasks) {
        this.plugin = plugin;
        this.tasks = tasks;
    }

    @Override
    public Executor sync() {
        return r -> tasks.runAsync(plugin, r);
    }

    @Override
    public Executor async() {
        return sync();
    }

    @Override
    public Task later(long delayTicks, Runnable task) {
        return wrap(tasks.schedule(plugin, task, delayTicks * 50, TimeUnit.MILLISECONDS));
    }

    @Override
    public Task repeat(long delayTicks, long periodTicks, Runnable task) {
        return wrap(tasks.schedule(plugin, task, delayTicks * 50, Math.max(1, periodTicks) * 50,
                TimeUnit.MILLISECONDS));
    }

    private static Task wrap(ScheduledTask t) {
        AtomicBoolean cancelled = new AtomicBoolean();
        return new Task() {
            @Override
            public void cancel() {
                cancelled.set(true);
                t.cancel();
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }
}
