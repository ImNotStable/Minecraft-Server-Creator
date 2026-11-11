package io.github.imnotstable.msc.agent.minestom;

/**
 * Work that runs on the server's main thread in slices, so a large edit or read never holds a
 * tick for long. The runner calls {@link #step} once per tick until it returns {@code true}.
 *
 * @param <T> the job's result
 */
public interface SlicedJob<T> {
    /**
     * Do work until done or until {@code System.nanoTime()} passes {@code deadlineNanos}.
     *
     * @return {@code true} when the job is finished and {@link #result()} is ready
     */
    boolean step(long deadlineNanos);

    T result();

    /** Run to completion in one go. For tests and small jobs already on the main thread. */
    default T runNow() {
        while (!step(Long.MAX_VALUE)) {
            // keep going
        }
        return result();
    }
}
