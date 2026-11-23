package io.github.imnotstable.msc.stdlib.game;

import io.github.imnotstable.msc.stdlib.geom.Cuboid;
import io.github.imnotstable.msc.stdlib.geom.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where a game is played: its bounds, the lobby players wait in, spawn points (shared, or per team), where spectators
 * watch, and how many players it takes. Plain data in the stdlib's own {@link Vec3}; the platform turns points into
 * locations in a world.
 *
 * <pre>{@code
 * Arena arena = Arena.builder("ruins")
 *         .bounds(Cuboid.of(new Vec3(0, 60, 0), new Vec3(80, 100, 80)))
 *         .lobby(new Vec3(40, 101, 40))
 *         .spawn("red", new Vec3(5, 64, 5)).spawn("blue", new Vec3(75, 64, 75))
 *         .players(2, 16)
 *         .build();
 * }</pre>
 */
public record Arena(String name, Cuboid bounds, Vec3 lobby, Map<String, List<Vec3>> spawns, Vec3 spectate,
                    int minPlayers, int maxPlayers) {
    /** The spawn list for players of no team, or of a team without its own spawns. */
    public static final String SHARED = "*";

    public Arena {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lobby, "lobby");
        if (spawns.isEmpty()) {
            throw new IllegalArgumentException("arena " + name + " has no spawn points");
        }
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new IllegalArgumentException("players must be 1 <= min <= max, got " + minPlayers + ".." + maxPlayers);
        }
        Map<String, List<Vec3>> copy = new LinkedHashMap<>();
        spawns.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        spawns = Map.copyOf(copy);
        spectate = spectate == null ? lobby : spectate;
    }

    /** The spawn points for {@code team} (null for none), falling back to the shared ones, then to all of them. */
    public List<Vec3> spawnsFor(String team) {
        List<Vec3> own = team == null ? null : spawns.get(team);
        if (own != null && !own.isEmpty()) {
            return own;
        }
        List<Vec3> shared = spawns.get(SHARED);
        if (shared != null && !shared.isEmpty()) {
            return shared;
        }
        List<Vec3> all = new ArrayList<>();
        spawns.values().forEach(all::addAll);
        return all;
    }

    /** Whether a point is inside the arena; an arena without bounds contains everything. */
    public boolean contains(Vec3 p) {
        return bounds == null || bounds.contains(p);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private Cuboid bounds;
        private Vec3 lobby;
        private Vec3 spectate;
        private final Map<String, List<Vec3>> spawns = new LinkedHashMap<>();
        private int min = 1;
        private int max = 16;

        private Builder(String name) {
            this.name = name;
        }

        public Builder bounds(Cuboid bounds) {
            this.bounds = bounds;
            return this;
        }

        public Builder lobby(Vec3 lobby) {
            this.lobby = lobby;
            return this;
        }

        public Builder spectate(Vec3 spectate) {
            this.spectate = spectate;
            return this;
        }

        /** A spawn point everyone may use. */
        public Builder spawn(Vec3 point) {
            return spawn(SHARED, point);
        }

        /** A spawn point for one team. */
        public Builder spawn(String team, Vec3 point) {
            spawns.computeIfAbsent(team, k -> new ArrayList<>()).add(point);
            return this;
        }

        public Builder players(int min, int max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public Arena build() {
            return new Arena(name, bounds, lobby, spawns, spectate, min, max);
        }
    }
}
