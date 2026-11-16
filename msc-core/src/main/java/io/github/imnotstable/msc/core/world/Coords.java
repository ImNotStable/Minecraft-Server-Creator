package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the compact coordinate strings tools take: {@code "x,y,z"} and {@code "x1,y1,z1 x2,y2,z2"}. */
public final class Coords {
    private static final Pattern INT = Pattern.compile("-?\\d+");
    /** Largest |x| and |z| a coordinate may have: the Minecraft world border stops at 29,999,984. */
    public static final int MAX_XZ = 30_000_000;
    /** Largest |y|: far above any build height, and far enough from the int limits that no loop over a region wraps. */
    public static final int MAX_Y = 1 << 20;

    private Coords() {
    }

    /**
     * A position inside the world, from coordinates computed in long (a base plus repeated offsets), so a far step is
     * reported instead of wrapping around the int range.
     */
    public static BlockPos inWorld(long x, long y, long z) {
        check('x', x);
        check('y', y);
        check('z', z);
        return new BlockPos((int) x, (int) y, (int) z);
    }

    private static void check(char axis, long v) {
        long max = axis == 'y' ? MAX_Y : MAX_XZ;
        if (v < -max || v > max) {
            throw new ToolException(axis + "=" + v + " is outside the world: |x| and |z| are at most " + MAX_XZ
                    + ", |y| at most " + MAX_Y);
        }
    }

    /** The integers in {@code text}, one per letter of {@code axes}, each checked against that axis's range. */
    private static int[] ints(String text, String axes, String shape) {
        int expected = axes.length();
        Matcher m = INT.matcher(text);
        List<Integer> found = new ArrayList<>();
        while (m.find()) {
            try {
                found.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException e) {
                throw new ToolException("coordinate out of range in \"" + text + "\"");
            }
        }
        if (found.size() != expected) {
            throw new ToolException("expected " + shape + ", got \"" + text + "\"");
        }
        int[] v = found.stream().mapToInt(Integer::intValue).toArray();
        for (int i = 0; i < v.length; i++) {
            check(axes.charAt(i), v[i]);
        }
        return v;
    }

    public static BlockPos pos(String text) {
        int[] v = ints(text, "xyz", "\"x,y,z\"");
        return new BlockPos(v[0], v[1], v[2]);
    }

    public static Region region(String world, String text) {
        int[] v = ints(text, "xyzxyz", "\"x1,y1,z1 x2,y2,z2\"");
        return Region.of(world, v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /** {@code "x1,z1 x2,z2"} as a region with y = 0. */
    public static Region area(String world, String text) {
        int[] v = ints(text, "xzxz", "\"x1,z1 x2,z2\"");
        return Region.of(world, v[0], 0, v[1], v[2], 0, v[3]);
    }

    public static String show(BlockPos p) {
        return p.x() + "," + p.y() + "," + p.z();
    }

    public static String show(Region r) {
        return r.world() + " " + show(r.min()) + " " + show(r.max());
    }

    /** Cut a region into boxes the protocol accepts in one call (at most 128 x 64 x 128). */
    public static List<Region> split(Region r) {
        List<Region> out = new ArrayList<>();
        if (r.volume() <= Limits.MAX_REGION_VOLUME) {
            out.add(r);
            return out;
        }
        // long counters: an int one wraps past Integer.MAX_VALUE and never ends.
        for (long y = r.min().y(); y <= r.max().y(); y += 64) {
            for (long z = r.min().z(); z <= r.max().z(); z += 128) {
                for (long x = r.min().x(); x <= r.max().x(); x += 128) {
                    out.add(Region.of(r.world(), (int) x, (int) y, (int) z, (int) Math.min(x + 127, r.max().x()),
                            (int) Math.min(y + 63, r.max().y()), (int) Math.min(z + 127, r.max().z())));
                }
            }
        }
        return out;
    }
}
