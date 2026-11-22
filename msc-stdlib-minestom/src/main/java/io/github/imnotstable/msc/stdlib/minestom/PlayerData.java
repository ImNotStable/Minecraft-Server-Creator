package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.db.Store;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Per-player data kept in a {@link Store}: loaded while the player configures (off the tick thread), held while they
 * play, saved when they leave and on {@link #saveAll}. Needs {@link Stdlib#init}.
 *
 * <pre>{@code
 * PlayerData<Stats> stats = PlayerData.create(Store.sql(db, "stats", Codec.json(Stats.class)), () -> new Stats(0, 0));
 * }</pre>
 */
public final class PlayerData<T> {
    private final Store<T> store;
    private final Supplier<T> initial;
    private final Map<UUID, T> cache = new ConcurrentHashMap<>();

    private PlayerData(Store<T> store, Supplier<T> initial) {
        this.store = store;
        this.initial = initial;
    }

    public static <T> PlayerData<T> create(Store<T> store, Supplier<T> initial) {
        PlayerData<T> data = new PlayerData<>(store, initial);
        Stdlib.events().addListener(AsyncPlayerConfigurationEvent.class,
                e -> data.cache.computeIfAbsent(e.getPlayer().getUuid(), data::load));
        Stdlib.events().addListener(PlayerDisconnectEvent.class, e -> {
            UUID id = e.getPlayer().getUuid();
            T v = data.cache.remove(id);
            if (v != null) {
                Stdlib.scheduler().async().execute(() -> store.put(id.toString(), v));
            }
        });
        return data;
    }

    public T get(Player player) {
        return get(player.getUuid());
    }

    public T get(UUID id) {
        return cache.computeIfAbsent(id, this::load);
    }

    public void set(Player player, T value) {
        cache.put(player.getUuid(), value);
    }

    public T update(Player player, UnaryOperator<T> change) {
        return cache.compute(player.getUuid(), (id, v) -> change.apply(v == null ? load(id) : v));
    }

    public void save(UUID id) {
        T v = cache.get(id);
        if (v != null) {
            store.put(id.toString(), v);
        }
    }

    /** Writes every cached player's data; call before the server stops. */
    public void saveAll() {
        cache.keySet().forEach(this::save);
    }

    private T load(UUID id) {
        return store.get(id.toString()).orElseGet(initial);
    }
}
