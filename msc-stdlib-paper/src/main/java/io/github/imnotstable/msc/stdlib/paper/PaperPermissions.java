package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.perm.Permissions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feeds {@link Permissions} (groups, ranks, timed nodes) into Bukkit's own permission checks, so
 * {@code player.hasPermission(...)}, commands' {@code permission(...)} and other plugins all see them. Wildcards are
 * matched by {@link Permissions#has}; Bukkit sees each node a player has set.
 *
 * <pre>{@code
 * Permissions perms = Permissions.sql(db, "perms");
 * PaperPermissions.use(perms);           // in onEnable, after Stdlib.init
 * perms.addGroup(id, "vip"); PaperPermissions.refresh(player);
 * }</pre>
 */
public final class PaperPermissions {
    private static Permissions perms;
    private static final Map<UUID, PermissionAttachment> ATTACHED = new ConcurrentHashMap<>();

    private PaperPermissions() {}

    public static void use(Permissions permissions) {
        perms = permissions;
        Stdlib.plugin().getServer().getPluginManager().registerEvents(new Hooks(), Stdlib.plugin());
        for (Player p : Stdlib.plugin().getServer().getOnlinePlayers()) {
            refresh(p);
        }
        // Timed nodes run out: look again every minute.
        Stdlib.scheduler().repeat(1200, 1200, () -> {
            perms.invalidate();
            Stdlib.plugin().getServer().getOnlinePlayers().forEach(PaperPermissions::refresh);
        });
    }

    public static Permissions permissions() {
        if (perms == null) {
            throw new IllegalStateException("Call PaperPermissions.use(...) first");
        }
        return perms;
    }

    /** Push a player's nodes to Bukkit again, after changing their groups or nodes. */
    public static void refresh(Player player) {
        PermissionAttachment old = ATTACHED.remove(player.getUniqueId());
        if (old != null) {
            player.removeAttachment(old);
        }
        PermissionAttachment a = player.addAttachment(Stdlib.plugin());
        permissions().effective(player.getUniqueId()).forEach(a::setPermission);
        ATTACHED.put(player.getUniqueId(), a);
        player.recalculatePermissions();
        player.updateCommands();
    }

    /** Whether a player has a node, wildcards and denials included. */
    public static boolean has(Player player, String node) {
        return permissions().has(player.getUniqueId(), node);
    }

    static final class Hooks implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onJoin(PlayerJoinEvent e) {
            refresh(e.getPlayer());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            ATTACHED.remove(e.getPlayer().getUniqueId());
        }
    }
}
