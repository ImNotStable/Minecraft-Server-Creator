package io.github.imnotstable.msc.protocol.world;

import io.github.imnotstable.msc.protocol.Limits;
import java.util.Objects;

/**
 * An inclusive box of blocks in one world. The constructor sorts the corners, so {@code min} is
 * always the lowest corner and {@code max} the highest.
 *
 * <p>Block arrays that cover a region are ordered Y, then Z, then X (X changes fastest), the same
 * order as Sponge schematics: see {@link #index(int, int, int)}.
 */
public record Region(String world, BlockPos min, BlockPos max) {
    public Region {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        BlockPos lo = new BlockPos(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
        BlockPos hi = new BlockPos(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
        min = lo;
        max = hi;
    }

    public static Region of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(world, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
    }

    /** Blocks along X. Throws {@link ArithmeticException} if the span does not fit an int. */
    public int sizeX() {
        return Math.toIntExact(span(min.x(), max.x()));
    }

    public int sizeY() {
        return Math.toIntExact(span(min.y(), max.y()));
    }

    public int sizeZ() {
        return Math.toIntExact(span(min.z(), max.z()));
    }

    /** Number of blocks, computed in long and capped at {@link Long#MAX_VALUE}, so it never wraps. */
    public long volume() {
        try {
            return Math.multiplyExact(Math.multiplyExact(span(min.x(), max.x()), span(min.y(), max.y())), span(min.z(), max.z()));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long span(int lo, int hi) {
        return (long) hi - lo + 1;
    }

    public boolean contains(int x, int y, int z) {
        return x >= min.x() && x <= max.x() && y >= min.y() && y <= max.y() && z >= min.z() && z <= max.z();
    }

    /**
     * Whether the point lies in one of this region's blocks: the rule every agent uses for entities, by position and
     * not by hitbox, so a region split into pieces holds each entity in exactly one piece.
     */
    public boolean containsPoint(double x, double y, double z) {
        return x >= min.x() && x < max.x() + 1 && y >= min.y() && y < max.y() + 1 && z >= min.z() && z < max.z() + 1;
    }

    /** Index into a Y-Z-X ordered block array for the world position (x, y, z). */
    public int index(int x, int y, int z) {
        if (!contains(x, y, z)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") is outside " + this);
        }
        return ((y - min.y()) * sizeZ() + (z - min.z())) * sizeX() + (x - min.x());
    }

    /** Throws if this region is larger than {@link Limits#MAX_REGION_VOLUME}. */
    public Region requireWithinLimit() {
        if (volume() > Limits.MAX_REGION_VOLUME) {
            throw new IllegalArgumentException("region volume " + volume() + " exceeds " + Limits.MAX_REGION_VOLUME);
        }
        return this;
    }
}
