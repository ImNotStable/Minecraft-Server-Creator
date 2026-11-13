package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
}
