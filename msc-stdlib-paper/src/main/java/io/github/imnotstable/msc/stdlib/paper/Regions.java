package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.ui.Feedback;
import io.github.imnotstable.msc.stdlib.geom.Region;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Named areas with rules and enter/leave actions: spawn protection, arenas, shops, safe zones, music zones. Areas are
 * {@link Region}s from msc-stdlib ({@code Cuboid}, {@code Region.sphere}, {@code Region.cylinder}) in one world.
 *
 * <pre>{@code
 * Regions.define("spawn", "world", Cuboid.of(new Vec3(-30, 0, -30), new Vec3(30, 320, 30)))
 *         .noBuild().noPvp().noDamage()
 *         .onEnter(p -> Feedback.actionBar(p, "<green>Entering spawn"))
 *         .onLeave(p -> Feedback.actionBar(p, "<gray>Leaving spawn"));
 * Regions.at(player.getLocation())   // regions a location is in
 * }</pre>
 * Players with {@code <regions bypass permission>} ({@link Area#bypass}) ignore the build rule.
 */
public final class Regions {
    private static final Map<String, Area> AREAS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> INSIDE = new ConcurrentHashMap<>();
    private static boolean listening;

    private Regions() {}

    /** One named area and its rules. */
    public static final class Area {
        private final String name;
        private final String world;
        private final Region region;
        private final List<Consumer<Player>> enter = new CopyOnWriteArrayList<>();
        private final List<Consumer<Player>> leave = new CopyOnWriteArrayList<>();
        private boolean noBuild;
        private boolean noPvp;
        private boolean noDamage;
        private String bypass = "";

        private Area(String name, String world, Region region) {
            this.name = name;
            this.world = world;
            this.region = region;
        }

        public Area noBuild() {
            this.noBuild = true;
            return this;
        }

        public Area noPvp() {
            this.noPvp = true;
            return this;
        }

        /** Players inside take no damage at all. */
        public Area noDamage() {
            this.noDamage = true;
            return this;
        }

        /** Players with this permission may build anyway. */
        public Area bypass(String permission) {
            this.bypass = permission;
            return this;
        }

        public Area onEnter(Consumer<Player> action) {
            enter.add(action);
            return this;
        }

        public Area onLeave(Consumer<Player> action) {
            leave.add(action);
            return this;
        }

        public boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && region.contains(Locations.vec(l));
        }

        public String name() {
            return name;
        }

        public Region region() {
            return region;
        }

        /** Players inside now. */
        public List<Player> players() {
            return Stdlib.plugin().getServer().getOnlinePlayers().stream().filter(p -> contains(p.getLocation()))
                    .map(p -> (Player) p).toList();
        }
    }

    public static Area define(String name, String world, Region region) {
        listen();
        Area a = new Area(name, world, region);
        AREAS.put(name, a);
        return a;
    }

    public static void remove(String name) {
        AREAS.remove(name);
    }

    public static Area get(String name) {
        return AREAS.get(name);
    }

    /** Every area {@code location} is in. */
    public static List<Area> at(Location location) {
        return AREAS.values().stream().filter(a -> a.contains(location)).toList();
    }

    private static boolean denied(Location l, java.util.function.Predicate<Area> rule) {
        return AREAS.values().stream().anyMatch(a -> rule.test(a) && a.contains(l));
    }

    /** Runs enter and leave actions for a player now at {@code to}. */
    static void update(Player p, Location to) {
        Set<String> now = new HashSet<>();
        for (Area a : AREAS.values()) {
            if (a.contains(to)) {
                now.add(a.name);
            }
        }
        Set<String> before = INSIDE.getOrDefault(p.getUniqueId(), Set.of());
        for (String n : now) {
            if (!before.contains(n)) {
                AREAS.get(n).enter.forEach(c -> c.accept(p));
            }
        }
        for (String n : before) {
            Area a = AREAS.get(n);
            if (a != null && !now.contains(n)) {
                a.leave.forEach(c -> c.accept(p));
            }
        }
        INSIDE.put(p.getUniqueId(), now);
    }

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        var plugin = Stdlib.plugin();
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void move(PlayerMoveEvent e) {
                if (e.hasChangedBlock()) {
                    update(e.getPlayer(), e.getTo());
                }
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void teleport(PlayerTeleportEvent e) {
                update(e.getPlayer(), e.getTo());
            }

            @EventHandler
            public void quit(PlayerQuitEvent e) {
                INSIDE.remove(e.getPlayer().getUniqueId());
            }

            @EventHandler(ignoreCancelled = true)
            public void breakBlock(BlockBreakEvent e) {
                if (denied(e.getBlock().getLocation(), a -> a.noBuild && !bypasses(e.getPlayer(), a))) {
                    e.setCancelled(true);
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void place(BlockPlaceEvent e) {
                if (denied(e.getBlock().getLocation(), a -> a.noBuild && !bypasses(e.getPlayer(), a))) {
                    e.setCancelled(true);
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void pvp(EntityDamageByEntityEvent e) {
                if (e.getEntity() instanceof Player victim && e.getDamager() instanceof Player
                        && denied(victim.getLocation(), a -> a.noPvp)) {
                    e.setCancelled(true);
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void damage(EntityDamageEvent e) {
                if (e.getEntity() instanceof Player p && denied(p.getLocation(), a -> a.noDamage)) {
                    e.setCancelled(true);
                }
            }
        }, plugin);
    }

    private static boolean bypasses(Player p, Area a) {
        return !a.bypass.isEmpty() && p.hasPermission(a.bypass);
    }

    static synchronized void reset() {
        listening = false;
        AREAS.clear();
        INSIDE.clear();
    }
}
