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
    }

    @Test
    void anUnknownIdGetsCloseNames() {
        assertThatThrownBy(() -> BlockStates.parse("minecraft:polished_diorite_wall"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("unknown block state: minecraft:polished_diorite_wall; did you mean ")
                .hasMessageContaining("polished_diorite");
        assertThatThrownBy(() -> BlockStates.parse("minecraft:glorpzz"))
                .hasMessage("unknown block state: minecraft:glorpzz");
    }

    @Test
    void badPropertiesOnARealBlockSayWhichPart() {
        assertThatThrownBy(() -> BlockStates.parse("minecraft:oak_stairs[facing=up]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("bad block properties in minecraft:oak_stairs[facing=up]: ")
                .hasMessageContaining("facing=up is not one of ").hasMessageContaining("north");
        assertThatThrownBy(() -> BlockStates.parse("stone[foo=bar]"))
                .hasMessage("bad block properties in stone[foo=bar]: stone has no properties");
        assertThatThrownBy(() -> BlockStates.parse("oak_stairs[colour=red]"))
                .hasMessageContaining("unknown property colour").hasMessageContaining("facing");
    }
}
