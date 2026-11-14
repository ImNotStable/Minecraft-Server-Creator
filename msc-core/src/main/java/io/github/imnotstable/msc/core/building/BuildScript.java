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
            block entity data (also world_slice's data lines). Every step \
            takes replace=a,b (change only those; air = empty), seed=N, repeat=N step=DX,DY,DZ.
            fill X,Y,Z X,Y,Z B [hollow|outline|walls]
            set X,Y,Z B
            cylinder X,Y,Z r=R h=H B [hollow] [thick=T] [inner=R]   (X,Y,Z = bottom centre)
            ring X,Y,Z r=R B [h=H] [thick=T]
            disc X,Y,Z r=R B [inner=R]
            sphere|dome X,Y,Z r=R B [hollow] [thick=T]   (dome: X,Y,Z = floor centre)
            line X,Y,Z X,Y,Z B [w=W]
            helix X,Y,Z r=R h=H B [turns=T] [w=W] [start=DEG]   (bottom centre; turns<0 anticlockwise; \
            start=0 north)
            bands X,Y,Z [h=H] R1=B R2=B ... [jitter=J]   (rings to each radius: land, beach, water)
            structure X,Y,Z [X,Y,Z ...] [rotate=90] [face=X,Z] [anchor=center] [air=false] [file=PATH]
              then the text and "end", unless file= (.schem or text). Text: "legend: #=stone g=glass", then per \
            layer "y=N" and rows (z) of symbols (x); "." air, "_" unchanged; "y=N same as y=M", "y=N all #". rotate \
            turns clockwise seen from above; face=X,Z turns each copy's north side (first row) toward X,Z.
            copy X,Y,Z X,Y,Z to=X,Y,Z [structure options]   (reads the world before this call)""";

    private static final Pattern POS = Pattern.compile("-?\\d+,-?\\d+,-?\\d+");
    private static final Pattern OPTION = Pattern.compile("[a-z]+=.*");
    private static final Pattern BAND = Pattern.compile("\\d+=.+");
    private static final Set<String> FLAGS = Set.of("solid", "hollow", "outline", "walls");

    /** Where structures come from: files in the workspace and the world itself. */
    public interface StructureFiles {
        /** A structure file (.schem or text) relative to the workspace, starting at 0,0,0. */
        BlockGrid load(String path, String world) throws java.io.IOException;

        /** The blocks in a region of the world as they are now, with block entity data, at their own coordinates. */
        BlockGrid read(Region region) throws InterruptedException;
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
                    steps.add(stamp(parts, TextGrid.parse(body.toString(), world, new BlockPos(0, 0, 0))));
                    i = j;
                } else {
                    steps.add(step(parts));
                }
            } catch (ToolException e) {
                throw new ToolException("line " + lineNo + " (" + abbreviate(line) + "): " + e.getMessage());
            }
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
        for (char c : line.trim().toCharArray()) {
            if (n.top(c) && Character.isWhitespace(c)) {
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
                if (options.put(t.substring(0, eq), t.substring(eq + 1)) != null) {
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
        if (p.options().containsKey("repeat") && !p.verb().equals("structure") && !p.verb().equals("copy")) {
            return repeated(p);
        }
        Options o = new Options(p);
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
                    blocks.add(BlockPattern.parse(band.substring(eq + 1), o.seed()));
                }
                o.done();
                yield new Step.Bands(world, p.points().get(0), h, radii, blocks, jitter, o.seed(), o.replace());
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
                    + "\"; use fill, set, cylinder, ring, disc, sphere, dome, line, helix, bands, structure or copy");
        };
        if (!p.verb().equals("structure") && !p.verb().equals("copy")) {
            o.done();
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
        private final Set<String> used = new java.util.HashSet<>(Set.of("replace", "seed"));

        Options(Parts p) {
            this.p = p;
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
            return BlockPattern.parse(requireBlock(), seed());
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
