package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.Region;
import java.util.BitSet;

/**
 * The positions one edit set (ADR 0049): the smallest box holding them, and one bit per cell of that box, in
 * {@link Region#index} order. An undo puts back every one of them, so it must not run while a later edit that set any
 * of them still stands.
 */
public record Footprint(Region box, BitSet cells) {
    /** Whether this edit and {@code other} set a position in common. */
    public boolean overlaps(Footprint other) {
        Region a = box;
        Region b = other.box;
        if (!a.world().equals(b.world())) {
            return false;
        }
        int x0 = Math.max(a.min().x(), b.min().x());
        int y0 = Math.max(a.min().y(), b.min().y());
        int z0 = Math.max(a.min().z(), b.min().z());
        int x1 = Math.min(a.max().x(), b.max().x());
        int y1 = Math.min(a.max().y(), b.max().y());
        int z1 = Math.min(a.max().z(), b.max().z());
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (cells.get(a.index(x, y, z)) && other.cells.get(b.index(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
