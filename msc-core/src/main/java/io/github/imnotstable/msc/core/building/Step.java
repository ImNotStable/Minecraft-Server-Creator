package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.core.world.BlockNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One part of a build: a fill, a shape, a few blocks or a stamped structure. Steps draw onto a {@link Canvas}. */
public sealed interface Step {
    /** Every block the step may set lies inside this box. */
    Region bounds();

    void draw(Canvas canvas) throws InterruptedException;

    /** A box, solid or as a room: {@code hollow} (shell, air inside), {@code outline} (shell only), {@code walls}. */
    record Fill(Region region, BlockPattern block, String shape, List<String> replace) implements Step {
        public static final List<String> SHAPES = List.of("solid", "hollow", "outline", "walls");

        public Fill {
            if (!SHAPES.contains(shape)) {
                throw new ToolException("unknown fill shape " + shape + "; use one of " + SHAPES);
            }
        }

        @Override
        public Region bounds() {
            return region;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(region, replace);
            BlockPos lo = region.min();
            BlockPos hi = region.max();
            for (int y = lo.y(); y <= hi.y(); y++) {
                for (int z = lo.z(); z <= hi.z(); z++) {
                    for (int x = lo.x(); x <= hi.x(); x++) {
                        boolean side = x == lo.x() || x == hi.x() || z == lo.z() || z == hi.z();
                        boolean shell = side || y == lo.y() || y == hi.y();
                        String state = switch (shape) {
                            case "hollow" -> shell ? block.at(x, y, z) : "minecraft:air";
                            case "outline" -> shell ? block.at(x, y, z) : null;
                            case "walls" -> side ? block.at(x, y, z) : null;
                            default -> block.at(x, y, z);
                        };
                        if (state != null && mask.allows(x, y, z)) {
                            c.set(x, y, z, state);
                        }
                    }
                }
            }
        }
    }

    /** Single blocks. */
    record Set(String world, List<BlockPos> positions, List<String> states, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return Shapes.box(world, positions);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            for (int i = 0; i < positions.size(); i++) {
                BlockPos p = positions.get(i);
                if (mask.allows(p.x(), p.y(), p.z())) {
                    c.set(p.x(), p.y(), p.z(), states.get(i));
                }
            }
        }
    }

    /**
     * An upright cylinder standing on {@code base} (its bottom centre block). {@code inner >= 0} cuts a
     * hole of that radius; {@code thickness > 0} keeps only a wall that thick (a ring or tube).
     */
    record Cylinder(String world, BlockPos base, int radius, int height, int inner, int thickness, BlockPattern block,
                    List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int top = base.y() + Math.max(height, 1) - 1;
            return Region.of(world, base.x() - radius, base.y(), base.z() - radius, base.x() + radius, top, base.z() + radius);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            boolean[] disc = Shapes.disc(radius);
            if (thickness > 0) {
                disc = Shapes.shell2d(disc, radius, thickness);
            }
            if (inner >= 0) {
                boolean[] hole = Shapes.disc(inner);
                int n = 2 * radius + 1;
                int m = 2 * inner + 1;
                for (int dz = -inner; dz <= inner; dz++) {
                    for (int dx = -inner; dx <= inner; dx++) {
                        if (hole[(dz + inner) * m + dx + inner]) {
                            disc[(dz + radius) * n + dx + radius] = false;
                        }
                    }
                }
            }
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int n = 2 * radius + 1;
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = base.x() + dx;
                        int z = base.z() + dz;
                        if (disc[(dz + radius) * n + dx + radius] && mask.allows(x, y, z)) {
                            c.set(x, y, z, block.at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /** A ball around {@code center}, or with {@code dome} its upper half standing on {@code center}'s layer. */
    record Sphere(String world, BlockPos center, int radius, int thickness, boolean dome, BlockPattern block,
                  List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return Region.of(world, center.x() - radius, dome ? center.y() : center.y() - radius, center.z() - radius,
                    center.x() + radius, center.y() + radius, center.z() + radius);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            boolean[] ball = Shapes.ball(radius);
            if (thickness > 0) {
                ball = Shapes.shell3d(ball, radius, thickness);
            }
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int n = 2 * radius + 1;
            for (int dy = dome ? 0 : -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = center.x() + dx;
                        int y = center.y() + dy;
                        int z = center.z() + dz;
                        if (ball[((dy + radius) * n + dz + radius) * n + dx + radius] && mask.allows(x, y, z)) {
                            c.set(x, y, z, block.at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * A straight line from {@code from} to {@code to}, {@code width} blocks across (a round brush). An even width has
     * no middle block, so its brush reaches one block further toward +x, +y and +z than toward -x, -y and -z.
     */
    record Line(String world, BlockPos from, BlockPos to, int width, BlockPattern block, List<String> replace)
            implements Step {
        @Override
        public Region bounds() {
            int lo = Shapes.brushLow(width);
            int hi = width - 1 - lo;
            return Region.of(world, Math.min(from.x(), to.x()) - lo, Math.min(from.y(), to.y()) - lo,
                    Math.min(from.z(), to.z()) - lo, Math.max(from.x(), to.x()) + hi, Math.max(from.y(), to.y()) + hi,
                    Math.max(from.z(), to.z()) + hi);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            // A block reached twice gets the same answer: the pattern depends only on the position, and after the
            // first visit the mask sees either this step's own block there or what it already refused.
            Shapes.sweep(Shapes.line(from, to), width, (x, y, z) -> {
                if (mask.allows(x, y, z)) {
                    c.set(x, y, z, block.at(x, y, z));
                }
            });
        }
    }

    /**
     * A spiral rising from {@code base} (its bottom centre block): {@code turns} full turns over {@code height}
     * layers, clockwise seen from above (counter-clockwise when negative), starting at {@code start} degrees
     * (0 = north, 90 = east). {@code width} blocks across, from the radius inward: a spiral stair's treads.
     */
    record Helix(String world, BlockPos base, int radius, int height, double turns, int width, int start,
                 BlockPattern block, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return Region.of(world, base.x() - radius, base.y(), base.z() - radius, base.x() + radius,
                    base.y() + height - 1, base.z() + radius);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            double arc = Math.abs(turns) * 2 * Math.PI * radius;
            int samples = (int) Math.ceil(Math.max(arc, height) * 4) + 1;
            for (int i = 0; i <= samples; i++) {
                double t = (double) i / samples;
                double angle = Math.toRadians(start) + turns * 2 * Math.PI * t;
                int y = base.y() + Math.min(height - 1, (int) Math.floor(t * height));
                for (int r = radius - width + 1; r <= radius; r++) {
                    int x = base.x() + (int) Math.round(r * Math.sin(angle));
                    int z = base.z() - (int) Math.round(r * Math.cos(angle));
                    if (mask.allows(x, y, z)) {
                        c.set(x, y, z, block.at(x, y, z));
                    }
                }
            }
        }
    }

    /** Several steps drawn in order, such as the copies of one step made by {@code repeat=}. */
    record Group(List<Step> steps) implements Step {
        @Override
        public Region bounds() {
            Region out = null;
            for (Step s : steps) {
                Region b = s.bounds();
                out = out == null ? b : Region.of(b.world(), Math.min(out.min().x(), b.min().x()),
                        Math.min(out.min().y(), b.min().y()), Math.min(out.min().z(), b.min().z()),
                        Math.max(out.max().x(), b.max().x()), Math.max(out.max().y(), b.max().y()),
                        Math.max(out.max().z(), b.max().z()));
            }
            return out;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            c.readAhead(b);
            try {
                for (Step s : steps) {
                    s.draw(c);
                }
            } finally {
                c.endReadAhead(b);
            }
        }
    }

    /**
     * Rings around {@code base} out to each radius in turn, {@code height} blocks tall: an island's
     * grass, then beach, then water. {@code jitter} wobbles every edge by up to that many blocks.
     */
    record Bands(String world, BlockPos base, int height, int[] radii, List<BlockPattern> blocks, int jitter, long seed,
                 List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int r = radii[radii.length - 1] + jitter;
            return Region.of(world, base.x() - r, base.y(), base.z() - r, base.x() + r, base.y() + Math.max(height, 1) - 1,
                    base.z() + r);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int r = radii[radii.length - 1] + jitter;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double angle = Math.atan2(dz, dx);
                    int band = -1;
                    for (int i = 0; i < radii.length; i++) {
                        double edge = radii[i] + 0.5 + jitter * Shapes.wobble(angle, seed + i);
                        if (d < edge) {
                            band = i;
                            break;
                        }
                    }
                    if (band < 0) {
                        continue;
                    }
                    int x = base.x() + dx;
                    int z = base.z() + dz;
                    for (int y = b.min().y(); y <= b.max().y(); y++) {
                        if (mask.allows(x, y, z)) {
                            c.set(x, y, z, blocks.get(band).at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * A structure placed once per position. {@code structure} starts at 0,0,0 and is already turned;
     * {@code -1} cells and, without {@code air}, air cells leave the world as it is.
     */
    record Stamp(String world, List<Placement> placements, boolean air, List<String> replace) implements Step {
        /** One copy: the structure, already turned, with its lowest corner at {@code at}. */
        public record Placement(BlockGrid structure, BlockPos at) {
        }

        public Stamp {
            if (placements.isEmpty()) {
                throw new ToolException("structure has no position");
            }
        }

        @Override
        public Region bounds() {
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Placement p : placements) {
                Region r = p.structure().region();
                lo[0] = Math.min(lo[0], p.at().x());
                lo[1] = Math.min(lo[1], p.at().y());
                lo[2] = Math.min(lo[2], p.at().z());
                hi[0] = Math.max(hi[0], p.at().x() + r.sizeX() - 1);
                hi[1] = Math.max(hi[1], p.at().y() + r.sizeY() - 1);
                hi[2] = Math.max(hi[2], p.at().z() + r.sizeZ() - 1);
            }
            return Region.of(world, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            for (Placement p : placements) {
                BlockGrid g = p.structure();
                Region r = g.region();
                int i = 0;
                for (int y = 0; y < r.sizeY(); y++) {
                    for (int z = 0; z < r.sizeZ(); z++) {
                        for (int x = 0; x < r.sizeX(); x++) {
                            int b = g.blocks()[i++];
                            if (b < 0) {
                                continue;
                            }
                            String state = g.palette().get(b);
                            if (!air && io.github.imnotstable.msc.core.world.BlockNames.isAir(state)) {
                                continue;
                            }
                            int wx = p.at().x() + x;
                            int wy = p.at().y() + y;
                            int wz = p.at().z() + z;
                            if (mask.allows(wx, wy, wz)) {
                                c.set(wx, wy, wz, state);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Land shaped by noise (ADR 0075): every column of {@code region} rises from its bottom to a height the style
     * gives, up to its top. Toward the rim, {@code edge} blocks wide, the land eases to {@code ground} along a wavy
     * outline, so it meets the land around it; {@code edge = 0} fills to the box's sides, for tiling a big area with
     * one seed. Below {@code ground} the land is dug out: water up to {@code water} (at most {@code ground}), air above. The top block is
     * {@code top}, the next {@code depth} are {@code under}, the rest {@code body}; with {@code bed} the floor of water and
     * its shore is that, and other steep columns show {@code cliff}.
     */
    record Terrain(Region region, BlockPattern body, BlockPattern top, BlockPattern under, BlockPattern cliff,
                   BlockPattern bed, String style, int ground, Integer water, int depth, int scale, int edge,
                   int octaves, int steps, long seed, List<String> replace) implements Step {
        public static final List<String> STYLES = List.of("hills", "ridges", "mesa", "dunes");
        /** A column this much higher or lower than a neighbour is a cliff face. */
        static final int STEEP = 3;
        private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        public Terrain {
            if (!STYLES.contains(style)) {
                throw new ToolException("unknown terrain style " + style + "; use one of " + STYLES);
            }
        }

        @Override
        public Region bounds() {
            return region;
        }

        /** The land's height per column, indexed [z][x] over the region. */
        int[] heights() {
            BlockPos lo = region.min();
            BlockPos hi = region.max();
            int sx = region.sizeX();
            int sz = region.sizeZ();
            double cx = (lo.x() + hi.x()) / 2.0;
            double cz = (lo.z() + hi.z()) / 2.0;
            double rx = sx / 2.0;
            double rz = sz / 2.0;
            int[] out = new int[sx * sz];
            for (int z = lo.z(); z <= hi.z(); z++) {
                for (int x = lo.x(); x <= hi.x(); x++) {
                    double level = lo.y() + (hi.y() - lo.y()) * shape(x, z);
                    double f = 1;
                    if (edge > 0) {
                        // Distance inside a wavy ellipse, in blocks: the land fades out over the last edge blocks.
                        double nx = (x - cx) / rx;
                        double nz = (z - cz) / rz;
                        double r = Math.sqrt(nx * nx + nz * nz);
                        double wobble = 0.35 * Noise.fbm(seed + 7, x, z, Math.max(8, edge * 1.5), 3);
                        f = Noise.smoothstep(0, edge, (1 - r + wobble) * Math.min(rx, rz));
                    }
                    out[(z - lo.z()) * sx + x - lo.x()] = (int) Math.round(ground + (level - ground) * f);
                }
            }
            return out;
        }

        /** The style's height at a column, 0 (the box's bottom) to 1 (its top). */
        double shape(int x, int z) {
            return switch (style) {
                case "ridges" -> Math.clamp(Noise.ridged(seed, x, z, scale, octaves) * 1.15 - 0.1, 0, 1);
                case "mesa" -> {
                    double s = Math.clamp(0.5 + 0.8 * Noise.fbm(seed, x, z, scale, octaves), 0, 1) * steps;
                    double floor = Math.floor(s);
                    yield Math.min(1, (floor + Noise.smoothstep(0.8, 1, s - floor)) / steps);
                }
                case "dunes" -> {
                    double angle = Noise.unit(Noise.hash(seed, 1, 2)) * Math.PI;
                    double along = x * Math.cos(angle) + z * Math.sin(angle);
                    double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * along / scale
                            + 4 * Noise.fbm(seed + 3, x, z, scale * 2.0, 2));
                    yield Math.clamp(0.75 * wave * wave + 0.25 * (0.5 + 0.5 * Noise.fbm(seed, x, z, scale, octaves)), 0, 1);
                }
                default -> Math.clamp(0.5 + 0.75 * Noise.fbm(seed, x, z, scale, octaves), 0, 1);
            };
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            BlockPos lo = region.min();
            BlockPos hi = region.max();
            int sx = region.sizeX();
            int[] h = heights();
            Canvas.Mask mask = c.mask(region, replace);
            for (int z = lo.z(); z <= hi.z(); z++) {
                for (int x = lo.x(); x <= hi.x(); x++) {
                    int t = h[(z - lo.z()) * sx + x - lo.x()];
                    int slope = 0;
                    int low = t;
                    for (int[] d : SIDES) {
                        int nx = x + d[0];
                        int nz = z + d[1];
                        if (nx >= lo.x() && nx <= hi.x() && nz >= lo.z() && nz <= hi.z()) {
                            int n = h[(nz - lo.z()) * sx + nx - lo.x()];
                            slope = Math.max(slope, Math.abs(t - n));
                            low = Math.min(low, n);
                        }
                    }
                    boolean steep = slope >= STEEP;
                    // Under water, or one above it beside water: the shore.
                    boolean wet = bed != null && water != null && (t <= water || t == water + 1 && low <= water);
                    for (int y = lo.y(); y <= hi.y(); y++) {
                        String state;
                        if (y <= t) {
                            int below = t - y;
                            BlockPattern p = below == 0 ? (wet ? bed : steep ? cliff : top)
                                    : below <= depth ? (wet ? bed : steep ? cliff : under) : body;
                            state = mask.allows(x, y, z) ? p.at(x, y, z) : null;
                        } else if (y <= ground) {
                            state = water != null && y <= water ? "minecraft:water" : "minecraft:air";
                        } else {
                            continue;
                        }
                        c.set(x, y, z, state);
                    }
                }
            }
        }
    }

    /**
     * A winding way along the ground through {@code points} (ADR 0075): a smooth curve through each point, pushed up
     * to {@code wander} blocks aside by noise, {@code width} blocks across with ragged edges. Each column's top ground
     * block (under plants, leaves and trunks; the search looks 16 above and below the points) becomes the block, and
     * plants on it are cleared. With {@code depth} the way is dug that deep in the middle, a bowl filled with the
     * block (water for a stream) over {@code bed}.
     */
    record Path(String world, List<BlockPos> points, int width, int wander, int depth, BlockPattern block,
                BlockPattern bed, long seed, List<String> replace) implements Step {
        static final int SEARCH = 16;

        @Override
        public Region bounds() {
            int reach = width / 2 + wander + 2;
            Region box = Shapes.box(world, points);
            return Region.of(world, box.min().x() - reach, box.min().y() - SEARCH - depth - 1, box.min().z() - reach,
                    box.max().x() + reach, box.max().y() + SEARCH, box.max().z() + reach);
        }

        /** The centre line as x, z, distance along: a Catmull-Rom curve through the points, pushed aside by noise. */
        List<double[]> centre() {
            List<double[]> out = new ArrayList<>();
            double travelled = 0;
            double[] last = null;
            for (int i = 0; i + 1 < points.size(); i++) {
                BlockPos p0 = points.get(Math.max(0, i - 1));
                BlockPos p1 = points.get(i);
                BlockPos p2 = points.get(i + 1);
                BlockPos p3 = points.get(Math.min(points.size() - 1, i + 2));
                double length = Math.hypot(p2.x() - p1.x(), p2.z() - p1.z());
                int samples = Math.max(2, (int) Math.ceil(length * 2));
                for (int k = i == 0 ? 0 : 1; k <= samples; k++) {
                    double t = (double) k / samples;
                    double x = spline(p0.x(), p1.x(), p2.x(), p3.x(), t) + 0.5;
                    double z = spline(p0.z(), p1.z(), p2.z(), p3.z(), t) + 0.5;
                    if (last != null) {
                        travelled += Math.hypot(x - last[0], z - last[1]);
                    }
                    double[] here = {x, z, travelled};
                    out.add(here);
                    last = here;
                }
            }
            if (wander > 0 && last != null) {
                double total = last[2];
                double[][] pushed = new double[out.size()][];
                for (int i = 0; i < out.size(); i++) {
                    double[] a = out.get(Math.max(0, i - 1));
                    double[] b = out.get(Math.min(out.size() - 1, i + 1));
                    double[] p = out.get(i);
                    double dx = b[0] - a[0];
                    double dz = b[1] - a[1];
                    double len = Math.hypot(dx, dz);
                    // Fade the push in and out so the way still meets its two ends.
                    double ends = Math.min(1, Math.min(p[2], total - p[2]) / Math.max(4, wander * 2.0));
                    double push = len == 0 ? 0 : wander * ends * Noise.fbm(seed + 11, p[2], 0.5, Math.max(12, wander * 6.0), 2);
                    pushed[i] = new double[] {p[0] - (len == 0 ? 0 : dz / len) * push, p[1] + (len == 0 ? 0 : dx / len) * push, p[2]};
                }
                out = new ArrayList<>(List.of(pushed));
            }
            return out;
        }

        private static double spline(double p0, double p1, double p2, double p3, double t) {
            double t2 = t * t;
            double t3 = t2 * t;
            return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
        }

        /** Each column the way covers, with its distance from the centre line. */
        Map<Long, Double> columns() {
            double radius = width / 2.0;
            int reach = (int) Math.ceil(radius) + 1;
            Map<Long, Double> out = new HashMap<>();
            for (double[] p : centre()) {
                int px = (int) Math.floor(p[0]);
                int pz = (int) Math.floor(p[1]);
                for (int dz = -reach; dz <= reach; dz++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        int x = px + dx;
                        int z = pz + dz;
                        double d = Math.hypot(x + 0.5 - p[0], z + 0.5 - p[1]);
                        if (d <= radius) {
                            out.merge(((long) x << 32) ^ (z & 0xffffffffL), d, Math::min);
                        }
                    }
                }
            }
            return out;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            Canvas.Lookup at = c.current(b);
            Canvas.Mask mask = c.mask(b, replace);
            double radius = Math.max(width / 2.0, 0.5);
            for (var e : columns().entrySet()) {
                int x = (int) (e.getKey() >> 32);
                int z = (int) (long) e.getKey();
                double d = e.getValue();
                // The outer rim is ragged: some of its columns stay as they were.
                if (width > 1 && d > radius - 0.75 && Noise.unit(Noise.hash(seed + 5, x, z)) < 0.45) {
                    continue;
                }
                int y = Surface.top(at, x, z, b.max().y(), b.min().y() + depth + 1);
                if (y == Surface.NONE || Surface.fluid(at.at(x, y, z))) {
                    continue;
                }
                for (int up = y + 1; up <= Math.min(y + 2, b.max().y()); up++) {
                    String above = at.at(x, up, z);
                    if (!BlockNames.isAir(above) && Surface.plant(above) && mask.allows(x, up, z)) {
                        c.set(x, up, z, "minecraft:air");
                    }
                }
                double share = d / radius;
                int dig = (int) Math.round(depth * (1 - share * share));
                for (int k = 0; k < Math.max(dig, 1); k++) {
                    if (mask.allows(x, y - k, z)) {
                        c.set(x, y - k, z, block.at(x, y - k, z));
                    }
                }
                if (dig > 0 && bed != null && mask.allows(x, y - dig, z)) {
                    c.set(x, y - dig, z, bed.at(x, y - dig, z));
                }
            }
        }
    }

    /**
     * Things set on the ground across {@code region} (ADR 0075): one per cell of a {@code spacing} grid, at a random
     * spot in the cell, kept with {@code chance} percent. Each stands on the column's top ground block between the
     * region's top and bottom (only on {@code on} blocks when given): a block from the mix, or one of the structures,
     * centred, turned a random quarter and sunk {@code sink} blocks. Air in a structure leaves the world as it is.
     */
    record Scatter(Region region, BlockPattern block, List<BlockGrid> structures, int spacing, int chance,
                   List<String> on, int sink, long seed, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int wide = 0;
            int tall = 1;
            for (BlockGrid g : structures) {
                wide = Math.max(wide, Math.max(g.region().sizeX(), g.region().sizeZ()));
                tall = Math.max(tall, g.region().sizeY());
            }
            int reach = (wide + 1) / 2;
            return Region.of(region.world(), region.min().x() - reach, region.min().y() - sink, region.min().z() - reach,
                    region.max().x() + reach, region.max().y() + tall, region.max().z() + reach);
        }

        /** The chosen spots as x, z and a hash for the pick and the turn. */
        List<long[]> spots() {
            return Nature.spots(region, spacing, chance, seed);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            Canvas.Lookup at = c.current(b);
            Canvas.Mask mask = c.mask(b, replace);
            List<String> ground = on.stream().map(BlockNames::full).toList();
            Map<Long, BlockGrid> turned = new HashMap<>();
            for (long[] s : spots()) {
                int x = (int) s[0];
                int z = (int) s[1];
                int y = Surface.top(at, x, z, region.max().y(), region.min().y());
                if (y == Surface.NONE) {
                    continue;
                }
                String surface = at.at(x, y, z);
                if (Surface.fluid(surface) || !ground.isEmpty() && !Canvas.matches(surface, ground)) {
                    continue;
                }
                if (structures.isEmpty()) {
                    if (BlockNames.isAir(at.at(x, y + 1, z)) && mask.allows(x, y + 1, z)) {
                        c.set(x, y + 1, z, block.at(x, y + 1, z));
                    }
                    continue;
                }
                int pick = (int) Long.remainderUnsigned(s[2], structures.size());
                int quarter = (int) Long.remainderUnsigned(s[2] >>> 8, 4);
                BlockGrid g = turned.computeIfAbsent(pick * 4L + quarter, k -> Turn.grid(structures.get(pick), quarter));
                Region r = g.region();
                int ox = x - r.sizeX() / 2;
                int oy = y + 1 - sink;
                int oz = z - r.sizeZ() / 2;
                int i = 0;
                for (int dy = 0; dy < r.sizeY(); dy++) {
                    for (int dz = 0; dz < r.sizeZ(); dz++) {
                        for (int dx = 0; dx < r.sizeX(); dx++) {
                            int id = g.blocks()[i++];
                            if (id >= 0 && !BlockNames.isAir(g.palette().get(id)) && mask.allows(ox + dx, oy + dy, oz + dz)) {
                                c.set(ox + dx, oy + dy, oz + dz, g.palette().get(id));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A ball or dome whose surface is pushed in and out by up to {@code rough} blocks of smooth noise (ADR 0077): a
     * boulder, a leaf canopy, a cave mouth, a hedge; never a perfect sphere.
     */
    record Rough(String world, BlockPos center, int radius, int thickness, boolean dome, int rough, long seed,
                 BlockPattern block, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int r = radius + rough;
            return Region.of(world, center.x() - r, dome ? center.y() : center.y() - r, center.z() - r,
                    center.x() + r, center.y() + r, center.z() + r);
        }

        boolean inside(int dx, int dy, int dz) {
            double scale = Math.max(2.5, radius / 1.6);
            double edge = radius + 0.5 + rough * Noise.value3(seed, (center.x() + dx) / scale, (center.y() + dy) / scale,
                    (center.z() + dz) / scale);
            return dx * dx + dy * dy + dz * dz <= edge * edge;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int r = radius + rough;
            for (int dy = dome ? 0 : -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (!inside(dx, dy, dz)) {
                            continue;
                        }
                        if (thickness > 0 && inside(dx + thickness, dy, dz) && inside(dx - thickness, dy, dz)
                                && inside(dx, dy, dz + thickness) && inside(dx, dy, dz - thickness)
                                && inside(dx, dy + thickness, dz) && (dome && dy - thickness < 0 || inside(dx, dy - thickness, dz))) {
                            continue;
                        }
                        int x = center.x() + dx;
                        int y = center.y() + dy;
                        int z = center.z() + dz;
                        if (mask.allows(x, y, z)) {
                            c.set(x, y, z, block.at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * A pitched roof over the walls in {@code box} (ADR 0077): its rows start at the box's bottom y, the eave, and
     * reach {@code overhang} past the walls; stairs face up the slope and their corners join, slabs alternate halves
     * for a low pitch, full blocks step. {@code gable} closes the ends in the walls' planes; {@code ridge} tops an odd
     * span; a box taller than one layer caps the roof flat at its top y.
     */
    record Roof(Region box, BlockPattern block, String style, int axis, int high, int pitch, int overhang,
                BlockPattern gable, BlockPattern ridge, List<String> replace) implements Step {
        public static final List<String> STYLES = List.of("gable", "hip", "shed");

        public Roof {
            if (!STYLES.contains(style)) {
                throw new ToolException("unknown roof style " + style + "; use one of " + STYLES);
            }
        }

        @Override
        public Region bounds() {
            int top = box.min().y() + Detail.roofHeight(this);
            if (box.max().y() > box.min().y()) {
                top = Math.min(top, box.max().y());
            }
            return Region.of(box.world(), box.min().x() - overhang, box.min().y(), box.min().z() - overhang,
                    box.max().x() + overhang, top, box.max().z() + overhang);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Detail.roof(c, this);
        }
    }

    /**
     * Detail along the walls already in {@code box} (ADR 0077), outside faces only (those facing away from the box's
     * middle): {@code top} a cornice, {@code base} a plinth, {@code band} a belt course at {@code level}, {@code out}
     * blocks deep; {@code corners} replaces outer corner columns; at window and door holes {@code sills},
     * {@code lintels}, {@code shutters} and {@code frames}. Stairs, slabs and trapdoors are turned to fit.
     */
    record Trim(Region box, BlockPattern block, String at, int level, int out, List<String> replace) implements Step {
        public static final List<String> AT = List.of("top", "base", "band", "corners", "sills", "lintels", "shutters",
                "frames");

        public Trim {
            if (!AT.contains(at)) {
                throw new ToolException("unknown trim at=" + at + "; use one of " + AT);
            }
        }

        @Override
        public Region bounds() {
            return Region.of(box.world(), box.min().x() - out, box.min().y() - 1, box.min().z() - out,
                    box.max().x() + out, box.max().y() + 1, box.max().z() + out);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Detail.trim(c, this);
        }
    }

    /**
     * Things set where they belong on what is already in {@code box} (ADR 0077): {@code on} top (any upward face),
     * ground (an upward face open to the sky), floor (under a roof), edge (a floor beside a wall), corner, wall
     * (hanging down a wall face from its top) or ceiling (hanging down); {@code chance} percent of the spots, in patches with clump, or one per {@code spacing}
     * cell; each {@code minLength} to {@code maxLength} blocks long (up from floors, down from walls and ceilings),
     * ending in {@code tip}.
     */
    record Decorate(Region box, BlockPattern block, BlockPattern tip, String on, int chance, int spacing,
                    int minLength, int maxLength, int clump, List<String> soil, long seed, List<String> replace)
            implements Step {
        public static final List<String> ON = List.of("top", "ground", "floor", "edge", "corner", "wall", "ceiling");

        public Decorate {
            if (!ON.contains(on)) {
                throw new ToolException("unknown decorate on=" + on + "; use one of " + ON);
            }
        }

        @Override
        public Region bounds() {
            boolean down = on.equals("wall") || on.equals("ceiling");
            return Region.of(box.world(), box.min().x(), box.min().y() - (down ? maxLength : 0), box.min().z(),
                    box.max().x(), box.max().y() + (down ? 0 : maxLength), box.max().z());
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Detail.decorate(c, this);
        }
    }

    /**
     * Age on what is already in {@code box} (ADR 0077), where weather would put it: moss low down and on ledges, cracks
     * on corners and edges, verdigris on exposed copper, {@code swaps} (from id to id) by exposure, all in patches;
     * {@code breakage} percent knocks blocks off exposed tops and edges, leaving {@code chip} (or air).
     */
    record Weather(Region box, int amount, Map<String, String> swaps, int breakage, BlockPattern chip, long seed,
                   List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Detail.weather(c, this);
        }
    }

    /**
     * One tree, bush or huge fungus standing on {@code base}, its lowest trunk block (ADR 0079): {@code kind} picks the
     * silhouette, {@code size} its scale, {@code height} 0 a height for both; {@code log} and {@code leaves} null keep
     * the kind's own blocks.
     */
    record Tree(String world, BlockPos base, String kind, String size, int height, BlockPattern log, BlockPattern leaves,
                long seed, List<String> replace) implements Step {
        List<Nature.Part> parts() {
            return Nature.grow(kind, base, size, height, log, leaves, seed);
        }

        @Override
        public Region bounds() {
            return Nature.box(world, parts());
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            Nature.place(c, parts(), c.current(b), c.mask(b, replace));
        }
    }

    /**
     * Trees on the ground across {@code region} (ADR 0079): one per {@code spacing} cell kept with {@code chance}, each
     * of a kind picked by weight and grown from that spot's seed, on the column's top ground (only on {@code on} blocks
     * when given). {@code size} mixed picks small, medium or large per tree.
     */
    record Forest(Region region, List<String> kinds, int[] weights, String size, BlockPattern log, BlockPattern leaves,
                  int spacing, int chance, List<String> on, long seed, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int[] reach = Nature.reach(size.equals("mixed") ? "large" : size);
            return Region.of(region.world(), region.min().x() - reach[0], region.min().y() - 4,
                    region.min().z() - reach[0], region.max().x() + reach[0], region.max().y() + reach[1],
                    region.max().z() + reach[0]);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Nature.forest(c, this);
        }
    }

    /** The ground in {@code box} smoothed (ADR 0080): column heights averaged over {@code radius}, {@code strength} times. */
    record Smooth(Region box, int radius, int strength, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.smooth(c, this);
        }
    }

    /** Edges melted, pits filled or both (ADR 0080), {@code iterations} times; see {@link Brushes#erode}. */
    record Erode(Region box, String mode, int faces, int iterations, List<String> replace) implements Step {
        public static final List<String> MODES = List.of("melt", "fill", "smooth");

        public Erode {
            if (!MODES.contains(mode)) {
                throw new ToolException("unknown erode mode=" + mode + "; use one of " + MODES);
            }
        }

        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.erode(c, this);
        }
    }

    /** Every block the most common of its neighbours (ADR 0080); {@code shape} false blends only materials. */
    record Blend(Region box, boolean shape, int iterations, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.blend(c, this);
        }
    }

    /** The exposed skin of what is in {@code box} painted with {@code block} (ADR 0080); see {@link Brushes#paint}. */
    record Paint(Region box, BlockPattern block, String faces, int depth, int minSlope, int maxSlope, int chance,
                 long seed, List<String> replace) implements Step {
        public static final List<String> FACES = List.of("top", "side", "all");

        public Paint {
            if (!FACES.contains(faces)) {
                throw new ToolException("unknown paint faces=" + faces + "; use one of " + FACES);
            }
        }

        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.paint(c, this);
        }
    }

    /** A river through {@code points} with a level water surface and sloped banks (ADR 0080); see {@link Brushes#river}. */
    record River(String world, List<BlockPos> points, int width, int wander, int depth, int bank, double slope,
                 BlockPattern water, BlockPattern bed, BlockPattern shore, long seed, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int reach = width / 2 + bank + wander + 2;
            Region box = Shapes.box(world, points);
            return Region.of(world, box.min().x() - reach, box.min().y() - Path.SEARCH - depth - 1, box.min().z() - reach,
                    box.max().x() + reach, box.max().y() + Path.SEARCH, box.max().z() + reach);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.river(c, this);
        }
    }

    /** A winding tunnel through 3D {@code points}, about {@code radius} round (ADR 0080). */
    record Tunnel(String world, List<BlockPos> points, int radius, int rough, BlockPattern block, long seed,
                  List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int reach = (int) Math.ceil(radius * 1.3) + rough + 1;
            Region box = Shapes.box(world, points);
            return Region.of(world, box.min().x() - reach, box.min().y() - reach, box.min().z() - reach,
                    box.max().x() + reach, box.max().y() + reach, box.max().z() + reach);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.tunnel(c, this);
        }
    }

    /** Noise caves through the solid ground of {@code box} (ADR 0080); see {@link Brushes#caves}. */
    record Caves(Region box, int density, int scale, int roof, BlockPattern block, long seed, List<String> replace)
            implements Step {
        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Brushes.caves(c, this);
        }
    }

    /** Entities summoned at a block's centre once the blocks are written (ADR 0081), tagged so build_undo removes them. */
    record Summon(String world, BlockPos at, String type, String nbt, int count, double dy) implements Step {
        @Override
        public Region bounds() {
            return Region.of(world, at.x(), at.y(), at.z(), at.x(), at.y(), at.z());
        }

        @Override
        public void draw(Canvas c) {
            for (int i = 0; i < count; i++) {
                c.command(Things.summon(world, type, at.x() + 0.5, at.y() + dy, at.z() + 0.5, nbt, c.tag()));
            }
        }
    }

    /** A biome painted over {@code box} with /fillbiome (ADR 0081); {@code only} limits it to one biome there. */
    record Biome(Region box, String biome, String only) implements Step {
        @Override
        public Region bounds() {
            return Region.of(box.world(), box.min().x(), box.min().y(), box.min().z(), box.min().x(), box.min().y(),
                    box.min().z());
        }

        @Override
        public void draw(Canvas c) {
            for (String command : Things.biome(box, biome, only)) {
                c.command(command);
            }
        }
    }

    /** The game's own configured feature placed with /place feature (ADR 0081), {@code count} times on the ground. */
    record Feature(String world, BlockPos at, String feature, int count, int spread, long seed) implements Step {
        @Override
        public Region bounds() {
            return Region.of(world, at.x() - spread, at.y() - 16, at.z() - spread, at.x() + spread, at.y() + 16,
                    at.z() + spread);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Lookup ground = spread == 0 ? null : c.current(bounds());
            for (BlockPos p : Things.featureSpots(ground, this)) {
                c.command("execute in " + Things.dimension(world) + " run place feature " + feature + " " + p.x() + " "
                        + p.y() + " " + p.z());
            }
        }
    }

    /** Lights where it is dark in {@code box} (ADR 0081); see {@link Things#light}. */
    record Light(Region box, BlockPattern block, String on, int level, long seed, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return box;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Things.light(c, this);
        }
    }
}
