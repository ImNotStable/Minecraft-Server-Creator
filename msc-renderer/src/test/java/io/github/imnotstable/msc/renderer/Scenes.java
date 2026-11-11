package io.github.imnotstable.msc.renderer;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Test helper: builds small grids block by block and decodes results. */
final class Scenes {
    private final Region region;
    private final List<String> palette = new ArrayList<>(List.of("minecraft:air"));
    private final int[] blocks;

    Scenes(int x1, int y1, int z1, int x2, int y2, int z2) {
        region = Region.of("world", x1, y1, z1, x2, y2, z2);
        blocks = new int[(int) region.volume()];
    }

    Scenes set(int x, int y, int z, String state) {
        String full = state.contains(":") ? state : "minecraft:" + state;
        int index = palette.indexOf(full);
        if (index < 0) {
            palette.add(full);
            index = palette.size() - 1;
        }
        blocks[region.index(x, y, z)] = index;
        return this;
    }

    Scenes fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    BlockGrid grid() {
        return new BlockGrid(region, palette, blocks.clone());
    }

    /** A 9x6x9 hut on grass at 0,64,0: stone-brick walls, glass windows, a door gap, an oak slab roof, a fence. */
    static BlockGrid hut() {
        Scenes s = new Scenes(0, 63, 0, 12, 70, 12);
        s.fill(0, 63, 0, 12, 63, 12, "grass_block");
        s.fill(2, 64, 2, 10, 67, 10, "stone_bricks");
        s.fill(3, 64, 3, 9, 67, 9, "air");
        s.fill(3, 63, 3, 9, 63, 9, "oak_planks");
        s.fill(5, 65, 2, 7, 66, 2, "glass");
        s.fill(2, 65, 5, 2, 66, 7, "glass");
        s.fill(6, 64, 10, 6, 65, 10, "air");
        s.set(6, 64, 10, "oak_door[facing=south,half=lower,hinge=left,open=false]");
        s.set(6, 65, 10, "oak_door[facing=south,half=upper,hinge=left,open=false]");
        s.fill(1, 68, 1, 11, 68, 11, "oak_slab[type=bottom]");
        s.fill(3, 68, 3, 9, 68, 9, "oak_planks");
        s.fill(5, 69, 5, 7, 69, 7, "oak_slab[type=bottom]");
        for (int x = 0; x <= 12; x++) {
            s.set(x, 64, 12, "oak_fence[east=true,west=true]");
        }
        s.set(11, 64, 6, "oak_stairs[facing=west,half=bottom]");
        s.set(0, 64, 0, "red_wool");
        s.set(4, 64, 11, "poppy");
        s.set(8, 64, 11, "torch");
        s.fill(11, 63, 0, 12, 63, 3, "water");
        return s.grid();
    }

    static BufferedImage decode(RenderResult result) {
        try {
            return ImageIO.read(new ByteArrayInputStream(result.png()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int red(int rgb) {
        return (rgb >> 16) & 0xff;
    }

    static int green(int rgb) {
        return (rgb >> 8) & 0xff;
    }

    static int blue(int rgb) {
        return rgb & 0xff;
    }
}
