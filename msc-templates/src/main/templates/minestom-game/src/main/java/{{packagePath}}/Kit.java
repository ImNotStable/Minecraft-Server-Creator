package {{packageName}};

import java.util.List;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/** The items a player gets on join and on {@code /game kit}. */
public record Kit(List<ItemStack> items) {
    /** The most of one item a kit entry gives: a full inventory, 36 stacks of 64. The same in every game template. */
    public static final int MAX_AMOUNT = 36 * 64;

    public Kit {
        items = List.copyOf(items);
    }

    public static Kit of(ItemStack... items) {
        return new Kit(List.of(items));
    }

    /** Shorthand for one kit entry: {@code Kit.item(Material.BREAD, 8)}. */
    public static ItemStack item(Material material, int amount) {
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("kit: amount must be a whole number from 1 to " + MAX_AMOUNT + " for "
                    + material.key().asString() + ", got " + amount);
        }
        return ItemStack.of(material, amount);
    }

    /** Clears the player's inventory and gives the kit. */
    void give(Player player) {
        player.getInventory().clear();
        for (ItemStack item : items) {
            player.getInventory().addItemStack(item);
        }
    }
}
