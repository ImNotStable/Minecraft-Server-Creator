package io.github.imnotstable.msc.agent.minestom;

import net.minestom.server.instance.block.Block;

/** Vanilla block state strings to Minestom blocks and back. */
final class BlockStates {
    private BlockStates() {
    }

    /**
     * Parses {@code minecraft:oak_stairs[facing=north]}; the namespace may be left out. Properties not
     * named keep their default values, as in vanilla.
     *
     * @throws IllegalArgumentException for an unknown block or property
     */
    static Block parse(String state) {
        String s = state.strip();
        int bracket = s.indexOf('[');
        String id = bracket < 0 ? s : s.substring(0, bracket);
        if (!id.contains(":")) {
            s = "minecraft:" + s;
        }
        Block block;
        try {
            block = Block.fromState(s);
        } catch (RuntimeException e) {
            block = null;
        }
        if (block == null) {
            throw new IllegalArgumentException("unknown block state: " + state);
        }
        return block;
    }

    /** The full vanilla state string, for example {@code minecraft:oak_stairs[facing=north,half=bottom,...]}. */
    static String format(Block block) {
        return block.state();
    }
}
