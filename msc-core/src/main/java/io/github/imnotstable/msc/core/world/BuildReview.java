package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.renderer.BlockColors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measurable signs of a plain build, so an agent judges its work by numbers and not only by its own look at a render
 * (ADR 0068): few block types, few shaped blocks, flat walls of one block, flat roofs, no lights, and walls
 * speckled by a random mix of clashing colours (ADR 0071). Soil is not part of a build's numbers, and the land around
 * it gets its own ({@link LandReview}, ADR 0075). ADR 0077 adds the finish a builder gives: wall tops and the base
 * ending on a plain block edge, rooms under a roof with nothing in them, and no plants. Straight lines are not
 * judged: a good build has plenty, dressed with detail.
 */
public final class BuildReview {
    /** Name parts of blocks that add shape or detail: trim, roofs, sills, furniture, plants, lights. */
    private static final List<String> DETAIL = List.of("stairs", "slab", "wall", "fence", "pane", "bars", "trapdoor",
            "door", "button", "lantern", "chain", "carpet", "banner", "pot", "candle", "torch", "sign", "rod", "leaves",
            "vine", "barrel", "campfire", "chest", "anvil", "lectern", "bell", "skull", "head", "flower", "short_grass",
            "tall_grass", "fern", "lichen", "scaffolding", "ladder", "lever", "pressure_plate", "dripleaf");
    private static final List<String> LIGHTS = List.of("lantern", "torch", "glowstone", "sea_lantern", "shroomlight",
            "froglight", "redstone_lamp", "campfire", "end_rod", "candle", "jack_o_lantern", "beacon", "glow_lichen");
    private static final Set<String> FLUIDS = Set.of("water", "lava", "bubble_column");
    /** Name parts of plants and greenery on and around a build. */
    private static final List<String> PLANTS = List.of("leaves", "vine", "flower", "grass", "fern", "moss", "azalea",
            "lichen", "dripleaf", "bush", "petals", "roots", "tulip", "poppy", "dandelion", "orchid", "allium", "bluet",
            "daisy", "cornflower", "lily", "rose", "peony", "lilac", "sapling", "kelp", "seagrass", "cactus", "bamboo",
            "sugar_cane", "mushroom", "wart", "litter");
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static final int MIN_TYPES = 6;
    static final int MAX_TOP_SHARE = 50;
    static final int MIN_DETAIL = 12;
    static final int MAX_FLAT = 45;
    static final int MIN_ROOF = 15;
    static final int MAX_SPECKLED = 20;
    /** Roofs and ground seen from above: a lone clashing block is rarer there than on a wall with windows (ADR 0078). */
    static final int MAX_SPECKLED_ROOF = 10;
    /** RGB distance where two blocks clash: stone_bricks to mossy_stone_bricks is 21, deepslate to prismarine 134. */
    static final int CLASH = 64;
    /** A region at least this much land (by blocks) is judged as land only: hills are not walls or flat roofs. */
    static final int LAND_ONLY = 60;
    /** Fewer built (not ground) blocks than this in a mostly-land region: only the land is judged (ADR 0095). */
    static final int MIN_BUILT = 400;
    static final int MAX_PLAIN_TOPS = 60;
    static final int MAX_BARE_BASE = 50;
    /** One furnishing per this many floor blocks under a roof. */
    static final int FLOOR_PER_PROP = 16;

    private BuildReview() {
    }

    public static String review(BlockGrid grid) {
        return review(grid, false);
    }

    /** {@code devFloor}: the region is in the flat dev world, where land does not belong (ADR 0078). */
    public static String review(BlockGrid grid, boolean devFloor) {
        return review(grid, devFloor, true);
    }

    /** {@code split}: in a region the size of a town, also judge each building on its own (ADR 0097). */
    private static String review(BlockGrid grid, boolean devFloor, boolean split) {
        Region r = grid.region();
        String[] names = new String[grid.palette().size()];
        boolean[] fluid = new boolean[names.length];
        boolean[] soil = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            String id = BlockNames.brief(BlockNames.id(grid.palette().get(i)));
            fluid[i] = FLUIDS.contains(id);
            names[i] = BlockNames.isAir(grid.palette().get(i)) || fluid[i] ? null : id;
            soil[i] = names[i] != null && LandReview.SOIL.contains(id);
        }
        LandReview.Result land = LandReview.review(grid, names, fluid, devFloor);
        boolean[] isGround = ground(grid, names, soil);
        int built = 0;
        int cover = 0;
        for (int i = 0; i < isGround.length; i++) {
            int b = grid.blocks()[i];
            if (b >= 0 && names[b] != null && !isGround[i]) {
                built++;
                cover += groundCover(names[b]) ? 1 : 0;
            }
        }
        // Grass, flowers and saplings on shaped land are the land's cover, not a building with flat roofs and no lights.
        if (built > 0 && cover == built) {
            return withLand("review: only plants above the ground (" + built + "), so only the land is judged", land,
                    WaterReview.basins(grid, names, fluid, isGround), false);
        }
        // Builds in a region that is mostly land are still judged: run 8 hid unfinished houses in big land regions.
        if (land.landShare() >= LAND_ONLY && built < MIN_BUILT) {
            return withLand("review: mostly land (" + land.landShare() + "% of the blocks), so only the land is "
                    + "judged; review each build on its own region", land,
                    WaterReview.basins(grid, names, fluid, isGround), false);
        }
        int[] rgb = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            rgb[i] = names[i] == null ? 0 : BlockColors.side(names[i]);
        }
        int ground = groundTop(grid, names);
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        int detail = 0;
        int lights = 0;
        int faces = 0;
        int flat = 0;
        int speckled = 0;
        int tops = 0;
        int plainTops = 0;
        int base = 0;
        int bareBase = 0;
        int plants = 0;
        int floor = 0;
        int props = 0;
        int dark = 0;
        int ladders = 0;
        String firstLadder = null;
        boolean[] light = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            light[i] = names[i] != null && matches(names[i], LIGHTS);
        }
        for (int y = ground + 1; y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String b = name(grid, names, x, y, z);
                    if (b == null && r.contains(x, y - 1, z) && built(grid, names, isGround, x, y - 1, z)
                            && covered(grid, names, x, y, z) && enclosed(grid, names, x, y, z)) {
                        floor++;
                        dark += lit(grid, light, x, y, z) ? 0 : 1;
                    }
                    if (b == null || isGround[r.index(x, y, z)]) {
                        continue;
                    }
                    total++;
                    boolean shaped = matches(b, DETAIL);
                    boolean green = matches(b, PLANTS);
                    plants += green ? 1 : 0;
                    // Windows and doors sit in the walls: not furnishings.
                    // Trapdoors are shutters and facades as often as furniture, so they do not count (ADR 0093).
                    props += shaped && !green && !b.contains("pane") && !b.contains("bars") && !b.contains("door")
                            && covered(grid, names, x, y, z) && enclosed(grid, names, x, y, z) ? 1 : 0;
                    if (b.equals("ladder") && r.contains(x, y - 1, z) && name(grid, names, x, y - 1, z) == null) {
                        ladders++;
                        firstLadder = firstLadder == null ? x + "," + y + "," + z : firstLadder;
                    }
                    boolean open = false;
                    for (int[] s : SIDES) {
                        open |= grid.region().contains(x + s[0], y, z + s[1]) && name(grid, names, x + s[0], y, z + s[1]) == null;
                    }
                    if (open && !green && y < r.max().y() && name(grid, names, x, y + 1, z) == null) {
                        tops++;
                        plainTops += shaped ? 0 : 1;
                    }
                    boolean onGround = y - 1 <= ground || r.contains(x, y - 1, z) && !built(grid, names, isGround, x, y - 1, z)
                            && name(grid, names, x, y - 1, z) != null;
                    if (onGround && !green) {
                        for (int[] s : SIDES) {
                            if (grid.region().contains(x + s[0], y, z + s[1]) && name(grid, names, x + s[0], y, z + s[1]) == null) {
                                base++;
                                bareBase += shaped ? 0 : 1;
                            }
                        }
                    }
                    counts.merge(b, 1, Integer::sum);
                    detail += matches(b, DETAIL) ? 1 : 0;
                    lights += matches(b, LIGHTS) ? 1 : 0;
                    // Each side open to air is a wall face; it is flat when its 3x3 patch is the same block, all open the same way.
                    for (int[] s : SIDES) {
                        if (name(grid, names, x + s[0], y, z + s[1]) != null || !grid.region().contains(x + s[0], y, z + s[1])) {
                            continue;
                        }
                        faces++;
                        flat += flatPatch(grid, names, b, x, y, z, s[0], s[1], ground) ? 1 : 0;
                        speckled += clashes(grid, names, rgb, x, y, z, s[1], s[0]) >= 3 ? 1 : 0;
                    }
                }
            }
        }
        if (total == 0) {
            return withLand("review: nothing built above the ground" + (ground >= r.min().y() ? " (y " + ground
                    + " and below)" : ""), land,
                    WaterReview.basins(grid, names, fluid, isGround), false);
        }
        int[] roof = roof(grid, names, isGround, ground);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        int topShare = pct(top.get(0).getValue(), total);
        int detailPct = pct(detail, total);
        int flatPct = faces == 0 ? 0 : pct(flat, faces);
        int speckledPct = faces == 0 ? 0 : pct(speckled, faces);
        int roofPct = roof[1] == 0 ? 100 : pct(roof[0], roof[1]);
        int speckledRoofPct = roof[1] == 0 ? 0 : pct(roof[3], roof[1]);
        int height = roof[2];
        int plainTopPct = tops == 0 ? 0 : pct(plainTops, tops);
        int bareBasePct = base == 0 ? 0 : pct(bareBase, base);

        StringBuilder out = new StringBuilder("review");
        if (ground >= r.min().y()) {
            out.append(" (ground up to y ").append(ground).append(" left out)");
        }
        out.append(": ").append(total).append(" blocks, ").append(counts.size()).append(counts.size() == 1 ? " type: "
                : " types: ");
        for (int i = 0; i < Math.min(4, top.size()); i++) {
            out.append(i == 0 ? "" : ", ").append(top.get(i).getKey()).append(' ').append(pct(top.get(i).getValue(), total)).append('%');
        }
        out.append(String.format(Locale.ROOT, "\ndetail blocks %d%% (aim %d%%+), flat wall faces %d%% (aim under %d%%), "
                + "speckled faces %d%% (aim under %d%%), shaped roof %d%%, speckled roof %d%% (aim under %d%%), "
                + "lights %d\n", detailPct, MIN_DETAIL, flatPct, MAX_FLAT, speckledPct, MAX_SPECKLED, roofPct,
                speckledRoofPct, MAX_SPECKLED_ROOF, lights));
        out.append(String.format(Locale.ROOT, "plain wall tops %d%% (aim under %d%%), bare base %d%% (aim under %d%%), "
                + "floor under a roof %d with %d furnishings, plants %d\n", plainTopPct, MAX_PLAIN_TOPS, bareBasePct,
                MAX_BARE_BASE, floor, props, plants));
        List<String> fixes = new ArrayList<>(doors(grid, names));
        fixes.addAll(masses(grid, names, isGround));
        fixes.addAll(WaterReview.fixes(grid, names, fluid, isGround));
        if (flatPct > MAX_FLAT && faces >= 20) {
            fixes.add("flat walls: give each wall depth (pillars or a frame 1 out, windows 1 in), a base and a trim "
                    + "line of stairs or slabs (trim at=base, band, top; sills, shutters, frames), and mix in variants "
                    + "of the main block (weather, a gradient a>b>c)");
        }
        if (speckledPct > MAX_SPECKLED && faces >= 50) {
            fixes.add("speckled walls: a random mix of clashing colours reads as noise; mix only near tones "
                    + "(a block with its own cracked, mossy or polished variants) and put contrast in lines: "
                    + "trims, bands, frames, an accent at the focal point");
        }
        if (speckledRoofPct > MAX_SPECKLED_ROOF && roof[1] >= 50) {
            fixes.add("speckled roof: seen from above, " + speckledRoofPct + "% of the roof is a lone block among "
                    + "clashing ones; keep a roof to one material and its near tones, weather it in streaks from the "
                    + "ridge and eaves (weather), and put contrast in lines: ridge, eaves, dormers");
        }
        if (detailPct < MIN_DETAIL) {
            fixes.add("few shaped blocks: stairs and slabs for roofs, sills and trim; walls and fences for railings; "
                    + "trapdoors for shutters; lanterns, barrels and plants as props");
        }
        if (counts.size() < MIN_TYPES) {
            fixes.add("only " + counts.size() + (counts.size() == 1 ? " block type" : " block types")
                    + ": add 2-3 variants of the main block (cracked, mossy, polished) and one accent");
        } else if (topShare > MAX_TOP_SHARE) {
            // The bar is on the whole build: a 70% main block in its mix still ends over it (round 4 of ADR 0107).
            fixes.add(top.get(0).getKey() + " is " + topShare + "% of the build (aim at most " + MAX_TOP_SHARE
                    + "%): give its variants more of the mix, so the main block is under half of it, and use a "
                    + "second material for frames, floors or the roof");
        }
        if (roofPct < MIN_ROOF && height >= 6) {
            fixes.add("flat roofs: pitch them with stairs, overhang 1, and break long ridges with dormers or a tower");
        }
        if (plainTopPct > MAX_PLAIN_TOPS && tops >= 20) {
            fixes.add("plain wall tops: finish rooflines and wall tops with stairs, slabs, walls or fences (a cornice: "
                    + "trim at=top, a parapet, a roof with overhang)");
        }
        if (bareBasePct > MAX_BARE_BASE && base >= 20) {
            fixes.add("bare base: the build meets the ground on a clean line; add a plinth (trim at=base), bushes "
                    + "and flowers (decorate on=ground), and worn ground at the doors");
        }
        if (floor >= 24 && props * FLOOR_PER_PROP < floor) {
            fixes.add("empty rooms: " + floor + " floor blocks under a roof and " + props + " furnishings; furnish "
                    + "them (decorate on=edge, corner, ceiling; tables from slabs and fences, beds, shelves, lights)");
        }
        if (floor >= 24 && dark * 100 > floor * MAX_DARK) {
            fixes.add("dark rooms: " + pct(dark, floor) + "% of the floor inside has no light within " + LIGHT_REACH
                    + " blocks (aim under " + MAX_DARK + "%), so it is dark and mobs spawn there; hang lanterns "
                    + "(decorate on=ceiling tip=lantern spacing=6) or set lights in walls and floors");
        }
        if (ladders > 0) {
            fixes.add("broken ladders: " + ladders + " (first at " + firstLadder + ") end in the air above a gap; run "
                    + "each ladder from the floor up, one block above another, against a wall");
        }
        if (plants == 0 && total >= 300) {
            fixes.add("no plants: vines and leaves on walls (decorate on=wall), bushes and flowers at the base, moss "
                    + "on ledges (weather); leave this where nothing grows");
        }
        if (lights == 0 && total >= 200) {
            fixes.add("no lights: lanterns, torches or glowing blocks at doors, paths and windows");
        }
        if (split && r.sizeX() * r.sizeZ() > TOWN_AREA) {
            List<Region> buildings = buildings(grid, names, isGround);
            List<String> failing = new ArrayList<>();
            for (Region b : buildings) {
                String own = review(sub(grid, b), devFloor, false);
                if (own.contains("\nverdict: NOT DONE")) {
                    failing.add(Coords.show(b) + ": " + String.join(", ", OpenReviews.fixes(own)));
                }
            }
            if (!failing.isEmpty()) {
                // A town's numbers average its houses: run 10 passed Havenfall as one 110x90 box (ADR 0097).
                fixes.add(0, "buildings failing: " + failing.size() + " of " + buildings.size() + " fail on their own ("
                        + String.join("; ", failing.subList(0, Math.min(4, failing.size()))) + "); review each "
                        + "building's own region and fix it there");
            }
            int[] streets = streets(grid, names, light, buildings);
            if (streets[1] >= STREET_CELLS && streets[0] * 100 > streets[1] * MAX_DARK_STREETS) {
                // Run 11's Havenfall: skeletons and a spider in the streets at night, 22 mob drops (ADR 0098).
                fixes.add("dark streets: " + pct(streets[0], streets[1]) + "% of the open ground within " + STREET_REACH
                        + " blocks of the buildings has no light within " + LIGHT_REACH + " (aim under " + MAX_DARK_STREETS
                        + "%), so mobs spawn in town at night; lamp posts along paths and lanterns at doors "
                        + "(decorate on=ground with a fence and tip=lantern, spacing=8)");
            }
        }
        return withLand(out.toString().stripTrailing(), land, fixes, true);
    }

    static final int STREET_REACH = 4;
    static final int STREET_CELLS = 100;
    static final int MAX_DARK_STREETS = 50;

    /**
     * Dark and all open ground cells (air over a solid block, open to the sky) within {@link #STREET_REACH} of a
     * building's box and outside every box: the streets and yards of a town.
     */
    private static int[] streets(BlockGrid grid, String[] names, boolean[] light, List<Region> buildings) {
        Region r = grid.region();
        int dark = 0;
        int all = 0;
        for (int z = r.min().z(); z <= r.max().z(); z++) {
            for (int x = r.min().x(); x <= r.max().x(); x++) {
                boolean near = false;
                boolean inside = false;
                for (Region b : buildings) {
                    inside |= x >= b.min().x() && x <= b.max().x() && z >= b.min().z() && z <= b.max().z();
                    near |= x >= b.min().x() - STREET_REACH && x <= b.max().x() + STREET_REACH
                            && z >= b.min().z() - STREET_REACH && z <= b.max().z() + STREET_REACH;
                }
                if (!near || inside) {
                    continue;
                }
                // The column's top solid block, looking through air and plants; a player stands on it.
                for (int y = r.max().y() - 1; y >= r.min().y(); y--) {
                    String n = name(grid, names, x, y, z);
                    if (n != null && (LandReview.SOIL.contains(n) || !matches(n, PLANTS))) { // grass_block is ground
                        all++;
                        dark += lit(grid, light, x, y + 1, z) ? 0 : 1;
                        break;
                    }
                }
            }
        }
        return new int[] {dark, all};
    }

    /**
     * Doors a player cannot use (ADR 0093): half a door (its other half gone, under a floor or lintel), and a door whose
     * bottom has a full block in front or behind (a floor laid at the door's top half, ground piled against it).
     */
    public static List<String> doors(BlockGrid grid, String[] names) {
        Region r = grid.region();
        int half = 0;
        int blocked = 0;
        String firstHalf = null;
        String firstBlocked = null;
        int holes = 0;
        int filled = 0;
        String firstHole = null;
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    int b = grid.blocks()[r.index(x, y, z)];
                    String n = b < 0 ? null : names[b];
                    if (n == null) {
                        holes += hole(grid, names, x, y, z) ? 1 : 0;
                        firstHole = firstHole == null && hole(grid, names, x, y, z) ? x + "," + y + "," + z : firstHole;
                        continue;
                    }
                    filled += n.contains("pane") || n.contains("glass") || n.contains("bars") ? 1 : 0;
                    if (!n.endsWith("_door") || n.endsWith("trapdoor")) {
                        continue;
                    }
                    filled++;
                    String state = grid.palette().get(b);
                    boolean lower = !state.contains("half=upper");
                    int oy = lower ? y + 1 : y - 1;
                    if (r.contains(x, oy, z)) {
                        int o = grid.blocks()[r.index(x, oy, z)];
                        if (o < 0 || !n.equals(names[o])
                                || !grid.palette().get(o).contains(lower ? "half=upper" : "half=lower")) {
                            half++;
                            firstHalf = firstHalf == null ? x + "," + y + "," + z : firstHalf;
                        }
                    }
                    if (lower) {
                        boolean alongX = state.contains("facing=east") || state.contains("facing=west");
                        for (int s = -1; s <= 1; s += 2) {
                            String side = name(grid, names, alongX ? x + s : x, y, alongX ? z : z + s);
                            if (side != null && !matches(side, DETAIL) && !matches(side, PLANTS)) {
                                blocked++;
                                firstBlocked = firstBlocked == null ? x + "," + y + "," + z : firstBlocked;
                                break;
                            }
                        }
                    }
                }
            }
        }
        List<String> fixes = new ArrayList<>();
        if (half > 0) {
            fixes.add("half doors: " + half + " (first at " + firstHalf + ") lack their other half, cut off by a floor, "
                    + "lintel or wall; a door is two blocks, its bottom on the floor's level and the block above free");
        }
        if (blocked > 0) {
            fixes.add("blocked doors: " + blocked + " (first at " + firstBlocked + ") have a full block in front of or "
                    + "behind their bottom half, so no one walks through; lay the floor inside at the door's bottom "
                    + "level (the floor block one below it) and clear the step outside");
        }
        // Open arches are fine among glazed windows; a build whose wall holes are mostly empty was left unfinished.
        if (holes >= MIN_HOLES && holes > filled) {
            fixes.add("open holes: " + holes + " gaps through walls with nothing in them (first at " + firstHole + "), "
                    + "more than the " + filled + " panes, glass and doors; glaze windows (glass panes, bars) and hang "
                    + "doors in doorways");
        }
        return fixes;
    }

    static final int MIN_HOLES = 4;
    static final int MAX_LEAVES_IN_WALLS = 8;
    static final int MAX_BOXY_LEAVES = 12;
    static final int MAX_CLOTH = 6;
    static final int MAX_TRAPDOOR_CLUMPS = 6;

    /**
     * Shapes a hand would not make (ADR 0094): leaves wedged into walls (a tree grown through a house), leaves in flat
     * boxes (a hedge as a pillar of leaf blocks), full wool hanging in the air (an awning that should be carpet, banners
     * or slabs), and trapdoors in clumps of three or more on a wall (a facade of trapdoors instead of shutters).
     */
    public static List<String> masses(BlockGrid grid, String[] names, boolean[] isGround) {
        Region r = grid.region();
        int wedged = 0;
        int leafFaces = 0;
        int boxy = 0;
        int cloth = 0;
        int clumped = 0;
        String firstWedged = null;
        String firstCloth = null;
        String firstClump = null;
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String n = name(grid, names, x, y, z);
                    if (n == null) {
                        continue;
                    }
                    String at = x + "," + y + "," + z;
                    if (n.contains("leaves")) {
                        // Under a built block and against another: a canopy inside a house or under its eaves, not
                        // a bush at a wall's foot (ADR 0094).
                        boolean[] wall = new boolean[6];
                        for (int k = 0; k < 6; k++) {
                            int[] d = six[k];
                            String o = name(grid, names, x + d[0], y + d[1], z + d[2]);
                            wall[k] = o != null && !o.contains("leaves") && !o.endsWith("_log") && !o.endsWith("_wood")
                                    && !o.endsWith("_stem") && !matches(o, PLANTS)
                                    && !isGround[r.index(x + d[0], y + d[1], z + d[2])];
                        }
                        if (wall[2] && (wall[0] || wall[1] || wall[4] || wall[5])) {
                            wedged++;
                            firstWedged = firstWedged == null ? at : firstWedged;
                        }
                        // A leaf in a run three tall with no wood near: a hedge or canopy stacked as blocks, not grown.
                        String below = name(grid, names, x, y - 1, z);
                        String above = name(grid, names, x, y + 1, z);
                        if (below != null && below.contains("leaves") && above != null && above.contains("leaves")) {
                            leafFaces++;
                            boxy += wood(grid, names, x, y, z) ? 0 : 1;
                        }
                    } else if (n.endsWith("_wool") && r.contains(x, y - 1, z) && r.contains(x, y + 1, z)
                            && name(grid, names, x, y - 1, z) == null && name(grid, names, x, y + 1, z) == null) {
                        cloth++;
                        firstCloth = firstCloth == null ? at : firstCloth;
                    } else if (n.endsWith("trapdoor")) {
                        int near = 0;
                        for (int[] d : six) {
                            String o = name(grid, names, x + d[0], y + d[1], z + d[2]);
                            near += o != null && o.endsWith("trapdoor") ? 1 : 0;
                        }
                        if (near >= 2) {
                            clumped++;
                            firstClump = firstClump == null ? at : firstClump;
                        }
                    }
                }
            }
        }
        List<String> fixes = new ArrayList<>();
        if (wedged >= MAX_LEAVES_IN_WALLS) {
            fixes.add("leaves in walls: " + wedged + " leaf blocks (first at " + firstWedged + ") are wedged against "
                    + "walls or roofs on two sides, a tree grown through a building; move the tree into the open or "
                    + "trim its canopy back from the walls");
        }
        if (boxy >= MAX_BOXY_LEAVES) {
            fixes.add("stacked leaves: " + boxy + " leaf blocks stand in columns three or more tall with no wood within "
                    + "6 blocks, a hedge or canopy built as a box of leaves; build trees with trunks and branches of "
                    + "logs and leaves in loose clusters at their tips, keep hedges 1-2 tall (decorate on=ground) or round "
                    + "them with sphere rough=");
        }
        if (cloth >= MAX_CLOTH) {
            fixes.add("hanging wool: " + cloth + " full wool blocks (first at " + firstCloth + ") hang in the air, an "
                    + "awning or canopy as a slab of solid cloth; use carpet on a slab or trapdoor frame, banners or "
                    + "stairs for a sloped awning");
        }
        if (clumped >= MAX_TRAPDOOR_CLUMPS) {
            fixes.add("trapdoor clumps: " + clumped + " trapdoors (first at " + firstClump + ") sit in clumps of three or "
                    + "more, a facade or window of trapdoors reads as noise; one shutter each side of a window, panes in "
                    + "the window, and a plain wall between");
        }
        return fixes;
    }

    /**
     * An air block through a wall: built blocks on both sides along the wall and above it (a lintel), open to air on
     * both faces; a window, or a doorway's cell.
     */
    private static boolean hole(BlockGrid grid, String[] names, int x, int y, int z) {
        if (!full(name(grid, names, x, y + 1, z))) {
            return false;
        }
        for (int a = 0; a < 2; a++) {
            int dx = a == 0 ? 1 : 0;
            int dz = 1 - dx;
            if (full(name(grid, names, x + dx, y, z + dz)) && full(name(grid, names, x - dx, y, z - dz))
                    && name(grid, names, x + dz, y, z + dx) == null && name(grid, names, x - dz, y, z - dx) == null
                    && grid.region().contains(x + dz, y, z + dx) && grid.region().contains(x - dz, y, z - dx)
                    // the wall beside it is open on both faces too: a hole through a wall, not a 1-wide corridor
                    && name(grid, names, x + dx + dz, y, z + dz + dx) == null
                    && name(grid, names, x + dx - dz, y, z + dz - dx) == null) {
                return true;
            }
        }
        return false;
    }

    /** Whether a log, wood or stem lies within 6 blocks: leaves of a tree, not a stack of leaf blocks. */
    private static boolean wood(BlockGrid grid, String[] names, int x, int y, int z) {
        for (int dy = -6; dy <= 6; dy++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dx = -6; dx <= 6; dx++) {
                    String o = name(grid, names, x + dx, y + dy, z + dz);
                    if (o != null && (o.endsWith("_log") || o.endsWith("_wood") || o.endsWith("_stem"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean full(String name) {
        return name != null && !matches(name, DETAIL) && !matches(name, PLANTS);
    }

    /** The build's text, the land's numbers on their own line, then every fix, or the pass line when there is none. */
    private static String withLand(String text, LandReview.Result land, List<String> buildFixes, boolean built) {
        StringBuilder out = new StringBuilder(text);
        if (!land.line().isEmpty()) {
            out.append("\nland: ").append(land.line());
        }
        List<String> fixes = new ArrayList<>(buildFixes);
        fixes.addAll(land.fixes());
        if (fixes.isEmpty()) {
            return !built && land.line().isEmpty() ? out.toString()
                    : out.append("\nno number below the bar; now judge the render: silhouette, story, scale").toString();
        }
        // A plain verdict first: agents read "fix first" as advice and called failing builds done (ADR 0084).
        out.append("\nverdict: NOT DONE, ").append(fixes.size()).append(fixes.size() == 1 ? " check" : " checks")
                .append(" below the bar");
        out.append("\nfix first:");
        for (int i = 0; i < fixes.size(); i++) {
            out.append(i == 0 ? " " : "; ").append(i + 1).append(") ").append(fixes.get(i));
        }
        return out.append("; then review this region again").toString();
    }

    /**
     * The top of the ground to leave out: bottom layers while each is at least 90% filled (a superflat floor, a
     * foundation slab), or one below the region when the bottom layer is not.
     */
    static int groundTop(BlockGrid grid, String[] names) {
        Region r = grid.region();
        int area = r.sizeX() * r.sizeZ();
        int y = r.min().y();
        for (; y < r.max().y(); y++) {
            int filled = 0;
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    filled += name(grid, names, x, y, z) != null ? 1 : 0;
                }
            }
            if (filled * 10 < area * 9) {
                break;
            }
        }
        return y - 1;
    }

    /** Shaped tops, all tops, the build's height and speckled tops (ADR 0078): what a roof looks like from above. */
    private static int[] roof(BlockGrid grid, String[] names, boolean[] land, int ground) {
        Region r = grid.region();
        int shaped = 0;
        int tops = 0;
        int height = 0;
        int[] rgb = new int[r.sizeX() * r.sizeZ()];
        java.util.Arrays.fill(rgb, -1);
        for (int z = r.min().z(); z <= r.max().z(); z++) {
            for (int x = r.min().x(); x <= r.max().x(); x++) {
                for (int y = r.max().y(); y > ground; y--) {
                    String b = name(grid, names, x, y, z);
                    if (b != null) {
                        if (b.contains("leaves") || land[r.index(x, y, z)]) {
                            break; // a tree canopy (ADR 0071) or the ground (ADR 0075, 0082) is not a roof
                        }
                        tops++;
                        shaped += b.contains("stairs") || b.contains("slab") ? 1 : 0;
                        height = Math.max(height, y - ground);
                        rgb[(z - r.min().z()) * r.sizeX() + x - r.min().x()] = BlockColors.top(b);
                        break;
                    }
                }
            }
        }
        return new int[] {shaped, tops, height, LandReview.speckled(rgb, r.sizeX(), r.sizeZ())};
    }

    /** How many of a wall face's four neighbours in its plane clash with it in colour; {@code ux, uz} runs along the wall. */
    private static int clashes(BlockGrid grid, String[] names, int[] rgb, int x, int y, int z, int ux, int uz) {
        int self = rgb[grid.blocks()[grid.region().index(x, y, z)]];
        int[][] near = {{x + ux, y, z + uz}, {x - ux, y, z - uz}, {x, y + 1, z}, {x, y - 1, z}};
        int n = 0;
        for (int[] p : near) {
            if (name(grid, names, p[0], p[1], p[2]) != null
                    && distance(self, rgb[grid.blocks()[grid.region().index(p[0], p[1], p[2])]]) > CLASH) {
                n++;
            }
        }
        return n;
    }

    static double distance(int a, int b) {
        int dr = (a >> 16 & 0xff) - (b >> 16 & 0xff);
        int dg = (a >> 8 & 0xff) - (b >> 8 & 0xff);
        int db = (a & 0xff) - (b & 0xff);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static boolean flatPatch(BlockGrid grid, String[] names, String b, int x, int y, int z, int dx, int dz,
            int ground) {
        for (int v = -1; v <= 1; v++) {
            for (int u = -1; u <= 1; u++) {
                int px = x + (dx == 0 ? u : 0);
                int pz = z + (dz == 0 ? u : 0);
                int py = y + v;
                if (py <= ground || !b.equals(name(grid, names, px, py, pz))
                        || name(grid, names, px + dx, py, pz + dz) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether a block is part of the build (not air, fluid or ground). */
    private static boolean built(BlockGrid grid, String[] names, boolean[] land, int x, int y, int z) {
        String n = name(grid, names, x, y, z);
        return n != null && !land[grid.region().index(x, y, z)];
    }

    /**
     * Ground by position: soil, and rock, terracotta or ore in an unbroken column up from the region's bottom, so a
     * generated world's hills, cliffs and mesas are not judged as walls with a bare base (ADR 0084). Rock standing on
     * soil (a cobblestone wall on grass) stays part of the build.
     * shortcut: rock above an underground dirt or gravel pocket counts as built; tell them apart by edit history if
     * that shows up in reviews.
     */
    static boolean[] ground(BlockGrid grid, String[] names, boolean[] soil) {
        Region r = grid.region();
        boolean[] out = new boolean[grid.blocks().length];
        for (int z = r.min().z(); z <= r.max().z(); z++) {
            for (int x = r.min().x(); x <= r.max().x(); x++) {
                boolean natural = true;
                for (int y = r.min().y(); y <= r.max().y(); y++) {
                    int i = r.index(x, y, z);
                    int b = grid.blocks()[i];
                    String n = b < 0 ? null : names[b];
                    boolean isSoil = n != null && soil[b];
                    natural &= n != null && !isSoil && (LandReview.land(n) || n.endsWith("_ore"));
                    out[i] = isSoil || natural;
                }
            }
        }
        return out;
    }

    /** Columns in a region above which a review also judges each building on its own (a town, not a house). */
    static final int TOWN_AREA = 48 * 48;

    /**
     * The buildings in a region: groups of touching columns (diagonals too) with at least 3 built blocks, 16 columns or
     * more, each as its box one block wider, within the region. Trees and paths are too thin or low to count.
     */
    static List<Region> buildings(BlockGrid grid, String[] names, boolean[] isGround) {
        Region r = grid.region();
        int sx = r.sizeX();
        int sz = r.sizeZ();
        boolean[] tall = new boolean[sx * sz];
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int n = 0;
                for (int y = r.min().y(); y <= r.max().y(); y++) {
                    int i = r.index(r.min().x() + x, y, r.min().z() + z);
                    int b = grid.blocks()[i];
                    n += b >= 0 && names[b] != null && !isGround[i] && !matches(names[b], PLANTS)
                            && !LandReview.land(names[b]) ? 1 : 0; // shaped hills are not buildings
                }
                tall[z * sx + x] = n >= 3;
            }
        }
        List<Region> out = new ArrayList<>();
        boolean[] seen = new boolean[tall.length];
        for (int start = 0; start < tall.length; start++) {
            if (!tall[start] || seen[start]) {
                continue;
            }
            java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>(List.of(start));
            seen[start] = true;
            int count = 0;
            int x0 = sx;
            int z0 = sz;
            int x1 = -1;
            int z1 = -1;
            while (!todo.isEmpty()) {
                int c = todo.pop();
                int cx = c % sx;
                int cz = c / sx;
                count++;
                x0 = Math.min(x0, cx);
                x1 = Math.max(x1, cx);
                z0 = Math.min(z0, cz);
                z1 = Math.max(z1, cz);
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx;
                        int nz = cz + dz;
                        if (nx >= 0 && nz >= 0 && nx < sx && nz < sz && tall[nz * sx + nx] && !seen[nz * sx + nx]) {
                            seen[nz * sx + nx] = true;
                            todo.push(nz * sx + nx);
                        }
                    }
                }
            }
            if (count >= 16 && out.size() < 40) {
                out.add(Region.of(r.world(), r.min().x() + Math.max(0, x0 - 1), r.min().y(),
                        r.min().z() + Math.max(0, z0 - 1), r.min().x() + Math.min(sx - 1, x1 + 1), r.max().y(),
                        r.min().z() + Math.min(sz - 1, z1 + 1)));
            }
        }
        return out;
    }

    /** The part of {@code grid} inside {@code part}. */
    private static BlockGrid sub(BlockGrid grid, Region part) {
        Region r = grid.region();
        int[] blocks = new int[Math.toIntExact(part.volume())];
        for (int y = part.min().y(); y <= part.max().y(); y++) {
            for (int z = part.min().z(); z <= part.max().z(); z++) {
                for (int x = part.min().x(); x <= part.max().x(); x++) {
                    blocks[part.index(x, y, z)] = grid.blocks()[r.index(x, y, z)];
                }
            }
        }
        return new BlockGrid(part, grid.palette(), blocks);
    }

    /** Floor inside farther than this from any light is dark. */
    static final int LIGHT_REACH = 6;
    static final int MAX_DARK = 25;

    /** Whether a light block lies within {@link #LIGHT_REACH} blocks (a cube) of a spot. */
    private static boolean lit(BlockGrid grid, boolean[] light, int x, int y, int z) {
        Region r = grid.region();
        for (int dy = -LIGHT_REACH; dy <= LIGHT_REACH; dy++) {
            for (int dz = -LIGHT_REACH; dz <= LIGHT_REACH; dz++) {
                for (int dx = -LIGHT_REACH; dx <= LIGHT_REACH; dx++) {
                    if (r.contains(x + dx, y + dy, z + dz)) {
                        int b = grid.blocks()[r.index(x + dx, y + dy, z + dz)];
                        if (b >= 0 && light[b]) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Whether built blocks stand within 8 blocks on at least three of the four sides: inside a room, not a porch. */
    private static boolean enclosed(BlockGrid grid, String[] names, int x, int y, int z) {
        int walls = 0;
        for (int[] s : SIDES) {
            for (int k = 1; k <= 8; k++) {
                if (name(grid, names, x + s[0] * k, y, z + s[1] * k) != null) {
                    walls++;
                    break;
                }
            }
        }
        return walls >= 3;
    }

    /** Whether something lies above within 8 blocks: a room's ceiling or a roof. */
    private static boolean covered(BlockGrid grid, String[] names, int x, int y, int z) {
        for (int up = y + 1; up <= Math.min(grid.region().max().y(), y + 8); up++) {
            if (name(grid, names, x, up, z) != null) {
                return true;
            }
        }
        return false;
    }

    private static String name(BlockGrid grid, String[] names, int x, int y, int z) {
        if (!grid.region().contains(x, y, z)) {
            return null;
        }
        int i = grid.blocks()[grid.region().index(x, y, z)];
        return i < 0 ? null : names[i];
    }

    /** Plants that cover ground: not leaves (canopies, hedges) or mossy stone (walls), which are built. */
    static boolean groundCover(String name) {
        return matches(name, PLANTS) && !name.contains("leaves") && !name.contains("mossy") && !name.endsWith("_block");
    }

    private static boolean matches(String name, List<String> parts) {
        for (String p : parts) {
            if (name.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static int pct(long part, long whole) {
        return (int) Math.round(100.0 * part / whole);
    }
}
