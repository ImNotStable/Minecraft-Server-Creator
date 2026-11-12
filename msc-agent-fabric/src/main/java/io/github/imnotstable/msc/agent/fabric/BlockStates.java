package io.github.imnotstable.msc.agent.fabric;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Block state strings ({@code minecraft:oak_stairs[facing=east]}) to states and back. */
final class BlockStates {
    private BlockStates() {
    }

    /** @throws IllegalArgumentException for an unknown block, property or value */
    static BlockState parse(String state) {
        return parseResult(state).blockState();
    }

    /** The full state string, every property included, as {@code /setblock} accepts it. */
    static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    private static BlockStateParser.BlockResult parseResult(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("unknown block state: " + state + " (" + e.getMessage() + ")", e);
        }
    }
}
