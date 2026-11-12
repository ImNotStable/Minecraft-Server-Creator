package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning structures clockwise as seen from above, in quarter turns: north becomes east. Block
 * states turn with them: {@code facing}, {@code axis}, {@code rotation} (signs, banners, skulls),
 * rail and jigsaw directions, and the {@code north/east/south/west} connections of fences and walls.
 */
public final class Turn {
    private static final List<String> SIDES = List.of("north", "east", "south", "west");
    private static final Pattern PROPS = Pattern.compile("^([^\\[]+)\\[(.*)]$");
    private static final Pattern SIDE_WORD = Pattern.compile("north|east|south|west");

    private Turn() {
    }

    /** Degrees (0, 90, 180, 270, or negative) as clockwise quarter turns, 0 to 3. */
    public static int quarters(int degrees) {
        if (degrees % 90 != 0) {
            throw new ToolException("rotate must be a multiple of 90, got " + degrees);
        }
        return Math.floorMod(degrees / 90, 4);
    }

    /**
     * Quarter turns that make the structure's north side (its first text row) face {@code target}
     * when its footprint centre is at {@code from}.
     */
    public static int facing(double fromX, double fromZ, double targetX, double targetZ) {
        double dx = targetX - fromX;
        double dz = targetZ - fromZ;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? 1 : 3;
        }
        return dz > 0 ? 2 : 0;
    }

    /** The grid turned {@code quarters} times; its region starts at 0,0,0. */
    public static BlockGrid grid(BlockGrid grid, int quarters) {
        Region r = grid.region();
        int sx = r.sizeX();
        int sy = r.sizeY();
        int sz = r.sizeZ();
        int q = Math.floorMod(quarters, 4);
        int nx = q % 2 == 0 ? sx : sz;
        int nz = q % 2 == 0 ? sz : sx;
        List<String> palette = new ArrayList<>(grid.palette().size());
        for (String s : grid.palette()) {
            palette.add(state(s, q));
        }
        int[] out = new int[grid.blocks().length];
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int tx;
                    int tz;
                    switch (q) {
                        case 1 -> {
                            tx = sz - 1 - z;
                            tz = x;
                        }
                        case 2 -> {
                            tx = sx - 1 - x;
                            tz = sz - 1 - z;
                        }
                        case 3 -> {
                            tx = z;
                            tz = sx - 1 - x;
                        }
                        default -> {
                            tx = x;
                            tz = z;
                        }
                    }
                    out[(y * nz + tz) * nx + tx] = grid.blocks()[i++];
                }
            }
        }
        Region turned = new Region(r.world(), new BlockPos(0, 0, 0), new BlockPos(nx - 1, sy - 1, nz - 1));
        return new BlockGrid(turned, palette, out);
    }

    /** One block state turned {@code quarters} times clockwise. */
    public static String state(String state, int quarters) {
        int q = Math.floorMod(quarters, 4);
        Matcher m = PROPS.matcher(state);
        if (q == 0 || !m.matches()) {
            return state;
        }
        String[] props = m.group(2).split(",");
        Map<String, String> sides = new java.util.HashMap<>();
        List<String> out = new ArrayList<>();
        for (String prop : props) {
            int eq = prop.indexOf('=');
            if (eq < 0) {
                out.add(prop);
                continue;
            }
            String key = prop.substring(0, eq);
            String value = prop.substring(eq + 1);
            switch (key) {
                case "facing", "shape", "orientation" -> out.add(key + "=" + sides(value, q));
                case "axis" -> out.add(key + "=" + (q % 2 == 1 && !value.equals("y") ? (value.equals("x") ? "z" : "x") : value));
                case "rotation" -> out.add(key + "=" + rotation(value, q));
                case "north", "east", "south", "west" -> sides.put(SIDES.get((SIDES.indexOf(key) + q) % 4), value);
                default -> out.add(prop);
            }
        }
        for (String side : SIDES) {
            if (sides.containsKey(side)) {
                out.add(side + "=" + sides.get(side));
            }
        }
        return m.group(1) + "[" + String.join(",", out) + "]";
    }

    private static String rotation(String value, int q) {
        try {
            return Integer.toString(Math.floorMod(Integer.parseInt(value) + 4 * q, 16));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** Turn every side word in a value: {@code north_east} becomes {@code east_south} after one turn. */
    private static String sides(String value, int q) {
        Matcher m = SIDE_WORD.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, SIDES.get((SIDES.indexOf(m.group()) + q) % 4));
        }
        m.appendTail(out);
        String turned = out.toString();
        // Rails name their two ends in a fixed order (north_south, south_east, north_west ...); keep it.
        return switch (turned) {
            case "east_south" -> "south_east";
            case "west_south" -> "south_west";
            case "east_north" -> "north_east";
            case "west_north" -> "north_west";
            case "south_north" -> "north_south";
            case "west_east" -> "east_west";
            default -> turned;
        };
    }
}
