package io.github.imnotstable.msc.agent.paper;

import org.bukkit.World;

/** Block entity data as SNBT (ADR 0042): Paper's API has none, so it goes through /data on Paper and NBT on Folia. */
interface BlockNbt {
    /** The data at a position with its id and position (the core drops those), or null when there is none. */
    String get(World world, int x, int y, int z);

    /** Merges {@code snbt} into the block entity at a position, as {@code /data merge block} does. */
    void merge(World world, int x, int y, int z, String snbt);
}
