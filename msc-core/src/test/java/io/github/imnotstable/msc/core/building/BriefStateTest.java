package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BriefStateTest {
    @Test
    void theDefaultWaterloggedGoesWhereverItStands() {
        assertThat(Builder.brief("spruce_slab[type=bottom,waterlogged=false]")).isEqualTo("spruce_slab[type=bottom]");
        assertThat(Builder.brief("oak_stairs[waterlogged=false,facing=north]")).isEqualTo("oak_stairs[facing=north]");
        assertThat(Builder.brief("lantern[waterlogged=false]")).isEqualTo("lantern");
    }

    @Test
    void everythingElseStays() {
        assertThat(Builder.brief("spruce_slab[type=top,waterlogged=true]")).isEqualTo("spruce_slab[type=top,waterlogged=true]");
        assertThat(Builder.brief("stone")).isEqualTo("stone");
        assertThat(Builder.brief("stripped_spruce_log[axis=y]")).isEqualTo("stripped_spruce_log[axis=y]");
    }
}
