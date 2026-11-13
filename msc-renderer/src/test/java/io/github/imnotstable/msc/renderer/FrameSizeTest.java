package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Every frame computation goes through one size clamp, so no image is a pixel over its budget. */
class FrameSizeTest {
    private final WorldRenderer renderer = new WorldRenderer();

    @Test
    void framesNeverRoundPastTheirBudget() {
        // #344: ceil(w * (1568 / w)) is 1569 for some widths.
        for (int w = 1; w <= 3000; w++) {
            double width = w * Math.sqrt(0.5) + 0.1 * w;
            assertThat(WorldRenderer.side(width * (1568 / width), 1568)).as("width %d", w).isLessThanOrEqualTo(1568);
        }
        assertThat(WorldRenderer.side(0.2, 100)).isEqualTo(1);
        assertThat(WorldRenderer.side(10.5, 100)).isEqualTo(11);
        for (int s : new int[] {30, 46, 50}) {
            Scenes scene = new Scenes(0, 0, 0, s - 1, 63, s - 1).fill(0, 0, 0, s - 1, 0, s - 1, "stone");
            RenderResult result = renderer.render(scene.grid(), View.parse("iso"),
                    new RenderOptions(1568, false, false, true, false));
            assertThat(Math.max(result.width(), result.height())).as("%dx64x%d", s, s).isLessThanOrEqualTo(1568);
        }
    }
}
