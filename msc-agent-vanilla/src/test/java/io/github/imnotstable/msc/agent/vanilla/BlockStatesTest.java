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

    @Test
    void rejectsTextAfterTheState() {
        assertThatThrownBy(() -> BlockStates.parse("minecraft:chest[facing=east]{Items:[]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{Items:[]}");
        assertThatThrownBy(() -> BlockStates.parse("oak_sign{front_text:{}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{front_text:{}}");
        assertThatThrownBy(() -> BlockStates.parse("oak_stairs[facing=east][half=top]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[half=top]");
        assertThatThrownBy(() -> BlockStates.parse("minecraft:oak_stairs [facing=east]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(" [facing=east]");
    }

    @Test
    void tellsAnUnknownBlockWithCloseNamesFromBadProperties() {
        assertThatThrownBy(() -> BlockStates.parse("chain"))
                .hasMessageStartingWith("unknown block state: chain; did you mean ").hasMessageContaining("iron_chain");
        assertThatThrownBy(() -> BlockStates.parse("oak_stairs[facing=up]"))
                .hasMessageStartingWith("bad block properties in oak_stairs[facing=up]: ").hasMessageContaining("up");
    }
}
