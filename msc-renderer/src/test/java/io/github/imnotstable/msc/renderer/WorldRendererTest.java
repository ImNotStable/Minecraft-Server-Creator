package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldRendererTest {
    /** Flat colours only: no shadows, outlines, rulers or smoothing, so pixels can be checked exactly. */
    private static final RenderOptions PLAIN = new RenderOptions(64, false, false, false, false);
    private final WorldRenderer renderer = new WorldRenderer();

    private static boolean isRed(int rgb) {
        return Scenes.red(rgb) > 90 && Scenes.red(rgb) > 2 * Scenes.green(rgb) && Scenes.red(rgb) > 2 * Scenes.blue(rgb);
    }

    private static boolean isGray(int rgb) {
        return Math.abs(Scenes.red(rgb) - Scenes.green(rgb)) < 12 && Math.abs(Scenes.green(rgb) - Scenes.blue(rgb)) < 12;
    }

    @Test
    void topViewPutsNorthUpAndXToTheRight() {
        // 4x1x4 stone floor at x 10..13, z -2..1 with one red block at x=13, z=-1.
        Scenes s = new Scenes(10, 64, -2, 13, 64, 1).fill(10, 64, -2, 13, 64, 1, "stone").set(13, 64, -1, "red_wool");
        RenderResult result = renderer.render(s.grid(), View.parse("top"), PLAIN);
        BufferedImage image = Scenes.decode(result);

        assertThat(image.getWidth()).isEqualTo(64).isEqualTo(result.width());
        assertThat(image.getHeight()).isEqualTo(64).isEqualTo(result.height());
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int rgb = image.getRGB(col * 16 + 8, row * 16 + 8);
                if (col == 3 && row == 1) {
                    assertThat(isRed(rgb)).as("block at column %d row %d", col, row).isTrue();
                } else {
                    assertThat(isGray(rgb)).as("block at column %d row %d", col, row).isTrue();
                }
            }
        }
        assertThat(result.caption()).contains("Top view", "x 10..13 left to right", "z -2..1 top to bottom", "16 px per block",
                "64x64 px", "stone 94%", "red_wool 6%");
        assertThat(result.estimatedTokens()).isEqualTo(6);
    }

    @Test
    void markersAreDrawnWhereTheEntityStandsAndListedInTheCaption() {
        Scenes s = new Scenes(10, 64, -2, 13, 64, 1).fill(10, 64, -2, 13, 64, 1, "stone");
        RenderResult result = renderer.render(s.grid(), View.parse("top"), PLAIN, java.util.List.of(
                new Marker(12.5, 65, -0.5, "villager \"Shopkeeper\""),
                new Marker(40, 65, 40, "text_display \"Welcome\"")));
        BufferedImage image = Scenes.decode(result);

        assertThat(image.getRGB(40, 24) & 0xffffff).as("centre of the block at x=12, z=-1").isEqualTo(0xff00ff);
        assertThat(isGray(image.getRGB(8, 56))).isTrue();
        assertThat(result.caption()).endsWith("Markers, numbered (magenta dot = other): 1 villager \"Shopkeeper\" at 12.5,65,-0.5; "
                + "2 text_display \"Welcome\" at 40,65,40. Outside the image: 2.");
    }

    @Test
    void sideViewsMirrorEachOther() {
        // A wall along x (0..3) two high, red block at x=0 on the upper row.
        Scenes s = new Scenes(0, 64, 0, 3, 65, 0).fill(0, 64, 0, 3, 65, 0, "stone").set(0, 65, 0, "red_wool");
        BufferedImage south = Scenes.decode(renderer.render(s.grid(), View.parse("south"), PLAIN));
        BufferedImage north = Scenes.decode(renderer.render(s.grid(), View.parse("north"), PLAIN));

        assertThat(south.getHeight()).isEqualTo(32);
        assertThat(isRed(south.getRGB(8, 8))).as("south view: x=0 is on the left, y=65 on top").isTrue();
        assertThat(isGray(south.getRGB(56, 8))).isTrue();
        assertThat(isGray(south.getRGB(8, 24))).isTrue();
        assertThat(isRed(north.getRGB(56, 8))).as("north view: x=0 is on the right").isTrue();
        assertThat(isGray(north.getRGB(8, 8))).isTrue();

        Scenes alongZ = new Scenes(0, 64, 0, 0, 65, 3).fill(0, 64, 0, 0, 65, 3, "stone").set(0, 65, 0, "red_wool");
        BufferedImage east = Scenes.decode(renderer.render(alongZ.grid(), View.parse("east"), PLAIN));
        BufferedImage west = Scenes.decode(renderer.render(alongZ.grid(), View.parse("west"), PLAIN));
        assertThat(isRed(east.getRGB(56, 8))).as("east view: z=0 (north) is on the right").isTrue();
        assertThat(isRed(west.getRGB(8, 8))).as("west view: z=0 (north) is on the left").isTrue();
    }

    @Test
    void topViewShowsTheHighestBlockAndPartialShapes() {
        Scenes s = new Scenes(0, 64, 0, 1, 66, 0).fill(0, 64, 0, 1, 64, 0, "stone")
                .set(0, 66, 0, "red_wool").set(1, 65, 0, "red_carpet");
        BufferedImage top = Scenes.decode(renderer.render(s.grid(), View.parse("top"), PLAIN));
        assertThat(isRed(top.getRGB(8, 8))).isTrue();
        assertThat(isRed(top.getRGB(40, 8))).isTrue();
        // From the south the carpet is only one sixteenth of a block high.
        BufferedImage south = Scenes.decode(renderer.render(s.grid(), View.parse("south"), PLAIN.withMaxSize(96)));
        assertThat(south.getWidth()).isEqualTo(64);
        assertThat(isRed(south.getRGB(48, 63))).as("carpet strip at the bottom of y=65").isTrue();
        assertThat(isRed(south.getRGB(48, 40))).as("air above the carpet").isFalse();
    }

    @Test
    void imagesStayInsideTheSizeBudget() {
        for (String view : new String[] {"top", "south", "east", "iso", "iso-ne", "iso-nw", "iso-sw", "eye 6,66,20 look 6,65,6"}) {
            for (int size : new int[] {64, 200, 512}) {
                RenderResult result = renderer.render(Scenes.hut(), View.parse(view), RenderOptions.defaults().withMaxSize(size));
                BufferedImage image = Scenes.decode(result);
                assertThat(Math.max(image.getWidth(), image.getHeight())).as(view + " at " + size).isLessThanOrEqualTo(size);
                assertThat(image.getWidth()).isEqualTo(result.width());
                assertThat(image.getHeight()).isEqualTo(result.height());
                assertThat(result.estimatedTokens()).isEqualTo((int) Math.ceil(result.width() * result.height() / 750.0));
                assertThat(result.caption()).contains("Visible: ").doesNotContain("nothing");
            }
        }
    }

    @Test
    void regionsLargerThanTheImageAreScaledDown() {
        Scenes s = new Scenes(0, 0, 0, 199, 0, 99).fill(0, 0, 0, 199, 0, 99, "stone").fill(100, 0, 0, 199, 0, 99, "red_wool");
        RenderResult result = renderer.render(s.grid(), View.parse("top"), PLAIN.withMaxSize(100));
        BufferedImage image = Scenes.decode(result);
        assertThat(image.getWidth()).isEqualTo(100);
        assertThat(image.getHeight()).isEqualTo(50);
        assertThat(isGray(image.getRGB(25, 25))).isTrue();
        assertThat(isRed(image.getRGB(75, 25))).isTrue();
        assertThat(result.caption()).contains("0.50 px per block");
    }

    @Test
    void rulersAddAMarginWithoutMovingBlocksOffTheGrid() {
        Scenes s = new Scenes(-5, 64, 100, 2, 64, 107).fill(-5, 64, 100, 2, 64, 107, "stone").set(-5, 64, 100, "red_wool");
        RenderOptions options = new RenderOptions(400, false, false, true, false);
        RenderResult result = renderer.render(s.grid(), View.parse("top"), options);
        BufferedImage image = Scenes.decode(result);
        int margin = image.getWidth() - 8 * 32;
        assertThat(margin).isBetween(10, 60);
        assertThat(image.getRGB(0, image.getHeight() - 1) & 0xffffff).as("margin is white").isEqualTo(0xffffff);
        assertThat(isRed(image.getRGB(margin + 16, image.getHeight() - 8 * 32 + 16))).isTrue();
        // Some ink (a label) in the top margin above the first column.
        boolean ink = false;
        for (int y = 0; y < image.getHeight() - 8 * 32; y++) {
            for (int x = margin; x < margin + 32; x++) {
                ink |= (image.getRGB(x, y) & 0xffffff) == 0x202020;
            }
        }
        assertThat(ink).isTrue();
        assertThat(result.caption()).contains("coordinates on the rulers");
    }

    @Test
    void shadowsFallAwayFromTheSouthEastSun() {
        Scenes s = new Scenes(0, 64, 0, 6, 67, 6).fill(0, 64, 0, 6, 64, 6, "stone").fill(3, 65, 3, 3, 67, 3, "stone");
        RenderOptions lit = new RenderOptions(7 * 16, true, false, false, false);
        BufferedImage image = Scenes.decode(renderer.render(s.grid(), View.parse("top"), lit));
        int shaded = Scenes.red(image.getRGB(2 * 16 + 8, 2 * 16 + 8));
        int sunny = Scenes.red(image.getRGB(5 * 16 + 8, 5 * 16 + 8));
        assertThat(shaded).as("north-west of the pillar").isLessThan(sunny - 20);
    }

    @Test
    void outlinesDarkenBlockEdges() {
        Scenes s = new Scenes(0, 64, 0, 1, 64, 1).fill(0, 64, 0, 1, 64, 1, "stone");
        RenderOptions outlined = new RenderOptions(64, false, true, false, false);
        BufferedImage image = Scenes.decode(renderer.render(s.grid(), View.parse("top"), outlined));
        assertThat(Scenes.red(image.getRGB(31, 16))).isLessThan(Scenes.red(image.getRGB(16, 16)));
    }

    @Test
    void glassLetsTheBlockBehindShowThrough() {
        Scenes s = new Scenes(0, 64, 0, 0, 64, 1).set(0, 64, 0, "red_wool").set(0, 64, 1, "glass");
        BufferedImage south = Scenes.decode(renderer.render(s.grid(), View.parse("south"), PLAIN));
        int rgb = south.getRGB(16, 16);
        assertThat(Scenes.red(rgb)).isGreaterThan(Scenes.blue(rgb) + 20);
        assertThat(Scenes.blue(rgb)).as("tinted by the glass").isGreaterThan(60);
    }

    @Test
    void texturesReplaceTableColours() {
        BufferedImage texture = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                // Left half green, right half blue; the second animation frame is white and must be ignored.
                texture.setRGB(x, y, y >= 16 ? 0xffffffff : x < 8 ? 0xff00c000 : 0xff0000c0);
            }
        }
        TextureSource source = (namespace, path) ->
                namespace.equals("myplugin") && path.equals("block/ruby_block") ? Optional.of(texture) : Optional.empty();
        Scenes s = new Scenes(0, 64, 0, 0, 64, 0).set(0, 64, 0, "myplugin:ruby_block");
        RenderResult result = new WorldRenderer(source).render(s.grid(), View.parse("top"), PLAIN);
        BufferedImage image = Scenes.decode(result);
        assertThat(Scenes.green(image.getRGB(8, 16))).isGreaterThan(150);
        assertThat(Scenes.blue(image.getRGB(8, 16))).isLessThan(20);
        assertThat(Scenes.blue(image.getRGB(24, 16))).isGreaterThan(150);
        assertThat(result.caption()).doesNotContain("No colour known");
        // Without the texture the block is unknown and the caption says so.
        assertThat(renderer.render(s.grid(), View.parse("top"), PLAIN).caption())
                .contains("No colour known for ruby_block");
    }

    @Test
    void cameraSeesWhatItFacesAndNothingBehindIt() {
        Scenes s = new Scenes(0, 64, 0, 4, 68, 0).fill(0, 64, 0, 4, 68, 0, "red_wool");
        RenderOptions options = new RenderOptions(64, false, false, false, false);
        RenderResult facing = renderer.render(s.grid(), View.parse("eye 2.5,66.5,6 look 2.5,66.5,0"), options);
        BufferedImage image = Scenes.decode(facing);
        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getHeight()).isEqualTo(48);
        assertThat(isRed(image.getRGB(32, 24))).isTrue();
        assertThat(image.getRGB(1, 24) & 0xffffff).as("sky beside the wall").isEqualTo(Tracer.BACKGROUND);
        assertThat(facing.caption()).contains("First-person view", "red_wool");

        RenderResult away = renderer.render(s.grid(), View.parse("eye 2.5,66.5,6 yaw 0 pitch 0"), options);
        assertThat(away.caption()).contains("Visible: nothing");
    }

    @Test
    void emptyAndUnchangedCellsRenderAsBackground() {
        Scenes s = new Scenes(0, 0, 0, 3, 3, 3);
        io.github.imnotstable.msc.protocol.world.BlockGrid grid = s.grid();
        java.util.Arrays.fill(grid.blocks(), -1);
        RenderResult result = renderer.render(grid, View.parse("iso"), PLAIN);
        assertThat(Scenes.decode(result).getRGB(result.width() / 2, result.height() / 2) & 0xffffff).isEqualTo(Tracer.BACKGROUND);
        assertThat(result.caption()).contains("Visible: nothing");
    }

    @Test
    void optionsRejectSizesOutsideTheBudget() {
        assertThatThrownBy(() -> RenderOptions.defaults().withMaxSize(2000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 64 and 1568");
        assertThatThrownBy(() -> RenderOptions.defaults().withMaxSize(8)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFullProtocolReadRendersQuickly() {
        // 128 x 64 x 128 = the protocol's largest single read: rolling terrain.
        Scenes s = new Scenes(0, 0, 0, 127, 63, 127);
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 128; x++) {
                int h = (int) (20 + 10 * Math.sin(x / 9.0) + 8 * Math.cos(z / 7.0));
                s.fill(x, 0, z, x, h, z, "stone").set(x, h + 1, z, "grass_block");
            }
        }
        long start = System.nanoTime();
        RenderResult result = renderer.render(s.grid(), View.parse("iso"), RenderOptions.defaults());
        assertThat(result.png().length).isGreaterThan(1000);
        assertThat((System.nanoTime() - start) / 1_000_000_000.0).as("seconds").isLessThan(20);
    }
}
