package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@link MainThread} on the Bukkit scheduler. Jobs get {@code budget} of main-thread time per tick,
 * which keeps the server at 20 TPS while large edits run.
 */
public final class BukkitMainThread implements MainThread {
    private final Plugin plugin;
    private final Duration budget;
    private final Duration timeout;

    public BukkitMainThread(Plugin plugin, Duration budget, Duration timeout) {
        this.plugin = plugin;
        this.budget = budget;
        this.timeout = timeout;
    }

    @Override
    public <T> T call(Supplier<T> task) {
        if (Bukkit.isPrimaryThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        schedule(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return await(future);
    }

    @Override
    public <T> T run(SlicedJob<T> job) {
        if (Bukkit.isPrimaryThread()) {
            return job.runNow();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        BukkitTask[] task = new BukkitTask[1];
        Runnable tick = () -> {
            if (future.isDone()) {
                task[0].cancel();
                return;
            }
            try {
                if (job.step(System.nanoTime() + budget.toNanos())) {
                    future.complete(job.result());
                    task[0].cancel();
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
                task[0].cancel();
            }
        };
        try {
            task[0] = Bukkit.getScheduler().runTaskTimer(plugin, tick, 0L, 1L);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
        }
        return await(future);
    }

    private void schedule(Runnable runnable) {
        try {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        return MainThread.await(future, timeout);
    }
}
