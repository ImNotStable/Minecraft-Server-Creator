package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.geom.Region;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Named areas of an instance with rules and enter/leave actions: spawn protection, arenas, safe zones.
 *
 * <pre>{@code
 * Regions.define("spawn", world, Cuboid.of(new Vec3(-30, 0, -30), new Vec3(30, 320, 30)))
 *         .noBuild().noPvp().onEnter(p -> Feedback.actionBar(p, "<green>Spawn"));
 * }</pre>
 */
public final class Regions {
    private static final Map<String, Area> AREAS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> INSIDE = new ConcurrentHashMap<>();
    private static boolean listening;

    private Regions() {}

    public static final class Area {
        private final String name;
        private final Instance instance;
        private final Region region;
        private final List<Consumer<Player>> enter = new CopyOnWriteArrayList<>();
        private final List<Consumer<Player>> leave = new CopyOnWriteArrayList<>();
        private boolean noBuild;
        private boolean noPvp;
        private boolean noDamage;

        private Area(String name, Instance instance, Region region) {
            this.name = name;
            this.instance = instance;
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

        public Area noDamage() {
            this.noDamage = true;
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

        public boolean contains(Instance in, Point p) {
            return in == instance && region.contains(Locations.vec(p));
        }

        public String name() {
            return name;
        }
    }

    public static Area define(String name, Instance instance, Region region) {
        listen();
        Area a = new Area(name, instance, region);
        AREAS.put(name, a);
        return a;
    }

    public static void remove(String name) {
        AREAS.remove(name);
    }

    public static List<Area> at(Instance instance, Point p) {
        return AREAS.values().stream().filter(a -> a.contains(instance, p)).toList();
    }

    static void update(Player p, Instance instance, Point to) {
        Set<String> now = new HashSet<>();
        AREAS.values().stream().filter(a -> a.contains(instance, to)).forEach(a -> now.add(a.name));
        Set<String> before = INSIDE.getOrDefault(p.getUuid(), Set.of());
        now.stream().filter(n -> !before.contains(n)).forEach(n -> AREAS.get(n).enter.forEach(c -> c.accept(p)));
        before.stream().filter(n -> !now.contains(n)).map(AREAS::get).filter(java.util.Objects::nonNull)
                .forEach(a -> a.leave.forEach(c -> c.accept(p)));
        INSIDE.put(p.getUuid(), now);
    }

    private static boolean denied(Instance in, Point p, java.util.function.Predicate<Area> rule) {
        return AREAS.values().stream().anyMatch(a -> rule.test(a) && a.contains(in, p));
    }

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        var events = Stdlib.events();
        events.addListener(PlayerMoveEvent.class, e -> {
            if (!e.getNewPosition().sameBlock(e.getPlayer().getPosition())) {
                update(e.getPlayer(), e.getInstance(), e.getNewPosition());
            }
        });
        events.addListener(PlayerDisconnectEvent.class, e -> INSIDE.remove(e.getPlayer().getUuid()));
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (denied(e.getInstance(), e.getBlockPosition(), a -> a.noBuild)) {
                e.setCancelled(true);
            }
        });
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (denied(e.getInstance(), e.getBlockPosition(), a -> a.noBuild)) {
                e.setCancelled(true);
            }
        });
        events.addListener(EntityDamageEvent.class, e -> {
            if (e.getEntity() instanceof Player p && p.getInstance() != null
                    && denied(p.getInstance(), p.getPosition(), a -> a.noDamage)) {
                e.setCancelled(true);
            }
        });
    }

    /** Minestom deals no attack damage itself: games that do check this first (false where {@link Area#noPvp}). */
    public static boolean pvpAllowed(Player victim) {
        return victim.getInstance() == null || !denied(victim.getInstance(), victim.getPosition(), a -> a.noPvp);
    }

    static synchronized void reset() {
        listening = false;
        AREAS.clear();
        INSIDE.clear();
    }
}
