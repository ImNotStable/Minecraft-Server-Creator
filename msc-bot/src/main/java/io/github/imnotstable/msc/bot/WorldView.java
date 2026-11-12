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

    /** Make sure {@code area} is loaded and fresh enough. */
    void ensure(Box area) throws Exception {
        age++;
        if (grid != null && age < REFRESH_TICKS && covers(area)) {
            return;
        }
        int cx = (int) Math.floor((area.minX() + area.maxX()) / 2);
        int cy = (int) Math.floor(area.minY());
        int cz = (int) Math.floor((area.minZ() + area.maxZ()) / 2);
        grid = source.read(Region.of(world, cx - RADIUS, cy - RADIUS, cz - RADIUS, cx + RADIUS, cy + RADIUS + 2,
                cz + RADIUS));
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
