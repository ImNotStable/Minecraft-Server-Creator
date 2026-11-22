package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.timer.TaskSchedule;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link Scheduler} on Minestom's scheduler. Minestom has no single main thread: "sync" runs on the next server tick. */
public final class MinestomScheduler implements Scheduler {
    private static final ExecutorService ASYNC = Executors.newVirtualThreadPerTaskExecutor();
    private final SchedulerManager scheduler;

    public MinestomScheduler(SchedulerManager scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Executor sync() {
        return scheduler::scheduleNextTick;
    }

    @Override
    public Executor async() {
        return ASYNC;
    }

    @Override
    public Task later(long delayTicks, Runnable task) {
        return wrap(scheduler.buildTask(task).delay(delay(delayTicks)).schedule());
    }

    @Override
    public Task repeat(long delayTicks, long periodTicks, Runnable task) {
        return wrap(scheduler.buildTask(task).delay(delay(delayTicks))
                .repeat(TaskSchedule.tick((int) periodTicks)).schedule());
    }

    /** Minestom refuses a 0-tick schedule; 0 means now. */
    private static TaskSchedule delay(long ticks) {
        return ticks <= 0 ? TaskSchedule.immediate() : TaskSchedule.tick((int) ticks);
    }

    private static Task wrap(net.minestom.server.timer.Task t) {
        return new Task() {
            @Override
            public void cancel() {
                t.cancel();
            }

            @Override
            public boolean cancelled() {
                return !t.isAlive();
            }
        };
    }
}
