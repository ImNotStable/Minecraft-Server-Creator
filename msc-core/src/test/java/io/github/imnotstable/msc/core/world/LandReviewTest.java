package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.building.BuildScript;
import io.github.imnotstable.msc.core.building.Canvas;
import io.github.imnotstable.msc.core.building.Step;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The land numbers of {@code review=true} (ADR 0075). */
class LandReviewTest {
    /** A 40x40 layer of grass at y 0 with {@code paint} drawn on it. */
    private static BlockGrid painted(java.util.function.BiFunction<Integer, Integer, Character> paint) {
        StringBuilder text = new StringBuilder("legend: g=grass_block d=dirt_path s=sand\ny=0\n");
        for (int z = 0; z < 40; z++) {
            for (int x = 0; x < 40; x++) {
                Character c = paint.apply(x, z);
                text.append(c == null ? 'g' : c);
            }
            text.append('\n');
        }
        return TextGrid.parse(text.toString(), "world", new BlockPos(0, 0, 0));
    }

    @Test
    void paintedFlatGroundIsFlaggedButStraightPathsAreNot() {
        BlockGrid g = painted((x, z) -> z == 20 || x == 10 ? Character.valueOf('d')
                : x > 25 && z > 25 ? Character.valueOf('s') : null);
        String review = BuildReview.review(g);
        assertThat(review).contains("mostly land", "ground at one level 100%", "flat land: 100% of the ground is at y 0",
                "ground at one level 100%").doesNotContain("straight", "land on the dev world");
        assertThat(BuildReview.review(g, true)).contains("land on the dev world", "world_create");
    }

    @Test
    void aRandomMixOfGroundIsSpeckledButPatchesAreNot() {
        java.util.Random random = new java.util.Random(7);
        String mixed = BuildReview.review(painted((x, z) -> random.nextBoolean() ? Character.valueOf('s') : null));
        assertThat(mixed).contains("speckled ground:");
        String patches = BuildReview.review(painted((x, z) -> x + z / 3 < 20 ? Character.valueOf('s') : null));
        assertThat(patches).contains("speckled ground 0%").doesNotContain("speckled ground:");
    }

    @Test
    void noiseTerrainWithAWindingPathPasses() throws Exception {
        List<Step> steps = new BuildScript("world", null).parse("""
                terrain 0,0,0 63,24,63 stone top=grass_block under=dirt edge=0 seed=3
                path 0,12,30 30,12,10 63,12,40 dirt_path w=3 seed=3""");
        Canvas c = new Canvas(new Step.Group(steps).bounds(), r -> new BlockGrid(r, List.of("minecraft:air"),
                new int[Math.toIntExact(r.volume())]));
        for (Step s : steps) {
            s.draw(c);
        }
        String review = BuildReview.review(c.grid(Region.of("world", 0, 0, 0, 63, 24, 63)));
        assertThat(review).contains("mostly land", "ground at one level").doesNotContain("flat land:",
                "floating", "speckled ground:");
    }

    @Test
    void blocksInTheAirAreFloatingButABoatOnWaterIsNot() {
        StringBuilder text = new StringBuilder("legend: g=grass_block l=oak_leaves w=water p=oak_planks\n");
        text.append("y=0\n").append("ggggg\n".repeat(5));
        text.append("y=1\n").append("wwwww\n".repeat(2)).append("wwpww\n").append("wwwww\n".repeat(2));
        text.append("y=2\n").append(".....\n".repeat(5));
        text.append("y=3\n").append(".....\n").append(".ll..\n").append(".ll..\n").append(".....\n".repeat(2));
        BlockGrid g = TextGrid.parse(text.toString(), "world", new BlockPos(10, 64, 20));
        String review = BuildReview.review(g);
        assertThat(review).contains("floating 4 blocks in 1 clumps", "(at 11,67,21)");
        assertThat(review).doesNotContain("12,65,22");
    }
}
