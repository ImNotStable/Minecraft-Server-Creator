package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Table colours are plausible for their block, checked over the catalog version's block list: a table line can
 * shadow a naming rule (white_bed was drawn red) and a rule can land on the wrong block (stripped_bamboo_block
 * was drawn as the green bamboo_block). A colour's family is "grey" when it is nearly neutral, else the nearest of
 * the 16 dye colours (nearest-dye alone splits light greys between white and pink).
 */
class ColourFamiliesTest {
    private static final String[] DYES = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime",
            "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    /** Wool-like dye colours, the reference every dyed block is compared with. */
    private static final int[] DYE_RGB = {0x3aafd9, 0x8e8e86, 0xe9ecec, 0xf07613, 0xbd44b3, 0xf8c627, 0x70b919,
            0xed8dac, 0x3e4447, 0x158991, 0x792aac, 0x35399d, 0x724728, 0x546d1b, 0xa12722, 0x141519};
    /** Stained terracotta is the dye muted by clay, so it is compared with the terracotta colours instead. */
    private static final int[] TERRACOTTA_RGB = {0x716d8a, 0x876b62, 0xd2b2a1, 0xa15325, 0x96586d, 0xba8523,
            0x687635, 0xa14e4e, 0x3a2a24, 0x575b5b, 0x764656, 0x4a3c5b, 0x4d3324, 0x4c532a, 0x8f3d2f, 0x251710};
    /**
     * Names that start with a dye word but are not dyed: blue ice, the orchid, mushrooms, red sand and sandstone,
     * red nether bricks. Candle cakes are a cake with a candle on top, coloured like the cake.
     */
    private static final List<String> NOT_DYED = List.of("blue_ice", "blue_orchid", "brown_mushroom", "red_sand",
            "red_nether_brick");
    private static final String[] SAME = {"waxed_", "infested_", "potted_"};
    /** Restyled forms keep their material's family; mossy_ is left out because moss is meant to tint it green. */
    private static final String[] RESTYLED = {"polished_", "smooth_", "chiseled_", "cut_", "cracked_"};
    /** Below this spread between the largest and smallest channel a colour is a grey, whatever dye is nearest. */
    private static final int GREY_CHROMA = 24;
    private static final String[] AGES = {"exposed_", "weathered_", "oxidized_"};
    private static final String[] BARK = {"_log", "_wood", "_stem", "_hyphae", "_block"};
    private static final String[] SHAPES = {"_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_sign",
            "_fence_gate", "_fence", "_pressure_plate", "_button", "_trapdoor", "_door", "_stairs", "_slab", "_wall",
            "_carpet", "_pane", "_shelf"};

    private static List<String> names;
    private static Set<String> known;

    @BeforeAll
    static void load() throws IOException {
        names = VanillaColorsTest.blocks("blocks-" + System.getProperty("msc.minecraft") + ".txt");
        known = new HashSet<>(names);
    }

    @Test
    void dyedBlocksAreNearestTheirOwnDye() {
        List<String> wrong = new ArrayList<>();
        for (String name : names) {
            int dye = dyeOf(name);
            if (dye < 0 || name.endsWith("candle_cake") || NOT_DYED.stream().anyMatch(name::startsWith)) {
                continue;
            }
            boolean terracotta = name.endsWith("_terracotta") && !name.endsWith("glazed_terracotta");
            int[] palette = terracotta ? TERRACOTTA_RGB : DYE_RGB;
            int nearest = nearest(colour(name), palette);
            if (nearest != dye) {
                wrong.add(name + " looks " + DYES[nearest]);
            }
        }
        assertThat(wrong).as("dyed blocks nearer another dye colour").isEmpty();
    }

    @Test
    void variantsKeepTheColourFamilyOfTheirBase() {
        List<String> wrong = new ArrayList<>();
        for (String name : names) {
            for (String prefix : SAME) {
                if (name.startsWith(prefix)) {
                    String base = name.substring(prefix.length());
                    if (known.contains(base) && colour(name) != colour(base)) {
                        wrong.add(name + " != " + base);
                    }
                }
            }
            if (name.startsWith("stripped_")) {
                for (String bark : BARK) {
                    if (name.endsWith(bark)) {
                        String planks = name.substring("stripped_".length(), name.length() - bark.length())
                                + "_planks";
                        if (known.contains(planks) && colour(name) != colour(planks)) {
                            wrong.add(name + " != " + planks);
                        }
                    }
                }
            }
            for (String prefix : RESTYLED) {
                if (name.startsWith(prefix)) {
                    sameFamily(name, name.substring(prefix.length()), wrong);
                }
            }
            for (String age : AGES) {
                if (name.startsWith(age)) {
                    sameFamily(name, age + "copper", wrong);
                }
            }
            for (String shape : SHAPES) {
                if (name.endsWith(shape)) {
                    String stem = name.substring(0, name.length() - shape.length());
                    for (String add : new String[] {"_planks", "s", "_block", ""}) {
                        if (known.contains(stem + add)) {
                            sameFamily(name, stem + add, wrong);
                            break;
                        }
                    }
                    break;
                }
            }
        }
        assertThat(wrong).as("variants coloured unlike their base").isEmpty();
    }

    private static void sameFamily(String name, String base, List<String> wrong) {
        if (!known.contains(base)) {
            return;
        }
        String a = family(colour(name));
        String b = family(colour(base));
        if (!a.equals(b)) {
            wrong.add(name + " looks " + a + ", " + base + " looks " + b);
        }
    }

    private static String family(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        return chroma < GREY_CHROMA ? "grey" : DYES[nearest(rgb, DYE_RGB)];
    }

    private static int dyeOf(String name) {
        for (int i = 0; i < DYES.length; i++) {
            if (name.startsWith(DYES[i] + "_")) {
                return i;
            }
        }
        return -1;
    }

    private static int colour(String name) {
        return BlockColors.find(name, BlockColors.SIDE).argb() & 0xffffff;
    }

    private static int nearest(int rgb, int[] palette) {
        int best = 0;
        for (int i = 1; i < palette.length; i++) {
            if (distance(rgb, palette[i]) < distance(rgb, palette[best])) {
                best = i;
            }
        }
        return best;
    }

    private static int distance(int a, int b) {
        int dr = ((a >> 16) & 0xff) - ((b >> 16) & 0xff);
        int dg = ((a >> 8) & 0xff) - ((b >> 8) & 0xff);
        int db = (a & 0xff) - (b & 0xff);
        return dr * dr + dg * dg + db * db;
    }
}
