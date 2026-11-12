package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
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
}
