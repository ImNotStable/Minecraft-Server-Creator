package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.imnotstable.msc.stdlib.db.Store;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Per-player data across the whole network, kept in a {@link Store}: loaded at login, saved on disconnect. Needs
 * {@link Stdlib#init}.
 *
 * <pre>{@code
 * PlayerData<Profile> profiles = PlayerData.create(Store.sql(db, "profiles", Codec.json(Profile.class)), Profile::new);
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
        var events = Stdlib.proxy().getEventManager();
        events.register(Stdlib.plugin(), LoginEvent.class, e -> data.get(e.getPlayer().getUniqueId()));
        events.register(Stdlib.plugin(), DisconnectEvent.class, e -> {
            UUID id = e.getPlayer().getUniqueId();
            T v = data.cache.remove(id);
            if (v != null) {
                store.put(id.toString(), v);
            }
        });
        return data;
    }

    public T get(Player player) {
        return get(player.getUniqueId());
    }

    public T get(UUID id) {
        return cache.computeIfAbsent(id, k -> store.get(k.toString()).orElseGet(initial));
    }

    public T update(Player player, UnaryOperator<T> change) {
        return cache.compute(player.getUniqueId(), (id, v) -> change.apply(v == null ? store.get(id.toString())
                .orElseGet(initial) : v));
    }

    public void saveAll() {
        cache.forEach((id, v) -> store.put(id.toString(), v));
    }
}
