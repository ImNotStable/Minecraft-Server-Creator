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

    @Test
    void chatTranslationsReadLikeTheClientShowsThem() {
        Component name = Component.text("MscMatrix");
        Component said = Component.text("matrix hello");
        assertThat(Text.plain(Component.translatable("chat.type.text", name, said))).isEqualTo("<MscMatrix> matrix hello");
        assertThat(Text.plain(Component.translatable("chat.type.announcement", Component.text("Server"), said)))
                .isEqualTo("[Server] matrix hello");
        assertThat(Text.plain(Component.translatable("chat.type.emote", name, Component.text("waves"))))
                .isEqualTo("* MscMatrix waves");
        assertThat(Text.plain(Component.translatable("some.key", name))).isEqualTo("some.key (MscMatrix)");
    }
}
