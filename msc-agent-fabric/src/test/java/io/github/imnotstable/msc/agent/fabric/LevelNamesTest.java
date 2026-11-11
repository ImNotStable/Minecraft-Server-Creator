package io.github.imnotstable.msc.agent.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LevelNamesTest {
    @Test
    void vanillaDimensionsGetPaperStyleNames() {
        assertThat(LevelNames.name("world", "minecraft:overworld")).isEqualTo("world");
        assertThat(LevelNames.name("world", "minecraft:the_nether")).isEqualTo("world_nether");
        assertThat(LevelNames.name("lobby", "minecraft:the_end")).isEqualTo("lobby_the_end");
        assertThat(LevelNames.name("world", "mymod:mining")).isEqualTo("mymod:mining");
    }

    @Test
    void aWorldIsFoundByNameOrByDimensionId() {
        assertThat(LevelNames.matches("world_nether", "world", "minecraft:the_nether")).isTrue();
        assertThat(LevelNames.matches("minecraft:the_nether", "world", "minecraft:the_nether")).isTrue();
        assertThat(LevelNames.matches("world", "world", "minecraft:the_nether")).isFalse();
    }
}
