package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColorsAndRampTest {
    @Test
    void parsesAndFormatsColors() {
        assertThat(Colors.parse("#d4263a")).isEqualTo(0xFFD4263A);
        assertThat(Colors.parse("f00")).isEqualTo(0xFFFF0000);
        assertThat(Colors.parse("#00ff0080")).isEqualTo(0x8000FF00);
        assertThat(Colors.parse("transparent")).isZero();
        assertThat(Colors.format(0x8000FF00)).isEqualTo("#00ff0080");
        assertThatThrownBy(() -> Colors.parse("#12")).hasMessageContaining("bad color");
    }

    @Test
    void hslRoundTrips() {
        for (int c : new int[] {0xFFD4263A, 0xFF7F7F7F, 0xFF22AA44, 0xFF1020F0}) {
            double[] hsl = Colors.toHsl(c);
            assertThat(Colors.fromHsl(hsl[0], hsl[1], hsl[2], 0xFF)).isEqualTo(c);
        }
    }

    @Test
    void rampGoesDarkToLightAndKeepsBaseInTheMiddle() {
        Ramp ramp = Ramp.from(0xFFD4263A, 5);
        assertThat(ramp.size()).isEqualTo(5);
        for (int i = 1; i < 5; i++) {
            assertThat(Colors.luminance(ramp.get(i))).isGreaterThan(Colors.luminance(ramp.get(i - 1)));
        }
        assertThat(ramp.get(2)).isEqualTo(0xFFD4263A);
        assertThat(Ramp.parse(List.of(ramp.toString()))).isEqualTo(ramp);
    }

    @Test
    void rampRejectsSillySizes() {
        assertThatThrownBy(() -> Ramp.from(0xFF000000, 1)).isInstanceOf(TextureException.class);
        assertThatThrownBy(() -> Ramp.from(0xFF000000, 12)).isInstanceOf(TextureException.class);
    }
}
