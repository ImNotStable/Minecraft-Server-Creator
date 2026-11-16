package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Seeded random grids for the encoding property tests. */
final class RandomGrids {
    private static final String[] STATES = {"stone", "dirt", "oak_planks", "glass", "mod:thing", "cave_air", "air",
            "oak_stairs[facing=north,half=bottom]", "oak_stairs[facing=south,half=top]", "redstone_wire[power=15]",
            "mymod:block[a=1]", "minecraft:void_air"};

    private RandomGrids() {
    }

    /**
     * A grid of up to {@code kinds} palette entries (equal entries allowed). Blocks copy their left or lower
     * neighbour often, so rows and layers repeat and the run-length forms come up.
     *
     * @param unchanged whether cells may be -1 ("leave unchanged")
     */
    static BlockGrid grid(Random rnd, int kinds, boolean unchanged) {
        int sx = 1 + rnd.nextInt(rnd.nextInt(8) == 0 ? 40 : 8);
        int sy = 1 + rnd.nextInt(rnd.nextInt(8) == 0 ? 20 : 6);
        int sz = 1 + rnd.nextInt(rnd.nextInt(8) == 0 ? 40 : 8);
        int ox = rnd.nextInt(2_000_000) - 1_000_000;
        int oy = rnd.nextInt(600) - 300;
        int oz = rnd.nextInt(2_000_000) - 1_000_000;
        Region r = Region.of("world", ox, oy, oz, ox + sx - 1, oy + sy - 1, oz + sz - 1);
        List<String> palette = new ArrayList<>();
        int n = 1 + rnd.nextInt(kinds);
        for (int i = 0; i < n; i++) {
            String s = rnd.nextInt(3) == 0 ? STATES[rnd.nextInt(STATES.length)] : "k" + rnd.nextInt(kinds * 2);
            palette.add(rnd.nextBoolean() && !s.contains(":") ? "minecraft:" + s : s);
        }
        int[] blocks = new int[(int) r.volume()];
        int mode = rnd.nextInt(4);
        int used = 1 + rnd.nextInt(n);
        for (int i = 0; i < blocks.length; i++) {
            if (mode == 0 && i > 0 && rnd.nextInt(4) != 0) {
                blocks[i] = blocks[i - 1];
            } else if (mode == 1 && i >= sx && rnd.nextInt(3) != 0) {
                blocks[i] = blocks[i - sx];
            } else if (mode == 2 && i >= sx * sz && rnd.nextInt(3) != 0) {
                blocks[i] = blocks[i - sx * sz];
            } else {
                blocks[i] = unchanged && rnd.nextInt(10) == 0 ? -1 : rnd.nextInt(used);
            }
        }
        return new BlockGrid(r, palette, blocks);
    }

    /** The state of every cell, null for "leave unchanged", after {@code normal}. */
    static List<String> cells(BlockGrid g, java.util.function.UnaryOperator<String> normal) {
        List<String> out = new ArrayList<>(g.blocks().length);
        for (int b : g.blocks()) {
            out.add(b < 0 ? null : normal.apply(g.palette().get(b)));
        }
        return out;
    }
}
