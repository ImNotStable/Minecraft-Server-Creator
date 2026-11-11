package io.github.imnotstable.msc.agent.fabric;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Block state strings ({@code minecraft:oak_stairs[facing=east]}) to states and back. */
final class BlockStates {
    private BlockStates() {
    }

    /** @throws IllegalArgumentException for an unknown block, property or value */
    static BlockState parse(String state) {
        return parseResult(state).blockState();
    }

    /**
     * A filter for {@code onlyReplace}: matches states of the same block whose properties equal the
     * ones written in {@code state}. {@code minecraft:stone} matches any stone; Paper's
     * {@code BlockData#matches} works the same way.
     */
    static Filter filter(String state) {
        BlockStateParser.BlockResult result = parseResult(state);
        return new Filter(result.blockState(), Map.copyOf(result.properties()));
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

    record Filter(BlockState example, Map<Property<?>, Comparable<?>> properties) {
        boolean matches(BlockState state) {
            if (!state.is(example.getBlock())) {
                return false;
            }
            for (Map.Entry<Property<?>, Comparable<?>> e : properties.entrySet()) {
                if (!state.getValue(e.getKey()).equals(e.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }
}
