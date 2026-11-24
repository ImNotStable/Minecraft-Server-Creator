package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Boats and harbours as a hand would not make them (ADR 0099), from the Shattered Meridian screenshots: a hull that is a
 * box of planks the same width bow to stern, sitting on the water instead of in it, a stump of a mast with no sail, and
 * a harbour dug as a square pit with sheer sides. MSC has no boat or harbour generator (ADR 0101): the agent builds
 * them from the general steps, and these checks say what a good one has.
 */
final class WaterReview {
    static final int MIN_HULL = 6;
    static final int MIN_RIGGED = 10;
    static final int MIN_SAIL = 12;
    static final int BOX_SHARE = 85;
    static final int MIN_BASIN = 40;
    static final int MIN_SHORE = 20;
    static final int MAX_SHEER = 60;
    static final int SQUARE_FILL = 90;
    private static final int MAX_GROUP = 50_000;
    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] FOUR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private WaterReview() {
    }

    /** {@code names}: brief ids by palette index, null for air and fluids; {@code fluid}: water and lava. */
    static List<String> fixes(BlockGrid grid, String[] names, boolean[] fluid, boolean[] isGround) {
        List<String> fixes = new ArrayList<>(vessels(grid, names, fluid, isGround));
        fixes.addAll(basins(grid, names, fluid, isGround));
        return fixes;
    }

    private static boolean water(BlockGrid grid, boolean[] fluid, String[] names, int x, int y, int z) {
        if (!grid.region().contains(x, y, z)) {
            return false;
        }
        int b = grid.blocks()[grid.region().index(x, y, z)];
        return b >= 0 && fluid[b] && "water".equals(BlockNames.brief(BlockNames.id(grid.palette().get(b))));
    }

    private static String name(BlockGrid grid, String[] names, int x, int y, int z) {
        if (!grid.region().contains(x, y, z)) {
            return null;
        }
        int b = grid.blocks()[grid.region().index(x, y, z)];
        return b < 0 ? null : names[b];
    }

    /** Groups of built blocks resting on water and joined to no land: boats. */
    static List<String> vessels(BlockGrid grid, String[] names, boolean[] fluid, boolean[] isGround) {
        Region r = grid.region();
        boolean[] seen = new boolean[grid.blocks().length];
        int boxy = 0;
        int dry = 0;
        int bare = 0;
        int stumps = 0;
        int flat = 0;
        String firstFlat = null;
        String firstBox = null;
        String firstDry = null;
        String firstBare = null;
        String firstStump = null;
        for (int y = r.min().y() + 1; y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    int i = r.index(x, y, z);
                    if (seen[i] || name(grid, names, x, y, z) == null || isGround[i] || !water(grid, fluid, names, x, y - 1, z)) {
                        continue;
                    }
                    Vessel v = flood(grid, names, fluid, isGround, seen, x, y, z);
                    if (v == null || v.onWater < MIN_HULL) {
                        continue;
                    }
                    String at = x + "," + y + "," + z;
                    if (v.boxy()) {
                        boxy++;
                        firstBox = firstBox == null ? at : firstBox;
                    }
                    if (!v.inWater) {
                        dry++;
                        firstDry = firstDry == null ? at : firstDry;
                    }
                    if (v.length() >= MIN_RIGGED && v.mast > 0 && v.sail == 0) {
                        bare++;
                        firstBare = firstBare == null ? at : firstBare;
                    }
                    if (v.sail >= MIN_SAIL && v.flatSail()) {
                        flat++;
                        firstFlat = firstFlat == null ? at : firstFlat;
                    }
                    if (v.length() >= MIN_RIGGED && v.mast > 0 && v.mast * 2 < v.length()) {
                        stumps++;
                        firstStump = firstStump == null ? at : firstStump;
                    }
                }
            }
        }
        List<String> fixes = new ArrayList<>();
        if (boxy > 0) {
            fixes.add("box hull: " + boxy + " boat" + plural(boxy) + " (first at " + firstBox + ") keep one width from "
                    + "bow to stern, a crate on the water; taper the hull to a pointed bow and a rounded stern, narrow each "
                    + "layer toward the keel and round the steps with upside-down stairs (guide=build, boats)");
        }
        if (dry > 0) {
            fixes.add("floating hull: " + dry + " boat" + plural(dry) + " (first at " + firstDry + ") sit on the "
                    + "water's surface; sink the hull 1-3 blocks below the waterline with the hold kept dry (air "
                    + "inside the planks)");
        }
        if (bare > 0) {
            fixes.add("bare mast: " + bare + " boat" + plural(bare) + " (first at " + firstBare + ") carry a mast with "
                    + "no sail; hang wool sails from yards across the mast (square sails) or along a boom (a fore-and-aft "
                    + "sail), bellied 1 block in the middle");
        }
        if (stumps > 0) {
            fixes.add("stump mast: " + stumps + " boat" + plural(stumps) + " (first at " + firstStump + ") have a mast "
                    + "under half the hull's length; a mast stands about as tall above the deck as the hull is long");
        }
        if (flat > 0) {
            fixes.add("flat sails: " + flat + " boat" + plural(flat) + " (first at " + firstFlat + ") carry sails that "
                    + "are one flat sheet; belly each sail: its middle one block forward of its edges, both blocks "
                    + "filled where it steps");
        }
        return fixes;
    }

    private static String plural(int n) {
        return n == 1 ? "" : "s";
    }

    /** A boat's measures: blocks over water, whether the hull dips into it, slice widths, mast height and sail. */
    private static final class Vessel {
        int onWater;
        boolean inWater;
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int[] lo;
        int[] hi;
        int mast;
        int sail;
        /** Sail blocks with sail beside them along both x and z: where a bellied sheet steps out. */
        int sailSteps;

        boolean flatSail() {
            return sailSteps == 0;
        }

        int length() {
            return maxA - minA + 1;
        }

        /** The hull's slices along its length nearly all as wide as the widest. */
        boolean boxy() {
            int widest = 0;
            for (int a = 0; a < lo.length; a++) {
                widest = Math.max(widest, lo[a] == Integer.MAX_VALUE ? 0 : hi[a] - lo[a] + 1);
            }
            if (widest < 3 || length() < 5) {
                return false;
            }
            int full = 0;
            int slices = 0;
            for (int a = minA; a <= maxA; a++) {
                if (lo[a] == Integer.MAX_VALUE) {
                    continue;
                }
                slices++;
                full += hi[a] - lo[a] + 1 >= widest ? 1 : 0;
            }
            return full * 100 >= slices * BOX_SHARE;
        }
    }

    private static Vessel flood(BlockGrid grid, String[] names, boolean[] fluid, boolean[] isGround, boolean[] seen,
                                int sx, int sy, int sz) {
        Region r = grid.region();
        List<int[]> cells = new ArrayList<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[] {sx, sy, sz});
        seen[r.index(sx, sy, sz)] = true;
        boolean land = false;
        while (!todo.isEmpty()) {
            int[] p = todo.poll();
            cells.add(p);
            if (cells.size() > MAX_GROUP) {
                land = true;
            }
            for (int[] d : SIX) {
                int x = p[0] + d[0];
                int y = p[1] + d[1];
                int z = p[2] + d[2];
                if (!r.contains(x, y, z) || name(grid, names, x, y, z) == null) {
                    continue;
                }
                int i = r.index(x, y, z);
                if (isGround[i]) {
                    land = true; // moored to the bank or standing on the bed: a pier, a house, not a boat
                    continue;
                }
                if (!seen[i]) {
                    seen[i] = true;
                    todo.add(new int[] {x, y, z});
                }
            }
        }
        // Touching the region's sides: part of something bigger, judged where it is whole.
        for (int[] p : cells) {
            land |= p[0] == r.min().x() || p[0] == r.max().x() || p[2] == r.min().z() || p[2] == r.max().z();
        }
        if (land) {
            return null;
        }
        Vessel v = new Vessel();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] p : cells) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        boolean alongX = maxX - minX >= maxZ - minZ;
        int base = alongX ? minX : minZ;
        int slices = (alongX ? maxX - minX : maxZ - minZ) + 1;
        v.lo = new int[slices];
        v.hi = new int[slices];
        java.util.Arrays.fill(v.lo, Integer.MAX_VALUE);
        java.util.Arrays.fill(v.hi, Integer.MIN_VALUE);
        v.minA = 0;
        v.maxA = slices - 1;
        java.util.Map<Long, Integer> column = new java.util.HashMap<>();
        for (int[] p : cells) {
            String n = name(grid, names, p[0], p[1], p[2]);
            if (water(grid, fluid, names, p[0], p[1] - 1, p[2])) {
                v.onWater++;
                int a = (alongX ? p[0] : p[2]) - base;
                int across = alongX ? p[2] : p[0];
                v.lo[a] = Math.min(v.lo[a], across);
                v.hi[a] = Math.max(v.hi[a], across);
            }
            for (int[] d : FOUR) {
                v.inWater |= water(grid, fluid, names, p[0] + d[0], p[1], p[2] + d[1]);
            }
            if (n.endsWith("_wool") || n.contains("banner")) {
                v.sail++;
                boolean besideX = sail(name(grid, names, p[0] + 1, p[1], p[2])) || sail(name(grid, names, p[0] - 1, p[1], p[2]));
                boolean besideZ = sail(name(grid, names, p[0], p[1], p[2] + 1)) || sail(name(grid, names, p[0], p[1], p[2] - 1));
                v.sailSteps += besideX && besideZ ? 1 : 0;
            }
            if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_fence") || n.endsWith("_wall")) {
                String under = name(grid, names, p[0], p[1] - 1, p[2]);
                boolean starts = under == null || !(under.endsWith("_log") || under.endsWith("_wood")
                        || under.endsWith("_fence") || under.endsWith("_wall"));
                if (starts) {
                    int h = 0;
                    String up = n;
                    while (up != null && (up.endsWith("_log") || up.endsWith("_wood") || up.endsWith("_fence")
                            || up.endsWith("_wall"))) {
                        h++;
                        up = name(grid, names, p[0], p[1] + h, p[2]);
                    }
                    if (h >= 4) {
                        column.merge(((long) p[0] << 32) | (p[2] & 0xffffffffL), h, Math::max);
                    }
                }
            }
        }
        for (int h : column.values()) {
            v.mast = Math.max(v.mast, h);
        }
        return v;
    }

    private static boolean sail(String n) {
        return n != null && (n.endsWith("_wool") || n.contains("banner"));
    }

    /**
     * Bodies of water with a free surface whose natural banks rise straight out of it, or whose shape fills its box: a
     * harbour or pond dug as a pit.
     */
    static List<String> basins(BlockGrid grid, String[] names, boolean[] fluid, boolean[] isGround) {
        Region r = grid.region();
        int sx = r.sizeX();
        int sz = r.sizeZ();
        boolean[] seen = new boolean[grid.blocks().length];
        int sheer = 0;
        int square = 0;
        String firstSheer = null;
        String firstSquare = null;
        int worstSheer = 0;
        for (int y = r.min().y(); y < r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    int i = r.index(x, y, z);
                    if (seen[i] || !surface(grid, names, fluid, x, y, z)) {
                        continue;
                    }
                    ArrayDeque<int[]> todo = new ArrayDeque<>();
                    todo.add(new int[] {x, z});
                    seen[i] = true;
                    int area = 0;
                    int banks = 0;
                    int cliffs = 0;
                    boolean edge = false;
                    int minX = x;
                    int maxX = x;
                    int minZ = z;
                    int maxZ = z;
                    while (!todo.isEmpty()) {
                        int[] p = todo.poll();
                        area++;
                        minX = Math.min(minX, p[0]);
                        maxX = Math.max(maxX, p[0]);
                        minZ = Math.min(minZ, p[1]);
                        maxZ = Math.max(maxZ, p[1]);
                        for (int[] d : FOUR) {
                            int nx = p[0] + d[0];
                            int nz = p[1] + d[1];
                            if (!r.contains(nx, y, nz)) {
                                edge = true;
                                continue;
                            }
                            int ni = r.index(nx, y, nz);
                            if (surface(grid, names, fluid, nx, y, nz) || water(grid, fluid, names, nx, y, nz)) {
                                if (!seen[ni] && surface(grid, names, fluid, nx, y, nz)) {
                                    seen[ni] = true;
                                    todo.add(new int[] {nx, nz});
                                }
                                continue;
                            }
                            if (name(grid, names, nx, y, nz) != null && isGround[ni]) {
                                banks++;
                                // The bank rises two more blocks straight up out of the water: a cut, not a shore.
                                boolean cliff = r.contains(nx, y + 2, nz) && name(grid, names, nx, y + 1, nz) != null
                                        && name(grid, names, nx, y + 2, nz) != null;
                                cliffs += cliff ? 1 : 0;
                            }
                        }
                    }
                    if (area < MIN_BASIN) {
                        continue;
                    }
                    String at = x + "," + y + "," + z;
                    if (banks >= MIN_SHORE && cliffs * 100 >= banks * MAX_SHEER) {
                        sheer++;
                        worstSheer = Math.max(worstSheer, cliffs * 100 / banks);
                        firstSheer = firstSheer == null ? at : firstSheer;
                    }
                    int boxArea = (maxX - minX + 1) * (maxZ - minZ + 1);
                    if (!edge && Math.min(maxX - minX, maxZ - minZ) >= 5 && area * 100 >= boxArea * SQUARE_FILL
                            && banks * 2 >= perimeter(maxX - minX + 1, maxZ - minZ + 1)) {
                        square++;
                        firstSquare = firstSquare == null ? at : firstSquare;
                    }
                }
            }
        }
        List<String> fixes = new ArrayList<>();
        if (sheer > 0) {
            fixes.add("sheer banks: " + sheer + " bod" + (sheer == 1 ? "y" : "ies") + " of water (first at "
                    + firstSheer + ") have up to " + worstSheer + "% of their natural shore rising 2+ blocks straight "
                    + "out of the water, a pit dug into the land; slope the banks down to a beach or rocks, a block "
                    + "every 1-3 blocks along a ragged line, and build a stone quay only where boats tie up");
        }
        if (square > 0) {
            fixes.add("square basin: " + square + " bod" + (square == 1 ? "y" : "ies") + " of water (first at "
                    + firstSquare + ") fill their box, a rectangle cut out of the land; give the shore a ragged line "
                    + "with coves and points, or frame it in built stone as a deliberate pool");
        }
        return fixes;
    }

    private static int perimeter(int a, int b) {
        return 2 * (a + b);
    }

    /** Water with air above: the top of a body of water. */
    private static boolean surface(BlockGrid grid, String[] names, boolean[] fluid, int x, int y, int z) {
        if (!water(grid, fluid, names, x, y, z) || !grid.region().contains(x, y + 1, z)) {
            return false;
        }
        int b = grid.blocks()[grid.region().index(x, y + 1, z)];
        return b >= 0 && !fluid[b] && names[b] == null;
    }
}
