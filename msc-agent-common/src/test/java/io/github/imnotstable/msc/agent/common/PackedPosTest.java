package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PackedPosTest {
    @Test
    void roundTripsPositionsAcrossTheWorld() {
        int[][] cases = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 2047, -29_999_999}, {-30_000_000, -2048, 12}};
        for (int[] c : cases) {
            long packed = PackedPos.pack(c[0], c[1], c[2]);
            assertThat(new int[]{PackedPos.x(packed), PackedPos.y(packed), PackedPos.z(packed)}).containsExactly(c);
        }
    }
}
