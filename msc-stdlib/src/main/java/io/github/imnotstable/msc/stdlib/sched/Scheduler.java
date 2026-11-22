package io.github.imnotstable.msc.stdlib.sched;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The game's scheduler, the same on every platform (Paper: {@code PaperScheduler}). Delays are in game ticks
 * (20 a second); {@link #ticks} converts.
 */
public interface Scheduler {
    /** Runs on the main (game) thread. */
    Executor sync();

    /** Runs off the main thread; never touch the world from there. */
    Executor async();

    /** Runs {@code task} on the main thread after {@code delayTicks}. */
    Task later(long delayTicks, Runnable task);

    /** Runs {@code task} on the main thread every {@code periodTicks}, first after {@code delayTicks}. */
    Task repeat(long delayTicks, long periodTicks, Runnable task);

    /** Work off the main thread, its result handed back on the main thread. */
    default <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, async()).thenApplyAsync(t -> t, sync());
    }

    /**
     * Counts down once a second on the main thread: {@code tick} gets the seconds left (n, n-1 ... 1), then {@code done}
     * runs. Cancel the returned task to stop it.
     */
    default Task countdown(int seconds, java.util.function.IntConsumer tick, Runnable done) {
        int[] left = {seconds};
        Task[] self = new Task[1];
        self[0] = repeat(0, 20, () -> {
            if (left[0] <= 0) {
                self[0].cancel();
                done.run();
                return;
            }
            tick.accept(left[0]--);
        });
        return self[0];
    }

    static long ticks(Duration d) {
        return d.toMillis() / 50;
    }

    /** A scheduled task. */
    interface Task {
        void cancel();

        boolean cancelled();
    }
}
