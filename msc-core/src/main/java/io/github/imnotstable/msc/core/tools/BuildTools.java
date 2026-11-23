package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.building.BuildScript;
import io.github.imnotstable.msc.core.building.Builder;
import io.github.imnotstable.msc.core.building.Step;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.StructureFile;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Shapes, stamps and batches: many blocks per call, one undo id per call, every block name checked first. */
public final class BuildTools {
    private static final String WORLD = "Default: main";
    private static final String DRY_RUN = "Only check names and count blocks";
    private static final String PHYSICS = "Apply block updates";

    private BuildTools() {
    }

    public static void register(ToolRegistry registry, CoreConfig config, WorldService world, Builder builder) {
        BuildScript.StructureFiles files = new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String worldName) throws java.io.IOException {
                return BuildTools.load(config, path, worldName);
            }

            @Override
            public BlockGrid read(Region region) throws InterruptedException {
                return world.readAllWithData(region, Builder.MAX_VOLUME, "build_batch");
            }

            @Override
            public Path file(String path) {
                Path file = config.inWorkspace().resolve("image file", path);
                if (!Files.isRegularFile(file)) {
                    throw new ToolException("no file " + file);
                }
                return file;
            }
        };
        registry.add("build_batch",
                "The one build tool. All steps are one edit with one undo id; every block name is checked "
                        + "before any is placed. Put a whole build in one call, not a generator script.\n" + BuildScript.HELP,
                Schema.of().req("steps", "string", "The steps, one per line")
                        .opt("world", "string", WORLD).opt("physics", "boolean", PHYSICS)
                        .opt("dryRun", "boolean", DRY_RUN),
                args -> {
                    boolean dryRun = args.bool("dryRun", false);
                    WayArgs.check(args, Set.of(dryRun ? "dryRun" : "write"), WayArgs.BUILD);
                    String w = world.world(args.str("world", null));
                    List<Step> steps = new BuildScript(w, files).parse(args.text("steps"));
                    return builder.run(w, steps, args.bool("physics", false), dryRun, "build_batch");
                });
    }

    /** A structure file from the workspace, read by {@link StructureFile}'s rule. */
    private static BlockGrid load(CoreConfig config, String path, String world) throws java.io.IOException {
        Path file = config.inWorkspace().resolve("structure file", path);
        if (!Files.isRegularFile(file)) {
            throw new ToolException("no file " + file);
        }
        BlockPos origin = new BlockPos(0, 0, 0);
        return StructureFile.load(file, world, origin);
    }
}
