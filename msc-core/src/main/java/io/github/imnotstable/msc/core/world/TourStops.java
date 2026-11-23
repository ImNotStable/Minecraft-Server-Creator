package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a player would stand to look at a build (ADR 0094): at what the review flags, in front of each door and just
 * inside it, and on the ground around the build. Each stop is an eye at standing height looking at a point.
 */
public final class TourStops {
    /** Eye height above the block a player stands in. */
    static final double EYE = 1.62;
    private static final Pattern FIRST_AT = Pattern.compile("^([a-z ]+): .*?\\(first at (-?\\d+),(-?\\d+),(-?\\d+)\\)");
    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private TourStops() {
    }

    public record Stop(String label, double ex, double ey, double ez, double tx, double ty, double tz) {
        public String camera() {
            return String.format(java.util.Locale.ROOT, "eye %.1f,%.1f,%.1f look %.1f,%.1f,%.1f", ex, ey, ez, tx, ty, tz);
        }
    }

    /**
     * Up to {@code max} stops in {@code grid}: one per flagged issue in {@code fixes} (the review's fix lines with a
     * "first at"), then outside and inside each door, then the ground around the build.
     */
    public static List<Stop> plan(BlockGrid grid, List<String> fixes, int max) {
        String[] names = names(grid);
        List<Stop> out = new ArrayList<>();
        for (String fix : fixes) {
            Matcher m = FIRST_AT.matcher(fix);
            if (m.find() && out.size() < max) {
                int x = Integer.parseInt(m.group(2));
                int y = Integer.parseInt(m.group(3));
                int z = Integer.parseInt(m.group(4));
                Stop s = near(grid, names, m.group(1), x, y, z);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        Region r = grid.region();
        List<int[]> doors = new ArrayList<>();
        for (int y = r.min().y(); y <= r.max().y() && out.size() < max; y++) {
            for (int z = r.min().z(); z <= r.max().z() && out.size() < max; z++) {
                for (int x = r.min().x(); x <= r.max().x() && out.size() < max; x++) {
                    String n = name(grid, names, x, y, z);
                    if (n == null || !n.endsWith("_door") || n.endsWith("trapdoor")
                            || grid.palette().get(grid.blocks()[r.index(x, y, z)]).contains("half=upper")
                            || tooClose(out, x, z) || nextTo(doors, x, z)) {
                        continue;
                    }
                    doors.add(new int[] {x, z});
                    String state = grid.palette().get(grid.blocks()[r.index(x, y, z)]);
                    boolean alongX = state.contains("facing=east") || state.contains("facing=west");
                    int dx = alongX ? 1 : 0;
                    int dz = alongX ? 0 : 1;
                    // The side under the open sky is outside.
                    boolean plusOutside = !covered(grid, names, x + dx, y, z + dz);
                    int s = plusOutside ? 1 : -1;
                    for (int dist = 4; dist >= 2; dist--) {
                        Stop o = new Stop("door at " + x + "," + y + "," + z + ", from outside", x + 0.5 + s * dx * dist,
                                y + EYE, z + 0.5 + s * dz * dist, x + 0.5, y + 1, z + 0.5);
                        if (standable(grid, names, o) && sees(grid, names, o, 1.2)) {
                            out.add(o);
                            break;
                        }
                    }
                    if (out.size() < max) {
                        out.add(new Stop("inside the door at " + x + "," + y + "," + z, x + 0.5 - s * dx * 1.5,
                                y + EYE, z + 0.5 - s * dz * 1.5, x + 0.5 - s * dx * 8, y + 1.2, z + 0.5 - s * dz * 8));
                    }
                }
            }
        }
        double cx = (r.min().x() + r.max().x() + 1) / 2.0;
        double cz = (r.min().z() + r.max().z() + 1) / 2.0;
        for (int k = 0; k < 4 && out.size() < max; k++) {
            // From the region's edge toward its middle, the first spot on the ground with a clear view ahead.
            int steps = (k % 2 == 0 ? r.sizeZ() : r.sizeX()) / 2;
            for (int i = 0; i < steps; i++) {
                int ex = k == 1 ? r.max().x() - i : k == 3 ? r.min().x() + i : (int) cx;
                int ez = k == 0 ? r.min().z() + i : k == 2 ? r.max().z() - i : (int) cz;
                int ground = top(grid, names, ex, ez);
                Stop o = new Stop("the build from the " + new String[] {"north", "east", "south", "west"}[k], ex + 0.5,
                        ground + 1 + EYE, ez + 0.5, cx, ground + 4, cz);
                if (standable(grid, names, o) && sees(grid, names, o, -1)) {
                    out.add(o);
                    break;
                }
            }
        }
        return out;
    }

    /** A stop 3 blocks from a flagged spot, on a side with room to stand, looking at it. */
    private static Stop near(BlockGrid grid, String[] names, String what, int x, int y, int z) {
        for (int dist = 3; dist >= 1; dist--) {
            for (int[] d : DIRS) {
                int ex = x + d[0] * dist;
                int ez = z + d[1] * dist;
                for (int ey = y - 1; ey <= y + 1; ey++) {
                    Stop o = new Stop(what + " at " + x + "," + y + "," + z, ex + 0.5, ey + EYE, ez + 0.5, x + 0.5,
                            y + 0.5, z + 0.5);
                    if (grid.region().contains(ex, ey, ez) && standable(grid, names, o) && sees(grid, names, o, 0.9)) {
                        return o;
                    }
                }
            }
        }
        return null;
    }

    /** Air (not water, not a block) where the player's feet and head are. */
    private static boolean standable(BlockGrid grid, String[] names, Stop s) {
        int x = (int) Math.floor(s.ex());
        int y = (int) Math.floor(s.ey() - EYE);
        int z = (int) Math.floor(s.ez());
        return grid.region().contains(x, y, z) && name(grid, names, x, y, z) == null
                && name(grid, names, x, y + 1, z) == null;
    }

    /**
     * Whether the view is clear: nothing but air and water on the way to the target, up to {@code stopShort} blocks
     * before it; a negative value checks only the first 3 blocks ahead (looking at a build, its walls stop the ray).
     */
    private static boolean sees(BlockGrid grid, String[] names, Stop s, double stopShort) {
        double dx = s.tx() - s.ex();
        double dy = s.ty() - s.ey();
        double dz = s.tz() - s.ez();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double reach = stopShort < 0 ? Math.min(3, len) : len - stopShort;
        for (double t = 0.5; t < reach; t += 0.25) {
            String n = name(grid, names, (int) Math.floor(s.ex() + dx * t / len),
                    (int) Math.floor(s.ey() + dy * t / len), (int) Math.floor(s.ez() + dz * t / len));
            if (n != null && !n.equals("water")) {
                return false;
            }
        }
        return true;
    }

    /** One door of a double door, or of a row of doors: the first one stands for them. */
    private static boolean nextTo(List<int[]> doors, int x, int z) {
        for (int[] d : doors) {
            if (Math.abs(d[0] - x) <= 3 && Math.abs(d[1] - z) <= 3) {
                return true;
            }
        }
        return false;
    }

    private static boolean tooClose(List<Stop> stops, int x, int z) {
        for (Stop s : stops) {
            if (Math.abs(s.tx() - x - 0.5) < 6 && Math.abs(s.tz() - z - 0.5) < 6) {
                return true;
            }
        }
        return false;
    }

    private static boolean covered(BlockGrid grid, String[] names, int x, int y, int z) {
        for (int up = y + 2; up <= Math.min(grid.region().max().y(), y + 10); up++) {
            if (name(grid, names, x, up, z) != null) {
                return true;
            }
        }
        return false;
    }

    private static int top(BlockGrid grid, String[] names, int x, int z) {
        for (int y = grid.region().max().y(); y >= grid.region().min().y(); y--) {
            String n = name(grid, names, x, y, z);
            if (n != null && !n.contains("leaves")) {
                return y; // the ground under a canopy, not the canopy
            }
        }
        return grid.region().min().y();
    }

    /** Brief block ids by palette index; null for air. */
    static String[] names(BlockGrid grid) {
        String[] out = new String[grid.palette().size()];
        for (int i = 0; i < out.length; i++) {
            String p = grid.palette().get(i);
            String id = BlockNames.brief(BlockNames.id(p));
            out[i] = BlockNames.isAir(p) ? null : id;
        }
        return out;
    }

    private static String name(BlockGrid grid, String[] names, int x, int y, int z) {
        if (!grid.region().contains(x, y, z)) {
            return null;
        }
        int i = grid.blocks()[grid.region().index(x, y, z)];
        return i < 0 ? null : names[i];
    }
}
