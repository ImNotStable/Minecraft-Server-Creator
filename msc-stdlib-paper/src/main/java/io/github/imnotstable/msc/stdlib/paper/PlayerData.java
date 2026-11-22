package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.db.Store;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Per-player data kept in a {@link Store}: loaded while the player logs in (off the main thread), held in memory
 * while they play, saved when they quit and on {@link #saveAll}. Needs {@link Stdlib#init}.
 *
 * <pre>{@code
 * record Stats(int kills, int deaths) {}
 * PlayerData<Stats> stats = PlayerData.create(Store.sql(db, "stats", Codec.json(Stats.class)), () -> new Stats(0, 0));
 * stats.update(killer, s -> new Stats(s.kills() + 1, s.deaths()));
 * // onDisable: stats.saveAll();
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

    /** Registers its listener with the plugin from {@link Stdlib#init}. */
    public static <T> PlayerData<T> create(Store<T> store, Supplier<T> initial) {
        PlayerData<T> data = new PlayerData<>(store, initial);
        var plugin = Stdlib.plugin();
        plugin.getServer().getPluginManager().registerEvents(data.new Hooks(), plugin);
        return data;
    }

    public T get(Player player) {
        return get(player.getUniqueId());
    }

    /** The player's data; loads it (blocking) when they are not online, {@code initial} when there is none. */
    public T get(UUID id) {
        return cache.computeIfAbsent(id, this::load);
    }

    public void set(Player player, T value) {
        cache.put(player.getUniqueId(), value);
    }

    /** Changes the player's data in memory; it is saved on quit. Returns the new value. */
    public T update(Player player, UnaryOperator<T> change) {
        return cache.compute(player.getUniqueId(), (id, v) -> change.apply(v == null ? load(id) : v));
    }

    /** Writes one player's data now. */
    public void save(UUID id) {
        T v = cache.get(id);
        if (v != null) {
            store.put(id.toString(), v);
        }
    }

    /** Writes every cached player's data; call in {@code onDisable}. */
    public void saveAll() {
        cache.keySet().forEach(this::save);
    }

    private T load(UUID id) {
        return store.get(id.toString()).orElseGet(initial);
    }

    private final class Hooks implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void preLogin(AsyncPlayerPreLoginEvent e) {
            if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                cache.put(e.getUniqueId(), load(e.getUniqueId()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void quit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            T v = cache.remove(id);
            if (v != null) {
                Stdlib.scheduler().async().execute(() -> store.put(id.toString(), v));
            }
        }
    }
}
