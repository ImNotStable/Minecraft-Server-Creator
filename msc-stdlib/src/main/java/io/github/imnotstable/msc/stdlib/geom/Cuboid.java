package io.github.imnotstable.msc.stdlib.geom;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A box between two corners in block coordinates, corners included: arenas, regions, build zones. {@link #contains}
 * takes exact positions; {@link #blocks} lists every block position inside.
 *
 * <pre>{@code
 * Cuboid arena = Cuboid.of(new Vec3(-20, 60, -20), new Vec3(20, 90, 20));
 * if (!arena.contains(Locations.vec(player.getLocation()))) teleportBack(player);
 * }</pre>
 */
public record Cuboid(Vec3 min, Vec3 max) implements Region {
    public Cuboid {
        Vec3 lo = new Vec3(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
        Vec3 hi = new Vec3(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
        min = lo;
        max = hi;
    }

    /** From any two opposite corners. */
    public static Cuboid of(Vec3 a, Vec3 b) {
        return new Cuboid(a, b);
    }

    /** A box of {@code radius} around {@code center} on every side. */
    public static Cuboid around(Vec3 center, double radius) {
        Vec3 r = new Vec3(radius, radius, radius);
        return new Cuboid(center.subtract(r), center.add(r));
    }

    /** True for a position inside, block corners counted in (a block at max is inside up to max + 1). */
    @Override
    public boolean contains(Vec3 p) {
        return p.x() >= min.x() && p.x() < max.x() + 1 && p.y() >= min.y() && p.y() < max.y() + 1
                && p.z() >= min.z() && p.z() < max.z() + 1;
    }

    public boolean intersects(Cuboid o) {
        return min.x() <= o.max.x() && max.x() >= o.min.x() && min.y() <= o.max.y() && max.y() >= o.min.y()
                && min.z() <= o.max.z() && max.z() >= o.min.z();
    }

    public Cuboid expand(double by) {
        Vec3 d = new Vec3(by, by, by);
        return new Cuboid(min.subtract(d), max.add(d));
    }

    public Cuboid shift(Vec3 by) {
        return new Cuboid(min.add(by), max.add(by));
    }

    @Override
    public Vec3 center() {
        return new Vec3((min.x() + max.x() + 1) / 2, (min.y() + max.y() + 1) / 2, (min.z() + max.z() + 1) / 2);
    }

    public int width() {
        return (int) (max.x() - min.x()) + 1;
    }

    public int height() {
        return (int) (max.y() - min.y()) + 1;
    }

    public int length() {
        return (int) (max.z() - min.z()) + 1;
    }

    public long volume() {
        return (long) width() * height() * length();
    }

    /** Every block position inside, x fastest then z then y. Careful with big boxes. */
    public List<Vec3> blocks() {
        List<Vec3> out = new ArrayList<>((int) Math.min(volume(), 1_000_000));
        for (int y = (int) min.y(); y <= (int) max.y(); y++) {
            for (int z = (int) min.z(); z <= (int) max.z(); z++) {
                for (int x = (int) min.x(); x <= (int) max.x(); x++) {
                    out.add(new Vec3(x, y, z));
                }
            }
        }
        return out;
    }

    /** The blocks of the outer walls, floor and ceiling only. */
    public List<Vec3> shell() {
        return blocks().stream().filter(b -> b.x() == min.x() || b.x() == max.x() || b.y() == min.y()
                || b.y() == max.y() || b.z() == min.z() || b.z() == max.z()).toList();
    }

    /** A random block position inside (for spawns and drops). */
    @Override
    public Vec3 randomPoint(RandomGenerator random) {
        return new Vec3(random.nextInt((int) min.x(), (int) max.x() + 1), random.nextInt((int) min.y(), (int) max.y() + 1),
                random.nextInt((int) min.z(), (int) max.z() + 1));
    }

    /** Points along the 12 edges, for outlining the box with particles. */
    public List<Vec3> outline(double step) {
        return Shapes.cuboidEdges(min, max.add(new Vec3(1, 1, 1)), step);
    }
}
