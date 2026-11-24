package io.github.imnotstable.msc.stdlib.bungeecord;

import io.github.imnotstable.msc.stdlib.db.Store;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

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
        Stdlib.proxy().getPluginManager().registerListener(Stdlib.plugin(), data.new Events());
        return data;
    }

    /** BungeeCord finds handlers by annotation, so the hooks live on a listener object. */
    public final class Events implements Listener {
        @EventHandler
        public void onLogin(PostLoginEvent e) {
            get(e.getPlayer().getUniqueId());
        }

        @EventHandler
        public void onDisconnect(PlayerDisconnectEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            T v = cache.remove(id);
            if (v != null) {
                store.put(id.toString(), v);
            }
        }
    }

    public T get(ProxiedPlayer player) {
        return get(player.getUniqueId());
    }

    public T get(UUID id) {
        return cache.computeIfAbsent(id, k -> store.get(k.toString()).orElseGet(initial));
    }

    public T update(ProxiedPlayer player, UnaryOperator<T> change) {
        return cache.compute(player.getUniqueId(), (id, v) -> change.apply(v == null ? store.get(id.toString())
                .orElseGet(initial) : v));
    }

    public void saveAll() {
        cache.forEach((id, v) -> store.put(id.toString(), v));
    }
}
