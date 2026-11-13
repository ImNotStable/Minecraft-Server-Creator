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
