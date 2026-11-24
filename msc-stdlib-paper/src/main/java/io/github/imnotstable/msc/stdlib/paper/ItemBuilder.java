package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Items in one expression. Strings are MiniMessage without the default italics.
 *
 * <pre>{@code
 * ItemStack sword = ItemBuilder.of(Material.DIAMOND_SWORD).name("<red>Slayer").lore("<gray>Kills: <k>", "k", 0)
 *         .enchant(Enchantment.SHARPNESS, 5).unbreakable().data(KEY, PersistentDataType.STRING, "slayer").build();
 * }</pre>
 */
public final class ItemBuilder {
    private final ItemStack item;
    private final List<Consumer<ItemMeta>> edits = new ArrayList<>();

    private ItemBuilder(ItemStack item) {
        this.item = item;
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(ItemStack.of(material));
    }

    /** A copy of {@code item} to change. */
    public static ItemBuilder from(ItemStack item) {
        return new ItemBuilder(item.clone());
    }

    /** A player head with {@code owner}'s skin. */
    public static ItemBuilder head(OfflinePlayer owner) {
        return of(Material.PLAYER_HEAD).meta(m -> ((SkullMeta) m).setOwningPlayer(owner));
    }

    /** A glass pane (or any item) with no name and no tooltip, for menu borders: {@code pane(Material.BLACK_STAINED_GLASS_PANE)}. */
    public static ItemStack pane(Material material) {
        return of(material).name(Component.empty()).hideTooltip().build();
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(String miniMessage, Object... placeholders) {
        return name(Text.item(miniMessage, placeholders));
    }

    public ItemBuilder name(Component name) {
        Component n = name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        return meta(m -> m.customName(n));
    }

    /** One lore line, with placeholders as in {@link Text#mm}. */
    public ItemBuilder lore(String miniMessage, Object... placeholders) {
        Component line = Text.item(miniMessage, placeholders);
        return meta(m -> {
            List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
            lore.add(line);
            m.lore(lore);
        });
    }

    /** Replaces the lore. */
    public ItemBuilder lore(List<Component> lines) {
        List<Component> l = lines.stream()
                .map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList();
        return meta(m -> m.lore(l));
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        return meta(m -> m.addEnchant(enchantment, level, true));
    }

    /** The enchantment shimmer without an enchantment. */
    public ItemBuilder glow() {
        return meta(m -> m.setEnchantmentGlintOverride(true));
    }

    public ItemBuilder unbreakable() {
        return meta(m -> m.setUnbreakable(true));
    }

    public ItemBuilder flags(ItemFlag... flags) {
        return meta(m -> m.addItemFlags(flags));
    }

    public ItemBuilder hideTooltip() {
        return meta(m -> m.setHideTooltip(true));
    }

    /** The model from a resource pack ({@code namespace:path}). */
    public ItemBuilder model(NamespacedKey model) {
        return meta(m -> m.setItemModel(model));
    }

    /** Stores a value on the item (persistent data), to recognise it later with {@link #read}. */
    public <P, C> ItemBuilder data(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        return meta(m -> m.getPersistentDataContainer().set(key, type, value));
    }

    /** Any other change to the meta. */
    public ItemBuilder meta(Consumer<ItemMeta> edit) {
        edits.add(edit);
        return this;
    }

    public ItemStack build() {
        ItemStack out = item.clone();
        if (!edits.isEmpty()) {
            out.editMeta(m -> edits.forEach(e -> e.accept(m)));
        }
        return out;
    }

    /** The value {@link #data} stored on {@code item}, or null. */
    public static <P, C> C read(ItemStack item, NamespacedKey key, PersistentDataType<P, C> type) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, type);
    }
}
