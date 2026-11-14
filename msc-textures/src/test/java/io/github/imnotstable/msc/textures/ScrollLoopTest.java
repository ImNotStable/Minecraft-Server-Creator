package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The default scroll is one full loop (#534). */
class ScrollLoopTest {

    /** Every pixel a different colour, so two frames are equal only when their offsets are. */
    private static Texture distinct(int size) {
        Texture t = Texture.square(size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                t.set(x, y, 0xFF000000 | (y * size + x));
            }
        }
        return t;
    }

    private static Texture shifted(Texture t, int dx, int dy) {
        Texture out = Texture.square(t.width());
        for (int y = 0; y < t.height(); y++) {
            for (int x = 0; x < t.width(); x++) {
                out.set(x, y, t.getWrapped(x - dx, y - dy));
            }
        }
        return out;
    }

    @Test
    void theDefaultScrollIsOneFullLoopOfDistinctFrames() {
        for (int size = 1; size <= 16; size++) {
            Texture base = distinct(size);
            for (int dx = -size - 1; dx <= size + 1; dx++) {
                for (int dy = -size - 1; dy <= size + 1; dy++) {
                    int loop = Animation.scrollLoop(size, dx, dy);
                    if (loop == 1) {
                        int fdx = dx;
                        int fdy = dy;
                        assertThatThrownBy(() -> Animation.scroll(base, fdx, fdy, 2)).hasMessageContaining("moves nothing");
                        continue;
                    }
                    List<Texture> frames = Animation.scroll(base, dx, dy, 2).frames();
                    String at = size + "x" + size + " step " + dx + "," + dy;
                    assertThat(frames).as(at).hasSize(loop);
                    assertThat(shifted(frames.getLast(), dx, dy)).as(at).isEqualTo(frames.getFirst());
                    assertThat(new HashSet<>(frames)).as(at).hasSize(frames.size());
                }
            }
        }
    }

    @Test
    void theLoopIsTheSmallestPeriodForEverySize() {
        for (int size = 1; size <= Texture.MAX_SIZE; size++) {
            for (int dx = -20; dx <= 20; dx++) {
                for (int dy = -20; dy <= 20; dy++) {
                    int n = Animation.scrollLoop(size, dx, dy);
                    assertThat(isPeriod(size, dx, dy, n)).isTrue();
                    // The smallest period divides every period, so n is smallest when no n/p is one.
                    for (int p = 2; p <= n; p++) {
                        if (n % p == 0 && isPrime(p) && isPeriod(size, dx, dy, n / p)) {
                            throw new AssertionError(size + " " + dx + "," + dy + ": " + n / p + " < " + n);
                        }
                    }
                }
            }
        }
    }

    private static boolean isPeriod(int size, int dx, int dy, int n) {
        return Math.floorMod((long) n * dx, size) == 0 && Math.floorMod((long) n * dy, size) == 0;
    }

    private static boolean isPrime(int p) {
        for (int d = 2; d * d <= p; d++) {
            if (p % d == 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    void refusesAStepThatMovesNothingOrALoopOverTheLimit() {
        Texture t = distinct(16);
        assertThat(Animation.scroll(t, 1, 2, 2).frames()).hasSize(16);
        assertThatThrownBy(() -> Animation.scroll(t, 0, 0, 2)).hasMessage(
                "scroll 0,0 moves nothing on 16x16: give a step that is not a multiple of 16");
        assertThatThrownBy(() -> Animation.scroll(t, 16, -32, 8, 2)).hasMessageContaining("moves nothing");
        Texture big = Texture.square(128);
        assertThatThrownBy(() -> Animation.scroll(big, 1, 0, 2)).hasMessage(
                "scroll 1,0 loops after 128 frames on 128x128, over the 64 limit:"
                        + " give frames (at most 64) or a step that loops within 64 frames");
        assertThat(Animation.scroll(big, 1, 0, 64, 2).frames()).hasSize(64);
        assertThat(Animation.scroll(big, 2, 0, 2).frames()).hasSize(64);
    }
}
