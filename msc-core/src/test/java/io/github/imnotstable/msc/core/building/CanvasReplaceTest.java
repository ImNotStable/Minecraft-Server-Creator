package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CanvasReplaceTest {
    private static final String STAIRS = "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]";

    @Test
    void entryWithSomePropertiesMatchesStatesThatHaveThem() {
        assertThat(Canvas.matches(STAIRS, List.of("minecraft:oak_stairs[half=top]"))).isTrue();
        assertThat(Canvas.matches(STAIRS, List.of("minecraft:oak_stairs[shape=straight,facing=east]"))).isTrue();
        assertThat(Canvas.matches(STAIRS, List.of("minecraft:oak_stairs[half=bottom]"))).isFalse();
        assertThat(Canvas.matches(STAIRS, List.of("minecraft:stone_stairs[half=top]"))).isFalse();
    }

    @Test
    void entryWithoutPropertiesMatchesAnyStateOfTheBlock() {
        assertThat(Canvas.matches(STAIRS, List.of("minecraft:oak_stairs"))).isTrue();
        assertThat(Canvas.matches(STAIRS, List.of(STAIRS))).isTrue();
        assertThat(Canvas.matches("minecraft:cave_air", List.of("minecraft:air"))).isTrue();
    }
}
