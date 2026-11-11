package io.github.imnotstable.msc.agent.minestom;

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
}
