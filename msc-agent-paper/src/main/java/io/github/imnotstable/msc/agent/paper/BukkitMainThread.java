package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.JobQueue;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@link MainThread} on the Bukkit scheduler. Jobs in flight share {@code budget} of main-thread time
 * per tick, which keeps the server at 20 TPS while large edits run: one repeating task steps the
 * {@link JobQueue} while it has jobs, and cancels itself when it is empty.
 */
public final class BukkitMainThread implements MainThread {
    private final Plugin plugin;
    private final Duration budget;
    private final Duration timeout;
    private final JobQueue jobs = new JobQueue();
    /** True while a repeating task steps {@link #jobs}. */
    private final AtomicBoolean driving = new AtomicBoolean();

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
        CompletableFuture<T> future = jobs.add(job);
        drive();
        return await(future);
    }

    /** How many jobs wait for a step; for tests. */
    int queued() {
        return jobs.size();
    }

    /** Start the repeating task that steps the jobs, unless one runs. */
    private void drive() {
        if (!driving.compareAndSet(false, true)) {
            return;
        }
        // The scheduler hands the task to each tick, so a tick never reads a handle stored by this thread.
        Consumer<BukkitTask> tick = task -> {
            jobs.step(budget);
            // Stop when empty, unless a job came in after the check and its caller found this task still driving.
            if (jobs.isEmpty()) {
                driving.set(false);
                if (jobs.isEmpty() || !driving.compareAndSet(false, true)) {
                    task.cancel();
                }
            }
        };
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, tick, 0L, 1L);
        } catch (IllegalStateException | IllegalArgumentException e) {
            driving.set(false);
            jobs.stop();
            throw new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
        }
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
