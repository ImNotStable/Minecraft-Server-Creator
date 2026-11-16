package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnimationTest {
    @Test
    void scrollMakesAStripThatSplitsBack() {
        Texture lava = Patterns.noise(16, Ramp.from(0xFFE0601A, 4), 1);
        Animation a = Animation.scroll(lava, 0, 1, 16, 2);
        Texture strip = a.strip();
        assertThat(strip.width()).isEqualTo(16);
        assertThat(strip.height()).isEqualTo(256);
        assertThat(strip.get(3, 16 + 5)).isEqualTo(lava.get(3, 4));
        assertThat(Animation.fromStrip(strip, 2, false)).isEqualTo(a);
        assertThat(a.mcmeta().toString()).isEqualTo("{\"animation\":{\"frametime\":2}}");
    }

    @Test
    void pulseOnlyChangesRampColors() {
        Ramp ore = Ramp.from(0xFF30D060, 4);
        Texture t = Patterns.ore(16, Ramp.from(0xFF7F7F7F, 4), ore, 3);
        Animation a = Animation.pulse(t, ore, 8, 3);
        assertThat(a.frames()).hasSize(8);
        assertThat(a.interpolate()).isTrue();
        for (Texture f : a.frames()) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    boolean isOre = ore.colors().contains(t.get(x, y));
                    assertThat(isOre ? ore.colors().contains(f.get(x, y)) : f.get(x, y) == t.get(x, y)).isTrue();
                }
            }
        }
        assertThat(a.frames().get(0)).isNotEqualTo(a.frames().get(2));
    }

    @Test
    void pulseRefusesATextureItCannotChange() {
        // #545: one flat colour (or no pixel in the ramp) gave 8 identical frames and no glow.
        Texture flat = Texture.square(16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                flat.set(x, y, 0xFF30D060);
            }
        }
        assertThatThrownBy(() -> Animation.pulse(flat, new Ramp(java.util.List.of(0xFF30D060)), 8, 2))
                .isInstanceOf(TextureException.class).hasMessageContaining("pulse changes no pixel");
        assertThatThrownBy(() -> Animation.pulse(flat, Ramp.from(0xFF3060E0, 4), 8, 2))
                .hasMessageContaining("pulse changes no pixel");
    }

    @Test
    void rejectsBadCountsBeforeBuildingFrames() {
        Texture big = Texture.square(512);
        Ramp ramp = Ramp.from(0xFF3060E0, 4);
        assertThatThrownBy(() -> Animation.scroll(big, 0, 1, Integer.MAX_VALUE, 2)).hasMessageContaining("1 to 64 frames");
        assertThatThrownBy(() -> Animation.pulse(big, ramp, Integer.MAX_VALUE, 2)).hasMessageContaining("1 to 64 frames");
        assertThatThrownBy(() -> Animation.scroll(big, 0, 1, 65, 2)).hasMessageContaining("not 65");
        assertThatThrownBy(() -> Animation.pulse(big, ramp, 0, 2)).hasMessageContaining("not 0");
        assertThatThrownBy(() -> Animation.scroll(big, 0, 1, 64, 0)).hasMessageContaining("frametime");
        assertThatThrownBy(() -> Animation.fromStrip(new Texture(1, 65), 2, false)).hasMessageContaining("not 65");
    }

    @Test
    void rejectsMixedFrameSizes() {
        assertThatThrownBy(() -> Animation.fromStrip(new Texture(16, 40), 1, false)).hasMessageContaining("multiple");
    }
}
