package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

class ItemsTest {
    /**
     * Every version the bot speaks needs its item table. To make one, run the server's data generator (it starts no
     * server): {@code java -DbundlerMainClass=net.minecraft.data.Main -jar server-<version>.jar --reports}, then
     * write the keys of {@code minecraft:item} in generated/reports/registries.json, ordered by protocol_id and
     * without "minecraft:", one per line, to src/main/resources/io/github/imnotstable/msc/bot/items/<version>.txt.
     */
    @Test
    void everySpokenVersionHasAnItemTable() {
        for (Bots.Speaker speaker : Bots.speakers(java.nio.file.Path.of(System.getProperty("msc.bot.protocols"))).values()) {
            Items items = Items.forVersion(speaker.minecraft());
            assertThat(items.size()).as("items/%s.txt", speaker.minecraft()).isGreaterThan(1000);
            assertThat(items.name(0)).isEqualTo("air");
            assertThat(items.name(1)).isEqualTo("stone");
        }
    }

    @Test
    void describesWhatAPluginGaveTheItem() {
        Items items = Items.forVersion("26.3");
        assertThat(items.describe(new ItemStack(1, 3))).isEqualTo("stone x3");
        DataComponents patch = new DataComponents(new HashMap<>());
        patch.put(DataComponentTypes.CUSTOM_NAME, Component.text("Ember Blade"));
        patch.put(DataComponentTypes.ITEM_MODEL, Key.key("lumen", "ember_blade"));
        patch.put(DataComponentTypes.LORE, List.of(Component.text("Burns"), Component.text("Wave 3 reward")));
        assertThat(items.describe(new ItemStack(1, 1, patch)))
                .isEqualTo("stone \"Ember Blade\" model lumen:ember_blade lore \"Burns / Wave 3 reward\"");
        assertThat(Items.forVersion("1.0").describe(new ItemStack(7, 1))).isEqualTo("item#7");
    }
}
