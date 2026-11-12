package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroundLevelTest {
    @Test
    void groundIsTheMostCommonHeightNotWhatWasBuiltOnIt() {
        // A tower at 0,0 on superflat ground.
        assertThat(LifecycleTools.groundLevel(new int[] {120, 118, -61, -61, -61, 100})).isEqualTo(-61);
        assertThat(LifecycleTools.groundLevel(new int[] {70, 64, 64, 70})).isEqualTo(64);
        assertThat(LifecycleTools.groundLevel(new int[] {5})).isEqualTo(5);
    }
}
