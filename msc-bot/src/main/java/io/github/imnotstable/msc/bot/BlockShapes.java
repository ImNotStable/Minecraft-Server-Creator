package io.github.imnotstable.msc.bot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collision boxes of block states, inside the unit cube. An approximation of vanilla's shapes that is good enough
 * to walk and jump: thin and odd shapes (panes, fences, doors) are simplified, blocks no rule names are full cubes.
 * The only table of which blocks a player passes: the core's route search reads it through {@link Footing}.
 * BlockShapesDriftTest checks every state of every block of the supported Minecraft version against vanilla's shape
 * class (none, low, block, tall), so a new block that a rule here misses fails the build.
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
            "nether_sprouts", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "cave_vines", "cave_vines_plant", "big_dripleaf_stem",
            "small_dripleaf", "pitcher_plant", "rail", "powered_rail", "detector_rail", "activator_rail", "sculk_vein",
            "resin_clump", "tripwire_string", "pale_hanging_moss", "open_eyeblossom", "closed_eyeblossom",
            "cactus_flower", "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "torchflower_crop", "golden_dandelion", "frogspawn", "end_gateway", "moving_piston", "powder_snow",
            "red_shrub");
    /** Mob heads and skulls on the floor: 8/16 wide, half a block high (the wall forms are full cubes here). */
    private static final Set<String> HEADS = Set.of("player_head", "zombie_head", "creeper_head", "dragon_head",
            "piglin_head", "skeleton_skull", "wither_skeleton_skull");
    /** Amethyst growth stages: height out of the face it grows on, and the inset of its sides. */
    private static final Map<String, double[]> AMETHYST = Map.of("amethyst_cluster", new double[] {7, 3},
            "large_amethyst_bud", new double[] {5, 3}, "medium_amethyst_bud", new double[] {4, 3},
            "small_amethyst_bud", new double[] {3, 4});
    /** Blocks whose box covers the whole cell up to a height. */
    private static final Map<String, Double> HEIGHT = Map.ofEntries(Map.entry("farmland", 15 / 16.0),
            Map.entry("dirt_path", 15 / 16.0), Map.entry("soul_sand", 7 / 8.0), Map.entry("mud", 7 / 8.0),
            Map.entry("chest", 7 / 8.0), Map.entry("trapped_chest", 7 / 8.0), Map.entry("ender_chest", 7 / 8.0),
            Map.entry("enchanting_table", 0.75), Map.entry("stonecutter", 9 / 16.0),
            Map.entry("daylight_detector", 6 / 16.0), Map.entry("repeater", 2 / 16.0),
            Map.entry("comparator", 2 / 16.0), Map.entry("campfire", 7 / 16.0), Map.entry("soul_campfire", 7 / 16.0),
            Map.entry("sculk_sensor", 0.5), Map.entry("calibrated_sculk_sensor", 0.5),
            Map.entry("sculk_shrieker", 0.5), Map.entry("straw_bed", 5 / 16.0));
    /** Blocks whose box stands in the middle of the cell: width and height in sixteenths. */
    private static final Map<String, double[]> CENTERED = Map.of("flower_pot", new double[] {6, 6},
            "lily_pad", new double[] {14, 1.5}, "cake", new double[] {14, 8}, "heavy_core", new double[] {8, 8},
            "turtle_egg", new double[] {14, 7});

    private BlockShapes() {
    }

    /** Boxes for a block state such as {@code minecraft:oak_stairs[facing=north,half=bottom]}. */
    static List<Box> of(String state) {
        String name = name(state);
        Map<String, String> p = properties(state);
        List<Box> low = lowAndThin(name, p);
        if (low != null) {
            return low;
        }
        if (PASSABLE.contains(name) || name.endsWith("_sapling") || name.endsWith("_button")
                || name.endsWith("_pressure_plate") || name.endsWith("_sign") || name.endsWith("_banner")
                || name.endsWith("_coral_fan") || name.endsWith("_coral_wall_fan") || name.endsWith("_coral") || name.endsWith("_tulip")
                || name.endsWith("_wall_torch") || name.endsWith("_fungus") || name.endsWith("_propagule")) {
            return NONE;
        }
        if (name.equals("candle") || name.endsWith("_candle")) {
            return List.of(new Box(0, 0, 0, 1, 6 / 16.0, 1));
        }
        if (name.endsWith("_carpet") || name.equals("moss_carpet")) {
            if (p.getOrDefault("bottom", "true").equals("false")) {
                return NONE; // pale moss hanging down the side of a block
            }
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
        if (name.endsWith("_wall") && p.getOrDefault("up", "true").equals("false")
                && List.of("north", "east", "south", "west").stream()
                        .allMatch(side -> p.getOrDefault(side, "none").equals("none"))) {
            return NONE; // a wall state with no post and no side has no shape
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

    /**
     * Small and low blocks whose names other rules would also match (potted saplings, wall hanging signs, heads), or
     * whose shape turns with the state; null for other blocks.
     */
    private static List<Box> lowAndThin(String name, Map<String, String> p) {
        if (name.startsWith("potted_")) {
            return centered(6, 6);
        }
        double[] c = CENTERED.get(name);
        if (c != null) {
            return centered(c[0], c[1]);
        }
        if (HEADS.contains(name)) {
            return centered(8, 8);
        }
        if (name.equals("sea_pickle")) {
            return centered(12, p.getOrDefault("pickles", "1").equals("4") ? 7 : 6);
        }
        if (name.equals("pitcher_crop")) {
            return p.getOrDefault("half", "lower").equals("upper") ? NONE
                    : p.getOrDefault("age", "0").equals("0") ? centered(6, 3) : centered(10, 5);
        }
        if (name.equals("shelf_mushroom")) {
            int age = p.getOrDefault("age", "0").equals("1") ? 1 : 0;
            return List.of(new Box(0, (1 + age) / 16.0, 0, 1, (3 + 2 * age) / 16.0, 1));
        }
        if (name.equals("big_dripleaf")) {
            return switch (p.getOrDefault("tilt", "none")) {
                case "full" -> NONE;
                case "partial" -> List.of(new Box(0, 11 / 16.0, 0, 1, 13 / 16.0, 1));
                default -> List.of(new Box(0, 11 / 16.0, 0, 1, 15 / 16.0, 1));
            };
        }
        if (name.endsWith("_wall_hanging_sign")) {
            boolean alongX = switch (p.getOrDefault("facing", "north")) {
                case "east", "west" -> false;
                default -> true;
            };
            return List.of(alongX ? new Box(0, 14 / 16.0, 6 / 16.0, 1, 1, 10 / 16.0)
                    : new Box(6 / 16.0, 14 / 16.0, 0, 10 / 16.0, 1, 1));
        }
        if (name.equals("chain") || name.endsWith("_chain")) {
            double a = 6.5 / 16;
            double b = 9.5 / 16;
            return switch (p.getOrDefault("axis", "y")) {
                case "x" -> List.of(new Box(0, a, a, 1, b, b));
                case "z" -> List.of(new Box(a, a, 0, b, b, 1));
                default -> List.of(new Box(a, 0, a, b, 1, b));
            };
        }
        if (name.equals("lantern") || name.equals("soul_lantern") || name.endsWith("copper_lantern")) {
            double y = p.getOrDefault("hanging", "false").equals("true") ? 1 / 16.0 : 0;
            return List.of(new Box(5 / 16.0, y, 5 / 16.0, 11 / 16.0, y + 7 / 16.0, 11 / 16.0),
                    new Box(6 / 16.0, y + 7 / 16.0, 6 / 16.0, 10 / 16.0, y + 9 / 16.0, 10 / 16.0));
        }
        double[] growth = AMETHYST.get(name);
        if (growth != null) {
            double h = growth[0] / 16;
            double i = growth[1] / 16;
            double o = 1 - i;
            return List.of(switch (p.getOrDefault("facing", "up")) {
                case "down" -> new Box(i, 1 - h, i, o, 1, o);
                case "north" -> new Box(i, i, 1 - h, o, o, 1);
                case "south" -> new Box(i, i, 0, o, o, h);
                case "east" -> new Box(0, i, i, h, o, o);
                case "west" -> new Box(1 - h, i, i, 1, o, o);
                default -> new Box(i, 0, i, o, h, o);
            });
        }
        return null;
    }

    private static List<Box> centered(double width, double height) {
        double lo = (16 - width) / 32;
        return List.of(new Box(lo, 0, lo, 1 - lo, height / 16, 1 - lo));
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
