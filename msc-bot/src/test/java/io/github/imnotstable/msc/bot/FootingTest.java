package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FootingTest {
    @Test
    void footingFollowsTheCollisionShape() {
        assertThat(Footing.of("minecraft:white_carpet")).isEqualTo(Footing.OPEN);
        assertThat(Footing.of("minecraft:crimson_fungus")).isEqualTo(Footing.OPEN);
        assertThat(Footing.of("minecraft:oak_stairs[half=bottom]")).isEqualTo(Footing.STEP);
        assertThat(Footing.of("minecraft:oak_stairs[half=top]")).isEqualTo(Footing.SOLID);
        assertThat(Footing.of("minecraft:candle")).isEqualTo(Footing.STEP);
        assertThat(Footing.of("minecraft:chest")).isEqualTo(Footing.SOLID);
        assertThat(Footing.of("minecraft:oak_door[open=false]")).isEqualTo(Footing.SOLID);
        assertThat(Footing.of("minecraft:oak_fence_gate[open=false]")).isEqualTo(Footing.TALL);
        assertThat(Footing.of("minecraft:melon_stem[age=7]")).isEqualTo(Footing.OPEN);
        assertThat(Footing.of("minecraft:tube_coral_wall_fan[facing=north]")).isEqualTo(Footing.OPEN);
        assertThat(Footing.of("minecraft:golden_dandelion")).isEqualTo(Footing.OPEN);
        assertThat(Footing.of("minecraft:repeater[facing=north]")).isEqualTo(Footing.STEP);
        assertThat(Footing.of("minecraft:potted_oak_sapling")).isEqualTo(Footing.STEP);
        assertThat(Footing.of("minecraft:player_head")).isEqualTo(Footing.STEP);
        assertThat(Footing.of("minecraft:piston_head[facing=up]")).isEqualTo(Footing.SOLID);
    }

    @Test
    void aLowFloorUnderHighWallsIsNoStep() {
        // A composter: a 2/16 floor and walls to the top. Only stairs-like shapes step up twice.
        assertThat(Footing.of(List.of(new Box(0, 0, 0, 1, 2 / 16.0, 1), new Box(0, 0, 0, 2 / 16.0, 1, 1))))
                .isEqualTo(Footing.SOLID);
    }
}
