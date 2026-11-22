package io.github.imnotstable.msc.stdlib.db;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Values by text key, for data that needs no queries of its own (player profiles, settings, saved kits).
 * {@link #sql} keeps them in a two-column table of any {@link Database}; {@link #memory} in a map, for tests.
 *
 * <pre>{@code
 * record Profile(int coins, List<String> unlocked) {}
 * Store<Profile> profiles = Store.sql(db, "profiles", Codec.json(Profile.class));
 * profiles.put(uuid.toString(), new Profile(10, List.of()));
 * profiles.update(uuid.toString(), p -> new Profile(p.coins() + 5, p.unlocked()), () -> new Profile(0, List.of()));
 * }</pre>
 */
public interface Store<V> {
    Optional<V> get(String key);

    void put(String key, V value);

    /** True when there was a value. */
    boolean remove(String key);

    List<String> keys();

    Map<String, V> all();

    /** Reads, changes and writes one value; {@code initial} supplies it when missing. Returns the new value. */
    default V update(String key, UnaryOperator<V> change, java.util.function.Supplier<V> initial) {
        V next = change.apply(get(key).orElseGet(initial));
        put(key, next);
        return next;
    }

    /** A store in table {@code table} (key VARCHAR(255), value text), created when missing. */
    static <V> Store<V> sql(Database db, String table, Codec<V> codec) {
        db.update(Table.named(table).key("k", ColumnType.STRING).column("v", ColumnType.TEXT).sql(db.type()));
        String upsert = db.type().upsert(table, List.of("k", "v"), List.of("k"));
        return new Store<>() {
            @Override
            public Optional<V> get(String key) {
                return db.queryOne("SELECT v FROM " + table + " WHERE k = ?", r -> codec.decode(r.string("v")), key);
            }

            @Override
            public void put(String key, V value) {
                db.update(upsert, key, codec.encode(value));
            }

            @Override
            public boolean remove(String key) {
                return db.update("DELETE FROM " + table + " WHERE k = ?", key) > 0;
            }

            @Override
            public List<String> keys() {
                return db.query("SELECT k FROM " + table, r -> r.string("k"));
            }

            @Override
            public Map<String, V> all() {
                Map<String, V> out = new java.util.LinkedHashMap<>();
                db.query("SELECT k, v FROM " + table, r -> out.put(r.string("k"), codec.decode(r.string("v"))));
                return out;
            }
        };
    }

    /**
     * One JSON file per key in {@code dir} ({@code <key>.json}, unsafe characters replaced): data people can read and
     * edit, no database needed. Keys keep only letters, digits, {@code - _ .}.
     */
    static <V> Store<V> jsonFiles(java.nio.file.Path dir, Codec<V> codec) {
        return new FileStore<>(dir, codec);
    }

    /** {@code store} with reads served from {@code cache}; writes go to both (write-through). */
    static <V> Store<V> cached(Store<V> store, io.github.imnotstable.msc.stdlib.cache.Cache<String, V> cache) {
        return new Store<>() {
            @Override
            public Optional<V> get(String key) {
                return Optional.ofNullable(cache.get(key, k -> store.get(k).orElse(null)));
            }

            @Override
            public void put(String key, V value) {
                store.put(key, value);
                cache.put(key, value);
            }

            @Override
            public boolean remove(String key) {
                cache.invalidate(key);
                return store.remove(key);
            }

            @Override
            public List<String> keys() {
                return store.keys();
            }

            @Override
            public Map<String, V> all() {
                return store.all();
            }
        };
    }

    /**
     * {@code store} with writes held in memory and flushed every {@code every} (and on {@link WriteBehind#flush} or
     * {@link WriteBehind#close}): many updates to hot data become one write. Reads see unflushed writes.
     */
    static <V> WriteBehind<V> writeBehind(Store<V> store, java.time.Duration every) {
        return new WriteBehind<>(store, every);
    }

    /** A store in memory, for tests and data that need not survive a restart. */
    static <V> Store<V> memory() {
        Map<String, V> map = new ConcurrentHashMap<>();
        return new Store<>() {
            @Override
            public Optional<V> get(String key) {
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public void put(String key, V value) {
                map.put(key, value);
            }

            @Override
            public boolean remove(String key) {
                return map.remove(key) != null;
            }

            @Override
            public List<String> keys() {
                return List.copyOf(map.keySet());
            }

            @Override
            public Map<String, V> all() {
                return Map.copyOf(map);
            }
        };
    }
}
