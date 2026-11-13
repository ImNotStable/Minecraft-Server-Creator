package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Entity colours, hologram and sign text, markers behind blocks, and corner labels on isometric views. */
class OverlayTest {
    private static final RenderOptions PLAIN = new RenderOptions(256, false, false, false, false);
    private final WorldRenderer renderer = new WorldRenderer();

    private static Scenes floor() {
        return new Scenes(0, 64, 0, 15, 68, 15).fill(0, 64, 0, 15, 64, 15, "stone");
    }

    private static int count(BufferedImage image, int rgb) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void entityDotsAreColouredByKind() {
        RenderResult result = renderer.render(floor().grid(), View.parse("top"), PLAIN, List.of(
                Marker.entity("minecraft:zombie", null, 4.5, 65, 4.5),
                Marker.entity("minecraft:player", "Alex", 11.5, 65, 11.5)));
        BufferedImage image = Scenes.decode(result);
        // 16 px per block: block 4 spans pixels 64..79.
        assertThat(image.getRGB(72, 72) & 0xffffff).isEqualTo(0xe02828);
        assertThat(image.getRGB(184, 184) & 0xffffff).isEqualTo(0x3070ff);
        assertThat(result.caption()).endsWith("Markers, numbered (blue dot = player, red dot = hostile): "
                + "1 zombie at 4.5,65,4.5; 2 player \"Alex\" at 11.5,65,11.5.");
    }

    @Test
    void hologramsAndSignsShowTheirWords() {
        RenderResult result = renderer.render(floor().grid(), View.parse("top"), PLAIN, List.of(
                Marker.entity("minecraft:text_display", "Welcome", 8, 66, 4),
                Marker.sign(8, 65, 12, List.of("Shop", "", "", ""))));
        BufferedImage image = Scenes.decode(result);
        // Dark box with white letters for the hologram, light box with dark letters for the sign.
        assertThat(count(image, 0x262626)).isGreaterThan(100);
        assertThat(count(image, 0xffffff)).isGreaterThan(20);
        assertThat(count(image, 0xeedcb0)).isGreaterThan(50);
        assertThat(image.getRGB(136, 200) & 0xffffff).as("sign box covers the sign's block").isEqualTo(0xeedcb0);
        assertThat(result.caption()).contains("dark box = floating text, light box = sign text",
                "1 text_display \"Welcome\" at 8,66,4; 2 sign \"Shop\" at 8.5,65.5,12.5.");
    }

    @Test
    void textBoxesMoveRatherThanCoverEachOther() {
        List<Marker> one = List.of(Marker.text(8, 66, 8, "Upper"));
        List<Marker> two = List.of(Marker.text(8, 66, 8, "Upper"), Marker.text(8, 66, 8, "Lower"));
        int single = count(Scenes.decode(renderer.render(floor().grid(), View.parse("top"), PLAIN, one)), 0x262626);
        int both = count(Scenes.decode(renderer.render(floor().grid(), View.parse("top"), PLAIN, two)), 0x262626);
        assertThat(both).isGreaterThan(single * 3 / 2);
    }

    @Test
    void markersBehindBlocksAreHollowAndListed() {
        Scenes s = floor().fill(0, 67, 0, 7, 67, 15, "oak_planks");
        RenderResult result = renderer.render(s.grid(), View.parse("top"), PLAIN, List.of(
                Marker.entity("minecraft:cow", null, 4.5, 65, 4.5),
                Marker.entity("minecraft:cow", null, 12.5, 65, 4.5)));
        BufferedImage image = Scenes.decode(result);
        assertThat(image.getRGB(72, 72) & 0xffffff).as("hollow centre under the roof").isEqualTo(0xffffff);
        assertThat(image.getRGB(200, 72) & 0xffffff).as("solid dot in the open").isEqualTo(0x30c048);
        assertThat(result.caption()).endsWith("Behind blocks (hollow or faded): 1.");
    }

    @Test
    void aSignBlockDoesNotHideItsOwnText() {
        for (String sign : List.of("oak_sign[rotation=0]", "oak_hanging_sign[rotation=0]")) {
            Scenes s = floor().set(8, 65, 12, sign);
            RenderResult result = renderer.render(s.grid(), View.parse("top"), PLAIN,
                    List.of(Marker.sign(8, 65, 12, List.of("Shop"))));
            assertThat(result.caption()).as(sign).doesNotContain("Behind blocks");
        }
        // A roof over the sign still hides it.
        Scenes roofed = floor().set(8, 65, 12, "oak_sign").fill(0, 67, 0, 15, 67, 15, "oak_planks");
        assertThat(renderer.render(roofed.grid(), View.parse("top"), PLAIN,
                List.of(Marker.sign(8, 65, 12, List.of("Shop")))).caption()).endsWith("Behind blocks (hollow or faded): 1.");
        // So does a roof that rests right on top of the sign's block.
        Scenes touching = floor().set(8, 65, 12, "oak_sign").fill(0, 66, 0, 15, 66, 15, "oak_planks");
        assertThat(renderer.render(touching.grid(), View.parse("top"), PLAIN,
                List.of(Marker.sign(8, 65, 12, List.of("Shop")))).caption()).endsWith("Behind blocks (hollow or faded): 1.");
        // And a partial block (a slab) right above it.
        Scenes slab = floor().set(8, 65, 12, "oak_sign").set(8, 66, 12, "oak_slab[type=bottom]");
        assertThat(renderer.render(slab.grid(), View.parse("top"), PLAIN,
                List.of(Marker.sign(8, 65, 12, List.of("Shop")))).caption()).endsWith("Behind blocks (hollow or faded): 1.");
        // A wall sign seen from the far side of its wall.
        Scenes wall = floor().fill(0, 65, 8, 15, 68, 8, "stone_bricks").set(8, 66, 7, "oak_wall_sign[facing=north]");
        assertThat(renderer.render(wall.grid(), View.parse("south"), PLAIN,
                List.of(Marker.sign(8, 66, 7, List.of("Shop")))).caption()).endsWith("Behind blocks (hollow or faded): 1.");
    }

    @Test
    void captionListsOnlyTheDrawnMarkers() {
        List<Marker> markers = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            markers.add(Marker.entity("minecraft:item", null, 1.5 + i % 14, 65, 1.5 + i % 13));
        }
        markers.add(Marker.sign(3, 65, 3, List.of("Late")));
        String caption = renderer.render(floor().grid(), View.parse("top"), PLAIN, markers).caption();
        assertThat(caption).contains("; 40 item at ").doesNotContain("41 item")
                .endsWith(" 961 more not drawn: 960 item, 1 sign; world_entities lists entities.");
        assertThat(caption.length()).isLessThan(2500);
    }

    @Test
    void glassDoesNotHideAMarker() {
        Scenes s = floor().fill(0, 67, 0, 15, 67, 15, "glass");
        RenderResult result = renderer.render(s.grid(), View.parse("top"), PLAIN,
                List.of(Marker.entity("minecraft:cow", null, 4.5, 65, 4.5)));
        assertThat(result.caption()).doesNotContain("Behind blocks");
    }

    @Test
    void aCameraSeesAMarkerUnlessAWallIsBetween() {
        Scenes s = floor().fill(8, 65, 0, 8, 68, 15, "stone_bricks");
        List<Marker> cows = List.of(Marker.entity("minecraft:cow", null, 4.5, 65, 8.5),
                Marker.entity("minecraft:cow", null, 12.5, 65, 8.5));
        RenderResult result = renderer.render(s.grid(), View.parse("eye 2,66.5,8.5 look 15,66,8.5"), PLAIN, cows);
        assertThat(result.caption()).endsWith("Behind blocks (hollow or faded): 2.");
    }

    @Test
    void isometricViewsLabelTheirCornersWithBlockCoordinates() {
        RenderResult labelled = renderer.render(Scenes.hut(), View.parse("iso"), RenderOptions.defaults());
        assertThat(labelled.caption()).contains("corners labelled with block coordinates x,y,z.");
        assertThat(Math.max(labelled.width(), labelled.height())).isLessThanOrEqualTo(512);
        BufferedImage image = Scenes.decode(labelled);
        int ink = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < 20; x++) {
                ink += (image.getRGB(x, y) & 0xffffff) == 0x202020 ? 1 : 0;
            }
        }
        assertThat(ink).as("label text in the left margin").isGreaterThan(10);

        RenderResult bare = renderer.render(Scenes.hut(), View.parse("iso"), RenderOptions.defaults().withRulers(false));
        assertThat(bare.caption()).doesNotContain("corners labelled");
        RenderResult tiny = renderer.render(Scenes.hut(), View.parse("iso"), RenderOptions.defaults().withMaxSize(64));
        assertThat(tiny.caption()).as("no room for labels").doesNotContain("corners labelled");
    }
}
