package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.world.BlockNames;
import java.util.List;

/** Finding the ground under plants, leaves and trunks, for the steps that sit on it (ADR 0075). */
final class Surface {
    /** No ground in the column's window. */
    static final int NONE = Integer.MIN_VALUE;

    /** Name parts of what the ground search looks through: air, plants, snow layers, leaves and trunks. */
    private static final List<String> THROUGH = List.of("short_grass", "tall_grass", "fern", "flower", "tulip",
            "poppy", "dandelion", "orchid", "allium", "bluet", "daisy", "cornflower", "lily_of_the_valley", "rose_bush",
            "peony", "lilac", "sunflower", "dead_bush", "bush", "sapling", "petals", "leaf_litter", "vine", "leaves",
            "_log", "_wood", "_stem", "hyphae", "torch", "lantern", "carpet");

    private Surface() {
    }

    /** Whether the ground search looks through this block. {@code snow} is a layer; {@code snow_block} is ground. */
    static boolean through(String state) {
        String id = BlockNames.brief(BlockNames.id(state));
        if (BlockNames.isAir(state) || id.equals("snow") || id.endsWith("mushroom")) {
            return true;
        }
        for (String part : THROUGH) {
            if (id.contains(part) && !id.equals("grass_block")) {
                return true;
            }
        }
        return false;
    }

    /** A plant or snow layer on the ground, which a path clears: what {@link #through} passes, less trees and lights. */
    static boolean plant(String state) {
        String id = BlockNames.brief(BlockNames.id(state));
        return through(state) && !BlockNames.isAir(state) && !id.contains("leaves") && !id.endsWith("_log")
                && !id.endsWith("_wood") && !id.endsWith("_stem") && !id.endsWith("hyphae") && !id.contains("torch")
                && !id.contains("lantern");
    }

    static boolean fluid(String state) {
        String id = BlockNames.brief(BlockNames.id(state));
        return id.equals("water") || id.equals("lava") || id.equals("bubble_column");
    }

    /** The highest ground block of a column from {@code top} down to {@code bottom}, or {@link #NONE}. */
    static int top(Canvas.Lookup at, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            if (!through(at.at(x, y, z))) {
                return y;
            }
        }
        return NONE;
    }
}
