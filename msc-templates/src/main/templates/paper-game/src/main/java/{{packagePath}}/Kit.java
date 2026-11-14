package {{packageName}};

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The items a player gets on join and on {@code /game kit}, read from config as {@code item} or {@code item:amount}. */
public record Kit(List<Entry> items) {
    /** The most of one item a kit entry gives: a full inventory, 36 stacks of 64. */
    public static final int MAX_AMOUNT = 36 * 64;

    public record Entry(Material material, int amount) {
        public Entry {
            if (amount < 1 || amount > MAX_AMOUNT) {
                throw new IllegalArgumentException("kit: amount must be a whole number from 1 to " + MAX_AMOUNT + " for "
                        + material + ", got " + amount);
            }
        }
    }

    public Kit {
        items = List.copyOf(items);
    }

    /** Clears the player's inventory and gives the kit. */
    void give(Player player) {
        player.getInventory().clear();
        for (Entry e : items) {
            player.getInventory().addItem(new ItemStack(e.material(), e.amount()));
        }
    }
}
