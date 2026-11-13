package io.github.imnotstable.msc.bot;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stone up to y=0 (so the ground surface is y=1), with blocks set by "x,y,z". Counts its reads. */
final class FlatWorld implements BlockSource {
    final Map<String, String> blocks = new HashMap<>();
    final List<Region> regions = new ArrayList<>();
    int reads;

    FlatWorld set(int x, int y, int z, String state) {
        blocks.put(x + "," + y + "," + z, state);
        return this;
    }

    FlatWorld gap(int x1, int x2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -8; y <= 0; y++) {
                    set(x, y, z, "minecraft:air");
                }
            }
        }
        return this;
    }

    @Override
    public BlockGrid read(Region r) {
        reads++;
        regions.add(r);
        List<String> palette = new ArrayList<>();
        int[] cells = new int[(int) r.volume()];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String s = blocks.getOrDefault(x + "," + y + "," + z, y <= 0 ? "minecraft:stone" : "minecraft:air");
                    int i = palette.indexOf(s);
                    if (i < 0) {
                        palette.add(s);
                        i = palette.size() - 1;
                    }
                    cells[r.index(x, y, z)] = i;
                }
            }
        }
        return new BlockGrid(r, palette, cells);
    }
}
