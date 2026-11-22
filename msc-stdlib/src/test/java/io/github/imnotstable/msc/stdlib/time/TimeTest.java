package io.github.imnotstable.msc.stdlib.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeTest {
    static final class TestClock extends Clock {
        Instant now = Instant.EPOCH;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aCooldownBlocksUntilItEnds() {
        TestClock clock = new TestClock();
        Cooldowns<String> c = new Cooldowns<>(clock);
        assertThat(c.tryUse("a", Duration.ofSeconds(5))).isTrue();
        assertThat(c.tryUse("a", Duration.ofSeconds(5))).isFalse();
        assertThat(c.ready("b")).isTrue();
        clock.now = clock.now.plusSeconds(3);
        assertThat(c.remaining("a")).isEqualTo(Duration.ofSeconds(2));
        clock.now = clock.now.plusSeconds(2);
        assertThat(c.tryUse("a", Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void durationsParseAndFormat() {
        assertThat(Durations.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(Durations.parse("1W 2D")).isEqualTo(Duration.ofDays(9));
        assertThat(Durations.parse("20t")).isEqualTo(Duration.ofSeconds(1));
        assertThat(Durations.parse("45")).isEqualTo(Duration.ofSeconds(45));
        assertThat(Durations.parse("1m500ms")).isEqualTo(Duration.ofMillis(60_500));
        assertThatThrownBy(() -> Durations.parse("soon")).hasMessageContaining("soon");
        assertThat(Durations.format(Duration.ofSeconds(3725))).isEqualTo("1h 2m");
        assertThat(Durations.format(Duration.ofMillis(1500))).isEqualTo("2s");
        assertThat(Durations.format(Duration.ZERO)).isEqualTo("0s");
        assertThat(Durations.ticks(Duration.ofSeconds(2))).isEqualTo(40);
    }
}
