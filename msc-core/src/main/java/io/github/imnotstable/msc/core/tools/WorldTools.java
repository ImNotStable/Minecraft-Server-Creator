package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.core.world.Schematic;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.renderer.Marker;
import io.github.imnotstable.msc.renderer.PackTextures;
import io.github.imnotstable.msc.renderer.RenderOptions;
import io.github.imnotstable.msc.renderer.RenderResult;
import io.github.imnotstable.msc.renderer.View;
import io.github.imnotstable.msc.renderer.WorldRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Seeing and building in the world. Coordinates are strings: "x,y,z" and "x1,y1,z1 x2,y2,z2". */
public final class WorldTools {
    private static final String WORLD = "World. Default: the main world";
    private static final String REGION = "Two corners, inclusive: \"x1,y1,z1 x2,y2,z2\"";

    /** Largest region one world_render reads, stitched from protocol-sized reads. */
    static final long MAX_RENDER_BLOCKS = 16_000_000L;

    private WorldTools() {
    }

    public static void register(ToolRegistry registry, CoreConfig config, WorldService world) {
        registry.add("world_slice",
                "See blocks as text: one grid per Y layer with a legend, in the structure text build_batch takes. "
                        + "Best up to about 32x32 per layer.",
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
        List<Path> textureRoots = new ArrayList<>(List.of(config.dataDir().resolve("pack")));
        // shortcut: read straight from the environment, not CoreConfig; move it there if more renderer settings appear.
        String clientJar = System.getenv("MSC_CLIENT_JAR");
        if (clientJar != null && !clientJar.isBlank()) {
            textureRoots.add(Path.of(clientJar));
        }
        WorldRenderer renderer = new WorldRenderer(new PackTextures(textureRoots));
        registry.addImage("world_render",
                "See a region as a small PNG image to check how a build looks. Views: iso (default), top, north, "
                        + "south, east, west, iso-ne/nw/sw/se, \"eye x,y,z look x,y,z\".",
                Schema.of().req("region", "string", REGION).opt("view", "string", "Camera. Default iso")
                        .opt("size", "integer", "Longest side in pixels, 64 to 1568. Default 512")
                        .opt("entities", "boolean", "Mark entities as numbered dots. Default true")
                        .opt("world", "string", WORLD),
                args -> {
                    View view;
                    RenderOptions options;
                    try {
                        view = View.parse(args.str("view", "iso"));
                        options = RenderOptions.defaults().withMaxSize(args.integer("size", RenderOptions.DEFAULT_SIZE));
                    } catch (IllegalArgumentException e) {
                        throw new ToolException(e.getMessage());
                    }
                    Region region = Coords.region(world.world(args.str("world", null)), args.str("region"));
                    List<Ops.EntitySummary> entities = args.bool("entities", true) ? new ArrayList<>() : null;
                    BlockGrid grid = world.readAll(region, MAX_RENDER_BLOCKS, "world_render", entities);
                    RenderResult r = renderer.render(grid, view, options, markers(entities));
                    Path dir = Files.createDirectories(config.dataDir().resolve("renders"));
                    Path file = Files.write(Files.createTempFile(dir, "render-", ".png"), r.png());
                    return new Tool.Output(r.caption() + "\nsaved: " + file, r.png(), r.estimatedTokens());
                });
        registry.add("world_summary",
                "Summarize a region in a few lines: block counts, non-air bounds, fill per layer, entities. Use before world_slice on unknown areas.",
                Schema.of().req("region", "string", REGION).opt("world", "string", WORLD),
                args -> world.summary(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        Budget.DEFAULT_CHARS));
        registry.add("world_entities", "List entities in a region: type, name, position, health, tags. Loaded chunks only.",
                Schema.of().req("region", "string", REGION).opt("type", "string", "Only this type, such as zombie")
                        .opt("limit", "integer", "Default 50").opt("ids", "boolean", "Add UUIDs")
                        .opt("world", "string", WORLD),
                args -> world.entities(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        args.str("type", null), args.integer("limit", 50), args.bool("ids", false), Budget.DEFAULT_CHARS));
        registry.add("build_undo", "Undo a build_batch edit.",
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
    }

    /** One marker per entity: brief type, then its name or text in quotes. */
    static List<Marker> markers(List<Ops.EntitySummary> entities) {
        if (entities == null) {
            return List.of();
        }
        List<Marker> out = new ArrayList<>();
        for (Ops.EntitySummary e : entities) {
            String name = e.name() == null ? "" : e.name().strip().replaceAll("\\s+", " ");
            if (name.length() > 40) {
                name = name.substring(0, 39) + "...";
            }
            out.add(new Marker(e.x(), e.y(), e.z(), BlockNames.brief(e.type()) + (name.isEmpty() ? "" : " \"" + name + "\"")));
        }
        return out;
    }
}
