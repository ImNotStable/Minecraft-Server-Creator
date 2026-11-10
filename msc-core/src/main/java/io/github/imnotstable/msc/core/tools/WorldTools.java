package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.core.world.Schematic;
import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Seeing and building in the world. Coordinates are strings: "x,y,z" and "x1,y1,z1 x2,y2,z2". */
public final class WorldTools {
    private static final String WORLD = "World name. Default: the server's main world";
    private static final String REGION = "Two corners, inclusive: \"x1,y1,z1 x2,y2,z2\"";
    private static final String FORMAT = "Text structure format: a line \"legend: #=stone g=glass\", then per layer a line "
            + "\"y=N\" followed by rows of symbols (rows = z, columns = x). \".\" is air, \"_\" leaves the block unchanged. "
            + "Shortcuts: \"y=N same as y=M\", \"y=N all #\".";

    private WorldTools() {
    }

    public static void register(ToolRegistry registry, CoreConfig config, WorldService world) {
        registry.add("world_slice",
                "See blocks as text: one character grid per Y layer with a legend, in the text structure format "
                        + "build_structure accepts. Best for regions up to about 32x32 per layer.",
                Schema.of().req("region", "string", REGION).opt("world", "string", WORLD)
                        .opt("states", "boolean", "Show block properties such as facing. Default false")
                        .opt("maxChars", "integer", "Result size cap. Default 6000"),
                args -> world.slice(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        args.bool("states", false), Budget.of(args)));
        registry.add("world_heightmap", "Surface height per column over an area, as a compact grid.",
                Schema.of().req("area", "string", "Two corners: \"x1,z1 x2,z2\"").opt("world", "string", WORLD)
                        .opt("maxChars", "integer", "Result size cap. Default 6000"),
                args -> world.heightmap(Coords.area(world.world(args.str("world", null)), args.str("area")),
                        Budget.of(args)));
        registry.add("world_summary",
                "Summarize a region in a few lines: block counts, non-air bounds, fill per layer, entities. Use before world_slice on unknown areas.",
                Schema.of().req("region", "string", REGION).opt("world", "string", WORLD),
                args -> world.summary(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        Budget.DEFAULT_CHARS));
        registry.add("build_fill", "Fill a region with one block. Returns an edit id for build_undo.",
                Schema.of().req("region", "string", REGION)
                        .req("block", "string", "Block state, for example stone or oak_stairs[facing=north]")
                        .opt("replace", "strings", "Only replace these blocks")
                        .opt("world", "string", WORLD),
                args -> world.fill(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        args.str("block"), args.strings("replace")));
        registry.add("build_set", "Set individual blocks.",
                Schema.of().req("blocks", "strings", "Entries \"x,y,z block\", for example \"10,64,5 oak_door[half=lower]\"")
                        .opt("world", "string", WORLD)
                        .opt("physics", "boolean", "Apply block updates. Default false"),
                args -> {
                    List<Ops.BlockChange> changes = new ArrayList<>();
                    for (String entry : args.strings("blocks")) {
                        String e = entry.trim();
                        int space = e.lastIndexOf(' ');
                        if (space < 0) {
                            throw new ToolException("bad entry \"" + entry + "\"; use \"x,y,z block\"");
                        }
                        BlockPos p = Coords.pos(e.substring(0, space));
                        changes.add(new Ops.BlockChange(p.x(), p.y(), p.z(), BlockNames.full(e.substring(space + 1))));
                    }
                    if (changes.isEmpty()) {
                        throw new ToolException("blocks is empty");
                    }
                    return world.set(world.world(args.str("world", null)), changes, args.bool("physics", false));
                });
        registry.add("build_structure", "Build from text, layer by layer. " + FORMAT,
                Schema.of().req("at", "string", "Where the structure's lowest corner goes: \"x,y,z\"")
                        .req("structure", "string", "The structure text")
                        .opt("world", "string", WORLD)
                        .opt("physics", "boolean", "Apply block updates. Default false"),
                args -> world.write(TextGrid.parse(args.str("structure"), world.world(args.str("world", null)),
                        Coords.pos(args.str("at"))), args.bool("physics", false)));
        registry.add("build_copy", "Copy a region to another place.",
                Schema.of().req("region", "string", REGION)
                        .req("to", "string", "Where the copy's lowest corner goes: \"x,y,z\"")
                        .opt("air", "boolean", "Copy air too. Default true")
                        .opt("world", "string", WORLD),
                args -> world.copy(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        Coords.pos(args.str("to")), args.bool("air", true)));
        registry.add("build_undo", "Undo an edit made by a build or structure tool.",
                Schema.of().opt("edit", "string", "Edit id such as e3. Default: the most recent edit"),
                args -> world.undo(args.str("edit", null)));
        registry.add("structure_save", "Save a region as a Sponge schematic (.schem) file, blocks only.",
                Schema.of().req("region", "string", REGION)
                        .req("file", "string", "Path relative to the workspace, for example structures/house.schem")
                        .opt("world", "string", WORLD),
                args -> {
                    Region region = Coords.region(world.world(args.str("world", null)), args.str("region"));
                    Path file = config.resolve(args.str("file"));
                    Schematic.save(world.read(region), file);
                    return "saved " + region.sizeX() + "x" + region.sizeY() + "x" + region.sizeZ() + " to " + file
                            + " (" + Files.size(file) + " bytes)";
                });
        registry.add("structure_load", "Place a Sponge schematic (.schem) file in the world, blocks only.",
                Schema.of().req("file", "string", "Path relative to the workspace")
                        .req("at", "string", "Where the schematic's lowest corner goes: \"x,y,z\"")
                        .opt("world", "string", WORLD)
                        .opt("air", "boolean", "Place air too. Default true"),
                args -> {
                    Path file = config.resolve(args.str("file"));
                    if (!Files.isRegularFile(file)) {
                        throw new ToolException("no file " + file);
                    }
                    BlockGrid grid = Schematic.load(file, world.world(args.str("world", null)), Coords.pos(args.str("at")));
                    if (!args.bool("air", true)) {
                        int[] blocks = grid.blocks().clone();
                        for (int i = 0; i < blocks.length; i++) {
                            if (blocks[i] >= 0 && BlockNames.isAir(grid.palette().get(blocks[i]))) {
                                blocks[i] = -1;
                            }
                        }
                        grid = new BlockGrid(grid.region(), grid.palette(), blocks);
                    }
                    return world.write(grid, false);
                });
    }
}
