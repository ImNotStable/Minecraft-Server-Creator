package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EditsTest {
    @Test
    void recolorTurnsADiamondIntoARubyKeepingShading() {
        Texture diamond = Shapes.draw("gem", Ramp.from(0xFF4AE0D8, 5), null);
        Texture ruby = Edits.recolor(diamond, Ramp.from(0xFFD4263A, 5));
        assertThat(BenchRules.rubyProblems(ruby)).isEmpty();
        assertThat(ruby.opaqueCount()).isEqualTo(diamond.opaqueCount());
        // The darkest pixel stays the darkest.
        assertThat(ruby.get(4, 2)).isEqualTo(Ramp.from(0xFFD4263A, 5).darkest());
    }

    @Test
    void outlineSurroundsOpaquePixels() {
        Texture t = Texture.square(5);
        t.set(2, 2, 0xFFFFFFFF);
        Texture out = Edits.outline(t, 0xFF000000);
        assertThat(out.opaqueCount()).isEqualTo(5);
        assertThat(out.get(2, 1)).isEqualTo(0xFF000000);
        assertThat(out.get(1, 1)).isZero();
    }

    @Test
    void quantizeLimitsColorsAndKeepsTransparency() {
        Texture t = Texture.square(16);
        for (int y = 0; y < 16; y++) {
            for (int x = 1; x < 16; x++) {
                t.set(x, y, 0xFF000000 | (x * 16) << 16 | (y * 16) << 8 | 0x40);
            }
        }
        Texture q = Edits.quantize(t, 6);
        assertThat(q.colors()).hasSize(6);
        assertThat(q.get(0, 0)).isZero();
        assertThat(Edits.quantize(q, 8)).isEqualTo(q);
    }
}
