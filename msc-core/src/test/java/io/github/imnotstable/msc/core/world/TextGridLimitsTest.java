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
    void overlongLayerNumbersAreToolErrors() {
        assertThatThrownBy(() -> TextGrid.parse("legend: #=stone\ny=99999999999\n#", "world", ORIGIN))
                .isInstanceOf(ToolException.class).hasMessageContaining("layer number 99999999999 is too large");
    }

    @Test
    void rangesWithinTheLimitStillWork() {
        assertThat(TextGrid.parse("legend: #=stone\ny=0\n#\ny=1..4095 same as y=0", "world", ORIGIN).region().sizeY())
                .isEqualTo(4096);
    }
}
