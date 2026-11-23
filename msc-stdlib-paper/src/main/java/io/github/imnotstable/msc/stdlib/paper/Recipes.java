package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.datapack.Datapack;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Loads a {@link Datapack} (recipes, loot tables, tags, functions) into the server: writes it to the main world's
 * {@code datapacks} folder and reloads the server's data, so the recipes work at once and survive restarts. For a
 * recipe that must react to code (a custom result per player), Bukkit's own {@code Bukkit.addRecipe} still works.
 *
 * <pre>{@code
 * Recipes.load(Datapack.of("myplugin", "My plugin's recipes")
 *         .shaped("ruby_block", "minecraft:redstone_block", 1, List.of("RR", "RR"), Map.of('R', "minecraft:redstone")));
 * }</pre>
 */
public final class Recipes {
    private Recipes() {}

    public static void load(Datapack pack) {
        World main = Bukkit.getWorlds().getFirst();
        try {
            pack.write(main.getWorldFolder().toPath().resolve("datapacks"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write data pack " + pack.namespace(), e);
        }
        Bukkit.reloadData();
    }
}
