package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

class BlockStatesTest {
    @Test
    void parsesVanillaStatesWithOrWithoutNamespace() {
        assertThat(BlockStates.parse("minecraft:stone")).isEqualTo(Block.STONE);
        assertThat(BlockStates.parse("oak_stairs[facing=north]").getProperty("facing")).isEqualTo("north");
        assertThat(BlockStates.format(Block.OAK_STAIRS.withProperty("facing", "east")))
                .startsWith("minecraft:oak_stairs[").contains("facing=east");
        assertThatThrownBy(() -> BlockStates.parse("minecraft:not_a_block"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
        assertThatThrownBy(() -> BlockStates.parse("minecraft:oak_stairs[colour=red]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
