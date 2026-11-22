package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.util.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Items in one expression. Names and lore lose the default italics unless the component sets them.
 *
 * <pre>{@code
 * ItemStack sword = ItemBuilder.of(Items.DIAMOND_SWORD).name(Component.literal("Slayer").withStyle(ChatFormatting.RED))
 *         .lore(Component.literal("Kills: 0")).glow().data("kit", "slayer").build();
 * }</pre>
 */
public final class ItemBuilder {
    private final ItemStack item;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(ItemStack item) {
        this.item = item;
    }

    public static ItemBuilder of(Item item) {
        return new ItemBuilder(new ItemStack(item));
    }

    public static ItemBuilder from(ItemStack stack) {
        return new ItemBuilder(stack.copy());
    }

    /** An unnamed grey pane, for menu backgrounds. */
    public static ItemStack filler() {
        return of(Items.STAINED_GLASS_PANE.gray()).name(Component.empty()).build();
    }

    public ItemBuilder amount(int amount) {
        item.setCount(amount);
        return this;
    }

    public ItemBuilder name(Component name) {
        item.set(DataComponents.CUSTOM_NAME, plain(name));
        return this;
    }

    public ItemBuilder lore(Component... lines) {
        for (Component l : lines) {
            lore.add(plain(l));
        }
        return this;
    }

    public ItemBuilder glow() {
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return this;
    }

    public ItemBuilder unbreakable() {
        item.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        return this;
    }

    /** The model from a resource pack ({@code namespace:path}). */
    public ItemBuilder model(String model) {
        item.set(DataComponents.ITEM_MODEL, Identifier.parse(model));
        return this;
    }

    /** Stores text on the item (custom data), read back with {@link #read}. */
    public ItemBuilder data(String key, String value) {
        CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> tag.putString(key, value));
        return this;
    }

    /** Any other data component. */
    public <T> ItemBuilder with(DataComponentType<T> type, T value) {
        item.set(type, value);
        return this;
    }

    public ItemStack build() {
        ItemStack out = item.copy();
        if (!lore.isEmpty()) {
            out.set(DataComponents.LORE, new ItemLore(List.copyOf(lore)));
        }
        return out;
    }

    /** The text {@link #data} stored, or null. */
    public static String read(ItemStack item, String key) {
        return item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString(key).orElse(null);
    }

    private static Component plain(Component c) {
        return c.copy().withStyle(Style.EMPTY.withItalic(false).applyTo(c.getStyle()));
    }

    /** {@code Component.literal(text).withStyle(formats)}, the usual way to colour a name. */
    public static Component text(String text, ChatFormatting... formats) {
        return Component.literal(text).withStyle(formats);
    }
}
