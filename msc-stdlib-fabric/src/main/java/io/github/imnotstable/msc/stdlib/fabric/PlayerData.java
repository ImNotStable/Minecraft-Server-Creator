package io.github.imnotstable.msc.stdlib.fabric;

import io.github.imnotstable.msc.stdlib.db.Store;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Per-player data kept in a {@link Store}: loaded off the server thread when the player joins, held while they play,
 * saved when they leave and when the server stops. Create it in {@code onInitialize}.
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
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID id = handler.getPlayer().getUUID();
            Stdlib.scheduler().async().execute(() -> data.get(id));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            T v = data.cache.remove(id);
            if (v != null) {
                Stdlib.scheduler().async().execute(() -> store.put(id.toString(), v));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> data.saveAll());
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
}
