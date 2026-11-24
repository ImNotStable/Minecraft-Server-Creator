package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Items in one expression; strings are MiniMessage without the default italics.
 *
 * <pre>{@code
 * ItemStack sword = ItemBuilder.of(Material.DIAMOND_SWORD).name("<red>Slayer").lore("<gray>Kills: <k>", "k", 0)
 *         .glow().tag(KIT_TAG, "slayer").build();
 * }</pre>
 */
public final class ItemBuilder {
    private final ItemStack.Builder builder;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(ItemStack.Builder builder) {
        this.builder = builder;
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(ItemStack.builder(material));
    }

    /** {@code material} with no name and no tooltip, for menu borders and backgrounds you choose. */
    public static ItemStack pane(Material material) {
        return ItemStack.builder(material).customName(Component.empty()).hideExtraTooltip().build();
    }

    public ItemBuilder amount(int amount) {
        builder.amount(amount);
        return this;
    }

    public ItemBuilder name(String miniMessage, Object... placeholders) {
        return name(Text.item(miniMessage, placeholders));
    }

    public ItemBuilder name(Component name) {
        builder.customName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        return this;
    }

    /** One lore line, placeholders as in {@link Text#mm}. */
    public ItemBuilder lore(String miniMessage, Object... placeholders) {
        lore.add(Text.item(miniMessage, placeholders));
        return this;
    }

    public ItemBuilder glow() {
        builder.glowing();
        return this;
    }

    public ItemBuilder hideTooltip() {
        builder.hideExtraTooltip();
        return this;
    }

    /** The model from a resource pack ({@code namespace:path}). */
    public ItemBuilder model(String model) {
        builder.itemModel(model);
        return this;
    }

    /** Stores a value on the item, read back with {@link #read}. */
    public <T> ItemBuilder tag(Tag<T> tag, T value) {
        builder.set(tag, value);
        return this;
    }

    /** The rest of Minestom's builder (data components). */
    public ItemStack.Builder raw() {
        return builder;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) {
            builder.lore(lore);
        }
        return builder.build();
    }

    public static <T> T read(ItemStack item, Tag<T> tag) {
        return item.getTag(tag);
    }
}
