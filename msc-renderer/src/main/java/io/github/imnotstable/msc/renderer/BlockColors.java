package io.github.imnotstable.msc.renderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block name to colour, from this module's own table ({@code block-colors.txt}) plus naming rules:
 * shape suffixes (oak_slab is coloured like oak_planks), wall-mounted forms (wall_torch like torch), copper
 * ages, the 16 dye colours, and word fallbacks. Every vanilla block resolves through the table or a rule
 * (VanillaColorsTest); word lines are a fallback for plugin and mod blocks. Colours are ARGB ints. Blocks
 * nobody listed get a stable colour derived from their name.
 */
public final class BlockColors {
    static final int TOP = 0;
    static final int SIDE = 1;
    static final int BOTTOM = 2;

    private static final String[] PREFIXES = {"waxed_", "infested_", "potted_", "polished_", "smooth_", "chiseled_",
            "cut_", "cracked_", "mossy_", "petrified_", "stripped_"};
    private static final String[] SUFFIXES = {"_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_sign",
            "_fence_gate", "_fence", "_pressure_plate", "_button", "_trapdoor", "_door", "_stairs", "_slab", "_wall",
            "_carpet", "_pane", "_fan", "_shelf"};
    /** Tried after a shape suffix is cut off; _planks first, so bamboo_slab is bamboo_planks, not the bamboo plant. */
    private static final String[] ADDS = {"_planks", "", "s", "_block", "_wool"};
    private static final String[] BARK = {"_log", "_wood", "_stem", "_hyphae"};
    /** Copper ages: exposed_copper_bars is coloured like exposed_copper, whatever the shape. */
    private static final String[] AGES = {"exposed_", "weathered_", "oxidized_"};

    private static final String[] DYES = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime",
            "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final int[] DYE_RGB = {0x3aafd9, 0x8e8e86, 0xe9ecec, 0xf07613, 0xbd44b3, 0xf8c627, 0x70b919,
            0xed8dac, 0x3e4447, 0x158991, 0x792aac, 0x35399d, 0x724728, 0x546d1b, 0xa12722, 0x141519};
    /** Stained terracotta, in DYES order: the dye muted by the clay, so it needs its own colours, not a mix. */
    private static final int[] TERRACOTTA_RGB = {0x716d8a, 0x876b62, 0xd2b2a1, 0xa15325, 0x96586d, 0xba8523,
            0x687635, 0xa14e4e, 0x3a2a24, 0x575b5b, 0x764656, 0x4a3c5b, 0x4d3324, 0x4c532a, 0x8f3d2f, 0x251710};

    private static final Map<String, Integer> TABLE = new HashMap<>();
    private static final List<Map.Entry<String, Integer>> WORDS = new ArrayList<>();

    static {
        try (InputStream in = BlockColors.class.getResourceAsStream("block-colors.txt")) {
            if (in == null) {
                throw new IllegalStateException("block-colors.txt is missing from the jar");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                int argb = parseColor(line.substring(eq + 1).trim());
                if (key.startsWith("~")) {
                    WORDS.add(Map.entry(key.substring(1), argb));
                } else {
                    TABLE.put(key, argb);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BlockColors() {
    }

    /** Where a colour came from: the table or a naming rule, a contains-word line, or the name hash. */
    enum Source {
        TABLE, WORD, HASH
    }

    /** A colour and where it came from. Table, rule and word colours count as {@code known}. */
    record Found(int argb, Source source) {
        boolean known() {
            return source != Source.HASH;
        }
    }

    /** The RGB colour of a block's side, as world_render draws it; for checks that judge colour (BuildReview). */
    public static int side(String name) {
        return find(name, SIDE).argb() & 0xffffff;
    }

    static Found find(String name, int faceKind) {
        // "Wood" blocks have bark on every face, unlike logs.
        int kind = name.endsWith("_wood") || name.endsWith("_hyphae") ? SIDE : faceKind;
        List<String> candidates = candidates(name);
        for (String c : candidates) {
            Integer argb = kind == TOP ? TABLE.get(c + "@top") : kind == BOTTOM ? TABLE.get(c + "@bottom") : null;
            if (argb == null) {
                argb = TABLE.get(c);
            }
            if (argb == null) {
                argb = dyed(c);
            }
            if (argb != null) {
                return new Found(argb, Source.TABLE);
            }
        }
        for (Map.Entry<String, Integer> word : WORDS) {
            if (name.contains(word.getKey())) {
                return new Found(word.getValue(), Source.WORD);
            }
        }
        return new Found(hashColor(name), Source.HASH);
    }

    /**
     * Names to try for a block, most specific first: the name itself, then the block it is made of
     * (stone_brick_stairs to stone_bricks, oak_fence to oak_planks, mossy_x to x).
     */
    static List<String> candidates(String name) {
        Set<String> out = new LinkedHashSet<>();
        collect(name, out, 0);
        return List.copyOf(out);
    }

    private static void collect(String name, Set<String> out, int depth) {
        out.add(name);
        // Wall-mounted forms look like the standing one: wall_torch, creeper_wall_head, tube_coral_wall_fan.
        if (depth < 3 && name.startsWith("wall_")) {
            collect(name.substring("wall_".length()), out, depth + 1);
        }
        int wall = name.indexOf("_wall_");
        if (depth < 3 && wall > 0) {
            collect(name.substring(0, wall) + name.substring(wall + "_wall".length()), out, depth + 1);
        }
        if (name.endsWith("candle_cake")) {
            out.add("cake");
        }
        for (String age : AGES) {
            if (name.startsWith(age)) {
                out.add(age + "copper");
            }
        }
        for (String bark : BARK) {
            if (name.endsWith(bark)) {
                String wood = name.substring(0, name.length() - bark.length());
                if (wood.startsWith("stripped_")) {
                    out.add(wood.substring("stripped_".length()) + "_planks");
                } else {
                    out.add(wood + "_log");
                    out.add(wood + "_stem");
                }
            }
        }
        // Bamboo's log form is bamboo_block; stripped, it shows the planks colour like stripped logs do.
        if (name.startsWith("stripped_") && name.endsWith("_block")) {
            out.add(name.substring("stripped_".length(), name.length() - "_block".length()) + "_planks");
        }
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                String base = name.substring(0, name.length() - suffix.length());
                for (String add : ADDS) {
                    out.add(base + add);
                }
                break;
            }
        }
        if (depth < 3) {
            for (String prefix : PREFIXES) {
                if (name.startsWith(prefix)) {
                    collect(name.substring(prefix.length()), out, depth + 1);
                }
            }
        }
    }

    private static Integer dyed(String name) {
        for (int i = 0; i < DYES.length; i++) {
            if (!name.startsWith(DYES[i] + "_")) {
                continue;
            }
            int rgb = DYE_RGB[i];
            return switch (name.substring(DYES[i].length() + 1)) {
                case "wool", "carpet", "bed", "banner", "wall_banner", "candle", "shulker_box", "glazed_terracotta" ->
                        0xff000000 | rgb;
                case "concrete" -> 0xff000000 | mix(rgb, 0x202020, 0.15);
                case "concrete_powder" -> 0xff000000 | mix(rgb, 0xd0d0d0, 0.15);
                case "terracotta" -> 0xff000000 | TERRACOTTA_RGB[i];
                case "stained_glass", "stained_glass_pane" -> 0x70000000 | rgb;
                default -> null;
            };
        }
        return null;
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xff) * (1 - t) + ((b >> 16) & 0xff) * t);
        int g = (int) Math.round(((a >> 8) & 0xff) * (1 - t) + ((b >> 8) & 0xff) * t);
        int bl = (int) Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int parseColor(String hex) {
        long v = Long.parseLong(hex, 16);
        return hex.length() > 6 ? (int) (((v & 0xff) << 24) | (v >>> 8)) : (int) (0xff000000L | v);
    }

    /** A mid-brightness colour that is stable for a name, so unknown blocks stay tellable apart. */
    private static int hashColor(String name) {
        int h = name.hashCode() * 0x9e3779b9;
        int r = 80 + ((h >>> 24) & 0x7f);
        int g = 80 + ((h >>> 16) & 0x7f);
        int b = 80 + ((h >>> 8) & 0x7f);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
