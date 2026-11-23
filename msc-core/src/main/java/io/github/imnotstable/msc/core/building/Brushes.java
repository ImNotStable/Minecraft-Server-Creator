package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Organic shaping of what is already there (ADR 0080), the ideas of a terraformer's brushes worked out on the canvas:
 * smooth a heightmap, melt or fill edges, blend materials and shapes, paint surfaces by slope, carve rivers that keep a
 * level, tunnels and caves. Each reads the world and the earlier steps, so it works on terrain and on builds alike.
 */
final class Brushes {
    private static final String AIR = "minecraft:air";

    private Brushes() {
    }

    /** Whether a block counts as empty for shaping: air, plants and fluids. */
    private static boolean open(String state) {
        return BlockNames.isAir(state) || Surface.plant(state) || Surface.fluid(state);
    }

    /** The column tops in {@code box}, x-major, {@link Surface#NONE} where none. */
    private static int[] heights(Canvas.Lookup at, Region box) {
        int sx = box.sizeX();
        int[] h = new int[sx * box.sizeZ()];
        for (int z = 0; z < box.sizeZ(); z++) {
            for (int x = 0; x < sx; x++) {
                h[z * sx + x] = Surface.top(at, box.min().x() + x, box.min().z() + z, box.max().y(), box.min().y());
            }
        }
        return h;
    }

    // ------------------------------------------------------------------ smooth

    static void smooth(Canvas c, Step.Smooth s) throws InterruptedException {
        Region box = s.box();
        Canvas.Lookup at = c.current(box);
        Canvas.Mask mask = c.mask(box, s.replace());
        int sx = box.sizeX();
        int sz = box.sizeZ();
        int[] old = heights(at, box);
        double[] h = new double[old.length];
        for (int i = 0; i < old.length; i++) {
            h[i] = old[i];
        }
        for (int pass = 0; pass < s.strength(); pass++) {
            double[] next = h.clone();
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (old[z * sx + x] == Surface.NONE) {
                        continue;
                    }
                    double sum = 0;
                    double weight = 0;
                    for (int dz = -s.radius(); dz <= s.radius(); dz++) {
                        for (int dx = -s.radius(); dx <= s.radius(); dx++) {
                            int nx = x + dx;
                            int nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= sx || nz >= sz || old[nz * sx + nx] == Surface.NONE) {
                                continue;
                            }
                            double w = 1.0 / (1 + dx * dx + dz * dz);
                            sum += h[nz * sx + nx] * w;
                            weight += w;
                        }
                    }
                    next[z * sx + x] = sum / weight;
                }
            }
            h = next;
        }
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int was = old[z * sx + x];
                if (was == Surface.NONE) {
                    continue;
                }
                // The box's rim eases back to the land around it.
                double rim = Math.min(Math.min(x, sx - 1 - x), Math.min(z, sz - 1 - z)) / (double) Math.max(1, s.radius());
                double blend = Math.clamp(rim, 0, 1);
                int now = (int) Math.round(was + (h[z * sx + x] - was) * blend);
                now = Math.clamp(now, box.min().y(), box.max().y());
                reshape(c, at, mask, box.min().x() + x, box.min().z() + z, was, now);
            }
        }
    }

    /** Raise or lower one column's ground from {@code was} to {@code now}, keeping its top block on top. */
    private static void reshape(Canvas c, Canvas.Lookup at, Canvas.Mask mask, int x, int z, int was, int now) {
        if (now == was) {
            return;
        }
        String top = at.at(x, was, z);
        if (Surface.fluid(top)) {
            return;
        }
        String under = at.at(x, was - 1, z);
        String fill = open(under) ? top : under;
        if (now > was) {
            for (int y = was; y < now; y++) {
                if (mask.allows(x, y, z)) {
                    c.set(x, y, z, fill);
                }
            }
            if (mask.allows(x, now, z)) {
                c.set(x, now, z, top);
            }
            // A plant on the old top would now be buried; one above the new top stays.
        } else {
            for (int y = now + 1; y <= was + 2; y++) {
                String here = at.at(x, y, z);
                if ((y <= was || Surface.plant(here)) && mask.allows(x, y, z)) {
                    c.set(x, y, z, AIR);
                }
            }
            if (mask.allows(x, now, z)) {
                c.set(x, now, z, top);
            }
        }
    }

    // ------------------------------------------------------------------ erode, fill, blend

    /** A copy of the box as it is now, padded by one so neighbours can be read. */
    private static final class Grid {
        final Region box;
        final int sx;
        final int sy;
        final int sz;
        final String[] cells;

        Grid(Canvas.Lookup at, Region box) {
            this.box = box;
            sx = box.sizeX() + 2;
            sy = box.sizeY() + 2;
            sz = box.sizeZ() + 2;
            cells = new String[sx * sy * sz];
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        cells[i(x, y, z)] = at.at(box.min().x() + x - 1, box.min().y() + y - 1, box.min().z() + z - 1);
                    }
                }
            }
        }

        int i(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * melt: solid blocks with at least {@code faces} open sides go (spikes, single blocks, sharp edges); fill: open
     * blocks with at least {@code faces} solid sides take their neighbours' most common block (pits, notches);
     * smooth: a melt pass then a fill pass, each time.
     */
    static void erode(Canvas c, Step.Erode e) throws InterruptedException {
        Region box = e.box();
        Canvas.Lookup at = c.current(Detail.grow(box, 1));
        Canvas.Mask mask = c.mask(box, e.replace());
        Grid g = new Grid(at, box);
        String[] start = g.cells.clone();
        for (int pass = 0; pass < e.iterations(); pass++) {
            if (!e.mode().equals("fill")) {
                step(g, true, e.faces());
            }
            if (!e.mode().equals("melt")) {
                step(g, false, e.mode().equals("fill") ? e.faces() : Math.min(6, e.faces() + 1));
            }
        }
        write(c, g, start, mask);
    }

    private static void step(Grid g, boolean melt, int faces) {
        String[] next = g.cells.clone();
        for (int y = 1; y < g.sy - 1; y++) {
            for (int z = 1; z < g.sz - 1; z++) {
                for (int x = 1; x < g.sx - 1; x++) {
                    String here = g.cells[g.i(x, y, z)];
                    if (melt == open(here)) {
                        continue;
                    }
                    int count = 0;
                    Map<String, Integer> around = new HashMap<>();
                    for (int[] f : FACES) {
                        String n = g.cells[g.i(x + f[0], y + f[1], z + f[2])];
                        if (open(n) == melt) {
                            count++;
                        }
                        if (!melt && !open(n)) {
                            around.merge(n, f[1] == -1 ? 2 : 1, Integer::sum); // the block below counts double
                        }
                    }
                    if (count >= faces) {
                        next[g.i(x, y, z)] = melt ? AIR : most(around);
                    }
                }
            }
        }
        System.arraycopy(next, 0, g.cells, 0, next.length);
    }

    private static String most(Map<String, Integer> counts) {
        String best = AIR;
        int n = -1;
        for (var e : counts.entrySet()) {
            if (e.getValue() > n || e.getValue() == n && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    private static void write(Canvas c, Grid g, String[] start, Canvas.Mask mask) {
        for (int y = 1; y < g.sy - 1; y++) {
            for (int z = 1; z < g.sz - 1; z++) {
                for (int x = 1; x < g.sx - 1; x++) {
                    int i = g.i(x, y, z);
                    if (!g.cells[i].equals(start[i])) {
                        int wx = g.box.min().x() + x - 1;
                        int wy = g.box.min().y() + y - 1;
                        int wz = g.box.min().z() + z - 1;
                        if (mask.allows(wx, wy, wz)) {
                            c.set(wx, wy, wz, g.cells[i]);
                        }
                    }
                }
            }
        }
    }

    /**
     * Each block takes the most common block of its 3x3x3 neighbourhood: seams between materials soften and lumps
     * round off. {@code shape} false keeps every open block open and every solid one solid, blending only materials.
     */
    static void blend(Canvas c, Step.Blend b) throws InterruptedException {
        Region box = b.box();
        Canvas.Lookup at = c.current(Detail.grow(box, 1));
        Canvas.Mask mask = c.mask(box, b.replace());
        Grid g = new Grid(at, box);
        String[] start = g.cells.clone();
        for (int pass = 0; pass < b.iterations(); pass++) {
            String[] next = g.cells.clone();
            for (int y = 1; y < g.sy - 1; y++) {
                for (int z = 1; z < g.sz - 1; z++) {
                    for (int x = 1; x < g.sx - 1; x++) {
                        String here = g.cells[g.i(x, y, z)];
                        if (!b.shape() && open(here)) {
                            continue;
                        }
                        Map<String, Integer> counts = new HashMap<>();
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    String n = g.cells[g.i(x + dx, y + dy, z + dz)];
                                    if (!b.shape() && open(n)) {
                                        continue;
                                    }
                                    counts.merge(open(n) ? AIR : n, 1, Integer::sum);
                                }
                            }
                        }
                        String pick = most(counts);
                        // A tie with the block itself keeps it, so flat areas do not churn.
                        if (counts.getOrDefault(open(here) ? AIR : here, 0) < counts.get(pick)) {
                            next[g.i(x, y, z)] = pick;
                        }
                    }
                }
            }
            System.arraycopy(next, 0, g.cells, 0, next.length);
        }
        write(c, g, start, mask);
    }

    // ------------------------------------------------------------------ paint

    /**
     * Paint the exposed skin of what is in the box: top faces (open above), side faces (open beside) or both, down
     * {@code depth} blocks, where the ground's slope lies between {@code minSlope} and {@code maxSlope} degrees, kept
     * with {@code chance} percent (in patches with the block's clump).
     */
    static void paint(Canvas c, Step.Paint p) throws InterruptedException {
        Region box = p.box();
        Canvas.Lookup at = c.current(Detail.grow(box, 1));
        Canvas.Mask mask = c.mask(box, p.replace());
        boolean sloped = p.minSlope() > 0 || p.maxSlope() < 90;
        int[] h = sloped ? heights(at, Detail.grow(box, 1)) : null;
        int hx = box.sizeX() + 2;
        List<int[]> skin = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (open(at.at(x, y, z))) {
                        continue;
                    }
                    boolean top = open(at.at(x, y + 1, z));
                    boolean side = open(at.at(x + 1, y, z)) || open(at.at(x - 1, y, z)) || open(at.at(x, y, z + 1))
                            || open(at.at(x, y, z - 1));
                    boolean face = switch (p.faces()) {
                        case "top" -> top;
                        case "side" -> side && !top;
                        default -> top || side;
                    };
                    if (!face) {
                        continue;
                    }
                    if (sloped) {
                        int ix = x - box.min().x() + 1;
                        int iz = z - box.min().z() + 1;
                        int here = h[iz * hx + ix];
                        int steep = 0;
                        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int n = h[(iz + d[1]) * hx + ix + d[0]];
                            if (n != Surface.NONE && here != Surface.NONE) {
                                steep = Math.max(steep, Math.abs(n - here));
                            }
                        }
                        double slope = Math.toDegrees(Math.atan(steep));
                        if (slope < p.minSlope() || slope > p.maxSlope()) {
                            continue;
                        }
                    }
                    if (p.chance() < 100 && Noise.unit(Noise.hash(p.seed() + 41, x * 31L + y, z)) * 100 >= p.chance()) {
                        continue;
                    }
                    skin.add(new int[] {x, y, z});
                }
            }
        }
        for (int[] s : skin) {
            for (int k = 0; k < p.depth(); k++) {
                int y = s[1] - k;
                if (y < box.min().y() || open(at.at(s[0], y, s[2]))) {
                    break;
                }
                if (mask.allows(s[0], y, s[2])) {
                    c.set(s[0], y, s[2], p.block().at(s[0], y, s[2]));
                }
            }
        }
    }

    // ------------------------------------------------------------------ river

    /**
     * A river along {@code points}: a channel {@code width} across, its water held at one level that only falls
     * downstream (never a staircase of water), {@code depth} deep in the middle over {@code bed}, the land beside it
     * sloping down over {@code bank} blocks to just above the water, topped with {@code shore}.
     */
    static void river(Canvas c, Step.River r) throws InterruptedException {
        Region b = r.bounds();
        Canvas.Lookup at = c.current(b);
        Canvas.Mask mask = c.mask(b, r.replace());
        Step.Path line = new Step.Path(r.world(), r.points(), r.width(), r.wander(), 0, r.water(), null, r.seed(),
                List.of());
        List<double[]> centre = line.centre();
        int top = b.max().y();
        int bottom = b.min().y();
        double[] level = new double[centre.size()];
        double run = Double.MAX_VALUE;
        for (int i = 0; i < centre.size(); i++) {
            double[] p = centre.get(i);
            int ground = Surface.top(at, (int) Math.floor(p[0]), (int) Math.floor(p[1]), top, bottom);
            if (ground != Surface.NONE) {
                run = Math.min(run, ground - 1);
            }
            level[i] = run;
        }
        double fallback = run == Double.MAX_VALUE ? r.points().get(0).y() - 1 : run;
        for (int i = 0; i < level.length; i++) {
            if (level[i] == Double.MAX_VALUE) {
                level[i] = fallback;
            }
        }
        double radius = r.width() / 2.0;
        double outer = radius + r.bank();
        int reach = (int) Math.ceil(outer) + 1;
        Map<Long, double[]> near = new HashMap<>(); // column -> {distance, level}
        for (int i = 0; i < centre.size(); i++) {
            double[] p = centre.get(i);
            int px = (int) Math.floor(p[0]);
            int pz = (int) Math.floor(p[1]);
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    double d = Math.hypot(px + dx + 0.5 - p[0], pz + dz + 0.5 - p[1]);
                    if (d > outer) {
                        continue;
                    }
                    long key = ((long) (px + dx) << 32) ^ ((pz + dz) & 0xffffffffL);
                    double[] seen = near.get(key);
                    if (seen == null || d < seen[0]) {
                        near.put(key, new double[] {d, level[i]});
                    }
                }
            }
        }
        for (var e : near.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            double d = e.getValue()[0];
            int water = (int) Math.floor(e.getValue()[1]);
            int ground = Surface.top(at, x, z, top, bottom);
            if (ground == Surface.NONE) {
                continue;
            }
            String topBlock = at.at(x, ground, z);
            if (d <= radius) {
                double share = d / Math.max(radius, 0.5);
                int floor = water - Math.max(1, (int) Math.round(r.depth() * (1 - share * share)));
                for (int y = Math.max(ground + 2, water + 1); y > water; y--) {
                    if (y <= ground + 2 && (y <= ground || Surface.plant(at.at(x, y, z))) && mask.allows(x, y, z)) {
                        c.set(x, y, z, AIR);
                    }
                }
                for (int y = water; y > floor; y--) {
                    if (mask.allows(x, y, z)) {
                        c.set(x, y, z, r.water().at(x, y, z));
                    }
                }
                if (mask.allows(x, floor, z)) {
                    c.set(x, floor, z, r.bed().at(x, floor, z));
                }
            } else {
                // The bank: no higher than a slope down to one above the water.
                int cap = water + 1 + (int) Math.floor((d - radius) * r.slope());
                if (ground <= cap) {
                    if (ground == water + 1 && r.shore() != null && !Surface.fluid(topBlock) && mask.allows(x, ground, z)) {
                        c.set(x, ground, z, r.shore().at(x, ground, z));
                    }
                    continue;
                }
                for (int y = cap + 1; y <= ground + 2; y++) {
                    if ((y <= ground || Surface.plant(at.at(x, y, z))) && mask.allows(x, y, z)) {
                        c.set(x, y, z, AIR);
                    }
                }
                if (mask.allows(x, cap, z)) {
                    c.set(x, cap, z, r.shore() != null && cap <= water + 2 ? r.shore().at(x, cap, z) : topBlock);
                }
            }
        }
    }

    // ------------------------------------------------------------------ tunnels and caves

    /** A winding tunnel through 3D points: a ball dragged along a curve, its radius breathing with noise. */
    static void tunnel(Canvas c, Step.Tunnel t) throws InterruptedException {
        Region b = t.bounds();
        Canvas.Mask mask = c.mask(b, t.replace());
        List<double[]> line = curve(t.points());
        for (double[] p : line) {
            double breathe = t.radius() * (1 + 0.3 * Noise.gradient(t.seed() + 3, p[3] / 9.0, 0.5));
            double rad = Math.max(1, breathe);
            int e = (int) Math.ceil(rad + t.rough());
            for (int dy = -e; dy <= e; dy++) {
                for (int dz = -e; dz <= e; dz++) {
                    for (int dx = -e; dx <= e; dx++) {
                        int x = (int) Math.floor(p[0]) + dx;
                        int y = (int) Math.floor(p[1]) + dy;
                        int z = (int) Math.floor(p[2]) + dz;
                        double edge = rad + t.rough() * Noise.value3(t.seed(), x / 3.0, y / 3.0, z / 3.0);
                        double ddx = x + 0.5 - p[0];
                        double ddy = (y + 0.5 - p[1]) * 1.25; // a little flatter than round
                        double ddz = z + 0.5 - p[2];
                        if (ddx * ddx + ddy * ddy + ddz * ddz <= edge * edge && mask.allows(x, y, z)) {
                            c.set(x, y, z, t.block().at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /** A Catmull-Rom curve through 3D points, sampled about every half block: x, y, z, distance along. */
    static List<double[]> curve(List<BlockPos> points) {
        List<double[]> out = new ArrayList<>();
        double travelled = 0;
        double[] last = null;
        for (int i = 0; i + 1 < points.size(); i++) {
            BlockPos p0 = points.get(Math.max(0, i - 1));
            BlockPos p1 = points.get(i);
            BlockPos p2 = points.get(i + 1);
            BlockPos p3 = points.get(Math.min(points.size() - 1, i + 2));
            double length = Math.sqrt(Math.pow(p2.x() - p1.x(), 2) + Math.pow(p2.y() - p1.y(), 2) + Math.pow(p2.z() - p1.z(), 2));
            int samples = Math.max(2, (int) Math.ceil(length * 2));
            for (int k = i == 0 ? 0 : 1; k <= samples; k++) {
                double t = (double) k / samples;
                double[] here = {spline(p0.x(), p1.x(), p2.x(), p3.x(), t) + 0.5, spline(p0.y(), p1.y(), p2.y(), p3.y(), t) + 0.5,
                        spline(p0.z(), p1.z(), p2.z(), p3.z(), t) + 0.5, 0};
                if (last != null) {
                    travelled += Math.sqrt(Math.pow(here[0] - last[0], 2) + Math.pow(here[1] - last[1], 2)
                            + Math.pow(here[2] - last[2], 2));
                }
                here[3] = travelled;
                out.add(here);
                last = here;
            }
        }
        return out;
    }

    private static double spline(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }

    /**
     * Caves through the solid ground of a box: wherever two octaves of 3D noise pass a threshold set by {@code density}
     * percent, the block becomes {@code block} (air, water, lava), keeping {@code roof} blocks of ground under the
     * surface so the land above stays whole.
     */
    static void caves(Canvas c, Step.Caves k) throws InterruptedException {
        Region box = k.box();
        Canvas.Lookup at = c.current(box);
        Canvas.Mask mask = c.mask(box, k.replace());
        int[] h = heights(at, box);
        int sx = box.sizeX();
        // density 0..100 maps to a threshold on |noise|: a band around zero makes long connected tubes.
        double band = 0.02 + 0.28 * k.density() / 100.0;
        for (int z = box.min().z(); z <= box.max().z(); z++) {
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                int ground = h[(z - box.min().z()) * sx + x - box.min().x()];
                int ceiling = ground == Surface.NONE ? box.max().y() : Math.min(box.max().y(), ground - k.roof());
                for (int y = box.min().y(); y <= ceiling; y++) {
                    if (open(at.at(x, y, z))) {
                        continue;
                    }
                    double s = k.scale();
                    double n1 = Noise.value3(k.seed(), x / s, y / (s * 0.6), z / s)
                            + 0.5 * Noise.value3(k.seed() + 1, 2 * x / s, 2 * y / (s * 0.6), 2 * z / s);
                    double n2 = Noise.value3(k.seed() + 7, x / s, y / (s * 0.6), z / s)
                            + 0.5 * Noise.value3(k.seed() + 8, 2 * x / s, 2 * y / (s * 0.6), 2 * z / s);
                    // Two bands crossing: spaghetti tunnels where both are near zero, wider rooms where one is.
                    boolean tube = Math.abs(n1) < band && Math.abs(n2) < band * 1.6;
                    if (tube && mask.allows(x, y, z)) {
                        c.set(x, y, z, k.block().at(x, y, z));
                    }
                }
            }
        }
    }
}
