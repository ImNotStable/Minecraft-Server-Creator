package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The detail steps of ADR 0077: {@code roof}, {@code trim}, {@code decorate} and {@code weather}. Each works on what
 * is already there (the world and the earlier steps of the batch), so a build is blocked out in a few big steps and
 * then dressed the way a builder dresses one: eaves, sills and shutters on the walls, plants and props where they
 * would be, wear where weather would cause it. None of them sets a style: every block is the caller's.
 */
final class Detail {
    private Detail() {
    }

    // ---------------------------------------------------------------- trim

    /** Where a trim goes; see {@link Step.Trim}. */
    static void trim(Canvas c, Step.Trim t) throws InterruptedException {
        Region box = t.box();
        Region b = t.bounds();
        Canvas.Lookup at = c.current(grow(b, 1));
        Canvas.Mask mask = c.mask(b, t.replace());
        double cx = (box.min().x() + box.max().x()) / 2.0;
        double cz = (box.min().z() + box.max().z()) / 2.0;
        List<Object[]> puts = new ArrayList<>(); // x, y, z, state, replaceSolid
        BlockPos lo = box.min();
        BlockPos hi = box.max();
        for (int y = lo.y(); y <= hi.y(); y++) {
            for (int z = lo.z(); z <= hi.z(); z++) {
                for (int x = lo.x(); x <= hi.x(); x++) {
                    String here = at.at(x, y, z);
                    switch (t.at()) {
                        case "top", "base", "band" -> {
                            if (!Joins.solid(here)) {
                                continue;
                            }
                            boolean row = switch (t.at()) {
                                case "top" -> !Joins.solid(at.at(x, y + 1, z)) && opening(at, x, y + 1, z, cx, cz) < 0; // not a sill
                                case "base" -> y == lowestWall(at, x, y, z, lo.y());
                                default -> y == t.level();
                            };
                            if (!row) {
                                continue;
                            }
                            List<Integer> sides = exterior(at, x, y, z, cx, cz);
                            for (int d : sides) {
                                ledge(t, puts, x, y, z, d);
                            }
                            if (sides.size() == 2 && sides.get(0) % 2 != sides.get(1) % 2) {
                                // The corner between two runs: one more piece, which joins turns into a corner stair.
                                int d0 = sides.get(0);
                                int d1 = sides.get(1);
                                for (int k = 1; k <= t.out(); k++) {
                                    int px = x + (Joins.STEP[d0][0] + Joins.STEP[d1][0]) * k;
                                    int pz = z + (Joins.STEP[d0][1] + Joins.STEP[d1][1]) * k;
                                    puts.add(new Object[] {px, y, pz, shaped(t.block().at(px, y, pz), (d0 + 2) % 4,
                                            !t.at().equals("base")), false});
                                }
                            }
                        }
                        case "corners" -> {
                            List<Integer> sides = exterior(at, x, y, z, cx, cz);
                            if (Joins.solid(here) && sides.size() >= 2 && sides.get(0) % 2 != sides.get(1) % 2) {
                                puts.add(new Object[] {x, y, z, t.block().at(x, y, z), true});
                            }
                        }
                        default -> {
                            int d = opening(at, x, y, z, cx, cz);
                            if (d < 0) {
                                continue;
                            }
                            int dx = Joins.STEP[d][0];
                            int dz = Joins.STEP[d][1];
                            int back = (d + 2) % 4;
                            switch (t.at()) {
                                case "sills" -> {
                                    if (Joins.solid(at.at(x, y - 1, z)) && !Joins.solid(at.at(x + dx, y - 1, z + dz))) {
                                        puts.add(new Object[] {x + dx, y - 1, z + dz,
                                                shaped(t.block().at(x + dx, y - 1, z + dz), back, true), false});
                                    }
                                }
                                case "lintels" -> {
                                    if (Joins.solid(at.at(x, y + 1, z))) {
                                        puts.add(new Object[] {x + dx, y + 1, z + dz,
                                                shaped(t.block().at(x + dx, y + 1, z + dz), back, false), false});
                                    }
                                }
                                case "shutters" -> {
                                    for (int s : new int[] {(d + 1) % 4, (d + 3) % 4}) {
                                        int sx = x + Joins.STEP[s][0];
                                        int sz = z + Joins.STEP[s][1];
                                        if (Joins.solid(at.at(sx, y, sz))) {
                                            String state = t.block().at(sx + dx, y, sz + dz);
                                            if (Joins.id(state).endsWith("trapdoor")) {
                                                state = Joins.with(Joins.with(state, "open", "true"), "facing", Joins.DIRS[d]);
                                            }
                                            puts.add(new Object[] {sx + dx, y, sz + dz, state, false});
                                        }
                                    }
                                }
                                case "frames" -> {
                                    for (int s : new int[] {(d + 1) % 4, (d + 3) % 4}) {
                                        int sx = x + Joins.STEP[s][0];
                                        int sz = z + Joins.STEP[s][1];
                                        if (Joins.solid(at.at(sx, y, sz))) {
                                            puts.add(new Object[] {sx, y, sz, t.block().at(sx, y, sz), true});
                                        }
                                    }
                                    for (int dy : new int[] {-1, 1}) {
                                        if (Joins.solid(at.at(x, y + dy, z))) {
                                            puts.add(new Object[] {x, y + dy, z, t.block().at(x, y + dy, z), true});
                                        }
                                    }
                                }
                                default -> throw new IllegalStateException(t.at());
                            }
                        }
                    }
                }
            }
        }
        for (Object[] p : puts) {
            int x = (int) p[0];
            int y = (int) p[1];
            int z = (int) p[2];
            boolean replaceSolid = (boolean) p[4];
            String now = at.at(x, y, z);
            boolean open = BlockNames.isAir(now) || !Joins.solid(now) && Surface.plant(now);
            if ((replaceSolid ? Joins.solid(now) : open) && mask.allows(x, y, z)) {
                c.set(x, y, z, (String) p[3]);
            }
        }
    }

    private static int lowestWall(Canvas.Lookup at, int x, int y, int z, int floor) {
        int low = y;
        while (low - 1 >= floor && Joins.solid(at.at(x, low - 1, z)) && !Surface.plant(at.at(x, low - 1, z))) {
            low--;
        }
        return low;
    }

    /** A ledge of {@code out} pieces from a wall block outward along side {@code d}, the outer one shaped. */
    private static void ledge(Step.Trim t, List<Object[]> puts, int x, int y, int z, int d) {
        for (int k = 1; k <= t.out(); k++) {
            int px = x + Joins.STEP[d][0] * k;
            int pz = z + Joins.STEP[d][1] * k;
            String state = t.block().at(px, y, pz);
            puts.add(new Object[] {px, y, pz, shaped(state, (d + 2) % 4, !t.at().equals("base")), false});
        }
    }

    /**
     * A stair facing back toward its wall ({@code upper}: upside down, a cornice or sill), a slab in the upper or lower
     * half, a closed trapdoor likewise; other blocks as they are. Properties the step's block names are kept.
     */
    static String shaped(String state, int facing, boolean upper) {
        String id = Joins.id(state);
        String out = state;
        if (Joins.stairs(id) || id.endsWith("trapdoor")) {
            out = Joins.has(out, "facing") ? out : Joins.with(out, "facing", Joins.DIRS[facing]);
            out = Joins.has(out, "half") ? out : Joins.with(out, "half", upper ? "top" : "bottom");
        } else if (id.endsWith("_slab")) {
            out = Joins.has(out, "type") ? out : Joins.with(out, "type", upper ? "top" : "bottom");
        }
        return out;
    }

    /** Sides of a wall block that face out of the build: open to air and pointing away from its middle. */
    static List<Integer> exterior(Canvas.Lookup at, int x, int y, int z, double cx, double cz) {
        List<Integer> out = new ArrayList<>(2);
        for (int d = 0; d < 4; d++) {
            int dx = Joins.STEP[d][0];
            int dz = Joins.STEP[d][1];
            double away = dx * (x - cx) + dz * (z - cz);
            if (away > 0 && !Joins.solid(at.at(x + dx, y, z + dz))) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Whether an air block is a hole through a thin wall (a window or door), and which side is outside; -1 when not.
     * The wall beside it runs on, open on both faces, so a room's air beside a side wall does not count.
     */
    static int opening(Canvas.Lookup at, int x, int y, int z, double cx, double cz) {
        if (Joins.solid(at.at(x, y, z))) {
            return -1;
        }
        for (int d = 0; d < 4; d++) {
            int dx = Joins.STEP[d][0];
            int dz = Joins.STEP[d][1];
            if (dx * (x - cx) + dz * (z - cz) <= 0) {
                continue;
            }
            if (Joins.solid(at.at(x + dx, y, z + dz)) || Joins.solid(at.at(x - dx, y, z - dz))) {
                continue;
            }
            for (int s : new int[] {(d + 1) % 4, (d + 3) % 4}) {
                int sx = x + Joins.STEP[s][0];
                int sz = z + Joins.STEP[s][1];
                if (Joins.solid(at.at(sx, y, sz)) && !Joins.solid(at.at(sx + dx, y, sz + dz))
                        && !Joins.solid(at.at(sx - dx, y, sz - dz))) {
                    return d;
                }
            }
            // A hole as wide as its wall's gap: closed above and below in the wall plane.
            if (Joins.solid(at.at(x, y + 1, z)) && !Joins.solid(at.at(x + dx, y + 1, z + dz))
                    && !Joins.solid(at.at(x - dx, y + 1, z - dz))) {
                return d;
            }
        }
        return -1;
    }

    static Region grow(Region r, int n) {
        return Region.of(r.world(), r.min().x() - n, r.min().y() - n, r.min().z() - n, r.max().x() + n,
                r.max().y() + n, r.max().z() + n);
    }

    // ---------------------------------------------------------------- decorate

    static void decorate(Canvas c, Step.Decorate d) throws InterruptedException {
        Region box = d.box();
        Region b = d.bounds();
        Canvas.Lookup at = c.current(grow(b, 1));
        Canvas.Mask mask = c.mask(b, d.replace());
        // Candidates, then one per spacing cell (the lowest hash wins) or each by chance.
        Map<Long, long[]> best = new HashMap<>();
        List<int[]> chosen = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (!fits(at, d.on(), x, y, z)
                            || !d.soil().isEmpty() && !Canvas.matches(at.at(x, y - 1, z), d.soil())) {
                        continue;
                    }
                    long h = Noise.hash(d.seed() ^ BlockPattern.mix(y * 0x51L), x, z);
                    if (d.spacing() > 1) {
                        long cell = Noise.hash(d.seed() + 3, Math.floorDiv(x, d.spacing()) * 4099L
                                + Math.floorDiv(y, d.spacing()), Math.floorDiv(z, d.spacing()));
                        long[] seen = best.get(cell);
                        if (seen == null || Long.compareUnsigned(h, seen[3]) < 0) {
                            best.put(cell, new long[] {x, y, z, h});
                        }
                    } else if (kept(d, h, x, y, z)) {
                        chosen.add(new int[] {x, y, z});
                    }
                }
            }
        }
        for (long[] s : best.values()) {
            if (kept(d, BlockPattern.mix(s[3]), (int) s[0], (int) s[1], (int) s[2])) {
                chosen.add(new int[] {(int) s[0], (int) s[1], (int) s[2]});
            }
        }
        boolean down = d.on().equals("wall") || d.on().equals("ceiling");
        for (int[] p : chosen) {
            long h = Noise.hash(d.seed() + 17, p[0] * 31L + p[1], p[2]);
            int length = d.minLength() + (int) Long.remainderUnsigned(h, d.maxLength() - d.minLength() + 1L);
            for (int k = 0; k < length; k++) {
                int y = down ? p[1] - k : p[1] + k;
                if (k > 0 && (!BlockNames.isAir(at.at(p[0], y, p[2])) || c.planned(p[0], y, p[2]) != null
                        || d.on().equals("wall") && Joins.wallSide(at, p[0], y, p[2]) < 0)) {
                    break;
                }
                boolean last = k == length - 1 || down && (Joins.solid(at.at(p[0], y - 1, p[2]))
                        || !BlockNames.isAir(at.at(p[0], y - 1, p[2])));
                BlockPattern pick = last && d.tip() != null ? d.tip() : d.block();
                if (mask.allows(p[0], y, p[2])) {
                    c.set(p[0], y, p[2], pick.at(p[0], y, p[2]));
                }
                if (last) {
                    break;
                }
            }
        }
    }

    /** Whether an air block is a spot of the given kind. */
    static boolean fits(Canvas.Lookup at, String on, int x, int y, int z) {
        if (!BlockNames.isAir(at.at(x, y, z))) {
            return false;
        }
        boolean floor = Joins.solid(at.at(x, y - 1, z));
        return switch (on) {
            case "top" -> floor;
            case "ground" -> floor && !covered(at, x, y, z, y + 12);
            case "floor" -> floor && indoors(at, x, y, z);
            case "edge" -> floor && indoors(at, x, y, z) && Joins.wallSide(at, x, y, z) >= 0;
            case "corner" -> floor && indoors(at, x, y, z) && corner(at, x, y, z);
            case "wall" -> Joins.wallSide(at, x, y, z) >= 0 && !floor
                    && (Joins.solid(at.at(x, y + 1, z)) || Joins.wallSide(at, x, y + 1, z) < 0
                    || !BlockNames.isAir(at.at(x, y + 1, z)));
            case "ceiling" -> Joins.solid(at.at(x, y + 1, z)) && BlockNames.isAir(at.at(x, y - 1, z));
            default -> throw new IllegalStateException(on);
        };
    }

    private static boolean covered(Canvas.Lookup at, int x, int y, int z, int limit) {
        for (int up = y + 1; up <= Math.min(limit, y + 12); up++) {
            if (!BlockNames.isAir(at.at(x, up, z))) {
                return true;
            }
        }
        return false;
    }

    /** Under a ceiling and walled in on all four sides within 12 blocks: a room, not the ground under an eave. */
    private static boolean indoors(Canvas.Lookup at, int x, int y, int z) {
        if (!covered(at, x, y, z, y + 12)) {
            return false;
        }
        for (int[] d : Joins.STEP) {
            boolean wall = false;
            for (int k = 1; k <= 12 && !wall; k++) {
                wall = Joins.solid(at.at(x + d[0] * k, y, z + d[1] * k));
            }
            if (!wall) {
                return false;
            }
        }
        return true;
    }

    private static boolean corner(Canvas.Lookup at, int x, int y, int z) {
        for (int d = 0; d < 4; d++) {
            int e = (d + 1) % 4;
            if (Joins.solid(at.at(x + Joins.STEP[d][0], y, z + Joins.STEP[d][1]))
                    && Joins.solid(at.at(x + Joins.STEP[e][0], y, z + Joins.STEP[e][1]))) {
                return true;
            }
        }
        return false;
    }

    /** The chance roll, in patches when clump= is given so plants and moss gather the way they grow. */
    private static boolean kept(Step.Decorate d, long h, int x, int y, int z) {
        double p = d.chance() / 100.0;
        if (d.clump() > 0) {
            double n = Noise.gradient(d.seed() + 5, (x + 0.37 * y) / d.clump(), (z - 0.29 * y) / d.clump());
            p = Math.clamp(p * (1 + 2.2 * n), 0, 1);
        }
        return Noise.unit(h) < p;
    }

    // ---------------------------------------------------------------- weather

    /** Moss where water sits: low down and on ledges. */
    private static final Map<String, String> MOSS = Map.ofEntries(Map.entry("stone_bricks", "mossy_stone_bricks"),
            Map.entry("stone_brick_stairs", "mossy_stone_brick_stairs"),
            Map.entry("stone_brick_slab", "mossy_stone_brick_slab"),
            Map.entry("stone_brick_wall", "mossy_stone_brick_wall"),
            Map.entry("cobblestone", "mossy_cobblestone"), Map.entry("cobblestone_stairs", "mossy_cobblestone_stairs"),
            Map.entry("cobblestone_slab", "mossy_cobblestone_slab"),
            Map.entry("cobblestone_wall", "mossy_cobblestone_wall"));
    /** Cracks where frost and knocks reach: corners and edges, higher up. */
    private static final Map<String, String> CRACK = Map.of("stone_bricks", "cracked_stone_bricks",
            "deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles", "cracked_deepslate_tiles",
            "nether_bricks", "cracked_nether_bricks", "polished_blackstone_bricks", "cracked_polished_blackstone_bricks");
    private static final List<String> COPPER = List.of("cut_copper", "cut_copper_stairs", "cut_copper_slab",
            "chiseled_copper", "copper_grate", "copper_door", "copper_trapdoor", "copper_bulb");
    private static final List<String> AGES = List.of("", "exposed_", "weathered_", "oxidized_");

    /** The block {@code steps} stages of verdigris on, or null when it is not copper that ages. */
    static String oxidise(String id, int steps) {
        boolean waxed = id.startsWith("waxed_");
        String rest = waxed ? id.substring(6) : id;
        int age = 0;
        for (int i = 1; i < AGES.size(); i++) {
            if (rest.startsWith(AGES.get(i))) {
                age = i;
                rest = rest.substring(AGES.get(i).length());
            }
        }
        boolean block = rest.equals("copper_block") || age > 0 && rest.equals("copper");
        if (!block && !COPPER.contains(rest)) {
            return null;
        }
        int to = Math.min(3, age + steps);
        if (to == age) {
            return null;
        }
        String base = block ? (to == 0 ? "copper_block" : "copper") : rest;
        return (waxed ? "waxed_" : "") + AGES.get(to) + base;
    }

    static void weather(Canvas c, Step.Weather w) throws InterruptedException {
        Region box = w.box();
        Canvas.Lookup at = c.current(grow(box, 1));
        Canvas.Mask mask = c.mask(box, w.replace());
        double amount = w.amount() / 100.0;
        int h0 = box.min().y();
        int span = Math.max(1, box.max().y() - h0);
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    String s = at.at(x, y, z);
                    if (BlockNames.isAir(s) || Surface.fluid(s) || !mask.allows(x, y, z)) {
                        continue;
                    }
                    int sides = 0;
                    for (int[] d : Joins.STEP) {
                        sides += Joins.solid(at.at(x + d[0], y, z + d[1])) ? 0 : 1;
                    }
                    boolean openTop = !Joins.solid(at.at(x, y + 1, z));
                    if (sides == 0 && !openTop) {
                        continue; // hidden inside a wall: weather cannot reach it
                    }
                    double low = 1 - (double) (y - h0) / span;
                    double patch = 0.5 + 0.5 * Noise.gradient(w.seed() + 21, (x + 0.6 * y) / 5.0, (z - 0.6 * y) / 5.0);
                    double roll = Noise.unit(Noise.hash(w.seed() ^ BlockPattern.mix(y * 0x9E37L), x, z));
                    String id = Joins.id(s);
                    String to = null;
                    if (w.breakage() > 0 && openTop && sides >= 1) {
                        double p = w.breakage() / 100.0 * (0.25 + 0.75 * (1 - low)) * (sides >= 2 ? 1.4 : 0.7)
                                * (0.3 + 1.4 * patch);
                        if (roll < p) {
                            c.set(x, y, z, w.chip() == null ? "minecraft:air" : w.chip().at(x, y, z));
                            continue;
                        }
                    }
                    String swap = w.swaps().get(id);
                    String moss = MOSS.get(id);
                    String crack = CRACK.get(id);
                    double damp = amount * (0.35 + 0.9 * low * low + (openTop ? 0.35 : 0)) * (0.2 + 1.6 * patch);
                    double worn = amount * (0.3 + 0.35 * (sides >= 2 ? 1 : 0) + 0.3 * (1 - low)) * (0.2 + 1.6 * (1 - patch));
                    if (moss != null && roll < damp) {
                        to = moss;
                    } else if (crack != null && roll < worn) {
                        to = crack;
                    } else if (swap != null && roll < amount * (0.4 + 0.25 * sides) * (0.3 + 1.4 * patch)) {
                        to = swap;
                    } else {
                        double exposure = (sides + (openTop ? 2 : 0)) / 6.0;
                        int steps = (int) Math.floor(3.2 * amount * (0.3 + exposure) * (0.4 + 1.2 * patch) + roll * 0.6);
                        to = steps > 0 ? oxidise(id, steps) : null;
                    }
                    if (to != null) {
                        c.set(x, y, z, Joins.renamed(s, to));
                    }
                }
            }
        }
    }
}
