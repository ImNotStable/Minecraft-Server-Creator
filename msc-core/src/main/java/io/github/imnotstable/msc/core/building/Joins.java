package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import java.util.Set;

/**
 * Blocks joined the way the game joins them when a player places them (ADR 0077). A build is written without block
 * updates, so without this every fence, pane and wall stood alone as a post, stair corners stayed square and a door was
 * half a door: the mark of a build no hand placed. Only what a step left unsaid is filled in: a state that names a
 * property keeps it, so saved structures and copies come out exactly as they were.
 * <ul>
 *   <li>fences, panes, bars and walls connect to their own kind and to full blocks; walls rise to a post where they turn</li>
 *   <li>stairs without {@code facing} put their back to the wall beside them, flip under a ceiling, and every stair
 *   takes the corner shape its neighbours give it</li>
 *   <li>doors, beds and tall plants get their other half; vines and lichen cling to the blocks beside them</li>
 *   <li>lanterns hang under a ceiling, torches beside a wall become wall torches, trapdoors beside a wall open flat
 *   against it as shutters, and chests, furnaces and ladders face away from their wall</li>
 * </ul>
 */
final class Joins {
    static final String[] DIRS = {"north", "east", "south", "west"};
    /** dx, dz per entry of {@link #DIRS}. */
    static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    /** Name parts of blocks that do not fill their cube, so nothing connects to their side. */
    private static final List<String> PARTIAL = List.of("stairs", "slab", "fence", "_wall", "pane", "bars", "door",
            "torch", "lantern", "sign", "banner", "button", "pressure_plate", "carpet", "rail", "chain", "ladder", "vine",
            "lichen", "pot", "candle", "head", "skull", "_bed", "chest", "lever", "end_rod", "lightning_rod", "campfire",
            "anvil", "cake", "bell", "grindstone", "scaffolding", "dripleaf", "amethyst_bud", "cluster", "roots", "moss_carpet",
            "petals", "leaf_litter", "resin_clump", "sculk_vein", "hanging_moss", "spore_blossom", "pointed_dripstone");
    private static final Set<String> FULL_ANYWAY = Set.of("sea_lantern", "jack_o_lantern", "redstone_lamp");
    private static final Set<String> TALL_PLANTS = Set.of("tall_grass", "large_fern", "sunflower", "lilac", "rose_bush",
            "peony", "pitcher_plant", "tall_seagrass");
    private static final Set<String> CLINGING = Set.of("vine", "glow_lichen", "sculk_vein", "resin_clump");
    private static final Set<String> FACE_OUT = Set.of("chest", "trapped_chest", "ender_chest", "furnace", "smoker",
            "blast_furnace", "lectern", "loom", "beehive", "bee_nest", "carved_pumpkin", "jack_o_lantern",
            "chiseled_bookshelf", "ladder", "decorated_pot", "stonecutter", "barrel");
    private static final Set<String> TORCHES = Set.of("torch", "soul_torch", "redstone_torch", "copper_torch");

    private Joins() {
    }

    /** Join every block of the canvas that needs it, looking at the world around the build too. */
    static void apply(Canvas c) throws InterruptedException {
        List<int[]> todo = c.where(Joins::needs);
        if (todo.isEmpty()) {
            return;
        }
        Canvas.Lookup at = around(c, todo);
        for (int[] p : todo) {
            orient(c, at, p[0], p[1], p[2]);
        }
        for (int[] p : todo) {
            connect(c, at, p[0], p[1], p[2]);
        }
        for (int[] p : todo) {
            pair(c, at, p[0], p[1], p[2]);
        }
    }

    /** The canvas over the world near the blocks to join; only the canvas when the world cannot be read (a dry run). */
    private static Canvas.Lookup around(Canvas c, List<int[]> todo) throws InterruptedException {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : todo) {
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p[k] - 2);
                hi[k] = Math.max(hi[k], p[k] + 2);
            }
        }
        try {
            return c.current(Region.of(c.bounds().world(), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
        } catch (ToolException e) {
            return (x, y, z) -> {
                String s = c.planned(x, y, z);
                return s == null ? "minecraft:air" : s;
            };
        }
    }

    static String id(String state) {
        return BlockNames.brief(BlockNames.id(state));
    }

    static boolean has(String state, String key) {
        String s = BlockNames.withoutData(state);
        int open = s.indexOf('[');
        return open >= 0 && (s.indexOf("[" + key + "=", open) >= 0 || s.indexOf("," + key + "=", open) >= 0
                || s.indexOf(" " + key + "=", open) >= 0);
    }

    static String get(String state, String key, String fallback) {
        String s = BlockNames.withoutData(state);
        int open = s.indexOf('[');
        if (open < 0) {
            return fallback;
        }
        for (String pair : s.substring(open + 1, s.lastIndexOf(']') > open ? s.lastIndexOf(']') : s.length()).split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1].trim();
            }
        }
        return fallback;
    }

    /** The state with {@code key=value}, replacing a value already there; block entity data is kept. */
    static String with(String state, String key, String value) {
        String data = BlockNames.data(state);
        String s = BlockNames.withoutData(state);
        int open = s.indexOf('[');
        String out;
        if (open < 0) {
            out = s + "[" + key + "=" + value + "]";
        } else {
            String id = s.substring(0, open);
            int close = s.lastIndexOf(']');
            StringBuilder props = new StringBuilder();
            boolean put = false;
            for (String pair : s.substring(open + 1, close > open ? close : s.length()).split(",")) {
                String p = pair.trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (p.startsWith(key + "=")) {
                    p = key + "=" + value;
                    put = true;
                }
                props.append(props.isEmpty() ? "" : ",").append(p);
            }
            if (!put) {
                props.append(props.isEmpty() ? "" : ",").append(key).append('=').append(value);
            }
            out = id + "[" + props + "]";
        }
        return data == null ? out : out + data;
    }

    /** The same state under another block id, keeping its properties and data. */
    static String renamed(String state, String newId) {
        String s = BlockNames.withoutData(state);
        int open = s.indexOf('[');
        String data = BlockNames.data(state);
        String out = "minecraft:" + newId + (open < 0 ? "" : s.substring(open));
        return data == null ? out : out + data;
    }

    static boolean fence(String id) {
        return id.endsWith("_fence");
    }

    static boolean pane(String id) {
        return id.endsWith("glass_pane") || id.equals("glass_pane") || id.endsWith("_bars") || id.equals("iron_bars");
    }

    static boolean wall(String id) {
        return id.endsWith("_wall");
    }

    static boolean stairs(String id) {
        return id.endsWith("_stairs");
    }

    static boolean door(String id) {
        return id.endsWith("_door") && !id.endsWith("trapdoor");
    }

    static boolean lantern(String id) {
        return id.endsWith("lantern") && !FULL_ANYWAY.contains(id);
    }

    /** Whether a block fills its cube, so fences, panes and walls join its side and things rest on it. */
    static boolean solid(String state) {
        if (BlockNames.isAir(state) || Surface.fluid(state)) {
            return false;
        }
        String id = id(state);
        if (FULL_ANYWAY.contains(id)) {
            return true;
        }
        if (id.endsWith("_slab")) {
            return get(state, "type", "bottom").equals("double");
        }
        if (id.equals("snow") || Surface.plant(state)) {
            return false;
        }
        for (String part : PARTIAL) {
            if (id.contains(part)) {
                return false;
            }
        }
        return true;
    }

    /** What a fence, pane or wall joins: full blocks except leaves (the game's exception). */
    private static boolean joinsSide(String state) {
        return solid(state) && !id(state).contains("leaves");
    }

    /** Whether a state leaves out a property this class fills in. */
    static boolean needs(String state) {
        String id = id(state);
        if (fence(id) || pane(id)) {
            return !has(state, "north") && !has(state, "east") && !has(state, "south") && !has(state, "west");
        }
        if (wall(id)) {
            return !has(state, "north") && !has(state, "east") && !has(state, "south") && !has(state, "west")
                    && !has(state, "up");
        }
        if (stairs(id)) {
            return !has(state, "shape") || !has(state, "facing") || !has(state, "half");
        }
        if (door(id) || TALL_PLANTS.contains(id)) {
            return !has(state, "half") || door(id) && !has(state, "facing");
        }
        if (id.endsWith("_bed")) {
            return !has(state, "part");
        }
        if (CLINGING.contains(id)) {
            for (String d : List.of("north", "east", "south", "west", "up", "down")) {
                if (has(state, d)) {
                    return false;
                }
            }
            return true;
        }
        if (lantern(id)) {
            return !has(state, "hanging");
        }
        if (TORCHES.contains(id)) {
            return true;
        }
        if (id.endsWith("trapdoor")) {
            return !has(state, "facing") && !has(state, "open") && !has(state, "half");
        }
        return FACE_OUT.contains(id) && !has(state, "facing");
    }

    /** Facing, half, hanging and the like for a block that left them out. */
    private static void orient(Canvas c, Canvas.Lookup at, int x, int y, int z) {
        String s = c.planned(x, y, z);
        String id = id(s);
        boolean below = solid(at.at(x, y - 1, z));
        boolean above = solid(at.at(x, y + 1, z));
        int wall = wallSide(at, x, y, z);
        String out = s;
        if (stairs(id)) {
            if (!has(s, "facing")) {
                int d = wall >= 0 ? wall : neighbourFacing(at, x, y, z);
                if (d >= 0) {
                    out = with(out, "facing", DIRS[d]);
                }
            }
            if (!has(s, "half") && above && !below) {
                out = with(out, "half", "top");
            }
        } else if (id.endsWith("trapdoor")) {
            if (!below && above) {
                out = with(out, "half", "top");
            } else if (!below && wall >= 0) {
                out = with(with(out, "open", "true"), "facing", DIRS[(wall + 2) % 4]);
            }
        } else if (TORCHES.contains(id)) {
            if (!below && wall >= 0) {
                String wallId = id.equals("torch") ? "wall_torch" : id.replace("_torch", "_wall_torch");
                out = with(renamed(s, wallId), "facing", DIRS[(wall + 2) % 4]);
            }
        } else if (lantern(id)) {
            if (!below && !BlockNames.isAir(at.at(x, y + 1, z))) {
                out = with(out, "hanging", "true");
            }
        } else if (door(id)) {
            if (!has(s, "facing") && solid(at.at(x, y, z - 1)) && solid(at.at(x, y, z + 1))) {
                out = with(out, "facing", "east");
            }
        } else if (id.endsWith("_bed")) {
            if (!has(s, "facing")) {
                for (int d = 0; d < 4; d++) {
                    int hx = x + STEP[d][0];
                    int hz = z + STEP[d][1];
                    if (free(c, at, hx, y, hz) && solid(at.at(hx + STEP[d][0], y, hz + STEP[d][1]))) {
                        out = with(out, "facing", DIRS[d]);
                        break;
                    }
                }
            }
        } else if (FACE_OUT.contains(id) && wall >= 0) {
            out = with(out, "facing", DIRS[(wall + 2) % 4]);
        }
        if (!out.equals(s)) {
            c.set(x, y, z, out);
        }
    }

    /** The side with a full block beside this one, preferring a wall with open air across from it; -1 for none. */
    static int wallSide(Canvas.Lookup at, int x, int y, int z) {
        int best = -1;
        for (int d = 0; d < 4; d++) {
            if (solid(at.at(x + STEP[d][0], y, z + STEP[d][1]))) {
                int o = (d + 2) % 4;
                if (!solid(at.at(x + STEP[o][0], y, z + STEP[o][1]))) {
                    return d;
                }
                best = best < 0 ? d : best;
            }
        }
        return best;
    }

    /** The facing of a stair beside this one that names it, so a row of stairs agrees; -1 for none. */
    private static int neighbourFacing(Canvas.Lookup at, int x, int y, int z) {
        for (int[] d : STEP) {
            String n = at.at(x + d[0], y, z + d[1]);
            if (stairs(id(n)) && has(n, "facing")) {
                return dir(get(n, "facing", "north"));
            }
        }
        return -1;
    }

    static int dir(String name) {
        for (int d = 0; d < 4; d++) {
            if (DIRS[d].equals(name)) {
                return d;
            }
        }
        return -1;
    }

    /** Connections and stair corners, from the neighbours as oriented. */
    private static void connect(Canvas c, Canvas.Lookup at, int x, int y, int z) {
        String s = c.planned(x, y, z);
        String id = id(s);
        String out = s;
        if (fence(id) || pane(id)) {
            boolean any = false;
            for (int d = 0; d < 4; d++) {
                String n = at.at(x + STEP[d][0], y, z + STEP[d][1]);
                String nid = id(n);
                boolean join = fence(id) ? fence(nid) && nid.equals("nether_brick_fence") == id.equals("nether_brick_fence")
                        || nid.endsWith("fence_gate") || joinsSide(n)
                        : pane(nid) || wall(nid) || joinsSide(n);
                out = with(out, DIRS[d], String.valueOf(join));
                any |= join;
            }
            if (!any) {
                return; // a post alone is the default state: left as written
            }
        } else if (wall(id) && !has(s, "up")) {
            String up = at.at(x, y + 1, z);
            boolean[] join = new boolean[4];
            for (int d = 0; d < 4; d++) {
                String n = at.at(x + STEP[d][0], y, z + STEP[d][1]);
                String nid = id(n);
                join[d] = wall(nid) || pane(nid) || nid.endsWith("fence_gate") || joinsSide(n);
                out = with(out, DIRS[d], join[d] ? solid(up) || wall(id(up)) ? "tall" : "low" : "none");
            }
            boolean straight = join[0] && join[2] && !join[1] && !join[3] || join[1] && join[3] && !join[0] && !join[2];
            boolean post = !straight || !BlockNames.isAir(up) && !solid(up) && !wall(id(up));
            out = with(out, "up", String.valueOf(post));
        } else if (stairs(id) && !has(s, "shape")) {
            String shape = stairShape(at, x, y, z, s);
            if (!shape.equals("straight")) { // straight is the default: a stair that says nothing stays as written
                out = with(out, "shape", shape);
            }
        } else if (CLINGING.contains(id)) {
            boolean any = false;
            for (int d = 0; d < 4; d++) {
                boolean on = solid(at.at(x + STEP[d][0], y, z + STEP[d][1]));
                out = with(out, DIRS[d], String.valueOf(on));
                any |= on;
            }
            boolean top = solid(at.at(x, y + 1, z));
            out = with(out, "up", String.valueOf(top));
            if (!id.equals("vine")) {
                boolean floor = solid(at.at(x, y - 1, z));
                out = with(out, "down", String.valueOf(floor));
                any |= floor;
            }
            if (!any && !top) {
                return; // nothing to cling to: left as given
            }
        }
        if (!out.equals(s)) {
            c.set(x, y, z, out);
        }
    }

    /** The game's own stair corner rule (StairBlock#getStairsShape). */
    static String stairShape(Canvas.Lookup at, int x, int y, int z, String s) {
        int facing = Math.max(0, dir(get(s, "facing", "north")));
        String half = get(s, "half", "bottom");
        int ccw = (facing + 3) % 4;
        String front = at.at(x + STEP[facing][0], y, z + STEP[facing][1]);
        if (stairs(id(front)) && get(front, "half", "bottom").equals(half)) {
            int f = Math.max(0, dir(get(front, "facing", "north")));
            if (f % 2 != facing % 2 && canTake(at, x, y, z, facing, half, (f + 2) % 4)) {
                return f == ccw ? "outer_left" : "outer_right";
            }
        }
        int back = (facing + 2) % 4;
        String behind = at.at(x + STEP[back][0], y, z + STEP[back][1]);
        if (stairs(id(behind)) && get(behind, "half", "bottom").equals(half)) {
            int f = Math.max(0, dir(get(behind, "facing", "north")));
            if (f % 2 != facing % 2 && canTake(at, x, y, z, facing, half, f)) {
                return f == ccw ? "inner_left" : "inner_right";
            }
        }
        return "straight";
    }

    private static boolean canTake(Canvas.Lookup at, int x, int y, int z, int facing, String half, int side) {
        String n = at.at(x + STEP[side][0], y, z + STEP[side][1]);
        return !stairs(id(n)) || Math.max(0, dir(get(n, "facing", "north"))) != facing
                || !get(n, "half", "bottom").equals(half);
    }

    /** The other half of doors, tall plants and beds, where it is free. */
    private static void pair(Canvas c, Canvas.Lookup at, int x, int y, int z) {
        String s = c.planned(x, y, z);
        String id = id(s);
        if ((door(id) || TALL_PLANTS.contains(id)) && !has(s, "half")) {
            if (free(c, at, x, y + 1, z)) {
                c.set(x, y, z, with(s, "half", "lower"));
                c.set(x, y + 1, z, with(s, "half", "upper"));
            }
        } else if (id.endsWith("_bed") && !has(s, "part")) {
            int d = Math.max(0, dir(get(s, "facing", "north")));
            int hx = x + STEP[d][0];
            int hz = z + STEP[d][1];
            if (free(c, at, hx, y, hz)) {
                c.set(x, y, z, with(s, "part", "foot"));
                c.set(hx, y, hz, with(s, "part", "head"));
            }
        }
    }

    /** Air, or a plant the build has not set: room for a door's top or a bed's head. */
    private static boolean free(Canvas c, Canvas.Lookup at, int x, int y, int z) {
        String planned = c.planned(x, y, z);
        String here = planned != null ? planned : at.at(x, y, z);
        return BlockNames.isAir(here) || planned == null && Surface.plant(here);
    }
}
