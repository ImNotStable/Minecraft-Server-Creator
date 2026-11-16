package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
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

    /**
     * The work is bounded by {@link Edits#MAX_CLUSTERS}, not by the number of distinct colours: merging
     * 65536 colours down to 65535 ends with MAX_CLUSTERS of them, so pair merging started from at most that
     * many clusters. That bound is what keeps quantize from going quadratic; asserting it needs no clock. The
     * timeout only catches a hang (an unbounded merge of 65536 colours runs for hours); a healthy run takes
     * about a second, so it holds however loaded the machine is.
     */
    @Test
    void quantizeAndViewStayBoundedOnManyDistinctColors() {
        Texture small = new Texture(64, 64);
        Texture large = new Texture(256, 256);
        for (int i = 0; i < 256 * 256; i++) {
            int c = 0xFF000000 | i * 2654435 & 0xFFFFFF;
            if (i < 64 * 64) {
                small.set(i % 64, i / 64, c);
            }
            large.set(i % 256, i / 256, c);
        }
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            assertThat(Edits.quantize(large, large.colors().size() - 1).colors()).hasSize(Edits.MAX_CLUSTERS);
            assertThat(PaletteText.view(small)).startsWith("4096 colors merged to ");
            assertThat(Edits.quantize(large, 16).colors()).hasSize(16);
            Texture glass = large.copy();
            for (int i = 0; i < 256 * 256; i++) {
                glass.set(i % 256, i / 256, (1 + i % 254) << 24 | large.get(i % 256, i / 256) & 0xFFFFFF);
            }
            assertThat(PaletteText.view(glass)).startsWith("65536 colors merged to ");
        });
    }

    @Test
    void quantizeReturnsTheRequestedColorCountForManyColors() {
        Texture random = new Texture(32, 32);
        Random r = new Random(7);
        for (int i = 0; i < 1024; i++) {
            random.set(i % 32, i / 32, 0xFF000000 | r.nextInt(0x1000000));
        }
        assertThat(Edits.quantize(random, 72).colors()).hasSize(72);
        assertThat(Edits.quantize(random, 200).colors()).hasSize(200);
        assertThat(Edits.quantize(random, 256).colors()).hasSize(256);
        Texture near = new Texture(20, 15);
        for (int i = 0; i < 300; i++) {
            near.set(i % 20, i / 20, 0xFF000000 | (100 + i % 10) << 16 | (100 + i / 10 % 10) << 8 | 100 + i / 100);
        }
        assertThat(near.colors()).hasSize(300);
        assertThat(Edits.quantize(near, 72).colors()).hasSize(72);
        assertThat(Edits.quantize(near, 280).colors()).hasSize(Edits.MAX_CLUSTERS);
    }

    @Test
    void quantizeKeepsAlphaAndHonoursMaxOnAnySize() {
        int[][] sizes = {{1, 1}, {2, 1}, {3, 7}, {16, 16}, {64, 64}, {128, 32}};
        for (int seed = 0; seed < sizes.length; seed++) {
            Random r = new Random(seed);
            Texture t = new Texture(sizes[seed][0], sizes[seed][1]);
            for (int y = 0; y < t.height(); y++) {
                for (int x = 0; x < t.width(); x++) {
                    int alpha = switch (r.nextInt(4)) {
                        case 0 -> 0;
                        case 1 -> 1 + r.nextInt(254);
                        default -> 255;
                    };
                    t.set(x, y, alpha << 24 | r.nextInt(0x1000000));
                }
            }
            for (int max : new int[] {1, 5, 72, 300}) {
                Texture q = Edits.quantize(t, max);
                assertThat(Edits.quantize(t, max)).isEqualTo(q);
                Set<Integer> rgb = new HashSet<>();
                for (int y = 0; y < t.height(); y++) {
                    for (int x = 0; x < t.width(); x++) {
                        int in = t.get(x, y);
                        int out = q.get(x, y);
                        assertThat(out >>> 24).isEqualTo(in >>> 24);
                        if (Colors.opaque(in)) {
                            rgb.add(out & 0xFFFFFF);
                        } else {
                            assertThat(out).isEqualTo(in);
                        }
                    }
                }
                Set<Integer> rgbIn = new HashSet<>();
                t.colors().forEach(c -> rgbIn.add(c & 0xFFFFFF));
                int expected = Math.min(rgbIn.size(), t.colors().size() <= max ? Integer.MAX_VALUE : Math.min(max, Edits.MAX_CLUSTERS));
                assertThat(rgb).hasSize(expected);
            }
        }
    }
}
