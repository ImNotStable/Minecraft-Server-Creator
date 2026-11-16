package io.github.imnotstable.msc.agent.vanilla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Block entity data as SNBT on the mod loaders (ADR 0042): checked before an edit, merged, read back. */
class BlockDataTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void dataIsCheckedAgainstTheBlockAndLosesItsIdAndPosition() {
        CompoundTag tag = BlockStates.parseData(Blocks.CHEST.defaultBlockState(), "{id:\"minecraft:chest\",x:5,Lock:{}}");
        assertThat(tag.keySet()).containsExactly("Lock");
        assertThatThrownBy(() -> BlockStates.parseData(Blocks.STONE.defaultBlockState(), "{a:1}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has no block entity data");
        assertThatThrownBy(() -> BlockStates.parseData(Blocks.CHEST.defaultBlockState(), "{Items:["))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("bad block entity data");
    }

    /** A sign, since items need loaded data packs that a unit test does not have. */
    @Test
    void mergedDataIsInTheBlockEntityAndReadsBackAsSnbtThatWritesTheSame() {
        BlockState state = Blocks.OAK_SIGN.defaultBlockState();
        SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, state);
        VanillaLevel.merge(sign, BlockStates.parseData(state, "{is_waxed:1b}"), registries);
        VanillaLevel.merge(sign, BlockStates.parseData(state, "{front_text:{messages:[\"Shop\",\"\",\"\",\"\"]}}"),
                registries);

        assertThat(sign.isWaxed()).as("a merge keeps what it does not name").isTrue();
        assertThat(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString()).isEqualTo("Shop");
        String read = VanillaLevel.data(sign, registries);
        assertThat(read).contains("Shop").doesNotContain("id:", "x:");
        SignBlockEntity copy = new SignBlockEntity(BlockPos.ZERO, state);
        VanillaLevel.merge(copy, BlockStates.parseData(state, read), registries);
        assertThat(VanillaLevel.data(copy, registries)).isEqualTo(read);
    }
}
