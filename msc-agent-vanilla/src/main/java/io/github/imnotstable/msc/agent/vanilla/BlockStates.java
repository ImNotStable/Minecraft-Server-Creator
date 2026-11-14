package io.github.imnotstable.msc.agent.vanilla;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.imnotstable.msc.agent.common.BlockNames;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Block state strings ({@code minecraft:oak_stairs[facing=east]}) to states and back. */
final class BlockStates {
    private BlockStates() {
    }

    /**
     * @throws IllegalArgumentException for an unknown block (with close ids), or a bad property or value on a
     *     real block (with Brigadier's reason), or text after the state such as NBT or a second {@code [..]}
     */
    static BlockState parse(String state) {
        return parseResult(state).blockState();
    }

    /** The full state string, every property included, as {@code /setblock} accepts it. */
    static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    private static BlockStateParser.BlockResult parseResult(String state) {
        StringReader reader = new StringReader(state);
        BlockStateParser.BlockResult result;
        try {
            result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(explain(state, e), e);
        }
        if (reader.canRead()) { // Paper and Minestom reject trailing NBT or a second [..]; never drop it silently
            throw new IllegalArgumentException(
                    "unexpected text after block state " + state + ": " + reader.getRemaining());
        }
        return result;
    }

    /** Why {@code state} failed: bad properties on a real block, or an unknown id with close names. */
    private static String explain(String state, CommandSyntaxException cause) {
        try {
            String id = (state.contains("[") ? state.substring(0, state.indexOf('[')) : state).strip()
                    .toLowerCase(Locale.ROOT);
            String full = id.contains(":") ? id : "minecraft:" + id;
            List<String> known = BuiltInRegistries.BLOCK.keySet().stream()
                    .map(k -> "minecraft".equals(k.getNamespace()) ? k.getPath() : k.toString()).toList();
            String bare = full.startsWith("minecraft:") ? full.substring("minecraft:".length()) : full;
            if (known.contains(bare)) {
                return "bad block properties in " + state + ": " + cause.getMessage();
            }
            List<String> close = BlockNames.suggest(bare, known);
            return "unknown block state: " + state
                    + (close.isEmpty() ? "" : "; did you mean " + String.join(", ", close) + "?");
        } catch (RuntimeException e) {
            return "unknown block state: " + state + " (" + cause.getMessage() + ")"; // never hide the real error
        }
    }
}
