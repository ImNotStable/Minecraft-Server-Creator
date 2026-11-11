package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TickRateTest {
    private static final long MS = 1_000_000L;

    @Test
    void measuresTicksPerSecondCappedAtTheTarget() {
        TickRate rate = new TickRate(20);
        assertThat(rate.tps(0)).isEqualTo(-1);

        for (int i = 0; i <= 100; i++) {
            rate.tick(i * 100 * MS); // 10 ticks per second
        }
        assertThat(rate.tps(10_000 * MS)).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));

        TickRate fast = new TickRate(20);
        for (int i = 0; i <= 100; i++) {
            fast.tick(i * 10 * MS); // 100 ticks per second
        }
        assertThat(fast.tps(1000 * MS)).isEqualTo(20.0);
    }

    @Test
    void onlyCountsTheLastMinute() {
        TickRate rate = new TickRate(20);
        rate.tick(0);
        rate.tick(50 * MS);
        for (int i = 0; i <= 20; i++) {
            rate.tick(120_000 * MS + i * 50 * MS);
        }
        assertThat(rate.tps(121_000 * MS)).isEqualTo(20.0);
        assertThat(rate.tps(400_000 * MS)).isEqualTo(-1);
    }
}
