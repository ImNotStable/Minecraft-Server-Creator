package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.Dimensions;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** {@code world_create}: generated worlds beside the flat dev world, as a data pack (ADR 0075). */
final class WorldCreate {
    private WorldCreate() {
    }

    static String run(CoreConfig config, ServerManager server, Args args) throws IOException {
        Platform platform = server.platform();
        // It only writes files, so it is not a world tool that waits for the server; a proxy has no world to add to.
        if (platform.noWorldTools() != null) {
            throw new ToolException(platform.noWorldTools());
        }
        if (platform == Platform.MINESTOM) {
            throw new ToolException("Minestom has no game world generator: set a generator on the instance in the "
                    + "project's code (InstanceContainer.setGenerator), or shape land with build_batch terrain");
        }
        Path worldDir = config.serverDir().resolve(levelName(config.serverDir()));
        String name = args.str("name", null);
        if (name == null) {
            List<String> worlds = Dimensions.list(worldDir);
            return worlds.isEmpty() ? "no worlds made with world_create yet" : "worlds: " + String.join(", ", worlds);
        }
        if (args.bool("remove", false)) {
            Dimensions.Spec probe = new Dimensions.Spec(name, "normal", List.of(), List.of()); // checks the name
            return Dimensions.remove(worldDir, name)
                    ? "removed " + probe.id() + " from the pack; the server drops it on its next start (its saved "
                    + "chunks stay in the world folder)"
                    : "no world " + probe.id() + " in " + Dimensions.pack(worldDir);
        }
        Dimensions.Spec spec = new Dimensions.Spec(name, args.str("terrain", "normal"), args.strings("biomes"),
                args.strings("layers"));
        Path file = Dimensions.write(worldDir, spec);
        String biomes = spec.biomes().isEmpty() ? "the terrain's own biomes" : String.join(", ", spec.biomes());
        return "wrote " + file + ": " + spec.id() + ", " + spec.terrain() + " terrain, " + biomes + ".\n"
                + "It loads on the next server start (" + (server.running() ? "server_stop, then server_start"
                : "server_start") + "). Then build there with world=" + spec.id() + "; world_heightmap finds the "
                + "ground, and server_command \"execute in " + spec.id() + " run tp <player> 0 120 0\" takes a player "
                + "there. If the start reports a data pack error, fix the biome or block name and call again, or "
                + "remove=true.";
    }

    /** The main world's folder name, from {@code server.properties}; {@code world} when unset. */
    static String levelName(Path serverDir) throws IOException {
        Path props = serverDir.resolve(ServerPropertiesFile.NAME);
        Map<String, String> values = Files.isRegularFile(props) ? ServerPropertiesFile.read(props) : Map.of();
        String name = values.getOrDefault("level-name", "").trim();
        return name.isEmpty() ? "world" : name;
    }
}
