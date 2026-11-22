package io.github.imnotstable.msc.stdlib.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WeightedRandomTest {
    @Test
    void picksByWeight() {
        WeightedRandom<String> w = new WeightedRandom<String>().add("rare", 1).add("common", 9);
        Random r = new Random(7);
        int rare = 0;
        for (int i = 0; i < 10_000; i++) {
            rare += w.pick(r).equals("rare") ? 1 : 0;
        }
        assertThat(rare).isBetween(800, 1200);
        assertThat(w.chance("common")).isCloseTo(0.9, within(1e-9));
    }
}
