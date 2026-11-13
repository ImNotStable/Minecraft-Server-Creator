package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The jobs a {@link MainThread} has in flight. The platform calls {@link #step} once per tick on the main thread,
 * and all jobs share that tick's budget, so N concurrent jobs never take N budgets. Every job still gets at least
 * one step per tick, so a quick call never waits behind a large edit. Thread-safe: jobs are added from request
 * threads.
 *
 * <p>A platform that steps the queue from a repeating task it starts on demand, rather than from every tick, uses
 * the driver handoff: after {@link #add} the caller starts a task only when {@link #claimDriver} returns true, and
 * the task calls {@link #stepAndKeepDriving} each tick and stops when it returns false. The handoff never strands a
 * job: one added while the task is stopping is either seen by the task, which keeps driving, or claims the driver
 * itself.
 */
public final class JobQueue {
    private final ConcurrentLinkedQueue<Entry<?>> jobs = new ConcurrentLinkedQueue<>();
    /** True while a task drives this queue. */
    private final AtomicBoolean driving = new AtomicBoolean();
    private volatile boolean stopped;

    /**
     * Queue {@code job}; the future completes with its result, or with what it threw.
     *
     * @throws OpException {@code shutdown} after {@link #stop()}
     */
    public <T> CompletableFuture<T> add(SlicedJob<T> job) {
        if (stopped) {
            throw shutdown();
        }
        Entry<T> entry = new Entry<>(job);
        jobs.add(entry);
        if (stopped) {
            entry.future.completeExceptionally(shutdown());
        }
        return entry.future;
    }

    /** Step every queued job once against one deadline {@code budget} from now. Call on the main thread. */
    public void step(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        Iterator<Entry<?>> it = jobs.iterator();
        while (it.hasNext()) {
            Entry<?> entry = it.next();
            if (entry.future.isDone() || entry.step(deadline)) {
                it.remove();
            }
        }
    }

    /**
     * Claim the driver after an {@link #add}.
     *
     * @return true when the caller must start a task that calls {@link #stepAndKeepDriving}; false when one runs
     */
    public boolean claimDriver() {
        return driving.compareAndSet(false, true);
    }

    /** Give up a claim whose task could not be started. */
    public void releaseDriver() {
        driving.set(false);
    }

    /**
     * {@link #step} as the driving task, then hand the driver back when the queue is empty.
     *
     * @return false when the task must stop; it no longer holds the driver
     */
    public boolean stepAndKeepDriving(Duration budget) {
        step(budget);
        if (!jobs.isEmpty()) {
            return true;
        }
        driving.set(false);
        // A job added after the check whose caller still saw this task driving: take the driver back for it.
        return !jobs.isEmpty() && driving.compareAndSet(false, true);
    }

    /** True when no job waits for a step. */
    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    /** How many jobs are queued and not finished yet. */
    public int size() {
        return jobs.size();
    }

    /** Fail every queued job with {@code shutdown} and refuse new ones. */
    public void stop() {
        stopped = true;
        Entry<?> entry;
        while ((entry = jobs.poll()) != null) {
            entry.future.completeExceptionally(shutdown());
        }
    }

    private static OpException shutdown() {
        return new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
    }

    private static final class Entry<T> {
        final SlicedJob<T> job;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Entry(SlicedJob<T> job) {
            this.job = job;
        }

        /** @return true when the job is finished, with or without a result */
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
