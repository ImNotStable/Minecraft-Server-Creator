package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Furnishing a built room by what it is for (ADR 0083): props in its corners and along its walls, a centre piece, a rug
 * and a light, in one wood and one colour. It reads the room as built: floor spots under a ceiling, the wall beside each,
 * the corners, and keeps the ways in clear (nothing beside a door or in front of an opening). Facings follow the room:
 * chests and barrels face out from their wall, chairs face their table, beds turn so their head is against a wall.
 */
final class Furnish {
    static final List<String> ROOMS = List.of("bedroom", "kitchen", "dining", "library", "study", "tavern", "storage",
            "smithy", "alchemy", "chapel", "throne", "shop", "barracks", "bathroom");
    static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "pale_oak", "bamboo", "crimson", "warped");

    /** A room's props: corners, walls, the centre piece, whether it gets a rug and a ceiling light. */
    private record Plan(List<String> corners, List<String> edges, String centre, boolean rug, boolean light) {
    }

    private static final Map<String, Plan> PLANS = Map.ofEntries(
            Map.entry("bedroom", new Plan(List.of("bed", "chest", "shelf2"), List.of("barrel", "nightstand", "W_trapdoor_shelf",
                    "flower_pot"), "none", true, true)),
            Map.entry("kitchen", new Plan(List.of("barrel", "crafting_table"), List.of("smoker", "furnace", "barrel",
                    "cauldron", "W_counter", "W_counter", "composter"), "table", false, true)),
            Map.entry("dining", new Plan(List.of("barrel", "flower_pot"), List.of("barrel", "W_counter"), "long_table", true,
                    true)),
            Map.entry("library", new Plan(List.of("shelf2", "shelf2"), List.of("shelf2", "shelf2", "chiseled_shelf",
                    "lectern"), "table", true, true)),
            Map.entry("study", new Plan(List.of("shelf2", "lectern"), List.of("shelf2", "W_desk", "chiseled_shelf"), "none",
                    true, true)),
            Map.entry("tavern", new Plan(List.of("barrel_stack", "barrel"), List.of("barrel", "W_counter", "brewing_stand",
                    "barrel"), "tables", false, true)),
            Map.entry("storage", new Plan(List.of("barrel_stack", "hay_block", "chest"), List.of("barrel", "chest",
                    "barrel_stack", "W_crate"), "none", false, true)),
            Map.entry("smithy", new Plan(List.of("blast_furnace", "lava_cauldron"), List.of("anvil", "smithing_table",
                    "grindstone", "barrel", "W_counter"), "none", false, true)),
            Map.entry("alchemy", new Plan(List.of("shelf2", "cauldron"), List.of("brewing_stand", "W_counter",
                    "shelf2", "cauldron"), "enchanting", true, true)),
            Map.entry("chapel", new Plan(List.of("candles", "flower_pot"), List.of("candles"), "pews", true, true)),
            Map.entry("throne", new Plan(List.of("candles", "barrel"), List.of("candles"), "throne", true, true)),
            Map.entry("shop", new Plan(List.of("barrel", "chest"), List.of("barrel", "W_crate", "barrel"), "counter", false,
                    true)),
            Map.entry("barracks", new Plan(List.of("bed", "chest"), List.of("bed", "chest", "bed", "barrel"), "table", false,
                    true)),
            Map.entry("bathroom", new Plan(List.of("water_cauldron", "barrel"), List.of("W_counter", "flower_pot"), "none",
                    true, true)));

    private Furnish() {
    }

    static void check(String room, String wood, String color) {
        if (!ROOMS.contains(room)) {
            throw new ToolException("unknown room " + room + "; use one of " + String.join(", ", ROOMS));
        }
        if (!WOODS.contains(wood)) {
            throw new ToolException("unknown wood " + wood + "; use one of " + String.join(", ", WOODS));
        }
        if (!Things.DYES.contains(color)) {
            throw new ToolException("color is a dye colour, got " + color);
        }
    }

    /** A floor spot: position, the wall beside it (-1 none), whether it is a corner. */
    private record Spot(int x, int y, int z, int wall, boolean corner, long hash) {
    }

    static void draw(Canvas c, Step.Furnish f) throws InterruptedException {
        Region box = f.box();
        Canvas.Lookup at = c.current(Detail.grow(box, 2));
        Canvas.Mask mask = c.mask(box, f.replace());
        Plan plan = PLANS.get(f.room());
        Put put = new Put(c, at, mask, f.wood(), f.color());
        List<Spot> spots = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (Detail.fits(at, "floor", x, y, z) && !nearWay(at, x, y, z)) {
                        spots.add(new Spot(x, y, z, Joins.wallSide(at, x, y, z), Detail.fits(at, "corner", x, y, z),
                                Noise.hash(f.seed() + 7, x * 31L + y, z)));
                    }
                }
            }
        }
        if (spots.isEmpty()) {
            throw new ToolException("furnish found no floor under a ceiling in the box; give the room's inside, floor to "
                    + "ceiling, after its walls and roof are built");
        }
        int floor = spots.stream().mapToInt(Spot::y).min().orElseThrow();
        List<Spot> ground = spots.stream().filter(s -> s.y() == floor).toList();
        // Centre first, so wall props leave the middle alone and chairs find room.
        centre(put, plan.centre(), ground, f);
        int i = 0;
        for (Spot s : ground.stream().filter(Spot::corner).sorted(Comparator.comparingLong(Spot::hash)).toList()) {
            put.prop(plan.corners().get(i++ % plan.corners().size()), s);
        }
        List<Spot> edges = ground.stream().filter(s -> s.wall() >= 0 && !s.corner())
                .sorted(Comparator.comparingLong(Spot::hash)).toList();
        int want = edges.size() * f.density() / 100;
        i = 0;
        for (Spot s : edges) {
            if (i >= want) {
                break;
            }
            if (put.prop(plan.edges().get(i % plan.edges().size()), s)) {
                i++;
            }
        }
        if (plan.light()) {
            chandelier(put, ground);
        }
    }

    /** Beside (or diagonal to) a door or an opening in the walls: kept clear. */
    private static boolean nearWay(Canvas.Lookup at, int x, int y, int z) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int nz = z + dz;
                if (dx == 0 && dz == 0) {
                    continue;
                }
                String here = at.at(nx, y, nz);
                if (Joins.door(Joins.id(here)) || Joins.door(Joins.id(at.at(nx, y + 1, nz)))) {
                    return true;
                }
                // An opening in the wall: open here, with wall on two opposite sides of the gap.
                if (BlockNames.isAir(here) && (Joins.solid(at.at(nx + 1, y, nz)) && Joins.solid(at.at(nx - 1, y, nz))
                        || Joins.solid(at.at(nx, y, nz + 1)) && Joins.solid(at.at(nx, y, nz - 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void centre(Put put, String kind, List<Spot> ground, Step.Furnish f) {
        if (kind.equals("none") && !PLANS.get(f.room()).rug()) {
            return;
        }
        double cx = ground.stream().mapToInt(Spot::x).average().orElseThrow();
        double cz = ground.stream().mapToInt(Spot::z).average().orElseThrow();
        int y = ground.getFirst().y();
        int minX = ground.stream().mapToInt(Spot::x).min().orElseThrow();
        int maxX = ground.stream().mapToInt(Spot::x).max().orElseThrow();
        int minZ = ground.stream().mapToInt(Spot::z).min().orElseThrow();
        int maxZ = ground.stream().mapToInt(Spot::z).max().orElseThrow();
        int x = (int) Math.round(cx);
        int z = (int) Math.round(cz);
        boolean alongX = maxX - minX >= maxZ - minZ;
        switch (kind) {
            case "table" -> table(put, x, y, z);
            case "tables" -> {
                int span = alongX ? maxX - minX : maxZ - minZ;
                for (int k = -span / 2 + 2; k <= span / 2 - 2; k += 4) {
                    table(put, alongX ? x + k : x, y, alongX ? z : z + k);
                }
            }
            case "long_table" -> {
                int half = Math.max(1, (alongX ? maxX - minX : maxZ - minZ) / 2 - 2);
                for (int k = -half; k <= half; k++) {
                    int tx = alongX ? x + k : x;
                    int tz = alongX ? z : z + k;
                    put.block(tx, y, tz, put.wood + "_slab[type=top]");
                    if (Math.floorMod(k, 3) == 0) {
                        put.block(tx, y + 1, tz, "candle[candles=3,lit=true]");
                    }
                    for (int side : new int[] {-1, 1}) {
                        int sx = alongX ? tx : tx + side;
                        int sz = alongX ? tz + side : tz;
                        put.block(sx, y, sz, put.wood + "_stairs[facing=" + face(sx - tx, sz - tz) + ",half=bottom]");
                    }
                }
            }
            case "pews", "throne" -> {
                // The far wall from the room's longest axis gets the altar or throne; rows face it.
                int far = alongX ? maxX : maxZ;
                int ax = alongX ? far : x;
                int az = alongX ? z : far;
                int toward = alongX ? 1 : 2; // east or south
                String back = Joins.DIRS[toward];
                if (kind.equals("throne")) {
                    put.block(ax, y, az, "polished_blackstone_stairs[facing=" + back + ",half=bottom]");
                    put.block(ax - (alongX ? 0 : 1), y, az - (alongX ? 1 : 0), "gold_block");
                    put.block(ax + (alongX ? 0 : 1), y, az + (alongX ? 1 : 0), "gold_block");
                    for (int k = (alongX ? minX : minZ); k < far; k++) {
                        put.block(alongX ? k : x, y, alongX ? z : k, f.color() + "_carpet");
                    }
                } else {
                    put.block(ax, y, az, "polished_andesite");
                    put.block(ax, y + 1, az, "candle[candles=4,lit=true]");
                    int width = Math.max(1, (alongX ? maxZ - minZ : maxX - minX) / 2 - 1);
                    for (int row = far - 3; row >= (alongX ? minX : minZ) + 1; row -= 2) {
                        for (int k = -width; k <= width; k++) {
                            if (k == 0) {
                                continue; // the aisle
                            }
                            put.block(alongX ? row : x + k, y, alongX ? z + k : row,
                                    put.wood + "_stairs[facing=" + Joins.DIRS[(toward + 2) % 4] + ",half=bottom]");
                        }
                    }
                }
            }
            case "counter" -> {
                int half = Math.max(1, (alongX ? maxZ - minZ : maxX - minX) / 2 - 1);
                for (int k = -half; k <= half; k++) {
                    put.block(alongX ? x : x + k, y, alongX ? z + k : z, put.wood + "_stairs[facing="
                            + (alongX ? "east" : "south") + ",half=top]");
                }
            }
            case "enchanting" -> put.block(x, y, z, "enchanting_table");
            default -> {
            }
        }
        // The rug last, around whatever stands in the middle.
        if (PLANS.get(f.room()).rug()) {
            int rx = Math.max(0, (maxX - minX) / 2 - 2);
            int rz = Math.max(0, (maxZ - minZ) / 2 - 2);
            for (int dz = -rz; dz <= rz; dz++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    boolean edge = Math.abs(dx) == rx || Math.abs(dz) == rz;
                    put.block(x + dx, y, z + dz, (edge ? f.color() : "white") + "_carpet");
                }
            }
        }
    }

    /** A fence table with a carpet top, a lantern on it and a chair on each open side. */
    private static void table(Put put, int x, int y, int z) {
        if (!put.block(x, y, z, put.wood + "_fence")) {
            return;
        }
        put.block(x, y + 1, z, put.wood + "_pressure_plate");
        for (int d = 0; d < 4; d++) {
            int sx = x + Joins.STEP[d][0];
            int sz = z + Joins.STEP[d][1];
            put.block(sx, y, sz, put.wood + "_stairs[facing=" + Joins.DIRS[d] + ",half=bottom]");
        }
    }

    private static String face(int dx, int dz) {
        return dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north";
    }

    /** A lantern on a chain from the ceiling over the room's middle. */
    private static void chandelier(Put put, List<Spot> ground) {
        double cx = ground.stream().mapToInt(Spot::x).average().orElseThrow();
        double cz = ground.stream().mapToInt(Spot::z).average().orElseThrow();
        int x = (int) Math.round(cx);
        int z = (int) Math.round(cz);
        int y = ground.getFirst().y();
        int top = y;
        while (top < y + 12 && BlockNames.isAir(put.at.at(x, top + 1, z))) {
            top++;
        }
        if (top - y < 3 || top >= y + 12) {
            return;
        }
        int chains = Math.min(2, top - y - 3);
        for (int k = 0; k < chains; k++) {
            put.block(x, top - k, z, "chain[axis=y]");
        }
        put.block(x, top - chains, z, "lantern[hanging=true]");
    }

    /** Placing into air only, inside the mask, with the room's wood and colour. */
    private static final class Put {
        final Canvas c;
        final Canvas.Lookup at;
        final Canvas.Mask mask;
        final String wood;
        final String color;

        Put(Canvas c, Canvas.Lookup at, Canvas.Mask mask, String wood, String color) {
            this.c = c;
            this.at = at;
            this.mask = mask;
            this.wood = wood;
            this.color = color;
        }

        boolean block(int x, int y, int z, String state) {
            if (!BlockNames.isAir(at.at(x, y, z)) || c.planned(x, y, z) != null || !mask.allows(x, y, z)) {
                return false;
            }
            c.set(x, y, z, BlockNames.full(state));
            return true;
        }

        /** One prop at a floor spot; false when it did not fit. */
        boolean prop(String prop, Spot s) {
            int x = s.x();
            int y = s.y();
            int z = s.z();
            String w = wood;
            String out = s.wall() >= 0 ? Joins.DIRS[(s.wall() + 2) % 4] : "north";
            String along = s.wall() >= 0 ? Joins.DIRS[(s.wall() + 1) % 4] : "east";
            return switch (prop) {
                // The foot one step out from the wall, the head against it.
                case "bed" -> s.wall() >= 0 && BlockNames.isAir(at.at(x, y, z))
                        && block(x - Joins.STEP[s.wall()][0], y, z - Joins.STEP[s.wall()][1],
                        color + "_bed[facing=" + Joins.DIRS[s.wall()] + "]");
                case "shelf2" -> block(x, y, z, "bookshelf") && (block(x, y + 1, z, "bookshelf") || true);
                case "chiseled_shelf" -> block(x, y, z, "chiseled_bookshelf[facing=" + out + "]");
                case "barrel_stack" -> block(x, y, z, "barrel[facing=up]") && (block(x, y + 1, z, "barrel[facing=up]") || true);
                case "barrel" -> block(x, y, z, "barrel[facing=up]");
                case "chest" -> block(x, y, z, "chest[facing=" + out + "]");
                case "nightstand" -> block(x, y, z, w + "_slab[type=top]") && (block(x, y + 1, z, "candle[candles=1,lit=true]") || true);
                case "W_trapdoor_shelf" -> block(x, y, z, w + "_trapdoor[facing=" + out + ",half=top,open=false]")
                        && (block(x, y + 1, z, "flower_pot") || true);
                case "flower_pot" -> block(x, y, z, "potted_" + (s.hash() % 3 == 0 ? "fern" : s.hash() % 3 == 1 ? "azure_bluet"
                        : "red_tulip"));
                case "W_counter" -> block(x, y, z, w + "_stairs[facing=" + Joins.DIRS[s.wall() < 0 ? 0 : s.wall()]
                        + ",half=top]");
                case "W_desk" -> block(x, y, z, w + "_slab[type=top]") && (block(x, y + 1, z, "candle[candles=2,lit=true]") || true);
                case "W_crate" -> block(x, y, z, w + "_planks") && (block(x, y + 1, z, w + "_trapdoor[facing=north,half=bottom,"
                        + "open=false]") || true);
                case "candles" -> block(x, y, z, "candle[candles=" + (1 + Math.floorMod(s.hash(), 4)) + ",lit=true]");
                case "anvil" -> block(x, y, z, "anvil[facing=" + along + "]");
                case "grindstone" -> block(x, y, z, "grindstone[face=floor,facing=" + along + "]");
                case "lectern" -> block(x, y, z, "lectern[facing=" + out + "]");
                case "smoker", "furnace", "blast_furnace" -> block(x, y, z, prop + "[facing=" + out + "]");
                case "hay_block" -> block(x, y, z, "hay_block[axis=y]");
                case "lava_cauldron", "water_cauldron" -> block(x, y, z, prop.equals("water_cauldron")
                        ? "water_cauldron[level=3]" : "lava_cauldron");
                case "brewing_stand" -> block(x, y, z, "brewing_stand");
                default -> block(x, y, z, prop);
            };
        }
    }
}
