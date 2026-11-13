package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.JobQueue;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.ExecutionType;
import net.minestom.server.timer.Scheduler;
import net.minestom.server.timer.TaskSchedule;

/**
 * {@link MainThread} on Minestom's global scheduler. Its tasks run at the start of each tick on the
 * tick scheduler thread, before instances tick, so world access there does not race the
 * instances' own tick threads. Jobs in flight share {@code budget} of time per tick, which keeps the
 * server at 20 TPS while large edits run: one task steps the {@link JobQueue} each tick while it has
 * jobs, and stops when it is empty.
 */
public final class SchedulerMainThread implements MainThread {
    private final Scheduler scheduler;
    private final Duration budget;
    private final Duration timeout;
    private final JobQueue jobs = new JobQueue();
    /** True while a task steps {@link #jobs}. */
    private final AtomicBoolean driving = new AtomicBoolean();

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
        CompletableFuture<T> future = jobs.add(job);
        drive();
        return await(future);
    }

    /** How many jobs wait for a step; for tests. */
    int queued() {
        return jobs.size();
    }

    /** Start the task that steps the jobs each tick, unless one runs. */
    private void drive() {
        if (!driving.compareAndSet(false, true)) {
            return;
        }
        submit(() -> {
            jobs.step(budget);
            // Stop when empty, unless a job came in after the check and its caller found this task still driving.
            if (jobs.isEmpty()) {
                driving.set(false);
                if (jobs.isEmpty() || !driving.compareAndSet(false, true)) {
                    return TaskSchedule.stop();
                }
            }
            return TaskSchedule.nextTick();
        });
    }

    /** Pending calls end when the agent disconnects on shutdown, or at the timeout. */
    private void submit(Supplier<TaskSchedule> task) {
        fromNextTick(scheduler, task);
    }

    /**
     * Runs {@code task} at the start of the next tick, and again for as long as it asks to.
     * {@link Scheduler#submitTask} runs a task's first round at once on the calling thread, which
     * would put world access off the tick thread; this skips that round.
     */
    static void fromNextTick(Scheduler scheduler, Supplier<TaskSchedule> task) {
        AtomicBoolean submitted = new AtomicBoolean();
        scheduler.submitTask(() -> submitted.getAndSet(true) ? task.get() : TaskSchedule.nextTick(),
                ExecutionType.TICK_START);
    }

    private <T> T await(CompletableFuture<T> future) {
        return MainThread.await(future, timeout);
    }
}
