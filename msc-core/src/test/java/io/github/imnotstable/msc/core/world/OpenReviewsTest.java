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
    void aPassingReviewOfAPartLeavesTheWholeFailing() {
        OpenReviews open = new OpenReviews();
        open.record(Region.of("msc:a", 0, 60, 0, 100, 90, 100), FAILED);
        open.record(Region.of("msc:a", 0, 60, 0, 10, 70, 10), "review: 10 blocks\nno number below the bar");
        assertThat(open.line()).contains("msc:a 0,60,0 100,90,100");
    }
}
