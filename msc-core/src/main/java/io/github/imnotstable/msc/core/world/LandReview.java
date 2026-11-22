package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Signs of hand-painted land (ADR 0075), measured for {@code world_summary review=true} beside the build numbers:
 * ground at one level (land painted onto the flat dev floor) and blocks that nothing holds up (leaves, rails, a
 * tower off its base). Straight lines are not judged: roads, walls and rails need them (ADR 0077).
 */
public final class LandReview {
    /** Soil and loose ground: never part of a build's own numbers. */
    static final Set<String> SOIL = Set.of("grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium",
            "mud", "clay", "sand", "red_sand", "gravel", "dirt_path", "farmland", "snow_block", "powder_snow",
            "moss_block", "suspicious_sand", "suspicious_gravel", "pale_moss_block");
    /** Ground that is also a building block: counts as land for the land numbers only. */
    private static final Set<String> ROCK = Set.of("stone", "deepslate", "andesite", "diorite", "granite", "tuff",
            "calcite", "terracotta", "sandstone", "red_sandstone", "netherrack", "end_stone", "basalt", "blackstone",
            "packed_ice", "ice", "blue_ice", "dripstone_block", "cobblestone", "mossy_cobblestone", "bedrock");
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Columns a region needs before its ground is judged: a build's own plot is not land. */
    static final int MIN_COLUMNS = 1024;
    static final int MAX_FLAT = 80;

    private LandReview() {
    }

    /** The land numbers as one line, and what to fix; the line is empty when there is no land to judge. */
    public record Result(String line, List<String> fixes, int landShare) {
    }

    /** Whether a block (brief id) is land: soil, rock or terracotta of any colour (not glazed). */
    static boolean land(String id) {
        return SOIL.contains(id) || ROCK.contains(id) || id.endsWith("_terracotta") && !id.contains("glazed");
    }

    /** {@code names}: brief ids by palette index, null for air and fluids; {@code fluid}: water, lava. */
    static Result review(BlockGrid grid, String[] names, boolean[] fluid) {
        Region r = grid.region();
        int sx = r.sizeX();
        int sz = r.sizeZ();
        int[] topY = new int[sx * sz];
        String[] top = new String[sx * sz];
        long solid = 0;
        long landBlocks = 0;
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int i = z * sx + x;
                topY[i] = Integer.MIN_VALUE;
                for (int y = r.max().y(); y >= r.min().y(); y--) {
                    String n = name(grid, names, r.min().x() + x, y, r.min().z() + z);
                    if (n != null && !plant(n)) {
                        topY[i] = y;
                        top[i] = land(n) ? n : null;
                        break;
                    }
                }
            }
        }
        for (int b : grid.blocks()) {
            if (b >= 0 && names[b] != null) {
                solid++;
                landBlocks += land(names[b]) ? 1 : 0;
            }
        }
        List<String> parts = new ArrayList<>();
        List<String> fixes = new ArrayList<>();
        int columns = sx * sz;
        int groundColumns = 0;
        for (String t : top) {
            groundColumns += t != null ? 1 : 0;
        }
        if (columns >= MIN_COLUMNS && groundColumns * 2 >= columns) {
            int[] level = flatLevel(topY, top);
            int flatPct = pct(level[1], groundColumns);
            parts.add(String.format(Locale.ROOT, "ground at one level %d%% (y %d, aim under %d%%)", flatPct, level[0],
                    MAX_FLAT));
            if (flatPct > MAX_FLAT) {
                fixes.add("flat land: " + flatPct + "% of the ground is at y " + level[0] + "; shape it with "
                        + "build_batch terrain (hills, ridges, valleys, water) or build in a world_create world, "
                        + "and let builds step with the slope");
            }
        }
        int[] floating = floating(grid, names, fluid);
        if (floating[0] > 0) {
            StringBuilder at = new StringBuilder();
            for (int k = 0; k < 3 && floating[2 + 3 * k] != Integer.MIN_VALUE; k++) {
                at.append(k == 0 ? "" : "; ").append(floating[2 + 3 * k]).append(',').append(floating[3 + 3 * k])
                        .append(',').append(floating[4 + 3 * k]);
            }
            parts.add("floating " + floating[0] + " blocks in " + floating[1] + " clumps");
            fixes.add("floating: " + floating[0] + " blocks in " + floating[1] + " clumps touch nothing that reaches "
                    + "the ground (at " + at + "); ground them, or give a sky island a visible underside");
        }
        String line = String.join(", ", parts);
        return new Result(line, fixes, solid == 0 ? 0 : pct(landBlocks, solid));
    }

    /** Short plants and snow layers do not hide the ground. */
    private static boolean plant(String id) {
        return id.equals("short_grass") || id.equals("tall_grass") || id.equals("fern") || id.equals("large_fern")
                || id.equals("snow") || id.contains("flower") || id.contains("petals") || id.contains("tulip")
                || id.equals("dead_bush") || id.equals("leaf_litter");
    }

    /** The most common height among ground columns, and how many columns have it. */
    private static int[] flatLevel(int[] topY, String[] top) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < top.length; i++) {
            if (top[i] != null) {
                counts.merge(topY[i], 1, Integer::sum);
            }
        }
        int best = 0;
        int count = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > count || e.getValue() == count && e.getKey() < best) {
                best = e.getKey();
                count = e.getValue();
            }
        }
        return new int[] {best, count};
    }

    /**
     * Blocks not joined, face to face, to a block on the region's bottom layer or its sides (water joins too): how
     * many, in how many clumps, and a block of each of the three largest clumps (x, y, z; MIN_VALUE when fewer).
     */
    static int[] floating(BlockGrid grid, String[] names, boolean[] fluid) {
        Region r = grid.region();
        int sx = r.sizeX();
        int sy = r.sizeY();
        int sz = r.sizeZ();
        int n = sx * sy * sz;
        boolean[] present = new boolean[n];
        for (int i = 0; i < n; i++) {
            int b = grid.blocks()[i];
            present[i] = b >= 0 && (names[b] != null || fluid[b]);
        }
        boolean[] held = new boolean[n];
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    boolean rim = y == 0 || x == 0 || z == 0 || x == sx - 1 || z == sz - 1;
                    int i = (y * sz + z) * sx + x;
                    if (rim && present[i] && !held[i]) {
                        held[i] = true;
                        queue[tail++] = i;
                    }
                }
            }
        }
        if (tail == 0) {
            return new int[] {0, 0, Integer.MIN_VALUE, 0, 0, Integer.MIN_VALUE, 0, 0, Integer.MIN_VALUE, 0, 0};
        }
        tail = spread(present, held, queue, head, tail, sx, sy, sz);
        int blocks = 0;
        int clumps = 0;
        int[][] biggest = new int[3][2]; // size, index
        for (int i = 0; i < n; i++) {
            if (present[i] && !held[i]) {
                held[i] = true;
                queue[0] = i;
                int size = spread(present, held, queue, 0, 1, sx, sy, sz);
                blocks += size;
                clumps++;
                for (int k = 0; k < 3; k++) {
                    if (size > biggest[k][0]) {
                        System.arraycopy(biggest, k, biggest, k + 1, 2 - k);
                        biggest[k] = new int[] {size, i};
                        break;
                    }
                }
            }
        }
        int[] out = new int[11];
        out[0] = blocks;
        out[1] = clumps;
        for (int k = 0; k < 3; k++) {
            int i = biggest[k][1];
            out[2 + 3 * k] = biggest[k][0] == 0 ? Integer.MIN_VALUE : r.min().x() + i % sx;
            out[3 + 3 * k] = r.min().y() + i / (sx * sz);
            out[4 + 3 * k] = r.min().z() + i / sx % sz;
        }
        return out;
    }

    /** Flood from the queue's entries through present blocks; returns how many entries the queue ended with. */
    private static int spread(boolean[] present, boolean[] held, int[] queue, int head, int tail, int sx, int sy, int sz) {
        while (head < tail) {
            int i = queue[head++];
            int x = i % sx;
            int z = i / sx % sz;
            int y = i / (sx * sz);
            for (int[] d : SIDES) {
                int nx = x + d[0];
                int ny = y + d[1];
                int nz = z + d[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
                    continue;
                }
                int j = (ny * sz + nz) * sx + nx;
                if (present[j] && !held[j]) {
                    held[j] = true;
                    queue[tail++] = j;
                }
            }
        }
        return tail;
    }

    private static String name(BlockGrid grid, String[] names, int x, int y, int z) {
        int i = grid.blocks()[grid.region().index(x, y, z)];
        return i < 0 ? null : names[i];
    }

    private static int pct(long part, long whole) {
        return (int) Math.round(100.0 * part / whole);
    }
}
