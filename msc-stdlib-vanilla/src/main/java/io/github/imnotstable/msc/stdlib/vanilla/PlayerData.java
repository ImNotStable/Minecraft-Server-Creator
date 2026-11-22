package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.db.Store;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Per-player data kept in a {@link Store}: loaded off the server thread when the player joins, held while they play,
 * saved when they leave and when the server stops ({@code Stdlib.init()} wires those events).
 *
 * <pre>{@code
 * PlayerData<Stats> stats = PlayerData.create(Store.sql(db, "stats", Codec.json(Stats.class)), () -> new Stats(0, 0));
 * stats.update(player, s -> new Stats(s.kills() + 1, s.deaths()));
 * }</pre>
 */
public final class PlayerData<T> {
    private static final List<PlayerData<?>> ALL = new CopyOnWriteArrayList<>();

    private final Store<T> store;
    private final Supplier<T> initial;
    private final Map<UUID, T> cache = new ConcurrentHashMap<>();

    private PlayerData(Store<T> store, Supplier<T> initial) {
        this.store = store;
        this.initial = initial;
    }

    public static <T> PlayerData<T> create(Store<T> store, Supplier<T> initial) {
        PlayerData<T> data = new PlayerData<>(store, initial);
        ALL.add(data);
        return data;
    }

    public T get(ServerPlayer player) {
        return get(player.getUUID());
    }

    /** The player's data; loads it (blocking) when not cached, {@code initial} when there is none. */
    public T get(UUID id) {
        return cache.computeIfAbsent(id, this::load);
    }

    public void set(UUID id, T value) {
        cache.put(id, value);
    }

    public T update(UUID id, UnaryOperator<T> change) {
        return cache.compute(id, (k, v) -> change.apply(v == null ? load(k) : v));
    }

    public T update(ServerPlayer player, UnaryOperator<T> change) {
        return update(player.getUUID(), change);
    }

    public void save(UUID id) {
        T v = cache.get(id);
        if (v != null) {
            store.put(id.toString(), v);
        }
    }

    public void saveAll() {
        cache.keySet().forEach(this::save);
    }

    private T load(UUID id) {
        return store.get(id.toString()).orElseGet(initial);
    }

    /** A player joined: load every PlayerData off the server thread. */
    static void joined(UUID id) {
        ALL.forEach(d -> Stdlib.scheduler().async().execute(() -> d.get(id)));
    }

    /** A player left: save and forget them, off the server thread. */
    static void left(UUID id) {
        for (PlayerData<?> d : ALL) {
            Object v = d.cache.remove(id);
            if (v != null) {
                Stdlib.scheduler().async().execute(() -> d.putRaw(id, v));
            }
        }
    }

    /** The server stops: save everyone now. */
    static void stopping() {
        ALL.forEach(PlayerData::saveAll);
    }

    @SuppressWarnings("unchecked")
    private void putRaw(UUID id, Object v) {
        store.put(id.toString(), (T) v);
    }
}
