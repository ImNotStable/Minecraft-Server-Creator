package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.datapack.Datapack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Loads a {@link Datapack} (recipes, loot tables, tags, functions) into the running world: writes it to the world's
 * {@code datapacks} folder and reloads, so new recipes show in the recipe book at once. Call it once the server has
 * started, for example from a server-started hook or a first command.
 *
 * <pre>{@code
 * Recipes.load(Datapack.of("mymod", "My mod's recipes").shapeless("dust", "minecraft:redstone", 9,
 *         List.of("minecraft:redstone_block")));
 * }</pre>
 */
public final class Recipes {
    private Recipes() {}

    public static void load(Datapack pack) {
        MinecraftServer server = Hooks.server();
        try {
            pack.write(server.getWorldPath(LevelResource.DATAPACK_DIR));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write data pack " + pack.namespace(), e);
        }
        // /reload finds the new pack, enables it and reloads every data pack.
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "reload");
    }
}
