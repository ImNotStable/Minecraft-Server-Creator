package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
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
        assertThat(review).startsWith("review (ground up to y 64 left out): 305 blocks, 1 type: bricks 100%\n")
                .contains("detail blocks 0% (aim 12%+)", "lights 0")
                .contains("1) flat walls", "few shaped blocks", "only 1 block type:", "flat roofs")
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

    private static BlockGrid dock(char fill) {
        return dock(fill, 9, 4);
    }

    /** A sand seabed {@code size} wide, log posts every 4 up to a plank deck at y={@code deck}, {@code fill} around. */
    private static BlockGrid dock(char fill, int size, int deck) {
        StringBuilder text = new StringBuilder("legend: s=sand l=spruce_log p=spruce_planks w=water\ny=0\n");
        text.append(("s".repeat(size) + "\n").repeat(size));
        for (int y = 1; y <= deck; y++) {
            text.append("y=").append(y).append('\n');
            for (int z = 0; z < size; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < size; x++) {
                    boolean post = x % 4 == 0 && z % 4 == 0;
                    row.append(y == deck ? 'p' : post ? 'l' : fill);
                }
                text.append(row).append('\n');
            }
        }
        return TextGrid.parse(text.toString(), "world", new BlockPos(0, 60, 0));
    }

    @Test
    void dockPostsStandingInWaterHaveNoBareBase() {
        assertThat(BuildReview.review(dock('.'))).contains("bare base: ");
        assertThat(BuildReview.review(dock('w'))).doesNotContain("bare base: ");
    }

    @Test
    void aPierOverDeepWaterIsADeckNotAFlatRoof() {
        // Gemini 4 Hydrogen's pier was told to pitch its roof and plant flowers (ADR 0109); on dry land it would be.
        assertThat(BuildReview.review(dock('w', 21, 7))).doesNotContain("flat roofs", "no plants", "bare base:");
        assertThat(BuildReview.review(dock('.', 21, 7))).contains("flat roofs", "no plants");
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
    void aFailingReviewSaysNotDone() {
        assertThat(BuildReview.review(box("bricks"))).contains("\nverdict: NOT DONE, 7 checks below the bar\nfix first: 1)");
    }

    @Test
    void naturalRockIsGroundNotABuild() {
        // A stone floor, a stone hill on the west half rising in solid columns, and a bricks hut on the east.
        StringBuilder text = new StringBuilder("legend: s=stone b=bricks\n");
        for (int y = 0; y <= 9; y++) {
            text.append("y=").append(y).append('\n');
            for (int z = 0; z < 11; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < 11; x++) {
                    boolean hill = x < 3 && y <= 2;
                    boolean hut = x >= 4 && y >= 1 && (y == 9 || x == 4 || x == 10 || z == 0 || z == 10);
                    row.append(y == 0 || hill ? 's' : hut ? 'b' : '.');
                }
                text.append(row).append('\n');
            }
        }
        String review = BuildReview.review(TextGrid.parse(text.toString(), "world", new BlockPos(0, 64, 0)));
        assertThat(review).contains("1 type: bricks 100%").doesNotContain("stone ");
    }

    @Test
    void aDarkRoomAndALadderEndingInTheAirAreFlagged() {
        // A closed 12x12 stone_bricks room on a grass floor, no lights, a ladder on the wall from y 3 up.
        Region r = Region.of("world", 0, 0, 0, 13, 7, 13);
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = 0; y <= 7; y++) {
            for (int z = 0; z <= 13; z++) {
                for (int x = 0; x <= 13; x++) {
                    boolean shell = y == 0 || y == 1 || y == 7 || x == 0 || x == 13 || z == 0 || z == 13;
                    blocks[r.index(x, y, z)] = y == 0 ? 1 : shell ? 2 : 0;
                }
            }
        }
        for (int y = 3; y <= 6; y++) {
            blocks[r.index(1, y, 5)] = 3;
        }
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:air", "minecraft:grass_block", "minecraft:stone_bricks",
                "minecraft:ladder[facing=east,waterlogged=false]"), blocks);
        String review = BuildReview.review(grid);
        assertThat(review).contains("dark rooms: 100% of the floor inside").contains("broken ladders: 1 (first at 1,3,5)");
        blocks[r.index(6, 6, 6)] = 4;
        blocks[r.index(1, 2, 5)] = 3;
        blocks[r.index(1, 1, 5)] = 2;
        String fixed = BuildReview.review(new BlockGrid(r, List.of("minecraft:air", "minecraft:grass_block",
                "minecraft:stone_bricks", "minecraft:ladder[facing=east,waterlogged=false]", "minecraft:lantern"), blocks));
        assertThat(fixed).doesNotContain("dark rooms").doesNotContain("broken ladders");
    }

    @Test
    void leavesInWallsBoxyHedgesHangingWoolAndTrapdoorClumpsAreFlagged() {
        // Run 6's Havenfall: a hedge pillar of leaves, a tree pushed between two walls, a wool awning, a trapdoor facade.
        Region r = Region.of("world", 0, 0, 0, 19, 8, 19);
        int[] b = new int[Math.toIntExact(r.volume())];
        for (int y = 0; y <= 5; y++) {
            for (int z = 1; z <= 3; z++) {
                for (int x = 1; x <= 3; x++) {
                    b[r.index(x, y, z)] = 1; // the leaf pillar
                }
            }
        }
        for (int z = 8; z <= 17; z++) {
            for (int y = 0; y <= 6; y++) {
                b[r.index(8, y, z)] = 2; // a house's two walls, its roof, and a canopy inside under the roof
                b[r.index(10, y, z)] = 2;
            }
            b[r.index(9, 6, z)] = 1;
            for (int x = 8; x <= 10; x++) {
                b[r.index(x, 7, z)] = 2;
            }
        }
        for (int x = 14; x <= 16; x++) {
            for (int z = 14; z <= 15; z++) {
                b[r.index(x, 4, z)] = 3; // wool awning over air
            }
            for (int y = 1; y <= 3; y++) {
                b[r.index(x, y, 18)] = 4; // trapdoors on a wall
                b[r.index(x, y, 19)] = 2;
            }
        }
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:air", "minecraft:oak_leaves", "minecraft:stone_bricks",
                "minecraft:white_wool", "minecraft:spruce_trapdoor[facing=north,half=bottom,open=true]"), b);
        String[] names = {null, "oak_leaves", "stone_bricks", "white_wool", "spruce_trapdoor"};
        assertThat(String.join("\n", BuildReview.masses(grid, names, new boolean[b.length])))
                .contains("leaves in walls: 10 leaf blocks").contains("stacked leaves:").contains("hanging wool: 6")
                .contains("trapdoor clumps: 9");
    }

    @Test
    void aHouseInARegionThatIsMostlyLandIsStillJudged() {
        // Seven layers of natural stone under a 12x12 bricks house: 92% of the blocks are land.
        Region r = Region.of("world", 0, 0, 0, 29, 16, 29);
        int[] b = new int[Math.toIntExact(r.volume())];
        for (int z = 0; z <= 29; z++) {
            for (int x = 0; x <= 29; x++) {
                for (int y = 0; y <= 6; y++) {
                    b[r.index(x, y, z)] = 1;
                }
                boolean in = x >= 9 && x <= 20 && z >= 9 && z <= 20;
                for (int y = 7; y <= 15 && in; y++) {
                    b[r.index(x, y, z)] = y == 15 || x == 9 || x == 20 || z == 9 || z == 20 ? 2 : 0;
                }
            }
        }
        String review = BuildReview.review(new BlockGrid(r, List.of("minecraft:air", "minecraft:stone",
                "minecraft:bricks"), b));
        assertThat(review).doesNotContain("mostly land").contains("type: bricks 100%");
    }

    @Test
    void aTownSizedReviewJudgesEachBuildingOnItsOwn() {
        // A 60x60 grass town with three plain 6x6 bricks huts and a lone 5-tall log (a tree trunk, not a building).
        Region r = Region.of("world", 0, 0, 0, 59, 10, 59);
        int[] b = new int[Math.toIntExact(r.volume())];
        for (int z = 0; z <= 59; z++) {
            for (int x = 0; x <= 59; x++) {
                b[r.index(x, 0, z)] = 1;
                for (int[] hut : new int[][] {{5, 5}, {40, 40}, {5, 40}}) {
                    boolean in = x >= hut[0] && x < hut[0] + 6 && z >= hut[1] && z < hut[1] + 6;
                    boolean shell = x == hut[0] || x == hut[0] + 5 || z == hut[1] || z == hut[1] + 5;
                    for (int y = 1; y <= 7 && in; y++) {
                        b[r.index(x, y, z)] = shell || y == 7 ? 2 : 0;
                    }
                }
            }
        }
        for (int y = 1; y <= 5; y++) {
            b[r.index(25, y, 30)] = 3;
        }
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:air", "minecraft:grass_block", "minecraft:bricks",
                "minecraft:oak_log[axis=y]"), b);
        assertThat(BuildReview.review(grid)).contains("fix first: 1) buildings failing: 3 of 3 fail on their own "
                + "(world 4,0,4 11,10,11: ")
                .contains("dark streets: 100% of the open ground within 4 blocks of the buildings")
                .contains("look-alike buildings: 3 buildings share one footprint, height and main materials (bricks");
    }

    @Test
    void aBareBaseSaysWhereItRunsAndWhatToTry() {
        // Run 34 rebuilt whole houses after "bare base" and never touched the line itself (ADR 0112).
        assertThat(BuildReview.review(box("bricks"))).contains("The bare line runs x 1..9, z 1..9 at y 65..65; "
                + "for example trim 1,65,1 9,66,9 <a stair or slab of your palette> at=base, then decorate "
                + "0,65,0 10,66,10 <your plants> on=ground");
    }

    @Test
    void anEmptyRegionSaysSo() {
        BlockGrid air = TextGrid.parse("legend: #=stone\ny=0\n...\n...", "world", new BlockPos(0, 0, 0));
        assertThat(BuildReview.review(air)).isEqualTo("review: nothing built above the ground");
    }

    @Test
    void groundCoverAloneIsLandNotABuild() {
        StringBuilder text = new StringBuilder("legend: g=grass_block s=short_grass p=poppy\ny=0\n");
        text.append("ggggggggggg\n".repeat(11)).append("y=1\n");
        for (int z = 0; z < 11; z++) {
            text.append(z % 3 == 0 ? "sspsssspsss\n" : "s.s.s.s.s.s\n");
        }
        String review = BuildReview.review(TextGrid.parse(text.toString(), "world", new BlockPos(0, 64, 0)));
        assertThat(review).startsWith("review: only plants above the ground").doesNotContain("flat roofs")
                .doesNotContain("no lights").doesNotContain("block types");
        assertThat(BuildReview.groundCover("mossy_stone_bricks")).isFalse();
        assertThat(BuildReview.groundCover("oak_leaves")).isFalse();
        assertThat(BuildReview.groundCover("moss_block")).isFalse();
        assertThat(BuildReview.groundCover("oak_sapling")).isTrue();
    }
}
