package io.github.imnotstable.msc.agent.vanilla;

import static org.assertj.core.api.Assertions.assertThat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What an undo record keeps of a block entity, so build_undo gives a sign its text back. */
class BlockEntityUndoTest {
    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
    }

    @Test
    void aSavedSignLoadsIntoTheNewBlockEntityWithItsText() {
        HolderLookup.Provider registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        SignBlockEntity old = sign();
        ListTag messages = new ListTag();
        for (String line : new String[] {"Shop", "", "", ""}) {
            messages.add(StringTag.valueOf(line));
        }
        CompoundTag front = new CompoundTag();
        front.put("messages", messages);
        CompoundTag written = new CompoundTag();
        written.put("front_text", front);
        VanillaLevel.load(old, written, registries);

        SignBlockEntity fresh = sign(); // what setBlock leaves after an undo puts the sign back
        VanillaLevel.load(fresh, VanillaLevel.save(old, registries), registries);

        assertThat(fresh.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString()).isEqualTo("Shop");
    }

    private static SignBlockEntity sign() {
        return new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
    }
}
