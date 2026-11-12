package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class TextTest {
    @Test
    void menuTitlesTurnBareKeysIntoWords() {
        assertThat(Text.title(Component.translatable("entity.minecraft.villager.farmer"))).isEqualTo("Farmer");
        assertThat(Text.title(Component.translatable("container.ender_chest"))).isEqualTo("Ender chest");
        assertThat(Text.title(Component.translatable("x.y", "Shop"))).isEqualTo("Shop");
        assertThat(Text.title(Component.text("Shop"))).isEqualTo("Shop");
    }
}
