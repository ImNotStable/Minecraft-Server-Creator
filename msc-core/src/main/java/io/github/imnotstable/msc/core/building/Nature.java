package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Trees, bushes and huge fungi grown in code (ADR 0079): the {@code tree} and {@code forest} steps. Every kind has its
 * own silhouette (a vanilla-like canopy, a cone, a bent acacia, a weeping willow, a palm, roots on a mangrove), varied by
 * the seed so no two copies match. Leaves are persistent, so nothing decays; logs carry their axis; leaves, vines and
 * moss only fill air and plants, so a tree grown beside a wall does not eat it.
 */
final class Nature {
    static final List<String> KINDS = List.of("oak", "birch", "spruce", "pine", "jungle", "acacia", "dark_oak", "cherry",
            "mangrove", "pale_oak", "azalea", "willow", "dead", "palm", "bush", "red_mushroom", "brown_mushroom", "crimson",
            "warped");
    static final List<String> SIZES = List.of("small", "medium", "large", "huge");

    /** Leaves, vines and hanging plants: only into air and plants. */
    static final int SOFT = 0;
    /** Logs and stems: also through leaves, plants and water. */
    static final int HARD = 1;
    /** Roots and rooted dirt: like hard, and into soft ground. */
    static final int ROOT = 2;

    /** One block of a grown plant. */
    record Part(int x, int y, int z, String state, int mode) {
    }

    private Nature() {
    }

    /** Default trunk and leaf blocks per kind. */
    static String[] blocks(String kind) {
        return switch (kind) {
            case "pine" -> new String[] {"spruce_log", "spruce_leaves"};
            case "azalea" -> new String[] {"oak_log", "azalea_leaves:3,flowering_azalea_leaves:1"};
            case "willow", "dead", "bush" -> new String[] {"oak_log", "oak_leaves"};
            case "palm" -> new String[] {"jungle_log", "jungle_leaves"};
            case "red_mushroom" -> new String[] {"mushroom_stem", "red_mushroom_block"};
            case "brown_mushroom" -> new String[] {"mushroom_stem", "brown_mushroom_block"};
            case "crimson" -> new String[] {"crimson_stem", "nether_wart_block"};
            case "warped" -> new String[] {"warped_stem", "warped_wart_block"};
            default -> new String[] {kind + "_log", kind + "_leaves"};
        };
    }

    static void checkKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new ToolException("unknown tree kind " + kind + "; use one of " + String.join(", ", KINDS));
        }
    }

    static void checkSize(String size) {
        if (!SIZES.contains(size)) {
            throw new ToolException("unknown size " + size + "; use one of " + String.join(", ", SIZES));
        }
    }

    /** How far a tree of this size may reach sideways and up from its base, for a forest's bounds. */
    static int[] reach(String size) {
        return switch (size) {
            case "small" -> new int[] {6, 18};
            case "medium" -> new int[] {8, 26};
            case "large" -> new int[] {11, 36};
            default -> new int[] {14, 48};
        };
    }

    /**
     * The blocks of one plant standing on {@code base} (its lowest trunk block). {@code height} 0 picks one for the kind
     * and size; {@code log} and {@code leaves} null take the kind's own.
     */
    static List<Part> grow(String kind, BlockPos base, String size, int height, BlockPattern log, BlockPattern leaves,
                           long seed) {
        String[] own = blocks(kind);
        G g = new G(base, log != null ? log : BlockPattern.parse(own[0], seed),
                leaves != null ? leaves : BlockPattern.parse(own[1], seed), seed);
        double s = switch (size) {
            case "small" -> 0.75;
            case "large" -> 1.45;
            case "huge" -> 1.95;
            default -> 1.0;
        };
        boolean big = size.equals("large") || size.equals("huge");
        int h = height > 0 ? height : (int) Math.round(g.between(base(kind)[0], base(kind)[1]) * s);
        h = Math.max(kind.equals("bush") ? 1 : 2, h);
        switch (kind) {
            case "oak", "birch" -> {
                if (big) {
                    g.fancy(h, s);
                } else {
                    g.column(0, 0, 0, h - 1, 1);
                    g.canopy(h, 2);
                }
            }
            case "spruce" -> g.cone(h, size.equals("huge") ? 2 : 1, Math.max(2, (int) Math.round(2.8 * s)), Math.max(1, h / 4));
            case "pine" -> g.cone(h, size.equals("huge") ? 2 : 1, Math.max(1, (int) Math.round(1.6 * s)), (int) (h * 0.62));
            case "jungle" -> g.jungle(h, big, s);
            case "acacia" -> g.acacia(h, s);
            case "dark_oak", "pale_oak" -> g.darkOak(h, s, kind.equals("pale_oak"));
            case "cherry" -> g.cherry(h, s);
            case "mangrove" -> g.mangrove(h, s);
            case "azalea" -> g.azalea(h, s);
            case "willow" -> g.willow(h, s);
            case "dead" -> g.dead(h);
            case "palm" -> g.palm(h, s);
            case "bush" -> g.bush(s, big);
            case "red_mushroom", "brown_mushroom" -> g.mushroom(h, s, kind.startsWith("red"));
            case "crimson", "warped" -> g.fungus(h, s, kind.equals("crimson"));
            default -> throw new IllegalStateException(kind);
        }
        return g.parts();
    }

    /** Trunk height range at medium size. */
    private static int[] base(String kind) {
        return switch (kind) {
            case "birch" -> new int[] {5, 7};
            case "spruce" -> new int[] {8, 12};
            case "pine" -> new int[] {10, 14};
            case "jungle" -> new int[] {9, 13};
            case "acacia" -> new int[] {5, 7};
            case "dark_oak", "pale_oak" -> new int[] {6, 8};
            case "cherry" -> new int[] {3, 4};
            case "mangrove" -> new int[] {7, 9};
            case "azalea" -> new int[] {3, 4};
            case "willow" -> new int[] {5, 7};
            case "dead" -> new int[] {4, 7};
            case "palm" -> new int[] {7, 10};
            case "bush" -> new int[] {1, 1};
            case "red_mushroom", "brown_mushroom" -> new int[] {5, 7};
            case "crimson", "warped" -> new int[] {5, 8};
            default -> new int[] {4, 6};
        };
    }

    /** Ground the usual plants grow on. */
    private static final String SOIL = "grass_block,dirt,podzol,coarse_dirt,moss_block,rooted_dirt,mud,mycelium";
    static final List<String> FLORA = List.of("meadow", "plains", "flowers", "forest", "taiga", "swamp", "jungle",
            "savanna", "desert", "cherry", "lush", "snowy", "mushroom", "nether", "crimson", "warped");

    /** A flora style's plant mix and the ground it grows on, as build_batch block lists. */
    static String[] flora(String style) {
        return switch (style) {
            case "meadow" -> new String[] {"short_grass:40,tall_grass:8,dandelion:4,poppy:4,oxeye_daisy:4,cornflower:4,"
                    + "azure_bluet:4,allium:2," + spread("wildflowers", "flower_amount", 1), SOIL};
            case "plains" -> new String[] {"short_grass:60,tall_grass:12,dandelion:3,poppy:3,oxeye_daisy:2,azure_bluet:2",
                    SOIL};
            case "flowers" -> new String[] {"dandelion:3,poppy:3,blue_orchid:2,allium:2,azure_bluet:2,red_tulip:2,"
                    + "orange_tulip:2,white_tulip:2,pink_tulip:2,oxeye_daisy:3,cornflower:3,lily_of_the_valley:2,"
                    + "sunflower:1,lilac:1,rose_bush:1,peony:1,short_grass:10", SOIL};
            case "forest" -> new String[] {"short_grass:30,fern:15,large_fern:5,bush:8,lily_of_the_valley:3,"
                    + "brown_mushroom:3,red_mushroom:2,firefly_bush:2," + spread("leaf_litter", "segment_amount", 1), SOIL};
            case "taiga" -> new String[] {"fern:30,large_fern:10,sweet_berry_bush[age=3]:6,short_grass:12,brown_mushroom:3,"
                    + spread("leaf_litter", "segment_amount", 1), SOIL};
            case "swamp" -> new String[] {"short_grass:20,fern:10,blue_orchid:8,brown_mushroom:4,red_mushroom:2,"
                    + "firefly_bush:4", SOIL};
            case "jungle" -> new String[] {"fern:25,large_fern:15,short_grass:20,tall_grass:10,bush:10", SOIL};
            case "savanna" -> new String[] {"short_grass:45,tall_grass:25,short_dry_grass:10,dandelion:2", SOIL};
            case "desert" -> new String[] {"dead_bush:6,short_dry_grass:12,tall_dry_grass:6",
                    "sand,red_sand,terracotta,orange_terracotta,red_terracotta,coarse_dirt"};
            case "cherry" -> new String[] {spread("pink_petals", "flower_amount", 3) + ",short_grass:20", SOIL};
            case "lush" -> new String[] {"moss_carpet:25,azalea:6,flowering_azalea:6,short_grass:15,fern:6",
                    SOIL + ",clay"};
            case "snowy" -> new String[] {"snow[layers=1]:40,snow[layers=2]:15,fern:5,short_grass:5",
                    SOIL + ",snow_block,stone"};
            case "mushroom" -> new String[] {"brown_mushroom:5,red_mushroom:5", SOIL};
            case "nether" -> new String[] {"crimson_roots:20,warped_roots:10,nether_sprouts:10,crimson_fungus:4,"
                    + "warped_fungus:4", "crimson_nylium,warped_nylium,soul_soil,netherrack"};
            case "crimson" -> new String[] {"crimson_roots:30,crimson_fungus:6", "crimson_nylium"};
            case "warped" -> new String[] {"warped_roots:30,nether_sprouts:15,warped_fungus:6", "warped_nylium"};
            default -> throw new ToolException("unknown flora style " + style + "; use one of " + String.join(", ", FLORA));
        };
    }

    /** A plant that lies in four turns and 1 to 4 pieces, every way of it at {@code weight}. */
    private static String spread(String id, String amount, int weight) {
        List<String> out = new ArrayList<>();
        for (String f : List.of("north", "east", "south", "west")) {
            for (int n = 1; n <= 4; n++) {
                out.add(id + "[facing=" + f + "," + amount + "=" + n + "]:" + weight);
            }
        }
        return String.join(",", out);
    }

    /** The box of a plant's parts. */
    static Region box(String world, List<Part> parts) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Part p : parts) {
            lo[0] = Math.min(lo[0], p.x());
            lo[1] = Math.min(lo[1], p.y());
            lo[2] = Math.min(lo[2], p.z());
            hi[0] = Math.max(hi[0], p.x());
            hi[1] = Math.max(hi[1], p.y());
            hi[2] = Math.max(hi[2], p.z());
        }
        return Region.of(world, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /**
     * Set the parts where they may go: soft parts into air and plants, hard ones also through leaves and water, roots
     * also into soft ground. Waterloggable parts say whether they stand in water.
     */
    static void place(Canvas c, List<Part> parts, Canvas.Lookup at, Canvas.Mask mask) {
        for (Part p : parts) {
            String here = at.at(p.x(), p.y(), p.z());
            boolean ok = BlockNames.isAir(here) || Surface.plant(here)
                    || p.mode() >= HARD && (Surface.fluid(here) || Joins.id(here).contains("leaves"))
                    || p.mode() == ROOT && soil(here);
            if (!ok || !mask.allows(p.x(), p.y(), p.z())) {
                continue;
            }
            String state = p.state();
            if (BlockRules.waterloggable(Joins.id(state))) {
                state = Joins.with(state, "waterlogged", String.valueOf(Joins.id(here).equals("water")));
            }
            c.set(p.x(), p.y(), p.z(), state);
        }
    }

    private static boolean soil(String state) {
        String id = Joins.id(state);
        return id.equals("dirt") || id.equals("grass_block") || id.equals("mud") || id.equals("podzol")
                || id.equals("coarse_dirt") || id.equals("rooted_dirt") || id.equals("sand") || id.equals("gravel")
                || id.equals("clay") || id.equals("moss_block") || id.equals("mycelium");
    }

    /** One per {@code spacing} cell of {@code region}, kept with {@code chance}: x, z and a hash (shared with scatter). */
    static List<long[]> spots(Region region, int spacing, int chance, long seed) {
        List<long[]> out = new ArrayList<>();
        BlockPos lo = region.min();
        BlockPos hi = region.max();
        for (int cz = 0; lo.z() + (long) cz * spacing <= hi.z(); cz++) {
            for (int cx = 0; lo.x() + (long) cx * spacing <= hi.x(); cx++) {
                long h = Noise.hash(seed, cx, cz);
                long x = lo.x() + (long) cx * spacing + (long) (Noise.unit(h) * spacing);
                long z = lo.z() + (long) cz * spacing + (long) (Noise.unit(BlockPattern.mix(h)) * spacing);
                if (x <= hi.x() && z <= hi.z() && Noise.unit(BlockPattern.mix(h + 1)) * 100 < chance) {
                    out.add(new long[] {x, z, BlockPattern.mix(h + 2)});
                }
            }
        }
        return out;
    }

    /** Draw a forest: a tree on the ground at each spot, its kind picked by weight. */
    static void forest(Canvas c, Step.Forest f) throws InterruptedException {
        Region b = f.bounds();
        Canvas.Lookup at = c.current(b);
        Canvas.Mask mask = c.mask(b, f.replace());
        List<String> ground = f.on().stream().map(BlockNames::full).toList();
        int total = 0;
        for (int w : f.weights()) {
            total += w;
        }
        Region r = f.region();
        for (long[] s : spots(r, f.spacing(), f.chance(), f.seed())) {
            int x = (int) s[0];
            int z = (int) s[1];
            int y = Surface.top(at, x, z, r.max().y(), r.min().y());
            if (y == Surface.NONE) {
                continue;
            }
            String surface = at.at(x, y, z);
            if (Surface.fluid(surface) || !ground.isEmpty() && !Canvas.matches(surface, ground)
                    || !BlockNames.isAir(at.at(x, y + 1, z)) && !Surface.plant(at.at(x, y + 1, z))) {
                continue;
            }
            int roll = (int) Long.remainderUnsigned(s[2], total);
            int k = 0;
            while (roll >= f.weights()[k]) {
                roll -= f.weights()[k++];
            }
            String size = f.size().equals("mixed") ? SIZES.get((int) Long.remainderUnsigned(s[2] >>> 16, 3)) : f.size();
            place(c, grow(f.kinds().get(k), new BlockPos(x, y + 1, z), size, 0, f.log(), f.leaves(), s[2]), at, mask);
        }
    }

    /** The grower: parts relative to the base, later hard parts winning over soft ones. */
    private static final class G {
        private final Map<Long, Part> parts = new LinkedHashMap<>();
        private final BlockPos base;
        private final BlockPattern log;
        private final BlockPattern leaves;
        private final SplittableRandom r;

        G(BlockPos base, BlockPattern log, BlockPattern leaves, long seed) {
            this.base = base;
            this.log = log;
            this.leaves = leaves;
            this.r = new SplittableRandom(seed);
        }

        List<Part> parts() {
            return List.copyOf(parts.values());
        }

        int between(int a, int b) {
            return a + r.nextInt(b - a + 1);
        }

        double rand() {
            return r.nextDouble();
        }

        private void put(int dx, int dy, int dz, String state, int mode) {
            int x = base.x() + dx;
            int y = base.y() + dy;
            int z = base.z() + dz;
            long key = ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF;
            Part old = parts.get(key);
            if (old != null && old.mode() > mode) {
                return;
            }
            parts.put(key, new Part(x, y, z, state, mode));
        }

        /** A trunk block; {@code axis} 0 x, 1 y, 2 z. */
        void log(int dx, int dy, int dz, int axis) {
            String s = log.at(base.x() + dx, base.y() + dy, base.z() + dz);
            String id = Joins.id(s);
            if ((id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae"))
                    && !id.equals("mushroom_stem") && !Joins.has(s, "axis")) {
                s = Joins.with(s, "axis", axis == 0 ? "x" : axis == 1 ? "y" : "z");
            }
            put(dx, dy, dz, s, HARD);
        }

        void leaf(int dx, int dy, int dz) {
            String s = leaves.at(base.x() + dx, base.y() + dy, base.z() + dz);
            if (Joins.id(s).contains("leaves") && !Joins.has(s, "persistent")) {
                s = Joins.with(s, "persistent", "true");
            }
            put(dx, dy, dz, s, SOFT);
        }

        void soft(int dx, int dy, int dz, String state) {
            put(dx, dy, dz, BlockNames.full(state), SOFT);
        }

        void root(int dx, int dy, int dz, String state) {
            put(dx, dy, dz, BlockNames.full(state), ROOT);
        }

        /** An upright trunk {@code width} wide (2 grows +x and +z) from dy0 to dy1. */
        void column(int dx, int dz, int dy0, int dy1, int width) {
            for (int y = dy0; y <= dy1; y++) {
                for (int i = 0; i < width; i++) {
                    for (int j = 0; j < width; j++) {
                        log(dx + i, y, dz + j, 1);
                    }
                }
            }
        }

        /** A branch of logs from one point to another, each turned along the way it runs. */
        void branch(double x0, double y0, double z0, double x1, double y1, double z1) {
            double ax = Math.abs(x1 - x0);
            double ay = Math.abs(y1 - y0);
            double az = Math.abs(z1 - z0);
            int axis = ay >= ax && ay >= az ? 1 : ax >= az ? 0 : 2;
            int n = (int) Math.ceil(Math.max(ax, Math.max(ay, az))) + 1;
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                log((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(y0 + (y1 - y0) * t),
                        (int) Math.round(z0 + (z1 - z0) * t), axis);
            }
        }

        /** A leaf ball, its edge pushed in and out by noise. */
        void blob(double cx, double cy, double cz, double rx, double ry, double rz) {
            long seed = r.nextLong();
            int ex = (int) Math.ceil(rx + 1);
            int ey = (int) Math.ceil(ry + 1);
            int ez = (int) Math.ceil(rz + 1);
            for (int y = (int) Math.floor(cy) - ey; y <= (int) Math.ceil(cy) + ey; y++) {
                for (int z = (int) Math.floor(cz) - ez; z <= (int) Math.ceil(cz) + ez; z++) {
                    for (int x = (int) Math.floor(cx) - ex; x <= (int) Math.ceil(cx) + ex; x++) {
                        double nx = (x - cx) / rx;
                        double ny = (y - cy) / ry;
                        double nz = (z - cz) / rz;
                        double d = nx * nx + ny * ny + nz * nz;
                        double edge = 1 + 0.35 * Noise.value3(seed, x / 2.3, y / 2.3, z / 2.3);
                        if (d <= edge) {
                            leaf(x, y, z);
                        }
                    }
                }
            }
        }

        /** A flat layer of leaves, its corners cut at random. */
        void layer(double cx, int dy, double cz, double radius, double cornerCut) {
            int e = (int) Math.ceil(radius);
            for (int z = (int) Math.floor(cz) - e; z <= (int) Math.ceil(cz) + e; z++) {
                for (int x = (int) Math.floor(cx) - e; x <= (int) Math.ceil(cx) + e; x++) {
                    double dx = Math.abs(x - cx);
                    double dz = Math.abs(z - cz);
                    if (dx * dx + dz * dz > radius * radius + radius * 0.8) {
                        continue;
                    }
                    if (dx + dz >= radius * 1.6 && rand() < cornerCut) {
                        continue;
                    }
                    leaf(x, dy, z);
                }
            }
        }

        /** The small oak and birch crown: two wide layers and two narrow ones over the trunk top. */
        void canopy(int h, int wide) {
            for (int y = h - 3; y <= h; y++) {
                int rad = y >= h - 1 ? 1 : wide;
                for (int z = -rad; z <= rad; z++) {
                    for (int x = -rad; x <= rad; x++) {
                        boolean corner = Math.abs(x) == rad && Math.abs(z) == rad;
                        if (corner && (y == h || rand() < 0.5)) {
                            continue;
                        }
                        leaf(x, y, z);
                    }
                }
            }
        }

        /** A big oak: a trunk with branches climbing out of it, each ending in a crown. */
        void fancy(int h, double s) {
            int trunk = (int) Math.round(h * 0.85);
            column(0, 0, 0, trunk, 1);
            int count = 3 + h / 5;
            for (int i = 0; i < count; i++) {
                double angle = rand() * Math.PI * 2;
                double sy = h * 0.4 + rand() * h * 0.45;
                double len = h * 0.33 * (0.6 + 0.6 * rand());
                double ex = Math.cos(angle) * len;
                double ez = Math.sin(angle) * len;
                double ey = Math.min(sy + len * 0.55, h);
                branch(0, sy, 0, ex, ey, ez);
                blob(ex, ey + 0.5, ez, 2.2 * s / 1.45 + 0.4, 1.6, 2.2 * s / 1.45 + 0.4);
            }
            blob(0, trunk + 1, 0, 2.6 * s / 1.45 + 0.4, 2.0, 2.6 * s / 1.45 + 0.4);
        }

        /** A spruce or pine: a straight trunk under rings of leaves that widen downward in tiers. */
        void cone(int h, int width, int maxR, int start) {
            column(0, 0, 0, h - 1, width);
            double c = (width - 1) / 2.0;
            int rad = 0;
            for (int y = h + 1; y >= start; y--) {
                double limit = 1 + (maxR - 1) * (double) (h + 1 - y) / Math.max(1, h + 1 - start);
                layer(c, y, c, rad + (width - 1) * 0.5, 0.6);
                rad++;
                if (rad > limit) {
                    rad = Math.max(1, (int) limit - 2);
                }
            }
            leaf((int) c, h, (int) c);
        }

        void jungle(int h, boolean big, double s) {
            int width = big ? 2 : 1;
            column(0, 0, 0, h - 1, width);
            double c = (width - 1) / 2.0;
            if (big) {
                blob(c, h, c, 3.4 * s / 1.45 + 1, 2.0, 3.4 * s / 1.45 + 1);
                int arms = between(3, 5);
                for (int i = 0; i < arms; i++) {
                    double angle = rand() * Math.PI * 2;
                    double sy = h * 0.55 + rand() * h * 0.3;
                    double len = 3 + rand() * 2;
                    double ex = c + Math.cos(angle) * len;
                    double ez = c + Math.sin(angle) * len;
                    branch(c, sy, c, ex, sy + 2, ez);
                    blob(ex, sy + 2.5, ez, 2.4, 1.3, 2.4);
                }
            } else {
                canopy(h, 2);
            }
            // Vines up the trunk and cocoa on its sides.
            for (int y = 0; y < h; y++) {
                for (int[] d : new int[][] {{-1, 0}, {width, 0}, {0, -1}, {0, width}}) {
                    if (rand() < 0.35) {
                        soft(d[0], y, d[1], "vine");
                    } else if (y > 1 && y < h - 2 && rand() < 0.04) {
                        String toward = d[0] < 0 ? "east" : d[0] >= width ? "west" : d[1] < 0 ? "south" : "north";
                        soft(d[0], y, d[1], "cocoa[facing=" + toward + ",age=" + between(0, 2) + "]");
                    }
                }
            }
            hang(c, h - 2, c, 4.5 * s, 0.25, 5, "vine", "vine");
        }

        /** Strands hanging from the leaves under a crown, out to {@code radius} from its middle. */
        void hang(double cx, int fromY, double cz, double radius, double chance, int maxLength, String body, String tip) {
            int e = (int) Math.ceil(radius);
            List<Part> crown = List.copyOf(parts.values());
            for (Part p : crown) {
                int dx = p.x() - base.x();
                int dy = p.y() - base.y();
                int dz = p.z() - base.z();
                if (p.mode() != SOFT || dy > fromY + 3 || Math.abs(dx - cx) > e || Math.abs(dz - cz) > e) {
                    continue;
                }
                long below = key(p.x(), p.y() - 1, p.z());
                if (parts.containsKey(below) || rand() >= chance) {
                    continue;
                }
                int len = between(1, maxLength);
                for (int k = 1; k <= len; k++) {
                    soft(dx, dy - k, dz, k == len ? tip : body);
                }
            }
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF;
        }

        /** An acacia: up, then bent off to one side, with flat crowns. */
        void acacia(int h, double s) {
            int straight = Math.max(1, (int) Math.round(h * (0.35 + 0.2 * rand())));
            int dir = between(0, 3);
            int sx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
            int sz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            column(0, 0, 0, straight, 1);
            int x = 0;
            int z = 0;
            for (int y = straight + 1; y < h; y++) {
                x += sx;
                z += sz;
                log(x, y, z, 1);
            }
            double rad = 2.5 * s + 0.5;
            layer(x, h, z, rad, 0.7);
            layer(x, h + 1, z, rad - 1, 0.5);
            if (rand() < 0.6) {
                int bx = 0;
                int bz = 0;
                int top = straight + between(1, 2);
                for (int y = straight; y <= top; y++) {
                    bx -= sx + (sz != 0 ? (rand() < 0.5 ? 1 : -1) : 0);
                    bz -= sz + (sx != 0 ? (rand() < 0.5 ? 1 : -1) : 0);
                    log(bx, y, bz, 1);
                }
                layer(bx, top + 1, bz, rad - 0.8, 0.6);
                layer(bx, top + 2, bz, rad - 1.8, 0.5);
            }
        }

        /** A dark or pale oak: a thick 2x2 trunk under a broad, low crown; pale oaks trail moss. */
        void darkOak(int h, double s, boolean pale) {
            column(0, 0, 0, h - 1, 2);
            for (int i = 0; i < 4; i++) {
                if (rand() < 0.5) {
                    int dx = i % 2 == 0 ? (i == 0 ? -1 : 2) : between(0, 1);
                    int dz = i % 2 == 1 ? (i == 1 ? -1 : 2) : between(0, 1);
                    log(dx, h - between(2, 3), dz, i % 2 == 0 ? 0 : 2);
                }
            }
            blob(0.5, h, 0.5, 3.4 * s + 0.6, 1.7, 3.4 * s + 0.6);
            if (pale) {
                hang(0.5, h - 1, 0.5, 4.5 * s, 0.22, 3, "pale_hanging_moss[tip=false]", "pale_hanging_moss[tip=true]");
            }
        }

        /** A cherry: a short trunk forking into arcing branches, each with a wide pink crown. */
        void cherry(int h, double s) {
            column(0, 0, 0, h - 1, 1);
            int arms = between(2, 3);
            double start = rand() * Math.PI * 2;
            for (int i = 0; i < arms; i++) {
                double angle = start + i * Math.PI * 2 / arms + (rand() - 0.5) * 0.6;
                double len = (3 + rand() * 2) * s;
                double mx = Math.cos(angle) * len * 0.5;
                double mz = Math.sin(angle) * len * 0.5;
                double ex = Math.cos(angle) * len;
                double ez = Math.sin(angle) * len;
                branch(0, h - 1, 0, mx, h + len * 0.3, mz);
                branch(mx, h + len * 0.3, mz, ex, h + len * 0.8, ez);
                blob(ex, h + len * 0.8 + 1, ez, 3.2 * s, 1.6, 3.2 * s);
            }
        }

        /** A mangrove: a trunk raised on arching roots, a crown hung with propagules. */
        void mangrove(int h, double s) {
            int lift = between(2, 3);
            column(0, 0, lift, h + lift - 1, 1);
            int roots = between(4, 6);
            for (int i = 0; i < roots; i++) {
                double angle = i * Math.PI * 2 / roots + rand() * 0.5;
                double out = 2 + rand() * 2 * s;
                int steps = (int) Math.ceil(out * 2);
                for (int k = 0; k <= steps; k++) {
                    double t = (double) k / steps;
                    int x = (int) Math.round(Math.cos(angle) * out * t);
                    int z = (int) Math.round(Math.sin(angle) * out * t);
                    int y = (int) Math.round(lift + 0.5 - (lift + 1.5) * t * t);
                    root(x, y, z, "mangrove_roots");
                }
            }
            int top = h + lift;
            blob(0, top, 0, 3.0 * s + 0.5, 2.0, 3.0 * s + 0.5);
            hang(0, top - 1, 0, 3.5 * s, 0.12, 1, "mangrove_propagule[hanging=true,age=4,stage=0]",
                    "mangrove_propagule[hanging=true,age=4,stage=0]");
        }

        /** An azalea: a short, crooked trunk on rooted dirt, a round flowering crown. */
        void azalea(int h, double s) {
            root(0, -1, 0, "rooted_dirt");
            int kink = between(1, Math.max(1, h - 1));
            int kx = between(-1, 1);
            int kz = kx == 0 ? (rand() < 0.5 ? -1 : 1) : 0;
            column(0, 0, 0, kink - 1, 1);
            column(kx, kz, kink, h - 1, 1);
            blob(kx, h + 0.5, kz, 2.4 * s + 0.4, 1.5, 2.4 * s + 0.4);
        }

        /** A willow: a crown like a dome whose rim weeps leaves to the ground. */
        void willow(int h, double s) {
            column(0, 0, 0, h - 1, 1);
            double rad = 3.3 * s + 0.7;
            blob(0, h, 0, rad, 2.0, rad);
            for (int i = 0; i < 3; i++) {
                double angle = rand() * Math.PI * 2;
                branch(0, h - 2, 0, Math.cos(angle) * rad * 0.7, h, Math.sin(angle) * rad * 0.7);
            }
            List<Part> crown = List.copyOf(parts.values());
            for (Part p : crown) {
                int dx = p.x() - base.x();
                int dz = p.z() - base.z();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (p.mode() != SOFT || d < rad - 1.6 || parts.containsKey(key(p.x(), p.y() - 1, p.z()))
                        || rand() > 0.65) {
                    continue;
                }
                int len = between(2, Math.max(2, h - 2));
                for (int k = 1; k <= len; k++) {
                    leaf(dx, p.y() - base.y() - k, dz);
                }
            }
        }

        /** A dead tree: a bare trunk and a few broken branches. */
        void dead(int h) {
            column(0, 0, 0, h - 1, 1);
            int arms = between(2, 4);
            for (int i = 0; i < arms; i++) {
                double angle = rand() * Math.PI * 2;
                double sy = h * 0.45 + rand() * h * 0.45;
                double len = 1.5 + rand() * 1.8;
                branch(0, sy, 0, Math.cos(angle) * len, sy + len * 0.7, Math.sin(angle) * len);
            }
        }

        /** A palm: a trunk leaning further as it rises, fronds drooping from its top. */
        void palm(int h, double s) {
            double angle = rand() * Math.PI * 2;
            double lean = h * (0.25 + 0.2 * rand());
            int tx = 0;
            int tz = 0;
            for (int y = 0; y < h; y++) {
                double t = (double) y / h;
                tx = (int) Math.round(Math.cos(angle) * lean * t * t);
                tz = (int) Math.round(Math.sin(angle) * lean * t * t);
                log(tx, y, tz, 1);
            }
            leaf(tx, h, tz);
            int len = (int) Math.round(3 + s);
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                int reach = d[0] != 0 && d[1] != 0 ? len - 1 : len;
                for (int k = 1; k <= reach; k++) {
                    int droop = k >= reach ? 2 : k >= reach - 1 ? 1 : 0;
                    leaf(tx + d[0] * k, h - droop, tz + d[1] * k);
                    if (k == reach) {
                        leaf(tx + d[0] * k, h - droop - 1, tz + d[1] * k);
                    }
                }
            }
        }

        /** A bush: a low leaf mound, on a stub of trunk when big. */
        void bush(double s, boolean big) {
            double rad = 0.9 + 0.7 * s;
            if (big) {
                log(0, 0, 0, 1);
            }
            blob(0, rad * 0.45, 0, rad, Math.max(1.0, rad * 0.7), rad);
        }

        /** A huge mushroom: red ones a domed cap with hanging sides, brown ones a flat plate. */
        void mushroom(int h, double s, boolean red) {
            column(0, 0, 0, h - 1, 1);
            int rad = Math.max(2, (int) Math.round((red ? 2 : 3) * s));
            if (red) {
                for (int y = h - 3; y <= h - 1; y++) {
                    for (int z = -rad; z <= rad; z++) {
                        for (int x = -rad; x <= rad; x++) {
                            boolean rim = Math.abs(x) == rad || Math.abs(z) == rad;
                            if (rim && !(Math.abs(x) == rad && Math.abs(z) == rad)) {
                                leaf(x, y, z);
                            }
                        }
                    }
                }
                for (int z = -rad + 1; z <= rad - 1; z++) {
                    for (int x = -rad + 1; x <= rad - 1; x++) {
                        leaf(x, h, z);
                    }
                }
            } else {
                for (int z = -rad; z <= rad; z++) {
                    for (int x = -rad; x <= rad; x++) {
                        if (!(Math.abs(x) == rad && Math.abs(z) == rad)) {
                            leaf(x, h, z);
                        }
                    }
                }
            }
        }

        /** A huge fungus: a stem under a wart cap lit by shroomlight; crimson ones drip weeping vines. */
        void fungus(int h, double s, boolean crimson) {
            column(0, 0, 0, h - 1, 1);
            double rad = 2.2 * s + 0.6;
            blob(0, h - 0.5, 0, rad, 1.8, rad);
            for (Part p : List.copyOf(parts.values())) {
                if (p.mode() == SOFT && rand() < 0.07) {
                    soft(p.x() - base.x(), p.y() - base.y(), p.z() - base.z(), "shroomlight");
                }
            }
            if (crimson) {
                hang(0, h - 2, 0, rad + 1, 0.2, 4, "weeping_vines_plant", "weeping_vines[age=25]");
            }
        }
    }
}
