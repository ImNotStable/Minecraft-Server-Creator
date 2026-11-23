package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import org.junit.jupiter.api.Test;

class BuildReviewTest {
    /** An 11x11 floor of grass with a box of {@code wall} on it, 9 wide, 8 tall, hollow, flat-roofed. */
    private static BlockGrid box(String wall) {
        return box(wall, wall);
    }

    /** The same box with its blocks alternating {@code a} and {@code b} like a checkerboard. */
    private static BlockGrid box(String a, String b) {
        StringBuilder text = new StringBuilder("legend: g=grass_block #=" + a + " p=" + b + "\ny=0\n");
        text.append("ggggggggggg\n".repeat(11));
        for (int y = 1; y <= 8; y++) {
            text.append("y=").append(y).append("\n...........\n");
            for (int z = 1; z <= 9; z++) {
                boolean solid = y == 8 || z == 1 || z == 9;
                StringBuilder row = new StringBuilder(".");
                for (int x = 1; x <= 9; x++) {
                    boolean on = solid || x == 1 || x == 9;
                    row.append(!on ? '.' : (x + y + z) % 2 == 0 ? '#' : 'p');
                }
                text.append(row).append(".\n");
            }
            text.append("...........\n");
        }
        return TextGrid.parse(text.toString(), "world", new BlockPos(0, 64, 0));
    }

    @Test
    void aPlainBoxGetsEveryFixAndTheGroundIsLeftOut() {
        String review = BuildReview.review(box("bricks"));
        assertThat(review).startsWith("review (ground up to y 64 left out): 305 blocks, 1 types: bricks 100%\n")
                .contains("detail blocks 0% (aim 12%+)", "lights 0")
                .contains("1) flat walls", "few shaped blocks", "only 1 block types", "flat roofs")
                .doesNotContain("grass");
    }

    @Test
    void aMixedDetailedBuildPassesTheNumbers() {
        // Neighbours are always different kinds, stairs among them: no flat patch, plenty of detail, a shaped roof.
        StringBuilder text = new StringBuilder(
                "legend: a=stone_bricks b=cracked_stone_bricks c=mossy_stone_bricks d=andesite e=stone_brick_stairs "
                        + "f=lantern g=oak_planks\n");
        String kinds = "abcdefg";
        for (int y = 0; y < 7; y++) {
            // A ring of air around the 5x5 build, so no layer looks like ground.
            text.append("y=").append(y).append("\n.......\n");
            for (int z = 0; z < 5; z++) {
                StringBuilder row = new StringBuilder(".");
                for (int x = 0; x < 5; x++) {
                    row.append(y == 6 ? 'e' : kinds.charAt((x + 2 * z + 3 * y) % kinds.length()));
                }
                text.append(row).append(".\n");
            }
            text.append(".......\n");
        }
        String review = BuildReview.review(TextGrid.parse(text.toString(), "world", new BlockPos(0, 0, 0)));
        assertThat(review).contains("7 types", "flat wall faces 0%", "shaped roof 100%")
                .endsWith("no number below the bar; now judge the render: silhouette, story, scale");
    }

    @Test
    void aRandomMixOfClashingColoursIsSpeckledButNearTonesAreNot() {
        assertThat(BuildReview.review(box("deepslate_bricks", "prismarine_bricks")))
                .contains("speckled walls", "speckled roof:").endsWith("then review this region again");
        assertThat(BuildReview.review(box("stone_bricks", "mossy_stone_bricks"))).contains("speckled faces 0%",
                "speckled roof 0%").doesNotContain("speckled walls", "speckled roof:");
    }

    @Test
    void aTreeCanopyIsNotAFlatRoof() {
        StringBuilder text = new StringBuilder("legend: l=oak_log o=oak_leaves\n");
        for (int y = 0; y < 9; y++) {
            text.append("y=").append(y).append('\n');
            for (int z = 0; z < 5; z++) {
                text.append(y < 6 ? (z == 2 ? "..l.." : ".....") : "ooooo").append('\n');
            }
        }
        assertThat(BuildReview.review(TextGrid.parse(text.toString(), "world", new BlockPos(0, 0, 0))))
                .doesNotContain("flat roofs");
    }

    @Test
    void anEmptyRegionSaysSo() {
        BlockGrid air = TextGrid.parse("legend: #=stone\ny=0\n...\n...", "world", new BlockPos(0, 0, 0));
        assertThat(BuildReview.review(air)).isEqualTo("review: nothing built above the ground");
    }
}
