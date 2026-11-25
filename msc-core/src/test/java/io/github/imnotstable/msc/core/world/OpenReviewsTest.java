package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

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
    void aPassingReviewOfAPartLeavesTheWholeFailing() {
        OpenReviews open = new OpenReviews();
        open.record(Region.of("msc:a", 0, 60, 0, 100, 90, 100), FAILED);
        open.record(Region.of("msc:a", 0, 60, 0, 10, 70, 10), "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).contains("msc:a 0,60,0 100,90,100");
    }
}
