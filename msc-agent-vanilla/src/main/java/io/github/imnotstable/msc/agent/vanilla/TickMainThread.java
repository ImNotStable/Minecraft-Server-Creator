package io.github.imnotstable.msc.agent.vanilla;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link MainThread} driven by the server's tick: the mod calls {@link #tick()} at the end of every
 * server tick, and each running job gets {@code budget} of main-thread time per tick, which keeps
 * the server at 20 TPS while large edits run. Concurrent jobs share the budget, but every job
 * gets at least one step per tick, so a quick call never waits behind a large edit.
 *
 * <p>The tick event is not enough on its own: a server with no players stops ticking its worlds after
 * {@code pause-when-empty-seconds} and then fires no end-of-tick event, but it still runs tasks given to its
 * executor. So a waiting caller that sees no tick for {@link #WAKE_AFTER} hands one step to the {@code wake}
 * executor; that step is skipped if a tick happened meanwhile, so jobs are never stepped twice per tick.
 */
final class TickMainThread implements MainThread {
    /** How long a caller waits for a tick before it steps the jobs through the wake executor. */
    static final Duration WAKE_AFTER = Duration.ofMillis(100);

    private final BooleanSupplier onMainThread;
    private final Executor wake;
    private final Duration budget;
    private final Duration timeout;
    private final ConcurrentLinkedQueue<Task<?>> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private volatile boolean stopped;

    /**
     * @param onMainThread whether the calling thread is the server thread
     * @param wake runs a task on the server thread even while the server does not tick, such as {@code server::execute}
     */
    TickMainThread(BooleanSupplier onMainThread, Executor wake, Duration budget, Duration timeout) {
        this.onMainThread = onMainThread;
        this.wake = wake;
        this.budget = budget;
        this.timeout = timeout;
    }

    /** Only {@link #tick()} steps jobs; for tests that drive the ticks themselves. */
    TickMainThread(BooleanSupplier onMainThread, Duration budget, Duration timeout) {
        this(onMainThread, task -> { }, budget, timeout);
    }

    @Override
    public <T> T call(Supplier<T> task) {
        return run(new SlicedJob<>() {
            private T result;

            @Override
            public boolean step(long deadlineNanos) {
                result = task.get();
                return true;
            }

            @Override
            public T result() {
                return result;
            }
        });
    }

    @Override
    public <T> T run(SlicedJob<T> job) {
        if (onMainThread.getAsBoolean()) {
            return job.runNow();
        }
        if (stopped) {
            throw new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
        }
        Task<T> task = new Task<>(job);
        tasks.add(task);
        if (stopped) {
            task.future.completeExceptionally(new OpException(ErrorCodes.SHUTDOWN, "server is shutting down"));
        }
        return await(task.future);
    }

    /** Step every queued job once, sharing one budget. Call once per tick on the main thread. */
    void tick() {
        ticks.incrementAndGet();
        step();
    }

    private void step() {
        long deadline = System.nanoTime() + budget.toNanos();
        Iterator<Task<?>> it = tasks.iterator();
        while (it.hasNext()) {
            Task<?> task = it.next();
            if (task.future.isDone() || task.step(deadline)) {
                it.remove();
            }
        }
    }

    /** How many jobs are queued and not finished yet. */
    int queued() {
        return tasks.size();
    }

    /** Fail every queued job and refuse new ones. Call when the server stops. */
    void stop() {
        stopped = true;
        Task<?> task;
        while ((task = tasks.poll()) != null) {
            task.future.completeExceptionally(new OpException(ErrorCodes.SHUTDOWN, "server is shutting down"));
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        long end = System.nanoTime() + timeout.toNanos();
        long left;
        while (!future.isDone() && (left = end - System.nanoTime()) > 0) {
            long seen = ticks.get();
            try {
                future.get(Math.min(left, WAKE_AFTER.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (ticks.get() == seen) {
                    wake(seen);
                }
            } catch (ExecutionException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!future.isDone() && !Thread.currentThread().isInterrupted()) {
            future.cancel(false);
            throw new OpException(ErrorCodes.TIMEOUT, "main thread did not finish within " + timeout.toSeconds() + "s");
        }
        return MainThread.await(future, timeout);
    }

    /** Step the jobs once on the server thread, unless a tick did since {@code seen}. One wake at a time. */
    private void wake(long seen) {
        if (!wakePending.compareAndSet(false, true)) {
            return;
        }
        try {
            wake.execute(() -> {
                wakePending.set(false);
                if (ticks.get() == seen && onMainThread.getAsBoolean()) {
                    step();
                }
            });
        } catch (RuntimeException e) {
            wakePending.set(false);
        }
    }

    private static final class Task<T> {
        final SlicedJob<T> job;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Task(SlicedJob<T> job) {
            this.job = job;
        }

        /** @return true when the task is finished, with or without a result */
        boolean step(long deadline) {
            try {
                if (job.step(deadline)) {
                    future.complete(job.result());
                    return true;
                }
                return false;
            } catch (Throwable t) {
                future.completeExceptionally(t);
                return true;
            }
        }
    }
}
