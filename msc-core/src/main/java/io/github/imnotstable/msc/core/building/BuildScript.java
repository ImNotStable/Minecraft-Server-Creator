package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The build language of {@code build_batch}: one step per line, run in order, later steps winning.
 *
 * <pre>
 * fill 0,64,0 10,68,10 stone_bricks hollow
 * cylinder 30,64,30 r=8 h=6 stone_bricks:3,cracked_stone_bricks:1 hollow
 * bands 0,63,0 30=grass_block:80,moss_block:20 34=sand 60=water jitter=3
 * structure 0,64,40 20,64,40 rotate=90
 * legend: #=oak_planks
 * y=0
 * ###
 * end
 * </pre>
 */
public final class BuildScript {
    /** What every step verb takes, for the tool descriptions. */
    public static final String HELP = """
            One step per line; later steps win. B = a block or a mix like grass_block:78,moss_block:10; B{SNBT} adds \
            block entity data; a>b>c a gradient (grade=y|x|z|-y). Every step takes replace=a,b (air = empty), seed=N, \
            repeat=N step=DX,DY,DZ, clump=N (patches). Unsaid facing, half, axis, type, open, hinge... is refused.
            fill X,Y,Z X,Y,Z B [hollow|outline|walls]
            set X,Y,Z B
            cylinder X,Y,Z r=R h=H B [hollow] [thick=T] [inner=R]   (X,Y,Z = bottom centre)
            ring X,Y,Z r=R B [h=H] [thick=T]
            disc X,Y,Z r=R B [inner=R]
            sphere|dome X,Y,Z r=R B [hollow] [thick=T] [rough=N]   (dome: X,Y,Z = floor centre)
            line X,Y,Z X,Y,Z B [w=W]
            helix X,Y,Z r=R h=H B [turns=T] [w=W] [start=DEG]   (bottom centre; turns<0 anticlockwise; \
            start=0 north)
            bands X,Y,Z [h=H] R1=B R2=B ... [jitter=J]   (flat rings)
            terrain X,Y,Z X,Y,Z B [top=B under=B depth=3 cliff=B bed=B noise=fbm|ridged|terraced|waves scale=N edge=N \
            ground=Y water=Y]   (noise land in the box; cliff= on steep sides, bed= under water; rim eases to ground= \
            over edge= blocks, 0 fills the box; below ground= digs, water= fills)
            path X,Y,Z X,Y,Z ... B [w=3 wander=N depth=N bed=B]   (curved way on the ground; depth= digs a stream)
            scatter X,Y,Z X,Y,Z B|file=PATH,PATH [spacing=8 chance=100 on=B sink=N]   (on the ground, one per cell)
            trim X,Y,Z X,Y,Z B at=top|base|band|corners|sills|lintels|shutters|frames [out=1 level=Y]
            decorate X,Y,Z X,Y,Z B [on=top|ground|floor|edge|corner|wall|ceiling chance=25 spacing=N length=A-B tip=B]
            weather X,Y,Z X,Y,Z [amount=30 swap=a>b break=N chip=B]   (these three: msc_status guide=build)
            flora X,Y,Z X,Y,Z B [chance=35 soil=B]   (your own plant mix on open ground; guide=build)
            smooth|erode|blend X,Y,Z X,Y,Z   paint X,Y,Z X,Y,Z B [slope=A-B]   (shape what is there)
            river|tunnel X,Y,Z X,Y,Z ...   cave X,Y,Z X,Y,Z   (these seven: guide=build)
            sign|banner|head|book|container X,Y,Z B text="a|b"|patterns=|player=|pages=|items=
            entity X,Y,Z TYPE [data={} still=true]  frame X,Y,Z item= facing=  stand X,Y,Z [head= mainhand=]
            biome X,Y,Z X,Y,Z ID  light X,Y,Z X,Y,Z [B level=8]  image X,Y,Z file=PNG w=N [plane=x|z|floor]  (guide=build)
            structure X,Y,Z [X,Y,Z ...] [rotate=90] [face=X,Z] [anchor=center] [air=false] [file=PATH]
              then the text and "end", unless file= (.schem or text). Text: "legend: #=stone g=glass", then per \
            layer "y=N" and rows (z) of symbols (x); "." air, "_" unchanged; "y=N same as y=M", "y=N all #". rotate \
            turns clockwise seen from above; face=X,Z turns each copy's north side (first row) toward X,Z.
            copy X,Y,Z X,Y,Z to=X,Y,Z [structure options]   (reads the world before this call)""";

    private static final Pattern POS = Pattern.compile("-?\\d+,-?\\d+,-?\\d+");
    private static final Pattern OPTION = Pattern.compile("[a-z]+=.*");
    private static final Pattern BAND = Pattern.compile("\\d+=.+");
    private static final Set<String> FLAGS = Set.of("solid", "hollow", "outline", "walls");
    /** Steps that set facing, half and the like on their own blocks from where each lands. */
    private static final Set<String> SHAPING = Set.of("trim", "decorate", "weather");

    /** Where structures come from: files in the workspace and the world itself. */
    public interface StructureFiles {
        /** A structure file (.schem or text) relative to the workspace, starting at 0,0,0. */
        BlockGrid load(String path, String world) throws java.io.IOException;

        /** The blocks in a region of the world as they are now, with block entity data, at their own coordinates. */
        BlockGrid read(Region region) throws InterruptedException;

        /** A file in the workspace, which must exist (images for the image step). */
        default java.nio.file.Path file(String path) {
            throw new ToolException("files are not available here");
        }
    }

    private final String world;
    private final StructureFiles files;

    public BuildScript(String world, StructureFiles files) {
        this.world = world;
        this.files = files;
    }

    /** The parts of one step line, as tokens. */
    public record Parts(String verb, List<BlockPos> points, Map<String, String> options, Set<String> flags,
                        List<String> bands, String block) {
    }

    public List<Step> parse(String text) throws java.io.IOException, InterruptedException {
        List<Step> steps = new ArrayList<>();
        String[] lines = text.split("\\R");
        List<String> unsaid = new ArrayList<>(); // every line's unsaid block data, refused together (ADR 0077)
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            int lineNo = i + 1;
            try {
                Parts parts = split(line);
                if (parts.verb().equals("structure") && !parts.options().containsKey("file")) {
                    StringBuilder body = new StringBuilder();
                    int j = i + 1;
                    while (j < lines.length && !lines[j].strip().equals("end")) {
                        body.append(lines[j]).append('\n');
                        j++;
                    }
                    if (j == lines.length) {
                        throw new ToolException("structure has no closing \"end\" line");
                    }
                    i = j;
                    steps.add(stamp(parts, TextGrid.parse(body.toString(), world, new BlockPos(0, 0, 0))));
                } else {
                    steps.add(step(parts));
                }
            } catch (ToolException e) {
                if (e.getMessage().startsWith(BlockRules.UNSAID)) {
                    unsaid.add("line " + lineNo + ": " + e.getMessage().substring(BlockRules.UNSAID.length()));
                    continue;
                }
                throw new ToolException("line " + lineNo + " (" + abbreviate(line) + "): " + e.getMessage());
            }
        }
        if (!unsaid.isEmpty()) {
            throw new ToolException("nothing placed; " + BlockRules.UNSAID + String.join("; ", unsaid) + BlockRules.EXAMPLE);
        }
        if (steps.isEmpty()) {
            throw new ToolException("no steps; write one step per line, for example: fill 0,64,0 4,64,4 stone");
        }
        return steps;
    }

    private static String abbreviate(String line) {
        return Limits.shorten(line, 60);
    }

    /** Words split at spaces, except spaces inside a block's {@code [...]} or block entity data {@code {...}}. */
    static String[] tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        BlockPattern.Nesting n = new BlockPattern.Nesting();
        boolean quoted = false; // text="two words" keeps its spaces (ADR 0082)
        for (char c : line.trim().toCharArray()) {
            boolean top = n.top(c);
            if (top && c == '"') {
                quoted = !quoted;
            }
            if (top && !quoted && Character.isWhitespace(c)) {
                if (!word.isEmpty()) {
                    out.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (!word.isEmpty() || out.isEmpty()) {
            out.add(word.toString());
        }
        return out.toArray(String[]::new);
    }

    public static Parts split(String line) {
        String[] tokens = tokens(line);
        String verb = tokens[0].toLowerCase(java.util.Locale.ROOT);
        List<BlockPos> points = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        List<String> bands = new ArrayList<>();
        String block = null;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (POS.matcher(t).matches()) {
                points.add(Coords.pos(t));
            } else if (BAND.matcher(t).matches()) {
                bands.add(t);
            } else if (OPTION.matcher(t).matches()) {
                int eq = t.indexOf('=');
                String value = t.substring(eq + 1);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (options.put(t.substring(0, eq), value) != null) {
                    throw new ToolException(t.substring(0, eq) + "= is given twice");
                }
            } else if (FLAGS.contains(t.toLowerCase(java.util.Locale.ROOT))) {
                flags.add(t.toLowerCase(java.util.Locale.ROOT));
            } else if (block == null) {
                block = t;
            } else {
                throw new ToolException("did not understand \"" + t + "\" (block is already " + block
                        + "; block mixes have no spaces)");
            }
        }
        return new Parts(verb, points, options, flags, bands, block);
    }

    /** Build a step from its parts. */
    public Step step(Parts p) throws java.io.IOException, InterruptedException {
        return step(p, null);
    }

    /** {@code span}: the box a gradient spreads over, once the step without it has told its bounds. */
    private Step step(Parts p, Region span) throws java.io.IOException, InterruptedException {
        if (p.options().containsKey("repeat") && !p.verb().equals("structure") && !p.verb().equals("copy")) {
            return repeated(p);
        }
        Options o = new Options(p, span);
        Step step = switch (p.verb()) {
            case "fill" -> {
                o.points(2);
                String shape = p.flags().isEmpty() ? "solid" : o.oneFlag();
                yield new Step.Fill(new Region(world, p.points().get(0),
                        p.points().get(1)), o.block(), shape, o.replace());
            }
            case "set" -> {
                o.points(1);
                o.flags(Set.of());
                String state = BlockNames.full(o.requireBlock());
                if (BlockPattern.isMix(state)) {
                    throw new ToolException("set takes one block, not a mix");
                }
                BlockRules.require(List.of(state));
                yield new Step.Set(world, p.points(), List.of(state), o.replace());
            }
            case "cylinder", "ring", "disc" -> {
                o.points(1);
                boolean ring = p.verb().equals("ring");
                boolean disc = p.verb().equals("disc");
                int r = o.integer("r", null, 0, 512);
                int h = disc ? 1 : o.integer("h", ring ? 1 : null, 1, 4096);
                int inner = o.integer("inner", -1, -1, r - 1);
                boolean hollow = ring || p.flags().contains("hollow");
                o.flags(disc ? Set.of() : Set.of("hollow"));
                int thick = hollow ? o.integer("thick", 1, 1, r + 1) : o.forbid("thick", "add hollow");
                yield new Step.Cylinder(world, p.points().get(0), r, h, inner, hollow ? thick : 0, o.block(), o.replace());
            }
            case "sphere", "dome" -> {
                o.points(1);
                int r = o.integer("r", null, 0, 256);
                boolean hollow = p.flags().contains("hollow");
                o.flags(Set.of("hollow"));
                int thick = hollow ? o.integer("thick", 1, 1, r + 1) : o.forbid("thick", "add hollow");
                int rough = o.integer("rough", 0, 0, Math.max(1, r));
                if (rough > 0) {
                    yield new Step.Rough(world, p.points().get(0), r, hollow ? thick : 0, p.verb().equals("dome"), rough,
                            o.seed(), o.block(), o.replace());
                }
                yield new Step.Sphere(world, p.points().get(0), r, hollow ? thick : 0, p.verb().equals("dome"),
                        o.block(), o.replace());
            }
            case "line" -> {
                o.points(2);
                o.flags(Set.of());
                int w = o.integer("w", 1, 1, 64);
                yield new Step.Line(world, p.points().get(0), p.points().get(1), w, o.block(), o.replace());
            }
            case "helix" -> {
                o.points(1);
                o.flags(Set.of());
                int r = o.integer("r", null, 1, 512);
                int h = o.integer("h", null, 1, 4096);
                // One block of rise per block of arc: a stair a player can walk up.
                double turns = o.decimal("turns", (h - 1) / (2 * Math.PI * r), -1024, 1024);
                int w = o.integer("w", 1, 1, r + 1);
                int start = o.integer("start", 0, -360, 360);
                yield new Step.Helix(world, p.points().get(0), r, h, turns, w, start, o.block(), o.replace());
            }
            case "bands" -> {
                o.points(1);
                o.flags(Set.of());
                if (p.block() != null) {
                    throw new ToolException("bands takes RADIUS=BLOCK entries, such as 30=grass_block 34=sand; got " + p.block());
                }
                if (p.bands().isEmpty()) {
                    throw new ToolException("bands needs RADIUS=BLOCK entries, such as 30=grass_block 34=sand 60=water");
                }
                int h = o.integer("h", 1, 1, 4096);
                int jitter = o.integer("jitter", 0, 0, 64);
                int[] radii = new int[p.bands().size()];
                List<BlockPattern> blocks = new ArrayList<>();
                for (int i = 0; i < radii.length; i++) {
                    String band = p.bands().get(i);
                    int eq = band.indexOf('=');
                    String digits = band.substring(0, eq);
                    long radius;
                    try {
                        radius = Long.parseLong(digits);
                    } catch (NumberFormatException e) {
                        radius = Long.MAX_VALUE; // more digits than a long holds: certainly above the limit
                    }
                    if (radius > 1024) {
                        throw new ToolException("band radius " + digits + " is above 1024");
                    }
                    radii[i] = (int) radius;
                    if (i > 0 && radii[i] <= radii[i - 1]) {
                        throw new ToolException("band radii must grow: " + radii[i - 1] + " then " + radii[i]);
                    }
                    blocks.add(BlockPattern.parse(band.substring(eq + 1), o.seed()).withClump(o.clump()));
                }
                o.done();
                yield new Step.Bands(world, p.points().get(0), h, radii, blocks, jitter, o.seed(), o.replace());
            }
            case "terrain" -> {
                o.points(2);
                o.flags(Set.of());
                Region box = new Region(world, p.points().get(0), p.points().get(1));
                int shortSide = Math.min(box.sizeX(), box.sizeZ());
                BlockPattern body = o.block();
                BlockPattern top = o.pattern("top", body);
                BlockPattern under = o.pattern("under", body);
                BlockPattern cliff = o.pattern("cliff", body);
                BlockPattern bed = o.pattern("bed", null);
                String noise = o.string("noise");
                int lo = box.min().y();
                int hi = box.max().y();
                int ground = o.integer("ground", lo, lo, hi);
                String waterText = o.string("water");
                Integer water = waterText == null ? null : o.integer("water", null, lo, ground);
                yield new Step.Terrain(box, body, top, under, cliff, bed, noise == null ? "fbm" : noise, ground, water,
                        o.integer("depth", 3, 0, 64), o.integer("scale", Math.max(8, shortSide / 2), 2, 4096),
                        o.integer("edge", shortSide / 4, 0, 4096), o.integer("octaves", 4, 1, 8),
                        o.integer("steps", 4, 2, 32), o.seed(), o.replace());
            }
            case "path" -> {
                if (p.points().size() < 2 || p.points().size() > 256) {
                    throw new ToolException("path takes 2 to 256 positions X,Y,Z, got " + p.points().size());
                }
                o.flags(Set.of());
                int w = o.integer("w", 3, 1, 64);
                yield new Step.Path(world, p.points(), w, o.integer("wander", w + 1, 0, 256),
                        o.integer("depth", 0, 0, 32), o.block(), o.pattern("bed", null), o.seed(), o.replace());
            }
            case "scatter" -> {
                o.points(2);
                o.flags(Set.of());
                String file = o.string("file");
                if ((file == null) == (p.block() == null)) {
                    throw new ToolException("scatter takes a block (mix) or file=PATH[,PATH...] of structures, one of them");
                }
                List<BlockGrid> structures = new ArrayList<>();
                if (file != null) {
                    for (String f : file.split(",")) {
                        structures.add(files.load(f.trim(), world));
                    }
                }
                String on = o.string("on");
                yield new Step.Scatter(new Region(world, p.points().get(0), p.points().get(1)),
                        file == null ? o.block() : null, structures, o.integer("spacing", 8, 1, 1024),
                        o.integer("chance", 100, 1, 100),
                        on == null ? List.of() : BlockPattern.splitTopLevel(on).stream().map(String::trim)
                                .filter(s -> !s.isEmpty()).toList(), o.integer("sink", 0, 0, 64), o.seed(), o.replace());
            }
            case "trim" -> {
                o.points(2);
                o.flags(Set.of());
                String at = o.string("at");
                if (at == null) {
                    throw new ToolException("trim needs at=" + String.join("|", Step.Trim.AT));
                }
                int level = at.equals("band") ? o.integer("level", null, -2048, 4096) : o.forbid("level", "at=band");
                yield new Step.Trim(new Region(world, p.points().get(0), p.points().get(1)), o.block(), at, level,
                        o.integer("out", 1, 1, 4), o.replace());
            }
            case "decorate" -> {
                o.points(2);
                o.flags(Set.of());
                String on = o.string("on");
                on = on == null ? "top" : on;
                int spacing = o.integer("spacing", 1, 1, 256);
                int[] length = o.range("length", on.equals("wall") ? new int[] {1, 4} : new int[] {1, 1}, 1, 64);
                yield new Step.Decorate(new Region(world, p.points().get(0), p.points().get(1)), o.block(),
                        o.pattern("tip", null), on, o.integer("chance", spacing > 1 ? 100 : 25, 1, 100), spacing,
                        length[0], length[1], o.clump(), o.list("soil"), o.seed(), o.replace());
            }
            case "flora" -> {
                o.points(2);
                o.flags(Set.of());
                if (p.options().containsKey("style")) {
                    throw new ToolException("flora takes your own plant mix (short_grass:40,poppy:3,...), not a style; "
                            + "pick the plants that grow there (ADR 0103)");
                }
                int clump = o.clump() == 0 ? 5 : o.clump();
                yield new Step.Decorate(new Region(world, p.points().get(0), p.points().get(1)), o.block(), null, "ground",
                        o.integer("chance", 35, 1, 100), 1, 1, 1, clump, o.list("soil"), o.seed(), o.replace());
            }
            case "smooth" -> {
                o.points(2);
                o.flags(Set.of());
                yield new Step.Smooth(new Region(world, p.points().get(0), p.points().get(1)),
                        o.integer("radius", 3, 1, 32), o.integer("strength", 2, 1, 16), o.replace());
            }
            case "erode" -> {
                o.points(2);
                o.flags(Set.of());
                String mode = o.string("mode");
                yield new Step.Erode(new Region(world, p.points().get(0), p.points().get(1)), mode == null ? "smooth" : mode,
                        o.integer("faces", 4, 1, 6), o.integer("times", 1, 1, 16), o.replace());
            }
            case "blend" -> {
                o.points(2);
                o.flags(Set.of());
                String shape = o.string("shape");
                yield new Step.Blend(new Region(world, p.points().get(0), p.points().get(1)),
                        shape == null || Boolean.parseBoolean(shape), o.integer("times", 1, 1, 16), o.replace());
            }
            case "paint" -> {
                o.points(2);
                o.flags(Set.of());
                String faces = o.string("faces");
                int[] slope = o.range("slope", new int[] {0, 90}, 0, 90);
                yield new Step.Paint(new Region(world, p.points().get(0), p.points().get(1)), o.block(),
                        faces == null ? "top" : faces, o.integer("depth", 1, 1, 16), slope[0], slope[1],
                        o.integer("chance", 100, 1, 100), o.seed(), o.replace());
            }
            case "river" -> {
                if (p.points().size() < 2 || p.points().size() > 256) {
                    throw new ToolException("river takes 2 to 256 positions X,Y,Z, got " + p.points().size());
                }
                o.flags(Set.of());
                if (p.block() != null) {
                    throw new ToolException("river takes water=, bed= and shore= blocks, not \"" + p.block() + "\"");
                }
                if (o.string("bed") == null) {
                    // From scratch (ADR 0106): the bed's blocks are the agent's choice, there is no stock mix.
                    throw new ToolException("river needs bed=, the blocks of its bed, such as bed=gravel:3,clay:1");
                }
                int w = o.integer("w", 5, 1, 64);
                yield new Step.River(world, p.points(), w, o.integer("wander", w, 0, 256), o.integer("depth", 3, 1, 16),
                        o.integer("bank", 4, 0, 32), o.decimal("slope", 1, 0.2, 8),
                        o.pattern("water", BlockPattern.single("minecraft:water")),
                        o.pattern("bed", null), o.pattern("shore", null),
                        o.seed(), o.replace());
            }
            case "tunnel" -> {
                if (p.points().size() < 2 || p.points().size() > 256) {
                    throw new ToolException("tunnel takes 2 to 256 positions X,Y,Z, got " + p.points().size());
                }
                o.flags(Set.of());
                yield new Step.Tunnel(world, p.points(), o.integer("r", 3, 1, 32), o.integer("rough", 1, 0, 8),
                        p.block() == null ? BlockPattern.single("minecraft:air") : o.block(), o.seed(), o.replace());
            }
            case "cave" -> {
                o.points(2);
                o.flags(Set.of());
                yield new Step.Caves(new Region(world, p.points().get(0), p.points().get(1)),
                        o.integer("density", 30, 1, 100), o.integer("scale", 16, 4, 256), o.integer("roof", 4, 0, 64),
                        p.block() == null ? BlockPattern.single("minecraft:air") : o.block(), o.seed(), o.replace());
            }
            case "sign", "banner", "head", "book", "container" -> {
                o.points(1);
                o.flags(Set.of());
                String data = switch (p.verb()) {
                    case "sign" -> Things.sign(o.string("text"), o.string("back"), o.string("color"),
                            Boolean.parseBoolean(o.string("glow")), !"false".equals(o.string("wax")));
                    case "banner" -> {
                        String patterns = o.string("patterns");
                        if (patterns == null) {
                            throw new ToolException("banner needs patterns=pattern:colour,... (bottom layer first)");
                        }
                        yield Things.banner(patterns);
                    }
                    case "head" -> Things.head(o.string("player"), o.string("texture"), o.string("skin"));
                    case "book" -> Things.book(o.string("title"), o.string("author"), o.string("pages"));
                    default -> Things.container(o.string("items"), o.string("loot"));
                };
                String state = Things.withData(o.requireBlock(), data);
                BlockRules.require(List.of(state));
                yield new Step.Set(world, p.points(), List.of(state), o.replace());
            }
            case "entity" -> {
                o.points(1);
                o.flags(Set.of());
                String type = o.requireBlock();
                String data = o.string("data");
                boolean still = Boolean.parseBoolean(o.string("still"));
                String extra = (still ? Things.STILL : "") + rotation(o);
                yield new Step.Summon(world, p.points().get(0), type, merge(data, extra), o.integer("count", 1, 1, 64), 0);
            }
            case "frame" -> {
                o.points(1);
                o.flags(Set.of());
                String item = o.string("item");
                String facing = o.string("facing");
                if (facing == null) {
                    throw new ToolException("frame needs facing=north|south|east|west|up|down, the way it faces out");
                }
                String data = "Facing:" + Things.facing(facing) + "b"
                        + (item == null ? "" : ",Item:" + Things.item(item))
                        + ("false".equals(o.string("fixed")) ? "" : ",Fixed:1b")
                        + (Boolean.parseBoolean(o.string("invisible")) ? ",Invisible:1b" : "")
                        + ",ItemRotation:" + o.integer("turn", 0, 0, 7) + "b";
                yield new Step.Summon(world, p.points().get(0),
                        Boolean.parseBoolean(o.string("glow")) ? "glow_item_frame" : "item_frame",
                        merge(o.string("data"), data), 1, 0.5);
            }
            case "stand" -> {
                o.points(1);
                o.flags(Set.of());
                List<String> gear = new ArrayList<>();
                for (String slot : List.of("head", "chest", "legs", "feet", "mainhand", "offhand")) {
                    String item = o.string(slot);
                    if (item != null) {
                        gear.add(slot + ":" + Things.item(item));
                    }
                }
                String data = "Invulnerable:1b,ShowArms:" + ("false".equals(o.string("arms")) ? "0b" : "1b")
                        + (Boolean.parseBoolean(o.string("small")) ? ",Small:1b" : "")
                        + ("false".equals(o.string("base")) ? ",NoBasePlate:1b" : "")
                        + (Boolean.parseBoolean(o.string("invisible")) ? ",Invisible:1b" : "")
                        + (gear.isEmpty() ? "" : ",equipment:{" + String.join(",", gear) + "}") + rotation(o);
                yield new Step.Summon(world, p.points().get(0), "armor_stand", merge(o.string("data"), data), 1, 0);
            }
            case "biome" -> {
                o.points(2);
                o.flags(Set.of());
                String only = o.string("only");
                yield new Step.Biome(new Region(world, p.points().get(0), p.points().get(1)),
                        Things.id(o.requireBlock(), "biome"), only == null ? null : Things.id(only, "only"));
            }
            case "light" -> {
                o.points(2);
                o.flags(Set.of());
                String on = o.string("on");
                on = on == null ? "floor" : on;
                if (!Step.Decorate.ON.contains(on)) {
                    throw new ToolException("light on= is one of " + Step.Decorate.ON + ", got " + on);
                }
                yield new Step.Light(new Region(world, p.points().get(0), p.points().get(1)),
                        p.block() == null ? null : o.block(), on, o.integer("level", 8, 1, 15), o.seed(), o.replace());
            }
            case "image" -> {
                o.points(1);
                o.flags(Set.of());
                String file = o.string("file");
                if (file == null) {
                    throw new ToolException("image needs file=PATH (a PNG or JPEG in the workspace)");
                }
                String plane = o.string("plane");
                String palette = o.string("palette");
                BlockGrid picture = Images.grid(files.file(file), world, o.integer("w", null, 1, 512),
                        o.integer("h", 0, 0, 512), plane == null ? "x" : plane, palette == null ? "all" : palette,
                        !"false".equals(o.string("dither")));
                List<String> replace = o.replace();
                o.done();
                yield new Step.Stamp(world, List.of(new Step.Stamp.Placement(picture, p.points().get(0))), false, replace);
            }
            case "weather" -> {
                o.points(2);
                o.flags(Set.of());
                if (p.block() != null) {
                    throw new ToolException("weather takes no block; it ages what is there (swap=from>to adds pairs)");
                }
                Map<String, String> swaps = new HashMap<>();
                String swap = o.string("swap");
                if (swap != null) {
                    for (String pair : BlockPattern.splitTopLevel(swap)) {
                        String[] ab = pair.split(">");
                        if (ab.length != 2 || ab[0].isBlank() || ab[1].isBlank()) {
                            throw new ToolException("swap takes from>to pairs, such as swap=bricks>mud_bricks; got " + pair);
                        }
                        swaps.put(BlockNames.brief(BlockNames.id(BlockNames.full(ab[0].trim()))), ab[1].trim());
                    }
                }
                yield new Step.Weather(new Region(world, p.points().get(0), p.points().get(1)),
                        o.integer("amount", 30, 0, 100), swaps, o.integer("break", 0, 0, 100), o.pattern("chip", null),
                        o.seed(), o.replace());
            }
            case "structure" -> {
                String file = p.options().get("file");
                if (file == null) {
                    throw new ToolException("structure needs file=PATH here, or its text on the next lines");
                }
                yield stamp(p, files.load(file, world));
            }
            case "copy" -> copy(p);
            default -> throw new ToolException("unknown step \"" + p.verb()
                    + "\"; use fill, set, cylinder, ring, disc, sphere, dome, line, helix, bands, terrain, path, scatter, trim, "
                    + "decorate, weather, flora, smooth, erode, blend, paint, river, tunnel, cave, sign, banner, "
                    + "head, book, container, entity, frame, stand, biome, light, image, structure or copy");
        };
        if (!p.verb().equals("structure") && !p.verb().equals("copy")) {
            o.done();
        }
        if (o.unspanned && span == null) {
            return step(p, step.bounds());
        }
        return step;
    }

    /** {@code repeat=N step=DX,DY,DZ} on a shape step: the step N times, each copy moved by one more step. */
    private Step repeated(Parts p) throws java.io.IOException, InterruptedException {
        Map<String, String> options = new LinkedHashMap<>(p.options());
        String repeatText = options.remove("repeat").trim();
        String stepText = options.remove("step");
        int repeat;
        try {
            repeat = Integer.parseInt(repeatText);
        } catch (NumberFormatException e) {
            throw new ToolException("repeat must be a whole number, got " + repeatText);
        }
        if (repeat < 1 || repeat > 1024) {
            throw new ToolException("repeat must be 1 to 1024, got " + repeat);
        }
        if (stepText == null) {
            throw new ToolException("repeat needs step=DX,DY,DZ, the offset between copies");
        }
        BlockPos d = Coords.pos(stepText);
        List<Step> copies = new ArrayList<>(repeat);
        for (int i = 0; i < repeat; i++) {
            int k = i;
            List<BlockPos> moved = p.points().stream().map(q -> Coords.inWorld(q.x() + (long) d.x() * k,
                    q.y() + (long) d.y() * k, q.z() + (long) d.z() * k)).toList();
            copies.add(step(new Parts(p.verb(), moved, options, p.flags(), p.bands(), p.block())));
        }
        return new Step.Group(copies);
    }

    /** {@code yaw=DEG} as an entity's Rotation, or nothing. */
    private static String rotation(Options o) {
        String yaw = o.string("yaw");
        if (yaw == null) {
            return "";
        }
        return ",Rotation:[" + (float) o.decimal("yaw", 0, -360, 360) + "f,0.0f]";
    }

    /** The agent's own SNBT compound with the step's entries added. */
    private static String merge(String data, String extra) {
        String e = extra.startsWith(",") ? extra.substring(1) : extra;
        if (data == null || data.isBlank()) {
            return "{" + e + "}";
        }
        String d = data.trim();
        if (!d.startsWith("{") || !d.endsWith("}")) {
            throw new ToolException("data must be one SNBT compound {...}, got " + data);
        }
        if (e.isEmpty()) {
            return d;
        }
        return "{" + e + (d.length() > 2 ? "," + d.substring(1) : "}");
    }

    /** Read a region of the world now and stamp it at {@code to=}, with every structure option. */
    private Step copy(Parts p) throws InterruptedException {
        if (p.points().size() != 2) {
            throw new ToolException("copy takes 2 positions X,Y,Z (the source corners) and to=X,Y,Z, got "
                    + p.points().size() + " positions");
        }
        if (p.options().containsKey("file")) {
            throw new ToolException("copy does not take file=");
        }
        String to = p.options().get("to");
        if (to == null) {
            throw new ToolException("copy needs to=X,Y,Z, where the copy's lowest corner goes");
        }
        BlockPos target = Coords.pos(to);
        Region source =
                new Region(world, p.points().get(0), p.points().get(1));
        BlockGrid read = files.read(source);
        Region origin = Region.of(
                world, 0, 0, 0, source.sizeX() - 1, source.sizeY() - 1, source.sizeZ() - 1);
        Map<String, String> options = new LinkedHashMap<>(p.options());
        options.remove("to");
        return stamp(new Parts(p.verb(), List.of(target), options, p.flags(), p.bands(), p.block()),
                new BlockGrid(origin, read.palette(), read.blocks()));
    }

    /** Place {@code structure} (starting at 0,0,0) at every position, turned and repeated as the options say. */
    public Step stamp(Parts p, BlockGrid structure) {
        Options o = new Options(p);
        o.used("file");
        o.flags(Set.of());
        if (p.points().isEmpty()) {
            throw new ToolException("structure needs at least one position X,Y,Z");
        }
        if (p.block() != null) {
            throw new ToolException("did not understand \"" + p.block() + "\"");
        }
        int fixed = Turn.quarters(o.integer("rotate", 0, -270, 270));
        BlockPos face = null;
        String faceText = o.string("face");
        if (faceText != null) {
            String[] xz = faceText.split(",");
            if (xz.length != 2) {
                throw new ToolException("face takes X,Z, got " + faceText);
            }
            try {
                face = new BlockPos(Integer.parseInt(xz[0].trim()), 0, Integer.parseInt(xz[1].trim()));
            } catch (NumberFormatException e) {
                throw new ToolException("face takes X,Z, got " + faceText);
            }
        }
        String anchor = o.string("anchor");
        boolean center = anchor != null && anchor.equals("center");
        if (anchor != null && !center && !anchor.equals("corner")) {
            throw new ToolException("anchor is corner or center, got " + anchor);
        }
        int repeat = o.integer("repeat", 1, 1, 1024);
        BlockPos step = new BlockPos(0, 0, 0);
        String stepText = o.string("step");
        if (stepText != null) {
            step = Coords.pos(stepText);
        } else if (repeat > 1) {
            throw new ToolException("repeat needs step=DX,DY,DZ, the offset between copies");
        }
        String airText = o.string("air");
        boolean air = airText == null || Boolean.parseBoolean(airText);
        List<String> replace = o.replace();
        o.done();
        BlockRules.require(structure.palette());
        Region0 size = new Region0(structure.region().sizeX(), structure.region().sizeZ());
        Map<Integer, BlockGrid> turned = new HashMap<>();
        List<Step.Stamp.Placement> placements = new ArrayList<>();
        for (BlockPos point : p.points()) {
            for (int i = 0; i < repeat; i++) {
                BlockPos base = Coords.inWorld(point.x() + (long) step.x() * i, point.y() + (long) step.y() * i,
                        point.z() + (long) step.z() * i);
                int q = fixed;
                if (face != null) {
                    double cx = center ? base.x() : base.x() + (size.x() - 1) / 2.0;
                    double cz = center ? base.z() : base.z() + (size.z() - 1) / 2.0;
                    q = Math.floorMod(Turn.facing(cx, cz, face.x(), face.z()) + fixed, 4);
                }
                BlockGrid g = turned.computeIfAbsent(q, k -> Turn.grid(structure, k));
                BlockPos at = center ? base.offset(-(g.region().sizeX() / 2), 0, -(g.region().sizeZ() / 2)) : base;
                placements.add(new Step.Stamp.Placement(g, at));
            }
        }
        return new Step.Stamp(world, placements, air, replace);
    }

    private record Region0(int x, int z) {
    }

    /** Option access that remembers what was read, so unknown options are reported. */
    private static final class Options {
        private final Parts p;
        private final Region span;
        private final Set<String> used = new java.util.HashSet<>(Set.of("replace", "seed"));
        /** A gradient was read before the step's box was known. */
        boolean unspanned;

        Options(Parts p) {
            this(p, null);
        }

        Options(Parts p, Region span) {
            this.p = p;
            this.span = span;
        }

        /** A gradient spread over the step's box, along grade= (y by default). */
        BlockPattern spanned(BlockPattern b) {
            if (!b.needsSpan()) {
                return b;
            }
            String grade = string("grade");
            if (span == null) {
                unspanned = true;
                return b;
            }
            return b.spanning(span, grade == null ? "y" : grade.trim());
        }

        /** {@code key=N} or {@code key=A-B}, as {min, max}. */
        int[] range(String key, int[] fallback, int min, int max) {
            String v = string(key);
            if (v == null) {
                return fallback;
            }
            String[] ab = v.trim().split("-", 2);
            try {
                int a = Integer.parseInt(ab[0].trim());
                int b = ab.length == 2 ? Integer.parseInt(ab[1].trim()) : a;
                if (a < min || b > max || a > b) {
                    throw new ToolException(key + " must be N or A-B within " + min + " to " + max + ", got " + v);
                }
                return new int[] {a, b};
            } catch (NumberFormatException e) {
                throw new ToolException(key + " must be N or A-B, got " + v);
            }
        }

        void used(String key) {
            used.add(key);
        }

        void points(int n) {
            if (p.points().size() != n) {
                throw new ToolException(p.verb() + " takes " + n + (n == 1 ? " position" : " positions") + " X,Y,Z, got "
                        + p.points().size());
            }
            if (!p.bands().isEmpty() && !p.verb().equals("bands")) {
                throw new ToolException("did not understand \"" + p.bands().get(0) + "\"");
            }
        }

        void flags(Set<String> allowed) {
            for (String f : p.flags()) {
                if (!allowed.contains(f)) {
                    throw new ToolException(p.verb() + " does not take " + f);
                }
            }
        }

        String oneFlag() {
            if (p.flags().size() > 1) {
                throw new ToolException("pick one of " + p.flags());
            }
            return p.flags().iterator().next();
        }

        String string(String key) {
            used.add(key);
            return p.options().get(key);
        }

        int integer(String key, Integer fallback, int min, int max) {
            String v = string(key);
            if (v == null) {
                if (fallback == null) {
                    throw new ToolException(p.verb() + " needs " + key + "=N");
                }
                return fallback;
            }
            int n;
            try {
                n = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new ToolException(key + " must be a whole number, got " + v);
            }
            if (n < min || n > max) {
                throw new ToolException(key + " must be " + min + " to " + max + ", got " + n);
            }
            return n;
        }

        double decimal(String key, double fallback, double min, double max) {
            String v = string(key);
            if (v == null) {
                return fallback;
            }
            double n;
            try {
                n = Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                throw new ToolException(key + " must be a number, got " + v);
            }
            if (!(n >= min && n <= max)) {
                throw new ToolException(key + " must be " + (int) min + " to " + (int) max + ", got " + v);
            }
            return n;
        }

        int forbid(String key, String hint) {
            if (p.options().containsKey(key)) {
                throw new ToolException(key + "= needs " + hint);
            }
            return 0;
        }

        long seed() {
            return integer("seed", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        String requireBlock() {
            if (p.block() == null) {
                throw new ToolException(p.verb() + " needs a block");
            }
            return p.block();
        }

        BlockPattern block() {
            return chosen(spanned(BlockPattern.parse(requireBlock(), seed()).withClump(clump())));
        }

        /**
         * Blocks the agent writes straight into the build say how they are placed (ADR 0077); the detail steps turn
         * their own blocks to fit, so those are left to them.
         */
        BlockPattern chosen(BlockPattern b) {
            if (!SHAPING.contains(p.verb())) {
                BlockRules.require(b.allStates());
            }
            return b;
        }

        /** A block option such as {@code top=grass_block}, with the step's seed and clump; {@code fallback} when absent. */
        BlockPattern pattern(String key, BlockPattern fallback) {
            String v = string(key);
            return v == null ? fallback : chosen(spanned(BlockPattern.parse(v, seed()).withClump(clump())));
        }

        int clump() {
            return integer("clump", 0, 0, 256);
        }

        /** {@code key=a,b} as full block names; empty when absent. */
        List<String> list(String key) {
            String v = string(key);
            return v == null ? List.of() : BlockPattern.splitTopLevel(v).stream().map(String::trim)
                    .filter(t -> !t.isEmpty()).map(BlockNames::full).toList();
        }

        List<String> replace() {
            String r = string("replace");
            return r == null ? List.of() : BlockPattern.splitTopLevel(r).stream().map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
        }

        void done() {
            for (String key : p.options().keySet()) {
                if (!used.contains(key)) {
                    throw new ToolException(p.verb() + " does not take " + key + "=");
                }
            }
        }
    }
}
