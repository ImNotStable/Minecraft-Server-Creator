package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.ExecutionType;
import net.minestom.server.timer.Scheduler;
import net.minestom.server.timer.TaskSchedule;

/**
 * {@link MainThread} on Minestom's global scheduler. Its tasks run at the start of each tick on the
 * tick scheduler thread, before instances tick, so world access there does not race the
 * instances' own tick threads. Jobs get {@code budget} of time per tick, which keeps the server at
 * 20 TPS while large edits run.
 */
public final class SchedulerMainThread implements MainThread {
    private final Scheduler scheduler;
    private final Duration budget;
    private final Duration timeout;

    public SchedulerMainThread(Scheduler scheduler, Duration budget, Duration timeout) {
        this.scheduler = scheduler;
        this.budget = budget;
        this.timeout = timeout;
    }

    /** True on Minestom's tick threads, where waiting for the next tick would deadlock. */
    static boolean onTickThread() {
        return Thread.currentThread().getName().startsWith(MinecraftServer.THREAD_NAME_TICK);
    }

    @Override
    public <T> T call(Supplier<T> task) {
        if (onTickThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        submit(() -> {
            if (!future.isDone()) {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
            return TaskSchedule.stop();
        });
        return await(future);
    }

    @Override
    public <T> T run(SlicedJob<T> job) {
        if (onTickThread()) {
            return job.runNow();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        submit(() -> {
            if (future.isDone()) {
                return TaskSchedule.stop();
            }
            try {
                if (job.step(System.nanoTime() + budget.toNanos())) {
                    future.complete(job.result());
                    return TaskSchedule.stop();
                }
                return TaskSchedule.nextTick();
            } catch (Throwable t) {
                future.completeExceptionally(t);
                return TaskSchedule.stop();
            }
        });
        return await(future);
    }

    /** Pending calls end when the agent disconnects on shutdown, or at the timeout. */
    private void submit(Supplier<TaskSchedule> task) {
        scheduler.submitTask(task, ExecutionType.TICK_START);
    }

    private <T> T await(CompletableFuture<T> future) {
        return MainThread.await(future, timeout);
    }
}
