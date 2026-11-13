package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}
