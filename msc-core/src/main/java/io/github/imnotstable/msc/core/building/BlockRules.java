package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The block data a builder decides by how they place a block (ADR 0077): which way it faces, its half, its axis, whether
 * it is open, hangs or stands in water. A block written straight into a build must say these, so the agent decides them
 * instead of getting the game's default (every stair facing north, every log upright). Data the game derives from the
 * neighbours (fence and wall connections, stair corners, a door's top half) is not asked for: {@link Joins} adds it.
 * Only {@code minecraft:} blocks are known here; other namespaces pass.
 */
final class BlockRules {
    private static final String FOUR = "north|east|south|west";
    private static final String SIX = "north|east|south|west|up|down";
    private static final Set<String> AXIS = Set.of("basalt", "polished_basalt", "hay_block", "bone_block",
            "quartz_pillar", "purpur_pillar", "deepslate", "infested_deepslate", "muddy_mangrove_roots", "bamboo_block",
            "stripped_bamboo_block", "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "creaking_heart",
            "chain", "iron_chain", "copper_chain", "exposed_copper_chain", "weathered_copper_chain", "oxidized_copper_chain",
            "waxed_copper_chain", "waxed_exposed_copper_chain", "waxed_weathered_copper_chain",
            "waxed_oxidized_copper_chain");
    private static final Set<String> FACING_FOUR = Set.of("ladder", "chest", "trapped_chest", "ender_chest", "furnace",
            "smoker", "blast_furnace", "lectern", "loom", "stonecutter", "beehive", "bee_nest", "carved_pumpkin",
            "jack_o_lantern", "anvil", "chipped_anvil", "damaged_anvil", "campfire", "soul_campfire", "repeater",
            "comparator", "cocoa", "chiseled_bookshelf", "decorated_pot", "vault", "calibrated_sculk_sensor",
            "end_portal_frame", "small_dripleaf", "big_dripleaf", "pink_petals", "wildflowers", "leaf_litter",
            "copper_golem_statue");
    private static final Set<String> FACING_SIX = Set.of("barrel", "dispenser", "dropper", "observer", "piston",
            "sticky_piston", "end_rod", "lightning_rod", "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud",
            "small_amethyst_bud", "shulker_box", "command_block", "chain_command_block", "repeating_command_block",
            "hopper");
    private static final Set<String> LANTERNS = Set.of("lantern", "soul_lantern", "copper_lantern", "exposed_copper_lantern",
            "weathered_copper_lantern", "oxidized_copper_lantern", "waxed_copper_lantern", "waxed_exposed_copper_lantern",
            "waxed_weathered_copper_lantern", "waxed_oxidized_copper_lantern");
    /** Name parts of blocks that can hold water, so standing in or beside water they must say {@code waterlogged=}. */
    private static final List<String> WATERLOGGABLE = List.of("_stairs", "_slab", "_fence", "pane", "_bars", "_wall",
            "trapdoor", "ladder", "lantern", "chain", "_sign", "chest", "campfire", "lightning_rod", "candle", "sea_pickle",
            "conduit", "scaffolding", "dripleaf", "amethyst", "pointed_dripstone", "coral", "hanging_roots", "glow_lichen",
            "sculk_vein", "copper_grate", "heavy_core", "decorated_pot", "mangrove_roots", "mangrove_propagule", "_shelf",
            "copper_golem_statue");

    /** How a refusal for unsaid placement starts, so a batch gathers every line's into one answer. */
    static final String UNSAID = "say how each block is placed (the game would default it): ";
    static final String EXAMPLE = ", such as oak_stairs[facing=north,half=bottom]; a world_slice for pasting back keeps "
            + "them with states=true";

    private BlockRules() {
    }

    /** What a block placed as given leaves for the game to default, as {@code key=choices}; empty when nothing. */
    static List<String> missing(String state) {
        String full = BlockNames.full(state);
        if (!BlockNames.id(full).startsWith("minecraft:")) {
            return List.of();
        }
        String id = Joins.id(full);
        Map<String, String> needs = new LinkedHashMap<>();
        if (id.endsWith("_stairs")) {
            needs.put("facing", FOUR);
            needs.put("half", "bottom|top");
        } else if (id.endsWith("_slab")) {
            needs.put("type", "bottom|top|double");
        } else if (id.endsWith("trapdoor")) {
            needs.put("facing", FOUR);
            needs.put("half", "bottom|top");
            needs.put("open", "true|false");
        } else if (Joins.door(id)) {
            needs.put("facing", FOUR);
            needs.put("hinge", "left|right");
            needs.put("open", "true|false");
        } else if (id.endsWith("_fence_gate")) {
            needs.put("facing", FOUR);
            needs.put("open", "true|false");
        } else if (id.endsWith("_button") || id.equals("lever") || id.equals("grindstone")) {
            needs.put("face", "floor|wall|ceiling");
            needs.put("facing", FOUR);
        } else if (id.endsWith("_bed")) {
            needs.put("facing", FOUR);
        } else if (axis(id)) {
            needs.put("axis", "x|y|z");
        } else if (id.endsWith("_wall_torch") || id.equals("wall_torch") || id.endsWith("_wall_sign")
                || id.endsWith("_wall_hanging_sign") || id.endsWith("_wall_banner") || id.endsWith("_wall_head")
                || id.endsWith("_wall_skull") || id.endsWith("_glazed_terracotta") || id.endsWith("_coral_wall_fan")
                || id.endsWith("_shelf") || FACING_FOUR.contains(id)) {
            needs.put("facing", FOUR);
        } else if (id.endsWith("shulker_box") || id.endsWith("_lightning_rod") || FACING_SIX.contains(id)) {
            needs.put("facing", id.equals("hopper") ? "down|north|east|south|west" : SIX);
        } else if (id.endsWith("_hanging_sign")) {
            needs.put("rotation", "0-15");
            needs.put("attached", "true|false");
        } else if (id.endsWith("_sign") || id.endsWith("_banner") || id.endsWith("_head") && !id.equals("piston_head")
                || id.endsWith("_skull")) {
            needs.put("rotation", "0-15");
        } else if (LANTERNS.contains(id)) {
            needs.put("hanging", "true|false");
        } else if (id.equals("bell")) {
            needs.put("attachment", "floor|ceiling|single_wall|double_wall");
            needs.put("facing", FOUR);
        } else if (id.equals("pointed_dripstone")) {
            needs.put("vertical_direction", "up|down");
        }
        List<String> out = new ArrayList<>();
        needs.forEach((k, v) -> {
            if (!Joins.has(full, k)) {
                out.add(k + "=" + v);
            }
        });
        return out;
    }

    private static boolean axis(String id) {
        return (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae")
                || AXIS.contains(id)) && !id.equals("petrified_oak_slab") && !id.equals("mushroom_stem");
    }

    static boolean waterloggable(String id) {
        for (String part : WATERLOGGABLE) {
            if (id.contains(part)) {
                return !id.endsWith("_wall_fan") || id.contains("coral");
            }
        }
        return false;
    }

    /** Refuse blocks that leave a placement choice unsaid, naming each once with what to add. */
    static void require(Iterable<String> states) {
        Map<String, List<String>> bad = new LinkedHashMap<>();
        for (String s : states) {
            List<String> m = missing(s);
            if (!m.isEmpty()) {
                bad.putIfAbsent(BlockNames.brief(BlockNames.withoutData(s)), m);
            }
        }
        if (bad.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder(UNSAID);
        int i = 0;
        for (var e : bad.entrySet()) {
            out.append(i++ == 0 ? "" : "; ").append(e.getKey()).append(" needs ").append(String.join(" ", e.getValue()));
        }
        throw new ToolException(out.toString());
    }

    /**
     * Refuse waterloggable blocks that stand in or beside water without {@code waterlogged=}: whether they hold water is
     * the builder's choice there.
     */
    static void requireWater(Canvas c) throws InterruptedException {
        List<int[]> todo = c.where(s -> waterloggable(Joins.id(s)) && !Joins.has(s, "waterlogged")
                && BlockNames.id(BlockNames.full(s)).startsWith("minecraft:"));
        if (todo.isEmpty()) {
            return;
        }
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : todo) {
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p[k] - 1);
                hi[k] = Math.max(hi[k], p[k] + 1);
            }
        }
        Region box = Region.of(c.bounds().world(), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        Canvas.Lookup now;
        Canvas.Lookup before;
        try {
            now = c.current(box);
            before = c.before(box);
        } catch (ToolException e) {
            return; // a dry run with no server: the world's water is unknown
        }
        Set<String> names = new LinkedHashSet<>();
        String first = null;
        int count = 0;
        for (int[] p : todo) {
            boolean wet = water(before.at(p[0], p[1], p[2]));
            for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
                wet |= water(now.at(p[0] + d[0], p[1] + d[1], p[2] + d[2]));
            }
            if (wet) {
                count++;
                names.add(Joins.id(c.planned(p[0], p[1], p[2])));
                first = first == null ? p[0] + "," + p[1] + "," + p[2] : first;
            }
        }
        if (count > 0) {
            throw new ToolException("nothing placed: " + count + " block" + (count == 1 ? "" : "s") + " in or beside water "
                    + "(first at " + first + ": " + String.join(", ", names) + ") must say waterlogged=true|false");
        }
    }

    private static boolean water(String state) {
        String id = Joins.id(state);
        return id.equals("water") || id.equals("bubble_column") || Joins.get(state, "waterlogged", "false").equals("true");
    }
}
