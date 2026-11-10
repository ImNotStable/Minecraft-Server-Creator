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

    private Coords() {
    }

    private static int[] ints(String text, int expected, String shape) {
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
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    public static BlockPos pos(String text) {
        int[] v = ints(text, 3, "\"x,y,z\"");
        return new BlockPos(v[0], v[1], v[2]);
    }

    public static Region region(String world, String text) {
        int[] v = ints(text, 6, "\"x1,y1,z1 x2,y2,z2\"");
        return Region.of(world, v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /** {@code "x1,z1 x2,z2"} as a region with y = 0. */
    public static Region area(String world, String text) {
        int[] v = ints(text, 4, "\"x1,z1 x2,z2\"");
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
        for (int y = r.min().y(); y <= r.max().y(); y += 64) {
            for (int z = r.min().z(); z <= r.max().z(); z += 128) {
                for (int x = r.min().x(); x <= r.max().x(); x += 128) {
                    out.add(Region.of(r.world(), x, y, z, Math.min(x + 127, r.max().x()),
                            Math.min(y + 63, r.max().y()), Math.min(z + 127, r.max().z())));
                }
            }
        }
        return out;
    }
}
