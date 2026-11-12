package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GeneratorsTest {
    private static final Ramp RUBY = Ramp.from(0xFFD4263A, 5);
    private static final Ramp STONE = Ramp.from(0xFF7F7F7F, 4);

    @Test
    void rubyGemMeetsTheBenchRules() {
        Texture gem = Shapes.draw("gem", RUBY, null);
        assertThat(BenchRules.rubyProblems(gem)).isEmpty();
        assertThat(Stats.of(gem).toString()).contains("16x16", "red");
    }

    @Test
    void everyShapeUsesOnlyRampColorsAndHasATransparentBackground() {
        for (String name : Shapes.NAMES) {
            Texture t = Shapes.draw(name, RUBY, 0xFF6B4A28, null);
            java.util.Set<Integer> allowed = new java.util.HashSet<>(RUBY.colors());
            allowed.addAll(Ramp.from(0xFF6B4A28, 3).colors());
            assertThat(allowed).as(name).containsAll(t.colors());
            assertThat(Stats.of(t).opaqueFraction()).isBetween(0.2, 0.85);
            assertThat(BenchRules.rubyProblems(t)).as(name).isEmpty();
        }
        assertThat(Shapes.draw("sword", RUBY, null).colors()).as("wooden handle by default")
                .contains(Ramp.from(0xFF6B4A28, 3).get(1));
        assertThat(Shapes.draw("food", RUBY, null).colors()).as("bone by default")
                .contains(Ramp.from(0xFFE8DCC0, 3).get(2));
        Texture outlined = Shapes.draw("ingot", Ramp.from(0xFFE8B830, 4), 0xFF000000);
        assertThat(outlined.get(4, 5)).isEqualTo(0xFF000000);
    }

    @Test
    void patternsAreDeterministicTileableAndUseTheRamp() {
        for (String name : Patterns.NAMES) {
            Texture a = Patterns.generate(name, 16, STONE, null, 7);
            Texture b = Patterns.generate(name, 16, STONE, null, 7);
            assertThat(a).as(name).isEqualTo(b);
            assertThat(a.opaqueCount()).isEqualTo(256);
            assertThat(a.colors().size()).as(name).isBetween(3, 12);
        }
        assertThat(Patterns.noise(16, STONE, 1)).isNotEqualTo(Patterns.noise(16, STONE, 2));
        assertThat(STONE.colors()).containsAll(Patterns.noise(32, STONE, 3).colors());
    }

    @Test
    void noiseUsesEveryShadeWithMiddleShadesMostCommon() {
        Texture t = Patterns.noise(16, STONE, 4);
        int[] counts = new int[STONE.size()];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                counts[STONE.colors().indexOf(t.get(x, y))]++;
            }
        }
        assertThat(counts).doesNotContain(0);
        assertThat(counts[1] + counts[2]).isGreaterThan(counts[0] + counts[3]);
    }

    @Test
    void oreHasClustersOfOreColor() {
        Texture t = Patterns.ore(16, STONE, RUBY, 5);
        long ore = t.colors().stream().filter(RUBY.colors()::contains).count();
        assertThat(ore).isGreaterThanOrEqualTo(2);
        int orePixels = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (RUBY.colors().contains(t.get(x, y))) {
                    orePixels++;
                }
            }
        }
        assertThat(orePixels).isBetween(9, 80);
    }

    @Test
    void unknownNamesListTheChoices() {
        assertThatThrownBy(() -> Patterns.generate("marble", 16, STONE, null, 0)).hasMessageContaining("noise, ore");
        assertThatThrownBy(() -> Shapes.draw("shield", RUBY, null)).hasMessageContaining("gem, ingot, orb, sword");
        assertThatThrownBy(() -> Patterns.noise(20, STONE, 0)).hasMessageContaining("16, 32 or 64");
    }
}
