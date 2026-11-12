package io.github.imnotstable.msc.bot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collision boxes of block states, inside the unit cube. An approximation of vanilla's shapes that is good enough
 * to walk and jump: thin and odd shapes (panes, fences, doors) are simplified, unknown blocks are full cubes.
 */
final class BlockShapes {
    private static final List<Box> FULL = List.of(new Box(0, 0, 0, 1, 1, 1));
    private static final List<Box> NONE = List.of();
    private static final Set<String> PASSABLE = Set.of("air", "cave_air", "void_air", "water", "lava", "short_grass",
            "tall_grass", "fern", "large_fern", "dead_bush", "bush", "short_dry_grass", "tall_dry_grass", "torch",
            "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch", "copper_torch",
            "copper_wall_torch", "redstone_wire", "lever", "tripwire", "tripwire_hook", "string", "vine", "ladder",
            "light", "structure_void", "fire", "soul_fire", "cobweb", "sweet_berry_bush", "sugar_cane", "kelp",
            "kelp_plant", "seagrass", "tall_seagrass", "bubble_column", "nether_portal", "end_portal", "wheat",
            "carrots", "potatoes", "beetroots", "nether_wart", "dandelion", "poppy", "blue_orchid", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "torchflower", "sunflower", "lilac", "rose_bush", "peony",
            "brown_mushroom", "red_mushroom", "glow_lichen", "hanging_roots", "spore_blossom",
            "pink_petals", "wildflowers", "leaf_litter", "firefly_bush", "crimson_roots", "warped_roots",
            "nether_sprouts", "weeping_vines", "twisting_vines", "cave_vines", "cave_vines_plant", "big_dripleaf_stem",
            "small_dripleaf", "pitcher_plant", "rail", "powered_rail", "detector_rail", "activator_rail", "sculk_vein",
            "resin_clump", "tripwire_string", "pale_hanging_moss", "open_eyeblossom", "closed_eyeblossom");
    private static final Map<String, Double> HEIGHT = new HashMap<>(Map.of("farmland", 15 / 16.0, "dirt_path",
            15 / 16.0, "soul_sand", 7 / 8.0, "mud", 7 / 8.0, "chest", 7 / 8.0, "trapped_chest", 7 / 8.0,
            "ender_chest", 7 / 8.0, "enchanting_table", 0.75, "stonecutter", 9 / 16.0, "daylight_detector", 6 / 16.0));

    private BlockShapes() {
    }

    /** Boxes for a block state such as {@code minecraft:oak_stairs[facing=north,half=bottom]}. */
    static List<Box> of(String state) {
        String name = name(state);
        Map<String, String> p = properties(state);
        if (PASSABLE.contains(name) || name.endsWith("_sapling") || name.endsWith("_button")
                || name.endsWith("_pressure_plate") || name.endsWith("_sign") || name.endsWith("_banner")
                || name.endsWith("_coral_fan") || name.endsWith("_coral") || name.endsWith("_tulip")
                || name.endsWith("_wall_torch")) {
            return NONE;
        }
        if (name.endsWith("_carpet") || name.equals("moss_carpet")) {
            return List.of(new Box(0, 0, 0, 1, 1 / 16.0, 1));
        }
        if (name.equals("snow")) {
            int layers = Integer.parseInt(p.getOrDefault("layers", "1"));
            return layers <= 1 ? NONE : List.of(new Box(0, 0, 0, 1, (layers - 1) / 8.0, 1));
        }
        if (name.endsWith("_slab")) {
            return switch (p.getOrDefault("type", "bottom")) {
                case "top" -> List.of(new Box(0, 0.5, 0, 1, 1, 1));
                case "double" -> FULL;
                default -> List.of(new Box(0, 0, 0, 1, 0.5, 1));
            };
        }
        if (name.endsWith("_stairs")) {
            boolean top = p.getOrDefault("half", "bottom").equals("top");
            Box base = top ? new Box(0, 0.5, 0, 1, 1, 1) : new Box(0, 0, 0, 1, 0.5, 1);
            double y0 = top ? 0 : 0.5;
            double y1 = top ? 0.5 : 1;
            Box step = switch (p.getOrDefault("facing", "north")) {
                case "south" -> new Box(0, y0, 0.5, 1, y1, 1);
                case "east" -> new Box(0.5, y0, 0, 1, y1, 1);
                case "west" -> new Box(0, y0, 0, 0.5, y1, 1);
                default -> new Box(0, y0, 0, 1, y1, 0.5);
            };
            return List.of(base, step);
        }
        if (name.endsWith("_fence") || name.endsWith("_wall") && !name.endsWith("_wall_sign")) {
            return List.of(new Box(0, 0, 0, 1, 1.5, 1));
        }
        if (name.endsWith("_fence_gate")) {
            return p.getOrDefault("open", "false").equals("true") ? NONE : List.of(new Box(0, 0, 0, 1, 1.5, 1));
        }
        if (name.endsWith("_door")) {
            return p.getOrDefault("open", "false").equals("true") ? NONE : FULL;
        }
        if (name.endsWith("_trapdoor")) {
            if (p.getOrDefault("open", "false").equals("true")) {
                return NONE;
            }
            return p.getOrDefault("half", "bottom").equals("top") ? List.of(new Box(0, 13 / 16.0, 0, 1, 1, 1))
                    : List.of(new Box(0, 0, 0, 1, 3 / 16.0, 1));
        }
        if (name.endsWith("_bed")) {
            return List.of(new Box(0, 0, 0, 1, 9 / 16.0, 1));
        }
        Double h = HEIGHT.get(name);
        return h != null ? List.of(new Box(0, 0, 0, 1, h, 1)) : FULL;
    }

    /** Ground friction (vanilla "friction" of the block): ice is slippery, slime sticky. */
    static double slipperiness(String state) {
        return switch (name(state)) {
            case "ice", "packed_ice", "frosted_ice" -> 0.98;
            case "blue_ice" -> 0.989;
            case "slime_block" -> 0.8;
            default -> 0.6;
        };
    }

    static String name(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    static Map<String, String> properties(String state) {
        Map<String, String> out = new HashMap<>();
        int open = state.indexOf('[');
        if (open < 0 || !state.endsWith("]")) {
            return out;
        }
        for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
