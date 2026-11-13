package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DiffTest {
    private static BlockGrid grid(String rows) {
        return TextGrid.parse("legend: #=stone d=dirt s=oak_stairs[facing=east] t=oak_stairs[facing=west]\ny=0\n" + rows,
                "world", new BlockPos(10, 64, 10));
    }

    @Test
    void countsKindsAndListsPositions() {
        assertThat(Diff.compare(grid("##\n##"), grid("##\n##"), "a.schem")).isEqualTo("no change since a.schem");
        assertThat(Diff.compare(grid("#s\n##"), grid(".t\n#d"), "a.schem")).isEqualTo("""
                3 blocks changed since a.schem, in world 10,64,10 11,64,11
                changes: stone -> air 1, oak_stairs turned or changed state 1, stone -> dirt 1
                10,64,10 stone -> air
                11,64,10 oak_stairs[facing=east] -> oak_stairs[facing=west]
                11,64,11 stone -> dirt""");
    }

    @Test
    void comparesOnlyWhereTheyOverlapAndCapsTheList() {
        String row = "#".repeat(20) + "\n";
        String changed = ".".repeat(20) + "\n";
        String result = Diff.compare(grid(row + row), grid(changed), "big.schem");
        assertThat(result).startsWith("note: big.schem is 20x1x2, the region 20x1x1; compared where they overlap\n"
                + "20 blocks changed").endsWith("[8 more]");
    }

    @Test
    void aGridComparedWithItselfIsUnchangedAndOneChangedCellIsFoundOnAnyBorder() {
        for (long seed = 0; seed < 300; seed++) {
            Random rnd = new Random(seed);
            BlockGrid g = RandomGrids.grid(rnd, 6, true);
            assertThat(Diff.compare(g, g, "s")).as("seed %d", seed).isEqualTo("no change since s");
            // The same blocks under another palette (spelled briefly, in reverse order) are no change either.
            java.util.List<String> reversed = new java.util.ArrayList<>(g.palette().stream().map(BlockNames::brief).toList());
            java.util.Collections.reverse(reversed);
            int[] remapped = Arrays.stream(g.blocks()).map(b -> b < 0 ? b : g.palette().size() - 1 - b).toArray();
            assertThat(Diff.compare(g, new BlockGrid(g.region(), reversed, remapped), "s")).as("seed %d", seed)
                    .isEqualTo("no change since s");
            // One cell, often a corner, set to a block the grid does not hold.
            var r = g.region();
            int x = rnd.nextBoolean() ? r.min().x() + rnd.nextInt(r.sizeX()) : rnd.nextBoolean() ? r.min().x() : r.max().x();
            int y = rnd.nextBoolean() ? r.min().y() + rnd.nextInt(r.sizeY()) : rnd.nextBoolean() ? r.min().y() : r.max().y();
            int z = rnd.nextBoolean() ? r.min().z() + rnd.nextInt(r.sizeZ()) : rnd.nextBoolean() ? r.min().z() : r.max().z();
            java.util.List<String> palette = new java.util.ArrayList<>(g.palette());
            palette.add("minecraft:emerald_block");
            int[] blocks = g.blocks().clone();
            blocks[r.index(x, y, z)] = palette.size() - 1;
            String at = x + "," + y + "," + z;
            String result = Diff.compare(g, new BlockGrid(r, palette, blocks), "s");
            if (g.stateAt(x, y, z) == null) {
                // A "leave unchanged" cell in the snapshot says nothing about what was there.
                assertThat(result).as("seed %d", seed).isEqualTo("no change since s");
            } else {
                assertThat(result).as("seed %d", seed).startsWith("1 block changed since s, in world " + at + " " + at + "\n")
                        .contains("\n" + at + " ");
            }
        }
    }
}
