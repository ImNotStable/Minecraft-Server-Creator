package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** furnish and image (ADR 0082). The world: a closed stone room 0..12 x 0..6 x 0..10 with a door gap at x 6, z 0. */
class RoomsAndPicturesTest {
    @TempDir
    Path dir;

    private static BlockGrid world(Region r) {
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    boolean shell = x >= 0 && x <= 12 && z >= 0 && z <= 10 && y >= 0 && y <= 6
                            && (x == 0 || x == 12 || z == 0 || z == 10 || y == 0 || y == 6);
                    boolean door = x == 6 && z == 0 && (y == 1 || y == 2);
                    blocks[r.index(x, y, z)] = shell && !door ? 1 : 0;
                }
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone"), blocks);
    }

    private Canvas draw(String steps) throws Exception {
        BuildScript.StructureFiles files = new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String w) {
                throw new AssertionError();
            }

            @Override
            public BlockGrid read(Region region) {
                return world(region);
            }

            @Override
            public Path file(String path) {
                return dir.resolve(path);
            }
        };
        List<Step> parsed = new BuildScript("world", files).parse(steps);
        return Builder.drawJoined(new Step.Group(parsed).bounds(), parsed, RoomsAndPicturesTest::world);
    }

    @Test
    void everyRoomFurnishesWithPlaceableBlocksAndKeepsTheDoorClear() throws Exception {
        for (String room : Furnish.ROOMS) {
            Canvas c = draw("furnish 1,1,1 11,5,9 room=" + room + " wood=oak color=blue seed=3");
            Map<String, Long> counts = c.counts();
            assertThat(counts).as(room).isNotEmpty();
            List<String> placed = c.palette();
            BlockRules.require(placed);
            // Inside the door and beside it stays open.
            for (int x = 5; x <= 7; x++) {
                assertThat(c.planned(x, 1, 1)).as(room + " at the door x=" + x).isNull();
            }
            // Nothing in the walls or floor.
            assertThat(c.planned(0, 1, 5)).isNull();
            assertThat(c.planned(5, 0, 5)).isNull();
        }
        Canvas bedroom = draw("furnish 1,1,1 11,5,9 room=bedroom wood=spruce color=red");
        assertThat(bedroom.palette()).anyMatch(s -> s.startsWith("minecraft:red_bed[") && s.contains("part=head"));
        assertThat(bedroom.palette()).anyMatch(s -> s.startsWith("minecraft:lantern[hanging=true]"));
        Canvas dining = draw("furnish 1,1,1 11,5,9 room=dining");
        assertThat(dining.palette()).anyMatch(s -> s.startsWith("minecraft:spruce_stairs[facing="));
        assertThatThrownBy(() -> draw("furnish 1,1,1 11,5,9 room=garage")).isInstanceOf(ToolException.class)
                .hasMessageContaining("bedroom");
    }

    @Test
    void imagesMapEachPixelToItsNearestBlockOnTheGivenPlane() throws Exception {
        BufferedImage img = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 4; x++) {
            img.setRGB(x, 0, 0xffe9ecec); // white
            img.setRGB(x, 1, 0xff1d1d21); // near black
        }
        img.setRGB(3, 1, 0x00000000); // transparent
        ImageIO.write(img, "png", dir.resolve("flag.png").toFile());
        Canvas wall = draw("image 20,1,20 file=flag.png w=4 palette=wool dither=false");
        assertThat(wall.planned(20, 2, 20)).isEqualTo("minecraft:white_wool");
        assertThat(wall.planned(20, 1, 20)).isEqualTo("minecraft:black_wool");
        assertThat(wall.planned(23, 1, 20)).isNull();
        Canvas floor = draw("image 20,1,20 file=flag.png w=4 plane=floor palette=concrete dither=false");
        assertThat(floor.planned(21, 1, 20)).isEqualTo("minecraft:white_concrete");
        assertThat(floor.planned(21, 1, 21)).isEqualTo("minecraft:black_concrete");
        assertThatThrownBy(() -> draw("image 0,1,0 file=flag.png w=4 palette=gems")).isInstanceOf(ToolException.class)
                .hasMessageContaining("wool");
    }
}
