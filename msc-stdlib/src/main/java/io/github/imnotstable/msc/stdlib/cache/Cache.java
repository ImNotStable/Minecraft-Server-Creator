package io.github.imnotstable.msc.stdlib.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * An in-memory cache with size and time limits, thread-safe.
 *
 * <pre>{@code
 * Cache<UUID, Profile> profiles = Cache.<UUID, Profile>builder()
 *         .maxSize(1000).expireAfterAccess(Duration.ofMinutes(10))
 *         .loader(id -> repo.find(id).orElse(null))     // get() loads misses
 *         .onRemove((id, p, cause) -> repo.save(p))     // write back what falls out
 *         .build();
 * Profile p = profiles.get(id);
 * }</pre>
 */
public final class Cache<K, V> {
    /** Why an entry left the cache. */
    public enum Cause { EXPIRED, SIZE, REPLACED, INVALIDATED }

    /** Called when an entry leaves. */
    @FunctionalInterface
    public interface RemovalListener<K, V> {
        void removed(K key, V value, Cause cause);
    }

    private record Entry<V>(V value, Instant written, Instant accessed) {}

    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);
    private final long maxSize;
    private final Duration afterWrite;
    private final Duration afterAccess;
    private final Function<K, V> loader;
    private final RemovalListener<K, V> onRemove;
    private final Clock clock;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private Cache(Builder<K, V> b) {
        this.maxSize = b.maxSize;
        this.afterWrite = b.afterWrite;
        this.afterAccess = b.afterAccess;
        this.loader = b.loader;
        this.onRemove = b.onRemove;
        this.clock = b.clock;
    }

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /** The value, loading it with the builder's loader on a miss (null when there is no loader or it gives null). */
    public V get(K key) {
        return loader == null ? getIfPresent(key).orElse(null) : get(key, loader);
    }

    /** The value, computed by {@code load} on a miss and kept (unless it is null). */
    public V get(K key, Function<K, V> load) {
        Optional<V> hit = getIfPresent(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        V v = load.apply(key);
        if (v != null) {
            put(key, v);
        }
        return v;
    }

    public synchronized Optional<V> getIfPresent(K key) {
        Entry<V> e = map.get(key);
        Instant now = clock.instant();
        if (e == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (expired(e, now)) {
            map.remove(key);
            notify(key, e.value(), Cause.EXPIRED);
            misses.incrementAndGet();
            return Optional.empty();
        }
        map.put(key, new Entry<>(e.value(), e.written(), now));
        hits.incrementAndGet();
        return Optional.of(e.value());
    }

    public synchronized void put(K key, V value) {
        Instant now = clock.instant();
        Entry<V> old = map.put(key, new Entry<>(value, now, now));
        if (old != null && old.value() != value) {
            notify(key, old.value(), Cause.REPLACED);
        }
        evict();
    }

    public synchronized void invalidate(K key) {
        Entry<V> e = map.remove(key);
        if (e != null) {
            notify(key, e.value(), Cause.INVALIDATED);
        }
    }

    public synchronized void invalidateAll() {
        Map<K, Entry<V>> copy = new LinkedHashMap<>(map);
        map.clear();
        copy.forEach((k, e) -> notify(k, e.value(), Cause.INVALIDATED));
    }

    /** Drops expired entries now (they also go when read). */
    public synchronized void cleanUp() {
        Instant now = clock.instant();
        for (Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<K, Entry<V>> e = it.next();
            if (expired(e.getValue(), now)) {
                it.remove();
                notify(e.getKey(), e.getValue().value(), Cause.EXPIRED);
            }
        }
    }

    public synchronized int size() {
        return map.size();
    }

    /** A copy of the live entries. */
    public synchronized Map<K, V> asMap() {
        cleanUp();
        Map<K, V> out = new LinkedHashMap<>();
        map.forEach((k, e) -> out.put(k, e.value()));
        return out;
    }

    public Stats stats() {
        return new Stats(hits.get(), misses.get());
    }

    /** Hits and misses since the cache was made. */
    public record Stats(long hits, long misses) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0 : (double) hits / total;
        }
    }

    private boolean expired(Entry<V> e, Instant now) {
        return (afterWrite != null && !now.isBefore(e.written().plus(afterWrite)))
                || (afterAccess != null && !now.isBefore(e.accessed().plus(afterAccess)));
    }

    private void evict() {
        while (maxSize > 0 && map.size() > maxSize) {
            Map.Entry<K, Entry<V>> eldest = map.entrySet().iterator().next();
            map.remove(eldest.getKey());
            notify(eldest.getKey(), eldest.getValue().value(), Cause.SIZE);
        }
    }

    private void notify(K key, V value, Cause cause) {
        if (onRemove != null) {
            onRemove.removed(key, value, cause);
        }
    }

    public static final class Builder<K, V> {
        private long maxSize;
        private Duration afterWrite;
        private Duration afterAccess;
        private Function<K, V> loader;
        private RemovalListener<K, V> onRemove;
        private Clock clock = Clock.systemUTC();

        /** Keeps at most {@code n}, dropping the least recently used. */
        public Builder<K, V> maxSize(long n) {
            this.maxSize = n;
            return this;
        }

        public Builder<K, V> expireAfterWrite(Duration d) {
            this.afterWrite = d;
            return this;
        }

        public Builder<K, V> expireAfterAccess(Duration d) {
            this.afterAccess = d;
            return this;
        }

        public Builder<K, V> loader(Function<K, V> loader) {
            this.loader = loader;
            return this;
        }

        public Builder<K, V> onRemove(RemovalListener<K, V> listener) {
            this.onRemove = listener;
            return this;
        }

        public Builder<K, V> onRemove(BiConsumer<K, V> listener) {
            this.onRemove = (k, v, c) -> listener.accept(k, v);
            return this;
        }

        public Builder<K, V> clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Cache<K, V> build() {
            return new Cache<>(this);
        }
    }
}
