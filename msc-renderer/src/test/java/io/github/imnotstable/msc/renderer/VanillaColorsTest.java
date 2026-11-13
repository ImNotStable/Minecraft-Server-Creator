package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The colour table keeps up with the game: every block of the catalog's Minecraft version gets its colour from
 * block-colors.txt or a naming rule. Contains-word lines are only a fallback for plugin and mod blocks, because
 * on a vanilla family they can be silently wrong (poplar_planks as oak, red_poplar_leaves as green).
 */
class VanillaColorsTest {
    private static final int[] KINDS = {BlockColors.TOP, BlockColors.SIDE, BlockColors.BOTTOM};

    @Test
    void everyVanillaBlockHasATableOrRuleColour() throws IOException {
        String version = System.getProperty("msc.minecraft");
        assertThat(version).as("msc.minecraft is set from the version catalog").isNotBlank();
        List<String> names = blocks("blocks-" + version + ".txt");
        assertThat(names).as("block list for Minecraft " + version).hasSizeGreaterThan(1000);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (Looks.INVISIBLE.contains(name)) {
                continue;
            }
            for (int kind : KINDS) {
                BlockColors.Found found = BlockColors.find(name, kind);
                if (found.source() != BlockColors.Source.TABLE) {
                    missing.add(name + "(" + found.source() + ")");
                    break;
                }
            }
        }
        assertThat(missing).as("blocks coloured only by a word line or the name hash").isEmpty();
    }

    /** Each block looks like the one it is made of, or the one it is a form of. */
    @ParameterizedTest
    @CsvSource({
            "poplar_stairs, poplar_planks", "poplar_slab, poplar_planks", "poplar_fence_gate, poplar_planks",
            "poplar_door, poplar_planks", "poplar_shelf, poplar_planks", "stripped_poplar_log, poplar_planks",
            "poplar_wood, poplar_log", "oak_shelf, oak_planks", "bamboo_slab, bamboo_planks",
            "bamboo_fence, bamboo_planks", "sulfur_brick_stairs, sulfur_bricks", "polished_sulfur_wall, sulfur",
            "chiseled_cinnabar, cinnabar", "cinnabar_brick_wall, cinnabar_bricks", "wall_torch, torch",
            "soul_wall_torch, soul_torch", "redstone_wall_torch, redstone_torch", "copper_wall_torch, copper_torch",
            "trapped_chest, chest", "creeper_wall_head, creeper_head", "tube_coral_wall_fan, tube_coral",
            "dead_horn_coral_fan, dead_horn_coral", "red_candle_cake, cake",
            "waxed_weathered_copper_bars, weathered_copper", "oxidized_lightning_rod, oxidized_copper",
            "potted_poplar_sapling, poplar_sapling"})
    void formsShareTheColourOfTheirBlock(String block, String base) {
        assertThat(BlockColors.find(block, BlockColors.SIDE).argb())
                .isEqualTo(BlockColors.find(base, BlockColors.SIDE).argb());
    }

    /** Pairs that word lines used to draw alike although the blocks look different. */
    @ParameterizedTest
    @CsvSource({
            "poplar_planks, oak_planks", "poplar_log, oak_log", "orange_poplar_leaves, oak_leaves",
            "red_poplar_leaves, oak_leaves", "yellow_poplar_leaves, oak_leaves", "sulfur_bricks, stone_bricks",
            "cinnabar_bricks, stone_bricks", "redstone_wall_torch, stone", "bamboo_slab, bamboo",
            "fire_coral, brain_coral", "dead_tube_coral, tube_coral", "verdant_froglight, ochre_froglight",
            "white_tulip, red_tulip", "white_bed, red_bed"})
    void differentBlocksAreNotDrawnAlike(String block, String other) {
        assertThat(BlockColors.find(block, BlockColors.SIDE).argb())
                .isNotEqualTo(BlockColors.find(other, BlockColors.SIDE).argb());
    }

    @Test
    void captionHasNoUnknownsForAPoplarHouse() {
        Scenes s = new Scenes(0, 64, 0, 3, 64, 0)
                .set(0, 64, 0, "minecraft:poplar_planks")
                .set(1, 64, 0, "minecraft:poplar_stairs[facing=north]")
                .set(2, 64, 0, "minecraft:orange_poplar_leaves")
                .set(3, 64, 0, "minecraft:wall_torch[facing=north]");
        RenderResult result = new WorldRenderer().render(s.grid(), View.parse("top"),
                new RenderOptions(64, false, false, false, false));
        assertThat(result.caption()).contains("poplar_stairs").doesNotContain("No colour known");
    }

    private static List<String> blocks(String resource) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = VanillaColorsTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " (regenerate it for the catalog's Minecraft version)").isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    names.add(line.strip());
                }
            }
        }
        return names;
    }
}
