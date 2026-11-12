package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Can a player walk and jump from one place to another? A search over the blocks of one region, with the moves a
 * survival player makes without tools: walk, step up one block, drop up to {@link #SAFE_DROP} blocks, jump a gap of
 * up to {@link #FLAT_GAP} blocks (one less going up), climb ladders and vines, swim. Block shapes are judged by name,
 * so this is a check for builders, not a physics engine: carpets, flowers and signs are walked through, fences and
 * walls cannot be jumped onto, doors and fence gates open, iron doors do not.
 */
public final class Walk {
    /** Highest drop without fall damage. */
    public static final int SAFE_DROP = 3;
    /** Widest gap a sprint jump clears on the level; one less going up a block, one more going down. */
    public static final int FLAT_GAP = 3;

    private static final byte OPEN = 0;
    private static final byte SOLID = 1;
    private static final byte CLIMB = 2;
    private static final byte DEADLY = 3;
    /** Solid, but 1.5 blocks tall: nobody jumps onto it. */
    private static final byte TALL = 4;
    /** Solid, and low enough to walk onto without a jump: stairs and bottom slabs. */
    private static final byte STEP = 5;

    private static final Set<String> OPEN_IDS = Set.of("air", "cave_air", "void_air", "short_grass", "tall_grass",
            "fern", "large_fern", "dead_bush", "glow_lichen", "light", "structure_void", "redstone_wire", "tripwire",
            "tripwire_hook", "lever", "sugar_cane", "cobweb", "snow", "seagrass", "tall_seagrass", "nether_portal",
            "hanging_roots", "pink_petals", "sweet_berry_bush", "wheat", "carrots", "potatoes", "beetroots",
            "nether_wart", "cocoa", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony",
            "torchflower", "pitcher_plant", "spore_blossom", "small_dripleaf", "brown_mushroom", "red_mushroom",
            "moss_carpet", "pale_moss_carpet", "leaf_litter", "short_dry_grass", "tall_dry_grass", "bush",
            "firefly_bush", "wildflowers", "cactus_flower", "end_rod", "string");
    private static final Set<String> CLIMB_IDS = Set.of("ladder", "vine", "scaffolding", "water", "bubble_column",
            "twisting_vines", "twisting_vines_plant", "weeping_vines", "weeping_vines_plant", "cave_vines",
            "cave_vines_plant", "kelp", "kelp_plant");
    private static final Set<String> DEADLY_IDS = Set.of("lava", "fire", "soul_fire", "powder_snow");
    private static final List<String> OPEN_SUFFIXES = List.of("_sapling", "_sign", "_banner", "_button",
            "_pressure_plate", "_carpet", "_torch", "_tulip", "_rail", "_roots", "_fungus", "_sprouts", "_coral",
            "_coral_fan", "_propagule", "_candle", "_fence_gate", "_door");

    private final BlockGrid grid;
    private final Region region;
    private final byte[] kind;

    public Walk(BlockGrid grid) {
        this.grid = grid;
        this.region = grid.region();
        byte[] byPalette = new byte[grid.palette().size()];
        for (int i = 0; i < byPalette.length; i++) {
            byPalette[i] = classify(grid.palette().get(i));
        }
        kind = new byte[grid.blocks().length];
        for (int i = 0; i < kind.length; i++) {
            int b = grid.blocks()[i];
            kind[i] = b < 0 ? SOLID : byPalette[b];
        }
    }

    /** How a block behaves for a walking player, judged from its name and properties. */
    static byte classify(String state) {
        String id = BlockNames.brief(BlockNames.id(state));
        if (id.contains(":")) {
            return SOLID;
        }
        if (DEADLY_IDS.contains(id)) {
            return DEADLY;
        }
        if (CLIMB_IDS.contains(id) || state.contains("waterlogged=true") && !isSolidShape(id)) {
            return CLIMB;
        }
        if (OPEN_IDS.contains(id)) {
            return OPEN;
        }
        if (id.equals("iron_door") || id.equals("iron_trapdoor")) {
            return SOLID;
        }
        if (id.endsWith("_trapdoor")) {
            return state.contains("open=true") ? CLIMB : SOLID;
        }
        for (String suffix : OPEN_SUFFIXES) {
            if (id.endsWith(suffix) && !id.endsWith("_block")) {
                return OPEN;
            }
        }
        if (id.endsWith("_fence") || id.endsWith("_wall") || id.equals("iron_bars") || id.endsWith("_pane")) {
            return TALL;
        }
        if (id.endsWith("_stairs") || id.endsWith("_slab") && !state.contains("type=top")
                && !state.contains("type=double")) {
            return STEP;
        }
        return SOLID;
    }

    private static boolean isSolidShape(String id) {
        return id.endsWith("_stairs") || id.endsWith("_slab") || id.endsWith("_fence") || id.endsWith("_wall");
    }

    private byte at(int x, int y, int z) {
        return region.contains(x, y, z) ? kind[region.index(x, y, z)] : SOLID;
    }

    private boolean passable(int x, int y, int z) {
        byte k = at(x, y, z);
        return k == OPEN || k == CLIMB;
    }

    private boolean ground(byte k) {
        return k == SOLID || k == TALL || k == STEP;
    }

    /** A player can stay here: room for feet and head, and something to stand on or hold. */
    private boolean standable(int x, int y, int z) {
        if (!region.contains(x, y, z) || !region.contains(x, y - 1, z)) {
            return false;
        }
        if (!passable(x, y, z) || !passable(x, y + 1, z) && region.contains(x, y + 1, z)) {
            return false;
        }
        return ground(at(x, y - 1, z)) || at(x, y, z) == CLIMB;
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The answer: a route, or the place closest to the target and what stops the player there. */
    public String path(BlockPos from, BlockPos to) {
        BlockPos start = feet(from, "from");
        BlockPos goal = feet(to, "to");
        int n = kind.length;
        int[] parent = new int[n];
        Arrays.fill(parent, -2);
        byte[] how = new byte[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int s = region.index(start.x(), start.y(), start.z());
        int g = region.index(goal.x(), goal.y(), goal.z());
        parent[s] = -1;
        queue.add(s);
        int best = s;
        double bestDist = dist(start, goal);
        int sx = region.sizeX();
        int layer = sx * region.sizeZ();
        while (!queue.isEmpty()) {
            int i = queue.poll();
            if (i == g) {
                break;
            }
            int y = region.min().y() + i / layer;
            int z = region.min().z() + i % layer / sx;
            int x = region.min().x() + i % sx;
            double d = dist(x, y, z, goal);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
            boolean climbing = at(x, y, z) == CLIMB;
            boolean headroom = passable(x, y + 2, z) || !region.contains(x, y + 2, z);
            if (climbing) {
                visit(queue, parent, how, i, x, y + 1, z, 'c');
                visit(queue, parent, how, i, x, y - 1, z, 'c');
            }
            for (int[] dir : DIRS) {
                int nx = x + dir[0];
                int nz = z + dir[1];
                // Walk on the level, or up one: a step block or a ladder needs no jump; anything else needs headroom.
                visit(queue, parent, how, i, nx, y, nz, 'w');
                if (standable(nx, y + 1, nz) && at(nx, y, nz) != TALL
                        && (headroom || at(nx, y, nz) == STEP || climbing)) {
                    visit(queue, parent, how, i, nx, y + 1, nz, 'u');
                }
                // Drop off an edge.
                if (passable(nx, y, nz) && passable(nx, y + 1, nz)) {
                    for (int k = 1; k <= SAFE_DROP; k++) {
                        if (!passable(nx, y - k, nz)) {
                            break;
                        }
                        if (standable(nx, y - k, nz)) {
                            visit(queue, parent, how, i, nx, y - k, nz, 'd');
                            break;
                        }
                    }
                }
                // Jump a gap: the columns between must be open from the lower feet to above the higher head.
                if (!headroom || !ground(at(x, y - 1, z))) {
                    continue;
                }
                for (int dy = 1; dy >= -SAFE_DROP; dy--) {
                    for (int gap = 1; gap <= FLAT_GAP + (dy < 0 ? 1 : 0) - (dy > 0 ? 1 : 0); gap++) {
                        int tx = x + dir[0] * (gap + 1);
                        int tz = z + dir[1] * (gap + 1);
                        if (!standable(tx, y + dy, tz) || dy > 0 && at(tx, y, tz) == TALL || !clear(x, z, dir, gap, y, dy)) {
                            continue;
                        }
                        visit(queue, parent, how, i, tx, y + dy, tz, 'j');
                    }
                }
            }
        }
        if (parent[g] != -2) {
            return route(parent, how, g, layer, sx);
        }
        return blocked(start, goal, pos(best, layer, sx));
    }

    private boolean clear(int x, int z, int[] dir, int gap, int y, int dy) {
        for (int k = 1; k <= gap; k++) {
            int cx = x + dir[0] * k;
            int cz = z + dir[1] * k;
            for (int cy = Math.min(y, y + dy); cy <= Math.max(y, y + dy) + 2; cy++) {
                if (region.contains(cx, cy, cz) && !passable(cx, cy, cz)) {
                    return false;
                }
            }
            // A column with a floor on the way is a walk or a drop, not a gap.
            for (int cy = Math.min(y, y + dy); cy <= y; cy++) {
                if (standable(cx, cy, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void visit(ArrayDeque<Integer> queue, int[] parent, byte[] how, int from, int x, int y, int z, char move) {
        if (!standable(x, y, z)) {
            return;
        }
        int i = region.index(x, y, z);
        if (parent[i] == -2) {
            parent[i] = from;
            how[i] = (byte) move;
            queue.add(i);
        }
    }

    /** The feet block for a position: the position itself, or the block above when the position is the floor. */
    private BlockPos feet(BlockPos p, String name) {
        if (!region.contains(p.x(), p.y(), p.z())) {
            throw new ToolException(name + " " + Coords.show(p) + " is outside the region " + Coords.show(region));
        }
        if (standable(p.x(), p.y(), p.z())) {
            return p;
        }
        if (standable(p.x(), p.y() + 1, p.z())) {
            return new BlockPos(p.x(), p.y() + 1, p.z());
        }
        String here = BlockNames.brief(String.valueOf(grid.stateAt(p.x(), p.y(), p.z())));
        throw new ToolException("nobody can stand at " + Coords.show(p) + " (" + here + "): give the feet block, "
                + "with 2 blocks of room above the floor, and include the floor layer in the region");
    }

    private BlockPos pos(int i, int layer, int sx) {
        return new BlockPos(region.min().x() + i % sx, region.min().y() + i / layer, region.min().z() + i % layer / sx);
    }

    private static double dist(BlockPos a, BlockPos b) {
        return dist(a.x(), a.y(), a.z(), b);
    }

    private static double dist(int x, int y, int z, BlockPos b) {
        double dx = x - b.x();
        double dy = y - b.y();
        double dz = z - b.z();
        return Math.sqrt(dx * dx + 2 * dy * dy + dz * dz);
    }

    private String route(int[] parent, byte[] how, int goal, int layer, int sx) {
        List<Integer> steps = new ArrayList<>();
        for (int i = goal; i != -1; i = parent[i]) {
            steps.add(0, i);
        }
        int jumps = 0;
        int longest = 0;
        int ups = 0;
        int drops = 0;
        int climbs = 0;
        List<BlockPos> turns = new ArrayList<>();
        BlockPos prev = null;
        int[] lastDir = null;
        for (int i : steps) {
            BlockPos p = pos(i, layer, sx);
            if (prev != null) {
                switch (how[i]) {
                    case 'j' -> {
                        jumps++;
                        longest = Math.max(longest, Math.abs(p.x() - prev.x()) + Math.abs(p.z() - prev.z()) - 1);
                    }
                    case 'u' -> ups++;
                    case 'd' -> drops++;
                    case 'c' -> climbs++;
                    default -> {
                    }
                }
                int[] dir = {Integer.signum(p.x() - prev.x()), Integer.signum(p.y() - prev.y()),
                        Integer.signum(p.z() - prev.z())};
                if ((lastDir != null && !Arrays.equals(dir, lastDir) || how[i] == 'j')
                        && !turns.get(turns.size() - 1).equals(prev)) {
                    turns.add(prev);
                }
                lastDir = dir;
            } else {
                turns.add(p);
            }
            prev = p;
        }
        if (!turns.get(turns.size() - 1).equals(prev)) {
            turns.add(prev);
        }
        StringBuilder out = new StringBuilder("reachable: " + (steps.size() - 1) + " moves");
        List<String> parts = new ArrayList<>();
        if (jumps > 0) {
            parts.add(jumps + (jumps == 1 ? " jump" : " jumps") + " (widest gap " + longest + ")");
        }
        if (ups > 0) {
            parts.add(ups + " up");
        }
        if (drops > 0) {
            parts.add(drops + (drops == 1 ? " drop" : " drops"));
        }
        if (climbs > 0) {
            parts.add(climbs + " climbed or swum");
        }
        if (!parts.isEmpty()) {
            out.append(", ").append(String.join(", ", parts));
        }
        out.append("\nroute:");
        int shown = Math.min(turns.size(), 20);
        for (int i = 0; i < shown; i++) {
            out.append(i == 0 ? " " : " > ").append(Coords.show(turns.get(i)));
        }
        if (turns.size() > shown) {
            out.append(" > ... > ").append(Coords.show(turns.get(turns.size() - 1)));
        }
        return out.toString();
    }

    /** Why the player gets no closer than {@code near}: the wall, gap or climb that stops them. */
    private String blocked(BlockPos start, BlockPos goal, BlockPos near) {
        StringBuilder out = new StringBuilder("not reachable from " + Coords.show(start) + " to " + Coords.show(goal)
                + ". Closest: " + Coords.show(near) + "\n");
        int dx = goal.x() - near.x();
        int dz = goal.z() - near.z();
        int dy = goal.y() - near.y();
        if (dx == 0 && dz == 0) {
            out.append(dy > 0 ? "the target is " + dy + " blocks straight up: no stairs, ladder or jump leads there"
                    : "the target is " + -dy + " blocks straight down: a drop over " + SAFE_DROP + " hurts");
            return out.toString();
        }
        int[] dir = Math.abs(dx) >= Math.abs(dz) ? new int[] {Integer.signum(dx), 0} : new int[] {0, Integer.signum(dz)};
        int x = near.x() + dir[0];
        int z = near.z() + dir[1];
        int y = near.y();
        String facing = dir[0] > 0 ? "east" : dir[0] < 0 ? "west" : dir[1] > 0 ? "south" : "north";
        if (!region.contains(x, y, z)) {
            return out.append("the region ends to the ").append(facing).append("; make it larger").toString();
        }
        for (int h = 0; h < 2; h++) {
            byte k = at(x, y + h, z);
            if (k == DEADLY) {
                return out.append(name(x, y + h, z)).append(" at ").append(Coords.show(new BlockPos(x, y + h, z)))
                        .append(" to the ").append(facing).toString();
            }
            if (!passable(x, y + h, z)) {
                int top = y + h;
                while (region.contains(x, top + 1, z) && !passable(x, top + 1, z)) {
                    top++;
                }
                int height = top - y + 1;
                String what = name(x, y + h, z);
                out.append("wall to the ").append(facing).append(": ").append(what).append(" at ")
                        .append(Coords.show(new BlockPos(x, y + h, z)));
                if (what.equals("iron_door")) {
                    return out.append(" (opens only with redstone)").toString();
                }
                if (h == 0 && height == 1 && at(x, y, z) == TALL) {
                    return out.append(" (fences and walls are too tall to jump onto)").toString();
                }
                if (h == 0 && height == 1 && !passable(near.x(), y + 2, near.z())) {
                    return out.append(" (1 high, but no headroom to jump: ").append(name(near.x(), y + 2, near.z()))
                            .append(" above ").append(Coords.show(near)).append(')').toString();
                }
                return out.append(" (").append(height).append(" high; a jump climbs 1)").toString();
            }
        }
        if (!ground(at(x, y - 1, z)) && at(x, y, z) != CLIMB) {
            int gap = 0;
            int depth = 0;
            for (int k = 1; k <= 8; k++) {
                int cx = near.x() + dir[0] * k;
                int cz = near.z() + dir[1] * k;
                if (!region.contains(cx, y, cz)) {
                    break;
                }
                if (ground(at(cx, y - 1, cz)) || at(cx, y, cz) == CLIMB) {
                    break;
                }
                gap++;
                int floor = y - 1;
                while (region.contains(cx, floor, cz) && passable(cx, floor, cz)) {
                    floor--;
                }
                depth = Math.max(depth, y - 1 - floor);
            }
            out.append("gap to the ").append(facing).append(": ").append(gap == 8 ? "8+" : String.valueOf(gap))
                    .append(" blocks without floor from ").append(Coords.show(new BlockPos(x, y, z)));
            out.append(depth > SAFE_DROP ? ", " + depth + "+ deep" : "");
            return out.append("; a jump clears ").append(FLAT_GAP).append(" on the level, ").append(FLAT_GAP - 1)
                    .append(" going up a block").toString();
        }
        if (dy > 0) {
            return out.append("the target is ").append(dy).append(" blocks higher: nothing to climb near ")
                    .append(Coords.show(near)).toString();
        }
        return out.append("the way on is blocked further along; try path to a point between").toString();
    }

    private String name(int x, int y, int z) {
        String s = grid.stateAt(x, y, z);
        return s == null ? "unknown" : BlockNames.brief(BlockNames.id(s));
    }
}
