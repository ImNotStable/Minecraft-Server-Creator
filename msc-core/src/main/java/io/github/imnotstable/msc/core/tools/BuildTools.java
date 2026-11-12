package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.building.BuildScript;
import io.github.imnotstable.msc.core.building.Builder;
import io.github.imnotstable.msc.core.building.Step;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.Schematic;
import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shapes, stamps and batches: many blocks per call, one undo id per call, every block name checked first. */
public final class BuildTools {
    private static final String WORLD = "World name. Default: the server's main world";
    private static final String DRY_RUN = "Only check names and count blocks. Default false";
    private static final String PHYSICS = "Apply block updates. Default false";

    private BuildTools() {
    }

    public static void register(ToolRegistry registry, CoreConfig config, WorldService world, Builder builder) {
        BuildScript.StructureFiles files = (path, worldName) -> load(config, path, worldName);
        registry.add("build_batch",
                "Build many steps as one edit with one undo id: fills and rooms, blocks, round shapes, lines, terrain "
                        + "bands, and structures stamped, turned or repeated. Checks every block name before placing "
                        + "any. Use it instead of many build_fill or build_set calls or a generator script.\n"
                        + BuildScript.HELP,
                Schema.of().req("steps", "string", "The steps, one per line")
                        .opt("world", "string", WORLD).opt("physics", "boolean", PHYSICS)
                        .opt("dryRun", "boolean", DRY_RUN),
                args -> {
                    String w = world.world(args.str("world", null));
                    List<Step> steps = new BuildScript(w, files).parse(args.str("steps"));
                    return builder.run(w, steps, args.bool("physics", false), args.bool("dryRun", false), "build_batch");
                });
    }

    /** A structure file from the workspace: a Sponge schematic, or anything else as text structure. */
    private static BlockGrid load(CoreConfig config, String path, String world) throws java.io.IOException {
        Path file = config.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new ToolException("no file " + file);
        }
        BlockPos origin = new BlockPos(0, 0, 0);
        return path.endsWith(".schem") ? Schematic.load(file, world, origin)
                : TextGrid.parse(Files.readString(file), world, origin);
    }
}
