package io.github.imnotstable.msc.agent.vanilla;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link MainThread} driven by the server's tick: the mod calls {@link #tick()} at the end of every
 * server tick, and each running job gets {@code budget} of main-thread time per tick, which keeps
 * the server at 20 TPS while large edits run. Concurrent jobs share the budget, but every job
 * gets at least one step per tick, so a quick call never waits behind a large edit.
 */
final class TickMainThread implements MainThread {
    private final BooleanSupplier onMainThread;
    private final Duration budget;
    private final Duration timeout;
    private final ConcurrentLinkedQueue<Task<?>> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean stopped;

    /** @param onMainThread whether the calling thread is the server thread */
    TickMainThread(BooleanSupplier onMainThread, Duration budget, Duration timeout) {
        this.onMainThread = onMainThread;
        this.budget = budget;
        this.timeout = timeout;
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
        return MainThread.await(future, timeout);
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
