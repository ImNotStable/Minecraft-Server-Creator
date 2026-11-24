package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs work on the server's main thread for request handlers, which run on virtual threads.
 * Both methods block the calling thread until the work is done.
 */
public interface MainThread {
    /** Run once on the main thread and return its result. */
    <T> T call(Supplier<T> task);

    /** Run a job across as many ticks as it needs and return its result. */
    <T> T run(SlicedJob<T> job);

    /**
     * Run once on the thread that owns {@code area}. A server with one main thread runs it there ({@link #call}); Folia
     * runs it on the region thread that owns those chunks (ADR 0100).
     */
    default <T> T call(Supplier<T> task, ChunkArea area) {
        return call(task);
    }

    /** As {@link #run(SlicedJob)}, on the thread that owns {@code area}: every block the job touches lies in it. */
    default <T> T run(SlicedJob<T> job, ChunkArea area) {
        return run(job);
    }

    /** Runs everything inline on the calling thread. For tests, where the test thread is the main thread. */
    static MainThread inline() {
        return new MainThread() {
            @Override
            public <T> T call(Supplier<T> task) {
                return task.get();
            }

            @Override
            public <T> T run(SlicedJob<T> job) {
                return job.runNow();
            }
        };
    }

    /**
     * Waits for main-thread work for at most {@code timeout}, for implementations. A runtime
     * exception the work threw is rethrown as is; a timeout, an interrupt or a checked exception
     * becomes an {@link OpException}.
     */
    static <T> T await(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new OpException(ErrorCodes.TIMEOUT, "main thread did not finish within " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpException(ErrorCodes.SHUTDOWN, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new OpException(ErrorCodes.INTERNAL, String.valueOf(cause));
        }
    }
}
