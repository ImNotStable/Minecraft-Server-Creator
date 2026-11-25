package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AtmosphereReviewTest {
    private static final Map<String, Integer> BIG = Map.of("stone_bricks", 400);

    /** One row of blocks from the legend {@code a=...}. */
    private static BlockGrid row(String legend, String row) {
        return TextGrid.parse("legend: " + legend + "\ny=0\n" + row, "world", new BlockPos(0, 64, 0));
    }

    private static AtmosphereReview.Result review(BlockGrid grid, Map<String, Integer> built) {
        return AtmosphereReview.review(grid, built, b -> b.contains("lantern") || b.contains("torch"));
    }

    @Test
    void aBigBuildWhereNothingSoundsOrMovesIsStill() {
        AtmosphereReview.Result r = review(row("s=stone_bricks", "sss"), BIG);
        assertThat(r.line()).isEqualTo("atmosphere: 0 sounding and 0 moving blocks, 0 light kinds");
        assertThat(r.fixes()).singleElement().asString().startsWith("still: no block here makes a sound or moves");
        assertThat(review(row("s=stone_bricks", "sss"), Map.of("stone_bricks", 299)).fixes()).isEmpty();
    }

    @Test
    void litThingsAndFlowingWaterCountButUnlitAndStillOnesDoNot() {
        assertThat(review(row("c=campfire w=water[level=0] d=white_candle[lit=false] f=furnace", "cwdf"), BIG)
                .line()).startsWith("atmosphere: 1 sounding and 1 moving");
        assertThat(review(row("c=campfire[lit=false] w=water[level=3] d=white_candle[lit=true]", "cwd"), BIG)
                .line()).startsWith("atmosphere: 1 sounding and 1 moving");
        assertThat(review(row("c=campfire[lit=false] w=water", "cw"), BIG).fixes()).hasSize(1);
    }

    @Test
    void manyLightsOfOneKindFailAndTorchesOnWallsAreTheSameKind() {
        AtmosphereReview.Result one = review(row("t=torch w=wall_torch", "tw"),
                Map.of("stone_bricks", 400, "torch", 4, "wall_torch", 4));
        assertThat(one.line()).endsWith("1 light kind");
        assertThat(one.fixes()).singleElement().asString().startsWith("one light: all 8 lights are torch;");
        AtmosphereReview.Result two = review(row("t=torch", "t"),
                Map.of("stone_bricks", 400, "torch", 4, "lantern", 4));
        assertThat(two.line()).endsWith("2 light kinds");
        assertThat(two.fixes()).isEmpty();
    }
}
