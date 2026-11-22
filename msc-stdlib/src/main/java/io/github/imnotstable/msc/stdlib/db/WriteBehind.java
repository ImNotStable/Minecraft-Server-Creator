package io.github.imnotstable.msc.stdlib.db;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** {@link Store#writeBehind}: buffers writes and flushes them on a timer. Close it on shutdown to write the rest. */
public final class WriteBehind<V> implements Store<V>, AutoCloseable {
    private static final Object REMOVED = new Object();
    private final Store<V> store;
    private final Map<String, Object> dirty = new LinkedHashMap<>();
    private final ScheduledExecutorService timer;

    WriteBehind(Store<V> store, Duration every) {
        this.store = store;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofPlatform().daemon().name("msc-stdlib-write-behind").unstarted(r);
            return t;
        });
        long ms = Math.max(1, every.toMillis());
        timer.scheduleAtFixedRate(this::flush, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<V> get(String key) {
        synchronized (dirty) {
            Object v = dirty.get(key);
            if (v == REMOVED) {
                return Optional.empty();
            }
            if (v != null) {
                return Optional.of((V) v);
            }
        }
        return store.get(key);
    }

    @Override
    public void put(String key, V value) {
        synchronized (dirty) {
            dirty.put(key, value);
        }
    }

    @Override
    public boolean remove(String key) {
        boolean had = get(key).isPresent();
        synchronized (dirty) {
            dirty.put(key, REMOVED);
        }
        return had;
    }

    @Override
    public List<String> keys() {
        flush();
        return store.keys();
    }

    @Override
    public Map<String, V> all() {
        flush();
        return store.all();
    }

    /** Writes everything buffered now. */
    @SuppressWarnings("unchecked")
    public void flush() {
        Map<String, Object> batch;
        synchronized (dirty) {
            if (dirty.isEmpty()) {
                return;
            }
            batch = new LinkedHashMap<>(dirty);
            dirty.clear();
        }
        batch.forEach((k, v) -> {
            if (v == REMOVED) {
                store.remove(k);
            } else {
                store.put(k, (V) v);
            }
        });
    }

    /** Number of writes waiting. */
    public int pending() {
        synchronized (dirty) {
            return dirty.size();
        }
    }

    @Override
    public void close() {
        timer.shutdown();
        flush();
    }
}
