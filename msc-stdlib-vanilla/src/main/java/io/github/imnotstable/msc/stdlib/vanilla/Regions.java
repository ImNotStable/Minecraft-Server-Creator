package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.geom.Region;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Named areas of a dimension with rules and enter/leave actions: spawn protection, arenas, safe zones. Positions are
 * checked every 5 ticks; {@code Stdlib.init()} routes block breaks, placements and attacks here.
 *
 * <pre>{@code
 * Regions.define("spawn", Level.OVERWORLD, Cuboid.of(new Vec3(-30, -64, -30), new Vec3(30, 320, 30)))
 *         .noBuild().noPvp().onEnter(p -> Feedback.actionBar(p, "Spawn"));
 * }</pre>
 */
public final class Regions {
    private static final Map<String, Area> AREAS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> INSIDE = new ConcurrentHashMap<>();
    private static int ticks;

    private Regions() {}

    public static final class Area {
        private final String name;
        private final ResourceKey<Level> dimension;
        private final Region region;
        private final List<Consumer<ServerPlayer>> enter = new CopyOnWriteArrayList<>();
        private final List<Consumer<ServerPlayer>> leave = new CopyOnWriteArrayList<>();
        private boolean noBuild;
        private boolean noPvp;

        private Area(String name, ResourceKey<Level> dimension, Region region) {
            this.name = name;
            this.dimension = dimension;
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

        public Area onEnter(Consumer<ServerPlayer> action) {
            enter.add(action);
            return this;
        }

        public Area onLeave(Consumer<ServerPlayer> action) {
            leave.add(action);
            return this;
        }

        public boolean contains(ResourceKey<Level> dim, Vec3 p) {
            return dim.equals(dimension) && region.contains(p);
        }

        public String name() {
            return name;
        }
    }

    public static Area define(String name, ResourceKey<Level> dimension, Region region) {
        Area a = new Area(name, dimension, region);
        AREAS.put(name, a);
        return a;
    }

    public static void remove(String name) {
        AREAS.remove(name);
    }

    static boolean denied(ResourceKey<Level> dim, Vec3 p, Predicate<Area> rule) {
        return AREAS.values().stream().anyMatch(a -> rule.test(a) && a.contains(dim, p));
    }

    /** False where a {@link Area#noBuild} area covers {@code pos}. */
    public static boolean canBuild(ResourceKey<Level> dim, BlockPos pos) {
        return !denied(dim, Locations.vec(pos), a -> a.noBuild);
    }

    /** False where a {@link Area#noPvp} area covers {@code victim}. */
    public static boolean canAttack(ServerPlayer victim) {
        return !denied(victim.level().dimension(), Locations.vec(victim.position()), a -> a.noPvp);
    }

    static void update(ServerPlayer player, ResourceKey<Level> dim, Vec3 at) {
        Set<String> now = new HashSet<>();
        AREAS.values().stream().filter(a -> a.contains(dim, at)).forEach(a -> now.add(a.name));
        Set<String> before = INSIDE.getOrDefault(player.getUUID(), Set.of());
        now.stream().filter(n -> !before.contains(n)).forEach(n -> AREAS.get(n).enter.forEach(c -> c.accept(player)));
        before.stream().filter(n -> !now.contains(n)).map(AREAS::get).filter(java.util.Objects::nonNull)
                .forEach(a -> a.leave.forEach(c -> c.accept(player)));
        INSIDE.put(player.getUUID(), now);
    }

    /** Called every server tick by {@code Stdlib}: checks positions every 5. */
    static void tick(MinecraftServer server) {
        if (AREAS.isEmpty() || ++ticks % 5 != 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            update(p, p.level().dimension(), Locations.vec(p.position()));
        }
    }

    static void left(UUID player) {
        INSIDE.remove(player);
    }
}
