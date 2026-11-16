package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TurnTest {
    @Test
    void turnsBlockStatesClockwise() {
        assertThat(Turn.state("minecraft:oak_stairs[facing=north,half=top]", 1)).isEqualTo("minecraft:oak_stairs[facing=east,half=top]");
        assertThat(Turn.state("minecraft:oak_stairs[facing=north]", 3)).isEqualTo("minecraft:oak_stairs[facing=west]");
        assertThat(Turn.state("minecraft:hopper[facing=down]", 1)).isEqualTo("minecraft:hopper[facing=down]");
        assertThat(Turn.state("minecraft:oak_log[axis=x]", 1)).isEqualTo("minecraft:oak_log[axis=z]");
        assertThat(Turn.state("minecraft:oak_log[axis=y]", 1)).isEqualTo("minecraft:oak_log[axis=y]");
        assertThat(Turn.state("minecraft:oak_log[axis=x]", 2)).isEqualTo("minecraft:oak_log[axis=x]");
        assertThat(Turn.state("minecraft:oak_sign[rotation=14]", 1)).isEqualTo("minecraft:oak_sign[rotation=2]");
        assertThat(Turn.state("minecraft:rail[shape=north_east]", 1)).isEqualTo("minecraft:rail[shape=south_east]");
        assertThat(Turn.state("minecraft:rail[shape=north_south]", 1)).isEqualTo("minecraft:rail[shape=east_west]");
        assertThat(Turn.state("minecraft:rail[shape=ascending_north]", 1)).isEqualTo("minecraft:rail[shape=ascending_east]");
        assertThat(Turn.state("minecraft:oak_fence[east=false,north=true,south=false,west=false]", 1))
                .isEqualTo("minecraft:oak_fence[north=false,east=true,south=false,west=false]");
        assertThat(Turn.state("minecraft:oak_stairs[facing=north,shape=inner_left]", 1))
                .isEqualTo("minecraft:oak_stairs[facing=east,shape=inner_left]");
        assertThat(Turn.state("minecraft:stone", 1)).isEqualTo("minecraft:stone");
    }

    @Test
    void turnsAGridSoNorthBecomesEast() {
        // 3 wide (x), 2 deep (z): a stone row on the north side.
        BlockGrid g = TextGrid.parse("legend: #=stone d=dirt\ny=0\n###\nddd", "world", new BlockPos(0, 0, 0));
        BlockGrid t = Turn.grid(g, 1);
        assertThat(t.region().sizeX()).isEqualTo(2);
        assertThat(t.region().sizeZ()).isEqualTo(3);
        for (int z = 0; z < 3; z++) {
            assertThat(t.stateAt(1, 0, z)).isEqualTo("minecraft:stone");
            assertThat(t.stateAt(0, 0, z)).isEqualTo("minecraft:dirt");
        }
        BlockGrid back = Turn.grid(Turn.grid(t, 2), 1);
        assertThat(back.blocks()).isEqualTo(g.blocks());
    }

    @Test
    void facesATarget() {
        assertThat(Turn.facing(0, 10, 0, 0)).isZero();
        assertThat(Turn.facing(-10, 0, 0, 0)).isEqualTo(1);
        assertThat(Turn.facing(0, -10, 0, 0)).isEqualTo(2);
        assertThat(Turn.facing(10, 0, 0, 0)).isEqualTo(3);
    }

    private static final List<String> STATES = List.of("minecraft:stone", "minecraft:oak_stairs[facing=north,half=top]",
            "minecraft:oak_log[axis=x]", "minecraft:oak_log[axis=y]", "minecraft:oak_sign[rotation=7]",
            "minecraft:rail[shape=north_east]", "minecraft:rail[shape=ascending_west]", "minecraft:rail[shape=north_south]",
            "minecraft:oak_fence[east=true,north=false,south=true,west=false,waterlogged=false]",
            "minecraft:redstone_wire[east=side,north=up,power=3,south=none,west=none]",
            "minecraft:jigsaw[orientation=west_up]", "minecraft:vine[east=true,up=true]");

    @Test
    void turnsAddUpAndFourQuartersAreTheIdentity() {
        for (long seed = 0; seed < 200; seed++) {
            Random rnd = new Random(seed);
            int sx = 1 + rnd.nextInt(6);
            int sy = 1 + rnd.nextInt(3);
            int sz = 1 + rnd.nextInt(6);
            int[] blocks = new int[sx * sy * sz];
            Arrays.setAll(blocks, i -> rnd.nextInt(10) == 0 ? -1 : rnd.nextInt(STATES.size()));
            BlockGrid g = new BlockGrid(io.github.imnotstable.msc.protocol.world.Region.of("w", 0, 0, 0, sx - 1, sy - 1, sz - 1),
                    STATES, blocks);
            int a = rnd.nextInt(4);
            int b = rnd.nextInt(4);
            BlockGrid twice = Turn.grid(Turn.grid(g, a), b);
            BlockGrid once = Turn.grid(g, a + b);
            assertThat(twice.region()).as("seed %d", seed).isEqualTo(once.region());
            assertThat(cells(twice)).as("seed %d turns %d+%d", seed, a, b).isEqualTo(cells(once));
            BlockGrid back = Turn.grid(Turn.grid(g, a), 4 - a);
            assertThat(back.region()).as("seed %d", seed).isEqualTo(g.region());
            assertThat(cells(back)).as("seed %d", seed).isEqualTo(cells(g));
        }
    }

    /** Each cell's id and set of properties, so the same state with its properties in another order is equal. */
    private static List<Object> cells(BlockGrid g) {
        return Arrays.stream(g.blocks()).mapToObj(b -> b < 0 ? (Object) "unchanged" : normal(g.palette().get(b))).toList();
    }

    private static Object normal(String state) {
        int open = state.indexOf('[');
        if (open < 0) {
            return state;
        }
        Set<String> props = new TreeSet<>(List.of(state.substring(open + 1, state.length() - 1).split(",")));
        return state.substring(0, open) + props;
    }
}
