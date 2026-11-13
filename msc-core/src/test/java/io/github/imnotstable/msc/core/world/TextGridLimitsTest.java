package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TextGridLimitsTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    @Test
    @Timeout(5)
    void hugeLayerRangesAreRejectedBeforeAnythingIsBuilt() {
        assertThatThrownBy(() -> TextGrid.parse("legend: #=stone\ny=0..2000000000 all #", "world", ORIGIN))
                .isInstanceOf(ToolException.class).hasMessageContaining("more than 4096 layers");
        assertThatThrownBy(() -> TextGrid.parse("legend: #=stone\ny=0..2147483647 all #", "world", ORIGIN))
                .isInstanceOf(ToolException.class).hasMessageContaining("more than 4096 layers");
        assertThatThrownBy(() -> TextGrid.parse("legend: #=stone\ny=0\n#\ny=1..4096 same as y=0", "world", ORIGIN))
                .isInstanceOf(ToolException.class).hasMessageContaining("more than 4096 layers");
    }

    @Test
    @Timeout(5)
    void layersEndingAtTheLargestIntDoNotWrapAround() {
        assertThat(TextGrid.parse("legend: #=stone\ny=2147483647\n#", "world", ORIGIN).region().sizeY())
                .isEqualTo(1);
        assertThat(TextGrid.parse("legend: #=stone\ny=2147483645\n#\ny=2147483646..2147483647 same as y=2147483645",
                "world", ORIGIN).region().sizeY()).isEqualTo(3);
    }

    @Test
    void overlongLayerNumbersAreToolErrors() {
        assertThatThrownBy(() -> TextGrid.parse("legend: #=stone\ny=99999999999\n#", "world", ORIGIN))
                .isInstanceOf(ToolException.class).hasMessageContaining("layer number 99999999999 is too large");
    }

    @Test
    void rangesWithinTheLimitStillWork() {
        assertThat(TextGrid.parse("legend: #=stone\ny=0\n#\ny=1..4095 same as y=0", "world", ORIGIN).region().sizeY())
                .isEqualTo(4096);
    }

    @Test
    @Timeout(10)
    void repeatedRowsBeyondTheVolumeLimitAreRejectedEarly() {
        String text = "legend: #=stone\ny=0\n" + "# (x9999)\n".repeat(200_000);
        assertThatThrownBy(() -> TextGrid.parse(text, "world", ORIGIN))
                .isInstanceOf(ToolException.class).hasMessageContaining("rows; split it");
    }
}
