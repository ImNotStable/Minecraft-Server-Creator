package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaletteTextTest {
    @Test
    void parseAndRenderRoundTrip() {
        Texture t = PaletteText.parse(List.of("a=#3a0a12 b=#d4263a"), List.of(".ab.", "abba", "abba", ".ab."));
        assertThat(t.get(0, 0)).isZero();
        assertThat(t.get(1, 1)).isEqualTo(0xFFD4263A);
        String text = PaletteText.render(t);
        assertThat(text).startsWith("4x4\npalette: .=transparent a=#3a0a12 b=#d4263a\n.ab.");
        List<String> lines = List.of(text.split("\n"));
        assertThat(PaletteText.parse(List.of(lines.get(1)), lines.subList(2, lines.size()))).isEqualTo(t);
    }

    @Test
    void viewMergesColorsBeyondTheSymbolsSoAnyTextureCanBeShown() {
        Texture t = new Texture(16, 16);
        for (int i = 0; i < 73; i++) {
            t.set(i % 16, i / 16, 0xFF000000 | i * 3 << 16 | i << 8);
        }
        assertThatThrownBy(() -> PaletteText.render(t)).hasMessageContaining("more than 72 colors");
        String text = PaletteText.view(t);
        assertThat(text).startsWith("73 colors merged to 72 to show them; the file is unchanged\n16x16\n");
        assertThat(PaletteText.view(PaletteText.parse(List.of("a=#3a0a12"), List.of("a.")))).isEqualTo(
                "2x1\npalette: .=transparent a=#3a0a12\na.");
    }

    @Test
    void viewShowsATextureWithManyPartialAlphaValues() {
        Texture t = new Texture(16, 16);
        for (int i = 0; i < 256; i++) {
            t.set(i % 16, i / 16, i << 24 | 0xFF0000);
        }
        String text = PaletteText.view(t);
        assertThat(text).startsWith("255 colors merged to ");
        String palette = text.split("\n")[2];
        assertThat(palette.split(" ").length - 2).isBetween(2, 72);
        assertThat(text.split("\n")[3]).startsWith(".a");
    }

    @Test
    void reportsBadInputInOneLine() {
        assertThatThrownBy(() -> PaletteText.parse(List.of("a=#000"), List.of("aa", "a")))
                .hasMessage("row 2 has 1 pixels, expected 2");
        assertThatThrownBy(() -> PaletteText.parse(List.of("a=#000"), List.of("ab")))
                .hasMessageContaining("symbol 'b'");
        assertThatThrownBy(() -> PaletteText.parse(List.of("a=#000"), List.of("", "")))
                .hasMessage("texture size 0x2 is out of range");
    }

    @Test
    void pngRoundTrip() {
        Texture t = Shapes.draw("gem", Ramp.from(0xFF2AB0D4, 5), null);
        assertThat(Texture.readPng(t.toPng())).isEqualTo(t);
    }

    @Test
    void viewKeepsHuesWhenATextureHasManyHuesAndManyAlphaLevels() {
        Texture t = new Texture(16, 16);
        for (int i = 0; i < 256; i++) {
            t.set(i % 16, i / 16, Colors.fromHsl(i * 360.0 / 256, 0.8, 0.5, 100 + i % 40));
        }
        String text = PaletteText.view(t);
        assertThat(rgbColors(text)).hasSizeGreaterThanOrEqualTo(36);
    }

    @Test
    void viewFitsTheSymbolsAndKeepsTransparencyOnAnySize() {
        int[][] sizes = {{1, 1}, {9, 9}, {16, 16}, {32, 32}, {64, 16}};
        for (int seed = 0; seed < sizes.length; seed++) {
            for (int alphaKinds : new int[] {1, 3, 200}) {
                Random r = new Random(seed * 7L + alphaKinds);
                Texture t = new Texture(sizes[seed][0], sizes[seed][1]);
                for (int y = 0; y < t.height(); y++) {
                    for (int x = 0; x < t.width(); x++) {
                        int alpha = r.nextInt(5) == 0 ? 0 : 255 - r.nextInt(alphaKinds);
                        t.set(x, y, alpha << 24 | r.nextInt(0x1000000));
                    }
                }
                String text = PaletteText.view(t);
                assertThat(PaletteText.view(t)).isEqualTo(text);
                List<String> lines = List.of(text.split("\n"));
                int skip = lines.getFirst().contains("merged") ? 1 : 0;
                List<String> rows = lines.subList(skip + 2, lines.size());
                Texture shown = PaletteText.parse(List.of(lines.get(skip + 1)), rows);
                assertThat(shown.colors().size()).isBetween(Math.min(1, t.colors().size()), PaletteText.SYMBOLS.length())
                        .isLessThanOrEqualTo(t.colors().size());
                for (int y = 0; y < t.height(); y++) {
                    for (int x = 0; x < t.width(); x++) {
                        assertThat(Colors.opaque(shown.get(x, y))).isEqualTo(Colors.opaque(t.get(x, y)));
                    }
                }
                if (t.colors().size() > PaletteText.SYMBOLS.length() && alphaKinds == 1) {
                    assertThat(shown.colors()).hasSize(PaletteText.SYMBOLS.length());
                }
            }
        }
    }

    private static Set<Integer> rgbColors(String view) {
        Set<Integer> rgb = new HashSet<>();
        for (String e : view.split("\n")[2].split(" ")) {
            if (e.length() > 2 && e.charAt(2) == '#') {
                rgb.add(Colors.parse(e.substring(2)) & 0xFFFFFF);
            }
        }
        return rgb;
    }
}
