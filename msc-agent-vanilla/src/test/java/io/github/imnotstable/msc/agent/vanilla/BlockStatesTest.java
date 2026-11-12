package io.github.imnotstable.msc.agent.vanilla;

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
    void rejectsUnknownBlocksAndProperties() {
        assertThatThrownBy(() -> BlockStates.parse("minecraft:not_a_block"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
        assertThatThrownBy(() -> BlockStates.parse("minecraft:stone[facing=up]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
