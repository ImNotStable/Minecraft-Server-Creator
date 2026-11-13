package io.github.imnotstable.msc.bot;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;

/**
 * The blocks around the player, read in small boxes from a {@link BlockSource} and re-read every few ticks so
 * blocks that change (a door opening, a pane breaking) are seen.
 */
final class WorldView {
    static final int RADIUS = 5;
    static final int REFRESH_TICKS = 10;
    private final BlockSource source;
    private final String world;
    private BlockGrid grid;
    private int age;

    WorldView(BlockSource source, String world) {
        this.source = source;
        this.world = world;
    }

    /**
     * Make sure {@code needed} (what this tick's move touches) is loaded and fresh enough. A new read covers
     * {@code ahead} too, the next few ticks of the move, so a fast fall does not read every tick.
     */
    void ensure(Box needed, Box ahead) throws Exception {
        age++;
        if (grid != null && age < REFRESH_TICKS && covers(needed)) {
            return;
        }
        Box want = new Box(Math.min(needed.minX(), ahead.minX()), Math.min(needed.minY(), ahead.minY()),
                Math.min(needed.minZ(), ahead.minZ()), Math.max(needed.maxX(), ahead.maxX()),
                Math.max(needed.maxY(), ahead.maxY()), Math.max(needed.maxZ(), ahead.maxZ()));
        grid = source.read(Region.of(world, (int) Math.floor(want.minX()) - RADIUS,
                (int) Math.floor(want.minY()) - RADIUS, (int) Math.floor(want.minZ()) - RADIUS,
                (int) Math.floor(want.maxX()) + RADIUS, (int) Math.floor(want.maxY()) + 2,
                (int) Math.floor(want.maxZ()) + RADIUS));
        age = 0;
    }

    private boolean covers(Box a) {
        Region r = grid.region();
        return Math.floor(a.minX()) - 1 >= r.min().x() && Math.floor(a.maxX()) + 1 <= r.max().x()
                && Math.floor(a.minY()) - 1 >= r.min().y() && Math.floor(a.maxY()) + 1 <= r.max().y()
                && Math.floor(a.minZ()) - 1 >= r.min().z() && Math.floor(a.maxZ()) + 1 <= r.max().z();
    }

    /** The state at a block, or air outside what was read. */
    String state(int x, int y, int z) {
        if (grid == null) {
            return "minecraft:air";
        }
        Region r = grid.region();
        if (x < r.min().x() || x > r.max().x() || y < r.min().y() || y > r.max().y() || z < r.min().z()
                || z > r.max().z()) {
            return "minecraft:air";
        }
        String s = grid.stateAt(x, y, z);
        return s == null ? "minecraft:air" : s;
    }

    /** Every block box touching {@code area}. */
    List<Box> collisions(Box area) {
        List<Box> out = new ArrayList<>();
        for (int x = (int) Math.floor(area.minX()); x <= (int) Math.floor(area.maxX()); x++) {
            for (int y = (int) Math.floor(area.minY()) - 1; y <= (int) Math.floor(area.maxY()); y++) {
                for (int z = (int) Math.floor(area.minZ()); z <= (int) Math.floor(area.maxZ()); z++) {
                    for (Box b : BlockShapes.of(state(x, y, z))) {
                        Box w = b.offset(x, y, z);
                        if (w.intersects(area)) {
                            out.add(w);
                        }
                    }
                }
            }
        }
        return out;
    }
}
