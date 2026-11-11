package io.github.imnotstable.msc.agent.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockStatesTest {
    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
    }

    @Test
    void parsesAndSerializesFullStates() {
        BlockState stairs = BlockStates.parse("minecraft:oak_stairs[facing=east,half=top]");

        assertThat(stairs.is(Blocks.OAK_STAIRS)).isTrue();
        assertThat(BlockStates.serialize(stairs)).startsWith("minecraft:oak_stairs[").contains("facing=east", "half=top");
        assertThat(BlockStates.parse(BlockStates.serialize(stairs))).isSameAs(stairs);
        assertThat(BlockStates.serialize(BlockStates.parse("stone"))).isEqualTo("minecraft:stone");
    }

    @Test
    void filtersCompareOnlyTheWrittenProperties() {
        BlockStates.Filter anyStairs = BlockStates.filter("minecraft:oak_stairs");
        BlockStates.Filter eastStairs = BlockStates.filter("minecraft:oak_stairs[facing=east]");
        BlockState eastTop = BlockStates.parse("minecraft:oak_stairs[facing=east,half=top]");
        BlockState west = BlockStates.parse("minecraft:oak_stairs[facing=west]");

        assertThat(anyStairs.matches(eastTop)).isTrue();
        assertThat(anyStairs.matches(west)).isTrue();
        assertThat(eastStairs.matches(eastTop)).isTrue();
        assertThat(eastStairs.matches(west)).isFalse();
        assertThat(anyStairs.matches(Blocks.STONE.defaultBlockState())).isFalse();
    }

    @Test
    void rejectsUnknownBlocksAndProperties() {
        assertThatThrownBy(() -> BlockStates.parse("minecraft:not_a_block"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
        assertThatThrownBy(() -> BlockStates.parse("minecraft:stone[facing=up]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
