package io.github.imnotstable.msc.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Approximate block shapes as a few boxes. This is not the vanilla model system: it covers the
 * shapes that change how a build reads (slabs, stairs, fences, walls, panes, doors, thin and small
 * blocks) and treats everything else as a full cube.
 */
final class Shapes {
    static final Box[] FULL = {Box.FULL};
    private static final Box[] FLAT = {Box.px(0, 0, 0, 16, 1, 16)};
    /** Two thin crossed panels, as the game draws plants: a solid box read as a coloured cube in renders (ADR 0077). */
    private static final Box[] PLANT = {Box.px(1, 0, 7, 15, 13, 9), Box.px(7, 0, 1, 9, 13, 15)};
    /** Flowers, saplings and mushrooms: a smaller cross. */
    private static final Box[] SMALL_PLANT = {Box.px(4, 0, 7.5, 12, 10, 8.5), Box.px(7.5, 0, 4, 8.5, 10, 12)};
    private static final Set<String> SMALL = Set.of("dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "open_eyeblossom",
            "closed_eyeblossom", "brown_mushroom", "red_mushroom", "nether_sprouts", "dead_bush");
    private static final Box[] POST = {Box.px(7, 0, 7, 9, 10, 9)};

    private static final Set<String> FLAT_BLOCKS = Set.of("rail", "powered_rail", "detector_rail", "activator_rail",
            "redstone_wire", "lily_pad", "repeater", "comparator", "tripwire", "glow_lichen", "sculk_vein",
            "pink_petals", "wildflowers", "leaf_litter", "frogspawn");
    private static final Set<String> PLANTS = Set.of("short_grass", "grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "torchflower", "sunflower", "lilac", "rose_bush", "peony",
            "pitcher_plant", "sugar_cane", "wheat", "carrots", "potatoes", "beetroots", "sweet_berry_bush",
            "nether_wart", "nether_sprouts", "brown_mushroom", "red_mushroom", "kelp", "kelp_plant", "seagrass",
            "tall_seagrass", "cobweb", "bamboo", "cactus_flower", "firefly_bush", "bush", "short_dry_grass",
            "tall_dry_grass", "hanging_roots", "spore_blossom", "fire", "soul_fire", "open_eyeblossom",
            "closed_eyeblossom", "twisting_vines", "weeping_vines", "twisting_vines_plant", "weeping_vines_plant",
            "cave_vines", "cave_vines_plant", "big_dripleaf", "small_dripleaf", "melon_stem", "pumpkin_stem");

    private Shapes() {
    }

    static Box[] of(BlockState s) {
        String n = s.name();
        if (n.endsWith("_slab")) {
            return switch (s.prop("type", "bottom")) {
                case "top" -> one(Box.px(0, 8, 0, 16, 16, 16));
                case "double" -> FULL;
                default -> one(Box.px(0, 0, 0, 16, 8, 16));
            };
        }
        if (n.endsWith("_stairs")) {
            return stairs(s);
        }
        if (n.endsWith("_fence_gate")) {
            return fenceGate(s);
        }
        if (n.endsWith("_fence")) {
            return arms(s, Box.px(6, 0, 6, 10, 16, 10), 7, 9, 6, 15);
        }
        if (n.endsWith("_wall")) {
            return arms(s, Box.px(4, 0, 4, 12, 16, 12), 5, 11, 0, 14);
        }
        if (n.endsWith("_pane") || n.endsWith("_bars")) {
            return arms(s, Box.px(7, 0, 7, 9, 16, 9), 7, 9, 0, 16);
        }
        if (n.endsWith("_trapdoor")) {
            if (s.is("open", "true")) {
                return one(against(opposite(s.prop("facing", "north")), 3));
            }
            return one(s.is("half", "top") ? Box.px(0, 13, 0, 16, 16, 16) : Box.px(0, 0, 0, 16, 3, 16));
        }
        if (n.endsWith("_door")) {
            return one(against(doorSide(s), 3));
        }
        if (n.endsWith("_carpet") || FLAT_BLOCKS.contains(n)) {
            return FLAT;
        }
        if (n.endsWith("_pressure_plate")) {
            return one(Box.px(1, 0, 1, 15, 1, 15));
        }
        if (n.endsWith("_button")) {
            return one(Box.px(5, 0, 6, 11, 2, 10));
        }
        if (n.equals("snow")) {
            int layers = Math.clamp(parseInt(s.prop("layers", "1")), 1, 8);
            return layers == 8 ? FULL : one(Box.px(0, 0, 0, 16, layers * 2, 16));
        }
        if (n.equals("ladder") || n.equals("vine") || n.endsWith("_wall_sign") || n.endsWith("_wall_banner")) {
            return one(against(opposite(s.prop("facing", "north")), 1));
        }
        if (n.endsWith("_sign")) {
            return new Box[] {Box.px(7, 0, 7, 9, 9, 9), Box.px(0, 9, 7, 16, 16, 9)};
        }
        if (n.endsWith("torch") || n.endsWith("_candle") || n.equals("candle")) {
            return POST;
        }
        if (n.equals("end_rod") || n.equals("lightning_rod") || n.endsWith("chain")) {
            return one(Box.px(6, 0, 6, 10, 16, 10));
        }
        if (n.endsWith("lantern") && !n.equals("sea_lantern") && !n.equals("jack_o_lantern")) {
            return one(s.is("hanging", "true") ? Box.px(5, 1, 5, 11, 10, 11) : Box.px(5, 0, 5, 11, 9, 11));
        }
        if (n.endsWith("_bed")) {
            return one(Box.px(0, 0, 0, 16, 9, 16));
        }
        if (n.equals("farmland") || n.equals("dirt_path")) {
            return one(Box.px(0, 0, 0, 16, 15, 16));
        }
        if (n.endsWith("chest")) {
            return one(Box.px(1, 0, 1, 15, 14, 15));
        }
        if (n.equals("enchanting_table")) {
            return one(Box.px(0, 0, 0, 16, 12, 16));
        }
        if (n.equals("daylight_detector")) {
            return one(Box.px(0, 0, 0, 16, 6, 16));
        }
        if (n.endsWith("campfire") || n.equals("stonecutter") || n.equals("cake")) {
            return one(Box.px(1, 0, 1, 15, 8, 15));
        }
        if (n.endsWith("_head") || n.endsWith("_skull") || n.equals("flower_pot") || n.startsWith("potted_")) {
            return one(Box.px(4, 0, 4, 12, 8, 12));
        }
        if (n.equals("cactus")) {
            return one(Box.px(1, 0, 1, 15, 16, 15));
        }
        if (PLANTS.contains(n) || n.endsWith("_sapling") || n.endsWith("_tulip") || n.endsWith("_fungus")
                || n.equals("crimson_roots") || n.equals("warped_roots") || n.endsWith("_coral") || n.endsWith("_coral_fan") || n.endsWith("_propagule")) {
            return SMALL.contains(n) || n.endsWith("_tulip") || n.endsWith("_sapling") || n.endsWith("_fungus") ? SMALL_PLANT
                    : PLANT;
        }
        return FULL;
    }

    private static Box[] one(Box box) {
        return new Box[] {box};
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Lower (or upper) half slab plus a half-height step on the side the stairs climb towards. */
    private static Box[] stairs(BlockState s) {
        boolean top = s.is("half", "top");
        Box base = top ? Box.px(0, 8, 0, 16, 16, 16) : Box.px(0, 0, 0, 16, 8, 16);
        double y0 = top ? 0 : 8;
        double y1 = y0 + 8;
        Box step = switch (s.prop("facing", "north")) {
            case "south" -> Box.px(0, y0, 8, 16, y1, 16);
            case "east" -> Box.px(8, y0, 0, 16, y1, 16);
            case "west" -> Box.px(0, y0, 0, 8, y1, 16);
            default -> Box.px(0, y0, 0, 16, y1, 8);
        };
        return new Box[] {base, step};
    }

    private static Box[] fenceGate(BlockState s) {
        boolean alongX = switch (s.prop("facing", "north")) {
            case "north", "south" -> true;
            default -> false;
        };
        if (s.is("open", "true")) {
            return alongX
                    ? new Box[] {Box.px(0, 5, 7, 2, 16, 9), Box.px(14, 5, 7, 16, 16, 9)}
                    : new Box[] {Box.px(7, 5, 0, 9, 16, 2), Box.px(7, 5, 14, 9, 16, 16)};
        }
        return one(alongX ? Box.px(0, 5, 7, 16, 16, 9) : Box.px(7, 5, 0, 9, 16, 16));
    }

    /** A centre post plus one arm per connected side (fences, walls, panes, bars). */
    private static Box[] arms(BlockState s, Box post, double lo, double hi, double y0, double y1) {
        List<Box> boxes = new ArrayList<>(5);
        if (!s.is("up", "false")) {
            boxes.add(post);
        }
        if (connected(s, "north")) {
            boxes.add(Box.px(lo, y0, 0, hi, y1, 8));
        }
        if (connected(s, "south")) {
            boxes.add(Box.px(lo, y0, 8, hi, y1, 16));
        }
        if (connected(s, "west")) {
            boxes.add(Box.px(0, y0, lo, 8, y1, hi));
        }
        if (connected(s, "east")) {
            boxes.add(Box.px(8, y0, lo, 16, y1, hi));
        }
        if (boxes.isEmpty()) {
            boxes.add(post);
        }
        return boxes.toArray(Box[]::new);
    }

    private static boolean connected(BlockState s, String side) {
        String v = s.prop(side, "false");
        return !v.equals("false") && !v.equals("none");
    }

    private static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            default -> "up";
        };
    }

    /** The side of the block a door panel sits on, following vanilla's facing/open/hinge rules. */
    private static String doorSide(BlockState s) {
        boolean open = s.is("open", "true");
        boolean right = s.is("hinge", "right");
        return switch (s.prop("facing", "north")) {
            case "east" -> open ? (right ? "south" : "north") : "west";
            case "south" -> open ? (right ? "west" : "east") : "north";
            case "west" -> open ? (right ? "north" : "south") : "east";
            default -> open ? (right ? "east" : "west") : "south";
        };
    }

    /** A panel of the given thickness (sixteenths) lying against one side of the block. */
    private static Box against(String side, double thick) {
        return switch (side) {
            case "north" -> Box.px(0, 0, 0, 16, 16, thick);
            case "south" -> Box.px(0, 0, 16 - thick, 16, 16, 16);
            case "west" -> Box.px(0, 0, 0, thick, 16, 16);
            case "east" -> Box.px(16 - thick, 0, 0, 16, 16, 16);
            case "up" -> Box.px(0, 16 - thick, 0, 16, 16, 16);
            default -> Box.px(0, 0, 0, 16, thick, 16);
        };
    }
}
