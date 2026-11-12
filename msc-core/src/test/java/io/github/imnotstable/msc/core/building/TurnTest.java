package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
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
}
