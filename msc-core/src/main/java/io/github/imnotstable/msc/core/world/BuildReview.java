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
        if (land.landShare() >= LAND_ONLY) {
            return withLand("review: mostly land (" + land.landShare() + "% of the blocks), so only the land is "
                    + "judged; review each build on its own region", land, List.of(), false);
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
        for (int y = ground + 1; y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String b = name(grid, names, x, y, z);
                    if (b == null && r.contains(x, y - 1, z) && built(grid, names, soil, x, y - 1, z)
                            && covered(grid, names, x, y, z)) {
                        floor++;
                    }
                    if (b == null || soil[grid.blocks()[r.index(x, y, z)]]) {
                        continue;
                    }
                    total++;
                    boolean shaped = matches(b, DETAIL);
                    boolean green = matches(b, PLANTS);
                    plants += green ? 1 : 0;
                    // Windows and doors sit in the walls: not furnishings.
                    props += shaped && !green && !b.contains("pane") && !b.contains("bars") && !b.contains("door")
                            && covered(grid, names, x, y, z) ? 1 : 0;
                    boolean open = false;
                    for (int[] s : SIDES) {
                        open |= grid.region().contains(x + s[0], y, z + s[1]) && name(grid, names, x + s[0], y, z + s[1]) == null;
                    }
                    if (open && !green && y < r.max().y() && name(grid, names, x, y + 1, z) == null) {
                        tops++;
                        plainTops += shaped ? 0 : 1;
                    }
                    boolean onGround = y - 1 <= ground || r.contains(x, y - 1, z) && !built(grid, names, soil, x, y - 1, z)
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
                    + " and below)" : ""), land, List.of(), false);
        }
        int[] roof = roof(grid, names, ground);
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
        out.append(": ").append(total).append(" blocks, ").append(counts.size()).append(" types: ");
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
        List<String> fixes = new ArrayList<>();
        if (flatPct > MAX_FLAT && faces >= 20) {
            fixes.add("flat walls: give each wall depth (pillars or a frame 1 out, windows 1 in), a base and a trim "
                    + "line of stairs or slabs (trim at=base, band, top; sills, shutters, frames), and mix in variants "
                    + "of the main block (weather, a gradient a>b>c)");
        }
        if (speckledPct > MAX_SPECKLED && faces >= 50) {
            fixes.add("speckled walls: a random mix of clashing colours reads as noise; mix only near tones "
                    + "(stone_bricks:70,cracked_stone_bricks:20,mossy_stone_bricks:10) and put contrast in lines: "
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
            fixes.add("only " + counts.size() + " block types: add 2-3 variants of the main block (cracked, mossy, "
                    + "polished) and one accent");
        } else if (topShare > MAX_TOP_SHARE) {
            fixes.add(top.get(0).getKey() + " is " + topShare + "% of the build: mix variants into it (a block mix "
                    + "such as stone_bricks:70,cracked_stone_bricks:20,mossy_stone_bricks:10)");
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
                    + "and flowers (decorate on=top), and worn ground at the doors");
        }
        if (floor >= 24 && props * FLOOR_PER_PROP < floor) {
            fixes.add("empty rooms: " + floor + " floor blocks under a roof and " + props + " furnishings; furnish "
                    + "them (decorate on=edge, corner, ceiling; tables from slabs and fences, beds, shelves, lights)");
        }
        if (plants == 0 && total >= 300) {
            fixes.add("no plants: vines and leaves on walls (decorate on=wall), bushes and flowers at the base, moss "
                    + "on ledges (weather); leave this where nothing grows");
        }
        if (lights == 0 && total >= 200) {
            fixes.add("no lights: lanterns, torches or glowing blocks at doors, paths and windows");
        }
        return withLand(out.toString().stripTrailing(), land, fixes, true);
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
    private static int[] roof(BlockGrid grid, String[] names, int ground) {
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
                        if (b.contains("leaves") || LandReview.SOIL.contains(b)) {
                            break; // a tree canopy (ADR 0071) or the ground (ADR 0075) is not a roof
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

    /** Whether a block is part of the build (not air, fluid or soil). */
    private static boolean built(BlockGrid grid, String[] names, boolean[] soil, int x, int y, int z) {
        String n = name(grid, names, x, y, z);
        return n != null && !soil[grid.blocks()[grid.region().index(x, y, z)]];
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
