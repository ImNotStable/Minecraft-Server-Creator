package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.core.world.BuildReview;
import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.core.world.Diff;
import io.github.imnotstable.msc.core.world.Schematic;
import io.github.imnotstable.msc.core.world.StructureFile;
import io.github.imnotstable.msc.core.world.Walk;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.renderer.Marker;
import io.github.imnotstable.msc.renderer.PackTextures;
import io.github.imnotstable.msc.renderer.RenderOptions;
import io.github.imnotstable.msc.renderer.RenderResult;
import io.github.imnotstable.msc.renderer.View;
import io.github.imnotstable.msc.renderer.WorldRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Seeing and building in the world. Coordinates are strings: "x,y,z" and "x1,y1,z1 x2,y2,z2". */
public final class WorldTools {
    private static final String WORLD = "Default: main";
    private static final String REGION = "Corners, inclusive: \"x1,y1,z1 x2,y2,z2\"";

    /** Largest region one world_render reads, stitched from protocol-sized reads. */
    static final long MAX_RENDER_BLOCKS = 16_000_000L;
    /** Saved renders kept in {@code .msc/renders}: a fallback for clients that drop images, so only recent ones matter. */
    static final int KEPT_RENDERS = 20;

    static final String SAVE_RENDER = "save a copy of the image";
    static final String PRUNE_RENDERS = "delete old saved images";
    static final String SAVE_STRUCTURE = "write the schematic";

    private WorldTools() {
    }

    /** A new {@code render-*.png} in {@code dir}, written whole. */
    private static Path saveRender(Path dir, byte[] png) throws IOException {
        Path file = Files.createTempFile(Files.createDirectories(dir), "render-", ".png");
        try {
            return Files.write(file, png);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    public static void register(ToolRegistry registry, CoreConfig config, WorldService world, WorldBackups backups) {
        registry.add("world_slice",
                "See blocks as text: one grid per Y layer with a legend, in the structure text build_batch takes. "
                        + "Best up to 32x32 per layer.",
                Schema.of().req("region", "string", REGION).opt("world", "string", WORLD)
                        .opt("states", "boolean", "Show block properties such as facing")
                        .opt("data", "boolean", "Block entity data as SNBT")
                        .maxChars(),
                args -> world.slice(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        args.bool("states", false), args.bool("data", false), Budget.of(args)));
        registry.add("world_heightmap", "Surface height per column, as a compact grid.",
                Schema.of().req("area", "string", "Corners: \"x1,z1 x2,z2\"").opt("world", "string", WORLD)
                        .maxChars(),
                args -> world.heightmap(Coords.area(world.world(args.str("world", null)), args.str("area")),
                        Budget.of(args)));
        List<Path> textureRoots = new ArrayList<>(List.of(config.packDir()));
        // shortcut: read straight from the environment, not CoreConfig; move it there if more renderer settings appear.
        String clientJar = System.getenv("MSC_CLIENT_JAR");
        if (clientJar != null && !clientJar.isBlank()) {
            textureRoots.add(Path.of(clientJar));
        }
        WorldRenderer renderer = new WorldRenderer(new PackTextures(textureRoots));
        registry.addImage("world_render",
                "See how a region looks, as a small PNG. Views: iso, top, north, "
                        + "south, east, west, iso-ne/nw/sw/se, \"eye x,y,z look x,y,z\".",
                Schema.of().req("region", "string", REGION).opt("view", "string", "Camera. Default iso")
                        .range("size", RenderOptions.MIN_SIZE, RenderOptions.MAX_SIZE, "Longest side in pixels. Default " + RenderOptions.DEFAULT_SIZE)
                        .opt("entities", "boolean", "Number entities; show hologram and sign text. Default true")
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
                    Path dir = OutputFile.checkFolder(config.dataDir().resolve("renders"), "render folder");
                    boolean marked = args.bool("entities", true);
                    List<Ops.EntitySummary> entities = marked ? new ArrayList<>() : null;
                    List<Ops.SignText> signs = marked ? new ArrayList<>() : null;
                    BlockGrid grid = world.readAll(region, MAX_RENDER_BLOCKS, "world_render", entities, signs);
                    RenderResult r = renderer.render(grid, view, options, markers(entities, signs));
                    // The image is the result; its saved copy and the pruning are extras that only add a note when they fail.
                    Steps steps = new Steps("world_render");
                    Path[] file = new Path[1];
                    String saved = steps.optional(SAVE_RENDER, () -> file[0] = saveRender(dir, r.png()));
                    String pruned = file[0] == null ? "" : steps.optional(PRUNE_RENDERS,
                            () -> pruneRenders(dir, file[0], KEPT_RENDERS));
                    return new Tool.Output(r.caption() + (file[0] == null ? "" : "\nsaved: " + file[0]) + saved + pruned,
                            r.png(), r.estimatedTokens());
                });
        registry.add("world_summary",
                "Summarize a region: block counts (where rare ones are), non-air bounds, fill per layer, entities. Use before world_slice on unknown areas and to find a block.",
                Schema.of().req("region", "string", REGION).opt("world", "string", WORLD)
                        .opt("path", "string", "\"x,y,z x,y,z\": can a player walk/jump from A to B; else the wall or gap")
                        .opt("diff", "string", "structure_save file of this region: what changed since")
                        .opt("review", "boolean", "Score a build (variety, detail, flat walls, roofs, lights) and say what to fix first"),
                args -> {
                    Region region = Coords.region(world.world(args.str("world", null)), args.str("region"));
                    String path = args.str("path", null);
                    String diff = args.str("diff", null);
                    boolean review = args.bool("review", false);
                    if ((path != null ? 1 : 0) + (diff != null ? 1 : 0) + (review ? 1 : 0) > 1) {
                        throw new ToolException("give one of path, diff or review");
                    }
                    if (review) {
                        return BuildReview.review(world.readAll(region, WorldService.MAX_BULK_VOLUME, "world_summary review"));
                    }
                    if (path != null) {
                        String[] ends = path.trim().split("\\s+");
                        if (ends.length != 2) {
                            throw new ToolException("path takes two positions, \"x,y,z x,y,z\", got \"" + path + "\"");
                        }
                        BlockGrid grid = world.readAll(region, WorldService.MAX_BULK_VOLUME, "world_summary path");
                        return new Walk(grid).path(Coords.pos(ends[0]), Coords.pos(ends[1]));
                    }
                    if (diff != null) {
                        return diff(config, world, region, diff);
                    }
                    return world.summary(region, Budget.DEFAULT_CHARS);
                });
        registry.add("world_entities", "List entities in a region: type, name, position, health, tags. Loaded chunks only, except on Minestom.",
                Schema.of().req("region", "string", REGION).opt("type", "string", "Only this type, such as zombie")
                        .range("limit", 1, Ops.EntitiesResult.MAX_ENTITIES, "Default 50").opt("ids", "boolean", "Add UUIDs")
                        .opt("world", "string", WORLD),
                args -> world.entities(Coords.region(world.world(args.str("world", null)), args.str("region")),
                        args.str("type", null), args.integer("limit", 50), args.bool("ids", false), Budget.DEFAULT_CHARS));
        registry.add("build_undo", "Undo a build_batch edit.",
                Schema.of().opt("edit", "string", "Edit id such as e3. Default: the most recent edit")
                        .opt("backup", "string", "World backups: list, save [label], or one to restore "
                                + "(restarts the server). Edits save one first"),
                args -> {
                    String backup = args.str("backup", null);
                    if (backup == null) {
                        String first = backups.beforeEdit();
                        String out = world.undo(args.str("edit", null));
                        return first.isEmpty() ? out : out + "\n" + first;
                    }
                    if (args.str("edit", null) != null) {
                        throw new ToolException("give edit or backup, not both");
                    }
                    return backups.run(backup);
                });
        registry.add("structure_save", "Save a region as a Sponge schematic (.schem) file.",
                Schema.of().req("region", "string", REGION)
                        .req("file", "string", "Path ending .schem")
                        .opt("world", "string", WORLD),
                args -> {
                    Region region = Coords.region(world.world(args.str("world", null)), args.str("region"));
                    Path file = OutputFile.check(StructureFile.checkSaveName(args.path("file", config.inWorkspace()),
                            "file"), "file");
                    BlockGrid grid = world.readWithData(region);
                    new Steps("structure_save").then(SAVE_STRUCTURE)
                            .run(SAVE_STRUCTURE, () -> OutputFile.write(file, to -> Schematic.save(grid, to)));
                    return "saved " + region.sizeX() + "x" + region.sizeY() + "x" + region.sizeZ() + " to " + file
                            + " (" + Files.size(file) + " bytes)";
                });
    }

    /** Compare a region with a snapshot file ({@link StructureFile}) placed at the region's lowest corner. */
    private static String diff(CoreConfig config, WorldService world, Region region, String name)
            throws java.io.IOException, InterruptedException {
        Path file = config.inWorkspace().resolve("diff", name);
        if (!Files.isRegularFile(file)) {
            throw new ToolException("no file " + file + "; take a snapshot first with structure_save");
        }
        BlockGrid before = StructureFile.load(file, region.world(), region.min());
        Region b = before.region();
        Region overlap = Region.of(region.world(), region.min().x(), region.min().y(), region.min().z(),
                Math.min(region.max().x(), b.max().x()), Math.min(region.max().y(), b.max().y()),
                Math.min(region.max().z(), b.max().z()));
        return Diff.compare(before, world.readAll(overlap, WorldService.MAX_BULK_VOLUME, "world_summary diff"), region,
                name);
    }

    /**
     * One marker per entity, coloured by kind; holograms (text displays, named armor stands) show their text. Each
     * sign with text gets a sign marker with its front lines, then its back lines.
     */
    static List<Marker> markers(List<Ops.EntitySummary> entities, List<Ops.SignText> signs) {
        List<Marker> out = new ArrayList<>();
        if (entities != null) {
            entities.forEach(e -> out.add(Marker.entity(e.type(), e.name(), e.x(), e.y(), e.z())));
        }
        if (signs != null) {
            for (Ops.SignText s : signs) {
                List<String> lines = new ArrayList<>(s.front());
                if (s.back() != null) {
                    lines.addAll(s.back());
                }
                out.add(Marker.sign(s.x(), s.y(), s.z(), lines));
            }
        }
        return out;
    }

    /**
     * Deletes the oldest {@code render-*.png} in {@code dir} beyond {@code keep}, never {@code latest}, whatever path
     * spells it (#398).
     */
    static void pruneRenders(Path dir, Path latest, int keep) throws IOException {
        List<Path> older;
        try (Stream<Path> list = Files.list(dir)) {
            older = list.filter(p -> !AtomicFile.sameFile(p, latest))
                    .filter(p -> p.getFileName().toString().startsWith("render-") && p.getFileName().toString().endsWith(".png"))
                    .toList();
        }
        List<Path> oldestFirst = new ArrayList<>(older);
        oldestFirst.sort(Comparator.comparing(WorldTools::modified));
        for (Path p : oldestFirst.subList(0, Math.max(0, oldestFirst.size() - (keep - 1)))) {
            Files.deleteIfExists(p);
        }
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
