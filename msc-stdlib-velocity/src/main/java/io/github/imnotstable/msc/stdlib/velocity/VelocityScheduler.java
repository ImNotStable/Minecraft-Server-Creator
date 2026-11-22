package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.TaskStatus;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** {@link Scheduler} on Velocity's: a proxy has no main thread, so sync and async both run on its task pool. */
public final class VelocityScheduler implements Scheduler {
    private final Object plugin;
    private final ProxyServer proxy;

    public VelocityScheduler(Object plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    @Override
    public Executor sync() {
        return r -> proxy.getScheduler().buildTask(plugin, r).schedule();
    }

    @Override
    public Executor async() {
        return sync();
    }

    @Override
    public Task later(long delayTicks, Runnable task) {
        return wrap(proxy.getScheduler().buildTask(plugin, task).delay(delayTicks * 50, TimeUnit.MILLISECONDS).schedule());
    }

    @Override
    public Task repeat(long delayTicks, long periodTicks, Runnable task) {
        return wrap(proxy.getScheduler().buildTask(plugin, task).delay(delayTicks * 50, TimeUnit.MILLISECONDS)
                .repeat(Math.max(1, periodTicks) * 50, TimeUnit.MILLISECONDS).schedule());
    }

    private static Task wrap(ScheduledTask t) {
        return new Task() {
            @Override
            public void cancel() {
                t.cancel();
            }

            @Override
            public boolean cancelled() {
                return t.status() != TaskStatus.SCHEDULED;
            }
        };
    }
}
