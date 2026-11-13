package io.github.imnotstable.msc.agent.minestom;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * How many operations are using each chunk of one instance right now, so a read's cleanup never
 * unloads a chunk another read or edit still needs. Shared through a transient tag on the instance,
 * since every lookup of a world makes a new {@link MinestomWorld}.
 */
final class ChunkReaders {
    private final Map<Long, Integer> counts = new HashMap<>();

    synchronized void acquire(Collection<Long> chunks) {
        chunks.forEach(key -> counts.merge(key, 1, Integer::sum));
    }

    synchronized void release(Collection<Long> chunks) {
        chunks.forEach(key -> counts.computeIfPresent(key, (k, n) -> n == 1 ? null : n - 1));
    }

    /**
     * Runs {@code unload} for each chunk no operation uses, under the same lock as {@link #acquire},
     * so a chunk is never unloaded after an operation has started to use it.
     */
    synchronized void unloadUnused(Collection<Long> chunks, LongConsumer unload) {
        for (long key : chunks) {
            if (!counts.containsKey(key)) {
                unload.accept(key);
            }
        }
    }
}
