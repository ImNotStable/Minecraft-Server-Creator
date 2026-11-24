package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Boats, piers and harbours (ADR 0099): a hull that tapers to a bow and curves under the waterline with a dry hold, masts
 * as tall as the hull is long with sails on yards and booms, and basins whose banks slope to the water instead of a
 * square pit with sheer sides.
 */
final class Vessels {
    static final List<String> KINDS = List.of("rowboat", "sloop", "ship");
    static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "pale_oak");
    static final List<String> FACINGS = List.of("north", "east", "south", "west");
    private static final String AIR = "minecraft:air";
    private static final String WATER = "minecraft:water";

    private Vessels() {
    }

    static void checkKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new ToolException("unknown boat kind " + kind + "; use one of " + KINDS);
        }
    }

    static void checkWood(String wood) {
        if (!WOODS.contains(wood)) {
            throw new ToolException("unknown wood " + wood + "; use one of " + WOODS);
        }
    }

    /** {@code facing} as an index into {@link Joins#STEP} (north, east, south, west). */
    static int facing(String facing) {
        int f = FACINGS.indexOf(facing);
        if (f < 0) {
            throw new ToolException("facing takes north, east, south or west (the way the bow points), got " + facing);
        }
        return f;
    }

    /** A boat's proportions: length, beam (odd), blocks below the waterline, blocks above it up to the deck. */
    record Size(int length, int beam, int draft, int freeboard) {
        static Size of(String kind, int length) {
            int l = length > 0 ? length : switch (kind) {
                case "rowboat" -> 7;
                case "sloop" -> 14;
                default -> 26;
            };
            return switch (kind) {
                case "rowboat" -> new Size(l, 3, 1, 1);
                case "sloop" -> new Size(l, Math.max(5, l / 3 | 1), 2, 2);
                default -> new Size(l, Math.max(7, l / 3 | 1), 3, 3);
            };
        }
    }

    /** Mast heights above the deck, as fractions of the length, and where each stands (0 stern, 1 bow). */
    static double[][] masts(String kind, int length) {
        return switch (kind) {
            case "rowboat" -> new double[0][];
            case "sloop" -> new double[][] {{0.58, 0.85}};
            default -> length >= 24 ? new double[][] {{0.3, 0.55}, {0.53, 0.75}, {0.78, 0.66}}
                    : new double[][] {{0.38, 0.62}, {0.68, 0.72}};
        };
    }

    /** Half the hull's width at {@code t} along it (0 stern, 1 bow) and {@code depth} up it (0 keel, 1 gunwale). */
    static double halfBeam(double t, double depth, double half) {
        double along = t < 0.2 ? 0.78 + 0.22 * Math.sin(Math.PI / 2 * t / 0.2) // a rounded stern into a transom
                : t > 0.5 ? Math.sqrt(Math.max(0, 1 - Math.pow((t - 0.5) / 0.5, 2))) // a pointed, curved bow
                : 1;
        return half * along * (0.35 + 0.65 * Math.sqrt(depth));
    }

    /** A boat's frame: {@code u} along it (0 the stern), {@code v} across (+ to starboard), {@code k} up from the keel. */
    record Frame(int cx, int cz, int keelY, int f, int length) {
        int x(int u, int v) {
            int[] fw = Joins.STEP[f];
            int[] right = Joins.STEP[(f + 1) % 4];
            return cx + (u - length / 2) * fw[0] + v * right[0];
        }

        int z(int u, int v) {
            int[] fw = Joins.STEP[f];
            int[] right = Joins.STEP[(f + 1) % 4];
            return cz + (u - length / 2) * fw[1] + v * right[1];
        }

        /** The side a block at {@code v} faces to look in toward the centre line. */
        String inward(int v) {
            return FACINGS.get(v > 0 ? (f + 3) % 4 : (f + 1) % 4);
        }

        String forward() {
            return FACINGS.get(f);
        }

        String back() {
            return FACINGS.get((f + 2) % 4);
        }

        String lengthAxis() {
            return f % 2 == 0 ? "z" : "x";
        }

        String acrossAxis() {
            return f % 2 == 0 ? "x" : "z";
        }
    }

    /** The box a boat may touch: its hull, bowsprit, rudder and rig. */
    static Region boatBounds(Step.Boat b) {
        Size s = b.size();
        int half = s.beam() / 2 + 3;
        int bowsprit = b.kind().equals("rowboat") ? 0 : s.length() / 5 + 1;
        int tallest = 0;
        for (double[] m : masts(b.kind(), s.length())) {
            tallest = Math.max(tallest, (int) Math.round(m[1] * s.length()));
        }
        Frame fr = new Frame(b.at().x(), b.at().z(), 0, b.facing(), s.length());
        int x1 = fr.x(-2, -half);
        int z1 = fr.z(-2, -half);
        int x2 = fr.x(s.length() + bowsprit + 1, half);
        int z2 = fr.z(s.length() + bowsprit + 1, half);
        int water = b.at().y();
        return Region.of(b.world(), Math.min(x1, x2), water - s.draft(), Math.min(z1, z2), Math.max(x1, x2),
                water + s.freeboard() + tallest + 4, Math.max(z1, z2));
    }

    /** Draw a boat whose midship waterline is {@code b.at()}: the block at its y is the water's surface. */
    static void boat(Canvas c, Step.Boat b) {
        Size s = b.size();
        int len = s.length();
        int levels = s.draft() + s.freeboard();
        int keelY = b.at().y() - s.draft() + 1;
        Frame fr = new Frame(b.at().x(), b.at().z(), keelY, b.facing(), len);
        String wood = "minecraft:" + b.wood();
        String planks = wood + "_planks";
        boolean rowboat = b.kind().equals("rowboat");
        boolean ship = b.kind().equals("ship");
        int[][] w = new int[len][levels + 3];
        int[] top = new int[len]; // the deck's level at each slice (raised at bow and stern)
        double half = s.beam() / 2.0;
        for (int u = 0; u < len; u++) {
            double t = len == 1 ? 0.5 : u / (double) (len - 1);
            int bow = rowboat ? 0 : t > 0.86 ? (ship ? 2 : 1) : 0;
            int stern = rowboat ? 0 : t < (ship ? 0.22 : 0.12) ? (ship ? 2 : 1) : 0;
            top[u] = levels - 1 + Math.max(bow, stern);
            for (int k = 0; k < w[u].length; k++) {
                w[u][k] = -1;
            }
            for (int k = 0; k <= top[u]; k++) {
                double depth = Math.min(1, k / (double) Math.max(1, levels - 1));
                // The keel is shorter than the deck: the bow rakes forward and the stern overhangs.
                double lo = 0.04 * (1 - depth);
                double hi = 1 - 0.12 * (1 - depth);
                if (t < lo || t > hi) {
                    continue;
                }
                w[u][k] = (int) Math.round(halfBeam((t - lo) / (hi - lo), depth, half));
            }
        }
        for (int u = 0; u < len; u++) {
            for (int k = 0; k <= top[u]; k++) {
                int width = w[u][k];
                if (width < 0) {
                    continue;
                }
                int y = keelY + k;
                int below = k == 0 ? -1 : w[u][k - 1];
                int prev = u == 0 ? -1 : k < w[u - 1].length ? w[u - 1][k] : -1;
                int next = u == len - 1 ? -1 : k < w[u + 1].length ? w[u + 1][k] : -1;
                for (int v = -width; v <= width; v++) {
                    int a = Math.abs(v);
                    // Skin wherever the hold would be open to the water: the sides, under a wider level, at the ends.
                    boolean skin = k == 0 || a >= width || a > below || a > prev || a > next;
                    String state;
                    if (k >= levels - 1 && !(rowboat && k == levels - 1)) {
                        // The deck, and solid raised decks at bow and stern.
                        state = skin ? b.hull().at(fr.x(u, v), y, fr.z(u, v)) : b.deck().at(fr.x(u, v), y, fr.z(u, v));
                    } else if (skin) {
                        if (k == 0 && v == 0 && !rowboat) {
                            state = wood + "_log[axis=" + fr.lengthAxis() + "]";
                        } else if (k > 0 && a == width && a > below && width > 0) {
                            // Upside-down stairs round the bilge where the hull steps out.
                            state = wood + "_stairs[facing=" + fr.inward(v) + ",half=top,shape=straight,waterlogged=false]";
                        } else {
                            state = b.hull().at(fr.x(u, v), y, fr.z(u, v));
                        }
                    } else {
                        state = AIR; // a dry hold below the waterline
                    }
                    c.set(fr.x(u, v), y, fr.z(u, v), state);
                }
            }
        }
        String fence = wood + "_fence[waterlogged=false]";
        String lantern = "minecraft:lantern[hanging=false,waterlogged=false]";
        String hanging = "minecraft:lantern[hanging=true,waterlogged=false]";
        if (rowboat) {
            // Thwarts: plank seats across the open boat.
            for (int u = 2; u < len - 2; u += 2) {
                int width = w[u][levels - 1];
                for (int v = -width + 1; v <= width - 1; v++) {
                    c.set(fr.x(u, v), keelY + levels - 1, fr.z(u, v),
                            wood + "_slab[type=bottom,waterlogged=false]");
                }
            }
            return;
        }
        // Rails along the gunwale, lanterns at bow and stern.
        for (int u = 0; u < len; u++) {
            int k = top[u];
            int width = w[u][k];
            if (width < 0) {
                continue;
            }
            int y = keelY + k + 1;
            int prev = u == 0 ? -1 : w[u - 1][Math.min(k, top[u - 1])];
            int next = u == len - 1 ? -1 : w[u + 1][Math.min(k, top[u + 1])];
            for (int v = -width; v <= width; v++) {
                int a = Math.abs(v);
                boolean edge = a == width || a > prev || a > next || u > 0 && top[u - 1] != k || u < len - 1 && top[u + 1] != k;
                if (edge && !(a < width && (u > 0 && top[u - 1] > k || u < len - 1 && top[u + 1] > k))) {
                    c.set(fr.x(u, v), y, fr.z(u, v), fence);
                }
            }
        }
        int sternU = firstDeck(w, top, 0, 1);
        int bowU = firstDeck(w, top, len - 1, -1);
        c.set(fr.x(sternU, 0), keelY + top[sternU] + 2, fr.z(sternU, 0), lantern);
        // Hold: lanterns under the deck and barrels along the sides.
        int holdTop = keelY + levels - 2;
        if (levels - 2 >= 1) {
            for (int u = 3; u < len - 3; u++) {
                if (w[u][levels - 2] < 2) {
                    continue;
                }
                if (u % 6 == 3) {
                    c.set(fr.x(u, 0), holdTop, fr.z(u, 0), hanging);
                }
                if (u % 3 == 1) {
                    int width = w[u][1];
                    for (int v : new int[] {-(width - 1), width - 1}) {
                        if (w[u][1] >= 2) {
                            c.set(fr.x(u, v), keelY + 1, fr.z(u, v), "minecraft:barrel[facing=up,open=false]");
                        }
                    }
                }
            }
        }
        // Rudder astern, hung on the raked sternpost.
        for (int k = 0; k < levels - 1; k++) {
            int u = 0;
            while (u < len && w[u][k] < 0) {
                u++;
            }
            c.set(fr.x(u - 1, 0), keelY + k, fr.z(u - 1, 0), planks);
        }
        int deckY = keelY + levels - 1;
        // Bowsprit: a spar forward of the bow, at the raised bow's rail.
        int bowY = keelY + top[bowU] + 1;
        int sprit = len / 5;
        for (int j = 1; j <= sprit; j++) {
            c.set(fr.x(bowU + j, 0), bowY, fr.z(bowU + j, 0), wood + "_log[axis=" + fr.lengthAxis() + "]");
        }
        c.set(fr.x(bowU, 0), bowY + 1, fr.z(bowU, 0), lantern);
        String stripped = "minecraft:stripped_" + b.wood() + "_log[axis=" + fr.acrossAxis() + "]";
        String boom = "minecraft:stripped_" + b.wood() + "_log[axis=" + fr.lengthAxis() + "]";
        double[][] masts = masts(b.kind(), len);
        for (int m = 0; m < masts.length; m++) {
            int mu = (int) Math.round(masts[m][0] * (len - 1));
            int mastTop = deckY + (int) Math.round(masts[m][1] * len);
            for (int y = keelY + 1; y <= mastTop; y++) {
                c.set(fr.x(mu, 0), y, fr.z(mu, 0), y >= mastTop - 1 ? fence : wood + "_log[axis=y]");
            }
            if (!ship) {
                // A sloop: a gaff mainsail aft of the mast over a boom, and a jib to the bowsprit.
                int boomLen = (int) Math.round(0.42 * len);
                int footY = deckY + 3;
                for (int a = 1; a <= boomLen; a++) {
                    c.set(fr.x(mu - a, 0), footY - 1, fr.z(mu - a, 0), boom);
                }
                Set<Long> sail = new HashSet<>();
                for (int a = 1; a <= boomLen; a++) {
                    int peak = mastTop - 2 - (int) Math.round((mastTop - 2 - footY) * 0.7 * a / (double) boomLen);
                    for (int y = footY; y <= peak; y++) {
                        sail.add(key(-a, y));
                    }
                }
                sheet(c, fr, b, mu, sail, true);
                int tipU = bowU + sprit;
                int jib = tipU - mu;
                Set<Long> jibSail = new HashSet<>();
                for (int a = 2; a < jib; a++) {
                    int foot = deckY + 2 + (int) Math.round((bowY - deckY - 1) * a / (double) jib);
                    int head = mastTop - 3 - (int) Math.round((mastTop - 3 - bowY) * a / (double) jib);
                    for (int y = foot; y <= head; y++) {
                        jibSail.add(key(a, y));
                    }
                }
                sheet(c, fr, b, mu, jibSail, true);
            } else {
                // A ship: square sails on yards across the mast, two or three tiers, each billowing forward.
                int base = deckY + 3;
                int span = mastTop - 2 - base;
                int tiers = span >= 14 ? 3 : 2;
                int tierH = span / tiers;
                for (int t = 0; t < tiers; t++) {
                    int lo = base + t * tierH;
                    int hi = lo + tierH - 2;
                    int yard = Math.max(2, w[mu][levels - 1] + 1 - t);
                    for (int v = -yard - 1; v <= yard + 1; v++) {
                        c.set(fr.x(mu + 1, v), hi + 1, fr.z(mu + 1, v), stripped);
                    }
                    Set<Long> sq = new HashSet<>();
                    for (int v = -yard; v <= yard; v++) {
                        for (int y = lo; y <= hi; y++) {
                            sq.add(key(v, y));
                        }
                    }
                    sheet(c, fr, b, mu, sq, false);
                }
                // A fighting top on the main mast: a platform of slabs with a rail.
                if (m == masts.length / 2) {
                    int platform = base + tierH * (tiers - 1) - 1;
                    for (int du = -1; du <= 1; du++) {
                        for (int v = -1; v <= 1; v++) {
                            if (du != 0 || v != 0) {
                                c.set(fr.x(mu + du, v), platform, fr.z(mu + du, v),
                                        wood + "_slab[type=top,waterlogged=false]");
                            }
                        }
                    }
                }
            }
        }
    }

    private static int firstDeck(int[][] w, int[] top, int from, int step) {
        int u = from;
        while (w[u][top[u]] < 0) {
            u += step;
        }
        return u;
    }

    private static long key(int a, int y) {
        return ((long) a << 32) | (y & 0xffffffffL);
    }

    /**
     * Wool over {@code cells} ({@code a} along the boat from the mast for a fore-and-aft sail, across it for a square
     * one), bellied one block out in the middle and joined to the flat edge at each step, so it reads as a curved sheet.
     */
    private static void sheet(Canvas c, Frame fr, Step.Boat b, int mu, Set<Long> cells, boolean foreAndAft) {
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (long k : cells) {
            int a = (int) (k >> 32);
            int y = (int) k;
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        for (long k : cells) {
            int a = (int) (k >> 32);
            int y = (int) k;
            int belly = belly(cells, a, y, minA, maxA, minY, maxY);
            boolean step = false;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long n = key(a + d[0], y + d[1]);
                step |= cells.contains(n) && belly(cells, a + d[0], y + d[1], minA, maxA, minY, maxY) != belly;
            }
            for (int out = belly; out >= (step ? 0 : belly); out--) {
                int u = foreAndAft ? mu + a : mu + 1 + out;
                int v = foreAndAft ? out : a;
                c.set(fr.x(u, v), y, fr.z(u, v), b.sail().at(fr.x(u, v), y, fr.z(u, v)));
            }
        }
    }

    /** 1 in the middle of a sail, 0 at its edges. */
    private static int belly(Set<Long> cells, int a, int y, int minA, int maxA, int minY, int maxY) {
        int ea = Math.min(a - minA, maxA - a);
        int ey = Math.min(y - minY, maxY - y);
        int needA = Math.max(1, (maxA - minA) / 4);
        int needY = Math.max(1, (maxY - minY) / 4);
        return ea >= needA && ey >= needY && cells.contains(key(a, y)) ? 1 : 0;
    }

    /** A pier's box: its deck, its posts down to the bed, and the rails and lanterns on it. */
    static Region pierBounds(Step.Pier p) {
        Region d = p.deck();
        return Region.of(d.world(), d.min().x(), d.min().y() - POST_REACH, d.min().z(), d.max().x(), d.min().y() + 2,
                d.max().z());
    }

    static final int POST_REACH = 40;

    /** A pier: a plank deck on log posts that reach the bed, rails along the sides, lanterns at the far end. */
    static void pier(Canvas c, Step.Pier p) throws InterruptedException {
        Region d = p.deck();
        int y = d.min().y();
        boolean alongX = d.sizeX() >= d.sizeZ();
        String wood = "minecraft:" + p.wood();
        Canvas.Lookup at = c.current(pierBounds(p));
        // The land end is the one whose posts are shortest.
        int lenAxis = alongX ? d.sizeX() : d.sizeZ();
        int a0 = alongX ? d.min().x() : d.min().z();
        int b0 = alongX ? d.min().z() : d.min().x();
        int b1 = alongX ? d.max().z() : d.max().x();
        int firstDepth = depth(at, alongX, a0, (b0 + b1) / 2, y);
        int lastDepth = depth(at, alongX, a0 + lenAxis - 1, (b0 + b1) / 2, y);
        int far = lastDepth >= firstDepth ? a0 + lenAxis - 1 : a0;
        for (int a = a0; a < a0 + lenAxis; a++) {
            boolean post = (a - a0) % 4 == 0 || a == a0 + lenAxis - 1;
            for (int b = b0; b <= b1; b++) {
                int x = alongX ? a : b;
                int z = alongX ? b : a;
                c.set(x, y, z, p.deckBlock().at(x, y, z));
                boolean side = b == b0 || b == b1;
                if (side && post) {
                    int floor = depth(at, alongX, a, b, y);
                    for (int yy = y - 1; yy > y - 1 - floor; yy--) {
                        c.set(x, yy, z, wood + "_log[axis=y]");
                    }
                }
                if (side) {
                    c.set(x, y + 1, z, wood + "_fence[waterlogged=false]");
                }
                if (side && (a == far || post && (a - a0) % 8 == 0 && a != a0)) {
                    c.set(x, y + 2, z, "minecraft:lantern[hanging=false,waterlogged=false]");
                }
            }
        }
    }

    /** Blocks of air or water under the deck at a spot, down to the first solid block. */
    private static int depth(Canvas.Lookup at, boolean alongX, int a, int b, int y) {
        int x = alongX ? a : b;
        int z = alongX ? b : a;
        int n = 0;
        while (n < POST_REACH) {
            String s = at.at(x, y - 1 - n, z);
            if (!BlockNames.isAir(s) && !s.startsWith(WATER) && !s.contains("seagrass") && !s.contains("kelp")) {
                break;
            }
            n++;
        }
        return n;
    }

    /** The box a harbour may touch: its basin, from the bed up past the land around it. */
    static Region harborBounds(Step.Harbor h) {
        Region b = h.box();
        return Region.of(b.world(), b.min().x(), h.water() - h.depth() - 2, b.min().z(), b.max().x(),
                Math.max(b.max().y(), h.water()) + Step.Harbor.LAND_REACH, b.max().z());
    }

    /**
     * A harbour basin in {@code h.box()}: water at {@code h.water()} over a bed {@code h.depth()} deep, banks that drop
     * one block every {@code h.bank()} blocks in from the edge with a ragged line, a beach where they meet the water, and
     * on the {@code quay} side a stone quay with ladders and lamp posts, from which piers run out.
     */
    static void harbor(Canvas c, Step.Harbor h) throws InterruptedException {
        Region box = h.box();
        Region all = harborBounds(h);
        Canvas.Lookup at = c.current(all);
        int water = h.water();
        int bed = water - h.depth();
        int q = h.quay() == null ? -1 : FACINGS.indexOf(h.quay());
        for (int z = box.min().z(); z <= box.max().z(); z++) {
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                int land = all.min().y();
                for (int y = all.max().y(); y >= all.min().y(); y--) {
                    if (solid(at.at(x, y, z))) {
                        land = y;
                        break;
                    }
                }
                int[] in = {z - box.min().z(), box.max().x() - x, box.max().z() - z, x - box.min().x()};
                int edge = Integer.MAX_VALUE;
                for (int s = 0; s < 4; s++) {
                    if (s != q) {
                        edge = Math.min(edge, in[s]);
                    }
                }
                if (q >= 0 && in[q] == 0) {
                    quay(c, h, at, x, z, land, bed);
                    continue;
                }
                double ragged = Noise.fbm(h.seed(), x, z, 12, 3) * 4;
                int surface = Math.min(land, Math.max(bed, land - (int) Math.floor((edge + ragged) / h.bank())));
                String top = at.at(x, land, z);
                for (int y = all.max().y(); y > surface; y--) {
                    if (y > land && !solid(at.at(x, y, z)) && y > water) {
                        continue;
                    }
                    c.set(x, y, z, y <= water ? WATER : AIR);
                }
                for (int y = surface; y > surface - 2; y--) {
                    String block = surface <= water - 2 ? h.bed().at(x, y, z)
                            : surface <= water + 1 ? h.shore().at(x, y, z)
                            : y == surface && surface < land ? top : null;
                    c.set(x, y, z, block);
                }
            }
        }
        if (q >= 0 && h.piers() > 0) {
            int[] fw = Joins.STEP[(q + 2) % 4];
            boolean alongX = fw[0] == 0; // the quay runs along x when the piers go along z
            int start = alongX ? box.min().x() : box.min().z();
            int span = alongX ? box.sizeX() : box.sizeZ();
            int reach = (int) Math.round((alongX ? box.sizeZ() : box.sizeX()) * 0.55);
            int edgeLine = q == 0 ? box.min().z() : q == 1 ? box.max().x() : q == 2 ? box.max().z() : box.min().x();
            for (int i = 0; i < h.piers(); i++) {
                int mid = start + (int) Math.round(span * (i + 1) / (double) (h.piers() + 1));
                int from = edgeLine + (alongX ? fw[1] : fw[0]);
                int to = from + (reach - 1) * (alongX ? fw[1] : fw[0]);
                Region deck = alongX
                        ? Region.of(box.world(), mid - 1, water + 1, Math.min(from, to), mid + 1, water + 1, Math.max(from, to))
                        : Region.of(box.world(), Math.min(from, to), water + 1, mid - 1, Math.max(from, to), water + 1, mid + 1);
                pier(c, new Step.Pier(deck, h.wood(), BlockPattern.single("minecraft:" + h.wood() + "_planks")));
            }
        }
    }

    /** One column of the quay: a wall from the bed to the quay's top, a ladder down the face, a lamp post on top. */
    private static void quay(Canvas c, Step.Harbor h, Canvas.Lookup at, int x, int z, int land, int bed) {
        int water = h.water();
        int topY = Math.max(water + 1, land);
        for (int y = bed - 1; y <= topY; y++) {
            c.set(x, y, z, h.quayBlock().at(x, y, z));
        }
        for (int y = topY + 1; y <= topY + Step.Harbor.LAND_REACH && !BlockNames.isAir(at.at(x, y, z)); y++) {
            c.set(x, y, z, AIR);
        }
        int q = FACINGS.indexOf(h.quay());
        int along = q % 2 == 0 ? x : z;
        int[] in = Joins.STEP[(q + 2) % 4];
        if (Math.floorMod(along, 10) == 5) {
            for (int y = bed + 1; y <= topY; y++) {
                c.set(x + in[0], y, z + in[1], "minecraft:ladder[facing=" + FACINGS.get((q + 2) % 4) + ",waterlogged="
                        + (y <= water) + "]");
            }
        }
        if (Math.floorMod(along, 8) == 1) {
            c.set(x, topY + 1, z, "minecraft:" + h.wood() + "_fence[waterlogged=false]");
            c.set(x, topY + 2, z, "minecraft:lantern[hanging=false,waterlogged=false]");
        }
    }

    private static boolean solid(String state) {
        if (BlockNames.isAir(state) || state.startsWith(WATER) || state.startsWith("minecraft:lava")) {
            return false;
        }
        String id = BlockNames.brief(BlockNames.id(state));
        return !(id.contains("grass") && !id.equals("grass_block")) && !id.contains("flower") && !id.contains("fern")
                && !id.contains("seagrass") && !id.contains("kelp") && !id.endsWith("_sapling") && !id.equals("snow");
    }
}
