package io.github.imnotstable.msc.bot;

import java.util.List;

/**
 * How a block's collision boxes ({@link BlockShapes}) meet a walking player, block by block. The route search for
 * {@code walk} and {@code world_summary path} judges blocks with it, so it plans with the shapes the test player
 * then collides with.
 */
public enum Footing {
    /** Nothing to bump into, or a box no higher than a carpet: feet and head pass. */
    OPEN,
    /** Low enough to walk onto without a jump (step height 0.6): bottom slabs, stairs, beds, closed trapdoors. */
    STEP,
    /** In the way; a jump climbs it. */
    SOLID,
    /** Higher than a jump (fences, walls, closed fence gates). */
    TALL;

    private static final double CARPET = 1 / 16.0;
    private static final double STEP_HEIGHT = 0.6;

    /** The footing of a block state such as {@code minecraft:oak_door[half=lower,open=false]}. */
    public static Footing of(String state) {
        List<Box> boxes = BlockShapes.of(state);
        double top = 0;
        boolean lowBase = false;
        for (Box b : boxes) {
            top = Math.max(top, b.maxY());
            if (b.minY() == 0 && b.maxY() <= STEP_HEIGHT && b.minX() == 0 && b.minZ() == 0 && b.maxX() == 1
                    && b.maxZ() == 1) {
                lowBase = true; // stairs: step onto the base, then onto the step
            }
        }
        if (top <= CARPET) {
            return OPEN;
        }
        if (top > 1) {
            return TALL;
        }
        return top <= STEP_HEIGHT || lowBase ? STEP : SOLID;
    }
}
