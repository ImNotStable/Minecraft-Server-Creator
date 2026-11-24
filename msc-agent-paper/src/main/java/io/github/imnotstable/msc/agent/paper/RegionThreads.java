package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.ChunkArea;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@link MainThread} on Folia (ADR 0100). Folia has no main thread: each region of loaded chunks ticks on its own thread,
 * and a chunk may only be touched from the thread of the region that owns it. Server-wide work (info, console commands,
 * packs) goes to the global region thread; a world job runs on the region thread that owns the chunk in the middle of
 * its area, one slice per tick of that region, and only while that region owns every chunk of the area. The agent
 * loads and holds the area's chunks first, and Folia merges neighbouring loaded chunks into one region, so a job whose
 * region does not yet own the whole area waits a few ticks for the merge instead of failing.
 */
final class RegionThreads implements MainThread {
    /** Ticks a job waits for its chunks to come under one region before it gives up. */
    static final int MAX_WAITS = 200;

    /** Folia's region scheduler and ownership check, as the agent uses them; tests fake them. */
    interface Regions {
        /** Runs {@code task} on the thread of the region owning chunk {@code cx, cz}, after {@code delayTicks} (0: soon). */
        void schedule(String world, int cx, int cz, long delayTicks, Runnable task);

        /** Whether the calling thread is a region thread that owns every chunk of {@code area}. */
        boolean owns(ChunkArea area);
    }

    private final MainThread global;
    private final Regions regions;
    private final Duration budget;
    private final Duration timeout;

    RegionThreads(MainThread global, Regions regions, Duration budget, Duration timeout) {
        this.global = global;
        this.regions = regions;
        this.budget = budget;
        this.timeout = timeout;
    }

    @Override
    public <T> T call(Supplier<T> task) {
        return global.call(task);
    }

    @Override
    public <T> T run(SlicedJob<T> job) {
        throw new OpException(ErrorCodes.INTERNAL, "a world job on Folia needs the chunks it touches");
    }

    @Override
    public <T> T call(Supplier<T> task, ChunkArea area) {
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
        }, area);
    }

    @Override
    public <T> T run(SlicedJob<T> job, ChunkArea area) {
        if (regions.owns(area)) {
            return job.runNow();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        new Stepper<>(job, area, future).schedule(0);
        return MainThread.await(future, timeout);
    }

    /** Steps one job on its region's thread, a slice per tick, until it is done or its caller stopped waiting. */
    private final class Stepper<T> implements Runnable {
        private final SlicedJob<T> job;
        private final ChunkArea area;
        private final CompletableFuture<T> future;
        private int waits;

        Stepper(SlicedJob<T> job, ChunkArea area, CompletableFuture<T> future) {
            this.job = job;
            this.area = area;
            this.future = future;
        }

        void schedule(long delay) {
            try {
                regions.schedule(area.world(), area.centerChunkX(), area.centerChunkZ(), delay, this);
            } catch (IllegalStateException | IllegalArgumentException e) {
                future.completeExceptionally(new OpException(ErrorCodes.SHUTDOWN, "server is shutting down"));
            }
        }

        @Override
        public void run() {
            if (future.isDone()) {
                return; // timed out or cancelled: change nothing more
            }
            try {
                if (!regions.owns(area)) {
                    if (++waits > MAX_WAITS) {
                        future.completeExceptionally(new OpException(ErrorCodes.TOO_LARGE, "the " + area.chunks()
                                + " chunks of this area did not come under one Folia region within " + MAX_WAITS
                                + " ticks; work on a smaller area"));
                        return;
                    }
                    schedule(1);
                    return;
                }
                if (job.step(System.nanoTime() + budget.toNanos())) {
                    future.complete(job.result());
                } else {
                    schedule(1);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }
}
