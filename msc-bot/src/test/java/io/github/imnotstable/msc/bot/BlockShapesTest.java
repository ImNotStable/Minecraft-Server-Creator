package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlockShapesTest {
    @Test
    void shapesFollowTheBlockState() {
        assertThat(BlockShapes.of("minecraft:stone")).containsExactly(new Box(0, 0, 0, 1, 1, 1));
        assertThat(BlockShapes.of("minecraft:air")).isEmpty();
        assertThat(BlockShapes.of("minecraft:oak_pressure_plate[powered=false]")).isEmpty();
        assertThat(BlockShapes.of("minecraft:stone_slab[type=top,waterlogged=false]"))
                .containsExactly(new Box(0, 0.5, 0, 1, 1, 1));
        assertThat(BlockShapes.of("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"))
                .containsExactly(new Box(0, 0, 0, 1, 0.5, 1), new Box(0.5, 0.5, 0, 1, 1, 1));
        assertThat(BlockShapes.of("minecraft:oak_fence[east=false]")).containsExactly(new Box(0, 0, 0, 1, 1.5, 1));
        assertThat(BlockShapes.of("minecraft:oak_door[open=true]")).isEmpty();
        assertThat(BlockShapes.of("minecraft:snow[layers=3]")).containsExactly(new Box(0, 0, 0, 1, 0.25, 1));
        assertThat(BlockShapes.slipperiness("minecraft:blue_ice")).isEqualTo(0.989);
    }
}
