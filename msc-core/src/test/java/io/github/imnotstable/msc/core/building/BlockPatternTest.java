package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockPatternTest {
    @Test
    void parsesWeightsNamespacesAndPropertiesWithCommas() {
        BlockPattern p = BlockPattern.parse("grass_block:78,minecraft:moss_block:10,oak_stairs[facing=north,half=top]:2,mymod:ore");
        assertThat(p.states()).containsExactly("minecraft:grass_block", "minecraft:moss_block",
                "minecraft:oak_stairs[facing=north,half=top]", "mymod:ore");
        assertThat(p.toString()).isEqualTo("grass_block:78,moss_block:10,oak_stairs[facing=north,half=top]:2,mymod:ore:1");
        assertThat(BlockPattern.parse("stone").at(1, 2, 3)).isEqualTo("minecraft:stone");
        assertThat(BlockPattern.isMix("stone")).isFalse();
        assertThat(BlockPattern.isMix("minecraft:stone")).isFalse();
        assertThat(BlockPattern.isMix("oak_stairs[facing=north,half=top]")).isFalse();
        assertThat(BlockPattern.isMix("stone,dirt")).isTrue();
    }

    @Test
    void picksByWeightAndTheSameWayEveryTime() {
        BlockPattern p = BlockPattern.parse("grass_block:3,moss_block:1");
        Map<String, Integer> counts = new HashMap<>();
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                counts.merge(p.at(x, 64, z), 1, Integer::sum);
                assertThat(p.at(x, 64, z)).isEqualTo(BlockPattern.parse("grass_block:3,moss_block:1").at(x, 64, z));
            }
        }
        assertThat(counts.get("minecraft:moss_block")).isBetween(2200, 2800);
        long differ = 0;
        BlockPattern other = p.withSeed(7);
        for (int x = 0; x < 100; x++) {
            differ += p.at(x, 64, 0).equals(other.at(x, 64, 0)) ? 0 : 1;
        }
        assertThat(differ).isPositive();
    }

    @Test
    void rejectsEmptyPatterns() {
        assertThatThrownBy(() -> BlockPattern.parse("stone:0")).isInstanceOf(ToolException.class).hasMessageContaining("above 0");
        assertThatThrownBy(() -> BlockPattern.parse("stone,,dirt")).hasMessageContaining("empty entry");
    }
}
