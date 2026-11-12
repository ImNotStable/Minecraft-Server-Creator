package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkerTest {
    @Test
    void entitiesAreSortedIntoKindsByType() {
        assertThat(Marker.entity("minecraft:player", "Steve", 0, 0, 0).kind()).isEqualTo(Marker.Kind.PLAYER);
        assertThat(Marker.entity("minecraft:zombie", null, 0, 0, 0).kind()).isEqualTo(Marker.Kind.HOSTILE);
        assertThat(Marker.entity("minecraft:villager", "Shopkeeper", 0, 0, 0).kind()).isEqualTo(Marker.Kind.CREATURE);
        assertThat(Marker.entity("minecraft:item", null, 0, 0, 0).kind()).isEqualTo(Marker.Kind.OBJECT);
        assertThat(Marker.entity("minecraft:spruce_chest_boat", null, 0, 0, 0).kind()).isEqualTo(Marker.Kind.OBJECT);
        assertThat(Marker.entity("minecraft:armor_stand", " ", 0, 0, 0).kind()).isEqualTo(Marker.Kind.OBJECT);
        assertThat(Marker.entity("myplugin:ghost", null, 0, 0, 0).kind()).isEqualTo(Marker.Kind.OTHER);
    }

    @Test
    void hologramsKeepTheirLinesAndLabelsStayShort() {
        Marker display = Marker.entity("minecraft:text_display", "Welcome\nto Spawn", 1.5, 65, 2.5);
        assertThat(display.kind()).isEqualTo(Marker.Kind.TEXT);
        assertThat(display.text()).containsExactly("Welcome", "to Spawn");
        assertThat(display.label()).isEqualTo("text_display \"Welcome to Spawn\"");
        assertThat(Marker.entity("minecraft:armor_stand", "Shop", 0, 0, 0).kind()).isEqualTo(Marker.Kind.TEXT);
        assertThat(Marker.entity("minecraft:cow", "x".repeat(60), 0, 0, 0).label()).hasSize(4 + 2 + 39 + 3);
        assertThat(Marker.text(0, 0, 0, "Hello").label()).isEqualTo("text \"Hello\"");
    }

    @Test
    void signsDropBlankLinesAndSitAtTheBlockCentre() {
        Marker sign = Marker.sign(3, 64, -2, List.of("", "Hello", " ", "There"));
        assertThat(sign.text()).containsExactly("Hello", "There");
        assertThat(sign.label()).isEqualTo("sign \"Hello / There\"");
        assertThat(List.of(sign.x(), sign.y(), sign.z())).containsExactly(3.5, 64.5, -1.5);
        assertThat(Marker.sign(0, 0, 0, List.of("", "")).label()).isEqualTo("sign (blank)");
    }

    @Test
    void imageLinesLoseFormattingAndAreCapped() {
        assertThat(WorldRenderer.textLines(List.of("§aGreen  text", "", "a much longer line than twenty", "3", "4", "5")))
                .containsExactly("Green text", "a much longer line..", "3", "4 ...");
    }

    @Test
    void theFontHasLettersAndPunctuation() {
        assertThat("Hello, World! 1-2 (ok)?".chars()).allMatch(c -> PixelFont.known((char) c.intValue()));
        assertThat(PixelFont.known('é')).isFalse();
        int[] lower = new int[20 * 7];
        int[] upper = new int[20 * 7];
        PixelFont.draw(lower, 20, 7, 0, 0, "abc", 1, 1);
        PixelFont.draw(upper, 20, 7, 0, 0, "ABC", 1, 1);
        assertThat(lower).isEqualTo(upper).contains(1);
    }
}
