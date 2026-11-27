package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import org.junit.jupiter.api.Test;

class OpenReviewsTest {
    private static final String FAILED = "review: 10 blocks\nverdict: NOT DONE, 2 checks below the bar\nfix first: "
            + "1) bare base: add a plinth (trim at=base); 2) floating: 3 blocks (at 1,2,3; 4,5,6); then review this "
            + "region again";

    @Test
    void aFailedReviewStaysListedUntilTheSameRegionPasses() {
        OpenReviews open = new OpenReviews();
        Region hut = Region.of("msc:a", 0, 60, 0, 10, 70, 10);
        open.record(hut, FAILED);
        open.record(Region.of("msc:b", 0, 60, 0, 10, 70, 10), FAILED);
        assertThat(open.count()).isEqualTo("2 reviews still failing (msc_status lists them): the work is not done");
        assertThat(open.line()).startsWith("reviews still failing (2;")
                .contains("\n- msc:a 0,60,0 10,70,10: bare base, floating");
        // A wider region that passes does not settle it: run 8 widened regions until one passed.
        open.record(Region.of("msc:a", -5, 50, -5, 20, 90, 20), "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).startsWith("reviews still failing (2;");
        open.record(hut, "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).startsWith("reviews still failing (1;").doesNotContain("msc:a");
        open.record(Region.of("msc:b", 0, 60, 0, 10, 70, 10), "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).isEmpty();
    }

    @Test
    void threeFailingReviewsStopNewBuildsButNotFixes() {
        OpenReviews open = new OpenReviews();
        Region elsewhere = Region.of("msc:a", 500, 60, 500, 510, 70, 510);
        for (int i = 0; i < 2; i++) {
            open.record(Region.of("msc:a", i * 100, 60, 0, i * 100 + 10, 70, 10), FAILED);
        }
        assertThat(open.gate(elsewhere)).isNull();
        open.record(Region.of("msc:a", 200, 60, 0, 210, 70, 10), FAILED);
        assertThat(open.gate(elsewhere)).startsWith("nothing placed: 3 reviews still fail")
                .contains("- msc:a 200,60,0 210,70,10: bare base, floating");
        assertThat(open.gate(Region.of("msc:a", 205, 60, 5, 220, 70, 20))).isNull(); // fixing one of them
        assertThat(open.gate(Region.of("msc:b", 205, 60, 5, 220, 70, 20))).isNotNull(); // another world
    }

    @Test
    void anEditAfterAPassReopensTheReviewItTouches() {
        OpenReviews open = new OpenReviews();
        Region house = Region.of("msc:a", 0, 60, 0, 10, 70, 10);
        Region other = Region.of("msc:a", 50, 60, 50, 60, 70, 60);
        open.record(house, "review: 10 blocks\nno number below the bar");
        open.record(other, "review: 10 blocks\nno number below the bar");
        open.edited(Region.of("msc:a", -20, 55, -20, 20, 80, 20)); // landscaping the town around the house
        assertThat(open.line()).contains("- msc:a 0,60,0 10,70,10: edited since it passed").doesNotContain("50,60,50");
        open.record(house, "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).isEmpty();
    }

    @Test
    void aBigBuildNoReviewCoversIsListedUntilReviewed() {
        OpenReviews open = new OpenReviews();
        open.edited(Region.of("msc:a", 0, 60, 0, 10, 62, 30), 120); // a dock deck
        open.edited(Region.of("msc:a", 11, 58, 0, 14, 62, 30), 60); // its posts, touching it: the same build
        open.edited(Region.of("msc:a", 100, 60, 100, 100, 61, 100), 2); // a sign: not a build
        assertThat(open.count()).isEqualTo("1 build never reviewed (msc_status lists them): the work is not done");
        assertThat(open.line()).startsWith("builds never reviewed (1;")
                .contains("- msc:a 0,58,0 14,62,30: never reviewed (180 blocks placed)").doesNotContain("100,60,100");
        open.record(Region.of("msc:a", -2, 55, -2, 16, 70, 32), FAILED);
        assertThat(open.line()).startsWith("reviews still failing (1;").doesNotContain("never reviewed");
        open.edited(Region.of("msc:a", 2, 60, 2, 8, 66, 8), 400); // fixing the failing build
        open.edited(Region.of("msc:a", 40, 60, 0, 50, 70, 10), 300); // a house 26 blocks away
        open.edited(Region.of("msc:a", 52, 60, 0, 60, 70, 10), 300); // its neighbour, a gap of 1 between
        assertThat(open.line()).contains("40,60,0 50,70,10: never reviewed", "52,60,0 60,70,10: never reviewed")
                .doesNotContain("2,60,2");
    }

    @Test
    void theSameFailureThreeTimesWithNoProgressIsAStall() {
        OpenReviews open = new OpenReviews();
        Region house = Region.of("msc:a", 0, 60, 0, 10, 70, 10);
        String bare80 = "review: 10 blocks\nverdict: NOT DONE, 1 check below the bar\nfix first: 1) bare base: 80% of "
                + "the faces; then review this region again";
        String bare60 = bare80.replace("80%", "60%");
        assertThat(open.recordAndCheck(house, bare80)).isEmpty();
        assertThat(open.recordAndCheck(house, bare60)).isEmpty(); // better: not counted
        assertThat(open.recordAndCheck(house, bare60)).isEmpty();
        assertThat(open.recordAndCheck(house, bare60)).startsWith("STALLED: this region has failed 3 reviews in a row "
                + "on the same findings (bare base)");
        assertThat(open.recordAndCheck(house, "review: 10 blocks\nno number below the bar")).isEmpty();
        assertThat(open.recordAndCheck(house, bare60)).isEmpty(); // a fresh streak after a pass
    }

    @Test
    void aPassingReviewOfAPartLeavesTheWholeFailing() {
        OpenReviews open = new OpenReviews();
        open.record(Region.of("msc:a", 0, 60, 0, 100, 90, 100), FAILED);
        open.record(Region.of("msc:a", 0, 60, 0, 10, 70, 10), "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).contains("msc:a 0,60,0 100,90,100");
    }

    private static BlockGrid grid(Region region, String block) {
        int[] blocks = new int[(int) region.volume()];
        return new BlockGrid(region, java.util.List.of(block), blocks);
    }

    /** ADR 0119: a region whose blocks did not change since its last review gets that review back in a few lines. */
    @Test
    void anUnchangedRegionGetsItsLastVerdictInAFewLines() {
        OpenReviews open = new OpenReviews();
        Region hut = Region.of("msc:a", 0, 60, 0, 3, 63, 3);
        int[] reviews = {0};
        java.util.function.Supplier<String> reviewer = () -> {
            reviews[0]++;
            return FAILED;
        };
        assertThat(open.review(hut, grid(hut, "minecraft:stone"), false, reviewer).text()).isEqualTo(FAILED);
        OpenReviews.Checked again = open.review(hut, grid(hut, "minecraft:stone"), false, reviewer);
        assertThat(again.text()).startsWith("UNCHANGED: no block in this region changed")
                .contains("\nverdict: NOT DONE, 2 checks below the bar\nfix first: 1) bare base")
                .doesNotContain("review: 10 blocks");
        assertThat(again.review()).isEqualTo(FAILED);
        assertThat(again.stall()).isEmpty();
        assertThat(reviews[0]).isEqualTo(1);
        // Unchanged re-reviews are not edits that failed: however many, they never make a stall.
        for (int i = 0; i < OpenReviews.STALL; i++) {
            assertThat(open.review(hut, grid(hut, "minecraft:stone"), false, reviewer).stall()).isEmpty();
        }
        // A changed block, or another floor, reviews again.
        open.review(hut, grid(hut, "minecraft:cobblestone"), false, reviewer);
        open.review(hut, grid(hut, "minecraft:cobblestone"), true, reviewer);
        assertThat(reviews[0]).isEqualTo(3);
        assertThat(open.line()).contains("msc:a 0,60,0 3,63,3: bare base, floating");
    }

    @Test
    void anUnchangedPassStillPassesAfterANoOpEditReopenedIt() {
        OpenReviews open = new OpenReviews();
        Region hut = Region.of("msc:a", 0, 60, 0, 3, 63, 3);
        String pass = "review: 10 blocks\nno number below the bar";
        open.review(hut, grid(hut, "minecraft:stone"), false, () -> pass);
        open.edited(hut);
        assertThat(open.line()).contains("edited since it passed");
        assertThat(open.review(hut, grid(hut, "minecraft:stone"), false, () -> pass).text())
                .startsWith("UNCHANGED:").contains("It passed and still passes");
        assertThat(open.line()).isEmpty();
    }

    @Test
    void aSecondReviewWithTheSameFindingsSaysSoBeforeItStalls() {
        OpenReviews open = new OpenReviews();
        Region hut = Region.of("msc:a", 0, 60, 0, 3, 63, 3);
        assertThat(open.review(hut, grid(hut, "minecraft:stone"), false, () -> FAILED).text()).isEqualTo(FAILED);
        OpenReviews.Checked second = open.review(hut, grid(hut, "minecraft:dirt"), false, () -> FAILED);
        assertThat(second.text()).startsWith("SAME FINDINGS: the edits since your last review of this region left "
                + "the same findings (bare base, floating)").endsWith(FAILED);
        assertThat(second.stall()).isEmpty();
        assertThat(open.review(hut, grid(hut, "minecraft:sand"), false, () -> FAILED).stall()).startsWith("STALLED: ");
    }

    /** ADR 0119: an edit whose box reaches over a passed build reopens it only when its blocks changed. */
    @Test
    void anEditBoxOverAnUnchangedPassKeepsIt() {
        OpenReviews open = new OpenReviews();
        Region house = Region.of("msc:a", 0, 60, 0, 3, 63, 3);
        Region shed = Region.of("msc:a", 10, 60, 0, 13, 63, 3);
        String pass = "review: 10 blocks\nno number below the bar";
        BlockGrid stone = grid(house, "minecraft:stone");
        open.review(house, stone, true, () -> pass);
        open.review(shed, grid(shed, "minecraft:stone"), false, () -> pass);
        Region landscaping = Region.of("msc:a", -5, 59, -5, 20, 60, 20);
        assertThat(open.touched(landscaping)).extracting(OpenReviews.Passed::region).containsExactly(house, shed);
        OpenReviews.Passed p = open.touched(landscaping).getFirst();
        assertThat(OpenReviews.print(stone, p.devFloor())).isEqualTo(p.print());
        open.edited(landscaping, 200, java.util.Set.of(house));
        assertThat(open.line()).contains("msc:a 10,60,0 13,63,3: edited since it passed").doesNotContain("0,60,0 3,63,3");
    }

    /** ADR 0119: fewer floating blocks is progress, not a stall, though the number is not a percentage. */
    @Test
    void fewerFloatingBlocksIsNotAStall() {
        OpenReviews open = new OpenReviews();
        Region fields = Region.of("msc:a", 0, 60, 0, 30, 70, 30);
        for (int n : new int[] {80, 80, 20, 3}) {
            String review = "review: 1364 blocks\nverdict: NOT DONE, 1 check below the bar\nfix first: 1) floating: "
                    + n + " blocks in 3 clumps touch nothing (at 1,2,3); then review this region again";
            String stall = open.recordAndCheck(fields, review);
            assertThat(stall).as("%d floating", n).isEmpty();
        }
    }

    /** ADR 0122: blocks changed but the review says exactly what it said: the short answer, not the whole review. */
    @Test
    void theSameResultAfterUnrelatedChangesIsShort() {
        OpenReviews open = new OpenReviews();
        Region hut = Region.of("msc:a", 0, 60, 0, 3, 63, 3);
        String passing = "review: 10 blocks\nno number below the bar";
        assertThat(open.review(hut, grid(hut, "minecraft:stone"), false, () -> passing).text()).isEqualTo(passing);
        assertThat(open.review(hut, grid(hut, "minecraft:dirt"), false, () -> passing).text())
                .startsWith("SAME RESULT: blocks here changed").contains("It passed and still passes");
    }

    /** ADR 0124: the failing list ends with the one call that re-checks them all. */
    @Test
    void theListOffersOneCallForEveryReCheck() {
        OpenReviews open = new OpenReviews();
        open.record(Region.of("msc:a", 0, 60, 0, 10, 70, 10), FAILED);
        open.record(Region.of("msc:a", 20, 60, 0, 30, 70, 10), FAILED);
        assertThat(open.line()).endsWith("\nre-check them in one call: world_summary world=msc:a "
                + "region=\"0,60,0 10,70,10; 20,60,0 30,70,10\" review=true");
    }
}